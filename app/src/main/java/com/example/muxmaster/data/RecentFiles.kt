package com.example.muxmaster.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Ana ekrandaki "Son Dosyalar" listesinin tek bir satırı.
 *
 * [uriString] kalıcı olarak saklanır (SharedPreferences); uygulama yeniden açıldığında
 * dosya yine açılabilsin diye URI'nin okuma izni seçim anında alınmaya çalışılır.
 */
@Immutable
data class RecentMedia(
    val uriString: String,
    val name: String,
    val durationMs: Long = 0L,
    val lastUsedMs: Long = 0L
) {
    val isAudio: Boolean
        get() {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in AUDIO_ONLY_EXTS
        }

    companion object {
        private val AUDIO_ONLY_EXTS = setOf(
            "mp3", "m4a", "aac", "flac", "opus", "ogg", "oga", "wav", "mka", "ac3", "eac3", "wma", "dts"
        )
    }
}

/**
 * "Son Dosyalar" listesini SharedPreferences içinde JSON olarak tutar.
 * En fazla [MAX_ITEMS] kayıt saklanır, en son kullanılan en üstte olur.
 */
object RecentFiles {
    private const val PREFS_NAME = "muxmaster_recent_files"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 12

    fun load(context: Context): List<RecentMedia> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val uri = obj.optString("uri", "")
                val name = obj.optString("name", "")
                if (uri.isBlank() || name.isBlank()) return@mapNotNull null
                RecentMedia(
                    uriString = uri,
                    name = name,
                    durationMs = obj.optLong("durationMs", 0L),
                    lastUsedMs = obj.optLong("lastUsedMs", 0L)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, items: List<RecentMedia>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("uri", item.uriString)
                    put("name", item.name)
                    put("durationMs", item.durationMs)
                    put("lastUsedMs", item.lastUsedMs)
                }
            )
        }
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }

    /** Dosyayı listenin en başına ekler (zaten varsa yukarı taşır). */
    fun add(context: Context, uri: Uri, name: String?, durationMs: Long = 0L) {
        if (name.isNullOrBlank()) return
        val uriString = uri.toString()
        val current = load(context)
        val existing = current.firstOrNull { it.uriString == uriString }
        val entry = RecentMedia(
            uriString = uriString,
            name = name,
            durationMs = if (durationMs > 0L) durationMs else (existing?.durationMs ?: 0L),
            lastUsedMs = System.currentTimeMillis()
        )
        val updated = (listOf(entry) + current.filter { it.uriString != uriString }).take(MAX_ITEMS)
        save(context, updated)
    }

    fun remove(context: Context, uriString: String) {
        save(context, load(context).filter { it.uriString != uriString })
    }
}

/**
 * Son dosyalar listesindeki videoların süresini ve küçük önizleme resmini
 * (thumbnail) çıkarır. Hepsi IO thread'inde çağrılmalıdır.
 */
object MediaThumbs {

    private fun retrieverFor(context: Context, uri: Uri): MediaMetadataRetriever? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file") {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(context, uri)
            }
            retriever
        } catch (_: Exception) {
            runCatching { retriever.release() }
            null
        }
    }

    fun durationMs(context: Context, uri: Uri): Long {
        val retriever = retrieverFor(context, uri) ?: return 0L
        return try {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Videodan küçük bir kare alır; ses dosyalarında null döner. */
    fun thumbnail(context: Context, uri: Uri, maxWidth: Int = 160, maxHeight: Int = 120): Bitmap? {
        val retriever = retrieverFor(context, uri) ?: return null
        return try {
            val frame = retriever.getFrameAtTime(
                TimeUnit.SECONDS.toMicros(1L),
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: return null
            val scale = minOf(
                maxWidth.toFloat() / frame.width.coerceAtLeast(1),
                maxHeight.toFloat() / frame.height.coerceAtLeast(1)
            ).coerceAtMost(1f)
            if (scale >= 1f) {
                frame
            } else {
                Bitmap.createScaledBitmap(
                    frame,
                    (frame.width * scale).toInt().coerceAtLeast(1),
                    (frame.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}