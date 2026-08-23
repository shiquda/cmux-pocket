package app.cmuxpocket

import app.cmuxpocket.protocol.SurfaceInfo
import app.cmuxpocket.protocol.WorkspaceInfo
import app.cmuxpocket.protocol.WorkspaceListResponse
import app.cmuxpocket.protocol.WorkspaceSelection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class WorkspaceModelTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testWorkspaceListDeserialization() {
        val jsonStr = """
            {
                "workspaces": [
                    {
                        "id": "ws-1",
                        "key": "ws-key-1",
                        "name": "cmux-main",
                        "order": 0,
                        "active_on_host": true,
                        "surfaces": [
                            {"id": "surf-1", "type": "terminal", "title": "zsh"},
                            {"id": "surf-2", "type": "terminal", "title": "Claude Code", "agent_state": "working"}
                        ]
                    },
                    {
                        "id": "ws-2",
                        "name": "android-dev",
                        "surfaces": [
                            {"id": "surf-3", "type": "terminal"}
                        ]
                    }
                ]
            }
        """.trimIndent()

        val resp = json.decodeFromString(WorkspaceListResponse.serializer(), jsonStr)
        assertEquals(2, resp.workspaces.size)

        val ws1 = resp.workspaces[0]
        assertEquals("ws-1", ws1.id)
        assertEquals("ws-key-1", ws1.stableKey)
        assertEquals("cmux-main", ws1.name)
        assertTrue(ws1.activeOnHost)
        assertEquals(2, ws1.surfaces.size)

        val surf2 = ws1.surfaces[1]
        assertEquals("Claude Code", surf2.displayTitle)
        assertEquals("working", surf2.agentState)

        val ws2 = resp.workspaces[1]
        assertEquals("ws-2", ws2.stableKey) // Fallback to id when key is null
        assertEquals(1, ws2.surfaces.size)
        assertEquals("Terminal", ws2.surfaces[0].displayTitle) // Fallback title
    }

    @Test
    fun testWorkspacePathAndTabLabels() {
        val ws = WorkspaceInfo(
            id = "ws-1",
            name = "π > 重构横屏设置面板与主题引擎",
            cwd = "~/repo/cmux-android",
            surfaces = listOf(
                SurfaceInfo(id = "s1", title = "agent"),
                SurfaceInfo(id = "s2", title = "zsh")
            )
        )
        assertEquals("2 tabs", ws.tabCountLabel)
        assertEquals("~/repo/cmux-android", ws.pathLabel)
        assertTrue(ws.surfaces[0].requiresCloseConfirmation())
        assertFalse(SurfaceInfo(id = "dead", dead = true).requiresCloseConfirmation())
    }

    @Test
    fun testSelectionStaysOnPhoneWorkspaceWhenHostChanges() {
        val host = WorkspaceInfo(
            id = "host",
            name = "Mac focused",
            activeOnHost = true,
            surfaces = listOf(SurfaceInfo(id = "h1"))
        )
        val phone = WorkspaceInfo(
            id = "phone",
            name = "Phone focused",
            activeOnHost = false,
            surfaces = listOf(SurfaceInfo(id = "p1"), SurfaceInfo(id = "p2"))
        )
        val first = WorkspaceSelection.reconcile(listOf(host, phone), null, null)
        assertEquals("host", first.first)
        assertEquals("h1", first.second)

        val sticky = WorkspaceSelection.reconcile(listOf(host, phone), "phone", "p2")
        assertEquals("phone", sticky.first)
        assertEquals("p2", sticky.second)
    }

    @Test
    fun testReconciliationFallbacksWhenSelectedSurfaceClosed() {
        val ws1 = WorkspaceInfo(
            id = "ws-1",
            key = "ws-key-1",
            name = "cmux-main",
            surfaces = listOf(
                SurfaceInfo(id = "surf-1", title = "Tab 1"),
                SurfaceInfo(id = "surf-2", title = "Tab 2")
            )
        )
        val initialWorkspaces = listOf(ws1)

        // Selected surface is surf-1
        val (wsKey1, surfId1) = WorkspaceSelection.reconcile(initialWorkspaces, "ws-key-1", "surf-1")
        assertEquals("ws-key-1", wsKey1)
        assertEquals("surf-1", surfId1)

        // surf-1 is closed: new workspace list only has surf-2
        val wsAfterClose = ws1.copy(surfaces = listOf(SurfaceInfo(id = "surf-2", title = "Tab 2")))
        val (wsKey2, surfId2) = WorkspaceSelection.reconcile(listOf(wsAfterClose), "ws-key-1", "surf-1")
        assertEquals("ws-key-1", wsKey2)
        assertEquals("surf-2", surfId2) // Falls back to remaining tab surf-2

        // All tabs closed: surfaces is empty
        val wsEmpty = ws1.copy(surfaces = emptyList())
        val (wsKey3, surfId3) = WorkspaceSelection.reconcile(listOf(wsEmpty), "ws-key-1", "surf-2")
        assertEquals("ws-key-1", wsKey3)
        assertNull(surfId3) // Falls back to null when no surfaces remain
    }

    @Test
    fun testNullableSurfaceFocusParamEncoding() {
        // Test non-null surface_id
        val nonNullParams = buildJsonObject {
            put("surface_id", "surf-42")
            put("client_id", "android-client")
        }
        assertEquals("""{"surface_id":"surf-42","client_id":"android-client"}""", nonNullParams.toString())

        // Test null surface_id (clearing client focus on Gateway)
        val nullParams = buildJsonObject {
            put("surface_id", JsonNull)
            put("client_id", "android-client")
        }
        assertEquals("""{"surface_id":null,"client_id":"android-client"}""", nullParams.toString())
    }

    @Test
    fun testReconciliationTransitionsFromNullToNewTab() {
        val wsWithTab = WorkspaceInfo(
            id = "ws-1",
            key = "ws-key-1",
            name = "cmux-main",
            surfaces = listOf(
                SurfaceInfo(id = "surf-new-from-mac", title = "New Tab")
            )
        )

        // Previous selection was null (no tabs existed on phone)
        val (wsKey, surfId) = WorkspaceSelection.reconcile(listOf(wsWithTab), "ws-key-1", null)
        assertEquals("ws-key-1", wsKey)
        assertEquals("surf-new-from-mac", surfId)
    }
}
