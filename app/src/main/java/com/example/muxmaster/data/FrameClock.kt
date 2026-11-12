package com.example.muxmaster.data

import androidx.compose.runtime.Immutable
import kotlin.math.roundToLong

/**
 * Videonun GERÇEK kare zamanlaması.
 *
 * Amaç: zaman çizelgesinde seçilen anı "saniyenin onda biri" gibi kaba adımlara değil,
 * videonun kendi kare sınırlarına oturtmak. 30 FPS videoda ~33,33 ms, 60 FPS videoda
 * ~16,67 ms hassasiyet verir.
 *
 * Nasıl bulunur? ffprobe zaten her video seçiminde çalışıyor (TrackProber); oradan
 * `avg_frame_rate` (gerçek ortalama kare hızı) ve varsa `nb_frames` (kare sayısı) okunur.
 * Kare sayısı varsa eşleme "kare sayısı / süre" üzerinden yapılır; bu, sabit kare hızlı
 * (CFR) videolarda FPS ile birebir aynı sonucu verir, değişken kare hızlı (VFR)
 * videolarda ise gerçek kare dağılımına FPS'ten daha yakın kalır.
 *
 * ÖNEMLİ: Bu sınıf HİÇBİR kare çözmez, hiçbir kareyi önbelleğe almaz. Sadece aritmetik
 * yapar. Kare görüntüsü her zaman mevcut oynatıcının donanım dekoderinden gelir.
 */
@Immutable
data class FrameClock(
    /** Ortalama kare hızı (ffprobe avg_frame_rate). 0 = bilinmiyor. */
    val fps: Float = 0f,
    /** Video süresi (ms). 0 = bilinmiyor. */
    val durationMs: Long = 0L,
    /** Toplam kare sayısı (ffprobe nb_frames). 0 = bilinmiyor (MKV'de çoğu zaman yok). */
    val frameCount: Int = 0
) {
    private val safeFps: Double
        get() = if (fps.isFinite() && fps >= 1f && fps <= 480f) fps.toDouble() else 30.0

    /** Bir karenin süresi (ms). 30 FPS -> 33, 60 FPS -> 17. */
    val frameStepMs: Long
        get() {
            if (frameCount > 1 && durationMs > 0L) {
                val avg = durationMs.toDouble() / frameCount.toDouble()
                if (avg >= 0.5 && avg <= 1000.0) return avg.roundToLong().coerceAtLeast(1L)
            }
            return (1000.0 / safeFps).roundToLong().coerceIn(1L, 1000L)
        }

    /** Bir karenin süresi (mikrosaniye) - ekranda "33.33 ms" yazmak için. */
    val frameStepUs: Long
        get() = if (frameCount > 1 && durationMs > 0L) {
            (durationMs.toDouble() * 1000.0 / frameCount.toDouble()).roundToLong()
        } else {
            (1_000_000.0 / safeFps).roundToLong()
        }

    /** Toplam kare sayısı (bilinmiyorsa süre x FPS'ten tahmin). 0 = hesaplanamadı. */
    val totalFrames: Long
        get() = when {
            frameCount > 1 -> frameCount.toLong()
            durationMs > 0L -> (durationMs.toDouble() * safeFps / 1000.0).roundToLong().coerceAtLeast(1L)
            else -> 0L
        }

    /** Verilen ana denk gelen kare numarası (0 tabanlı). */
    fun frameIndexAt(ms: Long): Long {
        val t = ms.coerceAtLeast(0L)
        val idx = if (frameCount > 1 && durationMs > 0L) {
            t.toDouble() * frameCount.toDouble() / durationMs.toDouble()
        } else {
            t.toDouble() * safeFps / 1000.0
        }
        return idx.roundToLong().coerceAtLeast(0L)
    }

    /** Kare numarasının başladığı an (ms). */
    fun frameTimeMs(index: Long): Long {
        val i = index.coerceAtLeast(0L)
        val t = if (frameCount > 1 && durationMs > 0L) {
            i.toDouble() * durationMs.toDouble() / frameCount.toDouble()
        } else {
            i.toDouble() * 1000.0 / safeFps
        }
        val rounded = t.roundToLong()
        return if (durationMs > 0L) rounded.coerceIn(0L, durationMs) else rounded.coerceAtLeast(0L)
    }

    /** Verilen anı en yakın kare başlangıcına oturtur. */
    fun snap(ms: Long): Long = frameTimeMs(frameIndexAt(ms))

    /**
     * Sürükleme örnekleme ızgarası: hedef anı önce en yakın [sampleMs] katına (varsayılan
     * 500 ms), sonra videonun GERÇEK kare sınırına oturtur.
     *
     * Örnek: 1350 ms -> 1500 ms (0,5 sn ızgarası) -> 1500 ms çevresindeki gerçek kare (1496 ms).
     * 0,5 sn bir "kare" değildir; yalnızca görüntülenecek örnek noktasıdır. Ekrana basılan
     * kare her zaman videoda gerçekten var olan bir karedir.
     */
    fun snapToSample(ms: Long, sampleMs: Long = SCRUB_SAMPLE_MS): Long {
        if (sampleMs <= 1L) return snap(ms)
        val grid = ((ms.coerceAtLeast(0L) + sampleMs / 2L) / sampleMs) * sampleMs
        return snap(grid)
    }

    /** Kare kare ilerle/geri git (negatif = geri). */
    fun step(ms: Long, frames: Int): Long = frameTimeMs(frameIndexAt(ms) + frames)

    /** 1 tabanlı kare numarası (kullanıcıya gösterim için). */
    fun frameNumber(ms: Long): Long = frameIndexAt(ms) + 1L

    companion object {
        /** Sürükleme örnekleme adımı: her 500 ms'de bir gerçek kare. */
        const val SCRUB_SAMPLE_MS = 500L

        /** Kare bilgisi yok: yuvarlama yapılmaz, 30 FPS varsayılır. */
        val NONE = FrameClock()

        fun of(fps: Float, durationMs: Long, frameCount: Int = 0): FrameClock =
            FrameClock(fps = fps, durationMs = durationMs, frameCount = frameCount)
    }
}
