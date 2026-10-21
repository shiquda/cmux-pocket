package app.cmuxpocket.diagnostics

import app.cmuxpocket.transport.ConnectionStatus
import app.cmuxpocket.ui.SettingsManager
import android.content.Context
import android.os.Build
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Diagnostics and connection log buffer.
 *
 * When [isDiagnosticsEnabled] is true, records the latest 500 lines of WebSocket
 * handshake, state changes, and RPC traffic with sensitive tokens redacted.
 */
object DiagnosticsManager {

    private const val MAX_LOG_LINES = 500

    private val logBuffer = ConcurrentLinkedDeque<String>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Volatile
    var isEnabled: Boolean = false

    fun log(category: String, message: String, sensitiveTokens: List<String> = emptyList()) {
        if (!isEnabled) return

        val timestamp = synchronized(dateFormat) {
            dateFormat.format(Date())
        }

        val sanitizedMessage = redactSensitive(message, sensitiveTokens)
        val entry = "[$timestamp] [$category] $sanitizedMessage"

        logBuffer.addLast(entry)
        while (logBuffer.size > MAX_LOG_LINES) {
            logBuffer.pollFirst()
        }
    }

    fun getLogs(): List<String> {
        return logBuffer.toList()
    }

    fun clear() {
        logBuffer.clear()
    }

    fun redactSensitive(raw: String, additionalTokens: List<String> = emptyList()): String {
        var text = raw

        // Redact any explicitly supplied tokens
        for (token in additionalTokens) {
            if (token.isNotBlank() && token.length > 3) {
                text = text.replace(token, "***REDACTED***")
            }
        }

        // Redact common auth token query params or json fields
        text = text.replace(Regex("""(?i)(token\s*[:=]\s*["']?)([^"'&\s]+)(["']?)""")) { match ->
            "${match.groupValues[1]}***REDACTED***${match.groupValues[3]}"
        }
        text = text.replace(Regex("""(?i)(bearer\s+)([A-Za-z0-9_\-\.]+)""")) { match ->
            "${match.groupValues[1]}***REDACTED***"
        }

        return text
    }

    fun exportDiagnosticsBundle(
        context: Context,
        settingsManager: SettingsManager,
        connectionStatus: ConnectionStatus
    ): String {
        val json = Json { prettyPrint = true }

        val appVersion = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pInfo.versionName} (${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else @Suppress("DEPRECATION") pInfo.versionCode})"
        } catch (_: Exception) {
            "0.2.0 (unknown)"
        }

        val bundle = buildJsonObject {
            put("app", "cmux-pocket")
            put("version", appVersion)
            put("os", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("connectionStatus", connectionStatus.name)
            put("diagnosticsEnabled", isEnabled)
            put("exportedAt", synchronized(dateFormat) { dateFormat.format(Date()) })

            putJsonArray("profiles") {
                settingsManager.profiles.forEach { p ->
                    add(buildJsonObject {
                        put("id", p.id)
                        put("name", p.name)
                        put("host", p.host)
                        put("port", p.port)
                        put("tokenRedacted", p.token.isNotBlank())
                        put("isBuiltIn", p.isBuiltIn)
                    })
                }
            }

            putJsonArray("logs") {
                getLogs().forEach { line ->
                    add(JsonPrimitive(line))
                }
            }
        }

        return json.encodeToString(bundle)
    }
}
