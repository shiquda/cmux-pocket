use async_trait::async_trait;
use cmux_pocket_cmux::MockCmuxBackend;
use cmux_pocket_gateway::{CmuxGateway, HostAppStatus, HostController};
use cmux_pocket_protocol::CAP_HOST_WAKE;
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use tokio_tungstenite::connect_async;
use tokio_tungstenite::tungstenite::Message;

/// Host controller that records wake calls instead of launching anything.
struct FakeHostController {
    installed: bool,
    can_wake: bool,
    wake_calls: AtomicUsize,
}

#[async_trait]
impl HostController for FakeHostController {
    async fn status(&self) -> HostAppStatus {
        HostAppStatus {
            installed: self.installed,
            can_wake: self.can_wake,
        }
    }

    async fn wake_cmux(&self) -> Result<(), String> {
        self.wake_calls.fetch_add(1, Ordering::SeqCst);
        Ok(())
    }
}

async fn spawn_gateway(
    backend: Arc<MockCmuxBackend>,
    host: Arc<FakeHostController>,
) -> (Arc<CmuxGateway>, u16) {
    let gateway = Arc::new(
        CmuxGateway::new("127.0.0.1", 0, "test-token", backend)
            .unwrap()
            .with_host_controller(host),
    );
    let listener = gateway.start().await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let runner = gateway.clone();
    tokio::spawn(async move {
        let _ = runner.run_with_listener(listener).await;
    });
    (gateway, port)
}

async fn authenticated_ws(port: u16) -> TestSocket {
    let (mut ws, _) = connect_async(format!("ws://127.0.0.1:{port}"))
        .await
        .expect("connect");
    ws.send(Message::Text(
        json!({"type": "auth", "token": "test-token"}).to_string(),
    ))
    .await
    .unwrap();
    let auth = ws.next().await.unwrap().unwrap();
    let parsed: Value = serde_json::from_str(auth.to_text().unwrap()).unwrap();
    assert_eq!(parsed["type"], "auth_ok");
    ws
}

type TestSocket =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

async fn subscribe_tree(ws: &mut TestSocket) {
    let req = json!({
        "id": "sub-1",
        "method": "mobile.events.subscribe",
        "params": { "topics": ["workspace.tree"] }
    });
    ws.send(Message::Text(req.to_string())).await.unwrap();
    let _ = ws.next().await.unwrap().unwrap();
}

#[tokio::test]
async fn host_status_reports_versions_and_cmux_state() {
    let backend = Arc::new(MockCmuxBackend::default());
    backend.set_offline(true);
    let host = Arc::new(FakeHostController {
        installed: true,
        can_wake: true,
        wake_calls: AtomicUsize::new(0),
    });
    let (gateway, port) = spawn_gateway(backend, host).await;
    let mut ws = authenticated_ws(port).await;

    ws.send(Message::Text(
        json!({"id": "status", "method": "mobile.host.status", "params": {}}).to_string(),
    ))
    .await
    .unwrap();
    let msg = ws.next().await.unwrap().unwrap();
    let parsed: Value = serde_json::from_str(msg.to_text().unwrap()).unwrap();
    let result = &parsed["result"];

    assert_eq!(result["gateway_version"], env!("CARGO_PKG_VERSION"));
    assert_eq!(result["mac_app_version"], env!("CARGO_PKG_VERSION"));
    assert_eq!(result["protocol_version"], "2.0.0");
    assert_eq!(result["cmux_status"]["installed"], true);
    assert_eq!(result["cmux_status"]["running"], false);
    assert_eq!(result["cmux_status"]["can_wake"], true);
    let caps = result["capabilities"].as_array().unwrap();
    assert!(caps.iter().any(|cap| cap == CAP_HOST_WAKE));

    gateway.stop().await;
}

#[tokio::test]
async fn wake_is_rejected_and_capability_hidden_when_host_cannot_launch() {
    let backend = Arc::new(MockCmuxBackend::default());
    let host = Arc::new(FakeHostController {
        installed: false,
        can_wake: false,
        wake_calls: AtomicUsize::new(0),
    });
    let (gateway, port) = spawn_gateway(backend, host.clone()).await;
    let mut ws = authenticated_ws(port).await;

    ws.send(Message::Text(
        json!({"id": "status", "method": "mobile.host.status", "params": {}}).to_string(),
    ))
    .await
    .unwrap();
    let msg = ws.next().await.unwrap().unwrap();
    let parsed: Value = serde_json::from_str(msg.to_text().unwrap()).unwrap();
    let caps = parsed["result"]["capabilities"].as_array().unwrap();
    assert!(!caps.iter().any(|cap| cap == CAP_HOST_WAKE));

    ws.send(Message::Text(
        json!({"id": "wake", "method": "mobile.host.wake_cmux", "params": {"background": true}})
            .to_string(),
    ))
    .await
    .unwrap();
    let msg = ws.next().await.unwrap().unwrap();
    let parsed: Value = serde_json::from_str(msg.to_text().unwrap()).unwrap();
    assert!(parsed["error"].is_object());
    assert_eq!(host.wake_calls.load(Ordering::SeqCst), 0);

    gateway.stop().await;
}

#[tokio::test]
async fn wake_broadcasts_workspace_tree_once_cmux_becomes_ready() {
    let backend = Arc::new(MockCmuxBackend::default());
    backend.set_offline(true);
    let host = Arc::new(FakeHostController {
        installed: true,
        can_wake: true,
        wake_calls: AtomicUsize::new(0),
    });
    let (gateway, port) = spawn_gateway(backend.clone(), host.clone()).await;
    let mut ws = authenticated_ws(port).await;
    subscribe_tree(&mut ws).await;

    ws.send(Message::Text(
        json!({"id": "wake", "method": "mobile.host.wake_cmux", "params": {"background": true}})
            .to_string(),
    ))
    .await
    .unwrap();
    let response = ws.next().await.unwrap().unwrap();
    let parsed: Value = serde_json::from_str(response.to_text().unwrap()).unwrap();
    assert_eq!(parsed["result"]["status"], "launching");
    assert_eq!(host.wake_calls.load(Ordering::SeqCst), 1);

    // Simulate the app finishing its cold start shortly after the launch request.
    backend.set_offline(false);

    let event = tokio::time::timeout(std::time::Duration::from_secs(5), ws.next())
        .await
        .expect("wake watch must broadcast a tree update")
        .unwrap()
        .unwrap();
    let parsed: Value = serde_json::from_str(event.to_text().unwrap()).unwrap();
    assert_eq!(parsed["event"], "workspace.tree");
    assert_eq!(parsed["data"]["action"], "sync");
    assert!(!parsed["data"]["workspaces"].as_array().unwrap().is_empty());

    gateway.stop().await;
}
