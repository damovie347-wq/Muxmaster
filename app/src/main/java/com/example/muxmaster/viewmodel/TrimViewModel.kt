package com.example.muxmaster.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.example.muxmaster.data.AppPreferences
import com.example.muxmaster.data.TrackProber
import com.example.muxmaster.model.VideoFile
import com.example.muxmaster.service.MuxCancelBus
import com.example.muxmaster.service.MuxForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Video Kırpma (Trim) özelliği için ViewModel.
 *
 * Kırpma tamamen "stream copy" (-c copy) ile yapılır: video/ses/altyazı yeniden
 * encode edilmez, bu yüzden kalite kaybı OLMAZ ve işlem çok hızlıdır. Tek
 * teknik sınırlama (ffmpeg'in doğası gereği): yeniden encode yapılmadığı için
 * başlangıç noktası videonun en yakın keyframe'ine hizalanabilir.
 */
class TrimViewModel(private val app: Application) : AndroidViewModel(app) {

    private val prefs = AppPreferences(app)

    var videoFile by mutableStateOf<VideoFile?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var loadingMessage by mutableStateOf("")
        private set

    // Kırpma aralığı (videonun kendi zaman çizelgesinde, milisaniye cinsinden)
    var trimStartMs by mutableStateOf(0L)
        private set
    var trimEndMs by mutableStateOf(0L)
        private set

    var outputFolderUri by mutableStateOf<Uri?>(null)
        private set
    var outputFileName by mutableStateOf("output_trim.mkv")
        private set

    var isTrimming by mutableStateOf(false)
        private set
    var trimProgress by mutableStateOf(0)
        private set
    var resultMessage by mutableStateOf<String?>(null)
        private set
    var isSuccess by mutableStateOf(false)
        private set

    private var trimJob: Job? = null

    private fun workDir(): File = File(app.cacheDir, "trim_work").also { it.mkdirs() }

    init {
        prefs.defaultOutputFolder?.let { outputFolderUri = it }
    }

    fun cancelTrim() { trimJob?.cancel() }
    fun clearResult() { resultMessage = null; isSuccess = false }

    fun dismissAndReset() {
        resultMessage = null; isSuccess = false; trimProgress = 0
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                workDir().listFiles()
                    ?.filter { !it.name.startsWith("input_video") }
                    ?.forEach { it.delete() }
            }
        }
    }

    fun clearVideo() {
        if (isTrimming) return
        val old = videoFile?.cachePath
        videoFile = null
        trimStartMs = 0L; trimEndMs = 0L
        resultMessage = null; isSuccess = false; trimProgress = 0
        viewModelScope.launch(Dispatchers.IO) {
            if (old != null) runCatching { File(old).delete() }
            runCatching { workDir().deleteRecursively() }
        }
    }

    fun onVideoSelected(uri: Uri, displayName: String) {
        viewModelScope.launch {
            isLoading = true
            loadingMessage = "Video analiz ediliyor..."
            resultMessage = null; isSuccess = false

            val old = videoFile?.cachePath
            withContext(Dispatchers.IO) {
                if (old != null) runCatching { File(old).delete() }
                runCatching {
                    workDir().listFiles()
                        ?.filter { !it.name.startsWith("input_video") }?.forEach { it.delete() }
                }
            }

            val ext = extensionFromName(displayName).ifBlank { "mkv" }
            val cacheFile = withContext(Dispatchers.IO) {
                copyUriToCache(uri, "input_video_${System.currentTimeMillis()}.$ext")
            }
            if (cacheFile == null) {
                resultMessage = "Video okunamadı."
                isLoading = false
                return@launch
            }

            val sizeMb = withContext(Dispatchers.IO) { File(cacheFile).length().toFloat() / (1024 * 1024) }
            val probe = withContext(Dispatchers.IO) { TrackProber.probe(cacheFile) }

            videoFile = VideoFile(
                uri = uri, displayName = displayName, cachePath = cacheFile,
                videoCodec = probe.videoCodec, resolution = probe.resolution,
                durationMs = probe.durationMs, fileSizeMb = sizeMb,
                videoStreamIndex = probe.videoStreamIndex
            )
            trimStartMs = 0L
            trimEndMs = probe.durationMs

            val rawName = displayName.substringBeforeLast('.', displayName)
            outputFileName = "${rawName}_kirpilmis.$ext"

            isLoading = false
        }
    }

    /** Slider veya manuel giriş her değiştiğinde çağrılır; sınırları ve minimum aralığı garanti eder. */
    fun setTrimRange(startMs: Long, endMs: Long) {
        val dur = videoFile?.durationMs ?: return
        var s = startMs.coerceIn(0L, dur)
        var e = endMs.coerceIn(0L, dur)
        if (e - s < MIN_GAP_MS) {
            if (s + MIN_GAP_MS <= dur) e = s + MIN_GAP_MS else s = (dur - MIN_GAP_MS).coerceAtLeast(0L)
        }
        trimStartMs = s
        trimEndMs = e
    }

    fun setTrimStart(ms: Long) = setTrimRange(ms, trimEndMs)
    fun setTrimEnd(ms: Long) = setTrimRange(trimStartMs, ms)

    fun setOutputFolder(uri: Uri) {
        outputFolderUri = uri
        try {
            app.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) { }
        prefs.defaultOutputFolder = uri
    }

    fun updateOutputFileName(name: String) { outputFileName = name }

    fun startTrim() {
        val video = videoFile
        val outFolder = outputFolderUri
        if (video == null) { resultMessage = "Önce bir video seç."; isSuccess = false; return }
        if (outFolder == null) { resultMessage = "Önce çıktı klasörü seç."; isSuccess = false; return }
        if (trimEndMs <= trimStartMs) { resultMessage = "Bitiş noktası başlangıçtan sonra olmalı."; isSuccess = false; return }
        if (isTrimming) return

        trimJob = viewModelScope.launch {
            MuxForegroundService.start(app)
            MuxCancelBus.onCancelRequested = { cancelTrim() }
            try {
                isTrimming = true; resultMessage = null; isSuccess = false; trimProgress = 0
                MuxForegroundService.update(app, 0, "Kırpılıyor…")

                val outExt = extensionFromName(outputFileName).ifBlank { extensionFromName(video.displayName).ifBlank { "mkv" } }
                val tempOutput = File(workDir(), "trim_out_${System.currentTimeMillis()}.$outExt")
                tempOutput.delete()

                val startSec = trimStartMs / 1000.0
                val durationSec = (trimEndMs - trimStartMs) / 1000.0
                val args = arrayOf(
                    "-y",
                    "-ss", String.format(Locale.US, "%.3f", startSec),
                    "-i", video.cachePath,
                    "-t", String.format(Locale.US, "%.3f", durationSec),
                    "-map", "0",
                    "-c", "copy",
                    "-avoid_negative_ts", "make_zero",
                    tempOutput.absolutePath
                )

                val rc = runFfmpegAsync(args, trimEndMs - trimStartMs) { pct ->
                    trimProgress = pct
                    MuxForegroundService.update(app, pct, "Kırpılıyor… %$pct")
                }

                val ok = rc == 0 && tempOutput.exists() && tempOutput.length() > 0L
                if (!ok) {
                    resultMessage = "Kırpma başarısız oldu (kod: $rc)."
                    isSuccess = false
                    runCatching { tempOutput.delete() }
                } else {
                    val finalSizeBytes = tempOutput.length()
                    val mime = mimeTypeFor(outputFileName)
                    val copyOk = withContext(Dispatchers.IO) {
                        try {
                            val outDoc = DocumentFile.fromTreeUri(app, outFolder)?.createFile(mime, outputFileName)
                            val outUri = outDoc?.uri ?: return@withContext false
                            app.contentResolver.openOutputStream(outUri)?.use { out ->
                                tempOutput.inputStream().use { input -> input.copyTo(out, 8 * 1024 * 1024) }
                            } ?: return@withContext false
                            triggerMediaScan(outUri, mime)
                            true
                        } catch (e: Exception) { false }
                    }
                    runCatching { tempOutput.delete() }

                    trimProgress = 100
                    resultMessage = if (copyOk) {
                        String.format(Locale.getDefault(), "Kırpma tamamlandı (%.1f MB).", finalSizeBytes / (1024f * 1024f))
                    } else {
                        "Dosya kaydedilemedi."
                    }
                    isSuccess = copyOk
                }
            } catch (c: CancellationException) {
                resultMessage = "İptal edildi."; isSuccess = false
                throw c
            } finally {
                isTrimming = false
                MuxCancelBus.onCancelRequested = null
                MuxForegroundService.stop(app, resultMessage, isSuccess)
            }
        }
    }

    private suspend fun runFfmpegAsync(args: Array<String>, durationMs: Long, onProgress: (Int) -> Unit): Int? =
        suspendCancellableCoroutine { cont ->
            val session = FFmpegKit.executeWithArgumentsAsync(
                args,
                { completedSession -> val rc = completedSession.returnCode?.value; if (cont.isActive) cont.resume(rc) },
                null
            ) { stats ->
                if (durationMs > 0) {
                    val pct = ((stats.time.toFloat() / durationMs) * 100f).toInt().coerceIn(0, 100)
                    viewModelScope.launch { onProgress(pct) }
                }
            }
            cont.invokeOnCancellation { runCatching { session.cancel() } }
        }

    private fun copyUriToCache(uri: Uri, fileName: String): String? {
        return try {
            val f = File(workDir(), fileName)
            val input = app.contentResolver.openInputStream(uri) ?: return null
            input.use { i -> f.outputStream().use { o -> i.copyTo(o, 8 * 1024 * 1024) } }
            if (f.exists() && f.length() > 0) f.absolutePath else null
        } catch (_: Exception) { null }
    }

    private fun extensionFromName(n: String): String {
        val d = n.lastIndexOf('.')
        return if (d < 0 || d == n.length - 1) "" else n.substring(d + 1).lowercase().filter { it.isLetterOrDigit() }
    }

    private fun mimeTypeFor(fileName: String): String = when (extensionFromName(fileName)) {
        "mkv" -> "video/x-matroska"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "webm" -> "video/webm"
        else -> "video/*"
    }

    private fun triggerMediaScan(docUri: Uri, mimeType: String) {
        try {
            val docId = android.provider.DocumentsContract.getDocumentId(docUri)
            val parts = docId.split(":", limit = 2)
            if (parts.size == 2 && parts[0].equals("primary", ignoreCase = true)) {
                val realPath = "${android.os.Environment.getExternalStorageDirectory().absolutePath}/${parts[1]}"
                if (File(realPath).exists()) {
                    android.media.MediaScannerConnection.scanFile(app, arrayOf(realPath), arrayOf(mimeType), null)
                }
            }
        } catch (_: Exception) { }
    }

    companion object {
        private const val MIN_GAP_MS = 100L
    }
}
