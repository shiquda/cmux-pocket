package app.cmuxpocket.ui

import app.cmuxpocket.BuildConfig
import app.cmuxpocket.R
import app.cmuxpocket.protocol.GatewayCompatibility
import app.cmuxpocket.protocol.GatewayHostStatus
import app.cmuxpocket.protocol.GatewayVersions
import app.cmuxpocket.protocol.HostStatusResponse
import app.cmuxpocket.protocol.WorkspaceInfo
import app.cmuxpocket.protocol.WorkspaceListResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

enum class ConnectionStep(val labelRes: Int, val label: String) {
    NETWORK(R.string.conn_step_network, "Reach Gateway"),
    AUTHENTICATION(R.string.conn_step_auth, "Accept token"),
    GATEWAY(R.string.conn_step_gateway, "Verify Gateway response"),
    SYNC(R.string.conn_step_sync, "Subscribe to workspace updates"),
    WORKSPACES(R.string.conn_step_workspaces, "Read cmux workspaces"),
    READY(R.string.conn_step_ready, "Ready"),
}

data class ConnectionReadiness(
    val step: ConnectionStep = ConnectionStep.NETWORK,
    val started: Boolean = false,
    val problem: String? = null,
    val workspaceCount: Int = 0,
    val terminalCount: Int = 0,
    val hostStatus: GatewayHostStatus? = null,
) {
    val ready: Boolean get() = started && step == ConnectionStep.READY && problem == null
    val compatibility: GatewayCompatibility
        get() = GatewayVersions.evaluate(BuildConfig.VERSION_NAME, hostStatus)
    val cmuxStopped: Boolean get() = hostStatus?.let { !it.cmuxRunning } == true
    val summary: String get() = when {
        problem != null -> problem
        cmuxStopped -> "Mac online · cmux not running"
        ready -> "Ready · $workspaceCount workspaces · $terminalCount terminals"
        !started -> "Connection has not been checked"
        else -> "${step.label}…"
    }

    fun failed(message: String) = copy(problem = message)

    val helpRes: Int? get() = when (step) {
        ConnectionStep.NETWORK -> R.string.conn_help_network
        ConnectionStep.AUTHENTICATION -> R.string.conn_help_auth
        ConnectionStep.GATEWAY -> R.string.conn_help_gateway
        ConnectionStep.WORKSPACES -> R.string.conn_help_workspaces
        ConnectionStep.SYNC -> R.string.conn_help_sync
        ConnectionStep.READY -> null
    }

    val help: String get() = when (step) {
        ConnectionStep.NETWORK -> "Check the host and port. For Tailscale, connect both devices to the same tailnet, keep the Mac awake, and run cmux-pocket setup --tailscale on the Mac. A failed connection alone cannot tell whether Tailscale or the Gateway is stopped."
        ConnectionStep.AUTHENTICATION -> "Use the Gateway token from the file reported by cmux-pocket token path on the Mac. This is separate from any cmux automation password."
        ConnectionStep.GATEWAY -> "Run cmux-pocket gateway probe on the Mac. Check that your HTTPS endpoint forwards WebSocket connections to the Pocket Gateway."
        ConnectionStep.WORKSPACES -> "Open cmux on the Mac and run cmux-pocket gateway probe. If cmux allows only cmux processes, stop the login service and run cmux-pocket gateway run in a cmux terminal. See Host setup below."
        ConnectionStep.SYNC -> "Reconnect to retry workspace updates. If this persists, update the Gateway and run cmux-pocket gateway probe on the Mac."
        ConnectionStep.READY -> when {
            cmuxStopped -> "The Gateway is online but cmux is not running. Tap Wake cmux on Mac to launch it remotely."
            terminalCount == 0 -> "The handshake succeeded. Open or create a terminal in cmux to see it here."
            else -> "The authenticated Gateway can read cmux workspaces."
        }
    }
}

/** Host status and workspaces captured by one readiness handshake. */
data class GatewayHandshake(
    val hostStatus: GatewayHostStatus,
    val workspaces: List<WorkspaceInfo>,
)

/** Readiness requires a real workspace query from the Gateway's process. */
suspend fun verifyGatewayConnection(
    callRpc: suspend (String) -> JsonElement,
    subscribe: suspend () -> Unit,
    onStep: (ConnectionStep) -> Unit,
    onHostStatus: (GatewayHostStatus) -> Unit = {},
): GatewayHandshake {
    onStep(ConnectionStep.GATEWAY)
    val statusJson = callRpc("mobile.host.status") as? JsonObject
        ?: error("Invalid Gateway status response")
    val hostStatus = decodeHostStatus(statusJson)
    onHostStatus(hostStatus)

    // Subscribe before fetching the baseline so changes during discovery are
    // still delivered to the session.
    onStep(ConnectionStep.SYNC)
    subscribe()
    onStep(ConnectionStep.WORKSPACES)
    val result = callRpc("mobile.workspace.list") as? JsonObject
        ?: error("Invalid workspace response")
    require(result["workspaces"] is JsonArray) { "Gateway response has no workspace list" }
    val list = Json { ignoreUnknownKeys = true }.decodeFromJsonElement<WorkspaceListResponse>(result)
    return GatewayHandshake(hostStatus, list.workspaces)
}

/** Parses `mobile.host.status`, tolerating Gateways that predate the new fields. */
fun decodeHostStatus(statusJson: JsonObject): GatewayHostStatus {
    val response = runCatching {
        Json { ignoreUnknownKeys = true }
            .decodeFromJsonElement<HostStatusResponse>(statusJson)
    }.getOrNull() ?: return GatewayHostStatus()

    return GatewayHostStatus(
        gatewayVersion = response.gatewayVersion ?: response.macAppVersion,
        protocolVersion = response.protocolVersion,
        capabilities = response.capabilities,
        cmuxInstalled = response.cmuxStatus?.installed ?: false,
        // An older Gateway cannot report cmux state; do not claim it is stopped.
        cmuxRunning = response.cmuxStatus?.running ?: true,
        canWake = response.cmuxStatus?.canWake ?: false,
    )
}
