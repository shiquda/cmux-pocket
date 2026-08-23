package app.cmuxpocket

import app.cmuxpocket.engine.SurfaceSessionStore
import app.cmuxpocket.protocol.Cursor
import app.cmuxpocket.protocol.MobileTerminalRenderGridFrame
import app.cmuxpocket.protocol.RowSpan
import app.cmuxpocket.protocol.SurfaceInfo
import org.junit.Assert.*
import org.junit.Test

class SurfaceSessionStoreTest {

    @Test
    fun testMultiSurfaceIsolation() {
        val store = SurfaceSessionStore()

        val frameA = MobileTerminalRenderGridFrame(
            surfaceId = "surf-A",
            stateSeq = 1,
            renderEpoch = "epoch-A",
            columns = 80,
            rows = 24,
            full = true,
            cursor = Cursor(row = 0, column = 5, visible = true),
            rowSpans = listOf(RowSpan(row = 0, column = 0, styleId = 0, text = "Surface A Text"))
        )

        val frameB = MobileTerminalRenderGridFrame(
            surfaceId = "surf-B",
            stateSeq = 1,
            renderEpoch = "epoch-B",
            columns = 80,
            rows = 24,
            full = true,
            cursor = Cursor(row = 2, column = 10, visible = true),
            rowSpans = listOf(RowSpan(row = 2, column = 0, styleId = 0, text = "Surface B Text"))
        )

        store.routeFrame(frameA)
        store.routeFrame(frameB)

        val sessionA = store.getSession("surf-A")
        val sessionB = store.getSession("surf-B")

        assertNotNull(sessionA)
        assertNotNull(sessionB)

        val stateA = sessionA!!.screenState.value
        val stateB = sessionB!!.screenState.value

        assertEquals("surf-A", stateA.surfaceId)
        assertEquals("surf-B", stateB.surfaceId)

        assertEquals("epoch-A", stateA.renderEpoch)
        assertEquals("epoch-B", stateB.renderEpoch)

        assertEquals(0, stateA.cursor.row)
        assertEquals(2, stateB.cursor.row)

        assertEquals("S", stateA.grid[0][0].text)
        assertEquals("A", stateA.grid[0][8].text)

        assertEquals("S", stateB.grid[2][0].text)
        assertEquals("B", stateB.grid[2][8].text)
    }

    @Test
    fun testSyncFromSurfaces() {
        val store = SurfaceSessionStore()

        val surfaces = listOf(
            SurfaceInfo(id = "surf-1", title = "zsh", type = "terminal"),
            SurfaceInfo(id = "surf-2", title = "Claude Code", type = "terminal", agentState = "working"),
            SurfaceInfo(id = "surf-3", title = "tests", type = "terminal", attention = true)
        )

        store.syncFromSurfaces(surfaces, "ws-main")

        val s1 = store.getSession("surf-1")
        val s2 = store.getSession("surf-2")
        val s3 = store.getSession("surf-3")

        assertNotNull(s1)
        assertNotNull(s2)
        assertNotNull(s3)

        assertEquals("zsh", s1?.title)
        assertEquals("working", s2?.agentState)
        assertTrue(s3?.attention == true)

        store.removeSession("surf-3")
        assertNull(store.getSession("surf-3"))
    }
}
