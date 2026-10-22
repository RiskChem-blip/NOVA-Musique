package app.nova.musique

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Un morceau écouté, à envoyer à Last.fm et ListenBrainz. */
data class Listen(
    val artist: String,
    val track: String,
    val album: String,
    val albumArtist: String,
    val durationMs: Long,
    val timestamp: Long
) {
    fun toJson(lfm: Boolean, lb: Boolean): JSONObject = JSONObject()
        .put("artist", artist).put("track", track).put("album", album)
        .put("albumArtist", albumArtist).put("dur", durationMs).put("ts", timestamp)
        .put("lfm", lfm).put("lb", lb)
}

/**
 * Envoi des écoutes (« scrobbling ») à Last.fm et ListenBrainz.
 * Les écoutes sont d'abord écrites dans une file sur le téléphone, puis envoyées :
 * sans réseau, rien n'est perdu, tout part à la prochaine connexion.
 */
object Scrobbler {
    private const val LFM = "https://ws.audioscrobbler.com/2.0/"
    private const val LB = "https://api.listenbrainz.org/1/"

    private val io = Executors.newSingleThreadExecutor()
    private val lock = Any()

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("novamusique", Context.MODE_PRIVATE)

    // ---------- Réseau ----------

    class Resp(val code: Int, val body: String)

    fun http(
        method: String,
        url: String,
        body: String? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "NovaMusique/${BuildConfig.VERSION_NAME} (Android)")
            c.setRequestProperty("Accept", "application/json")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                if (contentType != null) c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Resp(code, text)
        } finally {
            c.disconnect()
        }
    }

    private fun json(s: String): JSONObject? = try {
        JSONObject(s)
    } catch (_: Exception) {
        null
    }

    // ---------- Comptes ----------

    private fun lfmReady(p: SharedPreferences) =
        !p.getString("lfm_sk", "").isNullOrEmpty() && !p.getBoolean("lfm_bad", false)

    private fun lbReady(p: SharedPreferences) =
        !p.getString("lb_token", "").isNullOrEmpty() && !p.getBoolean("lb_bad", false)

    private fun lfmOn(p: SharedPreferences) = lfmReady(p) && p.getBoolean("lfm_on", true)
    private fun lbOn(p: SharedPreferences) = lbReady(p) && p.getBoolean("lb_on", true)

    fun accounts(ctx: Context): JSONObject {
        val p = prefs(ctx)
        val lfm = JSONObject()
            .put("connected", !p.getString("lfm_sk", "").isNullOrEmpty())
            .put("user", p.getString("lfm_user", "") ?: "")
            .put("key", p.getString("lfm_key", "") ?: "")
            .put("on", p.getBoolean("lfm_on", true))
            .put("bad", p.getBoolean("lfm_bad", false))
        val lb = JSONObject()
            .put("connected", !p.getString("lb_token", "").isNullOrEmpty())
            .put("user", p.getString("lb_user", "") ?: "")
            .put("on", p.getBoolean("lb_on", true))
            .put("bad", p.getBoolean("lb_bad", false))
        var lfmPending = 0
        var lbPending = 0
        synchronized(lock) {
            val q = readQueue(ctx)
            for (i in 0 until q.length()) {
                val o = q.optJSONObject(i) ?: continue
                if (o.optBoolean("lfm")) lfmPending++
                if (o.optBoolean("lb")) lbPending++
            }
        }
        lfm.put("pending", lfmPending)
        lb.put("pending", lbPending)
        return JSONObject().put("lfm", lfm).put("lb", lb)
    }

    fun lastfmLogin(ctx: Context, key: String, secret: String, user: String, pass: String): JSONObject {
        val out = JSONObject().put("type", "lastfm")
        try {
            val r = lfmCall(
                mapOf(
                    "method" to "auth.getMobileSession",
                    "username" to user.trim(),
                    "password" to pass,
                    "api_key" to key.trim()
                ),
                secret.trim()
            )
            val j = json(r.body) ?: JSONObject()
            val session = j.optJSONObject("session")
            if (session != null && session.optString("key").isNotEmpty()) {
                val name = session.optString("name").ifBlank { user.trim() }
                prefs(ctx).edit()
                    .putString("lfm_key", key.trim())
                    .putString("lfm_secret", secret.trim())
                    .putString("lfm_sk", session.optString("key"))
                    .putString("lfm_user", name)
                    .putBoolean("lfm_on", true)
                    .putBoolean("lfm_bad", false)
                    .apply()
                out.put("ok", true).put("user", name)
            } else {
                out.put("ok", false).put("error", lfmMessage(j.optInt("error"), j.optString("message"), r.code))
            }
        } catch (e: Exception) {
            out.put("ok", false).put("error", "Pas de connexion à internet, ou Last.fm ne répond pas.")
        }
        return out
    }

    private fun lfmMessage(code: Int, msg: String, http: Int): String = when (code) {
        4 -> "Nom d'utilisateur ou mot de passe incorrect."
        10 -> "Clé API invalide : vérifie la clé copiée depuis last.fm/api."
        13 -> "Secret partagé incorrect : vérifie-le sur last.fm/api."
        26 -> "Cette clé API a été suspendue par Last.fm."
        29 -> "Trop de tentatives : réessaie dans quelques minutes."
        else -> if (msg.isNotBlank()) "Last.fm : $msg" else "Erreur Last.fm ($http)."
    }

    fun listenbrainzLogin(ctx: Context, token: String): JSONObject {
        val out = JSONObject().put("type", "listenbrainz")
        val t = token.trim()
        try {
            val r = http("GET", LB + "validate-token", headers = mapOf("Authorization" to "Token $t"))
            val j = json(r.body) ?: JSONObject()
            if (r.code == 200 && j.optBoolean("valid")) {
                val name = j.optString("user_name")
                prefs(ctx).edit()
                    .putString("lb_token", t)
                    .putString("lb_user", name)
                    .putBoolean("lb_on", true)
                    .putBoolean("lb_bad", false)
                    .apply()
                out.put("ok", true).put("user", name)
            } else {
                out.put("ok", false)
                    .put("error", "Ce jeton n'est pas valide. Copie-le depuis listenbrainz.org › Paramètres.")
            }
        } catch (e: Exception) {
            out.put("ok", false).put("error", "Pas de connexion à internet, ou ListenBrainz ne répond pas.")
        }
        return out
    }

    fun logout(ctx: Context, service: String) {
        val e = prefs(ctx).edit()
        if (service == "lfm") {
            e.remove("lfm_sk").remove("lfm_user").remove("lfm_bad")
        } else {
            e.remove("lb_token").remove("lb_user").remove("lb_bad")
        }
        e.apply()
        // Les écoutes en attente pour ce compte ne partiront plus
        synchronized(lock) {
            val q = readQueue(ctx)
            for (i in 0 until q.length()) q.optJSONObject(i)?.put(service, false)
            writeQueue(ctx, cleaned(q))
        }
    }

    fun setEnabled(ctx: Context, service: String, on: Boolean) {
        prefs(ctx).edit().putBoolean(service + "_on", on).apply()
        if (on) flushAsync(ctx)
    }

    // ---------- File d'attente sur le téléphone ----------

    private fun queueFile(ctx: Context) = File(ctx.applicationContext.filesDir, "scrobbles.json")

    private fun readQueue(ctx: Context): JSONArray = try {
        val f = queueFile(ctx)
        if (f.exists()) JSONArray(f.readText()) else JSONArray()
    } catch (_: Exception) {
        JSONArray()
    }

    private fun writeQueue(ctx: Context, a: JSONArray) {
        try {
            val f = queueFile(ctx)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(a.toString())
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
    }

    private fun cleaned(a: JSONArray): JSONArray {
        val keep = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optBoolean("lfm") || o.optBoolean("lb")) keep.put(o)
        }
        return keep
    }

    /** Une écoute vient de compter : on la met en file, puis on tente l'envoi. */
    fun scrobble(ctx: Context, l: Listen) {
        val app = ctx.applicationContext
        io.execute {
            val p = prefs(app)
            val lfm = lfmOn(p)
            val lb = lbOn(p)
            if (lfm || lb) {
                synchronized(lock) {
                    val q = readQueue(app)
                    q.put(l.toJson(lfm, lb))
                    // Garde-fou : au plus 5000 écoutes en attente
                    while (q.length() > 5000) q.remove(0)
                    writeQueue(app, q)
                }
                flush(app)
            }
        }
    }

    fun flushAsync(ctx: Context, done: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        io.execute {
            flush(app)
            done?.invoke()
        }
    }

    /** Envoie tout ce qui attend (appelé sur le fil « io »). */
    private fun flush(ctx: Context) {
        val p = prefs(ctx)
        synchronized(lock) {
            val q = readQueue(ctx)
            if (q.length() == 0) return

            if (lfmOn(p)) {
                val idx = (0 until q.length()).filter { q.optJSONObject(it)?.optBoolean("lfm") == true }
                for (chunk in idx.chunked(50)) {
                    if (!lfmScrobble(p, chunk.map { q.getJSONObject(it) })) break
                    chunk.forEach { q.getJSONObject(it).put("lfm", false) }
                }
            }
            if (lbOn(p)) {
                val idx = (0 until q.length()).filter { q.optJSONObject(it)?.optBoolean("lb") == true }
                for (chunk in idx.chunked(100)) {
                    if (!lbSubmit(p, chunk.map { q.getJSONObject(it) })) break
                    chunk.forEach { q.getJSONObject(it).put("lb", false) }
                }
            }
            writeQueue(ctx, cleaned(q))
        }
    }

    /** « En cours d'écoute » sur les deux profils (sans file d'attente : inutile après coup). */
    fun nowPlaying(ctx: Context, l: Listen) {
        val app = ctx.applicationContext
        io.execute {
            val p = prefs(app)
            if (lfmOn(p)) {
                try {
                    val m = linkedMapOf(
                        "method" to "track.updateNowPlaying",
                        "api_key" to (p.getString("lfm_key", "") ?: ""),
                        "sk" to (p.getString("lfm_sk", "") ?: ""),
                        "artist" to l.artist,
                        "track" to l.track
                    )
                    if (l.album.isNotBlank()) m["album"] = l.album
                    if (l.albumArtist.isNotBlank()) m["albumArtist"] = l.albumArtist
                    if (l.durationMs > 0) m["duration"] = (l.durationMs / 1000).toString()
                    lfmCall(m, p.getString("lfm_secret", "") ?: "")
                } catch (_: Exception) {
                }
            }
            if (lbOn(p)) {
                try {
                    lbPost(p, lbBody("playing_now", listOf(l.toJson(false, true))))
                } catch (_: Exception) {
                }
            }
            // Profite d'avoir du réseau pour vider la file
            flush(app)
        }
    }

    // ---------- Last.fm ----------

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Appel signé : api_sig = md5(paramètres triés + secret). */
    private fun lfmCall(params: Map<String, String>, secret: String): Resp {
        val sig = md5(params.toSortedMap().entries.joinToString("") { it.key + it.value } + secret)
        val all = LinkedHashMap(params)
        all["api_sig"] = sig
        all["format"] = "json"
        val body = all.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        return http("POST", LFM, body, "application/x-www-form-urlencoded; charset=UTF-8")
    }

    /** true = lot traité (accepté ou refusé définitivement), false = à réessayer plus tard. */
    private fun lfmScrobble(p: SharedPreferences, items: List<JSONObject>): Boolean {
        val m = LinkedHashMap<String, String>()
        m["method"] = "track.scrobble"
        m["api_key"] = p.getString("lfm_key", "") ?: ""
        m["sk"] = p.getString("lfm_sk", "") ?: ""
        items.forEachIndexed { i, o ->
            m["artist[$i]"] = o.optString("artist")
            m["track[$i]"] = o.optString("track")
            m["timestamp[$i]"] = o.optLong("ts").toString()
            if (o.optString("album").isNotBlank()) m["album[$i]"] = o.optString("album")
            if (o.optString("albumArtist").isNotBlank()) m["albumArtist[$i]"] = o.optString("albumArtist")
            if (o.optLong("dur") > 0) m["duration[$i]"] = (o.optLong("dur") / 1000).toString()
        }
        return try {
            val r = lfmCall(m, p.getString("lfm_secret", "") ?: "")
            val j = json(r.body)
            if (j != null && j.has("error")) {
                when (j.optInt("error")) {
                    // Session ou clé refusée : on arrête jusqu'à la reconnexion
                    4, 9, 10, 13, 26 -> {
                        p.edit().putBoolean("lfm_bad", true).apply()
                        false
                    }
                    // Paramètres refusés : ce lot ne passera jamais, on l'abandonne
                    6 -> true
                    else -> false
                }
            } else {
                r.code in 200..299
            }
        } catch (_: Exception) {
            false
        }
    }

    // ---------- ListenBrainz ----------

    private fun lbBody(type: String, items: List<JSONObject>): String {
        val payload = JSONArray()
        for (o in items) {
            val info = JSONObject()
                .put("media_player", "Nova Musique")
                .put("submission_client", "Nova Musique")
                .put("submission_client_version", BuildConfig.VERSION_NAME)
            if (o.optLong("dur") > 0) info.put("duration_ms", o.optLong("dur"))
            val meta = JSONObject()
                .put("artist_name", o.optString("artist"))
                .put("track_name", o.optString("track"))
                .put("additional_info", info)
            if (o.optString("album").isNotBlank()) meta.put("release_name", o.optString("album"))
            val listen = JSONObject().put("track_metadata", meta)
            if (type != "playing_now") listen.put("listened_at", o.optLong("ts"))
            payload.put(listen)
        }
        return JSONObject().put("listen_type", type).put("payload", payload).toString()
    }

    private fun lbPost(p: SharedPreferences, body: String): Resp =
        http(
            "POST", LB + "submit-listens", body, "application/json",
            mapOf("Authorization" to "Token " + (p.getString("lb_token", "") ?: ""))
        )

    private fun lbSubmit(p: SharedPreferences, items: List<JSONObject>): Boolean {
        return try {
            val r = lbPost(p, lbBody(if (items.size == 1) "single" else "import", items))
            when {
                r.code in 200..299 -> true
                r.code == 401 -> {
                    p.edit().putBoolean("lb_bad", true).apply()
                    false
                }
                r.code == 400 -> true // données refusées : on n'insiste pas
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }
}

/** Historique local des écoutes (pour « Écoutés récemment » et « Tes plus écoutés »). */
object History {
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "history.json")

    fun read(ctx: Context): JSONObject = synchronized(lock) {
        try {
            val f = file(ctx)
            if (f.exists()) JSONObject(f.readText()) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
    }

    fun add(ctx: Context, mediaId: String) {
        if (mediaId.isBlank()) return
        synchronized(lock) {
            val h = read(ctx)
            val recent = h.optJSONArray("recent") ?: JSONArray()
            val counts = h.optJSONObject("counts") ?: JSONObject()
            val next = JSONArray()
            next.put(JSONObject().put("id", mediaId).put("ts", System.currentTimeMillis() / 1000))
            for (i in 0 until minOf(recent.length(), 299)) next.put(recent.get(i))
            counts.put(mediaId, counts.optInt(mediaId) + 1)
            h.put("recent", next).put("counts", counts)
            try {
                val f = file(ctx)
                val tmp = File(f.path + ".tmp")
                tmp.writeText(h.toString())
                tmp.renameTo(f)
            } catch (_: Exception) {
            }
        }
    }
}
