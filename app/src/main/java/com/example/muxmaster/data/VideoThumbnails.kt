package com.example.muxmaster.data

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Kırpma çubuğunda parmak gezdirilirken (YouTube'daki gibi) parmağın ALTINDAKİ ANIN
 * gerçek karesini gösteren küçük önizleme üreteci.
 *
 * Neden ayrı bir üretici?
 * Oynatıcının kendisi sürükleme sırasında anahtar kareye (keyframe) atladığı için 2-3
 * saniyede bir sahne gösteriyordu. Burada [MediaMetadataRetriever] ile hedef zamana en
 * yakın GERÇEK kare çıkarılır (OPTION_CLOSEST), küçük boyuta indirilir ve önbelleğe
 * alınır. Böylece parmak nereye gelirse gelsin o saniyenin görüntüsü anında görünür.
 *
 * - Tek bir retriever kullanılır (MediaMetadataRetriever thread-safe değildir) ve
 *   [Mutex] ile serileştirilir.
 * - Aynı kaynak + 150 ms'lik kova tekrar istendiğinde disk/kod çözme YAPILMAZ.
 * - İptal edilen istekler (parmak hızlıca geçtiyse) kilide girmeden düşer; yalnızca
 *   en güncel konum çözülür. Böylece hızlı sürüklemede birikme olmaz.
 */
object VideoThumbnails {

    /** Önizleme kovaları: aynı 150 ms içindeki istekler tek kare sayılır. */
    private const val BUCKET_MS = 150L

    /** Önizleme karesinin hedef genişliği (px). Küçük = çok hızlı çözülür. */
    private const val PREVIEW_WIDTH_PX = 400

    private val cache = LruCache<String, Bitmap>(24)

    private val lock = Mutex()
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverSource: String? = null
    private var sourceWidth = 0
    private var sourceHeight = 0

    /**
     * [path] (yerel dosya yolu) üzerinde [timeMs] anına en yakın kareyi döndürür.
     * Hata/okunamayan dosya durumunda `null` döner; arayüz önizlemeyi gizler.
     */
    suspend fun frameAt(path: String?, timeMs: Long): Bitmap? {
        if (path.isNullOrBlank()) return null
        val bucket = (timeMs.coerceAtLeast(0L) / BUCKET_MS) * BUCKET_MS
        val key = "$path@$bucket"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            lock.withLock {
                cache.get(key)?.let { return@withLock it }
                val r = obtain(path) ?: return@withLock null
                val bmp = runCatching { extract(r, bucket) }.getOrNull() ?: return@withLock null
                cache.put(key, bmp)
                bmp
            }
        }
    }

    /** Kaynak değiştiyse eski retriever'ı kapatır, yenisini hazırlar. */
    private fun obtain(path: String): MediaMetadataRetriever? {
        retriever?.let { if (retrieverSource == path) return it }
        release()
        val created = runCatching {
            MediaMetadataRetriever().apply { setDataSource(path) }
        }.getOrNull() ?: return null
        sourceWidth = runCatching {
            created.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        }.getOrDefault(0)
        sourceHeight = runCatching {
            created.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        }.getOrDefault(0)
        retriever = created
        retrieverSource = path
        return created
    }

    private fun extract(r: MediaMetadataRetriever, atMs: Long): Bitmap? {
        val us = atMs * 1000L
        val w = sourceWidth
        val h = sourceHeight
        // API 27+: doğru en-boy oranıyla, doğrudan küçük boyutta çöz (en hızlı yol).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && w > 0 && h > 0) {
            val tw = PREVIEW_WIDTH_PX
            val th = (tw.toLong() * h / w).toInt().coerceIn(1, 2160)
            val scaled = r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, tw, th)
            if (scaled != null) return scaled
        }
        val full = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        if (full.width <= PREVIEW_WIDTH_PX) return full
        val tw = PREVIEW_WIDTH_PX
        val th = (tw.toFloat() * full.height / full.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(full, tw, th, true)
        if (scaled !== full) full.recycle()
        return scaled
    }

    private fun release() {
        runCatching { retriever?.release() }
        retriever = null
        retrieverSource = null
        sourceWidth = 0
        sourceHeight = 0
    }

    /** Bellek baskısı / ekran kapanınca çağrılabilir. */
    fun clear() {
        cache.evictAll()
        release()
    }
}
