package app.vlcglobaleq.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.vlcglobaleq.data.PresetStore

/** Restarts the EQ after boot or an app update if it was left enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                if (PresetStore.get(context).enabled) EqController.startService(context, EqService.ACTION_START)
        }
    }
}
