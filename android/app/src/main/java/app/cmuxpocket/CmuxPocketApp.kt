package app.cmuxpocket

import android.app.Application
import app.cmuxpocket.ui.AppLanguageStore
import app.cmuxpocket.ui.SettingsManager

/**
 * Owns the process-wide [SettingsManager] and seeds [AppLanguageStore] before any activity is
 * created, so `attachBaseContext` can localize resources without touching the keystore on the
 * startup path.
 */
class CmuxPocketApp : Application() {

    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(this)
        AppLanguageStore.current = settingsManager.appLanguage
    }

    companion object {
        lateinit var settingsManager: SettingsManager
            private set
    }
}
