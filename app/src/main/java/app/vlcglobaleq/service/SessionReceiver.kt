package app.vlcglobaleq.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect

/**
 * Per-session fallback. Players announce their audio session with these broadcasts; most send
 * them implicitly, which only the runtime registration in [EqService] receives. The manifest
 * registration catches players that address this package explicitly.
 */
class SessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val engine = EqController.engine ?: return
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
        if (session <= 0) return
        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> engine.openSession(session)
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> engine.closeSession(session)
        }
    }
}
