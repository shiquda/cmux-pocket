use cmux_pocket_cmux::{CmuxBackend, MockCmuxBackend, MockTerminalSession};
use cmux_pocket_protocol::RENDER_GRID_FORMAT_V1;

#[tokio::test]
async fn test_mock_backend_initialization_and_health() {
    let backend = MockCmuxBackend::new();

    assert!(backend.ping().await.is_ok());
    let health = backend.health().await.unwrap();
    assert!(health.is_healthy());

    let workspaces = backend.list_workspaces().await.unwrap();
    assert_eq!(workspaces.len(), 3);
    assert_eq!(workspaces[0].id, "ws-main");
    assert!(workspaces[0].active_on_host);
    assert_eq!(workspaces[0].surfaces.len(), 3);
    assert_eq!(workspaces[1].id, "ws-android");
    assert!(!workspaces[1].active_on_host);
    assert_eq!(workspaces[2].id, "ws-exp");
}

#[tokio::test]
async fn test_mock_workspace_create_and_select() {
    let backend = MockCmuxBackend::new();

    let new_ws = backend.create_workspace("feature-branch").await.unwrap();
    assert_eq!(new_ws.name, "feature-branch");
    assert!(!new_ws.active_on_host);

    let list_after = backend.list_workspaces().await.unwrap();
    assert_eq!(list_after.len(), 4);

    backend.select_workspace(&new_ws.id).await.unwrap();

    let list_selected = backend.list_workspaces().await.unwrap();
    let selected = list_selected.iter().find(|w| w.id == new_ws.id).unwrap();
    assert!(selected.active_on_host);

    let old_main = list_selected.iter().find(|w| w.id == "ws-main").unwrap();
    assert!(!old_main.active_on_host);
}

#[tokio::test]
async fn test_mock_surface_lifecycle() {
    let backend = MockCmuxBackend::new();

    let new_surf = backend
        .create_surface("ws-main", Some("my-tab"), Some("terminal"))
        .await
        .unwrap();
    assert_eq!(new_surf.title.as_deref(), Some("my-tab"));
    assert_eq!(new_surf.surface_type, "terminal");

    let workspaces = backend.list_workspaces().await.unwrap();
    let main_ws = workspaces.iter().find(|w| w.id == "ws-main").unwrap();
    assert_eq!(main_ws.surfaces.len(), 4);

    let closed = backend.close_surface(&new_surf.id, None).await.unwrap();
    assert!(closed);

    let workspaces_after = backend.list_workspaces().await.unwrap();
    let main_ws_after = workspaces_after.iter().find(|w| w.id == "ws-main").unwrap();
    assert_eq!(main_ws_after.surfaces.len(), 3);

    // Closing non-existent surface returns false
    let closed_non_existent = backend
        .close_surface("surface:non-existent", None)
        .await
        .unwrap();
    assert!(!closed_non_existent);
}

#[tokio::test]
async fn test_mock_terminal_session_input_and_snapshots() {
    let mut session = MockTerminalSession::new("surf-test", "bash");
    let initial_frame = session.get_full_snapshot(0);

    assert_eq!(initial_frame.format, RENDER_GRID_FORMAT_V1);
    assert_eq!(initial_frame.surface_id, "surf-test");
    assert_eq!(initial_frame.state_seq, 1);
    assert!(initial_frame.full);
    assert!(initial_frame.cursor.is_some());

    // Send command "status\n"
    session.apply_input("status\n");
    let after_status = session.get_full_snapshot(0);
    assert!(after_status.state_seq > 1);
    assert!(after_status
        .row_spans
        .iter()
        .any(|s| s.text.contains("Session surf-test healthy")));

    // Send command "help\n"
    session.apply_input("help\n");
    let after_help = session.get_full_snapshot(0);
    assert!(after_help
        .row_spans
        .iter()
        .any(|s| s.text.contains("Available commands")));

    // Test with scrollback requested
    let with_sb = session.get_full_snapshot(100);
    assert_eq!(with_sb.scrollback_rows, Some(0));
    assert_eq!(with_sb.scrollback_spans, Some(vec![]));
}

#[tokio::test]
async fn test_mock_notifications() {
    let backend = MockCmuxBackend::new();

    let initial = backend.list_notifications().await.unwrap();
    assert!(initial.is_empty());

    let record = "1:notif-1|ws-main|surf-main-1|unread|Agent Done||Complete|1700000000";
    backend.add_notification(record);

    let notes = backend.list_notifications().await.unwrap();
    assert_eq!(notes.len(), 1);
    assert_eq!(notes[0], record);
}
