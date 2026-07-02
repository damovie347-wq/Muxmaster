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
import com.example.muxmaster.data.TrackProber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

data class ConvertSourceAudio(
    val uri: Uri,
    val displayName: String,
    val cachePath: String,
    val sourceCodec: String,
    val sourceChannels: Int,
    val sourceBitrateLabel: String,
    val durationMs: Long,
    val fileSizeMb: Float
)

class ConverterViewModel(private val app: Application) : AndroidViewModel(app) {

    var sourceAudio by mutableStateOf<ConvertSourceAudio?>(null)
        private set
    var bitrateKbpsText by mutableStateOf("128")
        private set
    var outputFolderUri by mutableStateOf<Uri?>(null)
        private set
    var outputFileName by mutableStateOf("converted_audio.opus")
        private set
    var isLoading by mutableStateOf(false)
        private set
    var loadingMessage by mutableStateOf("")
        private set
    var isConverting by mutableStateOf(false)
        private set
    var convertProgress by mutableStateOf(0)
        private set
    var resultMessage by mutableStateOf<String?>(null)
        private set
    var isSuccess by mutableStateOf(false)
        private set

    private var convertJob: Job? = null

    fun cancelConvert() { convertJob?.cancel() }

    fun onAudioSelected(uri: Uri, displayName: String) {
        viewModelScope.launch {
            isLoading = true
            loadingMessage = "Ses dosyası kopyalanıyor..."
            resultMessage = null
            isSuccess = false

            withContext(Dispatchers.IO) {
                runCatching { File(app.cacheDir, "convert_work").deleteRecursively() }
            }

            val ext = extensionFromName(displayName).ifBlank { "bin" }
            val cachePath = withContext(Dispatchers.IO) {
                copyUriToCache(uri, "src_audio_${System.currentTimeMillis()}.$ext")
            }
            if (cachePath == null) {
                resultMessage = "Dosya okunamadı / kopyalanamadı."
                isLoading = false
                return@launch
            }

            val sizeMb = withContext(Dispatchers.IO) { File(cachePath).length().toFloat() / (1024 * 1024) }

            loadingMessage = "Ses bilgisi okunuyor (ffprobe)..."
            val probe = withContext(Dispatchers.IO) { TrackProber.probe(cachePath) }
            val firstAudio = probe.audioStreams.firstOrNull()

            if (firstAudio == null) {
                resultMessage = "Bu dosyada ses stream'i bulunamadı."
                isLoading = false
                withContext(Dispatchers.IO) { runCatching { File(cachePath).delete() } }
                return@launch
            }

            sourceAudio = ConvertSourceAudio(
                uri = uri, displayName = displayName, cachePath = cachePath,
                sourceCodec = firstAudio.codec.uppercase(), sourceChannels = firstAudio.channels,
                sourceBitrateLabel = firstAudio.bitrate, durationMs = probe.durationMs, fileSizeMb = sizeMb
            )

            outputFileName = displayName.substringBeforeLast('.', displayName) + "_opus.opus"
            isLoading = false
            loadingMessage = ""
        }
    }

    fun clearAudio() {
        if (isConverting) return
        val old = sourceAudio?.cachePath
        sourceAudio = null; resultMessage = null; isSuccess = false; convertProgress = 0
        viewModelScope.launch(Dispatchers.IO) {
            if (old != null) runCatching { File(old).delete() }
            runCatching { File(app.cacheDir, "convert_work").deleteRecursively() }
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

    fun updateOutputFileName(name: String) { outputFileName = name }
    fun clearResult() { resultMessage = null; isSuccess = false }

    fun dismissAndReset() {
        if (isConverting) return
        val old = sourceAudio?.cachePath
        sourceAudio = null; resultMessage = null; isSuccess = false; convertProgress = 0
        viewModelScope.launch(Dispatchers.IO) {
            if (old != null) runCatching { File(old).delete() }
            runCatching { File(app.cacheDir, "convert_work").deleteRecursively() }
        }
    }

    fun startConvert() {
        val source = sourceAudio
        val outFolder = outputFolderUri
        val bitrate = bitrateKbpsText.toIntOrNull()

        if (source == null) { resultMessage = "Önce bir ses dosyası seçin."; isSuccess = false; return }
        if (outFolder == null) { resultMessage = "Çıktı klasörü seçilmedi."; isSuccess = false; return }
        if (bitrate == null || bitrate < 6 || bitrate > 512) {
            resultMessage = "Geçerli bir bitrate girin (6-512 kbps arası)."; isSuccess = false; return
        }
        if (isConverting) return

        convertJob = viewModelScope.launch {
            try {
                isConverting = true; convertProgress = 0; resultMessage = null; isSuccess = false

                val workDir = File(app.cacheDir, "convert_work").also { it.mkdirs() }
                val tempOutput = File(workDir, "temp_out_${System.currentTimeMillis()}.opus")
                tempOutput.delete()

                convertProgress = 10

                val forceMono = bitrate < 48
                val args = buildList {
                    add("-y"); add("-i"); add(source.cachePath)
                    add("-vn"); add("-map"); add("0:a:0")
                    add("-ar"); add("48000")
                    if (forceMono) { add("-ac"); add("1") }
                    add("-af"); add("afade=t=in:st=0:d=0.008")
                    add("-c:a"); add("libopus")
                    add("-b:a"); add("${bitrate}k")
                    add(tempOutput.absolutePath)
                }.toTypedArray()

                val returnCodeVal = runFfmpegAsync(args, source.durationMs)
                convertProgress = 92

                if (returnCodeVal == null || returnCodeVal != 0) {
                    resultMessage = "FFmpeg hatası (kod: $returnCodeVal). Kaynak dosya bozuk olabilir."
                    return@launch
                }
                if (!tempOutput.exists() || tempOutput.length() == 0L) {
                    resultMessage = "Hata: Çıktı dosyası oluşmadı (0 byte)."
                    return@launch
                }

                convertProgress = 95
                val rawName = outputFileName.trim().ifBlank { "converted_audio.opus" }
                val finalName = if (rawName.endsWith(".opus", ignoreCase = true)) rawName else "$rawName.opus"
                val finalSizeBytes = tempOutput.length()

                val copyOk = withContext(Dispatchers.IO) {
                    try {
                        val outDoc = DocumentFile.fromTreeUri(app, outFolder)?.createFile("audio/ogg", finalName)
                        val outUri = outDoc?.uri ?: return@withContext false
                        app.contentResolver.openOutputStream(outUri)?.use { out ->
                            tempOutput.inputStream().use { input -> input.copyTo(out, 8 * 1024 * 1024) }
                        } ?: return@withContext false
                        triggerMediaScan(outUri)
                        true
                    } catch (e: Exception) { false }
                }

                runCatching { tempOutput.delete() }
                convertProgress = 100

                if (copyOk) {
                    val sizeMb = finalSizeBytes / (1024f * 1024f)
                    resultMessage = "✅ Tamamlandı! %.1f MB (Opus, $bitrate kbps)".format(sizeMb)
                    isSuccess = true
                } else {
                    resultMessage = "Hata: Dosya hedef klasöre kaydedilemedi."
                    isSuccess = false
                }
            } catch (c: CancellationException) {
                resultMessage = "İşlem iptal edildi."; isSuccess = false; throw c
            } finally {
                isConverting = false
            }
        }
    }

    private suspend fun runFfmpegAsync(args: Array<String>, durationMs: Long): Int? =
        suspendCancellableCoroutine { cont ->
            val session = FFmpegKit.executeWithArgumentsAsync(
                args,
                { completedSession -> val rc = completedSession.returnCode?.value; if (cont.isActive) cont.resume(rc) },
                null
            ) { stats ->
                if (durationMs > 0) {
                    val pct = (((stats.time.toFloat() / durationMs) * 80f) + 10f).toInt().coerceIn(10, 90)
                    viewModelScope.launch { convertProgress = pct }
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

    private fun triggerMediaScan(docUri: Uri) {
        try {
            val docId = android.provider.DocumentsContract.getDocumentId(docUri)
            val parts = docId.split(":", limit = 2)
            if (parts.size == 2 && parts[0].equals("primary", ignoreCase = true)) {
                val realPath = "${android.os.Environment.getExternalStorageDirectory().absolutePath}/${parts[1]}"
                if (File(realPath).exists()) {
                    android.media.MediaScannerConnection.scanFile(app, arrayOf(realPath), arrayOf("audio/ogg"), null)
                }
            }
        } catch (_: Exception) { }
    }
}
