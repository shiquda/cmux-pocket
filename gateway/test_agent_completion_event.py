#!/usr/bin/env python3
import unittest

from cmux_gateway import notification_record_is_completion, parse_agent_completion_event


class AgentCompletionEventTest(unittest.TestCase):
    def test_stop_hook_with_surface_is_forwarded(self):
        result = parse_agent_completion_event({
            "type": "event",
            "id": "event-1",
            "name": "agent.hook.Stop",
            "category": "agent",
            "workspace_id": "workspace-1",
            "surface_id": "surface-1",
            "payload": {
                "hook_event_name": "Stop",
                "_source": "codex",
            },
        })
        self.assertEqual(result["surface_id"], "surface-1")
        self.assertEqual(result["category"], "turn-complete")
        self.assertEqual(result["agent_kind"], "codex")

    def test_turn_complete_agent_context_is_forwarded(self):
        result = parse_agent_completion_event({
            "type": "event",
            "id": "event-2",
            "name": "notification.created",
            "category": "notification",
            "surface_id": "surface-2",
            "agent": {"kind": "claude", "category": "turn-complete"},
            "payload": {},
        })
        self.assertEqual(result["surface_id"], "surface-2")
        self.assertEqual(result["agent_kind"], "claude")

    def test_unrelated_events_and_missing_surface_are_ignored(self):
        self.assertIsNone(parse_agent_completion_event({
            "type": "event",
            "name": "agent.hook.PreToolUse",
            "surface_id": "surface-1",
            "payload": {"hook_event_name": "PreToolUse"},
        }))
        self.assertIsNone(parse_agent_completion_event({
            "type": "event",
            "name": "agent.hook.Stop",
            "payload": {"hook_event_name": "Stop"},
        }))

    def test_notification_record_requires_completion_marker(self):
        record = "0:notification-1|workspace-1|surface-1|unread|Task title||Complete|2026-08-24T00:00:00Z|pct:Task title"
        self.assertTrue(notification_record_is_completion(record, "notification-1"))
        self.assertFalse(notification_record_is_completion(record.replace("Complete", "Waiting"), "notification-1"))
        self.assertFalse(notification_record_is_completion(record, "notification-2"))


if __name__ == "__main__":
    unittest.main()
