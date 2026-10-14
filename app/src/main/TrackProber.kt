package com.example.muxmaster.data

import com.arthenica.ffmpegkit.FFprobeKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Videonun içindeki GERÇEK track'leri ffprobe ile okur.
 * Hiçbir veri uydurulmaz; ffprobe çalışmazsa veya video bozuksa boş liste döner,
 * sahte/varsayılan track eklenmez.
 */
object TrackProber {

    data class ProbeResult(
        val audioStreams: List<ExistingAudio>,
        val subtitleStreams: List<ExistingSubtitle>,
        val videoCodec: String,
        val resolution: String,
        val durationMs: Long
    )

    data class ExistingAudio(
        val streamIndex: Int,   // ffmpeg'deki gerçek (absolute) stream index -> -map 0:N için kullanılır
        val codec: String,      // "aac", "opus", "ac3" vs
        val language: String,   // "tur", "eng", "und"
        val title: String,
        val channels: Int,
        val bitrate: String
    )

    data class ExistingSubtitle(
        val streamIndex: Int,
        val codec: String,      // "subrip", "ass", "hdmv_pgs_subtitle" vs
        val language: String,
        val title: String,
        val isForced: Boolean,
        val isDefault: Boolean
    )

    suspend fun probe(videoPath: String): ProbeResult = withContext(Dispatchers.IO) {
        val args = arrayOf(
            "-v", "quiet",
            "-print_format", "json",
            "-show_streams",
            "-show_format",
            videoPath
        )

        val session = FFprobeKit.executeWithArguments(args)
        val jsonOutput = session.output ?: ""

        val empty = ProbeResult(emptyList(), emptyList(), "Unknown", "?x?", 0)
        if (jsonOutput.isBlank()) return@withContext empty

        val root = try {
            JSONObject(jsonOutput)
        } catch (e: Exception) {
            return@withContext empty
        }

        val streams = root.optJSONArray("streams") ?: return@withContext empty

        val audioList = mutableListOf<ExistingAudio>()
        val subList = mutableListOf<ExistingSubtitle>()
        var videoCodec = "Unknown"
        var resolution = "?x?"
        var durationMs = 0L

        for (i in 0 until streams.length()) {
            val stream = streams.getJSONObject(i)
            val codecType = stream.optString("codec_type", "")
            val index = stream.optInt("index", i)
            val tags = stream.optJSONObject("tags")
            val lang = tags?.optString("language", "und") ?: "und"
            val title = tags?.optString("title", "") ?: ""
            val disposition = stream.optJSONObject("disposition")

            when (codecType) {
                "video" -> {
                    // Kapak resmi / thumbnail stream'lerini ("attached pic") video sayma
                    val isAttachedPic = (disposition?.optInt("attached_pic", 0) ?: 0) == 1
                    if (!isAttachedPic) {
                        videoCodec = stream.optString("codec_name", "unknown").uppercase()
                        val w = stream.optInt("width", 0)
                        val h = stream.optInt("height", 0)
                        resolution = if (w > 0 && h > 0) "${w}x${h}" else "?x?"
                        val dur = stream.optString("duration", "0").toDoubleOrNull() ?: 0.0
                        durationMs = (dur * 1000).toLong()
                    }
                }
                "audio" -> {
                    val channels = stream.optInt("channels", 2)
                    val bitrateRaw = stream.optString("bit_rate", "0").toLongOrNull() ?: 0L
                    val bitrateStr = if (bitrateRaw > 0) "${bitrateRaw / 1000} kbps" else "?"
                    audioList.add(
                        ExistingAudio(
                            streamIndex = index,
                            codec = stream.optString("codec_name", "unknown"),
                            language = lang,
                            title = title,
                            channels = channels,
                            bitrate = bitrateStr
                        )
                    )
                }
                "subtitle" -> {
                    val isForced = (disposition?.optInt("forced", 0) ?: 0) == 1
                    val isDefault = (disposition?.optInt("default", 0) ?: 0) == 1
                    subList.add(
                        ExistingSubtitle(
                            streamIndex = index,
                            codec = stream.optString("codec_name", "unknown"),
                            language = lang,
                            title = title,
                            isForced = isForced,
                            isDefault = isDefault
                        )
                    )
                }
            }
        }

        if (durationMs == 0L) {
            val format = root.optJSONObject("format")
            val dur = format?.optString("duration", "0")?.toDoubleOrNull() ?: 0.0
            durationMs = (dur * 1000).toLong()
        }

        ProbeResult(audioList, subList, videoCodec, resolution, durationMs)
    }
}
