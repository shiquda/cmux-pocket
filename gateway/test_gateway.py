#!/usr/bin/env python3
"""
Automated Integration Tests for cmux WebSocket Bridge Gateway v2.
Tests Authentication, Multi-Workspace listing/creation, Multi-Tab Surface creation/close,
Input routing, and RenderGrid Frames.
"""

import asyncio
import json
import os
import unittest
import websockets
from cmux_gateway import CmuxWebSocketGateway


class TestCmuxGatewayIntegration(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.port = 8999
        self.token = "test-token-xyz"
        # Force mock backend for deterministic unit test assertions
        os.environ["CMUX_GATEWAY_BACKEND"] = "mock"
        self.gateway = CmuxWebSocketGateway(host="127.0.0.1", port=self.port, auth_token=self.token)
        await self.gateway.start()
        self.ws_url = f"ws://127.0.0.1:{self.port}"

    async def asyncTearDown(self):
        await self.gateway.stop()
        os.environ.pop("CMUX_GATEWAY_BACKEND", None)

    async def test_auth_rejection(self):
        async with websockets.connect(self.ws_url) as ws:
            await ws.send(json.dumps({"type": "auth", "token": "wrong-token"}))
            response = json.loads(await ws.recv())
            self.assertEqual(response.get("type"), "auth_error")

    async def test_multi_workspace_and_multi_tab_rpc(self):
        async with websockets.connect(self.ws_url) as ws:
            # 1. Authenticate
            await ws.send(json.dumps({"type": "auth", "token": self.token}))
            auth_res = json.loads(await ws.recv())
            self.assertEqual(auth_res.get("type"), "auth_ok")
            self.assertIn("multi_surface.v1", auth_res.get("capabilities", []))

            # 2. List Workspaces
            await ws.send(json.dumps({
                "id": "req-ws-list",
                "method": "mobile.workspace.list",
                "params": {}
            }))
            ws_res = json.loads(await ws.recv())
            self.assertEqual(ws_res.get("id"), "req-ws-list")
            workspaces = ws_res.get("result", {}).get("workspaces", [])
            self.assertGreaterEqual(len(workspaces), 1)
            self.assertGreaterEqual(len(workspaces[0]["surfaces"]), 1)

            # 3. Create a New Workspace
            await ws.send(json.dumps({
                "id": "req-ws-create",
                "method": "mobile.workspace.create",
                "params": {
                    "name": "project-nebula",
                    "initial_surface": {"type": "terminal", "title": "nebula-sh"}
                }
            }))
            create_ws_res = json.loads(await ws.recv())
            self.assertEqual(create_ws_res.get("id"), "req-ws-create")
            new_ws = create_ws_res.get("result", {}).get("workspace", {})
            self.assertEqual(new_ws.get("name"), "project-nebula")
            self.assertEqual(len(new_ws.get("surfaces", [])), 1)
            new_ws_key = new_ws.get("key") or new_ws.get("id")

            # 4. Create a New Surface (Tab) in the new Workspace
            await ws.send(json.dumps({
                "id": "req-surf-create",
                "method": "mobile.surface.create",
                "params": {
                    "workspace_key": new_ws_key,
                    "title": "nebula-logs",
                    "type": "terminal"
                }
            }))
            create_surf_res = json.loads(await ws.recv())
            self.assertEqual(create_surf_res.get("id"), "req-surf-create")
            new_surf = create_surf_res.get("result", {}).get("surface", {})
            self.assertEqual(new_surf.get("title"), "nebula-logs")
            new_surf_id = new_surf.get("id")

            # 5. Subscribe to terminal.render_grid
            await ws.send(json.dumps({
                "id": "req-sub",
                "method": "mobile.events.subscribe",
                "params": {"topics": ["terminal.render_grid"]}
            }))
            sub_res = json.loads(await ws.recv())
            self.assertEqual(sub_res.get("id"), "req-sub")

            # Receive initial snapshot for currently active surface (surf-main-1)
            snapshot_event = json.loads(await ws.recv())
            self.assertEqual(snapshot_event.get("event"), "terminal.render_grid")
            self.assertTrue(snapshot_event.get("data", {}).get("full"))

            # 6. Focus the newly created surface (Tab)
            await ws.send(json.dumps({
                "id": "req-focus-new",
                "method": "mobile.surface.focus",
                "params": {"surface_id": new_surf_id}
            }))
            focus_res = json.loads(await ws.recv())
            self.assertEqual(focus_res.get("id"), "req-focus-new")
            self.assertEqual(focus_res.get("result", {}).get("status"), "ok")

            # Priority refresh frame for newly focused surface
            new_surf_frame = json.loads(await ws.recv())
            self.assertEqual(new_surf_frame.get("event"), "terminal.render_grid")
            self.assertEqual(new_surf_frame.get("data", {}).get("surface_id"), new_surf_id)

            # 7. Send input to the new surface
            await ws.send(json.dumps({
                "id": "req-input",
                "method": "mobile.terminal.input",
                "params": {
                    "surface_id": new_surf_id,
                    "text": "help\n"
                }
            }))
            input_ack = json.loads(await ws.recv())
            self.assertEqual(input_ack.get("id"), "req-input")
            self.assertEqual(input_ack.get("result", {}).get("status"), "ok")

            # Frame broadcast for new surface following input (ACK precedes frame)
            frame_event = json.loads(await ws.recv())
            self.assertEqual(frame_event.get("event"), "terminal.render_grid")
            self.assertEqual(frame_event.get("data", {}).get("surface_id"), new_surf_id)

            # 8. Close the Surface (Tab)
            await ws.send(json.dumps({
                "id": "req-surf-close",
                "method": "mobile.surface.close",
                "params": {
                    "surface_id": new_surf_id,
                    "workspace_key": new_ws_key
                }
            }))
            close_res = json.loads(await ws.recv())
            self.assertEqual(close_res.get("id"), "req-surf-close")
            self.assertEqual(close_res.get("result", {}).get("status"), "ok")

    async def test_replay_rpc_scrollback_options(self):
        async with websockets.connect(self.ws_url) as ws:
            # Authenticate
            await ws.send(json.dumps({"type": "auth", "token": self.token}))
            auth_res = json.loads(await ws.recv())
            self.assertEqual(auth_res.get("type"), "auth_ok")

            # 1. Explicit replay with max_scrollback_rows
            await ws.send(json.dumps({
                "id": "req-replay-sb",
                "method": "mobile.terminal.replay",
                "params": {
                    "surface_id": "surf-main-1",
                    "max_scrollback_rows": 500,
                }
            }))
            rpc_res = json.loads(await ws.recv())
            self.assertEqual(rpc_res.get("id"), "req-replay-sb")
            data = rpc_res.get("result", {})
            self.assertEqual(data.get("surface_id"), "surf-main-1")
            self.assertEqual(data.get("history_rows"), 500)
            self.assertEqual(data.get("row_space_revision"), 1)
            self.assertIn("scrollback_spans", data)

            event_msg = json.loads(await ws.recv())
            self.assertEqual(event_msg.get("event"), "terminal.render_grid")
            self.assertEqual(event_msg.get("data", {}).get("history_rows"), 500)

            # 2. Ordinary replay without max_scrollback_rows (slim frame)
            await ws.send(json.dumps({
                "id": "req-replay-nosb",
                "method": "mobile.terminal.replay",
                "params": {
                    "surface_id": "surf-main-1",
                }
            }))
            rpc_res_no = json.loads(await ws.recv())
            self.assertEqual(rpc_res_no.get("id"), "req-replay-nosb")
            data_no = rpc_res_no.get("result", {})
            self.assertEqual(data_no.get("history_rows"), 500)
            self.assertEqual(data_no.get("row_space_revision"), 1)
            self.assertNotIn("scrollback_spans", data_no)
            self.assertNotIn("scrollback_rows", data_no)

            event_msg_no = json.loads(await ws.recv())
            self.assertEqual(event_msg_no.get("event"), "terminal.render_grid")
            self.assertNotIn("scrollback_spans", event_msg_no.get("data", {}))

    async def test_mutation_id_echo(self):
        async with websockets.connect(self.ws_url) as ws:
            # Auth
            await ws.send(json.dumps({"type": "auth", "token": self.token}))
            auth_res = json.loads(await ws.recv())
            self.assertEqual(auth_res.get("type"), "auth_ok")

            # Subscribe to workspace.tree
            await ws.send(json.dumps({
                "id": "req-sub-ws",
                "method": "mobile.events.subscribe",
                "params": {"topics": ["workspace.tree"]}
            }))
            await ws.recv()  # sub ack

            # 1. Workspace create with mutation_id
            await ws.send(json.dumps({
                "id": "req-ws-mut",
                "method": "mobile.workspace.create",
                "params": {
                    "name": "mut-ws-test",
                    "mutation_id": "mut-ws-1234",
                }
            }))
            ws_rpc_res = json.loads(await ws.recv())
            self.assertEqual(ws_rpc_res.get("id"), "req-ws-mut")
            self.assertEqual(ws_rpc_res.get("result", {}).get("mutation_id"), "mut-ws-1234")
            ws_created = ws_rpc_res["result"]["workspace"]
            ws_key = ws_created.get("key") or ws_created.get("id")

            ws_event = json.loads(await ws.recv())
            self.assertEqual(ws_event.get("event"), "workspace.tree")
            self.assertEqual(ws_event.get("data", {}).get("action"), "workspace_created")
            self.assertEqual(ws_event.get("data", {}).get("mutation_id"), "mut-ws-1234")

            # 2. Surface create with mutation_id
            await ws.send(json.dumps({
                "id": "req-surf-mut",
                "method": "mobile.surface.create",
                "params": {
                    "workspace_key": ws_key,
                    "title": "mut-tab",
                    "mutation_id": "mut-surf-5678",
                }
            }))
            surf_rpc_res = json.loads(await ws.recv())
            self.assertEqual(surf_rpc_res.get("id"), "req-surf-mut")
            self.assertEqual(surf_rpc_res.get("result", {}).get("mutation_id"), "mut-surf-5678")
            surf_id = surf_rpc_res["result"]["surface"]["id"]

            surf_event = json.loads(await ws.recv())
            self.assertEqual(surf_event.get("event"), "workspace.tree")
            self.assertEqual(surf_event.get("data", {}).get("action"), "surface_created")
            self.assertEqual(surf_event.get("data", {}).get("mutation_id"), "mut-surf-5678")

            # 3. Surface close with mutation_id
            await ws.send(json.dumps({
                "id": "req-close-mut",
                "method": "mobile.surface.close",
                "params": {
                    "surface_id": surf_id,
                    "workspace_key": ws_key,
                    "mutation_id": "mut-close-9999",
                }
            }))
            close_rpc_res = json.loads(await ws.recv())
            self.assertEqual(close_rpc_res.get("id"), "req-close-mut")
            self.assertEqual(close_rpc_res.get("result", {}).get("mutation_id"), "mut-close-9999")

            close_event = json.loads(await ws.recv())
            self.assertEqual(close_event.get("event"), "workspace.tree")
            self.assertEqual(close_event.get("data", {}).get("action"), "surface_closed")
            self.assertEqual(close_event.get("data", {}).get("mutation_id"), "mut-close-9999")

    async def test_ack_before_slow_snapshot(self):
        async with websockets.connect(self.ws_url) as ws:
            await ws.send(json.dumps({"type": "auth", "token": self.token}))
            await ws.recv()

            # Focus surface first so active_surface_id is set
            await ws.send(json.dumps({
                "id": "req-focus-ack",
                "method": "mobile.surface.focus",
                "params": {"surface_id": "surf-main-1"}
            }))
            focus_ack = json.loads(await ws.recv())
            self.assertEqual(focus_ack.get("id"), "req-focus-ack")

            await ws.send(json.dumps({
                "id": "req-sub-rg",
                "method": "mobile.events.subscribe",
                "params": {"topics": ["terminal.render_grid"]}
            }))
            await ws.recv()  # sub ack
            await ws.recv()  # initial snapshot

            # Introduce artificial delay to snapshot retrieval
            original_get_snapshot = self.gateway.backend.get_snapshot
            import time as _t
            def slow_get_snapshot(sid, max_sb=0):
                _t.sleep(0.2)
                return original_get_snapshot(sid, max_sb)

            self.gateway.backend.get_snapshot = slow_get_snapshot

            t0 = _t.perf_counter()
            await ws.send(json.dumps({
                "id": "req-input-ack-fast",
                "method": "mobile.terminal.input",
                "params": {
                    "surface_id": "surf-main-1",
                    "text": "echo fast\n",
                }
            }))

            ack_msg = json.loads(await ws.recv())
            ack_latency = _t.perf_counter() - t0

            # ACK must arrive BEFORE the slow snapshot finishes (< 0.15s)
            self.assertEqual(ack_msg.get("id"), "req-input-ack-fast")
            self.assertEqual(ack_msg.get("result", {}).get("status"), "ok")
            self.assertLess(ack_latency, 0.15)

            # Then snapshot event arrives after delay
            frame_msg = json.loads(await ws.recv())
            self.assertEqual(frame_msg.get("event"), "terminal.render_grid")
            self.assertEqual(frame_msg.get("data", {}).get("surface_id"), "surf-main-1")
    async def test_write_failure_no_ack(self):
        async with websockets.connect(self.ws_url) as ws:
            await ws.send(json.dumps({"type": "auth", "token": self.token}))
            await ws.recv()

            # Simulate backend write failure
            def failing_send_input(sid, text):
                raise RuntimeError("Simulated host write failure")

            self.gateway.backend.send_input = failing_send_input

            await ws.send(json.dumps({
                "id": "req-fail-input",
                "method": "mobile.terminal.input",
                "params": {
                    "surface_id": "surf-main-1",
                    "text": "bad input",
                }
            }))

            res = json.loads(await ws.recv())
            self.assertEqual(res.get("id"), "req-fail-input")
            self.assertIn("error", res)
            self.assertEqual(res["error"].get("code"), -32000)
            self.assertNotIn("result", res)

    async def test_immediate_priority_refresh(self):
        async with websockets.connect(self.ws_url) as ws:
            await ws.send(json.dumps({"type": "auth", "token": self.token}))
            await ws.recv()

            # Focus surface first so active_surface_id is set
            await ws.send(json.dumps({
                "id": "req-focus-prio",
                "method": "mobile.surface.focus",
                "params": {"surface_id": "surf-main-1"}
            }))
            focus_ack = json.loads(await ws.recv())
            self.assertEqual(focus_ack.get("id"), "req-focus-prio")

            await ws.send(json.dumps({
                "id": "req-sub-prio",
                "method": "mobile.events.subscribe",
                "params": {"topics": ["terminal.render_grid"]}
            }))
            await ws.recv()  # sub ack
            await ws.recv()  # initial snapshot

            import time as _t
            t0 = _t.perf_counter()
            await ws.send(json.dumps({
                "id": "req-prio-input",
                "method": "mobile.terminal.input",
                "params": {
                    "surface_id": "surf-main-1",
                    "text": "help\n",
                }
            }))

            ack = json.loads(await ws.recv())
            self.assertEqual(ack.get("id"), "req-prio-input")
            frame = json.loads(await ws.recv())
            total_dt = _t.perf_counter() - t0

            self.assertEqual(frame.get("event"), "terminal.render_grid")
            self.assertEqual(frame.get("data", {}).get("surface_id"), "surf-main-1")
            # Priority refresh arrives quickly (< 0.1s)
            self.assertLess(total_dt, 0.1)
    async def test_latest_full_coalescing_bounded_state(self):
        from cmux_gateway import CmuxGatewayClientSession
        client = CmuxGatewayClientSession(None, self.gateway)
        client.authenticated = True
        client.subscribed_topics.add("terminal.render_grid")
        client.set_active_surface("surf-main-1")
        focus_gen = client.focus_generation

        # Enqueue 3 full frames without running writer
        frame1 = {"surface_id": "surf-main-1", "format": "cmux.render-grid.v1", "state_seq": 1}
        frame2 = {"surface_id": "surf-main-1", "format": "cmux.render-grid.v1", "state_seq": 2}
        frame3 = {"surface_id": "surf-main-1", "format": "cmux.render-grid.v1", "state_seq": 3}

        client.enqueue_render_frame("surf-main-1", focus_gen, frame1)
        client.enqueue_render_frame("surf-main-1", focus_gen, frame2)
        client.enqueue_render_frame("surf-main-1", focus_gen, frame3)

        # Only frame3 is stored in latest-wins slot
        self.assertIsNotNone(client._latest_render_frame)
        self.assertEqual(client._latest_render_frame[2]["state_seq"], 3)

        # Control queue is empty (render frames do not inflate control queue)
        self.assertEqual(client._control_queue.qsize(), 0)
        await client.close()

    async def test_send_json_completion_before_close(self):
        # Verify auth_error is guaranteed received before socket is closed by server
        async with websockets.connect(self.ws_url) as ws:
            await ws.send(json.dumps({"type": "auth", "token": "invalid-pass-999"}))
            msg = json.loads(await ws.recv())
            self.assertEqual(msg.get("type"), "auth_error")
            self.assertEqual(msg.get("reason"), "invalid_token")
            # Socket should now be closed with code 1008
            with self.assertRaises(websockets.exceptions.ConnectionClosed):
                await ws.recv()

    async def test_delta_frame_triggers_full_recovery(self):
        from cmux_gateway import CmuxGatewayClientSession
        client = CmuxGatewayClientSession(None, self.gateway)
        client.authenticated = True
        client.subscribed_topics.add("terminal.render_grid")
        client.set_active_surface("surf-main-1")
        focus_gen = client.focus_generation

        delta1 = {"surface_id": "surf-main-1", "format": "cmux.render-grid.v1", "state_seq": 1, "full": False}

        # Clear priority surfaces before test
        self.gateway._priority_surfaces.clear()

        client.enqueue_render_frame("surf-main-1", focus_gen, delta1)

        # Non-full delta frame must not inflate control queue or enter latest-wins slot
        self.assertEqual(client._control_queue.qsize(), 0)
        self.assertIsNone(client._latest_render_frame)

        # Authoritative full snapshot recovery must be scheduled for that surface
        self.assertIn("surf-main-1", self.gateway._priority_surfaces)
        await client.close()

    async def test_same_surface_snapshot_serialization_and_multi_surface_concurrency(self):
        import time as _t
        surf_a_order = []
        events_timeline = []

        orig_get_snapshot = self.gateway.backend.get_snapshot

        def mock_get_snapshot(sid, max_sb=0):
            if sid == "surf-slow":
                events_timeline.append(("start", sid, max_sb, _t.perf_counter()))
                _t.sleep(0.20)
                res = orig_get_snapshot("surf-main-1", max_sb)
                res["surface_id"] = sid
                res["state_seq"] = 101
                events_timeline.append(("end", sid, max_sb, _t.perf_counter()))
                surf_a_order.append(max_sb)
                return res
            elif sid == "surf-fast-diff":
                events_timeline.append(("start", sid, max_sb, _t.perf_counter()))
                _t.sleep(0.01)
                res = orig_get_snapshot("surf-main-1", max_sb)
                res["surface_id"] = sid
                res["state_seq"] = 201
                events_timeline.append(("end", sid, max_sb, _t.perf_counter()))
                return res
            else:
                return orig_get_snapshot(sid, max_sb)

        self.gateway.backend.get_snapshot = mock_get_snapshot

        t0 = _t.perf_counter()
        # Launch 1 slow 500-row replay on surf-slow, 1 fast poll on surf-slow, and 1 fast poll on surf-fast-diff concurrently
        task_replay_a = asyncio.create_task(self.gateway.get_surface_snapshot("surf-slow", 500))
        task_poll_a = asyncio.create_task(self.gateway.get_surface_snapshot("surf-slow", 0))
        task_diff_b = asyncio.create_task(self.gateway.get_surface_snapshot("surf-fast-diff", 0))

        res_diff_b = await task_diff_b
        t_diff_done = _t.perf_counter()

        res_replay_a = await task_replay_a
        res_poll_a = await task_poll_a

        # 1. Different surface completes concurrently without waiting for surf-slow (< 0.08s)
        self.assertLess(t_diff_done - t0, 0.08)
        self.assertEqual(res_diff_b["surface_id"], "surf-fast-diff")

        # 2. Same-surface calls on surf-slow are serialized in launch order (500 then 0), no overlap / order inversion
        self.assertEqual(surf_a_order, [500, 0])
        self.assertEqual(res_replay_a["surface_id"], "surf-slow")
        self.assertEqual(res_poll_a["surface_id"], "surf-slow")

        # Verify timeline: slow ended before fast started on the same surface
        slow_end = next(t for evt, sid, sb, t in events_timeline if evt == "end" and sid == "surf-slow" and sb == 500)
        fast_start = next(t for evt, sid, sb, t in events_timeline if evt == "start" and sid == "surf-slow" and sb == 0)
        self.assertGreaterEqual(fast_start, slow_end)

    async def test_close_surface_clears_active_surface_and_stops_polling(self):
        async with websockets.connect(self.ws_url) as ws:
            await ws.send(json.dumps({"type": "auth", "token": self.token}))
            await ws.recv()

            # Create a dedicated surface to close
            await ws.send(json.dumps({
                "id": "req-surf-create-tmp",
                "method": "mobile.surface.create",
                "params": {"workspace_key": "ws-main", "title": "tmp-close"}
            }))
            res_create = json.loads(await ws.recv())
            tmp_surf_id = res_create["result"]["surface"]["id"]

            # Focus the surface
            await ws.send(json.dumps({
                "id": "req-focus-tmp",
                "method": "mobile.surface.focus",
                "params": {"surface_id": tmp_surf_id}
            }))
            res_focus = json.loads(await ws.recv())
            self.assertEqual(res_focus["result"]["status"], "ok")

            # Find the active client session on the gateway
            session = next(iter(self.gateway.clients))
            self.assertEqual(session.active_surface_id, tmp_surf_id)

            # Close the active surface
            await ws.send(json.dumps({
                "id": "req-close-tmp",
                "method": "mobile.surface.close",
                "params": {"surface_id": tmp_surf_id, "workspace_key": "ws-main"}
            }))
            res_close = json.loads(await ws.recv())
            self.assertEqual(res_close["result"]["status"], "ok")

            # Session active_surface_id must immediately become None to stop stale polling
            self.assertIsNone(session.active_surface_id)
            self.assertIsNone(session._latest_render_frame)


if __name__ == "__main__":
    unittest.main()
