//! CLI argument builders for cmux commands.
//!
//! Provides deterministic argument vector constructors matching the exact
//! cmux CLI contracts.

use serde_json::json;

use crate::backend::{CmuxCapabilities, TerminalInputKind};

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

/// Constructs arguments for `cmux new-workspace [--name <name>]`.
pub fn new_workspace_args(name: &str) -> Vec<String> {
    if name.is_empty() {
        vec!["new-workspace".to_string()]
    } else {
        vec![
            "new-workspace".to_string(),
            "--name".to_string(),
            name.to_string(),
        ]
    }
}

/// Constructs arguments for `cmux rename-workspace --workspace <workspace_key> <title>`.
pub fn rename_workspace_args(workspace_key: &str, title: &str) -> Vec<String> {
    vec![
        "rename-workspace".to_string(),
        "--workspace".to_string(),
        workspace_key.to_string(),
        title.to_string(),
    ]
}

/// Constructs arguments for `cmux close-workspace --workspace <workspace_key>`.
pub fn close_workspace_args(workspace_key: &str) -> Vec<String> {
    vec![
        "close-workspace".to_string(),
        "--workspace".to_string(),
        workspace_key.to_string(),
    ]
}

/// Constructs arguments for `cmux rename-tab --surface <surface_id> <title>`.
pub fn rename_surface_args(surface_id: &str, title: &str) -> Vec<String> {
    vec![
        "rename-tab".to_string(),
        "--surface".to_string(),
        surface_id.to_string(),
        title.to_string(),
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
///
/// `--force` and `--paste` are only emitted when the installed cmux advertises
/// them: older builds treat unknown flags as input text.
pub fn send_input_args(
    surface_id: &str,
    text: &str,
    kind: TerminalInputKind,
    capabilities: CmuxCapabilities,
) -> Vec<String> {
    let key_name = match kind {
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
        if capabilities.force {
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
    if capabilities.force {
        args.push("--force".to_string());
    }
    if capabilities.paste && text.chars().count() > 1 {
        args.push("--paste".to_string());
    }
    args.push("--".to_string());
    args.push(text.to_string());
    args
}

/// Constructs arguments for a non-sticky viewport report.
///
/// The host attaches a short-lived (about five seconds) viewport report to any
/// `mobile.terminal.replay` that carries `client_id` plus dimensions. Reports
/// sent this way are not sticky, so a Gateway that stops renewing them cannot
/// pin the shared grid after a crash. The replay also returns the authoritative
/// frame, which carries the geometry the host actually applied.
pub fn rpc_viewport_report_args(
    surface_id: &str,
    columns: u32,
    rows: u32,
    client_id: &str,
    generation: u64,
) -> Vec<String> {
    let payload = json!({
        "surface_id": surface_id,
        "anchor": "viewport",
        "max_scrollback_rows": 0,
        "client_id": client_id,
        "viewport_columns": columns,
        "viewport_rows": rows,
        "viewport_generation": generation,
    });
    vec![
        "rpc".to_string(),
        "mobile.terminal.replay".to_string(),
        payload.to_string(),
    ]
}

/// Constructs arguments for the dedicated viewport clear call.
///
/// Only the clear half of `mobile.terminal.viewport` is used: the reporting half
/// is sticky for the client's connection lifetime and cannot be reclaimed by the
/// host's TTL when the Gateway itself dies.
pub fn rpc_viewport_clear_args(surface_id: &str, client_id: &str, generation: u64) -> Vec<String> {
    let payload = json!({
        "surface_id": surface_id,
        "client_id": client_id,
        "clear": true,
        "viewport_generation": generation,
    });
    vec![
        "rpc".to_string(),
        "mobile.terminal.viewport".to_string(),
        payload.to_string(),
    ]
}

/// Constructs arguments for `cmux capabilities --json`.
pub fn capabilities_args() -> Vec<String> {
    vec!["capabilities".to_string(), "--json".to_string()]
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
