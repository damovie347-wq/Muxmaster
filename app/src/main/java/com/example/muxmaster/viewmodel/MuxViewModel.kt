package com.example.muxmaster.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.muxmaster.data.TrackProber
import com.example.muxmaster.model.AudioTrackItem
import com.example.muxmaster.model.SubtitleTrackItem
import com.example.muxmaster.model.TrackSource
import com.example.muxmaster.model.VideoFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Tek bir audio/subtitle track'inin nihai ffmpeg komutundaki "kaynağını" tanımlar.
 * DIRECT     -> ana videodan, ekstra input açmaya gerek yok (-map 0:N)
 * SEPARATE   -> kendine ait bir ffmpeg input'u olacak (gerekirse -itsoffset ile)
 * FAILED     -> hazırlık aşamasında (extraction) hata oluştu
 */
private sealed class MapPlan {
    data class Direct(val streamIndex: Int) : MapPlan()
    data class Separate(val path: String, val delayMs: Long) : MapPlan()
    data class Failed(val reason: String) : MapPlan()
}

class MuxViewModel(private val app: Application) : AndroidViewModel(app) {

    // ---------------------------------------------------------------------
    // STATE
    // ---------------------------------------------------------------------
    var videoFile by mutableStateOf<VideoFile?>(null)
        private set

    val audioTracks = mutableStateListOf<AudioTrackItem>()
    val subtitleTracks = mutableStateListOf<SubtitleTrackItem>()

    var outputFolderUri by mutableStateOf<Uri?>(null)
        private set
    var outputFileName by mutableStateOf("output_mux.mkv")
        private set

    var isLoading by mutableStateOf(false)
        private set
    var loadingMessage by mutableStateOf("")
        private set

    var muxProgress by mutableStateOf(0)
        private set
    var isMuxing by mutableStateOf(false)
        private set
    var resultMessage by mutableStateOf<String?>(null)
        private set
    var isSuccess by mutableStateOf(false)
        private set

    // ---------------------------------------------------------------------
    // VİDEO SEÇİLDİĞİNDE: cache'e kopyala -> ffprobe ile GERÇEK track'leri oku
    // ---------------------------------------------------------------------
    fun onVideoSelected(uri: Uri, displayName: String) {
        viewModelScope.launch {
            isLoading = true
            loadingMessage = "Video kopyalanıyor..."
            audioTracks.clear()
            subtitleTracks.clear()
            resultMessage = null
            isSuccess = false

            val videoExt = extensionFromName(displayName).ifBlank { "mkv" }
            val cacheFile = withContext(Dispatchers.IO) {
                copyUriToCache(uri, "input_video_${System.currentTimeMillis()}.$videoExt")
            }

            if (cacheFile == null) {
                resultMessage = "Video okunamadı / kopyalanamadı."
                isLoading = false
                return@launch
            }

            val sizeMb = withContext(Dispatchers.IO) {
                File(cacheFile).length().toFloat() / (1024 * 1024)
            }

            loadingMessage = "Track'ler okunuyor (ffprobe)..."
            val probeResult = withContext(Dispatchers.IO) { TrackProber.probe(cacheFile) }

            videoFile = VideoFile(
                uri = uri,
                displayName = displayName,
                cachePath = cacheFile,
                videoCodec = probeResult.videoCodec,
                resolution = probeResult.resolution,
                durationMs = probeResult.durationMs,
                fileSizeMb = sizeMb
            )

            probeResult.audioStreams.forEachIndexed { i, audio ->
                audioTracks.add(
                    AudioTrackItem(
                        id = i + 1,
                        source = TrackSource.EXISTING,
                        existingStreamIndex = audio.streamIndex,
                        existingCodec = audio.codec,
                        existingChannels = audio.channels,
                        existingBitrate = audio.bitrate,
                        language = audio.language,
                        title = audio.title,
                        isDefault = (i == 0)
                    )
                )
            }

            probeResult.subtitleStreams.forEachIndexed { i, sub ->
                subtitleTracks.add(
                    SubtitleTrackItem(
                        id = i + 1,
                        source = TrackSource.EXISTING,
                        existingStreamIndex = sub.streamIndex,
                        existingCodec = sub.codec,
                        language = sub.language,
                        title = sub.title,
                        isDefault = sub.isDefault,
                        isForced = sub.isForced
                    )
                )
            }

            outputFileName = displayName.substringBeforeLast('.', displayName) + "_mux.mkv"
            isLoading = false
            loadingMessage = ""
        }
    }

    // ---------------------------------------------------------------------
    // YENİ SES / ALTYAZI DOSYASI EKLE (+ butonu)
    // ---------------------------------------------------------------------
    fun addAudioFile(uri: Uri, displayName: String) {
        tryPersist(uri)
        val nextId = (audioTracks.maxOfOrNull { it.id } ?: 0) + 1
        audioTracks.add(
            AudioTrackItem(
                id = nextId,
                source = TrackSource.NEW_FILE,
                fileUri = uri,
                fileDisplayName = displayName,
                language = "und"
            )
        )
    }

    fun addSubtitleFile(uri: Uri, displayName: String) {
        tryPersist(uri)
        val nextId = (subtitleTracks.maxOfOrNull { it.id } ?: 0) + 1
        subtitleTracks.add(
            SubtitleTrackItem(
                id = nextId,
                source = TrackSource.NEW_FILE,
                fileUri = uri,
                fileDisplayName = displayName,
                language = "und"
            )
        )
    }

    private fun tryPersist(uri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Bazı SAF sağlayıcıları persistable permission desteklemez; tek seferlik okuma izni
            // genelde yeterlidir çünkü dosyayı bu oturumda hemen cache'e kopiyoruz.
        }
    }

    // ---------------------------------------------------------------------
    // TRACK GÜNCELLE / SİL / SIRALAMA — gerçek liste mutasyonları
    // ---------------------------------------------------------------------
    fun updateAudio(updated: AudioTrackItem) {
        val idx = audioTracks.indexOfFirst { it.id == updated.id }
        if (idx < 0) return
        if (updated.isDefault) {
            // Aynı tipte tek "default" olsun (player'lar için doğru davranış)
            for (j in audioTracks.indices) {
                if (audioTracks[j].id != updated.id && audioTracks[j].isDefault) {
                    audioTracks[j] = audioTracks[j].copy(isDefault = false)
                }
            }
        }
        audioTracks[idx] = updated
    }

    fun removeAudio(id: Int) {
        audioTracks.removeAll { it.id == id }
    }

    fun moveAudioUp(id: Int) {
        val idx = audioTracks.indexOfFirst { it.id == id }
        if (idx > 0) {
            val t = audioTracks[idx]; audioTracks[idx] = audioTracks[idx - 1]; audioTracks[idx - 1] = t
        }
    }

    fun moveAudioDown(id: Int) {
        val idx = audioTracks.indexOfFirst { it.id == id }
        if (idx in 0 until audioTracks.size - 1) {
            val t = audioTracks[idx]; audioTracks[idx] = audioTracks[idx + 1]; audioTracks[idx + 1] = t
        }
    }

    fun updateSubtitle(updated: SubtitleTrackItem) {
        val idx = subtitleTracks.indexOfFirst { it.id == updated.id }
        if (idx < 0) return
        if (updated.isDefault) {
            for (j in subtitleTracks.indices) {
                if (subtitleTracks[j].id != updated.id && subtitleTracks[j].isDefault) {
                    subtitleTracks[j] = subtitleTracks[j].copy(isDefault = false)
                }
            }
        }
        subtitleTracks[idx] = updated
    }

    fun removeSubtitle(id: Int) {
        subtitleTracks.removeAll { it.id == id }
    }

    fun moveSubtitleUp(id: Int) {
        val idx = subtitleTracks.indexOfFirst { it.id == id }
        if (idx > 0) {
            val t = subtitleTracks[idx]; subtitleTracks[idx] = subtitleTracks[idx - 1]; subtitleTracks[idx - 1] = t
        }
    }

    fun moveSubtitleDown(id: Int) {
        val idx = subtitleTracks.indexOfFirst { it.id == id }
        if (idx in 0 until subtitleTracks.size - 1) {
            val t = subtitleTracks[idx]; subtitleTracks[idx] = subtitleTracks[idx + 1]; subtitleTracks[idx + 1] = t
        }
    }

    fun setOutputFolder(uri: Uri) {
        outputFolderUri = uri
        try {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) { /* yine de devam edilebilir */ }
    }

    fun setOutputFileName(name: String) {
        // NOT: burada ".mkv" zorla eklenmiyor — kullanıcı yazarken her tuş vuruşunda
        // metni değiştirmek input alanını kullanılamaz hale getirirdi. Uzantı normalizasyonu
        // sadece mux işlemi başlarken (startMux içinde) yapılır.
        outputFileName = name
    }

    fun clearResult() {
        resultMessage = null
        isSuccess = false
    }

    fun clearVideo() {
        if (isMuxing) return
        videoFile = null
        audioTracks.clear()
        subtitleTracks.clear()
        resultMessage = null
        isSuccess = false
        muxProgress = 0
    }

    // ---------------------------------------------------------------------
    // MUX BAŞLAT — GERÇEK FFmpeg işlemi
    // ---------------------------------------------------------------------
    fun startMux() {
        val video = videoFile
        val outFolder = outputFolderUri

        if (video == null) {
            resultMessage = "Önce bir video dosyası seçin."
            isSuccess = false
            return
        }
        if (outFolder == null) {
            resultMessage = "Çıktı klasörü seçilmedi."
            isSuccess = false
            return
        }
        if (isMuxing) return

        viewModelScope.launch {
            isMuxing = true
            muxProgress = 0
            resultMessage = null
            isSuccess = false

            val workDir = File(app.cacheDir, "mux_work").also { it.mkdirs() }
            val runTempFiles = mutableListOf<File>() // bu çalıştırmaya özel temp dosyalar (sonda silinir)

            val enabledAudio = audioTracks.filter { it.isEnabled }
            val enabledSubs = subtitleTracks.filter { it.isEnabled }

            // 1) Yeni eklenen ses/altyazı dosyalarını cache'e kopyala (SAF -> gerçek dosya yolu)
            muxProgress = 5
            val preparedAudio = mutableListOf<AudioTrackItem>()
            for ((i, track) in enabledAudio.withIndex()) {
                if (track.source == TrackSource.NEW_FILE && track.fileUri != null) {
                    val ext = extensionFromName(track.fileDisplayName)
                    val cachePath = withContext(Dispatchers.IO) {
                        copyUriToCache(track.fileUri, "new_audio_${track.id}.${ext.ifBlank { "bin" }}")
                    }
                    if (cachePath == null) {
                        resultMessage = "Ses dosyası okunamadı: ${track.fileDisplayName}"
                        isMuxing = false
                        cleanupRunFiles(runTempFiles)
                        return@launch
                    }
                    runTempFiles.add(File(cachePath))
                    preparedAudio.add(track.copy(fileCachePath = cachePath))
                } else {
                    preparedAudio.add(track)
                }
                muxProgress = (5 + ((i + 1) * 5 / (enabledAudio.size.coerceAtLeast(1)))).coerceAtMost(10)
            }

            val preparedSubs = mutableListOf<SubtitleTrackItem>()
            for (track in enabledSubs) {
                if (track.source == TrackSource.NEW_FILE && track.fileUri != null) {
                    val ext = extensionFromName(track.fileDisplayName)
                    val cachePath = withContext(Dispatchers.IO) {
                        copyUriToCache(track.fileUri, "new_sub_${track.id}.${ext.ifBlank { "srt" }}")
                    }
                    if (cachePath == null) {
                        resultMessage = "Altyazı dosyası okunamadı: ${track.fileDisplayName}"
                        isMuxing = false
                        cleanupRunFiles(runTempFiles)
                        return@launch
                    }
                    runTempFiles.add(File(cachePath))
                    preparedSubs.add(track.copy(fileCachePath = cachePath))
                } else {
                    preparedSubs.add(track)
                }
            }

            muxProgress = 12

            // 2) Delay'i olan EXISTING track'leri kendi dosyalarına ayıkla (extract),
            //    böylece -itsoffset uygulayabileceğimiz ayrı bir input haline gelirler.
            val audioPlans = mutableListOf<MapPlan>()
            for (track in preparedAudio) {
                val plan = resolveAudioPlan(track, video.cachePath, workDir, runTempFiles)
                if (plan is MapPlan.Failed) {
                    resultMessage = plan.reason
                    isMuxing = false
                    cleanupRunFiles(runTempFiles)
                    return@launch
                }
                audioPlans.add(plan)
            }

            val subPlans = mutableListOf<MapPlan>()
            for (track in preparedSubs) {
                val plan = resolveSubtitlePlan(track, video.cachePath, workDir, runTempFiles)
                if (plan is MapPlan.Failed) {
                    resultMessage = plan.reason
                    isMuxing = false
                    cleanupRunFiles(runTempFiles)
                    return@launch
                }
                subPlans.add(plan)
            }

            muxProgress = 20

            // 3) Geçici çıktı dosyası
            val tempOutput = File(workDir, "temp_output_${System.currentTimeMillis()}.mkv")
            tempOutput.delete()
            runTempFiles.add(tempOutput)

            // 4) Gerçek ffmpeg argümanlarını (array olarak — quote/escape hatası riski yok) oluştur
            val args = buildFfmpegArgs(
                videoPath = video.cachePath,
                audioPlans = audioPlans,
                audioTracks = preparedAudio,
                subPlans = subPlans,
                subTracks = preparedSubs,
                outputPath = tempOutput.absolutePath
            )

            // 5) FFmpegKit ile GERÇEK encode/mux işlemini çalıştır (async + progress callback)
            val returnCodeVal = runFfmpegAsync(args, video.durationMs)

            muxProgress = 92

            if (returnCodeVal == null || returnCodeVal != 0) {
                resultMessage = "FFmpeg hatası (kod: $returnCodeVal). Track codec/format uyumsuz olabilir."
                isMuxing = false
                cleanupRunFiles(runTempFiles)
                return@launch
            }

            if (!tempOutput.exists() || tempOutput.length() == 0L) {
                resultMessage = "Hata: Çıktı dosyası oluşmadı (0 byte)."
                isMuxing = false
                cleanupRunFiles(runTempFiles)
                return@launch
            }

            // 6) SAF ile kullanıcının seçtiği hedef klasöre gerçek dosya kopyala
            muxProgress = 95
            val rawName = outputFileName.trim().ifBlank { "output_mux.mkv" }
            val finalFileName = if (rawName.endsWith(".mkv", ignoreCase = true)) rawName else "$rawName.mkv"
            val finalSizeBytes = tempOutput.length()
            val copyOk = withContext(Dispatchers.IO) {
                try {
                    val outDoc = DocumentFile.fromTreeUri(app, outFolder)
                        ?.createFile("video/x-matroska", finalFileName)
                    val outUri = outDoc?.uri ?: return@withContext false
                    app.contentResolver.openOutputStream(outUri)?.use { out ->
                        tempOutput.inputStream().use { input -> input.copyTo(out, 8 * 1024 * 1024) }
                    } ?: return@withContext false
                    true
                } catch (e: Exception) {
                    false
                }
            }

            // 7) Bu çalıştırmaya ait temp dosyaları temizle (orijinal video cache KORUNUR,
            //    böylece kullanıcı ayarları değiştirip tekrar mux'layabilir)
            cleanupRunFiles(runTempFiles)
            muxProgress = 100

            if (copyOk) {
                val sizeMb = finalSizeBytes / (1024f * 1024f)
                resultMessage = "✅ Tamamlandı! %.1f MB".format(sizeMb)
                isSuccess = true
            } else {
                resultMessage = "Hata: Dosya hedef klasöre kaydedilemedi."
                isSuccess = false
            }

            isMuxing = false
        }
    }

    private fun cleanupRunFiles(files: List<File>) {
        files.forEach { runCatching { it.delete() } }
    }

    // ---------------------------------------------------------------------
    // Bir audio track için map planı: direkt mi yoksa ayrı input mu olacak?
    // ---------------------------------------------------------------------
    private suspend fun resolveAudioPlan(
        track: AudioTrackItem,
        videoPath: String,
        workDir: File,
        runTempFiles: MutableList<File>
    ): MapPlan = withContext(Dispatchers.IO) {
        if (track.source == TrackSource.EXISTING) {
            if (track.delayMs == 0L) {
                return@withContext MapPlan.Direct(track.existingStreamIndex)
            }
            // Delay var: bu stream'i kendi dosyasına ayıkla, sonra ayrı input olarak kullan
            val extracted = File(workDir, "extract_audio_${track.id}_${System.currentTimeMillis()}.mkv")
            val args = arrayOf(
                "-y", "-i", videoPath,
                "-map", "0:${track.existingStreamIndex}",
                "-c", "copy",
                extracted.absolutePath
            )
            val session = FFmpegKit.executeWithArguments(args)
            if (!ReturnCode.isSuccess(session.returnCode) || !extracted.exists() || extracted.length() == 0L) {
                return@withContext MapPlan.Failed("Ses track'i ayıklanamadı (stream #${track.existingStreamIndex})")
            }
            runTempFiles.add(extracted)
            MapPlan.Separate(extracted.absolutePath, track.delayMs)
        } else {
            if (track.fileCachePath.isBlank()) return@withContext MapPlan.Failed("Ses dosyası bulunamadı: ${track.fileDisplayName}")
            MapPlan.Separate(track.fileCachePath, track.delayMs)
        }
    }

    private suspend fun resolveSubtitlePlan(
        track: SubtitleTrackItem,
        videoPath: String,
        workDir: File,
        runTempFiles: MutableList<File>
    ): MapPlan = withContext(Dispatchers.IO) {
        if (track.source == TrackSource.EXISTING) {
            if (track.delayMs == 0L) {
                return@withContext MapPlan.Direct(track.existingStreamIndex)
            }
            val extracted = File(workDir, "extract_sub_${track.id}_${System.currentTimeMillis()}.mkv")
            val args = arrayOf(
                "-y", "-i", videoPath,
                "-map", "0:${track.existingStreamIndex}",
                "-c", "copy",
                extracted.absolutePath
            )
            val session = FFmpegKit.executeWithArguments(args)
            if (!ReturnCode.isSuccess(session.returnCode) || !extracted.exists() || extracted.length() == 0L) {
                return@withContext MapPlan.Failed("Altyazı track'i ayıklanamadı (stream #${track.existingStreamIndex})")
            }
            runTempFiles.add(extracted)
            MapPlan.Separate(extracted.absolutePath, track.delayMs)
        } else {
            if (track.fileCachePath.isBlank()) return@withContext MapPlan.Failed("Altyazı dosyası bulunamadı: ${track.fileDisplayName}")
            MapPlan.Separate(track.fileCachePath, track.delayMs)
        }
    }

    // ---------------------------------------------------------------------
    // GERÇEK ffmpeg komutunu ARRAY (List<String>) olarak üretir.
    // Tek bir String yerine array kullanmak, dosya adında/title'da boşluk ya da
    // tırnak karakteri olduğunda komutun bozulmasını önler.
    // ---------------------------------------------------------------------
    private fun buildFfmpegArgs(
        videoPath: String,
        audioPlans: List<MapPlan>,
        audioTracks: List<AudioTrackItem>,
        subPlans: List<MapPlan>,
        subTracks: List<SubtitleTrackItem>,
        outputPath: String
    ): List<String> {
        val args = mutableListOf("-y")

        // INPUT 0: ana video
        args += listOf("-i", videoPath)

        var nextInputIndex = 1
        var hasNegativeOrSeparateOffset = false

        // Ayrı input gerektiren ses track'leri için input ekle (itsoffset dahil)
        val audioInputIndexById = mutableMapOf<Int, Int>()
        audioPlans.forEachIndexed { i, plan ->
            if (plan is MapPlan.Separate) {
                if (plan.delayMs != 0L) {
                    val seconds = plan.delayMs / 1000.0
                    // ffmpeg -itsoffset negatif değerleri de destekler (örn: -itsoffset -0.5)
                    args += listOf("-itsoffset", seconds.toString())
                    hasNegativeOrSeparateOffset = true
                }
                args += listOf("-i", plan.path)
                audioInputIndexById[audioTracks[i].id] = nextInputIndex
                nextInputIndex++
            }
        }

        val subInputIndexById = mutableMapOf<Int, Int>()
        subPlans.forEachIndexed { i, plan ->
            if (plan is MapPlan.Separate) {
                if (plan.delayMs != 0L) {
                    val seconds = plan.delayMs / 1000.0
                    args += listOf("-itsoffset", seconds.toString())
                    hasNegativeOrSeparateOffset = true
                }
                args += listOf("-i", plan.path)
                subInputIndexById[subTracks[i].id] = nextInputIndex
                nextInputIndex++
            }
        }

        // MAP: video her zaman input 0'dan
        args += listOf("-map", "0:v:0")

        // MAP: ses track'leri — kullanıcının belirlediği SIRAYLA (output sırası = output index)
        audioPlans.forEachIndexed { i, plan ->
            when (plan) {
                is MapPlan.Direct -> args += listOf("-map", "0:${plan.streamIndex}")
                is MapPlan.Separate -> {
                    val inputIdx = audioInputIndexById[audioTracks[i].id]
                    if (inputIdx != null) args += listOf("-map", "$inputIdx:a:0")
                }
                is MapPlan.Failed -> { /* buraya hiç gelinmemeli, üst seviyede zaten elendi */ }
            }
        }

        // MAP: altyazı track'leri
        subPlans.forEachIndexed { i, plan ->
            when (plan) {
                is MapPlan.Direct -> args += listOf("-map", "0:${plan.streamIndex}")
                is MapPlan.Separate -> {
                    val inputIdx = subInputIndexById[subTracks[i].id]
                    if (inputIdx != null) args += listOf("-map", "$inputIdx:s:0")
                }
                is MapPlan.Failed -> { }
            }
        }

        // CODEC: re-encode YOK, stream copy — AV1/x265/Opus/PGS/ASS dahil her şey çalışır
        args += listOf("-c", "copy")

        // -itsoffset kullanıldıysa (özellikle negatif delay) negatif zaman damgalarını
        // sıfıra normalize et; aksi halde matroska muxer bazı frame'leri atabilir.
        if (hasNegativeOrSeparateOffset) {
            args += listOf("-avoid_negative_ts", "make_zero")
        }

        // METADATA + DISPOSITION: ses track'leri (output sırasına göre s:a:i)
        audioTracks.forEachIndexed { i, track ->
            if (track.title.isNotBlank()) args += listOf("-metadata:s:a:$i", "title=${track.title}")
            if (track.language.isNotBlank() && track.language != "und") {
                args += listOf("-metadata:s:a:$i", "language=${track.language}")
            }
            args += listOf("-disposition:a:$i", if (track.isDefault) "default" else "0")
        }

        // METADATA + DISPOSITION: altyazı track'leri
        subTracks.forEachIndexed { i, track ->
            if (track.title.isNotBlank()) args += listOf("-metadata:s:s:$i", "title=${track.title}")
            if (track.language.isNotBlank() && track.language != "und") {
                args += listOf("-metadata:s:s:$i", "language=${track.language}")
            }
            val flags = buildList {
                if (track.isDefault) add("default")
                if (track.isForced) add("forced")
                if (track.isHearingImpaired) add("hearing_impaired")
            }
            args += listOf("-disposition:s:$i", if (flags.isEmpty()) "0" else flags.joinToString("+"))
        }

        args += outputPath
        return args
    }

    /**
     * FFmpegKit.executeWithArgumentsAsync'i coroutine'e bağlar.
     * Busy-wait/polling YOKTUR; tamamlanma callback'i ile suspendCancellableCoroutine
     * üzerinden gerçek (yanlış senkronizasyon riski olmayan) bekleme yapılır.
     */
    private suspend fun runFfmpegAsync(args: List<String>, durationMs: Long): Int? =
        suspendCancellableCoroutine { cont ->
            val session = FFmpegKit.executeWithArgumentsAsync(
                args.toTypedArray(),
                { completedSession ->
                    val rc = completedSession.returnCode?.value
                    if (cont.isActive) cont.resume(rc)
                },
                null
            ) { stats ->
                if (durationMs > 0) {
                    val pct = (((stats.time.toFloat() / durationMs) * 70f) + 20f)
                        .toInt().coerceIn(20, 90)
                    viewModelScope.launch { muxProgress = pct }
                }
            }
            cont.invokeOnCancellation { runCatching { session.cancel() } }
        }

    // ---------------------------------------------------------------------
    // YARDIMCI FONKSİYONLAR
    // ---------------------------------------------------------------------
    private fun copyUriToCache(uri: Uri, fileName: String): String? {
        return try {
            val file = File(app.cacheDir, "mux_work/$fileName")
            file.parentFile?.mkdirs()
            app.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8 * 1024 * 1024)
                }
            } ?: return null
            if (file.exists() && file.length() > 0) file.absolutePath else null
        } catch (e: Exception) {
            null
        }
    }

    /** Gerçek dosya adından (SAF display name) uzantı çıkarır — Uri.lastPathSegment GÜVENİLMEZ. */
    private fun extensionFromName(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        if (dot < 0 || dot == displayName.length - 1) return ""
        return displayName.substring(dot + 1).lowercase().filter { it.isLetterOrDigit() }
    }
}
