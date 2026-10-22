package app.nova.musique

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Le lecteur tourne ici, hors de l'écran : la musique continue écran éteint ou appli fermée,
 * avec la notification, l'écran verrouillé et les boutons du casque.
 * C'est aussi ici que l'on compte les écoutes pour Last.fm / ListenBrainz.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var tracker: Tracker? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true // pause automatique pendant un appel ou une autre appli audio
            )
            .setHandleAudioBecomingNoisy(true) // pause si on débranche le casque
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java)
        val open = PendingIntent.getActivity(
            this, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(open)
            .build()

        val t = Tracker(player)
        player.addListener(t)
        tracker = t

        Scrobbler.flushAsync(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Appli balayée hors des récentes : on s'arrête seulement si rien ne joue
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        tracker?.stop()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Les morceaux envoyés par l'appli retrouvent leur fichier à partir de leur identifiant. */
    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                if (item.localConfiguration != null) item
                else item.buildUpon().setUri(Library.audioUri(item.mediaId)).build()
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }

    /**
     * Compte le temps réellement écouté de chaque morceau.
     * Règle Last.fm / ListenBrainz : morceau de plus de 30 s, écouté à moitié ou 4 min.
     */
    private inner class Tracker(private val player: ExoPlayer) : Player.Listener {
        private val handler = Handler(Looper.getMainLooper())
        private var counting = false
        private var lastTick = 0L
        private var playedMs = 0L
        private var startedAt = 0L
        private var counted = false
        private var announced = false

        private val tick = object : Runnable {
            override fun run() {
                step()
                if (counting) handler.postDelayed(this, 1000)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Nouveau morceau (ou le même relancé en boucle) : on repart de zéro
            playedMs = 0L
            lastTick = SystemClock.elapsedRealtime()
            startedAt = if (counting) now() else 0L
            counted = false
            announced = false
            if (counting) announce()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                counting = true
                lastTick = SystemClock.elapsedRealtime()
                if (startedAt == 0L) startedAt = now()
                announce()
                handler.removeCallbacks(tick)
                handler.postDelayed(tick, 1000)
            } else {
                step()
                counting = false
                handler.removeCallbacks(tick)
            }
        }

        fun stop() {
            handler.removeCallbacks(tick)
        }

        private fun now() = System.currentTimeMillis() / 1000

        private fun step() {
            val t = SystemClock.elapsedRealtime()
            if (counting) playedMs += t - lastTick
            lastTick = t
            if (counted) return
            val dur = player.duration
            if (dur == C.TIME_UNSET || dur <= 30_000) return
            if (playedMs >= minOf(dur / 2, 240_000L)) {
                counted = true
                val item = player.currentMediaItem ?: return
                History.add(this@PlaybackService, item.mediaId)
                listen(item, dur)?.let { Scrobbler.scrobble(this@PlaybackService, it) }
            }
        }

        private fun announce() {
            if (announced) return
            val item = player.currentMediaItem ?: return
            announced = true
            val dur = if (player.duration == C.TIME_UNSET) 0L else player.duration
            listen(item, dur)?.let { Scrobbler.nowPlaying(this@PlaybackService, it) }
        }

        private fun listen(item: MediaItem, dur: Long): Listen? {
            val m = item.mediaMetadata
            val artist = clean(m.artist)
            val title = clean(m.title)
            if (artist.isEmpty() || title.isEmpty()) return null
            return Listen(
                artist = artist,
                track = title,
                album = clean(m.albumTitle),
                albumArtist = clean(m.albumArtist),
                durationMs = dur,
                timestamp = if (startedAt > 0) startedAt else now()
            )
        }

        /** Android note « <unknown> » quand le fichier n'a pas l'information. */
        private fun clean(s: CharSequence?): String {
            val v = s?.toString()?.trim() ?: return ""
            return if (v.equals("<unknown>", ignoreCase = true)) "" else v
        }
    }
}
