use cmux_pocket_cmux::MockCmuxBackend;
use cmux_pocket_gateway::CmuxGateway;
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use std::sync::Arc;
use tokio_tungstenite::connect_async;
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

async fn authenticate_ws(
    port: u16,
    token: &str,
) -> tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>> {
    let url = format!("ws://127.0.0.1:{}", port);
    let (mut ws, _) = connect_async(&url).await.expect("Connect WebSocket");

    let auth_msg = json!({
        "type": "auth",
        "token": token,
        "client_id": "test-client",
    });

    ws.send(Message::Text(auth_msg.to_string()))
        .await
        .expect("Send auth");

    let auth_res = ws.next().await.expect("Recv").expect("Valid msg");
    let text = auth_res.to_text().expect("Text");
    let parsed: Value = serde_json::from_str(text).expect("JSON");
    assert_eq!(parsed.get("type").and_then(|v| v.as_str()), Some("auth_ok"));
    ws
}

#[tokio::test]
async fn test_host_status_rpc() {
    let token = "test-token";
    let (gw, port) = spawn_test_gateway(token).await;
    let mut ws = authenticate_ws(port, token).await;

    let req = json!({
        "id": "req-status",
        "method": "mobile.host.status",
        "params": {}
    });
    ws.send(Message::Text(req.to_string())).await.unwrap();

    let res_msg = ws.next().await.unwrap().unwrap();
    let res: Value = serde_json::from_str(res_msg.to_text().unwrap()).unwrap();

    assert_eq!(res.get("id").and_then(|v| v.as_str()), Some("req-status"));
    let result = res.get("result").unwrap();
    assert_eq!(
        result.get("mac_display_name").and_then(|v| v.as_str()),
        Some("cmux Host")
    );
    assert_eq!(
        result.get("mac_app_version").and_then(|v| v.as_str()),
        Some("2.0.0")
    );
    assert!(result.get("capabilities").is_some());

    gw.stop().await;
}

#[tokio::test]
async fn test_workspace_list_and_aliases() {
    let token = "test-token";
    let (gw, port) = spawn_test_gateway(token).await;
    let mut ws = authenticate_ws(port, token).await;

    // 1. Canonical method: mobile.workspace.list
    let req1 = json!({
        "id": "req-ws-list-1",
        "method": "mobile.workspace.list",
        "params": {}
    });
    ws.send(Message::Text(req1.to_string())).await.unwrap();

    let res_msg1 = ws.next().await.unwrap().unwrap();
    let res1: Value = serde_json::from_str(res_msg1.to_text().unwrap()).unwrap();
    assert_eq!(
        res1.get("id").and_then(|v| v.as_str()),
        Some("req-ws-list-1")
    );
    let workspaces = res1["result"]["workspaces"].as_array().unwrap();
    assert!(!workspaces.is_empty());
    assert_eq!(workspaces[0]["id"], "ws-main");

    // 2. Alias method: workspace.list
    let req2 = json!({
        "id": "req-ws-list-2",
        "method": "workspace.list",
        "params": {}
    });
    ws.send(Message::Text(req2.to_string())).await.unwrap();

    let res_msg2 = ws.next().await.unwrap().unwrap();
    let res2: Value = serde_json::from_str(res_msg2.to_text().unwrap()).unwrap();
    assert_eq!(
        res2.get("id").and_then(|v| v.as_str()),
        Some("req-ws-list-2")
    );
    assert!(!res2["result"]["workspaces"].as_array().unwrap().is_empty());

    gw.stop().await;
}

#[tokio::test]
async fn test_workspace_select_local_focus() {
    let token = "test-token";
    let (gw, port) = spawn_test_gateway(token).await;
    let mut ws = authenticate_ws(port, token).await;

    let req = json!({
        "id": "req-select",
        "method": "mobile.workspace.select",
        "params": {
            "workspace_key": "ws-android",
        }
    });
    ws.send(Message::Text(req.to_string())).await.unwrap();

    let res_msg = ws.next().await.unwrap().unwrap();
    let res: Value = serde_json::from_str(res_msg.to_text().unwrap()).unwrap();
    assert_eq!(res.get("id").and_then(|v| v.as_str()), Some("req-select"));
    assert_eq!(res["result"]["status"], "ok");
    assert_eq!(res["result"]["workspace_key"], "ws-android");
    assert_eq!(res["result"]["host_focus_moved"], false);

    gw.stop().await;
}

#[tokio::test]
async fn test_terminal_aliases_and_operations() {
    let token = "test-token";
    let (gw, port) = spawn_test_gateway(token).await;
    let mut ws = authenticate_ws(port, token).await;

    // 1. terminal.input (alias for mobile.terminal.input)
    let req_in = json!({
        "id": "req-in",
        "method": "terminal.input",
        "params": {
            "surface_id": "surf-main-1",
            "text": "echo test\n",
        }
    });
    ws.send(Message::Text(req_in.to_string())).await.unwrap();
    let res_in: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(res_in["id"], "req-in");
    assert_eq!(res_in["result"]["status"], "ok");

    // 2. terminal.scroll (alias for mobile.terminal.scroll)
    let req_scroll = json!({
        "id": "req-scroll",
        "method": "terminal.scroll",
        "params": {
            "surface_id": "surf-main-1",
            "delta_lines": -5.0,
            "col": 0,
            "row": 0,
        }
    });
    ws.send(Message::Text(req_scroll.to_string()))
        .await
        .unwrap();
    let res_scroll: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(res_scroll["id"], "req-scroll");
    assert_eq!(res_scroll["result"]["status"], "ok");

    let event_scroll: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(event_scroll["event"], "terminal.render_grid");

    // 3. terminal.replay (alias for mobile.terminal.replay) with scrollback clamping
    let req_replay = json!({
        "id": "req-replay",
        "method": "terminal.replay",
        "params": {
            "surface_id": "surf-main-1",
            "max_scrollback_rows": 500,
        }
    });
    ws.send(Message::Text(req_replay.to_string()))
        .await
        .unwrap();
    let res_replay: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(res_replay["id"], "req-replay");
    assert_eq!(res_replay["result"]["surface_id"], "surf-main-1");

    let event_replay: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(event_replay["event"], "terminal.render_grid");

    // 4. terminal.viewport (alias for mobile.terminal.viewport)
    let req_vp = json!({
        "id": "req-vp",
        "method": "terminal.viewport",
        "params": {
            "viewport_columns": 120,
            "viewport_rows": 40,
        }
    });
    ws.send(Message::Text(req_vp.to_string())).await.unwrap();
    let res_vp: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(res_vp["id"], "req-vp");
    assert_eq!(res_vp["result"]["accepted"], true);
    assert_eq!(res_vp["result"]["columns"], 120);
    assert_eq!(res_vp["result"]["rows"], 40);
    assert_eq!(res_vp["result"]["geometry_owner"], false);

    gw.stop().await;
}

#[tokio::test]
async fn test_unknown_method_error() {
    let token = "test-token";
    let (gw, port) = spawn_test_gateway(token).await;
    let mut ws = authenticate_ws(port, token).await;

    let req = json!({
        "id": "req-unknown",
        "method": "mobile.nonexistent.action",
        "params": {}
    });
    ws.send(Message::Text(req.to_string())).await.unwrap();

    let res: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(res["id"], "req-unknown");
    assert!(res.get("result").is_none());
    assert_eq!(res["error"]["code"], -32601);
    assert!(res["error"]["message"]
        .as_str()
        .unwrap()
        .contains("not implemented"));

    gw.stop().await;
}
