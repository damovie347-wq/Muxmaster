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
 * başlangıç noktası videonun bir keyframe'ine hizalanmak zorundadır. Bu hizalama,
 * istenen noktadan SONRAKİ ilk keyframe'e (ffprobe ile bulunarak) yapılır, böylece
 * kesim hiçbir zaman kullanıcının istediğinden daha erken başlamaz (bkz. startTrim
 * ve TrackProber.findForwardKeyframeSec).
 */
/** Klasörden seçilen kuyruktaki tek bir video/ses dosyası. */
data class TrimQueueEntry(val uri: Uri, val name: String)

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

    // Klasör kuyruğu ("Klasör Seç" -> sonraki videoya geç)
    var folderQueue by mutableStateOf<List<TrimQueueEntry>>(emptyList())
        private set
    var queueIndex by mutableStateOf(-1)
        private set
    val hasNextInQueue: Boolean get() = queueIndex >= 0 && queueIndex < folderQueue.size - 1
    val hasPrevInQueue: Boolean get() = queueIndex > 0 && folderQueue.isNotEmpty()

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

    /** Klasör seçtirir: içindeki tüm video/ses dosyalarını kuyruğa alır, ilkini açar. */
    fun onFolderSelected(treeUri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { }
        viewModelScope.launch {
            isLoading = true
            loadingMessage = "Klasör taranıyor..."
            val items = withContext(Dispatchers.IO) {
                runCatching {
                    DocumentFile.fromTreeUri(app, treeUri)?.listFiles()
                        ?.filter { it.isFile && isMediaDoc(it) }
                        ?.sortedBy { (it.name ?: "").lowercase() }
                        ?.map { TrimQueueEntry(it.uri, it.name ?: "video.mkv") }
                }.getOrNull() ?: emptyList()
            }
            isLoading = false
            if (items.isEmpty()) {
                resultMessage = "Klasörde video veya ses dosyası bulunamadı."
                isSuccess = false
                return@launch
            }
            folderQueue = items
            queueIndex = 0
            onVideoSelected(items[0].uri, items[0].name, fromQueue = true)
        }
    }

    /** Kuyruktaki sonraki dosyaya, hiçbir şey yapmadan sıfırdan geçer. */
    fun nextInQueue() {
        if (isTrimming || isLoading || !hasNextInQueue) return
        queueIndex += 1
        val e = folderQueue[queueIndex]
        onVideoSelected(e.uri, e.name, fromQueue = true)
    }

    fun prevInQueue() {
        if (isTrimming || isLoading || !hasPrevInQueue) return
        queueIndex -= 1
        val e = folderQueue[queueIndex]
        onVideoSelected(e.uri, e.name, fromQueue = true)
    }

    private fun isMediaDoc(d: DocumentFile): Boolean {
        val t = d.type.orEmpty()
        if (t.startsWith("video/") || t.startsWith("audio/")) return true
        return extensionFromName(d.name.orEmpty()) in MEDIA_EXTS
    }

    fun onVideoSelected(uri: Uri, displayName: String, fromQueue: Boolean = false) {
        if (!fromQueue) { folderQueue = emptyList(); queueIndex = -1 }
        viewModelScope.launch {
            isLoading = true
            loadingMessage = "Dosya analiz ediliyor..."
            resultMessage = null; isSuccess = false
            val old = videoFile?.cachePath
            videoFile = null // eski dosyanın oynatıcısı kapansın (cache silinecek)
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

            val audioOnly = !probe.hasVideo
            if (audioOnly && probe.audioStreams.isEmpty()) {
                withContext(Dispatchers.IO) { runCatching { File(cacheFile).delete() } }
                resultMessage = "Geçerli bir video/ses dosyası değil."
                isSuccess = false
                isLoading = false
                return@launch
            }
            videoFile = VideoFile(
                uri = uri, displayName = displayName, cachePath = cacheFile,
                videoCodec = if (audioOnly) (probe.audioStreams.firstOrNull()?.codec?.uppercase() ?: "AUDIO") else probe.videoCodec,
                resolution = if (audioOnly) "Ses" else probe.resolution,
                durationMs = probe.durationMs, fileSizeMb = sizeMb,
                videoStreamIndex = probe.videoStreamIndex,
                isAudioOnly = audioOnly
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
                val endSec = trimEndMs / 1000.0

                // "-c copy" (kalite kaybı olmadan) kesimde başlangıç noktası MUTLAKA bir
                // video keyframe'ine denk gelmek zorundadır; aksi halde çıktı decode
                // edilemez. Müdahale etmezsek ffmpeg kendi "-ss" (girdi seeği) davranışıyla
                // istenen noktadan ÖNCEKİ en yakın keyframe'e atlar. Keyframe aralığı (GOP)
                // geniş olan bazı videolarda bu, kullanıcının istediği saniyeden 20-30
                // saniye hatta daha fazla ERKEN başlayan yanlış kesimlere yol açıyordu.
                // Bunun yerine, istenen noktadan İTİBAREN SONRAKİ ilk keyframe'i ffprobe ile
                // bulup kesimi tam olarak oradan başlatıyoruz; böylece kesim hiçbir zaman
                // istenenden daha erken başlamaz (en kötüsü, genelde çok kısa süren bir
                // sonraki keyframe'e kadar birazcık geç başlar - bu çok daha az sorunludur).
                // Sadece-ses dosyalarında keyframe kavramı yok (her ses karesi kesilebilir).
                val snappedStartSec = if (video.isAudioOnly) startSec else {
                    withContext(Dispatchers.IO) {
                        TrackProber.findForwardKeyframeSec(video.cachePath, video.videoStreamIndex, startSec, endSec)
                    } ?: startSec
                }

                val durationSec = (endSec - snappedStartSec).coerceAtLeast(0.001)
                val args = arrayOf(
                    "-y",
                    "-ss", String.format(Locale.US, "%.3f", snappedStartSec),
                    "-i", video.cachePath,
                    "-t", String.format(Locale.US, "%.3f", durationSec),
                    "-map", if (video.isAudioOnly) "0:a" else "0",
                    "-c", "copy",
                    "-avoid_negative_ts", "make_zero",
                    tempOutput.absolutePath
                )

                val rc = runFfmpegAsync(args, (durationSec * 1000).toLong()) { pct ->
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
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "opus", "ogg", "oga" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/x-wav"
        "mka" -> "audio/x-matroska"
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
        private val MEDIA_EXTS = setOf(
            "mkv", "mp4", "m4v", "mov", "avi", "webm", "ts", "m2ts", "mts", "flv", "wmv", "3gp", "mpg", "mpeg",
            "mp3", "m4a", "aac", "opus", "ogg", "oga", "flac", "wav", "wma", "mka", "ac3", "eac3", "dts"
        )
    }
}
