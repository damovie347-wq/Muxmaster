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
import com.example.muxmaster.data.NativeTools
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
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Bir audio/subtitle track'inin mkvmerge komutunda nasıl temsil edileceğini belirtir.
 *
 * FromSource -> track, ana video dosyasının İÇİNDEN doğrudan mkvmerge'e -a/-s ile seçilerek
 *               alınır (ekstraksiyon YOK, tamamen lossless, --sync ile delay uygulanır).
 * FromFile   -> track, ayrı bir dosya olarak mkvmerge'e eklenir (kullanıcının eklediği harici
 *               dosya OLABİLİR, ya da ses yükseltme (gain) gerektiği için önce ffmpeg ile
 *               işlenip yeni bir dosyaya yazılmış OLABİLİR).
 */
private sealed class TrackPlan {
    data class FromSource(val trackId: Int, val delayMs: Long) : TrackPlan()
    data class FromFile(val path: String, val delayMs: Long) : TrackPlan()
    data class Failed(val reason: String) : TrackPlan()
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

    /** Uygulamanın kendi private çalışma dizini (Termux/paylaşımlı depolamaya gerek yok). */
    private fun workDir(): File = File(app.cacheDir, "mux_work").also { it.mkdirs() }

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
                workDir().listFiles()
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
            runCatching { workDir().deleteRecursively() }
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
                runCatching { workDir().listFiles()
                    ?.filter { !it.name.startsWith("input_video") }?.forEach { it.delete() } }
            }

            val videoExt = extensionFromName(displayName).ifBlank { "mkv" }

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
                durationMs = probeResult.durationMs, fileSizeMb = sizeMb,
                videoStreamIndex = probeResult.videoStreamIndex
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
                        if (!NativeTools.ensureInstalled(app)) return@runCatching null
                        val (ext, mime) = audioExportContainerFor(track.existingCodec)
                        val tmp = File(workDir(), "export_audio_${track.id}_${System.currentTimeMillis()}.$ext")
                        val res = NativeTools.run(
                            app, NativeTools.mkvextractPath(app),
                            listOf(video.cachePath, "tracks", "${track.existingStreamIndex}:${tmp.absolutePath}")
                        )
                        val good = (res.exitCode == 0 || res.exitCode == 1) && tmp.exists() && tmp.length() > 0L
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
                        if (!NativeTools.ensureInstalled(app)) return@runCatching null
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
                        val tmp = File(workDir(), "export_sub_${track.id}_${System.currentTimeMillis()}.$ext")
                        val res = NativeTools.run(
                            app, NativeTools.mkvextractPath(app),
                            listOf(video.cachePath, "tracks", "${track.existingStreamIndex}:${tmp.absolutePath}")
                        )
                        val good = (res.exitCode == 0 || res.exitCode == 1) && tmp.exists() && tmp.length() > 0L
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
            MuxForegroundService.start(app)
            try {
                isMuxing = true; resultMessage = null; isSuccess = false
                setProgress(0)

                if (!withContext(Dispatchers.IO) { NativeTools.ensureInstalled(app) }) {
                    resultMessage = app.getString(R.string.err_native_tools_missing)
                    return@launch
                }

                val wd = workDir()
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

                val audioPlans = mutableListOf<TrackPlan>()
                for (track in preparedAudio) {
                    val plan = resolveAudioPlan(track, wd, runTempFiles)
                    if (plan is TrackPlan.Failed) { resultMessage = plan.reason; return@launch }
                    audioPlans.add(plan)
                }

                val subPlans = mutableListOf<TrackPlan>()
                for (track in preparedSubs) {
                    val plan = resolveSubtitlePlan(track)
                    if (plan is TrackPlan.Failed) { resultMessage = plan.reason; return@launch }
                    subPlans.add(plan)
                }

                setProgress(15)
                val tempOutput = File(wd, "temp_output_${System.currentTimeMillis()}.mkv").also { it.delete() }
                runTempFiles.add(tempOutput)

                val args = buildMkvmergeArgs(video, preparedAudio, audioPlans, preparedSubs, subPlans, tempOutput.absolutePath)

                val execResult = NativeTools.run(app, NativeTools.mkvmergePath(app), args) { p ->
                    viewModelScope.launch { setProgress((15 + (p * 70 / 100)).coerceIn(15, 85)) }
                }
                setProgress(85)

                // mkvmerge exit code: 0 = başarılı, 1 = uyarılarla tamamlandı (yine de geçerli çıktı), 2 = hata
                if (execResult.exitCode != 0 && execResult.exitCode != 1) {
                    val detail = execResult.output.trim().takeLast(300)
                    resultMessage = app.getString(R.string.err_mkvmerge_code, execResult.exitCode) +
                        if (detail.isNotBlank()) "\n$detail" else ""
                    return@launch
                }
                if (!tempOutput.exists() || tempOutput.length() == 0L) {
                    val detail = execResult.output.trim().takeLast(500)
                    resultMessage = app.getString(R.string.err_output_zero_bytes) +
                        " (exit=${execResult.exitCode})" +
                        if (detail.isNotBlank()) "\n$detail" else ""
                    return@launch
                }

                setProgress(88, app.getString(R.string.notif_saving_output))
                val rawName = outputFileName.trim().ifBlank { "output_mux.mkv" }
                val finalName = if (rawName.endsWith(".mkv", true)) rawName else "$rawName.mkv"
                val finalBytes = tempOutput.length()

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
                withContext(Dispatchers.IO) { runCatching { workDir().listFiles()?.filter { !it.name.startsWith("input_video") }?.forEach { it.delete() } } }
                throw c
            } finally {
                isMuxing = false
                MuxForegroundService.stop(app, resultMessage, isSuccess)
            }
        }
    }

    /**
     * EXISTING (videonun içinden) track, ses yükseltme (gain) istemiyorsa hiç ekstraksiyon
     * yapılmadan doğrudan mkvmerge'e -a ile seçtirilir (FromSource). Gain isteniyorsa - mkvmerge
     * ses filtresi uygulayamadığı için - önce ffmpeg ile volume/compressor/limiter filtresinden
     * geçirilip yeni bir dosyaya yazılır (FromFile). Delay HER ZAMAN mkvmerge --sync ile
     * uygulanır (ffmpeg'in adelay/atrim'ine artık hiç gerek yok).
     */
    private suspend fun resolveAudioPlan(track: AudioTrackItem, wd: File, runTempFiles: MutableList<File>): TrackPlan {
        val needsGain = abs(track.gainDb) > 0.05f
        return if (track.source == TrackSource.EXISTING) {
            if (!needsGain) {
                TrackPlan.FromSource(track.existingStreamIndex, track.delayMs)
            } else {
                val video = videoFile ?: return TrackPlan.Failed(app.getString(R.string.err_no_video))
                val processed = withContext(Dispatchers.IO) {
                    applyGainFilter(video.cachePath, "0:${track.existingStreamIndex}", track.existingCodec, track.gainDb, wd)
                } ?: return TrackPlan.Failed(app.getString(R.string.err_audio_extract_failed, track.existingStreamIndex))
                runTempFiles.add(processed)
                TrackPlan.FromFile(processed.absolutePath, track.delayMs)
            }
        } else {
            if (track.fileCachePath.isBlank()) return TrackPlan.Failed(app.getString(R.string.err_audio_file_missing, track.fileDisplayName))
            if (!needsGain) {
                TrackPlan.FromFile(track.fileCachePath, track.delayMs)
            } else {
                val codec = withContext(Dispatchers.IO) { TrackProber.probe(track.fileCachePath) }.audioStreams.firstOrNull()?.codec ?: ""
                val processed = withContext(Dispatchers.IO) {
                    applyGainFilter(track.fileCachePath, "0:a:0", codec, track.gainDb, wd)
                } ?: return TrackPlan.Failed(app.getString(R.string.err_audio_read_failed, track.fileDisplayName))
                runTempFiles.add(processed)
                TrackPlan.FromFile(processed.absolutePath, track.delayMs)
            }
        }
    }

    /** Altyazılarda gain kavramı yok; her zaman doğrudan seçilir veya doğrudan eklenir. */
    private fun resolveSubtitlePlan(track: SubtitleTrackItem): TrackPlan {
        return if (track.source == TrackSource.EXISTING) {
            TrackPlan.FromSource(track.existingStreamIndex, track.delayMs)
        } else if (track.fileCachePath.isBlank()) {
            TrackPlan.Failed(app.getString(R.string.err_sub_file_missing, track.fileDisplayName))
        } else {
            TrackPlan.FromFile(track.fileCachePath, track.delayMs)
        }
    }

    /**
     * mkvmerge komut satırı argümanlarını inşa eder. Video + gain gerektirmeyen mevcut audio/altyazı
     * track'leri TEK bir kaynak dosya (ana video) segmentinden -d/-a/-s ile seçilir; her track'in
     * language/track-name/default/forced/hi/sync ayarları o segmentte ilgili track id'sine uygulanır.
     * Harici dosyalar (yeni eklenen ya da gain-işlenmiş) ayrı segmentler olarak eklenir.
     * --track-order ile nihai sıralama (video, sonra kullanıcının sıraladığı ses/altyazılar) garanti edilir.
     */
    private fun buildMkvmergeArgs(
        video: VideoFile,
        audioTracks: List<AudioTrackItem>, audioPlans: List<TrackPlan>,
        subTracks: List<SubtitleTrackItem>, subPlans: List<TrackPlan>,
        outputPath: String
    ): List<String> {
        val args = mutableListOf("--gui-mode", "-o", outputPath)

        val sourceAudioIds = mutableListOf<Int>()
        val sourceSubIds = mutableListOf<Int>()
        audioTracks.forEachIndexed { i, _ -> (audioPlans[i] as? TrackPlan.FromSource)?.let { sourceAudioIds.add(it.trackId) } }
        subTracks.forEachIndexed { i, _ -> (subPlans[i] as? TrackPlan.FromSource)?.let { sourceSubIds.add(it.trackId) } }

        val sourceOptions = mutableListOf<String>()
        audioTracks.forEachIndexed { i, t ->
            val plan = audioPlans[i]
            if (plan is TrackPlan.FromSource) {
                val id = plan.trackId
                sourceOptions += listOf("--language", "$id:${t.language.ifBlank { "und" }}")
                if (t.title.isNotBlank()) sourceOptions += listOf("--track-name", "$id:${t.title}")
                sourceOptions += listOf("--default-track", "$id:${if (t.isDefault) "yes" else "no"}")
                sourceOptions += listOf("--sync", "$id:${plan.delayMs}")
            }
        }
        subTracks.forEachIndexed { i, t ->
            val plan = subPlans[i]
            if (plan is TrackPlan.FromSource) {
                val id = plan.trackId
                sourceOptions += listOf("--language", "$id:${t.language.ifBlank { "und" }}")
                if (t.title.isNotBlank()) sourceOptions += listOf("--track-name", "$id:${t.title}")
                sourceOptions += listOf("--default-track", "$id:${if (t.isDefault) "yes" else "no"}")
                sourceOptions += listOf("--forced-track", "$id:${if (t.isForced) "yes" else "no"}")
                sourceOptions += listOf("--hearing-impaired-flag", "$id:${if (t.isHearingImpaired) "yes" else "no"}")
                sourceOptions += listOf("--sync", "$id:${plan.delayMs}")
            }
        }
        sourceOptions += listOf("-d", video.videoStreamIndex.coerceAtLeast(0).toString())
        sourceOptions += if (sourceAudioIds.isEmpty()) listOf("-A") else listOf("-a", sourceAudioIds.joinToString(","))
        sourceOptions += if (sourceSubIds.isEmpty()) listOf("-S") else listOf("-s", sourceSubIds.joinToString(","))
        sourceOptions += video.cachePath

        args += sourceOptions

        var fileIndex = 1
        val trackOrder = mutableListOf("0:${video.videoStreamIndex.coerceAtLeast(0)}")

        audioTracks.forEachIndexed { i, t ->
            when (val plan = audioPlans[i]) {
                is TrackPlan.FromSource -> trackOrder += "0:${plan.trackId}"
                is TrackPlan.FromFile -> {
                    args += listOf("--language", "0:${t.language.ifBlank { "und" }}")
                    if (t.title.isNotBlank()) args += listOf("--track-name", "0:${t.title}")
                    args += listOf("--default-track", "0:${if (t.isDefault) "yes" else "no"}")
                    args += listOf("--sync", "0:${plan.delayMs}")
                    args += plan.path
                    trackOrder += "$fileIndex:0"
                    fileIndex++
                }
                is TrackPlan.Failed -> {}
            }
        }
        subTracks.forEachIndexed { i, t ->
            when (val plan = subPlans[i]) {
                is TrackPlan.FromSource -> trackOrder += "0:${plan.trackId}"
                is TrackPlan.FromFile -> {
                    args += listOf("--language", "0:${t.language.ifBlank { "und" }}")
                    if (t.title.isNotBlank()) args += listOf("--track-name", "0:${t.title}")
                    args += listOf("--default-track", "0:${if (t.isDefault) "yes" else "no"}")
                    args += listOf("--forced-track", "0:${if (t.isForced) "yes" else "no"}")
                    args += listOf("--hearing-impaired-flag", "0:${if (t.isHearingImpaired) "yes" else "no"}")
                    args += listOf("--sync", "0:${plan.delayMs}")
                    args += plan.path
                    trackOrder += "$fileIndex:0"
                    fileIndex++
                }
                is TrackPlan.Failed -> {}
            }
        }

        args += listOf("--track-order", trackOrder.joinToString(","))
        return args
    }

    private val availableEncoders: Set<String> by lazy { detectAvailableEncoders() }

    private fun detectAvailableEncoders(): Set<String> {
        return try {
            val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", "-encoders"))
            val text = session.output ?: return emptySet()
            text.lineSequence()
                .mapNotNull { line -> Regex("^\\s*[VASDT.]{6}\\s+(\\S+)").find(line)?.groupValues?.get(1) }
                .toSet()
        } catch (_: Exception) { emptySet() }
    }

    private fun gainEncoderFor(sourceCodec: String): Pair<String, String> {
        val preferred = when (sourceCodec.lowercase()) {
            "opus" -> "libopus" to "160k"
            "vorbis" -> "libvorbis" to "160k"
            "mp3" -> "libmp3lame" to "192k"
            "ac3" -> "ac3" to "192k"
            "eac3" -> "eac3" to "192k"
            "flac", "pcm_s16le", "pcm_s16be", "pcm_s24le", "pcm_s24be", "pcm_s32le", "pcm_u8" -> "flac" to ""
            else -> "aac" to "192k"
        }
        val (encoder, _) = preferred
        return if (encoder == "aac" || encoder in availableEncoders) preferred else "aac" to "192k"
    }

    private fun gainContainerExtFor(encoder: String): String = when (encoder) {
        "libopus" -> "opus"
        "libvorbis" -> "ogg"
        "libmp3lame" -> "mp3"
        "ac3" -> "ac3"
        "eac3" -> "eac3"
        "flac" -> "flac"
        else -> "m4a"
    }

    /** Ses yükseltme (volume boost) + compressor/limiter'ı ffmpeg ile uygular, mkvmerge'e verilecek yeni dosyayı döner. */
    private fun applyGainFilter(inputPath: String, mapSelector: String, codecHint: String, gainDb: Float, wd: File): File? {
        return try {
            val (encoder, bitrate) = gainEncoderFor(codecHint)
            val ext = gainContainerExtFor(encoder)
            val out = File(wd, "gain_${System.currentTimeMillis()}_${(1000..9999).random()}.$ext")
            val gainStr = "%.1f".format(java.util.Locale.US, gainDb)
            val filter = "volume=${gainStr}dB,acompressor=threshold=-6dB:ratio=4:attack=5:release=80:makeup=1,alimiter=limit=0.999:attack=1:release=50:level=0"
            val args = mutableListOf("-y", "-i", inputPath, "-map", mapSelector, "-vn", "-filter:a", filter, "-c:a", encoder)
            if (bitrate.isNotBlank()) args += listOf("-b:a", bitrate)
            args += out.absolutePath
            val s = FFmpegKit.executeWithArguments(args.toTypedArray())
            if (ReturnCode.isSuccess(s.returnCode) && out.exists() && out.length() > 0L) out else null
        } catch (_: Exception) { null }
    }

    private fun copyUriToCache(uri: Uri, fileName: String): String? {
        return try {
            val f = File(workDir(), fileName)
            val input = app.contentResolver.openInputStream(uri) ?: return null
            input.use { i -> f.outputStream().use { o -> i.copyTo(o, 8*1024*1024) } }
            if (f.exists() && f.length() > 0) f.absolutePath else null
        } catch (_: Exception) { null }
    }

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
