use cmux_pocket_protocol::*;
use serde_json::json;

#[test]
fn test_workspace_create_mutation_id_echo() {
    let raw_params = json!({
        "name": "Project Workspace",
        "mutation_id": "mut-ws-101",
        "custom_tag": "urgent"
    });
    let params: WorkspaceCreateParams = serde_json::from_value(raw_params).unwrap();
    assert_eq!(params.name, "Project Workspace");
    assert_eq!(params.mutation_id.as_deref(), Some("mut-ws-101"));
    assert_eq!(params.extra.get("custom_tag").unwrap(), "urgent");

    let ws = WorkspaceInfo::new("ws-101", "Project Workspace");
    let resp = WorkspaceCreateResponse::ok(ws, params.mutation_id);
    let val = serde_json::to_value(&resp).unwrap();
    assert_eq!(val["status"], "ok");
    assert_eq!(val["mutation_id"], "mut-ws-101");
    assert_eq!(val["workspace"]["id"], "ws-101");
}

#[test]
fn test_workspace_select_mutation_and_target_key() {
    let params = WorkspaceSelectParams {
        workspace_key: Some("key-1".to_string()),
        workspace_id: None,
        mutation_id: Some("mut-sel-1".to_string()),
        extra: serde_json::Map::new(),
    };
    assert_eq!(params.target_workspace_key(), Some("key-1"));

    // Fallback to workspace_id if workspace_key is absent
    let params_fallback = WorkspaceSelectParams {
        workspace_key: None,
        workspace_id: Some("ws-fallback".to_string()),
        mutation_id: Some("mut-sel-2".to_string()),
        extra: serde_json::Map::new(),
    };
    assert_eq!(params_fallback.target_workspace_key(), Some("ws-fallback"));

    let resp =
        WorkspaceSelectResponse::ok(Some("key-1".to_string()), Some("mut-sel-1".to_string()));
    let val = serde_json::to_value(&resp).unwrap();
    assert_eq!(val["status"], "ok");
    assert_eq!(val["workspace_key"], "key-1");
    assert_eq!(val["host_focus_moved"], false);
    assert_eq!(val["mutation_id"], "mut-sel-1");
}

#[test]
fn test_surface_create_and_close_mutation_id() {
    // 1. Create Surface
    let surf = SurfaceInfo::with_title("surface:99", "custom tab");
    let resp = SurfaceCreateResponse::ok(surf, Some("mut-surf-create".to_string()));
    let val = serde_json::to_value(&resp).unwrap();
    assert_eq!(val["status"], "ok");
    assert_eq!(val["mutation_id"], "mut-surf-create");
    assert_eq!(val["surface"]["id"], "surface:99");

    // 2. Close Surface
    let close_resp = SurfaceCloseResponse::ok("surface:99", Some("mut-surf-close".to_string()));
    let close_val = serde_json::to_value(&close_resp).unwrap();
    assert_eq!(close_val["status"], "ok");
    assert_eq!(close_val["surface_id"], "surface:99");
    assert_eq!(close_val["mutation_id"], "mut-surf-close");
}

#[test]
fn test_terminal_input_and_scroll() {
    // Input
    let input_params = TerminalInputParams {
        surface_id: Some("surface:1".to_string()),
        text: "cargo test\n".to_string(),
        extra: serde_json::Map::new(),
    };
    let input_val = serde_json::to_value(&input_params).unwrap();
    assert_eq!(input_val["surface_id"], "surface:1");
    assert_eq!(input_val["text"], "cargo test\n");

    let input_resp = TerminalInputResponse::ok("surface:1");
    let resp_val = serde_json::to_value(&input_resp).unwrap();
    assert_eq!(resp_val["status"], "ok");
    assert_eq!(resp_val["surface_id"], "surface:1");

    // Scroll
    let scroll_params = TerminalScrollParams {
        surface_id: Some("surface:1".to_string()),
        delta_lines: -5.0,
        col: 10,
        row: 5,
        extra: serde_json::Map::new(),
    };
    let scroll_val = serde_json::to_value(&scroll_params).unwrap();
    assert_eq!(scroll_val["delta_lines"], -5.0);

    let scroll_resp = TerminalScrollResponse::ok("surface:1");
    assert_eq!(scroll_resp.status, "ok");
}

#[test]
fn test_terminal_viewport_and_host_status() {
    let vp_resp = TerminalViewportResponse::new(120, 40);
    let val = serde_json::to_value(&vp_resp).unwrap();
    assert_eq!(val["accepted"], true);
    assert_eq!(val["columns"], 120);
    assert_eq!(val["rows"], 40);
    assert_eq!(val["geometry_owner"], false);

    let host_resp = HostStatusResponse::new("MacBook Pro M1", "2.0.0");
    let host_val = serde_json::to_value(&host_resp).unwrap();
    assert_eq!(host_val["mac_display_name"], "MacBook Pro M1");
    assert_eq!(host_val["mac_app_version"], "2.0.0");
    assert!(host_val["capabilities"].as_array().unwrap().len() >= 6);
}
