package com.example.muxmaster.data

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileInputStream

/**
 * HE-AAC (v1) ve HE-AAC v2 encoder.
 *
 * ffmpeg-kit paketinde libfdk_aac olmadığı ve ffmpeg'in dahili "aac" encoder'ı HE-AAC
 * üretemediği için bu iş Android'in kendi MediaCodec AAC encoder'ına yaptırılır
 * (profil: AACObjectHE / AACObjectHE_PS). Girdi, ffmpeg ile önceden üretilmiş ham
 * 16-bit little-endian PCM dosyasıdır; çıktı .m4a (MP4 konteyner) dosyasıdır.
 */
object HeAacEncoder {

    const val PROFILE_HE = MediaCodecInfo.CodecProfileLevel.AACObjectHE        // HE-AAC v1 (SBR)
    const val PROFILE_HE_V2 = MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS  // HE-AAC v2 (SBR + PS)

    /**
     * @param pcmFile ham s16le PCM
     * @param outFile çıktı .m4a (cache içinde)
     * @param bitrateKbps hedef bitrate
     * @param profile [PROFILE_HE] veya [PROFILE_HE_V2]
     * @param onProgress 0..100
     * @param isCancelled true dönerse işlem yarıda kesilir (false döner)
     * @return başarılıysa true
     */
    fun encode(
        pcmFile: File,
        outFile: File,
        sampleRate: Int,
        channels: Int,
        bitrateKbps: Int,
        profile: Int,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var wroteAnySample = false
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, profile)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)

            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec = c
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()

            outFile.delete()
            val m = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            var trackIndex = -1

            val info = MediaCodec.BufferInfo()
            val totalBytes = pcmFile.length().coerceAtLeast(1L)
            val bytesPerFrame = channels * 2
            val chunk = ByteArray(16 * 1024)
            var readBytes = 0L
            var presentationUs = 0L
            var inputDone = false
            var outputDone = false
            var lastPct = -1

            FileInputStream(pcmFile).use { stream ->
                while (!outputDone) {
                    if (isCancelled()) return false

                    if (!inputDone) {
                        val inIdx = c.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val inBuf = c.getInputBuffer(inIdx)
                            if (inBuf == null) {
                                c.queueInputBuffer(inIdx, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                inBuf.clear()
                                var want = minOf(inBuf.remaining(), chunk.size)
                                want -= want % bytesPerFrame
                                val n = if (want > 0) stream.read(chunk, 0, want) else -1
                                if (n <= 0) {
                                    c.queueInputBuffer(inIdx, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    inBuf.put(chunk, 0, n)
                                    c.queueInputBuffer(inIdx, 0, n, presentationUs, 0)
                                    presentationUs += (n / bytesPerFrame).toLong() * 1_000_000L / sampleRate
                                    readBytes += n
                                    val pct = ((readBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                    if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                                }
                            }
                        }
                    }

                    val outIdx = c.dequeueOutputBuffer(info, 10_000L)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            trackIndex = m.addTrack(c.outputFormat)
                            m.start()
                            muxerStarted = true
                        }
                    } else if (outIdx >= 0) {
                        val outBuf = c.getOutputBuffer(outIdx)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            info.size = 0
                        }
                        if (info.size > 0 && outBuf != null && muxerStarted) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            m.writeSampleData(trackIndex, outBuf, info)
                            wroteAnySample = true
                        }
                        c.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
            return wroteAnySample
        } catch (e: Exception) {
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }
}
