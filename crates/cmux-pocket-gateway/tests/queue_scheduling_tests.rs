use cmux_pocket_cmux::MockCmuxBackend;
use cmux_pocket_gateway::{ClientSession, CmuxGateway};
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use std::sync::Arc;
use tokio_tungstenite::connect_async;
use tokio_tungstenite::tungstenite::Message;

#[tokio::test]
async fn test_latest_full_coalescing_bounded_slot() {
    let (session, mut _control_rx) = ClientSession::new("test-session-1".to_string());
    session.set_authenticated(true);
    session.set_active_surface(Some("surf-main-1".to_string()));
    let focus_gen = session.focus_generation();

    let frame1 = json!({
        "surface_id": "surf-main-1",
        "format": "cmux.render-grid.v1",
        "state_seq": 1,
        "full": true,
    });
    let frame2 = json!({
        "surface_id": "surf-main-1",
        "format": "cmux.render-grid.v1",
        "state_seq": 2,
        "full": true,
    });
    let frame3 = json!({
        "surface_id": "surf-main-1",
        "format": "cmux.render-grid.v1",
        "state_seq": 3,
        "full": true,
    });

    assert!(session.enqueue_render_frame("surf-main-1", focus_gen, frame1));
    assert!(session.enqueue_render_frame("surf-main-1", focus_gen, frame2));
    assert!(session.enqueue_render_frame("surf-main-1", focus_gen, frame3));

    let latest = session
        .get_latest_render_frame()
        .expect("Latest slot present");
    assert_eq!(latest.surface_id, "surf-main-1");
    assert_eq!(latest.focus_generation, focus_gen);
    assert_eq!(latest.frame["state_seq"], 3);

    session.close();
}

#[tokio::test]
async fn test_delta_frame_triggers_recovery() {
    let (session, mut _control_rx) = ClientSession::new("test-session-2".to_string());
    session.set_authenticated(true);
    session.set_active_surface(Some("surf-main-1".to_string()));
    let focus_gen = session.focus_generation();

    let delta_frame = json!({
        "surface_id": "surf-main-1",
        "format": "cmux.render-grid.v1",
        "state_seq": 10,
        "full": false,
    });

    let accepted = session.enqueue_render_frame("surf-main-1", focus_gen, delta_frame);
    // Non-full delta frame returns false so caller can trigger full priority recovery
    assert!(!accepted);
    assert!(session.get_latest_render_frame().is_none());

    session.close();
}

#[tokio::test]
async fn test_focus_generation_invalidates_stale_frames() {
    let (session, mut _control_rx) = ClientSession::new("test-session-3".to_string());
    session.set_authenticated(true);
    session.set_active_surface(Some("surf-1".to_string()));
    let focus_gen_1 = session.focus_generation();

    let frame1 = json!({
        "surface_id": "surf-1",
        "format": "cmux.render-grid.v1",
        "state_seq": 1,
        "full": true,
    });
    session.enqueue_render_frame("surf-1", focus_gen_1, frame1);
    assert!(session.get_latest_render_frame().is_some());

    // Switch focus to surf-2 -> focus_generation increments and latest render frame is invalidated
    session.set_active_surface(Some("surf-2".to_string()));
    let focus_gen_2 = session.focus_generation();
    assert_ne!(focus_gen_1, focus_gen_2);
    assert!(session.get_latest_render_frame().is_none());

    // Attempting to deliver a frame for old surface or old focus generation is ignored
    let stale_frame = json!({
        "surface_id": "surf-1",
        "format": "cmux.render-grid.v1",
        "state_seq": 2,
        "full": true,
    });
    session.enqueue_render_frame("surf-1", focus_gen_1, stale_frame);
    assert!(session.get_latest_render_frame().is_none());

    session.close();
}

#[tokio::test]
async fn test_control_fifo_delivery_order() {
    let backend = Arc::new(MockCmuxBackend::default());
    let token = "test-token-fifo";
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

    // Send 5 sequential RPCs
    for i in 1..=5 {
        let req = json!({
            "id": format!("req-{}", i),
            "method": "mobile.host.status",
            "params": {}
        });
        ws.send(Message::Text(req.to_string())).await.unwrap();
    }

    // Must receive 5 responses in strict sequential order
    for i in 1..=5 {
        let msg = ws.next().await.unwrap().unwrap();
        let val: Value = serde_json::from_str(msg.to_text().unwrap()).unwrap();
        assert_eq!(val["id"], format!("req-{}", i));
    }

    gateway.stop().await;
}
