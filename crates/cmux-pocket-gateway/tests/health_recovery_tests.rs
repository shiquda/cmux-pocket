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

struct ToggleHealthBackend {
    inner: MockCmuxBackend,
    healthy: AtomicBool,
}

impl ToggleHealthBackend {
    fn new() -> Self {
        Self {
            inner: MockCmuxBackend::default(),
            healthy: AtomicBool::new(true),
        }
    }

    fn set_healthy(&self, h: bool) {
        self.healthy.store(h, Ordering::SeqCst);
    }
}

#[async_trait]
impl CmuxBackend for ToggleHealthBackend {
    async fn ping(&self) -> Result<(), CmuxError> {
        if self.healthy.load(Ordering::SeqCst) {
            self.inner.ping().await
        } else {
            Err(CmuxError::unavailable("cmux daemon unreachable"))
        }
    }
    async fn health(&self) -> Result<BackendHealth, CmuxError> {
        if self.healthy.load(Ordering::SeqCst) {
            self.inner.health().await
        } else {
            Ok(BackendHealth::unhealthy("cmux unreachable"))
        }
    }
    async fn list_workspaces(&self) -> Result<Vec<WorkspaceInfo>, CmuxError> {
        if self.healthy.load(Ordering::SeqCst) {
            self.inner.list_workspaces().await
        } else {
            Err(CmuxError::unavailable("cmux daemon down"))
        }
    }
    async fn create_workspace(&self, name: &str) -> Result<WorkspaceInfo, CmuxError> {
        if self.healthy.load(Ordering::SeqCst) {
            self.inner.create_workspace(name).await
        } else {
            Err(CmuxError::unavailable("cmux daemon down"))
        }
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
        if self.healthy.load(Ordering::SeqCst) {
            self.inner.create_surface(key, title, surf_type).await
        } else {
            Err(CmuxError::unavailable("cmux daemon down"))
        }
    }
    async fn close_surface(&self, surf_id: &str, key: Option<&str>) -> Result<bool, CmuxError> {
        if self.healthy.load(Ordering::SeqCst) {
            self.inner.close_surface(surf_id, key).await
        } else {
            Err(CmuxError::unavailable("cmux daemon down"))
        }
    }
    async fn send_input(&self, surf_id: &str, text: &str) -> Result<(), CmuxError> {
        if self.healthy.load(Ordering::SeqCst) {
            self.inner.send_input(surf_id, text).await
        } else {
            Err(CmuxError::unavailable("cmux daemon down"))
        }
    }
    async fn handle_scroll(
        &self,
        surf_id: &str,
        delta: f64,
        col: usize,
        row: usize,
    ) -> Result<RenderGridFrame, CmuxError> {
        if self.healthy.load(Ordering::SeqCst) {
            self.inner.handle_scroll(surf_id, delta, col, row).await
        } else {
            Err(CmuxError::unavailable("cmux daemon down"))
        }
    }
    async fn get_snapshot(
        &self,
        surf_id: &str,
        max_sb: usize,
    ) -> Result<RenderGridFrame, CmuxError> {
        if self.healthy.load(Ordering::SeqCst) {
            self.inner.get_snapshot(surf_id, max_sb).await
        } else {
            Err(CmuxError::unavailable("cmux daemon down"))
        }
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
async fn test_unhealthy_backend_state_and_recovery() {
    let token = "test-token";
    let toggle_backend = Arc::new(ToggleHealthBackend::new());
    let gateway =
        Arc::new(CmuxGateway::new("127.0.0.1", 0, token, toggle_backend.clone()).unwrap());
    let listener = gateway.start().await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let gw_runner = gateway.clone();
    tokio::spawn(async move {
        let _ = gw_runner.run_with_listener(listener).await;
    });

    let url = format!("ws://127.0.0.1:{}", port);
    let (mut ws, _) = connect_async(&url).await.unwrap();

    let auth_msg = json!({"type": "auth", "token": token});
    ws.send(Message::Text(auth_msg.to_string())).await.unwrap();
    let _ = ws.next().await.unwrap().unwrap();

    // 1. Initially healthy
    let req1 = json!({"id": "req-1", "method": "mobile.workspace.list", "params": {}});
    ws.send(Message::Text(req1.to_string())).await.unwrap();
    let res1: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(res1["id"], "req-1");
    assert!(res1["result"]["workspaces"].is_array());
    assert!(gateway.health.is_healthy());

    // 2. Make backend unhealthy
    toggle_backend.set_healthy(false);

    let req2 = json!({"id": "req-2", "method": "mobile.workspace.list", "params": {}});
    ws.send(Message::Text(req2.to_string())).await.unwrap();
    let res2: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(res2["id"], "req-2");
    assert!(res2.get("result").is_none());
    assert_eq!(res2["error"]["code"], -32000);
    assert!(res2["error"]["message"]
        .as_str()
        .unwrap()
        .contains("Backend unavailable"));
    assert!(gateway.health.current().is_unhealthy());

    // 3. Recover backend
    toggle_backend.set_healthy(true);

    let req3 = json!({"id": "req-3", "method": "mobile.workspace.list", "params": {}});
    ws.send(Message::Text(req3.to_string())).await.unwrap();
    let res3: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(res3["id"], "req-3");
    assert!(res3["result"]["workspaces"].is_array());
    assert!(gateway.health.is_healthy());

    gateway.stop().await;
}
