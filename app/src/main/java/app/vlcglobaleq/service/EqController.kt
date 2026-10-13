package app.vlcglobaleq.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import app.vlcglobaleq.audio.EqEngine
import app.vlcglobaleq.audio.EqStatus
import app.vlcglobaleq.data.Preset
import app.vlcglobaleq.data.PresetStore

/**
 * Process-wide bridge between the UI / tile / receivers and the running [EqService].
 * Everything here runs on the main thread.
 */
object EqController {
    /** Set by [EqService] while it is running. */
    internal var engine: EqEngine? = null

    var status: EqStatus = EqStatus.Off
        internal set

    private val listeners = LinkedHashSet<(EqStatus) -> Unit>()

    fun addListener(l: (EqStatus) -> Unit) {
        listeners += l
        l(status)
    }

    fun removeListener(l: (EqStatus) -> Unit) {
        listeners -= l
    }

    internal fun publish(s: EqStatus) {
        status = s
        listeners.toList().forEach { it(s) }
    }

    /**
     * Turns the EQ on or off and remembers the choice for boot. Returns false if the
     * service could not be started (the saved flag is then reset to off).
     */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val store = PresetStore.get(context)
        store.enabled = enabled
        if (!enabled) {
            context.stopService(Intent(context, EqService::class.java))
            return true
        }
        return startService(context, EqService.ACTION_START).also { if (!it) store.enabled = false }
    }

    /** Pushes the preset (and current settings) to the live effect, without touching disk. */
    fun applyLive(context: Context, preset: Preset) {
        val store = PresetStore.get(context)
        engine?.apply(preset, store.autoPreamp, store.vlcExactCurve)
    }

    /** Re-reads settings (mode, active preset) and refreshes the notification. */
    fun refresh(context: Context) {
        if (engine != null) startService(context, EqService.ACTION_REFRESH)
    }

    fun startService(context: Context, action: String): Boolean = try {
        val intent = Intent(context, EqService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException and friends.
        Log.w("EqController", "Could not start EqService", e)
        false
    }
}
