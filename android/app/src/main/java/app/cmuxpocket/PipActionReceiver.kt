package app.cmuxpocket

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.cmuxpocket.ui.PipSizePreset
import app.cmuxpocket.ui.SettingsManager

/** Handles the floating-window size action shown inside the PiP window. */
class PipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CYCLE_SIZE) return
        val settingsManager = SettingsManager(context)
        settingsManager.pipAspectRatio = PipSizePreset.nextAfter(settingsManager.pipAspectRatio).ratio
        MainActivity.notifyPipSizeChanged()
    }

    companion object {
        const val ACTION_CYCLE_SIZE = "app.cmuxpocket.action.PIP_CYCLE_SIZE"
    }
}
