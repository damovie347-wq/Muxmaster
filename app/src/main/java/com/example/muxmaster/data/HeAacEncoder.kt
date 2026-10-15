package com.example.muxmaster.data

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Process as AndroidProcess
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

/**
 * xHE-AAC (Extended HE-AAC / USAC), HE-AAC (v1) ve HE-AAC v2 donanım/Codec2 hızlandırmalı encoder.
 *
 * Android MediaCodec AAC/USAC encoder'ını gerçek-zamanlı öncelik (KEY_PRIORITY = 0,
 * KEY_OPERATING_RATE = maksimum, THREAD_PRIORITY_URGENT_AUDIO) ve sıfır-beklemeli
 * tampon döngüsüyle çalıştırarak Opus kadar hızlı (bir şarkıyı ~1.5-2.5 saniyede)
 * .m4a (MP4 konteyner) olarak kodlar.
 */
object HeAacEncoder {

    const val PROFILE_LC = MediaCodecInfo.CodecProfileLevel.AACObjectLC        // 2  - AAC-LC
    const val PROFILE_HE = MediaCodecInfo.CodecProfileLevel.AACObjectHE        // 5  - HE-AAC v1 (SBR)
    const val PROFILE_HE_V2 = MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS  // 29 - HE-AAC v2 (SBR + PS)
    const val PROFILE_ELD = MediaCodecInfo.CodecProfileLevel.AACObjectELD      // 39 - AAC-ELD (LD-SBR)
    const val PROFILE_XHE = MediaCodecInfo.CodecProfileLevel.AACObjectXHE      // 42 - xHE-AAC (MPEG-D USAC)

    fun encode(
        pcmFile: File,
        outFile: File,
        sampleRate: Int,
        channels: Int,
        bitrateKbps: Int,
        profile: Int,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean = encodeBps(
        pcmFile = pcmFile,
        outFile = outFile,
        sampleRate = sampleRate,
        channels = channels,
        bitrateBps = (bitrateKbps.coerceAtLeast(6)) * 1000,
        profile = profile,
        onProgress = onProgress,
        isCancelled = isCancelled
    )

    /**
     * Tam bit/sn ([bitrateBps]) hassasiyetiyle kodlama yapar (Hedef Dosya Boyutu modu için).
     * Cihaz seçilen profili doğrudan desteklemiyorsa otomatik olarak en yakın yüksek
     * verimli profile (ör. xHE-AAC -> HE-AAC v2 / HE-AAC / ELD) düşerek hatasız tamamlar.
     */
    fun encodeBps(
        pcmFile: File,
        outFile: File,
        sampleRate: Int,
        channels: Int,
        bitrateBps: Int,
        profile: Int,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean {
        val candidateProfiles = buildCandidateProfiles(profile, channels, bitrateBps)
        for (candidate in candidateProfiles) {
            if (isCancelled()) return false
            val ok = tryEncodeWithProfile(
                pcmFile = pcmFile,
                outFile = outFile,
                sampleRate = sampleRate,
                channels = channels,
                bitrateBps = bitrateBps,
                profile = candidate,
                onProgress = onProgress,
                isCancelled = isCancelled
            )
            if (ok && outFile.exists() && outFile.length() > 0L) {
                return true
            }
        }
        return false
    }

    private fun buildCandidateProfiles(requestedProfile: Int, channels: Int, bitrateBps: Int): List<Int> {
        val list = mutableListOf<Int>()
        fun addUnique(p: Int) {
            if (p !in list) list.add(p)
        }
        when (requestedProfile) {
            PROFILE_XHE -> {
                addUnique(PROFILE_XHE)
                if (channels == 2 && bitrateBps in 12_000..48_000) {
                    addUnique(PROFILE_HE_V2)
                    addUnique(PROFILE_HE)
                    addUnique(PROFILE_ELD)
                } else {
                    addUnique(PROFILE_HE)
                    addUnique(PROFILE_ELD)
                    if (channels == 2) addUnique(PROFILE_HE_V2)
                }
                addUnique(PROFILE_LC)
            }
            PROFILE_HE_V2 -> {
                if (channels == 2) addUnique(PROFILE_HE_V2)
                addUnique(PROFILE_HE)
                addUnique(PROFILE_LC)
            }
            PROFILE_HE -> {
                addUnique(PROFILE_HE)
                addUnique(PROFILE_LC)
            }
            else -> {
                addUnique(requestedProfile)
                addUnique(PROFILE_HE)
                addUnique(PROFILE_LC)
            }
        }
        return list
    }

    private fun tryEncodeWithProfile(
        pcmFile: File,
        outFile: File,
        sampleRate: Int,
        channels: Int,
        bitrateBps: Int,
        profile: Int,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var wroteAnySample = false

        val tid = AndroidProcess.myTid()
        val prevPriority = runCatching { AndroidProcess.getThreadPriority(tid) }
            .getOrDefault(AndroidProcess.THREAD_PRIORITY_DEFAULT)
        runCatching { AndroidProcess.setThreadPriority(tid, AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO) }

        try {
            val safeBps = clampBitrateForProfile(profile, channels, bitrateBps)
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, profile)
            format.setInteger(MediaFormat.KEY_BIT_RATE, safeBps)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
            // Maksimum donanım/Codec2 hızı için gerçek-zamanlı öncelik ve yüksek çalışma hızı
            runCatching { format.setInteger(MediaFormat.KEY_PRIORITY, 0) }
            runCatching { format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt()) }

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
            val bytesPerFrame = (channels * 2).coerceAtLeast(2)
            val chunk = ByteArray(512 * 1024)
            var readBytes = 0L
            var totalFrames = 0L
            var inputDone = false
            var outputDone = false
            var lastPct = -1

            BufferedInputStream(FileInputStream(pcmFile), 1024 * 1024).use { stream ->
                while (!outputDone) {
                    if (isCancelled()) return false
                    var progressed = false

                    // 1) Hazır olan tüm giriş tamponlarını beklemeden doldur
                    while (!inputDone) {
                        val inIdx = c.dequeueInputBuffer(0L)
                        if (inIdx < 0) break
                        progressed = true
                        val presentationUs = totalFrames * 1_000_000L / sampleRate
                        val inBuf = c.getInputBuffer(inIdx)
                        if (inBuf == null) {
                            c.queueInputBuffer(inIdx, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                            break
                        }
                        inBuf.clear()
                        var want = minOf(inBuf.remaining(), chunk.size)
                        want -= want % bytesPerFrame
                        var n = 0
                        while (want > 0 && n < want) {
                            val r = stream.read(chunk, n, want - n)
                            if (r <= 0) break
                            n += r
                        }
                        n -= n % bytesPerFrame
                        if (n <= 0) {
                            c.queueInputBuffer(inIdx, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            inBuf.put(chunk, 0, n)
                            c.queueInputBuffer(inIdx, 0, n, presentationUs, 0)
                            totalFrames += (n / bytesPerFrame).toLong()
                            readBytes += n
                            val pct = ((readBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }

                    // 2) Hazır olan tüm çıkış tamponlarını beklemeden boşalt
                    while (true) {
                        val outIdx = c.dequeueOutputBuffer(info, if (progressed) 0L else 1_000L)
                        if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        progressed = true
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
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                                break
                            }
                        }
                    }
                }
            }
            return wroteAnySample
        } catch (_: Throwable) {
            runCatching { outFile.delete() }
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { AndroidProcess.setThreadPriority(tid, prevPriority) }
        }
    }

    private fun clampBitrateForProfile(profile: Int, channels: Int, bitrateBps: Int): Int {
        return when (profile) {
            PROFILE_XHE -> bitrateBps.coerceIn(if (channels == 1) 8_000 else 12_000, 256_000)
            PROFILE_HE_V2 -> bitrateBps.coerceIn(14_000, 64_000)
            PROFILE_HE -> bitrateBps.coerceIn(if (channels == 1) 12_000 else 20_000, if (channels == 1) 96_000 else 160_000)
            PROFILE_ELD -> bitrateBps.coerceIn(if (channels == 1) 16_000 else 24_000, 256_000)
            else -> bitrateBps.coerceIn(16_000, 320_000)
        }
    }
}
