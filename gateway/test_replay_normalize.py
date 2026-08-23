#!/usr/bin/env python3
import asyncio
import os
import tempfile
import unittest
from cmux_gateway import (
    LiveCmuxBackend,
    CmuxWebSocketGateway,
    abbreviate_home,
    display_cell_width,
    fanout_screen_snapshots,
    load_auth_token,
    is_loopback_bind_host,
    normalize_official_replay,
    workspace_tree_signature,
)


class TestGatewayBindPolicy(unittest.TestCase):
    def test_only_loopback_bind_hosts_are_accepted(self):
        self.assertTrue(is_loopback_bind_host("127.0.0.1"))
        self.assertTrue(is_loopback_bind_host("localhost"))
        self.assertFalse(is_loopback_bind_host("gateway.example.test"))

        with self.assertRaises(ValueError):
            CmuxWebSocketGateway(auth_token="test-token", host="gateway.example.test")


class TestOfficialReplayNormalize(unittest.TestCase):
    def test_display_width_cjk_and_ascii(self):
        self.assertEqual(display_cell_width("abc"), 3)
        self.assertEqual(display_cell_width("中文"), 4)
        self.assertEqual(display_cell_width("a中b"), 4)

    def test_uses_official_columns_and_strips_scrollback(self):
        payload = {
            "seq": 9,
            "columns": 145,
            "rows": 46,
            "surface_id": "UUID-SHOULD-NOT-LEAK",
            "render_grid": {
                "format": "cmux.render-grid.v1",
                "columns": 145,
                "rows": 46,
                "full": True,
                "state_seq": 0,
                "render_epoch": "epoch-live",
                "render_revision": 12,
                "cursor": {"row": 45, "column": 3, "visible": True, "style": "block", "blinking": True},
                "styles": [{"id": 0, "foreground": "#000", "background": "#FFF"}],
                "row_spans": [
                    {"row": 0, "column": 0, "style_id": 0, "text": "hello", "cell_width": 5},
                    {"row": 0, "column": 140, "style_id": 0, "text": "end", "cell_width": 3},
                ],
                "scrollback_spans": [{"row": 0, "text": "should-not-pass"}],
                "modes": [{"code": 7, "on": True}],
                "terminal_background": "#FEFFFF",
                "terminal_foreground": "#000000",
            },
        }

        frame = normalize_official_replay(payload, "surface:170", 4)
        self.assertEqual(frame["format"], "cmux.render-grid.v1")
        self.assertEqual(frame["surface_id"], "surface:170")
        self.assertEqual(frame["columns"], 145)
        self.assertEqual(frame["rows"], 46)
        self.assertEqual(frame["state_seq"], 4)
        self.assertTrue(frame["full"])
        self.assertEqual(len(frame["row_spans"]), 2)
        self.assertNotIn("scrollback_spans", frame)
        self.assertNotIn("modes", frame)
        self.assertEqual(frame["terminal_background"], "#FEFFFF")

    def test_poll_retains_history_metadata_and_excludes_spans(self):
        payload = {
            "render_grid": {
                "columns": 80,
                "rows": 24,
                "row_spans": [{"row": 0, "column": 0, "style_id": 0, "text": "visible", "cell_width": 7}],
                "scrollback_spans": [{"row": 0, "text": "older-line"}],
                "scrollback_rows": 1,
                "history_rows": 1500,
                "row_space_revision": 9,
            },
        }
        frame = normalize_official_replay(payload, "surface:170", 5, include_scrollback=False)
        self.assertEqual(frame["surface_id"], "surface:170")
        self.assertEqual(frame["history_rows"], 1500)
        self.assertEqual(frame["row_space_revision"], 9)
        self.assertNotIn("scrollback_rows", frame)
        self.assertNotIn("scrollback_spans", frame)

    def test_includes_scrollback_when_explicitly_requested(self):
        payload = {
            "render_grid": {
                "columns": 80,
                "rows": 24,
                "row_spans": [{"row": 0, "column": 0, "style_id": 0, "text": "visible", "cell_width": 7}],
                "scrollback_spans": [
                    {"row": 0, "column": 0, "style_id": 0, "text": "older-line"},
                    {"row": 1, "column": 0, "style_id": 0, "text": "oldest-line", "cell_width": 11},
                ],
                "scrollback_rows": 2,
                "history_rows": 1200,
                "row_space_revision": 7,
            },
        }
        frame = normalize_official_replay(payload, "surface:170", 5, include_scrollback=True)
        self.assertEqual(frame["surface_id"], "surface:170")
        self.assertEqual(frame["history_rows"], 1200)
        self.assertEqual(frame["row_space_revision"], 7)
        self.assertEqual(frame["scrollback_rows"], 2)
        self.assertEqual(len(frame["scrollback_spans"]), 2)
        self.assertEqual(frame["scrollback_spans"][0]["text"], "older-line")
        self.assertEqual(frame["scrollback_spans"][0]["cell_width"], 10)
        self.assertEqual(frame["scrollback_spans"][1]["cell_width"], 11)

    def test_derives_scrollback_rows_from_max_span_row_when_absent(self):
        payload = {
            "render_grid": {
                "columns": 80,
                "rows": 24,
                "row_spans": [{"row": 0, "column": 0, "style_id": 0, "text": "visible", "cell_width": 7}],
                "scrollback_spans": [
                    {"row": 0, "column": 0, "style_id": 0, "text": "first-older"},
                    {"row": 4, "column": 0, "style_id": 0, "text": "fifth-older", "cell_width": 11},
                ],
                "history_rows": 1200,
                "row_space_revision": 7,
            },
        }
        frame = normalize_official_replay(payload, "surface:170", 5, include_scrollback=True)
        self.assertEqual(frame["surface_id"], "surface:170")
        self.assertEqual(frame["scrollback_rows"], 5)
        self.assertEqual(len(frame["scrollback_spans"]), 2)

    def test_widens_columns_to_span_end(self):
        payload = {
            "render_grid": {
                "columns": 80,
                "rows": 10,
                "row_spans": [
                    {"row": 0, "column": 90, "style_id": 0, "text": "right-edge", "cell_width": 10}
                ],
            }
        }
        frame = normalize_official_replay(payload, "surface:1", 1)
        self.assertEqual(frame["columns"], 100)


class TestWorkspacePathHelpers(unittest.TestCase):
    def test_abbreviate_home(self):
        home = os.path.expanduser("~")
        self.assertEqual(abbreviate_home(home), "~")
        self.assertEqual(
            abbreviate_home(os.path.join(home, "repo", "cmux-pocket")),
            "~/repo/cmux-pocket",
        )

    def test_tree_signature_changes_with_tabs(self):
        one = [{"id": "w", "name": "n", "cwd": "~/repo", "surfaces": [{"id": "s1", "title": "a"}]}]
        two = [{"id": "w", "name": "n", "cwd": "~/repo", "surfaces": [{"id": "s1", "title": "a"}, {"id": "s2", "title": "b"}]}]
        self.assertNotEqual(workspace_tree_signature(one), workspace_tree_signature(two))


class TestLiveSurfaceTargeting(unittest.TestCase):
    def test_replay_uses_surface_id_parameter(self):
        backend = LiveCmuxBackend()
        calls = []

        def fake_rpc(method, params):
            calls.append((method, params))
            return {"render_grid": {"columns": 80, "rows": 24, "row_spans": []}}

        backend._rpc = fake_rpc
        frame = backend.get_snapshot("surface:42")
        self.assertEqual(calls, [("terminal.replay", {"surface_id": "surface:42", "anchor": "screen", "max_scrollback_rows": 0})])
        self.assertEqual(frame["surface_id"], "surface:42")

    def test_replay_with_max_scrollback_rows(self):
        backend = LiveCmuxBackend()
        calls = []

        def fake_rpc(method, params):
            calls.append((method, params))
            return {
                "render_grid": {
                    "columns": 80,
                    "rows": 24,
                    "row_spans": [],
                    "history_rows": 500,
                    "row_space_revision": 2,
                    "scrollback_rows": 0,
                    "scrollback_spans": [],
                }
            }

        backend._rpc = fake_rpc
        frame = backend.get_snapshot("surface:42", max_scrollback_rows=500)
        self.assertEqual(calls, [(
            "terminal.replay",
            {
                "surface_id": "surface:42",
                "anchor": "screen",
                "max_scrollback_rows": 500,
            },
        )])
        self.assertEqual(frame["surface_id"], "surface:42")
        self.assertEqual(frame["history_rows"], 500)
        self.assertEqual(frame["row_space_revision"], 2)
        self.assertEqual(frame["scrollback_rows"], 0)
        self.assertEqual(frame["scrollback_spans"], [])
    def test_scroll_targets_selected_surface(self):
        backend = LiveCmuxBackend()
        calls = []
        backend._rpc = lambda method, params: calls.append((method, params)) or {
            "render_grid": {"columns": 80, "rows": 24, "row_spans": []}
        }

        frame = backend.handle_scroll("surface:42", 3.5, 7, 9)

        self.assertEqual(calls, [(
            "mobile.terminal.scroll",
            {
                "surface_id": "surface:42",
                "delta_lines": 3.5,
                "col": 7,
                "row": 9,
                "max_scrollback_rows": 1,
            },
        )])
        self.assertEqual(frame["surface_id"], "surface:42")

    def test_create_surface_returns_created_id_not_last_tree_item(self):
        backend = LiveCmuxBackend()
        calls = []

        def fake_run(args):
            calls.append(args)
            if args[0] == "new-surface":
                return "OK surface:203 pane:64 workspace:13"
            return '''{"windows":[{"workspaces":[{"id":"ws-1","title":"Workspace","panes":[{"surfaces":[{"ref":"surface:203","type":"terminal","title":"New"},{"ref":"surface:old","type":"terminal","title":"Old"}]}]}]}]}'''

        backend._run_cmux = fake_run
        surface = backend.create_surface("ws-1")

        self.assertEqual(surface["id"], "surface:203")
        self.assertEqual(calls[0], [
            "new-surface",
            "--workspace",
            "ws-1",
            "--type",
            "terminal",
            "--focus",
            "false",
        ])

    def test_close_surface_passes_workspace_flag(self):
        backend = LiveCmuxBackend()
        calls = []

        def fake_run(args):
            calls.append(args)
            return "OK"

        backend._run_cmux = fake_run

        # With workspace_key
        res = backend.close_surface("surface:203", workspace_key="ws-1")
        self.assertTrue(res)
        self.assertEqual(calls[0], [
            "close-surface",
            "--surface",
            "surface:203",
            "--workspace",
            "ws-1",
        ])

        # Without workspace_key
        res_no_ws = backend.close_surface("surface:204")
        self.assertTrue(res_no_ws)
        self.assertEqual(calls[1], [
            "close-surface",
            "--surface",
            "surface:204",
        ])


class TestGatewayTokenLoading(unittest.TestCase):
    def test_loads_token_from_external_file(self):
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8") as handle:
            handle.write("runtime-token\n")
            handle.flush()
            previous_token = os.environ.pop("CMUX_AUTH_TOKEN", None)
            previous_file = os.environ.get("CMUX_AUTH_TOKEN_FILE")
            os.environ["CMUX_AUTH_TOKEN_FILE"] = handle.name
            try:
                self.assertEqual(load_auth_token(), "runtime-token")
            finally:
                if previous_token is not None:
                    os.environ["CMUX_AUTH_TOKEN"] = previous_token
                if previous_file is None:
                    os.environ.pop("CMUX_AUTH_TOKEN_FILE", None)
                else:
                    os.environ["CMUX_AUTH_TOKEN_FILE"] = previous_file


class MockClientSession:
    def __init__(self, session_id: str, active_surface_id: str, authenticated: bool = True, subscribed: bool = True):
        self.session_id = session_id
        self.active_surface_id = active_surface_id
        self.authenticated = authenticated
        self.subscribed_topics = {"terminal.render_grid"} if subscribed else set()
        self.sent_messages = []

    async def send_json(self, data):
        self.sent_messages.append(data)


class TestFanoutScreenSnapshots(unittest.IsolatedAsyncioTestCase):
    async def test_three_clients_on_same_surface_single_fetch(self):
        c1 = MockClientSession("c1", "surface:1")
        c2 = MockClientSession("c2", "surface:1")
        c3 = MockClientSession("c3", "surface:1")
        fetch_calls = []

        async def fake_fetch(sid):
            fetch_calls.append(sid)
            return {"surface_id": sid, "format": "cmux.render-grid.v1", "rows": 24, "columns": 80}

        results = await fanout_screen_snapshots([c1, c2, c3], fake_fetch)
        self.assertEqual(fetch_calls, ["surface:1"])
        self.assertIn("surface:1", results)
        self.assertEqual(len(c1.sent_messages), 1)
        self.assertEqual(len(c2.sent_messages), 1)
        self.assertEqual(len(c3.sent_messages), 1)
        self.assertEqual(c1.sent_messages[0]["data"]["surface_id"], "surface:1")
        self.assertEqual(c2.sent_messages[0]["data"]["surface_id"], "surface:1")
        self.assertEqual(c3.sent_messages[0]["data"]["surface_id"], "surface:1")

    async def test_clients_on_different_surfaces_concurrent_fetch(self):
        c1 = MockClientSession("c1", "surface:1")
        c2 = MockClientSession("c2", "surface:2")
        c3 = MockClientSession("c3", "surface:1")
        fetch_calls = []

        async def fake_fetch(sid):
            fetch_calls.append(sid)
            return {"surface_id": sid, "format": "cmux.render-grid.v1"}

        results = await fanout_screen_snapshots([c1, c2, c3], fake_fetch)
        self.assertEqual(sorted(fetch_calls), ["surface:1", "surface:2"])
        self.assertEqual(len(fetch_calls), 2)
        self.assertEqual(c1.sent_messages[0]["data"]["surface_id"], "surface:1")
        self.assertEqual(c2.sent_messages[0]["data"]["surface_id"], "surface:2")
        self.assertEqual(c3.sent_messages[0]["data"]["surface_id"], "surface:1")

    async def test_unsubscribed_or_unauthenticated_clients_skipped(self):
        c1 = MockClientSession("c1", "surface:1", authenticated=False)
        c2 = MockClientSession("c2", "surface:2", subscribed=False)
        fetch_calls = []

        async def fake_fetch(sid):
            fetch_calls.append(sid)
            return {"surface_id": sid}

        results = await fanout_screen_snapshots([c1, c2], fake_fetch)
        self.assertEqual(fetch_calls, [])
        self.assertEqual(results, {})
        self.assertEqual(len(c1.sent_messages), 0)
        self.assertEqual(len(c2.sent_messages), 0)

    async def test_multi_surface_independence(self):
        import time as _t
        c1 = MockClientSession("c1", "surface:fast")
        c2 = MockClientSession("c2", "surface:slow")

        c1_receive_time = None
        c2_receive_time = None
        t0 = _t.perf_counter()

        async def fake_fetch(sid):
            if sid == "surface:fast":
                await asyncio.sleep(0.01)
                return {"surface_id": sid, "format": "cmux.render-grid.v1"}
            else:
                await asyncio.sleep(0.25)
                return {"surface_id": sid, "format": "cmux.render-grid.v1"}

        # Custom send_json recording receive timestamp
        async def send1(data):
            nonlocal c1_receive_time
            c1_receive_time = _t.perf_counter()
            c1.sent_messages.append(data)
        c1.send_json = send1

        async def send2(data):
            nonlocal c2_receive_time
            c2_receive_time = _t.perf_counter()
            c2.sent_messages.append(data)
        c2.send_json = send2

        results = await fanout_screen_snapshots([c1, c2], fake_fetch, priority_surfaces={"surface:fast"})
        self.assertIn("surface:fast", results)
        self.assertIn("surface:slow", results)

        self.assertIsNotNone(c1_receive_time)
        self.assertIsNotNone(c2_receive_time)
        # c1 was not blocked by c2's slow 0.25s fetch
        self.assertLess(c1_receive_time - t0, 0.15)
        self.assertGreaterEqual(c2_receive_time - t0, 0.20)
        self.assertEqual(len(c1.sent_messages), 1)
        self.assertEqual(len(c2.sent_messages), 1)
    async def test_active_surface_filtering(self):
        from cmux_gateway import CmuxGatewayClientSession
        client = CmuxGatewayClientSession(None, None)
        client.authenticated = True
        client.subscribed_topics.add("terminal.render_grid")
        client.set_active_surface("surface:1")
        self.assertEqual(client.focus_generation, 1)

        frame_s2 = {"surface_id": "surface:2", "state_seq": 1}
        frame_stale_focus = {"surface_id": "surface:1", "state_seq": 2}
        frame_valid = {"surface_id": "surface:1", "state_seq": 3}

        # 1. Surface mismatch is filtered out
        client.enqueue_render_frame("surface:2", 1, frame_s2)
        self.assertIsNone(client._latest_render_frame)

        # 2. Focus generation mismatch is filtered out
        client.enqueue_render_frame("surface:1", 0, frame_stale_focus)
        self.assertIsNone(client._latest_render_frame)

        # 3. Matching surface & focus generation is accepted
        client.enqueue_render_frame("surface:1", 1, frame_valid)
        self.assertIsNotNone(client._latest_render_frame)
        self.assertEqual(client._latest_render_frame[0], "surface:1")
        self.assertEqual(client._latest_render_frame[1], 1)
        self.assertEqual(client._latest_render_frame[2]["state_seq"], 3)

        # 4. Switching active surface clears pending frame and increments generation
        client.set_active_surface("surface:2")
        self.assertEqual(client.focus_generation, 2)
        self.assertIsNone(client._latest_render_frame)
        await client.close()


class TestTracePrivacy(unittest.TestCase):
    def test_trace_privacy_numeric_only(self):
        from cmux_gateway import perf_trace, get_surface_ordinal, get_client_ordinal
        import logging

        os.environ["CMUX_PERF_TRACE"] = "1"
        records = []

        class TestHandler(logging.Handler):
            def emit(self, record):
                records.append(self.format(record))

        handler = TestHandler()
        logger = logging.getLogger("cmux-gateway")
        logger.addHandler(handler)
        logger.setLevel(logging.INFO)

        try:
            surf_ord = get_surface_ordinal("secret-surface-uuid-12345")
            cli_ord = get_client_ordinal("secret-client-uuid-67890")

            perf_trace(
                "host_input",
                surface_ord=surf_ord,
                client_ord=cli_ord,
                host_input_ms=3.14159,
                queue_depth=2,
                queue_wait_ms=0.5,
            )

            self.assertGreaterEqual(len(records), 1)
            last_log = records[-1]
            self.assertIn("[PERF]", last_log)
            self.assertIn("event=host_input", last_log)
            self.assertIn(f"surface_ord={surf_ord}", last_log)
            self.assertIn(f"client_ord={cli_ord}", last_log)
            self.assertIn("host_input_ms=3.142", last_log)
            self.assertIn("queue_depth=2", last_log)

            # Sensitive raw IDs and tokens must NEVER appear
            self.assertNotIn("secret-surface-uuid-12345", last_log)
            self.assertNotIn("secret-client-uuid-67890", last_log)
            self.assertNotIn("token", last_log.lower())
        finally:
            logger.removeHandler(handler)
            os.environ.pop("CMUX_PERF_TRACE", None)

    def test_bool_numeric_trace(self):
        from cmux_gateway import perf_trace
        import logging

        os.environ["CMUX_PERF_TRACE"] = "1"
        records = []

        class TestHandler(logging.Handler):
            def emit(self, record):
                records.append(self.format(record))

        handler = TestHandler()
        logger = logging.getLogger("cmux-gateway")
        logger.addHandler(handler)
        logger.setLevel(logging.INFO)

        try:
            perf_trace("bool_test", flag_true=True, flag_false=False, count=42)
            self.assertGreaterEqual(len(records), 1)
            last_log = records[-1]
            self.assertIn("flag_true=1", last_log)
            self.assertIn("flag_false=0", last_log)
            self.assertIn("count=42", last_log)
            self.assertNotIn("True", last_log)
            self.assertNotIn("False", last_log)
        finally:
            logger.removeHandler(handler)
            os.environ.pop("CMUX_PERF_TRACE", None)

    def test_run_cmux_failure_privacy(self):
        from cmux_gateway import LiveCmuxBackend
        backend = LiveCmuxBackend()

        # Test that _run_cmux raises only operation and exit code without leaking arguments or sensitive input
        import subprocess
        def fake_run(cmd, capture_output=True, text=True):
            class FakeRes:
                returncode = 127
                stdout = ""
                stderr = "Secret password leaked in stderr"
            return FakeRes()

        orig_subprocess_run = subprocess.run
        subprocess.run = fake_run
        try:
            with self.assertRaises(RuntimeError) as ctx:
                backend._run_cmux(["send", "--surface", "secret-surface-id", "my-secret-password-123"])

            err_msg = str(ctx.exception)
            self.assertEqual(err_msg, "cmux send failed with exit code 127")
            self.assertNotIn("secret-surface-id", err_msg)
            self.assertNotIn("my-secret-password-123", err_msg)
            self.assertNotIn("Secret password leaked in stderr", err_msg)
        finally:
            subprocess.run = orig_subprocess_run


if __name__ == "__main__":
    unittest.main()
