package app.cmuxpocket.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 8088,
    val token: String = "",
    val lastUsedAt: Long = 0L
) {
    val isBuiltIn: Boolean get() = id == USB_ID

    fun endpointLabel(): String = if (host.contains("://")) host else "$host:$port"

    companion object {
        const val USB_ID = "usb-loopback"
        const val USB_NAME = "USB"

        fun usb(token: String = ""): ConnectionProfile {
            return ConnectionProfile(
                id = USB_ID,
                name = USB_NAME,
                host = "127.0.0.1",
                port = 8088,
                token = token,
                lastUsedAt = 0L
            )
        }
    }
}

/**
 * Clipboard backup format for the saved profiles. Credentials are opt-in: without them every
 * token is blanked, so the copied JSON carries endpoints and names but no access keys.
 */
fun encodeProfilesBackup(profiles: List<ConnectionProfile>, includeCredentials: Boolean): String {
    val json = Json { prettyPrint = true; encodeDefaults = true }
    val payload = if (includeCredentials) profiles else profiles.map { it.copy(token = "") }
    return json.encodeToString(payload)
}

/**
 * Matches each imported profile to the stored one it would overwrite. An import without
 * credentials must not clear a token the device already holds for that endpoint.
 */
fun mergeImportedProfiles(
    imported: List<ConnectionProfile>,
    existing: List<ConnectionProfile>
): List<ConnectionProfile> = imported.map { profile ->
    val match = existing.firstOrNull { it.id == profile.id }
        ?: existing.firstOrNull {
            it.host.trim() == profile.host.trim() && it.port == profile.port
        }
    if (profile.token.isBlank() && match != null) profile.copy(token = match.token) else profile
}

object ConnectionEndpoint {
    fun websocketUrl(hostInput: String, port: Int): String {
        val endpoint = hostInput.trim().trimEnd('/')
        require(endpoint.isNotEmpty()) { "Host is required" }
        return when {
            endpoint.startsWith("wss://", ignoreCase = true) -> endpoint
            endpoint.startsWith("https://", ignoreCase = true) ->
                "wss://${endpoint.substringAfter("://")}"
            endpoint.startsWith("ws://", ignoreCase = true) -> {
                require(isLoopbackHost(endpoint.substringAfter("://").substringBefore('/').substringBefore(':'))) {
                    "Cleartext WebSocket is only allowed for loopback connections; use wss://"
                }
                endpoint
            }
            endpoint.startsWith("http://", ignoreCase = true) -> {
                val authority = endpoint.substringAfter("://").substringBefore('/')
                require(isLoopbackHost(authority.substringBefore(':'))) {
                    "Cleartext WebSocket is only allowed for loopback connections; use https:// or wss://"
                }
                "ws://${endpoint.substringAfter("://")}"
            }
            isLoopbackHost(endpoint) -> "ws://$endpoint:$port"
            port == 443 -> "wss://$endpoint"
            else -> "wss://$endpoint:$port"
        }
    }

    private fun isLoopbackHost(host: String): Boolean {
        return host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "[::1]" || host == "::1"
    }
}

data class DiscoveredGateway(
    val host: String,
    val port: Int,
    val latencyMs: Long,
    val source: String
) {
    fun endpointLabel(): String = "$host:$port"
}
