package com.example.muxmaster.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.example.muxmaster.data.TrackProber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** Dönüştürme hedefi format. */
enum class OutputFormat(val extension: String, val mimeType: String, val label: String) {
    OPUS("opus", "audio/ogg", "Opus"),
    MP3("mp3", "audio/mpeg", "MP3")
}

/** Kuyruktaki tek bir dosyanın işlem durumu. */
enum class ConvertStatus { PENDING, CONVERTING, DONE, ERROR }

/**
 * Toplu dönüştürme kuyruğundaki tek bir öğe.
 * @Immutable: Uri alanı içerdiği için (bkz. Models.kt'deki aynı gerekçe) Compose'a bu
 * sınıfın değişmez olduğunu garanti ediyoruz - kuyruk listesinde değişmeyen satırlar
 * gereksiz yere yeniden çizilmesin diye.
 */
@Immutable
data class ConvertQueueItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val cachePath: String = "",
    val sourceCodec: String = "",
    val sourceChannels: Int = 2,
    val sourceBitrateLabel: String = "",
    val durationMs: Long = 0L,
    val fileSizeMb: Float = 0f,
    val status: ConvertStatus = ConvertStatus.PENDING,
    val progress: Int = 0,
    val outputSizeMb: Float? = null,
    val errorMessage: String? = null
)

class ConverterViewModel(private val app: Application) : AndroidViewModel(app) {

    var queue by mutableStateOf<List<ConvertQueueItem>>(emptyList())
        private set
    var outputFormat by mutableStateOf(OutputFormat.OPUS)
        private set
    var bitrateKbpsText by mutableStateOf("128")
        private set
    var outputFolderUri by mutableStateOf<Uri?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var loadingMessage by mutableStateOf("")
        private set
    var isConverting by mutableStateOf(false)
        private set
    var convertProgress by mutableStateOf(0)
        private set
    var currentFileName by mutableStateOf("")
        private set
    var resultMessage by mutableStateOf<String?>(null)
        private set
    var isSuccess by mutableStateOf(false)
        private set

    private var convertJob: Job? = null
    private var nextId = 0L

    fun cancelConvert() { convertJob?.cancel() }

    fun selectFormat(format: OutputFormat) {
        if (!isConverting) outputFormat = format
    }

    /** Kullanıcı bir veya birden fazla dosya seçtiğinde çağrılır; her biri SIRAYLA kopyalanıp analiz edilir. */
    fun onAudioFilesSelected(files: List<Pair<Uri, String>>) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            isLoading = true
            resultMessage = null
            isSuccess = false

            var added = 0
            files.forEachIndexed { index, pair ->
                val uri = pair.first
                val displayName = pair.second
                loadingMessage = "Dosya analiz ediliyor (${index + 1}/${files.size}): $displayName"

                val ext = extensionFromName(displayName).ifBlank { "bin" }
                val id = nextId++
                val cachePath = withContext(Dispatchers.IO) {
                    copyUriToCache(uri, "src_${id}_${System.currentTimeMillis()}.$ext")
                }
                if (cachePath != null) {
                    val sizeMb = withContext(Dispatchers.IO) { File(cachePath).length().toFloat() / (1024 * 1024) }
                    val probe = withContext(Dispatchers.IO) { TrackProber.probe(cachePath) }
                    val firstAudio = probe.audioStreams.firstOrNull()
                    if (firstAudio != null) {
                        queue = queue + ConvertQueueItem(
                            id = id, uri = uri, displayName = displayName, cachePath = cachePath,
                            sourceCodec = firstAudio.codec.uppercase(), sourceChannels = firstAudio.channels,
                            sourceBitrateLabel = firstAudio.bitrate, durationMs = probe.durationMs, fileSizeMb = sizeMb
                        )
                        added++
                    } else {
                        withContext(Dispatchers.IO) { runCatching { File(cachePath).delete() } }
                    }
                }
            }

            isLoading = false
            loadingMessage = ""
            if (added == 0) {
                resultMessage = "Seçilen dosya(lar) okunamadı / ses stream'i bulunamadı."
                isSuccess = false
            }
        }
    }

    fun removeFromQueue(id: Long) {
        if (isConverting) return
        val item = queue.firstOrNull { it.id == id }
        queue = queue.filterNot { it.id == id }
        if (item != null) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { File(item.cachePath).delete() } }
        }
    }

    fun clearQueue() {
        if (isConverting) return
        val old = queue
        queue = emptyList()
        resultMessage = null; isSuccess = false; convertProgress = 0
        viewModelScope.launch(Dispatchers.IO) {
            old.forEach { runCatching { File(it.cachePath).delete() } }
        }
    }

    fun updateBitrateText(value: String) { bitrateKbpsText = value.filter { it.isDigit() }.take(4) }

    fun setOutputFolder(uri: Uri) {
        outputFolderUri = uri
        try {
            app.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) { }
    }

    fun clearResult() { resultMessage = null; isSuccess = false }

    fun dismissAndReset() {
        if (isConverting) return
        val old = queue
        queue = emptyList()
        resultMessage = null; isSuccess = false; convertProgress = 0
        viewModelScope.launch(Dispatchers.IO) {
            old.forEach { runCatching { File(it.cachePath).delete() } }
        }
    }

    /** Kuyruktaki tüm PENDING/ERROR öğeleri SIRAYLA (biri bitmeden diğeri başlamaz) dönüştürür. */
    fun startConvert() {
        val outFolder = outputFolderUri
        val bitrate = bitrateKbpsText.toIntOrNull()
        val toProcess = queue.filter { it.status == ConvertStatus.PENDING || it.status == ConvertStatus.ERROR }

        if (toProcess.isEmpty()) { resultMessage = "Kuyrukta dönüştürülecek dosya yok."; isSuccess = false; return }
        if (outFolder == null) { resultMessage = "Çıktı klasörü seçilmedi."; isSuccess = false; return }
        if (bitrate == null || bitrate < 6 || bitrate > 512) {
            resultMessage = "Geçerli bir bitrate girin (6-512 kbps arası)."; isSuccess = false; return
        }
        if (isConverting) return

        convertJob = viewModelScope.launch {
            try {
                isConverting = true; resultMessage = null; isSuccess = false; convertProgress = 0

                var doneCount = 0
                var errorCount = 0
                val total = toProcess.size
                val format = outputFormat

                for (item in toProcess) {
                    currentFileName = item.displayName
                    updateQueueItem(item.id) { it.copy(status = ConvertStatus.CONVERTING, progress = 0) }

                    val workDir = File(app.cacheDir, "convert_work").also { it.mkdirs() }
                    val tempOutput = File(workDir, "out_${item.id}_${System.currentTimeMillis()}.${format.extension}")
                    tempOutput.delete()

                    val args = buildFfmpegArgs(item, format, bitrate, tempOutput.absolutePath)
                    val doneSoFar = doneCount + errorCount

                    val returnCodeVal = runFfmpegAsync(args, item.durationMs) { pct ->
                        updateQueueItem(item.id) { it.copy(progress = pct) }
                        convertProgress = ((((doneSoFar).toFloat() + pct / 100f) / total) * 100).toInt().coerceIn(0, 99)
                    }

                    val ok = returnCodeVal == 0 && tempOutput.exists() && tempOutput.length() > 0L
                    if (!ok) {
                        errorCount++
                        updateQueueItem(item.id) { it.copy(status = ConvertStatus.ERROR, errorMessage = "FFmpeg hatası (kod: $returnCodeVal)") }
                        runCatching { tempOutput.delete() }
                    } else {
                        val rawName = item.displayName.substringBeforeLast('.', item.displayName)
                        val finalName = "$rawName.${format.extension}"
                        val finalSizeBytes = tempOutput.length()

                        val copyOk = withContext(Dispatchers.IO) {
                            try {
                                val outDoc = DocumentFile.fromTreeUri(app, outFolder)?.createFile(format.mimeType, finalName)
                                val outUri = outDoc?.uri ?: return@withContext false
                                app.contentResolver.openOutputStream(outUri)?.use { out ->
                                    tempOutput.inputStream().use { input -> input.copyTo(out, 8 * 1024 * 1024) }
                                } ?: return@withContext false
                                triggerMediaScan(outUri, format.mimeType)
                                true
                            } catch (e: Exception) { false }
                        }
                        runCatching { tempOutput.delete() }

                        if (copyOk) {
                            doneCount++
                            updateQueueItem(item.id) {
                                it.copy(status = ConvertStatus.DONE, progress = 100, outputSizeMb = finalSizeBytes / (1024f * 1024f))
                            }
                        } else {
                            errorCount++
                            updateQueueItem(item.id) { it.copy(status = ConvertStatus.ERROR, errorMessage = "Hedef klasöre kaydedilemedi.") }
                        }
                    }

                    convertProgress = (((doneCount + errorCount).toFloat() / total) * 100).toInt().coerceIn(0, 100)
                    runCatching { File(item.cachePath).delete() }
                }

                convertProgress = 100
                currentFileName = ""
                resultMessage = if (errorCount == 0) {
                    "✅ Tamamlandı! $doneCount/$total dosya ${format.label} formatına dönüştürüldü."
                } else {
                    "⚠ $doneCount/$total tamamlandı, $errorCount dosyada hata oluştu."
                }
                isSuccess = errorCount == 0
            } catch (c: CancellationException) {
                resultMessage = "İşlem iptal edildi."; isSuccess = false; currentFileName = ""
                throw c
            } finally {
                isConverting = false
            }
        }
    }

    private fun updateQueueItem(id: Long, transform: (ConvertQueueItem) -> ConvertQueueItem) {
        queue = queue.map { if (it.id == id) transform(it) else it }
    }

    private fun buildFfmpegArgs(item: ConvertQueueItem, format: OutputFormat, bitrate: Int, outputPath: String): Array<String> {
        val forceMono = bitrate < 48
        val audioFilters = if (forceMono) {
            "adelay=50:all=1,highpass=f=20,afade=t=in:st=0.05:d=0.12:curve=log,alimiter=limit=0.95:attack=5:release=50"
        } else {
            "highpass=f=20,afade=t=in:st=0:d=0.05:curve=log,alimiter=limit=0.95:attack=5:release=50"
        }
        return buildList {
            add("-y"); add("-i"); add(item.cachePath)
            add("-vn"); add("-map"); add("0:a:0")
            add("-ar"); add("48000")
            if (forceMono) { add("-ac"); add("1") }
            add("-af"); add(audioFilters)
            when (format) {
                OutputFormat.OPUS -> {
                    add("-c:a"); add("libopus")
                    add("-application"); add(if (forceMono) "voip" else "audio")
                }
                OutputFormat.MP3 -> {
                    add("-c:a"); add("libmp3lame")
                }
            }
            add("-b:a"); add("${bitrate}k")
            add(outputPath)
        }.toTypedArray()
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
            val file = File(app.cacheDir, "convert_work/$fileName")
            file.parentFile?.mkdirs()
            app.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output, bufferSize = 8 * 1024 * 1024) }
            } ?: return null
            if (file.exists() && file.length() > 0) file.absolutePath else null
        } catch (e: Exception) { null }
    }

    private fun extensionFromName(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        if (dot < 0 || dot == displayName.length - 1) return ""
        return displayName.substring(dot + 1).lowercase().filter { it.isLetterOrDigit() }
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
}
