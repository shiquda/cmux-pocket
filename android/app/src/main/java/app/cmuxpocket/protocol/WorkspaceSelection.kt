package app.cmuxpocket.protocol

object WorkspaceSelection {
    /**
     * Finds the workspace holding [surfaceId]. Surface ids are globally unique, so a stale or
     * foreign [workspaceId] (for example a cmux window id that is not in the mobile tree) must not
     * block a notification or capsule tap from landing on the finished session.
     */
    fun locateSurface(
        workspaces: List<WorkspaceInfo>,
        workspaceId: String?,
        surfaceId: String,
    ): WorkspaceInfo? {
        val candidates = workspaces.filter { ws -> ws.surfaces.any { it.id == surfaceId } }
        if (candidates.isEmpty()) return null
        if (workspaceId == null) return candidates.first()
        return candidates.firstOrNull { it.id == workspaceId || it.stableKey == workspaceId }
            ?: candidates.first()
    }

    fun reconcile(
        workspaces: List<WorkspaceInfo>,
        selectedWorkspaceKey: String?,
        selectedSurfaceId: String?,
    ): Pair<String?, String?> {
        val currentWs = workspaces.firstOrNull { it.stableKey == selectedWorkspaceKey }
            ?: if (selectedWorkspaceKey == null) {
                workspaces.firstOrNull { it.activeOnHost } ?: workspaces.firstOrNull()
            } else {
                workspaces.firstOrNull()
            }
        val surfaces = currentWs?.surfaces.orEmpty()
        val surface = surfaces.firstOrNull { it.id == selectedSurfaceId }
            ?: surfaces.firstOrNull { it.type == "terminal" }
            ?: surfaces.firstOrNull()
        return currentWs?.stableKey to surface?.id
    }
}
