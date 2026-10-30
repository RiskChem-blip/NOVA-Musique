package app.nova.musique

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Garde l'appli active pendant la lecture YouTube : musique écran éteint ou appli en arrière-plan. */
class YtService : Service() {

    companion object {
        private const val CHANNEL = "yt_playback"
        private const val ID = 42
        const val ACTION_TOGGLE = "app.nova.musique.YT_TOGGLE"
        const val ACTION_NEXT = "app.nova.musique.YT_NEXT"
        const val ACTION_PREV = "app.nova.musique.YT_PREV"

        /** Boutons de la notification, relayés à l'interface par MainActivity. */
        @Volatile var onAction: ((String) -> Unit)? = null
        private var instance: YtService? = null
        private var title = ""
        private var artist = ""
        private var playing = false

        fun update(ctx: Context, t: String, a: String, p: Boolean) {
            title = t; artist = a; playing = p
            val s = instance
            if (s != null) s.refresh()
            else if (p) ContextCompat.startForegroundService(ctx, Intent(ctx, YtService::class.java))
        }

        fun stop(ctx: Context) {
            instance?.let { it.stopSelf() } ?: ctx.stopService(Intent(ctx, YtService::class.java))
        }
    }

    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Lecture YouTube", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NovaMusique:yt").apply { setReferenceCounted(false) }
        @Suppress("DEPRECATION")
        wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NovaMusique:yt").apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, ID, build(), type)
        intent?.action?.let { a -> onAction?.invoke(a) }
        refresh()
        return START_NOT_STICKY
    }

    fun refresh() {
        if (playing) {
            if (wake?.isHeld != true) wake?.acquire(6 * 60 * 60 * 1000L)
            if (wifi?.isHeld != true) wifi?.acquire()
        } else {
            if (wake?.isHeld == true) wake?.release()
            if (wifi?.isHeld == true) wifi?.release()
        }
        getSystemService(NotificationManager::class.java).notify(ID, build())
    }

    private fun action(a: String, req: Int): PendingIntent =
        PendingIntent.getService(
            this, req, Intent(this, YtService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun build(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java)
        val open = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title.ifEmpty { "Nova Musique" })
            .setContentText(artist)
            .setContentIntent(open)
            .setOngoing(playing)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Précédent", action(ACTION_PREV, 1))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Lecture", action(ACTION_TOGGLE, 2)
            )
            .addAction(android.R.drawable.ic_media_next, "Suivant", action(ACTION_NEXT, 3))
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Appli balayée : la page (et donc le lecteur YouTube) disparaît avec elle
        stopSelf()
    }

    override fun onDestroy() {
        if (wake?.isHeld == true) wake?.release()
        if (wifi?.isHeld == true) wifi?.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        instance = null
        super.onDestroy()
    }
}
