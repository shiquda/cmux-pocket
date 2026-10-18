package app.cmuxpocket.ui

import java.net.URLDecoder

data class CmuxImportPayload(
    val version: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val token: String,
    val name: String,
    val isLoopback: Boolean
) {
    fun maskedToken(): String {
        return if (token.length <= 8) {
            "••••••••"
        } else {
            "${token.take(4)}••••${token.takeLast(4)}"
        }
    }

    fun websocketUrl(): String {
        val scheme = if (tls) "wss" else "ws"
        val hostFormatted = if (host.contains(':') && !host.startsWith('[')) {
            "[$host]"
        } else {
            host
        }
        return if ((tls && port == 443) || (!tls && port == 80)) {
            "$scheme://$hostFormatted"
        } else {
            "$scheme://$hostFormatted:$port"
        }
    }

    fun toConnectionProfile(): ConnectionProfile {
        return ConnectionProfile(
            name = name,
            host = if (tls && port == 443) host else if (!tls && isLoopback && port == 8088) host else websocketUrl(),
            port = port,
            token = token,
            lastUsedAt = System.currentTimeMillis()
        )
    }
}

object CmuxImportUri {
    const val CURRENT_VERSION = "1"
    const val SCHEME = "cmux-pocket"

    fun parse(uriString: String): CmuxImportPayload {
        val trimmed = uriString.trim()
        require(trimmed.isNotEmpty()) { "Import URI cannot be empty" }

        val schemeEnd = trimmed.indexOf("://")
        require(schemeEnd > 0) { "Invalid URI format: missing scheme delimiter" }

        val scheme = trimmed.substring(0, schemeEnd)
        require(scheme.equals(SCHEME, ignoreCase = true)) {
            "Invalid scheme '$scheme': expected '$SCHEME'"
        }

        val afterScheme = trimmed.substring(schemeEnd + 3)
        val queryStart = afterScheme.indexOf('?')
        val action = if (queryStart >= 0) afterScheme.substring(0, queryStart) else afterScheme
        require(
            action.equals("import", ignoreCase = true) ||
            action.equals("connect", ignoreCase = true) ||
            action.equals("setup", ignoreCase = true) ||
            action.isEmpty()
        ) {
            "Unsupported URI action '$action'"
        }

        require(queryStart >= 0 && queryStart < afterScheme.length - 1) {
            "Import URI missing configuration parameters"
        }

        val queryString = afterScheme.substring(queryStart + 1)
        val params = parseQueryParams(queryString)

        val version = params["v"] ?: throw IllegalArgumentException("Missing required parameter 'v'")
        require(version == CURRENT_VERSION) {
            "Unsupported configuration version '$version': expected '$CURRENT_VERSION'"
        }

        val host = (params["host"] ?: params["endpoint"])
            ?.trim()
            ?.trimEnd('/')
        require(!host.isNullOrEmpty()) { "Missing required parameter 'host'" }

        val isLoopback = isLoopbackHost(host)

        val tlsParam = params["tls"]?.lowercase()
        val tls = when (tlsParam) {
            "true", "1" -> true
            "false", "0" -> false
            else -> !isLoopback
        }

        if (!tls && !isLoopback) {
            throw IllegalArgumentException(
                "Cleartext WebSocket is only allowed for loopback connections; use https:// or wss://"
            )
        }

        val port = if (params.containsKey("port")) {
            val pStr = params["port"]!!.trim()
            val p = pStr.toIntOrNull() ?: throw IllegalArgumentException("Invalid port '$pStr': must be a valid integer")
            require(p in 1..65535) { "Invalid port '$p': must be between 1 and 65535" }
            p
        } else {
            if (tls) 443 else 8088
        }

        val token = params["token"]?.trim()
        require(!token.isNullOrEmpty()) { "Missing required parameter 'token'" }

        val name = params["name"]?.trim()?.ifBlank { null }
            ?: if (isLoopback) "USB / Local" else host

        return CmuxImportPayload(
            version = version,
            host = host,
            port = port,
            tls = tls,
            token = token,
            name = name,
            isLoopback = isLoopback
        )
    }

    private fun isLoopbackHost(host: String): Boolean {
        val clean = host.trim().trimStart('[').trimEnd(']')
        return clean.equals("localhost", ignoreCase = true) ||
               clean == "127.0.0.1" ||
               clean == "::1" ||
               clean.startsWith("127.")
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pairs = query.split('&')
        for (pair in pairs) {
            if (pair.isEmpty()) continue
            val eqIdx = pair.indexOf('=')
            if (eqIdx >= 0) {
                val key = URLDecoder.decode(pair.substring(0, eqIdx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(eqIdx + 1), "UTF-8")
                result[key] = value
            } else {
                val key = URLDecoder.decode(pair, "UTF-8")
                result[key] = ""
            }
        }
        return result
    }
}
