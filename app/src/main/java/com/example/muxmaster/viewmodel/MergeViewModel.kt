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
import com.example.muxmaster.data.AppPreferences
import com.example.muxmaster.data.InputAccess
import com.example.muxmaster.data.TrackProber
import com.example.muxmaster.model.MergeItem
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
 * Video Birleştirme (Merge) özelliği için ViewModel.
 *
 * Birleştirme, ffmpeg'in "concat demuxer"ı ile tamamen "stream copy" (-c copy)
 * olarak yapılır: hiçbir video/ses yeniden encode edilmez, bu yüzden kalite
 * kaybı OLMAZ ve işlem sadece paketleri kopyaladığı için çok hızlıdır.
 *
 * Bunun doğal bir sınırlaması vardır: concat demuxer, birleştirilen dosyaların
 * aynı codec/çözünürlük/track yapısında olmasını gerektirir; aksi halde çıktı
 * bozuk olabilir ya da ffmpeg hata verebilir. Bu yüzden birleştirmeden önce
 * videolar arasında uyumluluk kontrolü yapılır (bkz. [checkCompatibility]) ve
 * uyumsuzluk varsa kullanıcıya net bir mesajla bildirilip işlem başlatılmaz -
 * kullanıcının "kalitede hiçbir değişiklik olmadan" isteğini bozacak sessiz bir
 * yeniden kodlamaya asla başvurulmaz.
 */
class MergeViewModel(private val app: Application) : AndroidViewModel(app) {

    private val prefs = AppPreferences(app)

    val items = mutableStateListOf<MergeItem>()

    var isLoading by mutableStateOf(false)
        private set
    var loadingMessage by mutableStateOf("")
        private set

    var outputFolderUri by mutableStateOf<Uri?>(null)
        private set
    var outputFileName by mutableStateOf("birlesmis_video.mkv")
        private set

    var isMerging by mutableStateOf(false)
        private set
    var mergeProgress by mutableStateOf(0)
        private set
    var resultMessage by mutableStateOf<String?>(null)
        private set
    var isSuccess by mutableStateOf(false)
        private set

    private var mergeJob: Job? = null
    private var nextId = 1

    private fun workDir(): File = File(app.cacheDir, "merge_work").also { it.mkdirs() }

    init {
        prefs.defaultOutputFolder?.let { outputFolderUri = it }
    }

    fun cancelMerge() { mergeJob?.cancel() }
    fun clearResult() { resultMessage = null; isSuccess = false }

    fun dismissAndReset() {
        resultMessage = null; isSuccess = false; mergeProgress = 0
    }

    fun clearAll() {
        if (isMerging) return
        val paths = items.map { it.cachePath }
        items.clear()
        resultMessage = null; isSuccess = false; mergeProgress = 0
        viewModelScope.launch(Dispatchers.IO) {
            paths.forEach { p -> if (p.isNotBlank()) runCatching { InputAccess.release(p) } }
        }
    }

    fun removeItem(id: Int) {
        if (isMerging) return
        val target = items.find { it.id == id } ?: return
        items.removeAll { it.id == id }
        viewModelScope.launch(Dispatchers.IO) {
            if (target.cachePath.isNotBlank()) runCatching { InputAccess.release(target.cachePath) }
        }
    }

    fun moveUp(id: Int) {
        if (isMerging) return
        val i = items.indexOfFirst { it.id == id }
        if (i > 0) { val t = items[i]; items[i] = items[i - 1]; items[i - 1] = t }
    }

    fun moveDown(id: Int) {
        if (isMerging) return
        val i = items.indexOfFirst { it.id == id }
        if (i in 0 until items.size - 1) { val t = items[i]; items[i] = items[i + 1]; items[i + 1] = t }
    }

    /** Kullanıcının çoklu seçim ile eklediği videoları analiz edip kuyruğun sonuna ekler. */
    fun onVideosSelected(files: List<Pair<Uri, String>>) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            isLoading = true
            resultMessage = null; isSuccess = false

            files.forEachIndexed { index, pair ->
                val uri = pair.first
                val displayName = pair.second
                loadingMessage = "${index + 1}/${files.size}: $displayName"

                val ext = extensionFromName(displayName).ifBlank { "mp4" }
                val id = nextId++
                val cacheFile = withContext(Dispatchers.IO) {
                    copyUriToCache(uri, "merge_input_${id}_${System.currentTimeMillis()}.$ext")
                }
                if (cacheFile != null) {
                    val sizeMb = withContext(Dispatchers.IO) { InputAccess.length(cacheFile).toFloat() / (1024 * 1024) }
                    val probe = withContext(Dispatchers.IO) { TrackProber.probe(cacheFile) }
                    val firstAudio = probe.audioStreams.firstOrNull()

                    items.add(
                        MergeItem(
                            id = id,
                            uri = uri,
                            displayName = displayName,
                            cachePath = cacheFile,
                            videoCodec = probe.videoCodec,
                            resolution = probe.resolution,
                            durationMs = probe.durationMs,
                            fileSizeMb = sizeMb,
                            hasAudio = firstAudio != null,
                            audioCodec = firstAudio?.codec ?: ""
                        )
                    )
                }
            }

            if (outputFileName == "birlesmis_video.mkv" && items.isNotEmpty()) {
                val firstExt = extensionFromName(items.first().displayName).ifBlank { "mkv" }
                outputFileName = "birlesmis_video.$firstExt"
            }

            isLoading = false
            loadingMessage = ""
        }
    }

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

    /**
     * Videoların kayıpsız (stream copy) birleştirilebilmesi için uyması gereken
     * uyumluluk kurallarını denetler. Herhangi bir uyumsuzluk bulunursa kullanıcıya
     * gösterilecek açıklayıcı mesajı döner; hepsi uyumluysa `null` döner.
     *
     * Not: Bu kontrol video/ses codec'i ve çözünürlüğü karşılaştırır; bunlar farklı
     * olduğunda concat demuxer ile "-c copy" çoğunlukla bozuk çıktı üretir ya da
     * ffmpeg hata verir. Kontrolün amacı, kullanıcının açıkça istediği "kalite
     * kaybı olmadan" birleştirmeyi bozacak bir denemeye asla girmemektir.
     */
    private fun checkCompatibility(): String? {
        if (items.size < 2) return "Birleştirmek için en az 2 video seçmelisin."
        val first = items.first()
        for (i in 1 until items.size) {
            val cur = items[i]
            if (!cur.videoCodec.equals(first.videoCodec, ignoreCase = true)) {
                return "\"${first.displayName}\" (${first.videoCodec}) ile \"${cur.displayName}\" (${cur.videoCodec}) aynı video codec'te değil. Kalite kaybı olmadan (yeniden kodlama yapmadan) birleştirme için tüm videoların codec'i aynı olmalı."
            }
            if (cur.resolution != first.resolution) {
                return "\"${first.displayName}\" (${first.resolution}) ile \"${cur.displayName}\" (${cur.resolution}) çözünürlükleri farklı. Kalite kaybı olmadan birleştirme için tüm videoların çözünürlüğü aynı olmalı."
            }
            if (cur.hasAudio != first.hasAudio) {
                return "\"${first.displayName}\" ile \"${cur.displayName}\" arasında ses track'i uyuşmazlığı var (biri sesli, diğeri sessiz). Bu videolar kayıpsız birleştirilemez."
            }
            if (cur.hasAudio && !cur.audioCodec.equals(first.audioCodec, ignoreCase = true)) {
                return "\"${first.displayName}\" (${first.audioCodec}) ile \"${cur.displayName}\" (${cur.audioCodec}) ses codec'leri farklı. Kalite kaybı olmadan birleştirme için ses codec'leri de aynı olmalı."
            }
        }
        return null
    }

    fun startMerge() {
        val outFolder = outputFolderUri
        if (outFolder == null) { resultMessage = "Önce çıktı klasörü seç."; isSuccess = false; return }
        val issue = checkCompatibility()
        if (issue != null) { resultMessage = issue; isSuccess = false; return }
        if (isMerging) return

        mergeJob = viewModelScope.launch {
            MuxForegroundService.start(app)
            MuxCancelBus.onCancelRequested = { cancelMerge() }
            try {
                isMerging = true; resultMessage = null; isSuccess = false; mergeProgress = 0
                MuxForegroundService.update(app, 0, "Birleştiriliyor…")

                val snapshot = items.toList()
                val totalDurationMs = snapshot.sumOf { it.durationMs }

                // ffmpeg concat demuxer, birleştirilecek dosyaların listesini bir metin
                // dosyasından okur: her satır "file '<mutlak yol>'" formatındadır.
                val listFile = File(workDir(), "concat_list_${System.currentTimeMillis()}.txt")
                withContext(Dispatchers.IO) {
                    listFile.bufferedWriter().use { w ->
                        for (item in snapshot) {
                            val escaped = item.cachePath.replace("'", "'\\''")
                            w.write("file '$escaped'")
                            w.newLine()
                        }
                    }
                }

                val outExt = extensionFromName(outputFileName).ifBlank {
                    extensionFromName(snapshot.first().displayName).ifBlank { "mkv" }
                }
                val tempOutput = File(workDir(), "merge_out_${System.currentTimeMillis()}.$outExt")
                tempOutput.delete()

                val args = arrayOf(
                    "-y",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", listFile.absolutePath,
                    "-map", "0",
                    "-c", "copy",
                    "-avoid_negative_ts", "make_zero",
                    tempOutput.absolutePath
                )

                val rc = runFfmpegAsync(args, totalDurationMs) { pct ->
                    mergeProgress = pct
                    MuxForegroundService.update(app, pct, "Birleştiriliyor… %$pct")
                }

                runCatching { listFile.delete() }

                val ok = rc == 0 && tempOutput.exists() && tempOutput.length() > 0L
                if (!ok) {
                    resultMessage = "Birleştirme başarısız oldu (kod: $rc)."
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

                    mergeProgress = 100
                    resultMessage = if (copyOk) {
                        String.format(Locale.getDefault(), "Birleştirme tamamlandı (%.1f MB, %d video).", finalSizeBytes / (1024f * 1024f), snapshot.size)
                    } else {
                        "Dosya kaydedilemedi."
                    }
                    isSuccess = copyOk
                }
            } catch (c: CancellationException) {
                resultMessage = "İptal edildi."; isSuccess = false
                throw c
            } finally {
                isMerging = false
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

    private fun copyUriToCache(uri: Uri, fileName: String): String? =
        InputAccess.acquire(app, uri, workDir(), fileName, needChildRead = false)

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
}
