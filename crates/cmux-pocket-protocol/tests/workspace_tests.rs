use cmux_pocket_protocol::*;
use serde_json::json;

#[test]
fn test_surface_info_serde_and_optional_omissions() {
    let surface = SurfaceInfo::with_title("surface:1", "zsh");
    let val = serde_json::to_value(&surface).unwrap();

    assert_eq!(val["id"], "surface:1");
    assert_eq!(val["type"], "terminal");
    assert_eq!(val["title"], "zsh");
    assert_eq!(val["tab_index"], 0);
    assert_eq!(val["attention"], false);
    assert_eq!(val["dead"], false);
    assert!(val.get("workspace_key").is_none());
    assert!(val.get("cwd").is_none());
    assert!(val.get("agent_state").is_none());

    // Round-trip with unknown fields
    let raw = json!({
        "id": "surface:2",
        "type": "terminal",
        "title": "node",
        "workspace_key": "ws-1",
        "pane_id": "pane:99",
        "tab_index": 2,
        "agent_state": "idle",
        "attention": true,
        "dead": false,
        "cwd": "~/projects",
        "custom_badge": "live",
        "battery_pct": 95
    });

    let parsed: SurfaceInfo = serde_json::from_value(raw).unwrap();
    assert_eq!(parsed.id, "surface:2");
    assert_eq!(parsed.pane_id.as_deref(), Some("pane:99"));
    assert_eq!(parsed.agent_state.as_deref(), Some("idle"));
    assert!(parsed.attention);
    assert_eq!(parsed.extra.get("custom_badge").unwrap(), "live");
    assert_eq!(parsed.extra.get("battery_pct").unwrap(), 95);
}

#[test]
fn test_workspace_info_serde_with_surfaces() {
    let mut ws = WorkspaceInfo::new("ws-1", "Development");
    ws.key = Some("key-1".to_string());
    ws.active_on_host = true;
    ws.cwd = Some("/Users/jim/repo".to_string());
    ws.surfaces
        .push(SurfaceInfo::with_title("surface:1", "editor"));
    ws.surfaces
        .push(SurfaceInfo::with_title("surface:2", "server"));

    let val = serde_json::to_value(&ws).unwrap();
    assert_eq!(val["id"], "ws-1");
    assert_eq!(val["key"], "key-1");
    assert_eq!(val["name"], "Development");
    assert_eq!(val["active_on_host"], true);
    assert_eq!(val["cwd"], "/Users/jim/repo");
    assert_eq!(val["surfaces"].as_array().unwrap().len(), 2);

    let parsed: WorkspaceInfo = serde_json::from_value(val).unwrap();
    assert_eq!(parsed.surfaces.len(), 2);
    assert_eq!(parsed.surfaces[0].title.as_deref(), Some("editor"));
    assert_eq!(parsed.surfaces[1].title.as_deref(), Some("server"));
}

#[test]
fn test_workspace_tree_event_variants() {
    // 1. Sync
    let ws = WorkspaceInfo::new("ws-1", "Main");
    let sync_event = WorkspaceTreeEvent::sync(vec![ws]);
    let sync_val = serde_json::to_value(&sync_event).unwrap();
    assert_eq!(sync_val["action"], "sync");
    assert_eq!(sync_val["workspaces"].as_array().unwrap().len(), 1);

    // 2. Workspace created
    let new_ws = WorkspaceInfo::new("ws-2", "New WS");
    let created_event = WorkspaceTreeEvent::workspace_created(new_ws, Some("mut-1".to_string()));
    let created_val = serde_json::to_value(&created_event).unwrap();
    assert_eq!(created_val["action"], "workspace_created");
    assert_eq!(created_val["workspace"]["name"], "New WS");
    assert_eq!(created_val["mutation_id"], "mut-1");

    // 3. Surface closed
    let closed_event = WorkspaceTreeEvent::surface_closed("surface:42", Some("mut-2".to_string()));
    let closed_val = serde_json::to_value(&closed_event).unwrap();
    assert_eq!(closed_val["action"], "surface_closed");
    assert_eq!(closed_val["surface_id"], "surface:42");
    assert_eq!(closed_val["mutation_id"], "mut-2");
}

#[test]
fn test_workspace_tree_signature_deterministic() {
    let mut ws1 = WorkspaceInfo::new("ws-1", "Alpha");
    ws1.cwd = Some("/path/a".to_string());
    ws1.surfaces.push(SurfaceInfo::with_title("surf-1", "term"));

    let mut ws2 = WorkspaceInfo::new("ws-2", "Beta");
    ws2.cwd = None;

    let sig1 = workspace_tree_signature(&[ws1.clone(), ws2.clone()]);
    let sig2 = workspace_tree_signature(&[ws1.clone(), ws2.clone()]);
    assert_eq!(sig1, sig2);

    // Changing surface title changes signature
    let mut ws1_mod = ws1.clone();
    ws1_mod.surfaces[0].title = Some("modified-term".to_string());
    let sig_mod = workspace_tree_signature(&[ws1_mod, ws2]);
    assert_ne!(sig1, sig_mod);
}
