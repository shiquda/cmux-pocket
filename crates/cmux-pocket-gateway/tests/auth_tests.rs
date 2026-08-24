use cmux_pocket_cmux::MockCmuxBackend;
use cmux_pocket_gateway::{constant_time_token_eq, verify_token, CmuxGateway};
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use std::sync::Arc;
use tokio_tungstenite::connect_async;
use tokio_tungstenite::tungstenite::protocol::frame::coding::CloseCode;
use tokio_tungstenite::tungstenite::Message;

async fn spawn_test_gateway(token: &str) -> (Arc<CmuxGateway>, u16) {
    let backend = Arc::new(MockCmuxBackend::default());
    let gateway = Arc::new(CmuxGateway::new("127.0.0.1", 0, token, backend).expect("Gateway init"));
    let listener = gateway.start().await.expect("Gateway start");
    let port = listener.local_addr().expect("Local addr").port();
    let gw_runner = gateway.clone();
    tokio::spawn(async move {
        let _ = gw_runner.run_with_listener(listener).await;
    });
    (gateway, port)
}

#[tokio::test]
async fn test_auth_success() {
    let token = "valid-secret-token-12345";
    let (gw, port) = spawn_test_gateway(token).await;

    let url = format!("ws://127.0.0.1:{}", port);
    let (mut ws, _) = connect_async(&url).await.expect("Connect WebSocket");

    let auth_msg = json!({
        "type": "auth",
        "token": token,
        "client_id": "test-client-1",
    });

    ws.send(Message::Text(auth_msg.to_string()))
        .await
        .expect("Send auth message");

    let msg = ws
        .next()
        .await
        .expect("Receive message")
        .expect("Valid msg");
    let text = msg.to_text().expect("Text message");
    let parsed: Value = serde_json::from_str(text).expect("Valid JSON");

    assert_eq!(parsed.get("type").and_then(|v| v.as_str()), Some("auth_ok"));
    assert_eq!(
        parsed.get("server_version").and_then(|v| v.as_str()),
        Some("2.0.0")
    );
    assert!(parsed.get("session_id").is_some());

    let caps = parsed
        .get("capabilities")
        .and_then(|v| v.as_array())
        .expect("Caps array");
    let cap_strs: Vec<&str> = caps.iter().filter_map(|v| v.as_str()).collect();
    assert!(cap_strs.contains(&"terminal.render_grid.v1"));
    assert!(cap_strs.contains(&"terminal.input.ordered.v1"));
    assert!(cap_strs.contains(&"workspace.changes.v1"));
    assert!(cap_strs.contains(&"events.v1"));
    assert!(cap_strs.contains(&"client_focus.v1"));
    assert!(cap_strs.contains(&"multi_surface.v1"));

    gw.stop().await;
}

#[tokio::test]
async fn test_auth_invalid_token_ordering() {
    let token = "correct-token";
    let (gw, port) = spawn_test_gateway(token).await;

    let url = format!("ws://127.0.0.1:{}", port);
    let (mut ws, _) = connect_async(&url).await.expect("Connect WebSocket");

    let bad_auth = json!({
        "type": "auth",
        "token": "wrong-token",
    });

    ws.send(Message::Text(bad_auth.to_string()))
        .await
        .expect("Send bad auth");

    // 1. First frame received must be auth_error
    let msg = ws
        .next()
        .await
        .expect("Receive message")
        .expect("Valid msg");
    let text = msg.to_text().expect("Text message");
    let parsed: Value = serde_json::from_str(text).expect("Valid JSON");

    assert_eq!(
        parsed.get("type").and_then(|v| v.as_str()),
        Some("auth_error")
    );
    assert_eq!(
        parsed.get("reason").and_then(|v| v.as_str()),
        Some("invalid_token")
    );

    // 2. Next event must be connection close with code 1008 (Policy)
    let next_msg = ws.next().await;
    match next_msg {
        Some(Ok(Message::Close(Some(frame)))) => {
            assert_eq!(frame.code, CloseCode::Policy);
        }
        Some(Ok(Message::Close(None))) | None | Some(Err(_)) => {
            // Connection closed
        }
        other => panic!("Expected connection close or Close frame, got: {:?}", other),
    }

    gw.stop().await;
}

#[tokio::test]
async fn test_auth_unauthenticated_first_frame() {
    let token = "valid-token";
    let (gw, port) = spawn_test_gateway(token).await;

    let url = format!("ws://127.0.0.1:{}", port);
    let (mut ws, _) = connect_async(&url).await.expect("Connect WebSocket");

    // Send RPC method before authenticating
    let rpc_msg = json!({
        "id": "req-1",
        "method": "mobile.host.status",
        "params": {}
    });

    ws.send(Message::Text(rpc_msg.to_string()))
        .await
        .expect("Send RPC before auth");

    // 1. First frame received must be auth_error unauthenticated
    let msg = ws
        .next()
        .await
        .expect("Receive message")
        .expect("Valid msg");
    let text = msg.to_text().expect("Text message");
    let parsed: Value = serde_json::from_str(text).expect("Valid JSON");

    assert_eq!(
        parsed.get("type").and_then(|v| v.as_str()),
        Some("auth_error")
    );
    assert_eq!(
        parsed.get("reason").and_then(|v| v.as_str()),
        Some("unauthenticated")
    );

    // 2. Next event must be close
    let next_msg = ws.next().await;
    match next_msg {
        Some(Ok(Message::Close(Some(frame)))) => {
            assert_eq!(frame.code, CloseCode::Policy);
        }
        Some(Ok(Message::Close(None))) | None | Some(Err(_)) => {}
        other => panic!("Expected connection close or Close frame, got: {:?}", other),
    }

    gw.stop().await;
}

#[test]
fn test_constant_time_token_equality() {
    assert!(constant_time_token_eq("secret123", "secret123"));
    assert!(!constant_time_token_eq("secret123", "secret124"));
    assert!(!constant_time_token_eq("secret123", "secret1234"));
    assert!(!constant_time_token_eq("secret1234", "secret123"));
    assert!(!constant_time_token_eq("", "secret"));
    assert!(!constant_time_token_eq("secret", ""));
    assert!(constant_time_token_eq("", ""));

    assert!(verify_token("tok", "tok"));
    assert!(!verify_token("tok", "wrong"));
    assert!(!verify_token("", "tok"));
    assert!(!verify_token("tok", ""));
}

#[tokio::test]
async fn test_loopback_enforcement() {
    let backend = Arc::new(MockCmuxBackend::default());
    // Reject 0.0.0.0
    let err_all = CmuxGateway::new("0.0.0.0", 8088, "token", backend.clone());
    assert!(err_all.is_err());

    // Reject public IP
    let err_pub = CmuxGateway::new("192.168.1.100", 8088, "token", backend.clone());
    assert!(err_pub.is_err());

    // Reject empty token
    let err_empty_tok = CmuxGateway::new("127.0.0.1", 8088, "", backend.clone());
    assert!(err_empty_tok.is_err());

    // Accept loopbacks
    assert!(CmuxGateway::new("127.0.0.1", 8088, "token", backend.clone()).is_ok());
    assert!(CmuxGateway::new("localhost", 8088, "token", backend.clone()).is_ok());
    assert!(CmuxGateway::new("::1", 8088, "token", backend.clone()).is_ok());
}
