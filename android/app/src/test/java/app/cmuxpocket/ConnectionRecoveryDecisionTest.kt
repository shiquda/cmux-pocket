package app.cmuxpocket

import app.cmuxpocket.transport.ConnectionStatus
import app.cmuxpocket.ui.probeFailureShouldReconnect
import app.cmuxpocket.ui.shouldRecoverConnectionOnPictureInPictureExit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRecoveryDecisionTest {
    @Test
    fun lateProbeFailureDoesNotReplaceANewerConnection() {
        assertFalse(
            probeFailureShouldReconnect(
                probeGeneration = 4,
                currentGeneration = 5,
                manualDisconnect = false,
                sameTarget = true,
                status = ConnectionStatus.CONNECTED,
            )
        )
    }

    @Test
    fun currentProbeFailureStillReplacesASilentSocket() {
        assertTrue(
            probeFailureShouldReconnect(
                probeGeneration = 4,
                currentGeneration = 4,
                manualDisconnect = false,
                sameTarget = true,
                status = ConnectionStatus.CONNECTED,
            )
        )
    }

    @Test
    fun leavingPictureInPictureRecoversWhileTheActivityStaysStarted() {
        assertTrue(
            shouldRecoverConnectionOnPictureInPictureExit(
                wasInPictureInPicture = true,
                isInPictureInPicture = false,
            )
        )
        assertFalse(
            shouldRecoverConnectionOnPictureInPictureExit(
                wasInPictureInPicture = false,
                isInPictureInPicture = true,
            )
        )
        assertFalse(
            shouldRecoverConnectionOnPictureInPictureExit(
                wasInPictureInPicture = false,
                isInPictureInPicture = false,
            )
        )
    }
}
