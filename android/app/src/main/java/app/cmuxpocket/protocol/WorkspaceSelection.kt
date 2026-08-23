package app.cmuxpocket.protocol

object WorkspaceSelection {
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
