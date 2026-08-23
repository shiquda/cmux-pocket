package app.cmuxpocket.ui

import kotlinx.serialization.Serializable
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
