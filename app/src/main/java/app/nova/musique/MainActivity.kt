package app.nova.musique

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/**
 * Écran principal : l'interface Nova Musique (assets/index.html) dans une WebView, comme Nova.
 * La lecture est confiée au service PlaybackService, piloté ici par un MediaController.
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** Origine de l'interface = nom du paquet de l'appli (identité attendue par YouTube). */
        const val HOST = "app.nova.musique"
        private val NET_HOSTS = setOf("ws.audioscrobbler.com", "api.listenbrainz.org", "www.googleapis.com")

        /** Blocage des pubs (best-effort) : domaines publicitaires connus de YouTube/Google. */
        private val AD_HOSTS = setOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "googletagservices.com", "adservice.google.com"
        )
        /** Chemins de pub/suivi sur youtube.com (sans toucher au flux audio de googlevideo.com). */
        private val AD_PATHS = listOf("/pagead/", "/ptracking", "/api/stats/ads", "/get_midroll_info")
    }

    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newFixedThreadPool(3)

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()

    /** Dernier état connu du lecteur, lu par l'interface toutes les demi-secondes. */
    @Volatile private var stateJson = "{\"c\":false}"
    @Volatile private var queueJson = "[]"
    private var queueVersion = 0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            js("window.novaEvent && novaEvent({type:'permission', ok:$granted})")
        }

    private val playerListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = refreshQueue()
        override fun onEvents(player: Player, events: Player.Events) = refreshState()
    }

    private val ticker = object : Runnable {
        override fun run() {
            refreshState()
            main.postDelayed(this, 500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // La page se croit toujours visible : le lecteur YouTube continue écran éteint ou appli en arrière-plan
        web = object : WebView(this) {
            override fun onWindowVisibilityChanged(visibility: Int) = super.onWindowVisibilityChanged(View.VISIBLE)
        }
        web.setBackgroundColor(0xFF13070A.toInt())
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.mediaPlaybackRequiresUserGesture = false

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Le lecteur YouTube navigue dans son propre cadre : on le laisse faire
                if (!request.isForMainFrame || request.url.host == HOST) return false
                openUrl(request.url.toString())
                return true
            }

            // https://app.nova.musique/... : l'interface, les pochettes et les appels réseau faits par Android.
            // Une vraie origine https est indispensable : YouTube refuse sa lecture (erreur 153) sans elle.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (u.host != HOST) return null
                val path = u.path ?: ""
                return when {
                    path == "/" || path == "/index.html" -> pageResponse()
                    path.startsWith("/art/") -> artResponse(path.removePrefix("/art/"), u.getQueryParameter("a") ?: "")
                    path == "/net" -> netResponse(u.getQueryParameter("u") ?: "")
                    else -> textResponse(404, "")
                }
            }
        }
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "NovaMusiqueAndroid")

        YtService.onAction = { a ->
            val cmd = when (a) {
                YtService.ACTION_NEXT -> "next"
                YtService.ACTION_PREV -> "prev"
                else -> "toggle"
            }
            js("window.novaYt && novaYt('$cmd')")
        }

        setContentView(web)
        web.loadUrl("https://$HOST/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.novaBack && window.novaBack()) ? 'yes' : 'no'") { r ->
                    // Rien à fermer : on passe en arrière-plan, la musique continue
                    if (r == null || !r.contains("yes")) moveTaskToBack(true)
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        connect()
        Scrobbler.flushAsync(this)
        js("window.novaEvent && novaEvent({type:'resume'})")
    }

    override fun onStop() {
        try {
            web.evaluateJavascript("window.novaEvent && novaEvent({type:'hidden'})", null)
        } catch (_: Exception) {
        }
        disconnect()
        super.onStop()
    }

    override fun onDestroy() {
        YtService.onAction = null
        YtService.stop(this)
        bg.shutdown()
        web.destroy()
        super.onDestroy()
    }

    private fun js(code: String) {
        main.post {
            try {
                web.evaluateJavascript(code, null)
            } catch (_: Exception) {
            }
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
        }
    }

    // ---------- Connexion au lecteur ----------

    private fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c: MediaController? = try {
                future.get()
            } catch (_: Exception) {
                null
            }
            if (c == null) return@addListener
            if (controllerFuture !== future) {
                c.release()
                return@addListener
            }
            controller = c
            c.addListener(playerListener)
            refreshQueue()
            val todo = ArrayList(pending)
            pending.clear()
            todo.forEach { it(c) }
            main.removeCallbacks(ticker)
            main.post(ticker)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun disconnect() {
        main.removeCallbacks(ticker)
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        stateJson = "{\"c\":false}"
    }

    /** Exécute une commande sur le lecteur (dès qu'il est connecté). */
    private fun withPlayer(block: (MediaController) -> Unit) {
        main.post {
            val c = controller
            if (c != null) {
                try {
                    block(c)
                } catch (_: Exception) {
                }
            } else {
                pending.add(block)
                connect()
            }
        }
    }

    private fun refreshQueue() {
        val c = controller ?: return
        val a = JSONArray()
        for (i in 0 until c.mediaItemCount) a.put(c.getMediaItemAt(i).mediaId)
        queueJson = a.toString()
        queueVersion++
        refreshState()
    }

    private fun refreshState() {
        val c = controller ?: return
        val dur = c.duration
        stateJson = JSONObject()
            .put("c", true)
            .put("p", c.isPlaying)
            .put("pw", c.playWhenReady)
            .put("s", c.playbackState)
            .put("i", c.currentMediaItemIndex)
            .put("id", c.currentMediaItem?.mediaId ?: "")
            .put("pos", c.currentPosition)
            .put("dur", if (dur == C.TIME_UNSET) 0L else dur)
            .put("r", c.repeatMode)
            .put("n", c.mediaItemCount)
            .put("qv", queueVersion)
            .toString()
    }

    private fun items(json: String): List<MediaItem> {
        val a = JSONArray(json)
        val out = ArrayList<MediaItem>(a.length())
        for (i in 0 until a.length()) out.add(Library.mediaItem(a.getJSONObject(i)))
        return out
    }

    // ---------- Requêtes interceptées ----------

    private val cors = mapOf("Access-Control-Allow-Origin" to "*")

    private fun textResponse(code: Int, body: String): WebResourceResponse =
        WebResourceResponse(
            "application/json", "utf-8", code, if (code in 200..299) "OK" else "Erreur",
            cors, ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        )

    private fun artResponse(trackId: String, albumId: String): WebResourceResponse {
        val bytes: ByteArray? = try {
            Library.artwork(this, trackId, albumId)
        } catch (_: Exception) {
            null
        }
        if (bytes == null) return textResponse(404, "")
        return WebResourceResponse(
            "image/jpeg", null, 200, "OK",
            cors + mapOf("Cache-Control" to "max-age=86400"), ByteArrayInputStream(bytes)
        )
    }

    private fun pageResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/html", "utf-8", 200, "OK",
            mapOf("Cache-Control" to "no-store"), assets.open("index.html")
        )

    /** Appels réseau de l'interface : seulement Last.fm, ListenBrainz et l'API de recherche YouTube. */
    private fun netResponse(target: String): WebResourceResponse {
        val host = try {
            Uri.parse(target).host ?: ""
        } catch (_: Exception) {
            ""
        }
        if (!target.startsWith("https://") || host !in NET_HOSTS) {
            return textResponse(403, "{\"error\":\"adresse refusée\"}")
        }
        // ListenBrainz : le jeton donne accès aux playlists et recommandations personnelles
        val token = Scrobbler.prefs(this).getString("lb_token", "") ?: ""
        val headers = if (host == "api.listenbrainz.org" && token.isNotBlank()) mapOf("Authorization" to "Token $token") else emptyMap()
        return try {
            val r = Scrobbler.http("GET", target, headers = headers)
            val code = if (r.code in 200..299 || r.code in 400..599) r.code else 502
            textResponse(code, r.body)
        } catch (e: Exception) {
            textResponse(599, "{\"error\":\"réseau\"}")
        }
    }

    /** Écoute envoyée par le lecteur YouTube de l'interface. */
    private fun listenFrom(json: String): Listen? = try {
        val o = JSONObject(json)
        val artist = o.optString("artist").trim()
        val track = o.optString("track").trim()
        if (artist.isEmpty() || track.isEmpty()) null
        else Listen(
            artist = artist,
            track = track,
            album = o.optString("album").trim(),
            albumArtist = "",
            durationMs = o.optLong("dur"),
            timestamp = o.optLong("ts").takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)
        )
    } catch (_: Exception) {
        null
    }

    // ---------- Permission ----------

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED

    /** Fonctions accessibles depuis l'interface (window.NovaMusiqueAndroid). */
    inner class Bridge {
        // Bibliothèque
        @JavascriptInterface
        fun hasPermission(): Boolean = hasAudioPermission()

        @JavascriptInterface
        fun requestPermission() {
            main.post { permissionLauncher.launch(audioPermission()) }
        }

        @JavascriptInterface
        fun openAppSettings() {
            main.post {
                try {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    )
                } catch (_: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun scan(clearArt: Boolean) {
            bg.execute {
                if (clearArt) Library.clearArtCache(this@MainActivity)
                val json = try {
                    Library.scan(this@MainActivity)
                } catch (_: Exception) {
                    "[]"
                }
                js("window.novaLibrary && novaLibrary($json)")
            }
        }

        @JavascriptInterface
        fun history(): String = History.read(this@MainActivity).toString()

        // Lecteur
        @JavascriptInterface
        fun state(): String = stateJson

        @JavascriptInterface
        fun queue(): String = queueJson

        @JavascriptInterface
        fun setQueue(json: String, index: Int, positionMs: Double, play: Boolean) {
            val list = try {
                items(json)
            } catch (_: Exception) {
                return
            }
            if (list.isEmpty()) return
            withPlayer { c ->
                c.setMediaItems(list, index.coerceIn(0, list.size - 1), positionMs.toLong().coerceAtLeast(0L))
                c.prepare()
                c.playWhenReady = play
            }
        }

        @JavascriptInterface
        fun play() = withPlayer { c ->
            if (c.mediaItemCount == 0) return@withPlayer
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }

        @JavascriptInterface
        fun pause() = withPlayer { it.pause() }

        @JavascriptInterface
        fun next() = withPlayer { it.seekToNext() }

        @JavascriptInterface
        fun previous() = withPlayer { it.seekToPrevious() }

        @JavascriptInterface
        fun seek(positionMs: Double) = withPlayer { it.seekTo(positionMs.toLong().coerceAtLeast(0L)) }

        @JavascriptInterface
        fun skipTo(index: Int) = withPlayer { c ->
            if (index !in 0 until c.mediaItemCount) return@withPlayer
            c.seekToDefaultPosition(index)
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }

        /** 0 = pas de répétition, 1 = répéter le morceau, 2 = répéter la file. */
        @JavascriptInterface
        fun setRepeat(mode: Int) = withPlayer {
            it.repeatMode = when (mode) {
                1 -> Player.REPEAT_MODE_ONE
                2 -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
        }

        @JavascriptInterface
        fun insertAt(index: Int, json: String) {
            val list = try {
                items(json)
            } catch (_: Exception) {
                return
            }
            withPlayer { c ->
                if (c.mediaItemCount == 0) {
                    c.setMediaItems(list)
                    c.prepare()
                    c.play()
                } else {
                    c.addMediaItems(index.coerceIn(0, c.mediaItemCount), list)
                }
            }
        }

        /** Remplace tout ce qui suit le morceau en cours (lecture aléatoire activée / désactivée). */
        @JavascriptInterface
        fun replaceUpcoming(json: String) {
            val list = try {
                items(json)
            } catch (_: Exception) {
                return
            }
            withPlayer { c ->
                val n = c.mediaItemCount
                if (n == 0) return@withPlayer
                val cur = c.currentMediaItemIndex
                if (cur + 1 < n) c.removeMediaItems(cur + 1, n)
                if (list.isNotEmpty()) c.addMediaItems(list)
            }
        }

        @JavascriptInterface
        fun removeAt(index: Int) = withPlayer { c ->
            if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
        }

        @JavascriptInterface
        fun move(from: Int, to: Int) = withPlayer { c ->
            val n = c.mediaItemCount
            if (from in 0 until n && to in 0 until n) c.moveMediaItem(from, to)
        }

        @JavascriptInterface
        fun clearQueue() = withPlayer { c ->
            c.stop()
            c.clearMediaItems()
        }

        // Comptes Last.fm / ListenBrainz
        @JavascriptInterface
        fun accounts(): String = Scrobbler.accounts(this@MainActivity).toString()

        @JavascriptInterface
        fun lastfmLogin(key: String, secret: String, user: String, password: String) {
            bg.execute {
                val r = Scrobbler.lastfmLogin(this@MainActivity, key, secret, user, password)
                js("window.novaEvent && novaEvent($r)")
            }
        }

        @JavascriptInterface
        fun listenbrainzLogin(token: String) {
            bg.execute {
                val r = Scrobbler.listenbrainzLogin(this@MainActivity, token)
                js("window.novaEvent && novaEvent($r)")
            }
        }

        @JavascriptInterface
        fun logout(service: String) {
            bg.execute {
                Scrobbler.logout(this@MainActivity, service)
                js("window.novaEvent && novaEvent({type:'accounts'})")
            }
        }

        @JavascriptInterface
        fun setScrobbling(service: String, on: Boolean) {
            Scrobbler.setEnabled(this@MainActivity, service, on)
        }

        @JavascriptInterface
        fun flush() {
            Scrobbler.flushAsync(this@MainActivity) {
                js("window.novaEvent && novaEvent({type:'accounts'})")
            }
        }

        // Lecture YouTube : scrobbling fait par l'interface, écran allumé pendant la lecture
        @JavascriptInterface
        fun scrobbleRemote(json: String) {
            listenFrom(json)?.let { Scrobbler.scrobble(this@MainActivity, it) }
        }

        @JavascriptInterface
        fun nowPlayingRemote(json: String) {
            listenFrom(json)?.let { Scrobbler.nowPlaying(this@MainActivity, it) }
        }

        @JavascriptInterface
        fun keepScreenOn(on: Boolean) {
            main.post {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /** Lecture YouTube en arrière-plan : notification + appli gardée active. */
        @JavascriptInterface
        fun ytPlayback(title: String, artist: String, playing: Boolean) {
            main.post {
                val prefs = Scrobbler.prefs(this@MainActivity)
                if (playing && Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notif_asked", false) &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    prefs.edit().putBoolean("notif_asked", true).apply()
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
                }
                try {
                    YtService.update(this@MainActivity, title, artist, playing)
                } catch (_: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun ytStop() {
            main.post { YtService.stop(this@MainActivity) }
        }

        @JavascriptInterface
        fun stopNative() = withPlayer { it.pause() }

        @JavascriptInterface
        fun youtubeKey(): String = Scrobbler.prefs(this@MainActivity).getString("yt_key", "") ?: ""

        @JavascriptInterface
        fun setYoutubeKey(key: String) {
            Scrobbler.prefs(this@MainActivity).edit().putString("yt_key", key.trim()).apply()
        }

        // Divers
        @JavascriptInterface
        fun openUrl(url: String) {
            main.post { this@MainActivity.openUrl(url) }
        }

        @JavascriptInterface
        fun version(): String = BuildConfig.VERSION_NAME
    }
}
