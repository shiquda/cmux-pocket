use cmux_pocket_cmux::*;
use serde_json::Value;

/// cmux <= 0.64.x: no `--force` / `--paste` support.
const LEGACY: CmuxCapabilities = CmuxCapabilities {
    force: false,
    paste: false,
};

#[test]
fn test_ping_args() {
    assert_eq!(ping_args(), vec!["ping"]);
}

#[test]
fn test_tree_args() {
    assert_eq!(tree_args(), vec!["tree", "--all", "--json"]);
}

#[test]
fn test_new_workspace_args() {
    assert_eq!(
        new_workspace_args("my-workspace"),
        vec!["new-workspace", "--name", "my-workspace"]
    );
    assert_eq!(new_workspace_args(""), vec!["new-workspace"]);
}

#[test]
fn test_rename_workspace_args() {
    assert_eq!(
        rename_workspace_args("ws-123", "backend-logs"),
        vec!["rename-workspace", "--workspace", "ws-123", "backend-logs"]
    );
}

#[test]
fn test_close_workspace_args() {
    assert_eq!(
        close_workspace_args("ws-123"),
        vec!["close-workspace", "--workspace", "ws-123"]
    );
}

#[test]
fn test_rename_surface_args() {
    assert_eq!(
        rename_surface_args("surface:1", "zsh-dev"),
        vec!["rename-tab", "--surface", "surface:1", "zsh-dev"]
    );
}

#[test]
fn test_select_workspace_args() {
    assert_eq!(
        select_workspace_args("ws-123"),
        vec!["select-workspace", "--workspace", "ws-123"]
    );
}

#[test]
fn test_new_surface_args_default_and_custom() {
    assert_eq!(
        new_surface_args("ws-123", None),
        vec![
            "new-surface",
            "--workspace",
            "ws-123",
            "--type",
            "terminal",
            "--focus",
            "false"
        ]
    );

    assert_eq!(
        new_surface_args("ws-123", Some("browser")),
        vec![
            "new-surface",
            "--workspace",
            "ws-123",
            "--type",
            "browser",
            "--focus",
            "false"
        ]
    );
}

#[test]
fn test_close_surface_args() {
    assert_eq!(
        close_surface_args("surface:1", None),
        vec!["close-surface", "--surface", "surface:1"]
    );

    assert_eq!(
        close_surface_args("surface:1", Some("ws-123")),
        vec![
            "close-surface",
            "--surface",
            "surface:1",
            "--workspace",
            "ws-123"
        ]
    );
}

#[test]
fn test_send_input_special_keys() {
    let surf = "surface:99";

    // Escape
    assert_eq!(
        send_input_args(surf, "\u{001b}", TerminalInputKind::Key, LEGACY,),
        vec!["send-key", "--surface", surf, "escape"]
    );

    // Tab
    assert_eq!(
        send_input_args(surf, "\t", TerminalInputKind::Key, LEGACY,),
        vec!["send-key", "--surface", surf, "tab"]
    );

    // Up / Down / Right / Left
    assert_eq!(
        send_input_args(surf, "\u{001b}[A", TerminalInputKind::Key, LEGACY,),
        vec!["send-key", "--surface", surf, "up"]
    );
    assert_eq!(
        send_input_args(surf, "\u{001b}[B", TerminalInputKind::Key, LEGACY,),
        vec!["send-key", "--surface", surf, "down"]
    );
    assert_eq!(
        send_input_args(surf, "\u{001b}[C", TerminalInputKind::Key, LEGACY,),
        vec!["send-key", "--surface", surf, "right"]
    );
    assert_eq!(
        send_input_args(surf, "\u{001b}[D", TerminalInputKind::Key, LEGACY,),
        vec!["send-key", "--surface", surf, "left"]
    );

    // Ctrl-C / Ctrl-D
    assert_eq!(
        send_input_args(surf, "\u{0003}", TerminalInputKind::Key, LEGACY,),
        vec!["send-key", "--surface", surf, "ctrl-c"]
    );
    assert_eq!(
        send_input_args(surf, "\u{0004}", TerminalInputKind::Key, LEGACY,),
        vec!["send-key", "--surface", surf, "ctrl-d"]
    );

    // Backspace (\x7f and \x08)
    assert_eq!(
        send_input_args(surf, "\u{007f}", TerminalInputKind::Key, LEGACY,),
        vec!["send-key", "--surface", surf, "backspace"]
    );
    assert_eq!(
        send_input_args(surf, "\u{0008}", TerminalInputKind::Key, LEGACY,),
        vec!["send-key", "--surface", surf, "backspace"]
    );

    // Enter (\n and \r)
    assert_eq!(
        send_input_args(surf, "\n", TerminalInputKind::Key, LEGACY,),
        vec!["send-key", "--surface", surf, "enter"]
    );
    assert_eq!(
        send_input_args(surf, "\r", TerminalInputKind::Key, LEGACY,),
        vec!["send-key", "--surface", surf, "enter"]
    );

    // Literal text
    assert_eq!(
        send_input_args(surf, "git status", TerminalInputKind::Text, LEGACY),
        vec!["send", "--surface", surf, "--", "git status"]
    );
}

#[test]
fn test_literal_text_is_never_parsed_as_a_key_or_option() {
    let surf = "surface:99";

    assert_eq!(
        send_input_args(surf, "--force", TerminalInputKind::Text, LEGACY),
        vec!["send", "--surface", surf, "--", "--force"]
    );
    assert_eq!(
        send_input_args(surf, "\n", TerminalInputKind::Text, LEGACY),
        vec!["send", "--surface", surf, "--", "\n"]
    );
}

#[test]
fn test_flags_are_only_emitted_when_the_cli_supports_them() {
    let surf = "surface:99";
    let current = CmuxCapabilities {
        force: true,
        paste: true,
    };

    // Legacy cmux: unknown flags are typed as literal input, so omit them.
    assert_eq!(
        send_input_args(surf, "hello", TerminalInputKind::Text, LEGACY),
        vec!["send", "--surface", surf, "--", "hello"]
    );
    assert_eq!(
        send_input_args(surf, "\u{001b}[A", TerminalInputKind::Key, LEGACY),
        vec!["send-key", "--surface", surf, "up"]
    );

    // cmux >= 0.65.0: force every input and paste multi-character text.
    assert_eq!(
        send_input_args(surf, "hello", TerminalInputKind::Text, current),
        vec![
            "send",
            "--surface",
            surf,
            "--force",
            "--paste",
            "--",
            "hello"
        ]
    );
    assert_eq!(
        send_input_args(surf, "h", TerminalInputKind::Text, current),
        vec!["send", "--surface", surf, "--force", "--", "h"]
    );
    assert_eq!(
        send_input_args(surf, "\u{001b}[A", TerminalInputKind::Key, current),
        vec!["send-key", "--surface", surf, "--force", "up"]
    );
}

#[test]
fn test_rpc_replay_args() {
    let args = rpc_replay_args("surface:1", 50);
    assert_eq!(args.len(), 3);
    assert_eq!(args[0], "rpc");
    assert_eq!(args[1], "terminal.replay");

    let payload: Value = serde_json::from_str(&args[2]).unwrap();
    assert_eq!(payload["surface_id"], "surface:1");
    assert_eq!(payload["anchor"], "screen");
    assert_eq!(payload["max_scrollback_rows"], 50);
}

#[test]
fn test_rpc_scroll_args() {
    let args = rpc_scroll_args("surface:1", -5.5, 10, 20);
    assert_eq!(args.len(), 3);
    assert_eq!(args[0], "rpc");
    assert_eq!(args[1], "mobile.terminal.scroll");

    let payload: Value = serde_json::from_str(&args[2]).unwrap();
    assert_eq!(payload["surface_id"], "surface:1");
    assert_eq!(payload["delta_lines"], -5.5);
    assert_eq!(payload["col"], 10);
    assert_eq!(payload["row"], 20);
    assert_eq!(payload["max_scrollback_rows"], 1);
}

#[test]
fn test_read_screen_args() {
    assert_eq!(
        read_screen_args("surface:42"),
        vec!["read-screen", "--surface", "surface:42"]
    );
}

#[test]
fn test_list_notifications_args() {
    assert_eq!(list_notifications_args(), vec!["list-notifications"]);
}

#[test]
fn test_events_args() {
    assert_eq!(
        events_args(),
        vec![
            "events",
            "--category",
            "agent",
            "--category",
            "notification",
            "--reconnect",
            "--no-heartbeat"
        ]
    );
}
