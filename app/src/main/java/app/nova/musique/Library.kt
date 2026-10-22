package app.nova.musique

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * La musique du téléphone : liste des morceaux (via MediaStore, l'index audio d'Android)
 * et pochettes des albums.
 */
object Library {

    private val ALBUM_ART: Uri = Uri.parse("content://media/external/audio/albumart")

    fun audioUri(id: String): Uri =
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toLongOrNull() ?: 0L)

    fun albumArtUri(albumId: String): Uri =
        ContentUris.withAppendedId(ALBUM_ART, albumId.toLongOrNull() ?: 0L)

    /** Tous les morceaux de musique du téléphone, au format JSON pour l'interface. */
    fun scan(ctx: Context): String {
        val cols = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_ADDED
        )
        if (Build.VERSION.SDK_INT >= 30) cols.add(MediaStore.Audio.Media.ALBUM_ARTIST)

        val out = JSONArray()
        val cursor = ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            cols.toTypedArray(),
            MediaStore.Audio.Media.IS_MUSIC + " != 0",
            null,
            null
        ) ?: return "[]"

        cursor.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val iAlbumArtist = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST) else -1

            while (c.moveToNext()) {
                val o = JSONObject()
                o.put("id", c.getLong(iId).toString())
                o.put("t", c.getString(iTitle) ?: "")
                o.put("ar", c.getString(iArtist) ?: "")
                o.put("al", c.getString(iAlbum) ?: "")
                o.put("alId", c.getLong(iAlbumId).toString())
                o.put("aa", if (iAlbumArtist >= 0) (c.getString(iAlbumArtist) ?: "") else "")
                o.put("d", c.getLong(iDur))
                o.put("n", c.getInt(iTrack))
                o.put("y", c.getInt(iYear))
                o.put("da", c.getLong(iAdded))
                out.put(o)
            }
        }
        return out.toString()
    }

    /** Morceau envoyé par l'interface → élément du lecteur. */
    fun mediaItem(o: JSONObject): MediaItem {
        val id = o.optString("id")
        val meta = MediaMetadata.Builder()
            .setTitle(o.optString("t").ifBlank { null })
            .setArtist(o.optString("ar").ifBlank { null })
            .setAlbumTitle(o.optString("al").ifBlank { null })
            .setAlbumArtist(o.optString("aa").ifBlank { null })
            .setArtworkUri(albumArtUri(o.optString("alId")))
            .setIsPlayable(true)
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(audioUri(id))
            .setMediaMetadata(meta)
            .build()
    }

    // ---------- Pochettes ----------

    private fun artDir(ctx: Context): File = File(ctx.cacheDir, "art").apply { mkdirs() }

    fun clearArtCache(ctx: Context) {
        artDir(ctx).listFiles()?.forEach { it.delete() }
    }

    /** Pochette en JPEG (512 px max), mise en cache. null si le morceau n'en a pas. */
    fun artwork(ctx: Context, trackId: String, albumId: String): ByteArray? {
        val key = if (albumId.isNotBlank() && albumId != "0") "a$albumId" else "t$trackId"
        val dir = artDir(ctx)
        val file = File(dir, "$key.jpg")
        val none = File(dir, "$key.none")
        if (file.exists()) return try { file.readBytes() } catch (_: Exception) { null }
        if (none.exists()) return null

        var bytes: ByteArray? = null

        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val bmp = ctx.contentResolver.loadThumbnail(audioUri(trackId), Size(512, 512), null)
                bytes = toJpeg(bmp)
            } catch (_: Exception) {
            }
        } else if (key.startsWith("a")) {
            try {
                ctx.contentResolver.openInputStream(albumArtUri(albumId))?.use { input ->
                    val raw = input.readBytes()
                    bytes = shrink(raw) ?: raw
                }
            } catch (_: Exception) {
            }
        }

        // Dernier recours : l'image intégrée au fichier audio
        if (bytes == null) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, audioUri(trackId))
                val pic = r.embeddedPicture
                if (pic != null) bytes = shrink(pic)
            } catch (_: Exception) {
            } finally {
                try {
                    r.release()
                } catch (_: Exception) {
                }
            }
        }

        val result = bytes
        try {
            if (result != null) {
                val tmp = File(dir, "$key.tmp${Thread.currentThread().id}")
                tmp.writeBytes(result)
                tmp.renameTo(file)
            } else {
                none.createNewFile()
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun toJpeg(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    /** Réduit une image trop grande (certaines pochettes font 3000 px). */
    private fun shrink(raw: ByteArray): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 512 && bounds.outHeight / (sample * 2) >= 512) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            toJpeg(bmp)
        } catch (_: Exception) {
            null
        }
    }
}
