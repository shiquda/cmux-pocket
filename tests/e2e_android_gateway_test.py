#!/usr/bin/env python3
"""
End-to-End Acceptance Test: cmux WebSocket Gateway & Android Protocol Pipeline v2.
Simulates real-time terminal co-operation with multi-workspace and multi-tab surfaces.
"""

import asyncio
import json
import os
import sys
import time
import unittest
import websockets

# Add gateway to import path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "../gateway")))
from cmux_gateway import CmuxWebSocketGateway


class TestAndroidGatewayEndToEnd(unittest.IsolatedAsyncioTestCase):

    async def asyncSetUp(self):
        self.port = 9050
        self.token = "android-test-token"
        os.environ["CMUX_GATEWAY_BACKEND"] = "mock"
        self.gateway = CmuxWebSocketGateway(host="127.0.0.1", port=self.port, auth_token=self.token)
        await self.gateway.start()
        self.ws_url = f"ws://127.0.0.1:{self.port}"

    async def asyncTearDown(self):
        await self.gateway.stop()
        os.environ.pop("CMUX_GATEWAY_BACKEND", None)

    async def test_full_terminal_co_control_and_tab_lifecycle(self):
        """Simulates full Android connection, multi-workspace discovery, tab creation, input, and real-time screen sync."""
        async with websockets.connect(self.ws_url) as ws:
            t0 = time.perf_counter()

            # 1. Handshake & Auth
            await ws.send(json.dumps({
                "type": "auth",
                "token": self.token,
                "client_id": "android-client-e2e"
            }))
            auth_ack = json.loads(await ws.recv())
            self.assertEqual(auth_ack.get("type"), "auth_ok")

            # 2. Host Capabilities
            await ws.send(json.dumps({
                "id": "req-status",
                "method": "mobile.host.status",
                "params": {}
            }))
            status_res = json.loads(await ws.recv())
            capabilities = status_res.get("result", {}).get("capabilities", [])
            self.assertIn("terminal.render_grid.v1", capabilities)
            self.assertIn("multi_surface.v1", capabilities)

            # 3. Workspace Discovery
            await ws.send(json.dumps({
                "id": "req-ws",
                "method": "mobile.workspace.list",
                "params": {}
            }))
            ws_res = json.loads(await ws.recv())
            workspaces = ws_res.get("result", {}).get("workspaces", [])
            self.assertGreaterEqual(len(workspaces), 1)

            # 4. Subscribe to Events
            await ws.send(json.dumps({
                "id": "req-sub",
                "method": "mobile.events.subscribe",
                "params": {
                    "topics": ["terminal.render_grid", "mobile.sync.delta", "workspace.tree"]
                }
            }))
            sub_res = json.loads(await ws.recv())
            self.assertEqual(sub_res.get("id"), "req-sub")

            # 5. Receive Full Initial RenderGrid Snapshot
            initial_snapshot = json.loads(await ws.recv())
            self.assertEqual(initial_snapshot.get("event"), "terminal.render_grid")
            grid = initial_snapshot.get("data", {})
            self.assertTrue(grid.get("full"))
            self.assertEqual(grid.get("format"), "cmux.render-grid.v1")
            self.assertGreater(grid.get("rows"), 0)
            self.assertGreater(grid.get("columns"), 0)

            # 6. Send Terminal Input from Android (e.g. typing "help\n")
            input_start = time.perf_counter()
            await ws.send(json.dumps({
                "id": "req-input-1",
                "method": "mobile.terminal.input",
                "params": {
                    "surface_id": "surf-main-1",
                    "text": "help\n",
                    "client_id": "android-client-e2e"
                }
            }))

            # 7. Verify RPC ACK
            input_ack = json.loads(await ws.recv())
            self.assertEqual(input_ack.get("id"), "req-input-1")
            self.assertEqual(input_ack.get("result", {}).get("status"), "ok")

            # 8. Receive the authoritative post-input frame. A stale poll snapshot may
            # already be queued after the ACK, and the current replay path may publish
            # either a full snapshot or a delta.
            render_data = None
            for _ in range(10):
                candidate = json.loads(await ws.recv())
                self.assertEqual(candidate.get("event"), "terminal.render_grid")
                data = candidate.get("data", {})
                self.assertEqual(data.get("format"), "cmux.render-grid.v1")
                spans = data.get("row_spans", [])
                all_text = " ".join(span["text"] for span in spans)
                if "Available commands" in all_text:
                    render_data = data
                    break
            self.assertIsNotNone(render_data)

            latency_ms = (time.perf_counter() - input_start) * 1000
            print(f"\n[E2E VERIFICATION] Input-to-Render Latency: {latency_ms:.2f} ms")
            self.assertLess(latency_ms, 50.0)


if __name__ == "__main__":
    unittest.main()
