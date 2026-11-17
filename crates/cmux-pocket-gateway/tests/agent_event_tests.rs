use async_trait::async_trait;
use cmux_pocket_cmux::error::CmuxError;
use cmux_pocket_cmux::events::CmuxEventStream;
use cmux_pocket_cmux::{CmuxBackend, MockCmuxBackend};
use cmux_pocket_gateway::{CmuxGateway, CompletionForward, EventDedup, SurfaceCompletionGate};
use cmux_pocket_protocol::{BackendHealth, RenderGridFrame, SurfaceInfo, WorkspaceInfo};
use futures_util::{SinkExt, StreamExt};
use parking_lot::Mutex;
use serde_json::{json, Value};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::mpsc;
use tokio_tungstenite::connect_async;
use tokio_tungstenite::tungstenite::Message;

#[test]
fn test_agent_event_dedup() {
    let mut dedup = EventDedup::new();

    // First time seeing event -> true
    assert!(dedup.check_and_record("evt-1"));
    // Second time seeing same event -> false
    assert!(!dedup.check_and_record("evt-1"));

    assert!(dedup.check_and_record("evt-2"));
    assert!(!dedup.check_and_record("evt-2"));
    assert!(!dedup.check_and_record("evt-1"));
}

#[test]
fn test_surface_completion_gate_collapses_one_turn() {
    let mut gate = SurfaceCompletionGate::new();
    let start = Instant::now();

    // cmux publishes one Stop hook twice within the same millisecond.
    assert!(gate.accept("surface:1", start));
    assert!(!gate.accept("surface:1", start));
    assert!(!gate.accept("surface:1", start + Duration::from_millis(900)));

    // A different surface is unaffected.
    assert!(gate.accept("surface:2", start + Duration::from_millis(10)));

    // After the window the same surface may report a new turn.
    assert!(gate.accept("surface:1", start + Duration::from_secs(4)));
}

#[test]
fn test_reply_replaces_generic_without_a_second_alert() {
    let mut gate = SurfaceCompletionGate::new();
    let now = Instant::now();
    assert_eq!(
        gate.decide("surface-a", now, false, "evt-generic"),
        CompletionForward::New
    );
    assert_eq!(
        gate.decide("surface-a", now, true, "evt-reply"),
        CompletionForward::Replace {
            event_id: "evt-generic".to_string()
        }
    );
    assert_eq!(
        gate.decide("surface-a", now, false, "evt-generic-again"),
        CompletionForward::Drop
    );
    assert_eq!(
        gate.decide("surface-b", now, true, "evt-other"),
        CompletionForward::New
    );
}

struct AgentTestBackend {
    inner: MockCmuxBackend,
    event_rx: Mutex<Option<mpsc::UnboundedReceiver<Value>>>,
    notifications: Vec<String>,
    details: Vec<Value>,
}

impl AgentTestBackend {
    fn new(event_rx: mpsc::UnboundedReceiver<Value>, notifications: Vec<String>) -> Self {
        Self {
            inner: MockCmuxBackend::default(),
            event_rx: Mutex::new(Some(event_rx)),
            notifications,
            details: Vec::new(),
        }
    }

    fn with_details(event_rx: mpsc::UnboundedReceiver<Value>, details: Vec<Value>) -> Self {
        Self {
            inner: MockCmuxBackend::default(),
            event_rx: Mutex::new(Some(event_rx)),
            notifications: Vec::new(),
            details,
        }
    }
}

#[async_trait]
impl CmuxBackend for AgentTestBackend {
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
    async fn send_input(&self, surf_id: &str, text: &str) -> Result<(), CmuxError> {
        self.inner.send_input(surf_id, text).await
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
        Ok(self.notifications.clone())
    }

    async fn list_notification_details(&self) -> Result<Vec<Value>, CmuxError> {
        if self.details.is_empty() {
            return Err(CmuxError::unavailable("notification details unavailable"));
        }
        Ok(self.details.clone())
    }
    async fn spawn_events_stream(&self) -> Result<CmuxEventStream, CmuxError> {
        let rx = self.event_rx.lock().take();
        if let Some(receiver) = rx {
            Ok(CmuxEventStream::mock(receiver))
        } else {
            let (_tx, dummy_rx) = mpsc::unbounded_channel();
            Ok(CmuxEventStream::mock(dummy_rx))
        }
    }
}

#[tokio::test]
async fn test_agent_event_supervisor_and_broadcast() {
    let (event_tx, event_rx) = mpsc::unbounded_channel();
    let backend = Arc::new(AgentTestBackend::new(event_rx, vec![]));

    let token = "test-token";
    let gateway = Arc::new(CmuxGateway::new("127.0.0.1", 0, token, backend).unwrap());
    let listener = gateway.start().await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let gw_runner = gateway.clone();
    tokio::spawn(async move {
        let _ = gw_runner.run_with_listener(listener).await;
    });

    let url = format!("ws://127.0.0.1:{}", port);
    let (mut ws, _) = connect_async(&url).await.unwrap();

    // Auth
    let auth_msg = json!({"type": "auth", "token": token});
    ws.send(Message::Text(auth_msg.to_string())).await.unwrap();
    let _ = ws.next().await.unwrap().unwrap();

    // Subscribe to agent.session.completed
    let sub_req = json!({
        "id": "req-sub-agent",
        "method": "mobile.events.subscribe",
        "params": {
            "topics": ["agent.session.completed"]
        }
    });
    ws.send(Message::Text(sub_req.to_string())).await.unwrap();
    let _sub_ack = ws.next().await.unwrap().unwrap();

    // Send 1. Valid Agent Hook Event
    let event1 = json!({
        "type": "event",
        "id": "event-codex-1",
        "name": "agent.hook.Stop",
        "workspace_id": "ws-main",
        "surface_id": "surf-main-1",
        "payload": {
            "hook_event_name": "Stop",
            "_source": "codex"
        }
    });
    event_tx.send(event1.clone()).unwrap();

    // Send 2. Duplicate Event (must be ignored)
    event_tx.send(event1).unwrap();

    // Send 3. Another Agent Event with category turn-complete
    let event2 = json!({
        "type": "event",
        "id": "event-claude-2",
        "name": "notification.created",
        "workspace_id": "ws-main",
        "surface_id": "surf-main-2",
        "agent": {
            "kind": "claude",
            "category": "turn-complete"
        },
        "payload": {}
    });
    event_tx.send(event2).unwrap();

    // Receive first broadcast
    let bcast1: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(bcast1["event"], "agent.session.completed");
    assert_eq!(bcast1["data"]["event_id"], "event-codex-1");
    assert_eq!(bcast1["data"]["surface_id"], "surf-main-1");
    assert_eq!(bcast1["data"]["agent_kind"], "codex");

    // Receive second broadcast (event2, not the duplicate event1)
    let bcast2: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(bcast2["event"], "agent.session.completed");
    assert_eq!(bcast2["data"]["event_id"], "event-claude-2");
    assert_eq!(bcast2["data"]["surface_id"], "surf-main-2");
    assert_eq!(bcast2["data"]["agent_kind"], "claude");

    gateway.stop().await;
}

#[tokio::test]
async fn test_notification_fallback_to_agent_completion() {
    let (event_tx, event_rx) = mpsc::unbounded_channel();
    // Notification record showing Complete status
    let notif_record = "0:notif-xyz|ws-main|surf-main-3|unread|Task finished||Complete|2026-08-24T00:00:00Z|pct:Task finished".to_string();
    let backend = Arc::new(AgentTestBackend::new(event_rx, vec![notif_record]));

    let token = "test-token-notif";
    let gateway = Arc::new(CmuxGateway::new("127.0.0.1", 0, token, backend).unwrap());
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

    let sub_req = json!({
        "id": "req-sub",
        "method": "mobile.events.subscribe",
        "params": {
            "topics": ["agent.session.completed"]
        }
    });
    ws.send(Message::Text(sub_req.to_string())).await.unwrap();
    let _sub_ack = ws.next().await.unwrap().unwrap();

    // Send notification.created event
    let notif_event = json!({
        "type": "event",
        "name": "notification.created",
        "surface_id": "surf-main-3",
        "workspace_id": "ws-main",
        "payload": {
            "notification_id": "notif-xyz"
        }
    });
    event_tx.send(notif_event).unwrap();

    // Gateway queries list_notifications, finds matching record with status "Complete", and emits agent.session.completed
    let bcast: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(bcast["event"], "agent.session.completed");
    assert_eq!(bcast["data"]["surface_id"], "surf-main-3");
    assert_eq!(bcast["data"]["category"], "turn-complete");

    gateway.stop().await;
}

/// cmux events identify a surface by its durable UUID; the client only knows
/// the short ref. The tree poller seeds the translation, and an unmapped UUID
/// must pass through untouched rather than be attributed to another surface.
#[tokio::test]
async fn test_completion_event_is_translated_to_surface_ref() {
    let (event_tx, event_rx) = mpsc::unbounded_channel();
    let backend = Arc::new(AgentTestBackend::new(event_rx, vec![]));
    backend.inner.set_surface_uuid("surf-main-1", "UUID-1");

    let token = "test-token-translate";
    let gateway = Arc::new(CmuxGateway::new("127.0.0.1", 0, token, backend).unwrap());
    let listener = gateway.start().await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let gw_runner = gateway.clone();
    tokio::spawn(async move {
        let _ = gw_runner.run_with_listener(listener).await;
    });

    let url = format!("ws://127.0.0.1:{}", port);
    let (mut ws, _) = connect_async(&url).await.unwrap();
    ws.send(Message::Text(
        json!({"type": "auth", "token": token}).to_string(),
    ))
    .await
    .unwrap();
    let _ = ws.next().await.unwrap().unwrap();

    ws.send(Message::Text(
        json!({
            "id": "req-sub",
            "method": "mobile.events.subscribe",
            "params": { "topics": ["workspace.tree", "agent.session.completed"] }
        })
        .to_string(),
    ))
    .await
    .unwrap();
    let _ = ws.next().await.unwrap().unwrap();

    // The first tree sync proves the identity map is populated.
    let tree_bcast: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(tree_bcast["event"], "workspace.tree");

    event_tx
        .send(json!({
            "type": "event",
            "id": "event-translate-1",
            "name": "agent.hook.Stop",
            "workspace_id": "ws-main",
            "surface_id": "UUID-1",
            "payload": { "hook_event_name": "Stop", "_source": "omp" }
        }))
        .unwrap();

    let bcast: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(bcast["event"], "agent.session.completed");
    assert_eq!(bcast["data"]["surface_id"], "surf-main-1");

    // An unknown UUID is forwarded unchanged.
    event_tx
        .send(json!({
            "type": "event",
            "id": "event-translate-2",
            "name": "agent.hook.Stop",
            "workspace_id": "ws-main",
            "surface_id": "UUID-unmapped",
            "payload": { "hook_event_name": "Stop", "_source": "omp" }
        }))
        .unwrap();

    let bcast: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(bcast["data"]["surface_id"], "UUID-unmapped");

    gateway.stop().await;
}

#[tokio::test]
async fn test_completion_uses_matching_reply_and_ignores_other_surfaces() {
    let (event_tx, event_rx) = mpsc::unbounded_channel();
    let details = vec![
        json!({
            "id": "reply-a",
            "surface_id": "surface-a",
            "workspace_id": "ws",
            "title": "OMP",
            "subtitle": "Completed in tmp",
            "body": "Ready. What are we working on?",
            "created_at": "2026-11-17T04:37:17Z"
        }),
        json!({
            "id": "generic-a",
            "surface_id": "surface-a",
            "workspace_id": "ws",
            "title": "omp",
            "subtitle": "",
            "body": "Complete",
            "created_at": "2026-11-17T04:37:17Z"
        }),
        json!({
            "id": "reply-b",
            "surface_id": "surface-b",
            "workspace_id": "ws",
            "title": "OMP",
            "subtitle": "Completed in other",
            "body": "Reply from another surface",
            "created_at": "2026-11-17T04:37:17Z"
        }),
        json!({
            "id": "waiting-a",
            "surface_id": "surface-a",
            "body": "Waiting for input",
            "subtitle": "",
            "created_at": "2026-11-17T04:37:17Z"
        }),
    ];
    let backend = Arc::new(AgentTestBackend::with_details(event_rx, details));
    let token = "test-token-reply";
    let gateway = Arc::new(CmuxGateway::new("127.0.0.1", 0, token, backend).unwrap());
    let listener = gateway.start().await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let gw_runner = gateway.clone();
    tokio::spawn(async move {
        let _ = gw_runner.run_with_listener(listener).await;
    });

    let url = format!("ws://127.0.0.1:{port}");
    let (mut ws, _) = connect_async(&url).await.unwrap();
    ws.send(Message::Text(
        json!({"type": "auth", "token": token}).to_string(),
    ))
    .await
    .unwrap();
    let _ = ws.next().await.unwrap().unwrap();
    ws.send(Message::Text(
        json!({
            "id": "sub",
            "method": "mobile.events.subscribe",
            "params": { "topics": ["agent.session.completed"] }
        })
        .to_string(),
    ))
    .await
    .unwrap();
    let _ = ws.next().await.unwrap().unwrap();

    event_tx
        .send(json!({
            "type": "event",
            "id": "stop-a",
            "name": "agent.hook.Stop",
            "occurred_at": "2026-11-17T04:37:17.269Z",
            "workspace_id": "ws",
            "surface_id": "surface-a",
            "payload": { "hook_event_name": "Stop", "_source": "omp" }
        }))
        .unwrap();
    event_tx
        .send(json!({
            "type": "event",
            "id": "generic-created",
            "name": "notification.created",
            "occurred_at": "2026-11-17T04:37:17.400Z",
            "workspace_id": "ws",
            "surface_id": "surface-a",
            "payload": { "notification_id": "generic-a" }
        }))
        .unwrap();
    event_tx
        .send(json!({
            "type": "event",
            "id": "waiting-created",
            "name": "notification.created",
            "surface_id": "surface-a",
            "payload": { "notification_id": "waiting-a" }
        }))
        .unwrap();

    let first: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(first["event"], "agent.session.completed");
    assert_eq!(first["data"]["surface_id"], "surface-a");
    assert_eq!(first["data"]["body"], "Ready. What are we working on?");
    assert!(first["data"].get("content_update").is_none());

    event_tx
        .send(json!({
            "type": "event",
            "id": "stop-b",
            "name": "agent.hook.Stop",
            "occurred_at": "2026-11-17T04:37:17.269Z",
            "workspace_id": "ws",
            "surface_id": "surface-b",
            "payload": { "hook_event_name": "Stop", "_source": "omp" }
        }))
        .unwrap();
    let second: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(second["data"]["surface_id"], "surface-b");
    assert_eq!(second["data"]["body"], "Reply from another surface");

    gateway.stop().await;
}
