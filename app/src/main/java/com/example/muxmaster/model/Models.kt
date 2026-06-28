package com.example.muxmaster.model

import android.net.Uri

/** Bir audio/subtitle track'inin nereden geldiğini belirtir. */
enum class TrackSource { EXISTING, NEW_FILE }

/**
 * Tek bir ses track'i.
 *
 * EXISTING  -> videonun içinden geliyor (existingStreamIndex dolu)
 * NEW_FILE  -> kullanıcının + butonuyla eklediği harici dosyadan geliyor (fileUri dolu)
 */
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
    val isEnabled: Boolean = true
)

/** Tek bir altyazı track'i. Alan anlamları AudioTrackItem ile aynı mantıkta. */
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
data class VideoFile(
    val uri: Uri,
    val displayName: String,
    val cachePath: String = "",
    val videoCodec: String = "",
    val resolution: String = "",
    val durationMs: Long = 0L,
    val fileSizeMb: Float = 0f
)
