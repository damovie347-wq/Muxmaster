package com.example.muxmaster.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.muxmaster.R
import com.example.muxmaster.data.AppPreferences
import com.example.muxmaster.data.TrackProber
import com.example.muxmaster.model.AudioTrackItem
import com.example.muxmaster.model.SubtitleTrackItem
import com.example.muxmaster.model.TrackSource
import com.example.muxmaster.model.VideoFile
import com.example.muxmaster.service.MuxForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max

private sealed class MapPlan {
    data class Direct(val streamIndex: Int) : MapPlan()
    data class Separate(val path: String, val delayMs: Long) : MapPlan()
    data class Failed(val reason: String) : MapPlan()
}

class MuxViewModel(private val app: Application) : AndroidViewModel(app) {

    private val prefs = AppPreferences(app)

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

    // KÖK NEDEN (arka planda mux ilerlemesi görünmüyordu): muxProgress sadece
    // Compose state'iydi; uygulama arka plana alındığında/ekran kapatıldığında
    // kullanıcının işlemi takip edebileceği hiçbir bildirim yoktu. Artık her
    // ilerleme güncellemesi AYNI ANDA hem UI state'ini hem de MuxForegroundService
    // üzerinden gerçek bir Android bildirimini (yüzde ilerlemeli) güncelliyor.
    private fun setProgress(p: Int, statusText: String? = null) {
        muxProgress = p
        MuxForegroundService.update(app, p, statusText ?: app.getString(R.string.notif_muxing_progress))
    }

    val expandedAudioIds = mutableStateMapOf<Int, Boolean>()
    val expandedSubtitleIds = mutableStateMapOf<Int, Boolean>()

    var isExporting by mutableStateOf(false)
        private set
    var exportMessage by mutableStateOf<String?>(null)
        private set
    var exportIsSuccess by mutableStateOf(false)
        private set

    private var muxJob: Job? = null

    init {
        prefs.defaultOutputFolder?.let { outputFolderUri = it }
    }

    fun cancelMux() { muxJob?.cancel() }

    fun clearResult() { resultMessage = null; isSuccess = false }
    fun clearExportResult() { exportMessage = null }

    fun dismissAndReset() {
        resultMessage = null; isSuccess = false; muxProgress = 0
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                File(app.cacheDir, "mux_work").listFiles()
                    ?.filter { !it.name.startsWith("input_video") }
                    ?.forEach { it.delete() }
            }
        }
    }

    fun clearVideo() {
        if (isMuxing) return
        val old = videoFile?.cachePath
        videoFile = null; audioTracks.clear(); subtitleTracks.clear()
        expandedAudioIds.clear(); expandedSubtitleIds.clear()
        resultMessage = null; isSuccess = false; muxProgress = 0
        viewModelScope.launch(Dispatchers.IO) {
            if (old != null) runCatching { File(old).delete() }
            runCatching { File(app.cacheDir, "mux_work").deleteRecursively() }
        }
    }

    fun onVideoSelected(uri: Uri, displayName: String) {
        viewModelScope.launch {
            isLoading = true
            loadingMessage = app.getString(R.string.video_analyzing)
            audioTracks.clear(); subtitleTracks.clear()
            expandedAudioIds.clear(); expandedSubtitleIds.clear()
            resultMessage = null; isSuccess = false

            val old = videoFile?.cachePath
            withContext(Dispatchers.IO) {
                if (old != null) runCatching { File(old).delete() }
                runCatching { File(app.cacheDir, "mux_work").listFiles()
                    ?.filter { !it.name.startsWith("input_video") }?.forEach { it.delete() } }
            }

            val videoExt = extensionFromName(displayName).ifBlank { "mkv" }

            // KÖK NEDEN (video seçimi yavaş yükleniyordu): önceden TÜM video dosyası
            // (birkaç GB olabilir) cache'e kopyalanana kadar bekleniyor, ffprobe ancak
            // ONDAN SONRA çalıştırılıyordu — yani "işlem süresi = kopyalama + probe"
            // idi ve dosya boyutuyla orantılı gereksiz bir bekleme yaratıyordu.
            // ÇÖZÜM: ffprobe, SAF Uri'sinin file descriptor'ı üzerinden ("/proc/self/fd/N")
            // DOĞRUDAN ve PARALEL olarak çalıştırılıyor; artık "işlem süresi =
            // max(kopyalama, probe)". Kopyalama (mux için hâlâ gerekli, tek seferlik
            // yerel dosya) arka planda aynı anda devam ederken track bilgisi çok daha
            // hızlı gelir. fd üzerinden probe herhangi bir nedenle başarısız olursa
            // (bazı OEM/bulut sağlayıcılarda olabilir), kopyalanmış dosya üzerinden
            // sessizce normal probe'a geri dönülür.
            val probeDeferred = async(Dispatchers.IO) { probeFromUri(uri) }
            val cacheDeferred = async(Dispatchers.IO) {
                copyUriToCache(uri, "input_video_${System.currentTimeMillis()}.$videoExt")
            }

            val cacheFile = cacheDeferred.await()
            if (cacheFile == null) {
                probeDeferred.cancel()
                resultMessage = app.getString(R.string.err_video_read_failed)
                isLoading = false
                return@launch
            }

            val sizeMb = withContext(Dispatchers.IO) { File(cacheFile).length().toFloat() / (1024 * 1024) }

            loadingMessage = "ffprobe..."
            val probeResult = probeDeferred.await()
                ?: withContext(Dispatchers.IO) { TrackProber.probe(cacheFile) }

            videoFile = VideoFile(
                uri = uri, displayName = displayName, cachePath = cacheFile,
                videoCodec = probeResult.videoCodec, resolution = probeResult.resolution,
                durationMs = probeResult.durationMs, fileSizeMb = sizeMb
            )

            probeResult.audioStreams.forEachIndexed { i, audio ->
                audioTracks.add(AudioTrackItem(
                    id = i + 1, source = TrackSource.EXISTING,
                    existingStreamIndex = audio.streamIndex, existingCodec = audio.codec,
                    existingChannels = audio.channels, existingBitrate = audio.bitrate,
                    language = audio.language, title = audio.title, isDefault = (i == 0)
                ))
            }
            probeResult.subtitleStreams.forEachIndexed { i, sub ->
                subtitleTracks.add(SubtitleTrackItem(
                    id = i + 1, source = TrackSource.EXISTING,
                    existingStreamIndex = sub.streamIndex, existingCodec = sub.codec,
                    language = sub.language, title = sub.title,
                    isDefault = sub.isDefault, isForced = sub.isForced
                ))
            }

            outputFileName = displayName.substringBeforeLast('.', displayName) + "_mux.mkv"
            isLoading = false
            loadingMessage = ""
        }
    }

    fun addAudioFile(uri: Uri, displayName: String) {
        try { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) {}
        val nextId = (audioTracks.maxOfOrNull { it.id } ?: 0) + 1
        audioTracks.add(AudioTrackItem(id = nextId, source = TrackSource.NEW_FILE, fileUri = uri, fileDisplayName = displayName, language = "und"))
    }

    fun addSubtitleFile(uri: Uri, displayName: String) {
        try { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) {}
        val nextId = (subtitleTracks.maxOfOrNull { it.id } ?: 0) + 1
        subtitleTracks.add(SubtitleTrackItem(id = nextId, source = TrackSource.NEW_FILE, fileUri = uri, fileDisplayName = displayName, language = "und"))
    }

    fun updateAudio(updated: AudioTrackItem) {
        val idx = audioTracks.indexOfFirst { it.id == updated.id }; if (idx < 0) return
        if (updated.isDefault) for (j in audioTracks.indices) if (audioTracks[j].id != updated.id && audioTracks[j].isDefault) audioTracks[j] = audioTracks[j].copy(isDefault = false)
        audioTracks[idx] = updated
    }
    fun removeAudio(id: Int) { audioTracks.removeAll { it.id == id }; expandedAudioIds.remove(id) }
    fun moveAudioUp(id: Int) { val i = audioTracks.indexOfFirst { it.id == id }; if (i > 0) { val t = audioTracks[i]; audioTracks[i] = audioTracks[i-1]; audioTracks[i-1] = t } }
    fun moveAudioDown(id: Int) { val i = audioTracks.indexOfFirst { it.id == id }; if (i in 0 until audioTracks.size-1) { val t = audioTracks[i]; audioTracks[i] = audioTracks[i+1]; audioTracks[i+1] = t } }

    fun updateSubtitle(updated: SubtitleTrackItem) {
        val idx = subtitleTracks.indexOfFirst { it.id == updated.id }; if (idx < 0) return
        if (updated.isDefault) for (j in subtitleTracks.indices) if (subtitleTracks[j].id != updated.id && subtitleTracks[j].isDefault) subtitleTracks[j] = subtitleTracks[j].copy(isDefault = false)
        subtitleTracks[idx] = updated
    }
    fun removeSubtitle(id: Int) { subtitleTracks.removeAll { it.id == id }; expandedSubtitleIds.remove(id) }
    fun moveSubtitleUp(id: Int) { val i = subtitleTracks.indexOfFirst { it.id == id }; if (i > 0) { val t = subtitleTracks[i]; subtitleTracks[i] = subtitleTracks[i-1]; subtitleTracks[i-1] = t } }
    fun moveSubtitleDown(id: Int) { val i = subtitleTracks.indexOfFirst { it.id == id }; if (i in 0 until subtitleTracks.size-1) { val t = subtitleTracks[i]; subtitleTracks[i] = subtitleTracks[i+1]; subtitleTracks[i+1] = t } }

    fun setOutputFolder(uri: Uri) {
        outputFolderUri = uri
        try { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        catch (_: SecurityException) {}
        prefs.defaultOutputFolder = uri
    }
    fun updateOutputFileName(name: String) { outputFileName = name }

    fun exportAudioTrack(track: AudioTrackItem) {
        val folder = outputFolderUri
        if (folder == null) { exportMessage = app.getString(R.string.export_no_output_folder); exportIsSuccess = false; return }
        if (isExporting || isMuxing) return

        viewModelScope.launch {
            isExporting = true; exportMessage = null
            val displayName = withContext(Dispatchers.IO) {
                runCatching {
                    val baseName = sanitizeFileName(
                        track.title.ifBlank {
                            if (track.source == TrackSource.EXISTING) "audio_${track.existingStreamIndex}"
                            else track.fileDisplayName.substringBeforeLast('.', track.fileDisplayName)
                        }
                    )
                    if (track.source == TrackSource.EXISTING) {
                        val video = videoFile ?: return@runCatching null
                        val (ext, mime) = audioExportContainerFor(track.existingCodec)
                        val workDir = File(app.cacheDir, "mux_work").also { it.mkdirs() }
                        val tmp = File(workDir, "export_audio_${track.id}_${System.currentTimeMillis()}.$ext")
                        val s = FFmpegKit.executeWithArguments(arrayOf(
                            "-y", "-i", video.cachePath, "-map", "0:${track.existingStreamIndex}",
                            "-c", "copy", "-avoid_negative_ts", "make_zero", tmp.absolutePath
                        ))
                        val good = ReturnCode.isSuccess(s.returnCode) && tmp.exists() && tmp.length() > 0L
                        val result = if (good) copyFileToTree(tmp, folder, "$baseName.$ext", mime) else null
                        runCatching { tmp.delete() }
                        result
                    } else {
                        val uri = track.fileUri ?: return@runCatching null
                        val ext = extensionFromName(track.fileDisplayName).ifBlank { "bin" }
                        copyUriToTree(uri, folder, "$baseName.$ext")
                    }
                }.getOrNull()
            }
            exportIsSuccess = displayName != null
            exportMessage = if (displayName != null) app.getString(R.string.export_success, displayName)
                             else app.getString(R.string.export_failed)
            isExporting = false
        }
    }

    fun exportSubtitleTrack(track: SubtitleTrackItem) {
        val folder = outputFolderUri
        if (folder == null) { exportMessage = app.getString(R.string.export_no_output_folder); exportIsSuccess = false; return }
        if (isExporting || isMuxing) return

        viewModelScope.launch {
            isExporting = true; exportMessage = null
            val displayName = withContext(Dispatchers.IO) {
                runCatching {
                    val baseName = sanitizeFileName(
                        track.title.ifBlank {
                            if (track.source == TrackSource.EXISTING) "subtitle_${track.existingStreamIndex}"
                            else track.fileDisplayName.substringBeforeLast('.', track.fileDisplayName)
                        }
                    )
                    if (track.source == TrackSource.EXISTING) {
                        val video = videoFile ?: return@runCatching null
                        val codec = track.existingCodec.lowercase()
                        val ext = when (codec) {
                            "subrip", "srt" -> "srt"
                            "ass" -> "ass"
                            "ssa" -> "ssa"
                            else -> "mks"
                        }
                        val mime = when (ext) {
                            "srt" -> "application/x-subrip"
                            "ass", "ssa" -> "text/x-ssa"
                            else -> "application/x-matroska"
                        }
                        val workDir = File(app.cacheDir, "mux_work").also { it.mkdirs() }
                        val tmp = File(workDir, "export_sub_${track.id}_${System.currentTimeMillis()}.$ext")
                        val s = FFmpegKit.executeWithArguments(arrayOf(
                            "-y", "-i", video.cachePath, "-map", "0:${track.existingStreamIndex}", "-c", "copy", tmp.absolutePath
                        ))
                        val good = ReturnCode.isSuccess(s.returnCode) && tmp.exists() && tmp.length() > 0L
                        val result = if (good) copyFileToTree(tmp, folder, "$baseName.$ext", mime) else null
                        runCatching { tmp.delete() }
                        result
                    } else {
                        val uri = track.fileUri ?: return@runCatching null
                        val ext = extensionFromName(track.fileDisplayName).ifBlank { "srt" }
                        copyUriToTree(uri, folder, "$baseName.$ext")
                    }
                }.getOrNull()
            }
            exportIsSuccess = displayName != null
            exportMessage = if (displayName != null) app.getString(R.string.export_success, displayName)
                             else app.getString(R.string.export_failed)
            isExporting = false
        }
    }

    private fun copyFileToTree(src: File, folder: Uri, fileName: String, mime: String): String? {
        return try {
            val outDoc = DocumentFile.fromTreeUri(app, folder)?.createFile(mime, fileName)
            if (outDoc == null) return null
            val outUri = outDoc.uri
            val stream = app.contentResolver.openOutputStream(outUri)
            if (stream == null) return null
            stream.use { o -> src.inputStream().use { it.copyTo(o, 8 * 1024 * 1024) } }
            outDoc.name ?: fileName
        } catch (_: Exception) { null }
    }

    private fun audioExportContainerFor(codec: String): Pair<String, String> = when (codec.lowercase()) {
        "aac" -> "m4a" to "audio/mp4"
        "mp3" -> "mp3" to "audio/mpeg"
        "ac3" -> "ac3" to "audio/ac3"
        "eac3" -> "eac3" to "audio/eac3"
        "dts" -> "dts" to "audio/vnd.dts"
        "opus" -> "opus" to "audio/ogg"
        "vorbis" -> "ogg" to "audio/ogg"
        "flac" -> "flac" to "audio/flac"
        "pcm_s16le", "pcm_s16be", "pcm_s24le", "pcm_s24be", "pcm_s32le", "pcm_u8" -> "wav" to "audio/wav"
        else -> "mka" to "audio/x-matroska"
    }

    private fun copyUriToTree(src: Uri, folder: Uri, fileName: String): String? {
        return try {
            val mime = app.contentResolver.getType(src) ?: "application/octet-stream"
            val outDoc = DocumentFile.fromTreeUri(app, folder)?.createFile(mime, fileName)
            if (outDoc == null) return null
            val outUri = outDoc.uri
            val input = app.contentResolver.openInputStream(src)
            if (input == null) return null
            val output = app.contentResolver.openOutputStream(outUri)
            if (output == null) { input.close(); return null }
            input.use { i -> output.use { o -> i.copyTo(o, 8 * 1024 * 1024) } }
            outDoc.name ?: fileName
        } catch (_: Exception) { null }
    }

    private fun sanitizeFileName(name: String): String =
        name.trim().ifBlank { "track" }.map { c -> if (c in "\\/:*?\"<>|") '_' else c }.joinToString("")

    fun startMux() {
        val video = videoFile
        val outFolder = outputFolderUri
        if (video == null) { resultMessage = app.getString(R.string.err_no_video); isSuccess = false; return }
        if (outFolder == null) { resultMessage = app.getString(R.string.err_no_output_folder); isSuccess = false; return }
        if (isMuxing) return

        muxJob = viewModelScope.launch {
            // Arka plan bildirimi (bkz. MuxForegroundService): mux işlemi başladığı andan
            // bitene kadar (uygulama arka plana alınsa/ekran kapansa bile) yüzde ilerlemeli
            // gerçek bir Android bildirimi gösterilir.
            MuxForegroundService.start(app)
            try {
                isMuxing = true; resultMessage = null; isSuccess = false
                setProgress(0)

                val workDir = File(app.cacheDir, "mux_work").also { it.mkdirs() }
                val runTempFiles = mutableListOf<File>()
                val enabledAudio = audioTracks.filter { it.isEnabled }
                val enabledSubs  = subtitleTracks.filter { it.isEnabled }

                val preparedAudio = mutableListOf<AudioTrackItem>()
                for ((i, track) in enabledAudio.withIndex()) {
                    if (track.source == TrackSource.NEW_FILE && track.fileUri != null) {
                        val ext = extensionFromName(track.fileDisplayName)
                        val cp = withContext(Dispatchers.IO) { copyUriToCache(track.fileUri, "new_audio_${track.id}.$ext") }
                        if (cp == null) { resultMessage = app.getString(R.string.err_audio_read_failed, track.fileDisplayName); return@launch }
                        runTempFiles.add(File(cp)); preparedAudio.add(track.copy(fileCachePath = cp))
                    } else preparedAudio.add(track)
                    setProgress((2 + (i + 1) * 6 / enabledAudio.size.coerceAtLeast(1)).coerceAtMost(8))
                }

                val preparedSubs = mutableListOf<SubtitleTrackItem>()
                for ((i, track) in enabledSubs.withIndex()) {
                    if (track.source == TrackSource.NEW_FILE && track.fileUri != null) {
                        val ext = extensionFromName(track.fileDisplayName)
                        val cp = withContext(Dispatchers.IO) { copyUriToCache(track.fileUri, "new_sub_${track.id}.$ext") }
                        if (cp == null) { resultMessage = app.getString(R.string.err_sub_read_failed, track.fileDisplayName); return@launch }
                        runTempFiles.add(File(cp)); preparedSubs.add(track.copy(fileCachePath = cp))
                    } else preparedSubs.add(track)
                    setProgress((8 + (i + 1) * 2 / enabledSubs.size.coerceAtLeast(1)).coerceAtMost(10))
                }

                setProgress(10)

                // Ses track'leri artık planlama aşamasında ffmpeg çağırmıyor (bkz.
                // resolveAudioPlan) — bu yüzden bu adımda ilerleme çubuğu ilerletmeye
                // gerek yok, anında tamamlanıyor.
                val audioPlans = mutableListOf<MapPlan>()
                for (track in preparedAudio) {
                    val plan = resolveAudioPlan(track)
                    if (plan is MapPlan.Failed) { resultMessage = plan.reason; return@launch }
                    audioPlans.add(plan)
                }

                // Altyazı track'leri, gecikmesi olan EXISTING kaynaklar için hâlâ ayrı
                // dosyaya ayıklanıyor (extract); bu, gerçek zaman alabilen tek adım
                // olduğundan ilerleme çubuğuna 10-15 aralığında yansıtılıyor.
                val subPlans = mutableListOf<MapPlan>()
                for ((i, track) in preparedSubs.withIndex()) {
                    val plan = resolveSubtitlePlan(track, video.cachePath, workDir, runTempFiles)
                    if (plan is MapPlan.Failed) { resultMessage = plan.reason; return@launch }
                    subPlans.add(plan)
                    setProgress((10 + (i + 1) * 5 / preparedSubs.size.coerceAtLeast(1)).coerceAtMost(15))
                }

                setProgress(15)
                val tempOutput = File(workDir, "temp_output_${System.currentTimeMillis()}.mkv").also { it.delete() }
                runTempFiles.add(tempOutput)

                val args = buildFfmpegArgs(video.cachePath, audioPlans, preparedAudio, subPlans, preparedSubs, tempOutput.absolutePath)
                val rc = runFfmpegAsync(args, video.durationMs)
                setProgress(85)

                if (rc == null || rc != 0) { resultMessage = app.getString(R.string.err_ffmpeg_code, rc.toString()); return@launch }
                if (!tempOutput.exists() || tempOutput.length() == 0L) { resultMessage = app.getString(R.string.err_output_zero_bytes); return@launch }

                setProgress(88, app.getString(R.string.notif_saving_output))
                val rawName = outputFileName.trim().ifBlank { "output_mux.mkv" }
                val finalName = if (rawName.endsWith(".mkv", true)) rawName else "$rawName.mkv"
                val finalBytes = tempOutput.length()

                // KÖK NEDEN (ilerleme çubuğu düşük yüzdeden aniden %100'e zıplıyordu):
                // çıktı dosyası (birkaç GB olabilir) SAF klasörüne TEK SEFERDE
                // copyTo() ile kopyalanıyordu; bu adım gerçek zaman alsa da ilerleme
                // çubuğunda HİÇ yansımıyor, kopyalama bitince değer doğrudan 100'e
                // atlıyordu. ÇÖZÜM: kopyalama artık kendi byte sayacıyla manuel
                // parça parça (chunk) yapılıyor ve gerçek kopyalanan oran 88-99
                // aralığına yansıtılıyor.
                val copyOk = withContext(Dispatchers.IO) {
                    try {
                        val outDoc = DocumentFile.fromTreeUri(app, outFolder)?.createFile("video/x-matroska", finalName)
                        val outUri = outDoc?.uri ?: return@withContext false
                        app.contentResolver.openOutputStream(outUri)?.use { o ->
                            tempOutput.inputStream().use { input ->
                                val buffer = ByteArray(4 * 1024 * 1024)
                                var copied = 0L
                                var lastPct = -1
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    o.write(buffer, 0, read)
                                    copied += read
                                    val within = if (finalBytes > 0) ((copied.toFloat() / finalBytes) * 11f).toInt() else 11
                                    val pct = (88 + within).coerceIn(88, 99)
                                    if (pct != lastPct) { lastPct = pct; setProgress(pct, app.getString(R.string.notif_saving_output)) }
                                }
                            }
                        } ?: return@withContext false
                        triggerMediaScan(outUri)
                        true
                    } catch (_: Exception) { false }
                }

                withContext(Dispatchers.IO) { runTempFiles.forEach { runCatching { it.delete() } } }
                setProgress(100)

                if (copyOk) {
                    resultMessage = app.getString(R.string.result_mux_success, finalBytes / (1024f*1024f))
                    isSuccess = true
                } else {
                    resultMessage = app.getString(R.string.err_save_to_folder_failed)
                    isSuccess = false
                }
            } catch (c: CancellationException) {
                resultMessage = app.getString(R.string.result_mux_cancelled)
                isSuccess = false
                withContext(Dispatchers.IO) { runCatching { File(app.cacheDir, "mux_work").listFiles()?.filter { !it.name.startsWith("input_video") }?.forEach { it.delete() } } }
                throw c
            } finally {
                isMuxing = false
                MuxForegroundService.stop(app, resultMessage, isSuccess)
            }
        }
    }

    private fun resolveAudioPlan(track: AudioTrackItem): MapPlan {
        // KÖK NEDEN (delay girilse de sese HİÇBİR ŞEKİLDE yansımıyordu — ne pozitif
        // ne negatif değerde): önceki sürüm, gecikmesi olan EXISTING track'leri ayrı
        // bir dosyaya "extract" edip "-itsoffset" ile ayrı input olarak veriyordu.
        // Bu, "-avoid_negative_ts" ve muxer'ın interleave davranışıyla birlikte
        // ÇALIŞMASI GEREKEN ama pratikte container zaman damgası seviyesinde
        // sessizce iptal olabilen kırılgan bir mekanizmaydı. ÇÖZÜM: ses gecikmesi
        // artık container/timestamp oyunlarıyla DEĞİL, doğrudan örnek (sample)
        // seviyesinde "adelay"/"atrim" ses filtreleriyle uygulanıyor (bkz.
        // buildFfmpegArgs) — hangi player/muxer olursa olsun deterministik ve
        // garanti çalışır. Bu sayede EXISTING track'ler delay için ARTIK HİÇ
        // extract edilmiyor: her zaman doğrudan orijinal videodan map'lenir
        // (ayrıca daha hızlı, çünkü fazladan bir ffmpeg çağrısı yapılmıyor).
        return if (track.source == TrackSource.EXISTING) {
            MapPlan.Direct(track.existingStreamIndex)
        } else if (track.fileCachePath.isBlank()) {
            MapPlan.Failed(app.getString(R.string.err_audio_file_missing, track.fileDisplayName))
        } else {
            MapPlan.Separate(track.fileCachePath, 0L)
        }
    }

    private suspend fun resolveSubtitlePlan(track: SubtitleTrackItem, videoPath: String, workDir: File, runTempFiles: MutableList<File>): MapPlan {
        return withContext(Dispatchers.IO) {
            if (track.source == TrackSource.EXISTING) {
                if (track.delayMs == 0L) return@withContext MapPlan.Direct(track.existingStreamIndex)
                val extracted = File(workDir, "extract_sub_${track.id}_${System.currentTimeMillis()}.mkv")
                val s = FFmpegKit.executeWithArguments(arrayOf("-y","-i",videoPath,"-map","0:${track.existingStreamIndex}","-c","copy",extracted.absolutePath))
                if (!ReturnCode.isSuccess(s.returnCode) || !extracted.exists() || extracted.length()==0L) return@withContext MapPlan.Failed(app.getString(R.string.err_sub_extract_failed, track.existingStreamIndex))
                runTempFiles.add(extracted); MapPlan.Separate(extracted.absolutePath, track.delayMs)
            } else {
                if (track.fileCachePath.isBlank()) return@withContext MapPlan.Failed(app.getString(R.string.err_sub_file_missing, track.fileDisplayName))
                MapPlan.Separate(track.fileCachePath, track.delayMs)
            }
        }
    }

    private fun buildFfmpegArgs(videoPath: String, audioPlans: List<MapPlan>, audioTracks: List<AudioTrackItem>, subPlans: List<MapPlan>, subTracks: List<SubtitleTrackItem>, outputPath: String): List<String> {
        val args = mutableListOf("-y"); args += listOf("-i", videoPath)
        var nextIdx = 1
        var hasSubOffset = false
        var maxAbsSubDelayMs = 0L

        // Ses input'ları: ARTIK "-itsoffset" uygulanmıyor. Gecikme, aşağıdaki
        // "-filter:a:$i" bölümünde adelay/atrim ile örnek seviyesinde veriliyor.
        val audioInputIdx = mutableMapOf<Int,Int>()
        audioPlans.forEachIndexed { i, plan -> if (plan is MapPlan.Separate) { args += listOf("-i",plan.path); audioInputIdx[audioTracks[i].id]=nextIdx++ } }

        // Altyazı input'ları: metin tabanlı cue zaman damgaları için "-itsoffset"
        // hâlâ standart ve doğru yöntem; değiştirilmedi.
        val subInputIdx = mutableMapOf<Int,Int>()
        subPlans.forEachIndexed { i, plan -> if (plan is MapPlan.Separate) { if (plan.delayMs != 0L) { args += listOf("-itsoffset",(plan.delayMs/1000.0).toString()); hasSubOffset=true; maxAbsSubDelayMs = max(maxAbsSubDelayMs, abs(plan.delayMs)) }; args += listOf("-i",plan.path); subInputIdx[subTracks[i].id]=nextIdx++ } }

        args += listOf("-map","0:v:0")
        audioPlans.forEachIndexed { i, plan -> when(plan) { is MapPlan.Direct -> args += listOf("-map","0:${plan.streamIndex}"); is MapPlan.Separate -> audioInputIdx[audioTracks[i].id]?.let { args += listOf("-map","$it:a:0") }; is MapPlan.Failed -> {} } }
        subPlans.forEachIndexed { i, plan -> when(plan) { is MapPlan.Direct -> args += listOf("-map","0:${plan.streamIndex}"); is MapPlan.Separate -> subInputIdx[subTracks[i].id]?.let { args += listOf("-map","$it:s:0") }; is MapPlan.Failed -> {} } }

        args += listOf("-c:v","copy")

        // KÖK NEDEN (ses seviyesi/kazanç artırıldığında muxlama AŞIRI yavaşlıyordu):
        // "-max_interleave_delta" daha önce "0" (TAMAMEN SINIRSIZ tamponlama) idi.
        // Video "-c:v copy" ile neredeyse anında akarken, kazanç (gain) uygulanan
        // ses track'i YENİDEN KODLANIYOR (decode+filter+encode) — yani video
        // paketleri sesten çok daha hızlı üretiliyor. Sınırsız pencerede muxer,
        // yavaş sesin yetişmesini beklerken TÜM video paketlerini bellekte
        // biriktirmek zorunda kalıyordu; büyük dosyalarda bu bellek/G-Ç baskısı
        // mux süresini dakikalarca uzatıyordu. ÇÖZÜM: pencereyi tamamen sınırsız
        // bırakmak yerine, gerçekte kullanılan en büyük altyazı gecikmesinin biraz
        // üzerinde SONLU bir değere sabitliyoruz — hem eski senkron/kesilme
        // sorununu çözüyor hem de sınırsız tamponlanmayı (dolayısıyla aşırı
        // yavaşlamayı) engelliyor. (Ses artık itsoffset kullanmadığı için bu
        // hesaplamaya dahil değil — bkz. yukarısı.)
        val interleaveWindowUs = if (hasSubOffset) {
            ((maxAbsSubDelayMs + 15_000L) * 1000L).coerceAtLeast(15_000_000L)
        } else {
            10_000_000L // ffmpeg varsayılanı (10sn); offset yoksa dokunmaya gerek yok
        }
        args += listOf("-max_interleave_delta", interleaveWindowUs.toString())

        // KÖK NEDEN 2 (videonun başında otomatik ileri sarma/atlama):
        // "-avoid_negative_ts make_zero" TÜM akışları (dokunulmamış video dahil)
        // ortak bir sıfır noktasına göre kaydırıyordu; video akışının kendi
        // start_time'ı tam sıfır olmadığında (çoğu mp4/mkv kaynağında normaldir)
        // bu, video akışının birkaç kare ileri kaymasına, yani oynatılırken
        // başın atlanmış/hızlı sarılmış gibi görünmesine neden oluyordu.
        // ÇÖZÜM: "make_non_negative" yalnızca gerçekten negatif zaman damgasına
        // sahip akışı (negatif gecikme uygulanan altyazı) düzeltir, diğer
        // akışları (video gibi) olduğu gibi bırakır.
        if (hasSubOffset) args += listOf("-avoid_negative_ts", "make_non_negative")

        audioTracks.forEachIndexed { i, t ->
            val hasGain = abs(t.gainDb) > 0.05f
            val hasDelay = t.delayMs != 0L
            if (hasGain || hasDelay) {
                val filters = mutableListOf<String>()
                // Gecikme: örnek seviyesinde, deterministik uygulama.
                // Pozitif değer  -> ses GEÇ başlasın: başa sessizlik eklenir (adelay).
                // Negatif değer  -> ses ERKEN başlasın: baştan o kadarı kesilir (atrim).
                if (t.delayMs > 0L) {
                    filters += "adelay=delays=${t.delayMs}:all=1"
                } else if (t.delayMs < 0L) {
                    val trimSec = "%.3f".format(java.util.Locale.US, (-t.delayMs) / 1000.0)
                    filters += "atrim=start=$trimSec"
                    filters += "asetpts=PTS-STARTPTS"
                }
                if (hasGain) {
                    val gainStr = "%.1f".format(java.util.Locale.US, t.gainDb)
                    filters += "volume=${gainStr}dB"
                    filters += "acompressor=threshold=-6dB:ratio=4:attack=5:release=80:makeup=1"
                    filters += "alimiter=limit=0.999:attack=1:release=50:level=0"
                }
                args += listOf("-filter:a:$i", filters.joinToString(","))
                args += listOf("-c:a:$i", "aac", "-b:a:$i", "192k")
            } else {
                args += listOf("-c:a:$i", "copy")
            }
            if (t.title.isNotBlank()) args += listOf("-metadata:s:a:$i","title=${t.title}")
            if (t.language.isNotBlank() && t.language!="und") args += listOf("-metadata:s:a:$i","language=${t.language}")
            args += listOf("-disposition:a:$i", if (t.isDefault) "default" else "0")
        }

        args += listOf("-c:s","copy")
        subTracks.forEachIndexed { i, t -> if (t.title.isNotBlank()) args += listOf("-metadata:s:s:$i","title=${t.title}"); if (t.language.isNotBlank() && t.language!="und") args += listOf("-metadata:s:s:$i","language=${t.language}"); val f = buildList { if(t.isDefault) add("default"); if(t.isForced) add("forced"); if(t.isHearingImpaired) add("hearing_impaired") }; args += listOf("-disposition:s:$i",if(f.isEmpty()) "0" else f.joinToString("+")) }
        args += outputPath; return args
    }

    private suspend fun runFfmpegAsync(args: List<String>, durationMs: Long): Int? = suspendCancellableCoroutine { cont ->
        val session = FFmpegKit.executeWithArgumentsAsync(args.toTypedArray(),
            { s -> val rc = s.returnCode?.value; if (cont.isActive) cont.resume(rc) }, null
        ) { stats ->
            if (durationMs > 0) {
                // Ana ffmpeg encode aşaması ilerleme çubuğunda 15-85 aralığına
                // yansıtılıyor; hazırlık (0-15) ve kaydetme (85-100) adımlarıyla
                // birlikte artık uçtan uca gerçekçi/monoton bir ilerleme elde ediliyor.
                val p = (((stats.time.toFloat()/durationMs)*70f)+15f).toInt().coerceIn(15,85)
                viewModelScope.launch { setProgress(p) }
            }
        }
        cont.invokeOnCancellation { runCatching { session.cancel() } }
    }

    private fun copyUriToCache(uri: Uri, fileName: String): String? {
        return try {
            val f = File(app.cacheDir, "mux_work/$fileName").also { it.parentFile?.mkdirs() }
            val input = app.contentResolver.openInputStream(uri) ?: return null
            input.use { i -> f.outputStream().use { o -> i.copyTo(o, 8*1024*1024) } }
            if (f.exists() && f.length() > 0) f.absolutePath else null
        } catch (_: Exception) { null }
    }

    // Uri'nin SAF file descriptor'ını "/proc/self/fd/N" yolu olarak ffprobe'a doğrudan
    // verir; tam kopyalamayı beklemeden video track bilgisini okumayı sağlar (bkz.
    // onVideoSelected). videoCodec "Unknown" dönerse (fd okunamadı/desteklenmiyor)
    // null döndürülür ve çağıran taraf kopyalanmış dosya üzerinden tekrar dener.
    private suspend fun probeFromUri(uri: Uri): TrackProber.ProbeResult? = withContext(Dispatchers.IO) {
        var pfd: android.os.ParcelFileDescriptor? = null
        try {
            pfd = app.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
            val result = TrackProber.probe("/proc/self/fd/${pfd.fd}")
            if (result.videoCodec != "Unknown") result else null
        } catch (_: Exception) {
            null
        } finally {
            try { pfd?.close() } catch (_: Exception) { }
        }
    }

    private fun extensionFromName(n: String): String { val d = n.lastIndexOf('.'); return if (d<0||d==n.length-1) "" else n.substring(d+1).lowercase().filter { it.isLetterOrDigit() } }

    private fun triggerMediaScan(docUri: Uri) {
        try {
            val docId = android.provider.DocumentsContract.getDocumentId(docUri)
            val parts = docId.split(":", limit = 2)
            if (parts.size == 2 && parts[0].equals("primary", ignoreCase = true)) {
                val realPath = "${android.os.Environment.getExternalStorageDirectory().absolutePath}/${parts[1]}"
                if (File(realPath).exists()) {
                    android.media.MediaScannerConnection.scanFile(app, arrayOf(realPath), arrayOf("video/x-matroska"), null)
                }
            }
        } catch (_: Exception) { }
    }
}
