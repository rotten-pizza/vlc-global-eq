package app.vlcglobaleq.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.util.Log
import app.vlcglobaleq.data.EqMode
import app.vlcglobaleq.data.Preset

/**
 * Owns the DynamicsProcessing instances: one on session 0 (global output mix) and, in
 * per-session mode, one per session announced by a player. All DSP runs in AudioFlinger;
 * this class only keeps the effect objects alive and pushes parameters.
 *
 * Must be used from a single (main) thread.
 */
class EqEngine(private val onStatus: (EqStatus) -> Unit) {

    private var global: DynamicsProcessing? = null
    private val sessions = HashMap<Int, DynamicsProcessing>()
    private var mode = EqMode.GLOBAL
    private var preset: Preset? = null
    private var autoPreamp = false
    private var vlcExact = false
    private var globalError: String? = null

    var status: EqStatus = EqStatus.Off
        private set

    fun start(mode: EqMode, preset: Preset, autoPreamp: Boolean, vlcExact: Boolean) {
        this.mode = mode
        this.preset = preset
        this.autoPreamp = autoPreamp
        this.vlcExact = vlcExact
        if (mode == EqMode.SESSIONS) releaseGlobal() else if (global == null) createGlobal()
        if (mode == EqMode.GLOBAL) releaseSessions()
        applyAll()
        publish()
    }

    fun apply(preset: Preset, autoPreamp: Boolean = this.autoPreamp, vlcExact: Boolean = this.vlcExact) {
        this.preset = preset
        this.autoPreamp = autoPreamp
        this.vlcExact = vlcExact
        applyAll()
    }

    fun openSession(sessionId: Int) {
        if (mode == EqMode.GLOBAL || sessionId == 0 || sessions.containsKey(sessionId)) return
        try {
            val dp = DynamicsProcessing(PRIORITY, sessionId, buildConfig())
            sessions[sessionId] = dp
            preset?.let { applyTo(dp, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not attach to session $sessionId", e)
        }
        publish()
    }

    fun closeSession(sessionId: Int) {
        sessions.remove(sessionId)?.let { safeRelease(it) }
        publish()
    }

    fun release() {
        releaseGlobal()
        releaseSessions()
        status = EqStatus.Off
        onStatus(status)
    }

    private fun createGlobal() {
        globalError = null
        try {
            val dp = DynamicsProcessing(PRIORITY, 0, buildConfig())
            dp.setControlStatusListener { _, granted ->
                if (granted) preset?.let { p -> global?.let { applyTo(it, p) } }
                publish()
            }
            dp.setEnableStatusListener { _, _ -> publish() }
            if (dp.hasControl()) dp.enabled = true
            global = dp
        } catch (e: Exception) {
            // UnsupportedOperationException / RuntimeException when the device refuses session 0.
            Log.w(TAG, "Could not attach to the global output mix", e)
            globalError = e.message ?: e.javaClass.simpleName
        }
    }

    private fun releaseGlobal() {
        global?.let { safeRelease(it) }
        global = null
        globalError = null
    }

    private fun releaseSessions() {
        sessions.values.forEach { safeRelease(it) }
        sessions.clear()
    }

    private fun safeRelease(effect: AudioEffect) {
        try {
            effect.enabled = false
        } catch (_: Exception) {
        }
        try {
            effect.release()
        } catch (_: Exception) {
        }
    }

    private fun applyAll() {
        val p = preset ?: return
        global?.let { applyTo(it, p) }
        sessions.values.forEach { applyTo(it, p) }
        syncSessionEnable()
    }

    /**
     * In BOTH mode the per-session effects are only a fallback: they switch on while the
     * global effect is unavailable or lost, so audio is never equalized twice.
     */
    private fun syncSessionEnable() {
        val g = global
        val on = mode == EqMode.SESSIONS || g == null || !hasControl(g)
        sessions.values.forEach { dp ->
            try {
                if (dp.enabled != on) dp.enabled = on
            } catch (_: Exception) {
            }
        }
    }

    private fun applyTo(dp: DynamicsProcessing, p: Preset) {
        try {
            if (!dp.hasControl()) return
            val bands = CurveMapper.dpBands(p, vlcExact)
            bands.forEachIndexed { i, b ->
                dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, b.cutoffHz.toFloat(), b.gainDb.toFloat()))
            }
            dp.setInputGainAllChannelsTo(CurveMapper.inputGainDb(p, bands, autoPreamp).toFloat())
            if (dp === global && !dp.enabled) dp.enabled = true
        } catch (e: Exception) {
            // Lost control between the check and the call, or the effect died.
            Log.w(TAG, "Could not update effect", e)
        }
    }

    private fun publish() {
        syncSessionEnable()
        val g = global
        val n = sessions.size
        status = when {
            mode == EqMode.SESSIONS -> EqStatus.Sessions(n, null)
            g == null -> if (mode == EqMode.BOTH) EqStatus.Sessions(n, globalError ?: "unavailable")
                else EqStatus.Error(globalError ?: "unknown error")
            !hasControl(g) -> EqStatus.LostControl
            else -> EqStatus.ActiveGlobal
        }
        onStatus(status)
    }

    private fun hasControl(e: AudioEffect) = try {
        e.hasControl()
    } catch (_: Exception) {
        false
    }

    private fun buildConfig(): DynamicsProcessing.Config {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNELS,
            true, CurveMapper.DP_BAND_COUNT,
            false, 0,
            false, 0,
            true,
        ).build()
        CurveMapper.cutoffs.forEachIndexed { i, hz ->
            config.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, hz.toFloat(), 0f))
        }
        config.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(
                true, true, 0,
                LIMITER_ATTACK_MS, LIMITER_RELEASE_MS, LIMITER_RATIO, LIMITER_THRESHOLD_DB, 0f,
            )
        )
        return config
    }

    companion object {
        private const val TAG = "EqEngine"
        private const val PRIORITY = Int.MAX_VALUE
        private const val CHANNELS = 2
        private const val LIMITER_ATTACK_MS = 1f
        private const val LIMITER_RELEASE_MS = 60f
        private const val LIMITER_RATIO = 10f
        private const val LIMITER_THRESHOLD_DB = -1f
    }
}

sealed class EqStatus(val text: String) {
    object Off : EqStatus("Off")
    object ActiveGlobal : EqStatus("Active on all audio")
    object LostControl : EqStatus("Lost control to another EQ app")
    class Sessions(count: Int, globalError: String?) : EqStatus(
        "Per-session mode · $count active" + if (globalError != null) " (global mix unavailable)" else ""
    )
    class Error(message: String) : EqStatus("Couldn't attach to the global mix: $message")
}
