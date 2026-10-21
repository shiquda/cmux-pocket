package app.cmuxpocket.ui

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Languages the app ships dictionaries for.
 *
 * [ENGLISH] is the base dictionary (`res/values/`) and the default for a fresh install, so the app
 * never falls back to Chinese on its own.
 */
enum class AppLanguage(val tag: String, val nativeName: String) {
    ENGLISH("en", "English"),
    CHINESE("zh-CN", "简体中文");

    fun locale(): Locale = when (this) {
        ENGLISH -> Locale.ENGLISH
        CHINESE -> Locale.SIMPLIFIED_CHINESE
    }

    companion object {
        val DEFAULT: AppLanguage = ENGLISH

        fun fromTag(tag: String?): AppLanguage =
            values().firstOrNull { it.tag.equals(tag, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * Returns a [Context] whose resources resolve against [language].
 *
 * Applied from `attachBaseContext`, so every `stringResource` / `getString` call in the activity
 * (and anything it inflates) uses the chosen dictionary. The process-wide default locale is left
 * untouched.
 */
fun Context.withAppLanguage(language: AppLanguage): Context {
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
