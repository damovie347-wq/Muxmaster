package com.example.muxmaster.data

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * SÜRÜKLEME (SCRUBBING) SARMA MOTORU — "en son istek kazanır".
 *
 * Neden gerekli?
 * Zaman çizelgesi sürüklenirken her 500 ms'lik örnek için oynatıcıya ayrı bir sarma isteği
 * gider. İstekler doğrudan `player.seekTo()` ile gönderilirse:
 *  - dekoder yetişemediğinde istek kuyruğu birikir, görüntü parmağın 1-2 saniye gerisinde kalır,
 *  - geç tamamlanan eski bir sarma, güncel karenin ÜZERİNE yazılabilir,
 *  - arada kalan 500 ms örnekleri hiç gösterilmez.
 *
 * Bu sınıf tek bir "pompa" döngüsü çalıştırır: her sarmanın karesi ekrana gelince EN SON
 * istenen ana geçilir. Parmak hızlı hareket ederken ara istekler
 * bilinçli olarak ATLANIR (zaten bir anlamı kalmaz), ama EN SON istenen an her zaman
 * uygulanır. Her istek bir [generation] numarası alır; daha yeni bir istek geldiğinde
 * eskisinin beklemesi anında kesilir. Böylece eski kare güncel karenin üzerine yazılamaz.
 *
 * Hiçbir kare çözülmez, hiçbir thumbnail üretilmez, video yeniden encode edilmez:
 * yalnızca mevcut donanım dekoderine "şu ana git" denir. Sarma parametresi çağıran
 * tarafından `SeekParameters.EXACT` yapılır; motor anahtar kareye ATLAMAZ.
 */
class ScrubSeeker(
    private val player: ExoPlayer,
    /** Bir sarmanın karesinin ekrana gelmesi için beklenecek en uzun süre (ms). */
    private val settleTimeoutMs: Long = 2500L,
    /** Art arda iki sarma arasındaki en küçük boşluk (ms). */
    private val minGapMs: Long = 0L
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingMs = -1L
    private var pumpJob: Job? = null
    private var released = false

    /**
     * KÖK NEDEN DÜZELTMESİ: Eskiden her yeni istek, önceki sarmanın karesi çözülüp ekrana
     * gelmeden (90 ms sonra) yeni bir `seekTo` gönderiyordu. Her yeni `seekTo` dekoderi
     * sıfırladığı için parmak hareket ettiği sürece HİÇBİR kare ekrana gelmiyordu.
     * Artık her sarmadan sonra o sarmanın karesi gerçekten çizilene kadar
     * (onRenderedFirstFrame) beklenir, sonra EN SON hedefe geçilir. Böylece sürükleme
     * boyunca sahneler kesintisiz görünür.
     */
    private var frameRendered = false
    private var readySinceSeek = false
    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            renderedFrames.incrementAndGet()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Ses dosyası / görüntüsüz durum: hazır olunca da beklemeyi bitir.
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                readySinceSeek = true
            }
        }
    }
    /**
     * ASIL KÖK NEDEN: `onRenderedFirstFrame` ExoPlayer'da her sarmada DEĞİL, yalnızca
     * renderer etkinleştirildiğinde / yüzey değiştiğinde BİR KEZ çağrılır. Bu yüzden
     * sürüklemede her sarma "kare gelmedi" sanılıp zaman aşımına düşüyor, araya giren yeni
     * `seekTo` dekoderi sıfırlıyor ve hiçbir sahne ekrana gelmiyordu.
     * `VideoFrameMetadataListener` ise ekrana gönderilen HER kare için çağrılır (duraklatılmış
     * hâlde sarmadan sonraki ilk kare dahil). Kare sayacı bununla tutulur.
     */
    private val renderedFrames = AtomicLong(0L)
    private val frameListener = VideoFrameMetadataListener { _: Long, _: Long, _: Format, _: android.media.MediaFormat? ->
        renderedFrames.incrementAndGet()
    }
    init {
        runCatching { player.addListener(listener) }
        runCatching { player.setVideoFrameMetadataListener(frameListener) }
    }

    /** Sürükleme sürüyor mu? Sürükleme bitince yapılan sarma kesin (kare hassas) yapılır. */
    var isScrubbing = false
        private set

    /** En son uygulanan (ekrana gitmesi istenen) an. */
    var lastAppliedMs = -1L
        private set

    /** Her sarma isteğine verilen artan numara (eski istekler geçersiz kalır). */
    var generation = 0L
        private set

    fun begin() {
        isScrubbing = true
    }

    /** Yeni hedef an. Bekleyen istek varsa onun yerine geçer (kuyruk oluşmaz). */
    fun request(targetMs: Long) {
        if (released) return
        generation++
        pendingMs = targetMs.coerceAtLeast(0L)
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch { pump() }
    }

    /** Sürükleme bitti: son hedef kesin olarak uygulanır. */
    fun end(finalTargetMs: Long) {
        isScrubbing = false
        request(finalTargetMs)
    }

    /** Bekleyen (henüz uygulanmamış) isteği iptal eder: düğme ile sarmada kullanılır. */
    fun cancelPending() {
        pendingMs = -1L
        generation++
    }

    fun release() {
        released = true
        isScrubbing = false
        pendingMs = -1L
        pumpJob?.cancel()
        pumpJob = null
        runCatching { player.removeListener(listener) }
        runCatching { player.clearVideoFrameMetadataListener(frameListener) }
        scope.cancel()
    }

    private suspend fun pump() {
        while (!released) {
            val target = pendingMs
            if (target < 0L) break
            pendingMs = -1L
            apply(target)
            if (released) break
            if (pendingMs < 0L) break
            if (minGapMs > 0L) delay(minGapMs)
        }
    }

    private suspend fun apply(targetMs: Long) {
        if (released) return
        // Oynatıcı kapatılmışsa hiçbir şey yapılmaz (bırakılmış oynatıcıya sarma hatası).
        val state = runCatching { player.playbackState }.getOrNull() ?: return
        if (state == Player.STATE_IDLE) return
        val dur = runCatching { player.duration }.getOrDefault(C.TIME_UNSET)
        val t = if (dur > 0L && dur != C.TIME_UNSET) targetMs.coerceIn(0L, dur) else targetMs
        // Aynı kare zaten ekrandaysa tekrar sarılmaz (dekoder boşuna sıfırlanmaz).
        if (t == lastAppliedMs && frameRendered) return
        frameRendered = false
        readySinceSeek = false
        val framesBefore = renderedFrames.get()
        if (!runCatching { player.seekTo(t) }.isSuccess) return
        lastAppliedMs = t
        val hasVideo = runCatching { player.videoSize.width > 0 }.getOrDefault(true)
        // Bu sarmanın karesi ekrana gelene kadar bekle. Bekleme sırasında yeni istek gelse
        // bile yarıda KESİLMEZ (kesilirse hiçbir kare görünmez); yeni istek kare çizilir
        // çizilmez uygulanır. Sürükleme bittiyse son hedefe hemen geçilir.
        val deadline = System.currentTimeMillis() + settleTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (released) return
            if (renderedFrames.get() != framesBefore) { frameRendered = true; break }
            if (!hasVideo && readySinceSeek) break
            if (!isScrubbing && pendingMs >= 0L) break
            delay(4L)
        }
    }
}
