package com.example.muxmaster.data

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
 * Bu sınıf tek bir "pompa" döngüsü çalıştırır: parmak hızlı hareket ederken ara istekler
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
    /** Bir sarmanın dekoderde oturması için beklenecek en uzun süre (ms). */
    private val settleTimeoutMs: Long = 90L,
    /** Art arda iki sarma arasındaki en küçük boşluk (ms). */
    private val minGapMs: Long = 8L
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingMs = -1L
    private var pumpJob: Job? = null
    private var released = false

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
        scope.cancel()
    }

    private suspend fun pump() {
        while (!released) {
            val target = pendingMs
            if (target < 0L) break
            pendingMs = -1L
            val gen = generation
            apply(target, gen)
            if (released) break
            if (pendingMs < 0L) break
            delay(minGapMs)
        }
    }

    private suspend fun apply(targetMs: Long, gen: Long) {
        if (released) return
        // Oynatıcı kapatılmışsa hiçbir şey yapılmaz (bırakılmış oynatıcıya sarma hatası).
        val state = runCatching { player.playbackState }.getOrNull() ?: return
        if (state == Player.STATE_IDLE) return
        val dur = runCatching { player.duration }.getOrDefault(C.TIME_UNSET)
        val t = if (dur > 0L && dur != C.TIME_UNSET) targetMs.coerceIn(0L, dur) else targetMs
        if (!runCatching { player.seekTo(t) }.isSuccess) return
        lastAppliedMs = t
        // Dekoderin hedef kareyi gerçekten hazırlamasını bekle (en fazla settleTimeoutMs).
        // Daha yeni bir istek gelirse bekleme ANINDA kesilir: eski kare yeni karenin
        // üzerine yazılmaz ve görüntü parmağın gerisinde kalmaz.
        val deadline = System.currentTimeMillis() + settleTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (released || gen != generation) return
            val ready = runCatching { !player.isLoading && player.playbackState == Player.STATE_READY }
                .getOrDefault(false)
            if (ready) break
            delay(4L)
        }
    }
}
