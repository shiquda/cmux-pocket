package app.cmuxpocket.ui

import app.cmuxpocket.protocol.AgentSessionCompleted
import app.cmuxpocket.protocol.SurfaceInfo
import app.cmuxpocket.protocol.WorkspaceInfo

/**
 * One completion worth surfacing in the in-app capsule. Built from the Gateway event and the
 * workspace tree so the banner can name the workspace and tab without another round trip.
 */
data class SessionCompletionNotice(
    val key: String,
    val surfaceId: String,
    val workspaceId: String?,
    val workspaceName: String,
    val tabLabel: String?,
    val surfaceTitle: String,
    val agentKind: String?
)

/**
 * Resolves [completion] against [workspaces]. Falls back to the surface id when the tree has not
 * caught up yet, so an event that arrives mid-sync still produces a tappable notice.
 */
fun sessionCompletionNotice(
    completion: AgentSessionCompleted,
    workspaces: List<WorkspaceInfo>,
): SessionCompletionNotice {
    val workspace = workspaces.firstOrNull { ws ->
        ws.surfaces.any { it.id == completion.surfaceId } ||
            (completion.workspaceId != null &&
                (ws.id == completion.workspaceId || ws.stableKey == completion.workspaceId))
    }
    val surface = workspace?.surfaces?.firstOrNull { it.id == completion.surfaceId }
    return SessionCompletionNotice(
        key = completion.eventId ?: "${completion.surfaceId}:${System.currentTimeMillis()}",
        surfaceId = completion.surfaceId,
        workspaceId = completion.workspaceId ?: workspace?.id,
        workspaceName = workspace?.name?.takeIf { it.isNotBlank() } ?: "cmux",
        tabLabel = surface?.let { "#${it.tabIndex + 1}" },
        surfaceTitle = surface?.displayTitle ?: "session",
        agentKind = completion.agentKind?.takeIf { it.isNotBlank() },
    )
}

/**
 * Label shown on the system notification so grouped completions stay distinguishable:
 * `workspace › #N title`. Null when the workspace tree has not resolved the surface yet.
 */
fun sessionNotificationLabel(
    completion: AgentSessionCompleted,
    workspaces: List<WorkspaceInfo>,
): String? {
    val workspace = workspaces.firstOrNull { ws -> ws.surfaces.any { it.id == completion.surfaceId } }
        ?: return null
    val surface = workspace.surfaces.first { it.id == completion.surfaceId }
    return "${workspace.name} › #${surface.tabIndex + 1} ${surface.displayTitle}"
}

/**
 * True when the finished surface is the one currently rendered. `selectedSurfaceId` may be stale
 * or absent, and the stage then falls back to the first surface, exactly like the island pill.
 */
fun isCurrentSession(completion: AgentSessionCompleted, selectedSurfaceId: String?, activeSurfaces: List<SurfaceInfo>): Boolean {
    val current = activeSurfaces.firstOrNull { it.id == selectedSurfaceId } ?: activeSurfaces.firstOrNull()
    return current != null && current.id == completion.surfaceId
}
