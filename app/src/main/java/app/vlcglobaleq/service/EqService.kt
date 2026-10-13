package app.vlcglobaleq.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.TileService
import app.vlcglobaleq.R
import app.vlcglobaleq.audio.EqEngine
import app.vlcglobaleq.audio.EqStatus
import app.vlcglobaleq.data.EqMode
import app.vlcglobaleq.data.PresetStore
import app.vlcglobaleq.ui.MainActivity

/**
 * Foreground service whose only job is to keep the DynamicsProcessing objects alive.
 * It plays nothing and runs no audio threads; the DSP lives in AudioFlinger.
 */
class EqService : Service() {

    private lateinit var store: PresetStore
    private lateinit var engine: EqEngine
    private var sessionReceiverRegistered = false
    private val sessionReceiver = SessionReceiver()

    override fun onCreate() {
        super.onCreate()
        store = PresetStore.get(this)
        createChannel()
        engine = EqEngine { status ->
            EqController.publish(status)
            updateNotification(status)
        }
        EqController.engine = engine
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always promote to foreground first: startForegroundService() requires it.
        goForeground()
        when (intent?.action) {
            ACTION_DISABLE -> {
                store.enabled = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_NEXT -> store.next()
        }
        if (!store.enabled && intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mode = store.mode
        engine.start(mode, store.active, store.autoPreamp, store.vlcExactCurve)
        setSessionReceiver(mode != EqMode.GLOBAL)
        updateNotification(engine.status)
        return START_STICKY
    }

    override fun onDestroy() {
        setSessionReceiver(false)
        engine.release()
        EqController.engine = null
        EqController.publish(EqStatus.Off)
        requestTileUpdate()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setSessionReceiver(on: Boolean) {
        if (on == sessionReceiverRegistered) return
        if (on) {
            val filter = IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            }
            // Players send these as implicit broadcasts, so they only reach a runtime receiver.
            // The flag is ignored below API 33.
            registerReceiver(sessionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            unregisterReceiver(sessionReceiver)
        }
        sessionReceiverRegistered = on
    }

    private fun goForeground() {
        val n = buildNotification(EqController.status)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification(status: EqStatus) {
        getSystemService(NotificationManager::class.java)!!.notify(NOTIFICATION_ID, buildNotification(status))
        requestTileUpdate()
    }

    private fun buildNotification(status: EqStatus): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_eq)
            .setContentTitle(store.active.name)
            .setContentText(if (status == EqStatus.Off) "Starting…" else status.text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(action("Disable", ACTION_DISABLE, 1))
            .addAction(action("Next preset", ACTION_NEXT, 2))
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    private fun action(title: String, action: String, code: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, code, Intent(this, EqService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, title, pi).build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Equalizer status", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)!!.createNotificationChannel(channel)
    }

    private fun requestTileUpdate() {
        TileService.requestListeningState(this, ComponentName(this, EqTileService::class.java))
    }

    companion object {
        const val ACTION_START = "app.vlcglobaleq.START"
        const val ACTION_REFRESH = "app.vlcglobaleq.REFRESH"
        const val ACTION_DISABLE = "app.vlcglobaleq.DISABLE"
        const val ACTION_NEXT = "app.vlcglobaleq.NEXT"
        private const val CHANNEL_ID = "eq_status"
        private const val NOTIFICATION_ID = 1
    }
}
