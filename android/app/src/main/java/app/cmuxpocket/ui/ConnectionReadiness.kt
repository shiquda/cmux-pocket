package app.cmuxpocket.ui

import app.cmuxpocket.protocol.WorkspaceListResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

enum class ConnectionStep(val label: String) {
    NETWORK("Reach Gateway"),
    AUTHENTICATION("Accept token"),
    GATEWAY("Verify Gateway response"),
    SYNC("Subscribe to workspace updates"),
    WORKSPACES("Read cmux workspaces"),
    READY("Ready"),
}

data class ConnectionReadiness(
    val step: ConnectionStep = ConnectionStep.NETWORK,
    val started: Boolean = false,
    val problem: String? = null,
    val workspaceCount: Int = 0,
    val terminalCount: Int = 0,
) {
    val ready: Boolean get() = started && step == ConnectionStep.READY && problem == null
    val summary: String get() = when {
        problem != null -> problem
        ready -> "Ready · $workspaceCount workspaces · $terminalCount terminals"
        !started -> "Connection has not been checked"
        else -> "${step.label}…"
    }

    fun failed(message: String) = copy(problem = message)

    val help: String get() = when (step) {
        ConnectionStep.NETWORK -> "Check the host and port. For Tailscale, connect both devices to the same tailnet, keep the Mac awake, and run cmux-pocket setup --tailscale on the Mac. A failed connection alone cannot tell whether Tailscale or the Gateway is stopped."
        ConnectionStep.AUTHENTICATION -> "Use the Gateway token from the file reported by cmux-pocket token path on the Mac. This is separate from any cmux automation password."
        ConnectionStep.GATEWAY -> "Run cmux-pocket gateway probe on the Mac. Check that your HTTPS endpoint forwards WebSocket connections to the Pocket Gateway."
        ConnectionStep.WORKSPACES -> "Open cmux on the Mac and run cmux-pocket gateway probe. If cmux allows only cmux processes, stop the login service and run cmux-pocket gateway run in a cmux terminal. See Host setup below."
        ConnectionStep.SYNC -> "Reconnect to retry workspace updates. If this persists, update the Gateway and run cmux-pocket gateway probe on the Mac."
        ConnectionStep.READY -> if (terminalCount == 0) "The handshake succeeded. Open or create a terminal in cmux to see it here." else "The authenticated Gateway can read cmux workspaces."
    }
}

/** Readiness requires a real workspace query from the Gateway's process. */
suspend fun verifyGatewayConnection(
    callRpc: suspend (String) -> JsonElement,
    subscribe: suspend () -> Unit,
    onStep: (ConnectionStep) -> Unit,
): WorkspaceListResponse {
    onStep(ConnectionStep.GATEWAY)
    require(callRpc("mobile.host.status") is JsonObject) { "Invalid Gateway status response" }
    // Subscribe before fetching the baseline so changes during discovery are
    // still delivered to the session.
    onStep(ConnectionStep.SYNC)
    subscribe()
    onStep(ConnectionStep.WORKSPACES)
    val result = callRpc("mobile.workspace.list") as? JsonObject
        ?: error("Invalid workspace response")
    require(result["workspaces"] is JsonArray) { "Gateway response has no workspace list" }
    return Json { ignoreUnknownKeys = true }.decodeFromJsonElement(result)
}
