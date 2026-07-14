package com.example.muxmaster.model

import android.net.Uri
import androidx.compose.runtime.Immutable

/** Bir audio/subtitle track'inin nereden geldiğini belirtir. */
enum class TrackSource { EXISTING, NEW_FILE }

/**
 * Tek bir ses track'i.
 *
 * EXISTING  -> videonun içinden geliyor (existingStreamIndex dolu)
 * NEW_FILE  -> kullanıcının + butonuyla eklediği harici dosyadan geliyor (fileUri dolu)
 *
 * @Immutable: Bu sınıf `android.net.Uri` alanı içerdiği için Compose derleyicisi
 * onu normalde "unstable" (kararsız) olarak işaretler; bunu parametre olarak alan
 * her composable (AudioTrackCard gibi) recomposition'ı ATLAYAMAZ hale gelir - yani
 * listedeki HER track kartı, ekranda alakasız bir şey değiştiğinde bile (örn. mux
 * ilerleme yüzdesi) gereksiz yere yeniden çizilir. Sınıf aslında tamamen değişmez
 * (sadece `val` alanlar) olduğu için bunu Compose'a garanti ediyoruz.
 */
@Immutable
data class AudioTrackItem(
    val id: Int,
    val source: TrackSource,

    // EXISTING track için (videonun kendi stream'i):
    val existingStreamIndex: Int = -1,
    val existingCodec: String = "",
    val existingChannels: Int = 2,
    val existingBitrate: String = "",

    // NEW_FILE track için (harici dosya):
    val fileUri: Uri? = null,
    val fileCachePath: String = "",
    val fileDisplayName: String = "",

    // Ortak alanlar:
    val language: String = "und",
    val title: String = "",
    val delayMs: Long = 0L,
    val isDefault: Boolean = false,
    val isEnabled: Boolean = true,

    // Ses yükseltme (dB). 0f = değişiklik yok. Muxlama sırasında bu track
    // için ffmpeg "volume" filtresi uygulanır (bkz. MuxViewModel).
    val gainDb: Float = 0f
)

/** Tek bir altyazı track'i. Alan anlamları AudioTrackItem ile aynı mantıkta. */
@Immutable
data class SubtitleTrackItem(
    val id: Int,
    val source: TrackSource,

    val existingStreamIndex: Int = -1,
    val existingCodec: String = "",

    val fileUri: Uri? = null,
    val fileCachePath: String = "",
    val fileDisplayName: String = "",

    val language: String = "und",
    val title: String = "",
    val delayMs: Long = 0L,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val isHearingImpaired: Boolean = false,
    val isEnabled: Boolean = true
)

/** Kullanıcının seçtiği ana video dosyası ve onunla ilgili temel bilgiler. */
@Immutable
data class VideoFile(
    val uri: Uri,
    val displayName: String,
    val cachePath: String = "",
    val videoCodec: String = "",
    val resolution: String = "",
    val durationMs: Long = 0L,
    val fileSizeMb: Float = 0f,
    val videoStreamIndex: Int = 0 // mkvmerge -d için: kaynaktaki gerçek video track id'si
)
