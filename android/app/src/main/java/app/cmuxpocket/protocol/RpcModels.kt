package app.cmuxpocket.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class AuthRequest(
    @SerialName("type") val type: String = "auth",
    @SerialName("token") val token: String,
    @SerialName("client_id") val clientId: String = "android-client"
)

@Serializable
data class AuthResponse(
    @SerialName("type") val type: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("server_version") val serverVersion: String? = null,
    @SerialName("capabilities") val capabilities: List<String> = emptyList(),
    @SerialName("reason") val reason: String? = null
)

@Serializable
data class JsonRpcRequest(
    @SerialName("id") val id: String,
    @SerialName("method") val method: String,
    @SerialName("params") val params: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class JsonRpcResponse(
    @SerialName("id") val id: String? = null,
    @SerialName("result") val result: JsonElement? = null,
    @SerialName("error") val error: JsonRpcError? = null,
    @SerialName("event") val event: String? = null,
    @SerialName("data") val data: JsonElement? = null
)

@Serializable
data class JsonRpcError(
    @SerialName("code") val code: Int,
    @SerialName("message") val message: String,
    @SerialName("data") val data: JsonElement? = null
)

@Serializable
data class WorkspaceListResponse(
    @SerialName("workspaces") val workspaces: List<WorkspaceInfo> = emptyList()
)

@Serializable
data class WorkspaceInfo(
    @SerialName("id") val id: String,
    @SerialName("key") val key: String? = null,
    @SerialName("name") val name: String,
    @SerialName("order") val order: Int = 0,
    @SerialName("active_on_host") val activeOnHost: Boolean = false,
    @SerialName("cwd") val cwd: String? = null,
    @SerialName("surfaces") val surfaces: List<SurfaceInfo> = emptyList()
) {
    val stableKey: String get() = key ?: id
    val tabCountLabel: String get() = if (surfaces.size == 1) "1 tab" else "${surfaces.size} tabs"
    val pathLabel: String get() = cwd?.takeIf { it.isNotBlank() }.orEmpty()
}

@Serializable
data class SurfaceInfo(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String = "terminal",
    @SerialName("title") val title: String? = null,
    @SerialName("workspace_key") val workspaceKey: String? = null,
    @SerialName("pane_id") val paneId: String? = null,
    @SerialName("tab_index") val tabIndex: Int = 0,
    @SerialName("agent_state") val agentState: String? = null,
    @SerialName("attention") val attention: Boolean = false,
    @SerialName("dead") val dead: Boolean = false,
    @SerialName("cwd") val cwd: String? = null
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: (if (type == "terminal") "Terminal" else type)
    fun requiresCloseConfirmation(): Boolean = !dead
}

@Serializable
data class AgentSessionCompleted(
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("workspace_id") val workspaceId: String? = null,
    @SerialName("surface_id") val surfaceId: String,
    @SerialName("agent_kind") val agentKind: String? = null,
    @SerialName("category") val category: String = "turn-complete"
)

@Serializable
data class SurfaceCreateParam(
    @SerialName("type") val type: String = "terminal",
    @SerialName("title") val title: String? = null
)

@Serializable
data class WorkspaceCreateResponse(
    @SerialName("workspace") val workspace: WorkspaceInfo? = null,
    @SerialName("status") val status: String? = "ok",
    @SerialName("mutation_id") val mutationId: String? = null
)

@Serializable
data class SurfaceCreateResponse(
    @SerialName("surface") val surface: SurfaceInfo? = null,
    @SerialName("status") val status: String? = "ok",
    @SerialName("mutation_id") val mutationId: String? = null
)

@Serializable
data class SurfaceCloseResponse(
    @SerialName("status") val status: String? = "ok",
    @SerialName("surface_id") val surfaceId: String? = null,
    @SerialName("mutation_id") val mutationId: String? = null
)
