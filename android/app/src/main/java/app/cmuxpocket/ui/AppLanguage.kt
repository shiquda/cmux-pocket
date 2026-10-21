package app.cmuxpocket.ui

import android.content.Context
import android.content.res.Configuration
import app.cmuxpocket.R
import java.util.Locale

/**
 * Languages the app ships dictionaries for.
 *
 * [SYSTEM] is the default for a fresh install: the app follows the device locale and resolves to
 * the closest shipped dictionary, so a Chinese phone starts in Chinese and everything else falls
 * back to the English base dictionary (`res/values/`).
 */
enum class AppLanguage(val tag: String, val nativeName: String) {
    SYSTEM("system", "System default"),
    ENGLISH("en", "English"),
    CHINESE("zh-CN", "简体中文");

    /** The concrete dictionary this choice uses for [locale]; [SYSTEM] follows the device. */
    fun resolved(locale: Locale = Locale.getDefault()): AppLanguage = when (this) {
        SYSTEM -> if (locale.language == "zh") CHINESE else ENGLISH
        else -> this
    }

    fun locale(): Locale = when (resolved()) {
        CHINESE -> Locale.SIMPLIFIED_CHINESE
        else -> Locale.ENGLISH
    }

    val labelRes: Int
        get() = when (this) {
            SYSTEM -> R.string.settings_language_system
            ENGLISH -> R.string.settings_language_english
            CHINESE -> R.string.settings_language_chinese
        }

    companion object {
        val DEFAULT: AppLanguage = SYSTEM

        fun fromTag(tag: String?): AppLanguage =
            values().firstOrNull { it.tag.equals(tag, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * Returns a [Context] whose resources resolve against [language].
 *
 * Applied from `attachBaseContext`, so every `stringResource` / `getString` call in the activity
 * (and anything it inflates) uses the chosen dictionary. [AppLanguage.SYSTEM] leaves the base
 * context untouched so the platform applies the device (or per-app) locale. The process-wide
 * default locale is left untouched either way.
 */
fun Context.withAppLanguage(language: AppLanguage): Context {
    if (language == AppLanguage.SYSTEM) return this
    val locale = language.locale()
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return createConfigurationContext(configuration)
}

/**
 * Holds the active language for contexts created before any `SettingsManager` is available.
 *
 * `attachBaseContext` runs before `onCreate`, and building the encrypted preference store there
 * would touch the Android keystore on the critical startup path. The application class seeds this
 * once from persisted settings instead.
 */
object AppLanguageStore {

    @Volatile
    var current: AppLanguage = AppLanguage.DEFAULT
}
