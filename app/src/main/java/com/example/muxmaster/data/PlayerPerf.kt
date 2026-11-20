package com.example.muxmaster.data

import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * AÇILIŞ / SARMA SÜRE ÖLÇÜMÜ (yalnızca logcat — arayüze HİÇBİR şey eklenmez).
 *
 * Neden: "video 3–8 saniye siyah ekranda bekliyor" şikâyetinde sürenin nerede geçtiği
 * (konteyner okuma mı, dekoder kurulumu mu, ağ mı) ancak ölçümle ayrılır. Burada
 *   • hangi dekoderin seçildiği ve kurulum süresi,
 *   • hazırlanma → ilk kare süresi
 * tek satır hâlinde yazılır.
 *
 * Kullanım: `adb logcat -s MuxPerf`
 * Örnek çıktı:
 *   MuxPerf: [P1] hazır 320 ms (ilk kare bekleniyor)
 *   MuxPerf: [P1] dekoder=c2.qti.av1.decoder init=180 ms
 *   MuxPerf: [P1] İLK KARE 640 ms
 *
 * Ölçüm maliyeti: video başına 3–4 satır log. Kare başına iş YOKTUR.
 */
object PlayerPerf {

    const val TAG = "MuxPerf"

    fun log(msg: String) {
        runCatching { Log.d(TAG, msg) }
    }

    /** Dekoder adı + kurulum süresini yazar (donanım mı yazılım mı olduğunu buradan görürsünüz). */
    fun attach(player: ExoPlayer, label: String) {
        runCatching {
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long
                ) {
                    log("[$label] dekoder=$decoderName init=${initializationDurationMs}ms")
                }
            })
        }
    }
}
