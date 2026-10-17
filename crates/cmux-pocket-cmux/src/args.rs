//! CLI argument builders for cmux commands.
//!
//! Provides deterministic argument vector constructors matching the exact
//! cmux CLI contracts.

use serde_json::json;

use crate::backend::{TerminalInputKind, TerminalInputOptions};

/// Constructs arguments for `cmux ping`.
pub fn ping_args() -> Vec<String> {
    vec!["ping".to_string()]
}

/// Constructs arguments for `cmux tree --all --json`.
pub fn tree_args() -> Vec<String> {
    vec![
        "tree".to_string(),
        "--all".to_string(),
        "--json".to_string(),
    ]
}

/// Constructs arguments for `cmux new-workspace --name <name>`.
pub fn new_workspace_args(name: &str) -> Vec<String> {
    vec![
        "new-workspace".to_string(),
        "--name".to_string(),
        name.to_string(),
    ]
}

/// Constructs arguments for `cmux select-workspace --workspace <workspace_key>`.
pub fn select_workspace_args(workspace_key: &str) -> Vec<String> {
    vec![
        "select-workspace".to_string(),
        "--workspace".to_string(),
        workspace_key.to_string(),
    ]
}

/// Constructs arguments for `cmux new-surface --workspace <workspace_key> --type <type> --focus false`.
pub fn new_surface_args(workspace_key: &str, surface_type: Option<&str>) -> Vec<String> {
    vec![
        "new-surface".to_string(),
        "--workspace".to_string(),
        workspace_key.to_string(),
        "--type".to_string(),
        surface_type.unwrap_or("terminal").to_string(),
        "--focus".to_string(),
        "false".to_string(),
    ]
}

/// Constructs arguments for `cmux close-surface --surface <surface_id> [--workspace <workspace_key>]`.
pub fn close_surface_args(surface_id: &str, workspace_key: Option<&str>) -> Vec<String> {
    let mut args = vec![
        "close-surface".to_string(),
        "--surface".to_string(),
        surface_id.to_string(),
    ];
    if let Some(ws_key) = workspace_key {
        args.push("--workspace".to_string());
        args.push(ws_key.to_string());
    }
    args
}

/// Maps an intentional control key to `send-key`; all other input is literal
/// text passed after `--` so it can never be parsed as a cmux option.
pub fn send_input_args(surface_id: &str, text: &str, options: TerminalInputOptions) -> Vec<String> {
    let key_name = match options.kind {
        TerminalInputKind::Text => None,
        TerminalInputKind::Key => match text {
            "\u{001b}" => Some("escape"),
            "\t" => Some("tab"),
            "\u{001b}[A" => Some("up"),
            "\u{001b}[B" => Some("down"),
            "\u{001b}[C" => Some("right"),
            "\u{001b}[D" => Some("left"),
            "\u{0003}" => Some("ctrl-c"),
            "\u{0004}" => Some("ctrl-d"),
            "\u{007f}" | "\u{0008}" => Some("backspace"),
            "\n" | "\r" => Some("enter"),
            _ => None,
        },
    };

    if let Some(key) = key_name {
        let mut args = vec![
            "send-key".to_string(),
            "--surface".to_string(),
            surface_id.to_string(),
        ];
        if options.force {
            args.push("--force".to_string());
        }
        args.push(key.to_string());
        return args;
    }

    let mut args = vec![
        "send".to_string(),
        "--surface".to_string(),
        surface_id.to_string(),
    ];
    if options.force {
        args.push("--force".to_string());
    }
    if options.paste && text.chars().count() > 1 {
        args.push("--paste".to_string());
    }
    args.push("--".to_string());
    args.push(text.to_string());
    args
}

/// Constructs arguments for `cmux rpc terminal.replay <json_payload>`.
pub fn rpc_replay_args(surface_id: &str, max_scrollback_rows: usize) -> Vec<String> {
    let payload = json!({
        "surface_id": surface_id,
        "anchor": "screen",
        "max_scrollback_rows": max_scrollback_rows,
    });
    vec![
        "rpc".to_string(),
        "terminal.replay".to_string(),
        payload.to_string(),
    ]
}

/// Constructs arguments for `cmux rpc mobile.terminal.scroll <json_payload>`.
pub fn rpc_scroll_args(surface_id: &str, delta_lines: f64, col: usize, row: usize) -> Vec<String> {
    let payload = json!({
        "surface_id": surface_id,
        "delta_lines": delta_lines,
        "col": col,
        "row": row,
        "max_scrollback_rows": 1,
    });
    vec![
        "rpc".to_string(),
        "mobile.terminal.scroll".to_string(),
        payload.to_string(),
    ]
}

/// Constructs arguments for `cmux read-screen --surface <surface_id>`.
pub fn read_screen_args(surface_id: &str) -> Vec<String> {
    vec![
        "read-screen".to_string(),
        "--surface".to_string(),
        surface_id.to_string(),
    ]
}

/// Constructs arguments for `cmux list-notifications`.
pub fn list_notifications_args() -> Vec<String> {
    vec!["list-notifications".to_string()]
}

/// Constructs arguments for `cmux events --category agent --category notification --reconnect --no-heartbeat`.
pub fn events_args() -> Vec<String> {
    vec![
        "events".to_string(),
        "--category".to_string(),
        "agent".to_string(),
        "--category".to_string(),
        "notification".to_string(),
        "--reconnect".to_string(),
        "--no-heartbeat".to_string(),
    ]
}
