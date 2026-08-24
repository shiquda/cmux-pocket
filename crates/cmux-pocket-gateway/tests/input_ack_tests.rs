use async_trait::async_trait;
use cmux_pocket_cmux::error::CmuxError;
use cmux_pocket_cmux::events::CmuxEventStream;
use cmux_pocket_cmux::{CmuxBackend, MockCmuxBackend};
use cmux_pocket_gateway::CmuxGateway;
use cmux_pocket_protocol::{BackendHealth, RenderGridFrame, SurfaceInfo, WorkspaceInfo};
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tokio_tungstenite::connect_async;
use tokio_tungstenite::tungstenite::Message;

async fn spawn_test_gateway(backend: Arc<dyn CmuxBackend>, token: &str) -> (Arc<CmuxGateway>, u16) {
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
async fn test_ack_precedes_refresh() {
    let token = "test-token";
    let backend = Arc::new(MockCmuxBackend::default());
    let (gw, port) = spawn_test_gateway(backend, token).await;
    let mut ws = authenticate_ws(port, token).await;

    // Focus surface
    let focus_req = json!({
        "id": "req-focus",
        "method": "mobile.surface.focus",
        "params": {
            "surface_id": "surf-main-1",
        }
    });
    ws.send(Message::Text(focus_req.to_string())).await.unwrap();
    let _ = ws.next().await.unwrap().unwrap();

    // Subscribe to render grid
    let sub_req = json!({
        "id": "req-sub",
        "method": "mobile.events.subscribe",
        "params": {
            "topics": ["terminal.render_grid"],
        }
    });
    ws.send(Message::Text(sub_req.to_string())).await.unwrap();
    let _ = ws.next().await.unwrap().unwrap(); // sub ack
    let _ = ws.next().await.unwrap().unwrap(); // initial frame

    // Send input
    let input_req = json!({
        "id": "req-input-order",
        "method": "mobile.terminal.input",
        "params": {
            "surface_id": "surf-main-1",
            "text": "help\n",
        }
    });
    ws.send(Message::Text(input_req.to_string())).await.unwrap();

    // Next message MUST be the input ACK
    let ack_msg: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(ack_msg["id"], "req-input-order");
    assert_eq!(ack_msg["result"]["status"], "ok");

    // Frame refresh arrives after ACK
    let frame_msg: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(frame_msg["event"], "terminal.render_grid");
    assert_eq!(frame_msg["data"]["surface_id"], "surf-main-1");

    gw.stop().await;
}

/// A wrapper backend where `send_input` can be configured to fail.
struct FailingInputBackend {
    inner: MockCmuxBackend,
    fail_input: AtomicBool,
}

impl FailingInputBackend {
    fn new() -> Self {
        Self {
            inner: MockCmuxBackend::default(),
            fail_input: AtomicBool::new(true),
        }
    }
}

#[async_trait]
impl CmuxBackend for FailingInputBackend {
    async fn ping(&self) -> Result<(), CmuxError> {
        self.inner.ping().await
    }
    async fn health(&self) -> Result<BackendHealth, CmuxError> {
        self.inner.health().await
    }
    async fn list_workspaces(&self) -> Result<Vec<WorkspaceInfo>, CmuxError> {
        self.inner.list_workspaces().await
    }
    async fn create_workspace(&self, name: &str) -> Result<WorkspaceInfo, CmuxError> {
        self.inner.create_workspace(name).await
    }
    async fn select_workspace(&self, key: &str) -> Result<(), CmuxError> {
        self.inner.select_workspace(key).await
    }
    async fn create_surface(
        &self,
        key: &str,
        title: Option<&str>,
        surf_type: Option<&str>,
    ) -> Result<SurfaceInfo, CmuxError> {
        self.inner.create_surface(key, title, surf_type).await
    }
    async fn close_surface(&self, surf_id: &str, key: Option<&str>) -> Result<bool, CmuxError> {
        self.inner.close_surface(surf_id, key).await
    }
    async fn send_input(&self, _surf_id: &str, _text: &str) -> Result<(), CmuxError> {
        if self.fail_input.load(Ordering::SeqCst) {
            Err(CmuxError::unavailable("Simulated host write failure"))
        } else {
            Ok(())
        }
    }
    async fn handle_scroll(
        &self,
        surf_id: &str,
        delta: f64,
        col: usize,
        row: usize,
    ) -> Result<RenderGridFrame, CmuxError> {
        self.inner.handle_scroll(surf_id, delta, col, row).await
    }
    async fn get_snapshot(
        &self,
        surf_id: &str,
        max_sb: usize,
    ) -> Result<RenderGridFrame, CmuxError> {
        self.inner.get_snapshot(surf_id, max_sb).await
    }
    async fn read_screen_fallback(&self, surf_id: &str) -> Result<RenderGridFrame, CmuxError> {
        self.inner.read_screen_fallback(surf_id).await
    }
    async fn list_notifications(&self) -> Result<Vec<String>, CmuxError> {
        self.inner.list_notifications().await
    }
    async fn spawn_events_stream(&self) -> Result<CmuxEventStream, CmuxError> {
        self.inner.spawn_events_stream().await
    }
}

#[tokio::test]
async fn test_host_write_failure_returns_error_no_ack() {
    let token = "test-token";
    let backend = Arc::new(FailingInputBackend::new());
    let (gw, port) = spawn_test_gateway(backend, token).await;
    let mut ws = authenticate_ws(port, token).await;

    let input_req = json!({
        "id": "req-fail-in",
        "method": "mobile.terminal.input",
        "params": {
            "surface_id": "surf-main-1",
            "text": "doomed input",
        }
    });
    ws.send(Message::Text(input_req.to_string())).await.unwrap();

    let res: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(res["id"], "req-fail-in");
    assert!(res.get("result").is_none());
    assert_eq!(res["error"]["code"], -32000);
    assert!(res["error"]["message"]
        .as_str()
        .unwrap()
        .contains("Input failed"));

    gw.stop().await;
}
