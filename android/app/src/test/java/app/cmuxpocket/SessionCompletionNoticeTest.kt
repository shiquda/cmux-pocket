package app.cmuxpocket

import app.cmuxpocket.protocol.AgentSessionCompleted
import app.cmuxpocket.protocol.SurfaceInfo
import app.cmuxpocket.protocol.WorkspaceInfo
import app.cmuxpocket.ui.isCurrentSession
import app.cmuxpocket.ui.sessionCompletionNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCompletionNoticeTest {

    private val backend = WorkspaceInfo(
        id = "ws-1",
        key = "backend",
        name = "backend",
        surfaces = listOf(
            SurfaceInfo(id = "surface-a", title = "tests", tabIndex = 0),
            SurfaceInfo(id = "surface-b", title = "logs", tabIndex = 1),
        )
    )

    @Test
    fun resolvesWorkspaceTabAndTitle() {
        val notice = sessionCompletionNotice(
            AgentSessionCompleted(surfaceId = "surface-b", workspaceId = "ws-1", agentKind = "claude"),
            listOf(backend)
        )

        assertEquals("backend", notice.workspaceName)
        assertEquals("#2", notice.tabLabel)
        assertEquals("logs", notice.surfaceTitle)
        assertEquals("ws-1", notice.workspaceId)
        assertEquals("claude", notice.agentKind)
    }

    @Test
    fun fallsBackToWorkspaceIdWhenSurfaceIsNotInTheTreeYet() {
        val notice = sessionCompletionNotice(
            AgentSessionCompleted(surfaceId = "surface-new", workspaceId = "ws-1"),
            listOf(backend)
        )

        assertEquals("backend", notice.workspaceName)
        assertNull(notice.tabLabel)
        assertEquals("session", notice.surfaceTitle)
    }

    @Test
    fun fallsBackWhenTheWorkspaceTreeHasNotLoaded() {
        val notice = sessionCompletionNotice(
            AgentSessionCompleted(surfaceId = "surface-x", eventId = "evt-9"),
            emptyList()
        )

        assertEquals("cmux", notice.workspaceName)
        assertEquals("evt-9", notice.key)
    }

    @Test
    fun currentSessionFallsBackToTheFirstRenderedSurface() {
        val surfaces = backend.surfaces
        assertTrue(
            isCurrentSession(AgentSessionCompleted(surfaceId = "surface-a"), selectedSurfaceId = null, activeSurfaces = surfaces)
        )
        assertTrue(
            isCurrentSession(AgentSessionCompleted(surfaceId = "surface-b"), selectedSurfaceId = "surface-b", activeSurfaces = surfaces)
        )
        assertFalse(
            isCurrentSession(AgentSessionCompleted(surfaceId = "surface-b"), selectedSurfaceId = null, activeSurfaces = surfaces)
        )
    }
}
