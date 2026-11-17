package app.cmuxpocket.ui

import app.cmuxpocket.transport.ConnectionStatus

/**
 * A foreground liveness probe belongs to the connection generation that started it.
 * A failure from an older generation must not discard the socket that replaced it.
 */
internal fun probeFailureShouldReconnect(
    probeGeneration: Long,
    currentGeneration: Long,
    manualDisconnect: Boolean,
    sameTarget: Boolean,
    status: ConnectionStatus,
): Boolean =
    !manualDisconnect &&
        sameTarget &&
        probeGeneration == currentGeneration &&
        status == ConnectionStatus.CONNECTED

/**
 * Picture-in-picture keeps the activity STARTED, so leaving it does not run onStart.
 * Recovery has to be requested from that transition itself.
 */
internal fun shouldRecoverConnectionOnPictureInPictureExit(
    wasInPictureInPicture: Boolean,
    isInPictureInPicture: Boolean,
): Boolean = wasInPictureInPicture && !isInPictureInPicture
