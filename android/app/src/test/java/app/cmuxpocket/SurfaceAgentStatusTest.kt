package app.cmuxpocket

import app.cmuxpocket.protocol.SurfaceInfo
import app.cmuxpocket.ui.SurfaceAgentStatus
import app.cmuxpocket.ui.agentStatus
import app.cmuxpocket.ui.usesInAppCompletionFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceAgentStatusTest {

    private fun surface(
        agentState: String? = null,
        attention: Boolean = false,
        dead: Boolean = false,
    ) = SurfaceInfo(id = "surface:1", agentState = agentState, attention = attention, dead = dead)

    @Test
    fun gatewayLifecycleValuesMapDirectly() {
        assertEquals(SurfaceAgentStatus.WORKING, surface("working").agentStatus())
        assertEquals(SurfaceAgentStatus.NEEDS_INPUT, surface("needs_input").agentStatus())
        assertEquals(SurfaceAgentStatus.IDLE, surface("idle").agentStatus())
    }

    @Test
    fun legacyCmuxSpellingsMapToTheSameStates() {
        assertEquals(SurfaceAgentStatus.WORKING, surface("busy").agentStatus())
        assertEquals(SurfaceAgentStatus.WORKING, surface("launching").agentStatus())
        assertEquals(SurfaceAgentStatus.NEEDS_INPUT, surface("needsInput").agentStatus())
        assertEquals(SurfaceAgentStatus.NEEDS_INPUT, surface("needs-input").agentStatus())
        assertEquals(SurfaceAgentStatus.IDLE, surface("ended").agentStatus())
    }

    @Test
    fun missingStateIsUnknownRatherThanIdle() {
        assertEquals(SurfaceAgentStatus.UNKNOWN, surface(null).agentStatus())
        assertEquals(SurfaceAgentStatus.UNKNOWN, surface("").agentStatus())
        assertEquals(SurfaceAgentStatus.UNKNOWN, surface("mystery").agentStatus())
    }

    @Test
    fun attentionAloneMeansNeedsInput() {
        assertEquals(SurfaceAgentStatus.NEEDS_INPUT, surface(null, attention = true).agentStatus())
        assertEquals(SurfaceAgentStatus.NEEDS_INPUT, surface("mystery", attention = true).agentStatus())
    }

    @Test
    fun deadSurfaceWinsOverAnyAgentState() {
        assertEquals(SurfaceAgentStatus.DEAD, surface("working", dead = true).agentStatus())
    }

    @Test
    fun onlyWorkingAndNeedsInputCountAsBusy() {
        assertTrue(SurfaceAgentStatus.WORKING.isBusy)
        assertTrue(SurfaceAgentStatus.NEEDS_INPUT.isBusy)
        assertFalse(SurfaceAgentStatus.IDLE.isBusy)
        assertFalse(SurfaceAgentStatus.UNKNOWN.isBusy)
        assertFalse(SurfaceAgentStatus.DEAD.isBusy)
    }

    @Test
    fun onlyTheFullScreenForegroundKeepsInAppFeedback() {
        assertTrue(
            usesInAppCompletionFeedback(
                isStarted = true,
                isInPictureInPicture = false,
                isInMultiWindow = false,
            )
        )
        assertFalse(
            usesInAppCompletionFeedback(
                isStarted = true,
                isInPictureInPicture = true,
                isInMultiWindow = true,
            )
        )
        assertFalse(
            usesInAppCompletionFeedback(
                isStarted = true,
                isInPictureInPicture = false,
                isInMultiWindow = true,
            )
        )
        assertFalse(
            usesInAppCompletionFeedback(
                isStarted = false,
                isInPictureInPicture = false,
                isInMultiWindow = false,
            )
        )
    }
}
