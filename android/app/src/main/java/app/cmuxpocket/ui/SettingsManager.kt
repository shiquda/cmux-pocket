package app.cmuxpocket.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SettingsManager(context: Context) {

    private val prefs: SharedPreferences = createEncryptedPreferences(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    init {
        migrateLegacyPreferences(context.applicationContext, prefs)
    }


    var host: String
        get() = prefs.getString(KEY_HOST, "127.0.0.1")?.ifBlank { "127.0.0.1" } ?: "127.0.0.1"
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, 8088)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var fontSizeSp: Float
        get() = prefs.getFloat(KEY_FONT_SIZE, 14.5f)
        set(value) = prefs.edit().putFloat(KEY_FONT_SIZE, value).apply()

    var themeMode: ThemeMode
        get() {
            val name = prefs.getString(KEY_THEME_MODE, ThemeMode.DARK.name) ?: ThemeMode.DARK.name
            return try { ThemeMode.valueOf(name) } catch (_: Exception) { ThemeMode.DARK }
        }
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value.name).apply()

    var terminalBgTheme: String
        get() = prefs.getString(KEY_TERMINAL_BG, "#1E1E1E") ?: "#1E1E1E"
        set(value) = prefs.edit().putString(KEY_TERMINAL_BG, value).apply()

    var activeProfileId: String?
        get() = prefs.getString(KEY_ACTIVE_PROFILE, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE_PROFILE, value).apply()

    var profiles: List<ConnectionProfile>
        get() {
            val raw = prefs.getString(KEY_PROFILES, null)
            val stored = if (raw.isNullOrBlank()) {
                emptyList()
            } else {
                try {
                    json.decodeFromString<List<ConnectionProfile>>(raw)
                } catch (_: Exception) {
                    emptyList()
                }
            }
            return mergeBuiltIn(stored)
        }
        set(value) = prefs.edit().putString(KEY_PROFILES, json.encodeToString(mergeBuiltIn(value))).apply()

    fun upsertProfile(profile: ConnectionProfile): List<ConnectionProfile> {
        val normalized = normalizeProfile(profile)
        val next = profiles.filterNot { it.id == normalized.id || sameEndpoint(it, normalized) } + normalized
        persistProfiles(next)
        activeProfileId = normalized.id
        host = normalized.host
        port = normalized.port
        token = normalized.token
        return profiles
    }

    fun applyProfile(profile: ConnectionProfile): List<ConnectionProfile> {
        return upsertProfile(profile.copy(lastUsedAt = System.currentTimeMillis()))
    }

    fun deleteProfile(id: String): List<ConnectionProfile> {
        if (id == ConnectionProfile.USB_ID) return profiles
        val next = profiles.filterNot { it.id == id }
        persistProfiles(next)
        if (activeProfileId == id) {
            val usb = next.first { it.id == ConnectionProfile.USB_ID }
            activeProfileId = usb.id
            host = usb.host
            port = usb.port
            token = usb.token
        }
        return profiles
    }

    private fun persistProfiles(next: List<ConnectionProfile>) {
        profiles = next
    }

    private fun sameEndpoint(a: ConnectionProfile, b: ConnectionProfile): Boolean {
        return a.host == b.host && a.port == b.port
    }

    private fun normalizeProfile(profile: ConnectionProfile): ConnectionProfile {
        if (profile.id == ConnectionProfile.USB_ID || (profile.host == "127.0.0.1" && profile.port == 8088)) {
            return ConnectionProfile.usb(token = profile.token.ifBlank { token }).copy(
                lastUsedAt = profile.lastUsedAt
            )
        }
        return profile.copy(
            host = profile.host.trim(),
            name = profile.name.trim().ifBlank { profile.host.trim().ifBlank { "Saved Host" } }
        )
    }

    private fun mergeBuiltIn(stored: List<ConnectionProfile>): List<ConnectionProfile> {
        val usbToken = stored.firstOrNull { it.id == ConnectionProfile.USB_ID }?.token?.ifBlank { null } ?: token
        val usb = ConnectionProfile.usb(token = usbToken).copy(
            lastUsedAt = stored.firstOrNull { it.id == ConnectionProfile.USB_ID }?.lastUsedAt ?: 0L
        )
        val others = stored
            .filterNot { it.id == ConnectionProfile.USB_ID || (it.host == "127.0.0.1" && it.port == 8088) }
            .sortedByDescending { it.lastUsedAt }
        return listOf(usb) + others
    }

    companion object {
        private const val LEGACY_PREFS_NAME = "cmux_preferences"
        private const val SECURE_PREFS_NAME = "cmux_preferences_secure"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_FONT_SIZE = "font_size_sp"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_TERMINAL_BG = "terminal_bg"
        private const val KEY_PROFILES = "connection_profiles"
        private const val KEY_ACTIVE_PROFILE = "active_profile_id"
    }

        private fun createEncryptedPreferences(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        private fun migrateLegacyPreferences(context: Context, encrypted: SharedPreferences) {
            val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            val values = legacy.all
            if (values.isEmpty()) {
                context.deleteSharedPreferences(LEGACY_PREFS_NAME)
                return
            }

            val editor = encrypted.edit()
            values.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            check(editor.commit()) { "Unable to migrate encrypted settings" }
            check(context.deleteSharedPreferences(LEGACY_PREFS_NAME)) { "Unable to remove legacy settings" }
        }
}
