use cmux_pocket_cmux::{extract_surface_id, parse_workspace_tree, parse_workspace_tree_value};
use serde_json::json;

#[test]
fn test_parse_multi_window_tree_json() {
    let tree_json = json!({
        "windows": [
            {
                "id": "win-1",
                "workspaces": [
                    {
                        "id": "ws-main",
                        "title": "cmux-main",
                        "selected": true,
                        "panes": [
                            {
                                "id": "pane-1",
                                "surfaces": [
                                    {
                                        "ref": "surface:1",
                                        "type": "terminal",
                                        "title": "zsh",
                                        "selected": true,
                                        "cwd": "/Users/jim/repo/cmux"
                                    },
                                    {
                                        "ref": "surface:2",
                                        "type": "terminal",
                                        "title": "Claude Code",
                                        "agent_state": "working",
                                        "attention": false
                                    }
                                ]
                            }
                        ]
                    },
                    {
                        "id": "ws-android",
                        "title": "android-dev",
                        "selected": false,
                        "panes": [
                            {
                                "id": "pane-2",
                                "surfaces": [
                                    {
                                        "ref": "surface:3",
                                        "type": "terminal",
                                        "title": "gradle build"
                                    }
                                ]
                            }
                        ]
                    }
                ]
            }
        ]
    });

    let workspaces = parse_workspace_tree_value(&tree_json).unwrap();
    assert_eq!(workspaces.len(), 2);

    let ws1 = &workspaces[0];
    assert_eq!(ws1.id, "ws-main");
    assert_eq!(ws1.name, "cmux-main");
    assert!(ws1.active_on_host);
    assert_eq!(ws1.cwd.as_deref(), Some("/Users/jim/repo/cmux"));
    assert_eq!(ws1.surfaces.len(), 2);

    let s1 = &ws1.surfaces[0];
    assert_eq!(s1.id, "surface:1");
    assert_eq!(s1.surface_type, "terminal");
    assert_eq!(s1.title.as_deref(), Some("zsh"));
    assert_eq!(s1.tab_index, 0);
    assert_eq!(s1.workspace_key.as_deref(), Some("ws-main"));

    let s2 = &ws1.surfaces[1];
    assert_eq!(s2.id, "surface:2");
    assert_eq!(s2.agent_state.as_deref(), Some("working"));
    assert_eq!(s2.tab_index, 1);

    let ws2 = &workspaces[1];
    assert_eq!(ws2.id, "ws-android");
    assert!(!ws2.active_on_host);
    assert_eq!(ws2.surfaces.len(), 1);
}

#[test]
fn test_parse_direct_workspaces_array() {
    let tree_json = json!([
        {
            "id": "ws-1",
            "name": "Workspace 1",
            "active_on_host": true,
            "surfaces": [
                {
                    "id": "surf-1",
                    "title": "shell",
                    "type": "terminal"
                }
            ]
        }
    ]);

    let workspaces = parse_workspace_tree_value(&tree_json).unwrap();
    assert_eq!(workspaces.len(), 1);
    assert_eq!(workspaces[0].id, "ws-1");
    assert_eq!(workspaces[0].surfaces.len(), 1);
    assert_eq!(workspaces[0].surfaces[0].id, "surf-1");
}

#[test]
fn test_parse_malformed_json_tree() {
    let invalid_json = "{not_json";
    let res = parse_workspace_tree(invalid_json);
    assert!(res.is_err());
}

#[test]
fn test_extract_surface_id() {
    assert_eq!(
        extract_surface_id("Created surface surface:123 in workspace ws-1"),
        Some("surface:123".to_string())
    );
    assert_eq!(
        extract_surface_id("surface:main-tab"),
        Some("surface:main-tab".to_string())
    );
    assert_eq!(
        extract_surface_id("[surface:42]"),
        Some("surface:42".to_string())
    );
    assert_eq!(extract_surface_id("no surface here"), None);
}
