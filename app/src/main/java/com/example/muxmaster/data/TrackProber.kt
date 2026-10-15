package com.example.muxmaster.data

import com.arthenica.ffmpegkit.FFprobeKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Videonun ve ses dosyalarının içindeki GERÇEK track'leri ffprobe ile okur.
 * Hiçbir veri uydurulmaz; ffprobe çalışmazsa veya dosya bozuksa boş liste döner.
 */
object TrackProber {

    data class ProbeResult(
        val audioStreams: List<ExistingAudio>,
        val subtitleStreams: List<ExistingSubtitle>,
        val videoCodec: String,
        val resolution: String,
        val durationMs: Long,
        val videoStreamIndex: Int = 0, // mkvmerge -d için: kaynaktaki gerçek video track id'si
        val hasVideo: Boolean = true,
        val pixFmt: String = ""
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

        val empty = ProbeResult(emptyList(), emptyList(), "Unknown", "?x?", 0, 0)
        if (jsonOutput.isBlank()) return@withContext empty

        val root = try {
            JSONObject(jsonOutput)
        } catch (e: Exception) {
            return@withContext empty
        }

        val streams = root.optJSONArray("streams") ?: return@withContext empty
        val formatObj = root.optJSONObject("format")
        val formatBitrateRaw = formatObj?.optString("bit_rate", "0")?.toLongOrNull() ?: 0L

        val audioList = mutableListOf<ExistingAudio>()
        val subList = mutableListOf<ExistingSubtitle>()
        var videoCodec = "Unknown"
        var resolution = "?x?"
        var durationMs = 0L
        var videoStreamIndex = 0
        var hasVideo = false
        var pixFmt = ""

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
                        if (dur > 0.0) durationMs = (dur * 1000).toLong()
                        videoStreamIndex = index
                        hasVideo = true
                        pixFmt = stream.optString("pix_fmt", "")
                    }
                }
                "audio" -> {
                    val channels = stream.optInt("channels", 2)
                    var bitrateRaw = stream.optString("bit_rate", "0").toLongOrNull() ?: 0L
                    if (bitrateRaw <= 0L && !hasVideo && formatBitrateRaw > 0L) {
                        bitrateRaw = formatBitrateRaw
                    }
                    val bitrateStr = if (bitrateRaw > 0) "${bitrateRaw / 1000} kbps" else "?"
                    if (durationMs == 0L) {
                        val aDur = stream.optString("duration", "0").toDoubleOrNull() ?: 0.0
                        if (aDur > 0.0) durationMs = (aDur * 1000).toLong()
                    }
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
            val dur = formatObj?.optString("duration", "0")?.toDoubleOrNull() ?: 0.0
            durationMs = (dur * 1000).toLong()
        }

        if (durationMs <= 0L && audioList.isNotEmpty()) {
            durationMs = probeAudioDurationMsFallback(videoPath)
        }

        ProbeResult(audioList, subList, videoCodec, resolution, durationMs, videoStreamIndex, hasVideo, pixFmt)
    }

    /**
     * Konteyner üstbilgisinde süre yazmayan (ör. ham .aac veya bazı VBR akışları) ses
     * dosyalarında son paket zaman damgasından gerçek süreyi (ms) hesaplar.
     */
    suspend fun probeAudioDurationMsFallback(path: String): Long = withContext(Dispatchers.IO) {
        try {
            val args = arrayOf(
                "-v", "error",
                "-select_streams", "a:0",
                "-show_entries", "packet=pts_time,duration_time",
                "-of", "csv=p=0",
                path
            )
            val session = FFprobeKit.executeWithArguments(args)
            val out = session.output ?: return@withContext 0L
            var maxEndSec = 0.0
            var totalDurSec = 0.0
            for (line in out.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val parts = trimmed.split(',')
                val pts = parts.getOrNull(0)?.toDoubleOrNull()
                val dur = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                if (dur > 0.0) totalDurSec += dur
                if (pts != null && pts >= 0.0) {
                    val end = pts + dur
                    if (end > maxEndSec) maxEndSec = end
                }
            }
            val bestSec = if (maxEndSec > 0.0) maxEndSec else totalDurSec
            (bestSec * 1000.0).toLong().coerceAtLeast(0L)
        } catch (_: Throwable) {
            0L
        }
    }

    suspend fun findForwardKeyframeSec(
        videoPath: String,
        videoStreamIndex: Int,
        fromSec: Double,
        endSec: Double,
        maxWindowSec: Double = 90.0
    ): Double? = withContext(Dispatchers.IO) {
        if (endSec <= fromSec) return@withContext null

        val searchStart = (fromSec - 0.5).coerceAtLeast(0.0)
        val windowEnd = minOf(fromSec + maxWindowSec, endSec)
        val windowLen = windowEnd - searchStart
        if (windowLen <= 0.0) return@withContext null

        val args = arrayOf(
            "-v", "error",
            "-select_streams", videoStreamIndex.toString(),
            "-read_intervals", String.format(Locale.US, "%.3f%%+%.3f", searchStart, windowLen),
            "-show_entries", "packet=pts_time,flags",
            "-print_format", "json",
            videoPath
        )

        val session = FFprobeKit.executeWithArguments(args)
        val jsonOutput = session.output ?: return@withContext null

        val root = try {
            JSONObject(jsonOutput)
        } catch (e: Exception) {
            return@withContext null
        }
        val packets = root.optJSONArray("packets") ?: return@withContext null

        var best: Double? = null
        for (i in 0 until packets.length()) {
            val pkt = packets.getJSONObject(i)
            val flags = pkt.optString("flags", "")
            if (!flags.contains('K')) continue
            val pts = pkt.optString("pts_time", "N/A").toDoubleOrNull() ?: continue
            if (pts < fromSec - 0.001) continue
            if (pts > endSec) continue
            if (best == null || pts < best!!) best = pts
        }
        best
    }
}
