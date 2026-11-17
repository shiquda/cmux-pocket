use cmux_pocket_protocol::*;
use serde_json::json;

#[test]
fn test_stop_hook_with_surface_is_forwarded() {
    let event = json!({
        "type": "event",
        "id": "event-1",
        "name": "agent.hook.Stop",
        "category": "agent",
        "workspace_id": "workspace-1",
        "surface_id": "surface-1",
        "payload": {
            "hook_event_name": "Stop",
            "_source": "codex"
        }
    });

    let completion = parse_agent_completion_event(&event).expect("Expected completion event");
    assert_eq!(completion.surface_id, "surface-1");
    assert_eq!(completion.category, "turn-complete");
    assert_eq!(completion.agent_kind.as_deref(), Some("codex"));
    assert_eq!(completion.workspace_id.as_deref(), Some("workspace-1"));
    assert_eq!(completion.event_id.as_deref(), Some("event-1"));
}

#[test]
fn test_session_end_hook_is_forwarded() {
    let event = json!({
        "type": "event",
        "id": "event-end",
        "name": "agent.hook.SessionEnd",
        "surface_id": "surface-42",
        "payload": {
            "hook_event_name": "sessionend",
            "_source": "gemini"
        }
    });

    let completion = parse_agent_completion_event(&event).expect("Expected completion event");
    assert_eq!(completion.surface_id, "surface-42");
    assert_eq!(completion.category, "turn-complete");
    assert_eq!(completion.agent_kind.as_deref(), Some("gemini"));
}

#[test]
fn test_turn_complete_agent_context_is_forwarded() {
    let event = json!({
        "type": "event",
        "id": "event-2",
        "name": "notification.created",
        "category": "notification",
        "surface_id": "surface-2",
        "agent": {
            "kind": "claude",
            "category": "turn-complete"
        },
        "payload": {}
    });

    let completion = parse_agent_completion_event(&event).expect("Expected completion event");
    assert_eq!(completion.surface_id, "surface-2");
    assert_eq!(completion.agent_kind.as_deref(), Some("claude"));
    assert_eq!(completion.category, "turn-complete");
}

#[test]
fn test_unrelated_events_and_missing_surface_are_ignored() {
    let pre_tool = json!({
        "type": "event",
        "name": "agent.hook.PreToolUse",
        "surface_id": "surface-1",
        "payload": {"hook_event_name": "PreToolUse"}
    });
    assert!(parse_agent_completion_event(&pre_tool).is_none());

    let missing_surf = json!({
        "type": "event",
        "name": "agent.hook.Stop",
        "payload": {"hook_event_name": "Stop"}
    });
    assert!(parse_agent_completion_event(&missing_surf).is_none());

    let non_event = json!({
        "type": "other",
        "name": "agent.hook.Stop",
        "surface_id": "surface-1",
        "payload": {"hook_event_name": "Stop"}
    });
    assert!(parse_agent_completion_event(&non_event).is_none());
}

#[test]
fn test_notification_record_requires_completion_marker() {
    let record_complete =
        "0:notif-1|ws-1|surf-1|unread|Task title||Complete|2026-08-24T00:00:00Z|pct:Task title";
    assert!(notification_record_is_completion(
        record_complete,
        "notif-1"
    ));

    let record_completed =
        "0:notif-2|ws-1|surf-1|unread|Task title||Completed|2026-08-24T00:00:00Z|pct:Task title";
    assert!(notification_record_is_completion(
        record_completed,
        "notif-2"
    ));

    let record_done =
        "0:notif-3|ws-1|surf-1|unread|Task title||Done|2026-08-24T00:00:00Z|pct:Task title";
    assert!(notification_record_is_completion(record_done, "notif-3"));

    // Case-insensitivity check
    let record_lower =
        "0:notif-4|ws-1|surf-1|unread|Task title||completed|2026-08-24T00:00:00Z|pct:Task title";
    assert!(notification_record_is_completion(record_lower, "notif-4"));

    // Incomplete status
    let record_waiting =
        "0:notif-1|ws-1|surf-1|unread|Task title||Waiting|2026-08-24T00:00:00Z|pct:Task title";
    assert!(!notification_record_is_completion(
        record_waiting,
        "notif-1"
    ));

    // ID mismatch
    assert!(!notification_record_is_completion(
        record_complete,
        "notif-999"
    ));

    // Malformed record with fewer fields
    assert!(!notification_record_is_completion(
        "0:notif-1|short",
        "notif-1"
    ));
}

#[test]
fn test_specific_reply_is_preferred_over_generic_status() {
    let notices = vec![
        completion_notice_from_json(&json!({
            "id": "generic",
            "surface_id": "surface-a",
            "workspace_id": "ws",
            "title": "omp",
            "subtitle": "",
            "body": "Complete",
            "created_at": "2026-11-17T04:37:17Z"
        }))
        .unwrap(),
        completion_notice_from_json(&json!({
            "id": "reply",
            "surface_id": "surface-a",
            "workspace_id": "ws",
            "title": "OMP",
            "subtitle": "Completed in tmp",
            "body": "Ready. What are we working on?",
            "created_at": "2026-11-17T04:37:17Z"
        }))
        .unwrap(),
        completion_notice_from_json(&json!({
            "id": "other-surface",
            "surface_id": "surface-b",
            "body": "Reply from another surface",
            "subtitle": "Completed in other",
            "created_at": "2026-11-17T04:37:17Z"
        }))
        .unwrap(),
        completion_notice_from_json(&json!({
            "id": "waiting",
            "surface_id": "surface-a",
            "body": "Waiting for input",
            "subtitle": "",
            "created_at": "2026-11-17T04:37:17Z"
        }))
        .unwrap(),
    ];

    let generic = select_completion_notice(&notices, Some("generic"), "surface-a", None).unwrap();
    assert!(generic.reply.is_none());
    assert!(generic.is_completion);

    let reply = select_completion_notice(&notices, Some("reply"), "surface-a", None).unwrap();
    assert_eq!(
        reply.reply.as_deref(),
        Some("Ready. What are we working on?")
    );

    assert!(select_completion_notice(&notices, Some("waiting"), "surface-a", None).is_none());

    let attached = select_completion_notice(
        &notices,
        None,
        "surface-a",
        Some("2026-11-17T04:37:17.269Z"),
    )
    .unwrap();
    assert_eq!(attached.id, "reply");
    assert_eq!(
        select_completion_notice(&notices, None, "surface-b", Some("2026-11-17T04:37:17Z"))
            .unwrap()
            .reply
            .as_deref(),
        Some("Reply from another surface")
    );
    assert!(
        select_completion_notice(&notices, None, "surface-a", Some("2026-11-17T04:20:00Z"),)
            .is_none()
    );
}

#[test]
fn test_compact_record_keeps_a_specific_reply() {
    let record = "1:reply|ws|surface-a|unread|OMP|Completed in tmp|Ready. What are we working on?|2026-11-17T04:37:17Z|pct:Dev";
    let notice = completion_notice_from_record(record).unwrap();
    assert!(notice.is_completion);
    assert_eq!(
        notice.reply.as_deref(),
        Some("Ready. What are we working on?")
    );
    assert!(!notification_record_is_completion(record, "reply"));
}
