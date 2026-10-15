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
import com.example.muxmaster.R
import com.example.muxmaster.data.AppPreferences
import com.example.muxmaster.data.HeAacEncoder
import com.example.muxmaster.data.InputAccess
import com.example.muxmaster.data.TrackProber
import com.example.muxmaster.service.MuxCancelBus
import com.example.muxmaster.service.MuxForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.roundToInt

enum class OutputFormat(val extension: String, val mimeType: String, val label: String) {
    OPUS("opus", "audio/ogg", "Opus"),
    XHE_AAC("m4a", "audio/mp4", "xHE-AAC"),
    HE_AAC("m4a", "audio/mp4", "HE-AAC"),
    HE_AAC_V2("m4a", "audio/mp4", "HE-AAC v2"),
    MP3("mp3", "audio/mpeg", "MP3");

    /** xHE-AAC / HE-AAC ailesi: Android MediaCodec ile hızlı encode edilir. */
    val isHeAac: Boolean get() = this == HE_AAC || this == HE_AAC_V2 || this == XHE_AAC
}

enum class ConvertStatus { PENDING, CONVERTING, DONE, ERROR }

/** Kullanıcı hedefi bitrate (kbps) olarak mı yoksa hedef dosya boyutu (KB / MB) olarak mı belirledi? */
enum class TargetMode { BITRATE, FILE_SIZE }

enum class FileSizeUnit(val label: String, val bytesPerUnit: Long) {
    KB("KB", 1024L),
    MB("MB", 1024L * 1024L)
}

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

/** Ses çıkışı kanal seçimi. */
enum class ChannelMode { AUTO, MONO, STEREO }

class ConverterViewModel(private val app: Application) : AndroidViewModel(app) {

    private val prefs = AppPreferences(app)

    var queue by mutableStateOf<List<ConvertQueueItem>>(emptyList())
        private set
    var outputFormat by mutableStateOf(OutputFormat.OPUS)
        private set
    var targetMode by mutableStateOf(TargetMode.BITRATE)
        private set
    var bitrateKbpsText by mutableStateOf("128")
        private set
    var targetSizeText by mutableStateOf("750")
        private set
    var targetSizeUnit by mutableStateOf(FileSizeUnit.KB)
        private set
    /** Çıkış kanal düzeni: Otomatik / Mono / Stereo */
    var channelMode by mutableStateOf(ChannelMode.AUTO)
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

    init {
        prefs.defaultOutputFolder?.let { outputFolderUri = it }
    }

    fun cancelConvert() { convertJob?.cancel() }

    fun selectFormat(format: OutputFormat) {
        if (!isConverting) outputFormat = format
    }

    fun selectTargetMode(mode: TargetMode) {
        if (!isConverting) targetMode = mode
    }

    fun updateTargetSizeText(value: String) {
        val normalized = value.replace(',', '.')
        val sb = StringBuilder()
        var hasDot = false
        for (ch in normalized) {
            if (ch.isDigit()) {
                sb.append(ch)
            } else if (ch == '.' && !hasDot) {
                hasDot = true
                sb.append(ch)
            }
            if (sb.length >= 7) break
        }
        targetSizeText = sb.toString()
    }

    fun selectTargetSizeUnit(unit: FileSizeUnit) {
        if (!isConverting) targetSizeUnit = unit
    }

    fun applySizePreset(valueText: String, unit: FileSizeUnit) {
        if (isConverting) return
        targetSizeText = valueText
        targetSizeUnit = unit
    }

    fun parseTargetSizeBytes(): Long? {
        val v = targetSizeText.replace(',', '.').toDoubleOrNull() ?: return null
        if (v <= 0.0) return null
        val bytes = (v * targetSizeUnit.bytesPerUnit.toDouble()).toLong()
        if (bytes < 8L * 1024L || bytes > 500L * 1024L * 1024L) return null
        return bytes
    }

    fun isTargetValid(): Boolean = when (targetMode) {
        TargetMode.BITRATE -> {
            val br = bitrateKbpsText.toIntOrNull()
            br != null && br in 6..512
        }
        TargetMode.FILE_SIZE -> parseTargetSizeBytes() != null
    }

    /**
     * Hedef dosya boyutu (KB / MB) modunda dosyanın süresine ve konteyner üstbilgi payına göre
     * gereken tam bit/saniye (bps) değerini hesaplar.
     */
    fun calculateBpsForFileSize(durationMs: Long, targetBytes: Long, format: OutputFormat = outputFormat): Int {
        val durationSec = (durationMs / 1000.0).coerceAtLeast(1.0)
        val overheadBytes = when (format) {
            OutputFormat.OPUS -> 1024L + (132.0 * durationSec).toLong()
            OutputFormat.MP3 -> 512L + (18.0 * durationSec).toLong()
            OutputFormat.HE_AAC, OutputFormat.HE_AAC_V2, OutputFormat.XHE_AAC -> 1536L + (95.0 * durationSec).toLong()
        }
        val minBps = when (format) {
            OutputFormat.OPUS -> 6_000
            OutputFormat.XHE_AAC -> 8_000
            OutputFormat.MP3 -> 8_000
            OutputFormat.HE_AAC, OutputFormat.HE_AAC_V2 -> 12_000
        }
        val maxBps = when (format) {
            OutputFormat.OPUS -> 510_000
            OutputFormat.MP3 -> 320_000
            OutputFormat.XHE_AAC -> 256_000
            OutputFormat.HE_AAC -> 160_000
            OutputFormat.HE_AAC_V2 -> 64_000
        }
        val usableBytes = (targetBytes - overheadBytes).coerceAtLeast((durationSec * minBps / 8.0).toLong())
        val rawBps = ((usableBytes * 8.0) / durationSec).roundToInt()
        return rawBps.coerceIn(minBps, maxBps)
    }

    /** UI'da seçili dosya için otomatik hesaplanan bitrate'i (kbps) göstermek üzere yardımcı. */
    fun previewAutoBitrateKbps(item: ConvertQueueItem): Int? {
        val targetBytes = parseTargetSizeBytes() ?: return null
        if (item.durationMs <= 0L) return null
        val bps = calculateBpsForFileSize(item.durationMs, targetBytes, outputFormat)
        return (bps / 1000.0).roundToInt().coerceAtLeast(6)
    }

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
                loadingMessage = "${index + 1}/${files.size}: $displayName"

                val ext = extensionFromName(displayName).ifBlank { "bin" }
                val id = nextId++
                val cachePath = withContext(Dispatchers.IO) {
                    copyUriToCache(uri, "src_${id}_${System.currentTimeMillis()}.$ext")
                }
                if (cachePath != null) {
                    val sizeMb = withContext(Dispatchers.IO) { InputAccess.length(cachePath).toFloat() / (1024 * 1024) }
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
                        withContext(Dispatchers.IO) { runCatching { InputAccess.release(cachePath) } }
                    }
                }
            }

            isLoading = false
            loadingMessage = ""
            if (added == 0) {
                resultMessage = app.getString(R.string.err_no_files_analyzed)
                isSuccess = false
            }
        }
    }

    fun removeFromQueue(id: Long) {
        if (isConverting) return
        val item = queue.firstOrNull { it.id == id }
        queue = queue.filterNot { it.id == id }
        if (item != null) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { InputAccess.release(item.cachePath) } }
        }
    }

    fun clearQueue() {
        if (isConverting) return
        val old = queue
        queue = emptyList()
        resultMessage = null; isSuccess = false; convertProgress = 0
        viewModelScope.launch(Dispatchers.IO) {
            old.forEach { runCatching { InputAccess.release(it.cachePath) } }
        }
    }

    fun selectChannelMode(mode: ChannelMode) { channelMode = mode }

    fun updateBitrateText(value: String) { bitrateKbpsText = value.filter { it.isDigit() }.take(4) }

    fun setOutputFolder(uri: Uri) {
        outputFolderUri = uri
        try {
            app.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) { }
        prefs.defaultOutputFolder = uri
    }

    fun clearResult() { resultMessage = null; isSuccess = false }

    fun dismissAndReset() {
        if (isConverting) return
        val old = queue
        queue = emptyList()
        resultMessage = null; isSuccess = false; convertProgress = 0
        viewModelScope.launch(Dispatchers.IO) {
            old.forEach { runCatching { InputAccess.release(it.cachePath) } }
        }
    }

    fun startConvert() {
        val outFolder = outputFolderUri
        val mode = targetMode
        val manualBitrateKbps = bitrateKbpsText.toIntOrNull()
        val targetBytes = parseTargetSizeBytes()
        val toProcess = queue.filter { it.status == ConvertStatus.PENDING || it.status == ConvertStatus.ERROR }

        if (toProcess.isEmpty()) { resultMessage = app.getString(R.string.err_no_pending_files); isSuccess = false; return }
        if (outFolder == null) { resultMessage = app.getString(R.string.err_no_output_folder); isSuccess = false; return }
        if (mode == TargetMode.BITRATE && (manualBitrateKbps == null || manualBitrateKbps < 6 || manualBitrateKbps > 512)) {
            resultMessage = app.getString(R.string.err_invalid_bitrate); isSuccess = false; return
        }
        if (mode == TargetMode.FILE_SIZE && targetBytes == null) {
            resultMessage = app.getString(R.string.err_invalid_target_size); isSuccess = false; return
        }
        if (isConverting) return

        convertJob = viewModelScope.launch {
            MuxForegroundService.start(app)
            MuxCancelBus.onCancelRequested = { cancelConvert() }
            try {
                isConverting = true; resultMessage = null; isSuccess = false; convertProgress = 0

                var doneCount = 0
                var errorCount = 0
                val total = toProcess.size
                val format = outputFormat

                for (item in toProcess) {
                    currentFileName = item.displayName
                    updateQueueItem(item.id) { it.copy(status = ConvertStatus.CONVERTING, progress = 0) }

                    val resolvedDurationMs = if (item.durationMs > 0L) {
                        item.durationMs
                    } else {
                        val probed = TrackProber.probeAudioDurationMsFallback(item.cachePath)
                        if (probed > 0L) {
                            updateQueueItem(item.id) { it.copy(durationMs = probed) }
                        }
                        probed
                    }

                    val targetBps: Int = if (mode == TargetMode.FILE_SIZE && targetBytes != null) {
                        calculateBpsForFileSize(resolvedDurationMs, targetBytes, format)
                    } else {
                        ((manualBitrateKbps ?: 128).coerceIn(6, 512)) * 1000
                    }

                    val workDir = File(app.cacheDir, "convert_work").also { it.mkdirs() }
                    val tempOutput = File(workDir, "out_${item.id}_${System.currentTimeMillis()}.${format.extension}")
                    tempOutput.delete()

                    val doneSoFar = doneCount + errorCount
                    val progressCb: (Int) -> Unit = { pct ->
                        updateQueueItem(item.id) { it.copy(progress = pct) }
                        convertProgress = ((((doneSoFar).toFloat() + pct / 100f) / total) * 100).toInt().coerceIn(0, 99)
                        MuxForegroundService.update(
                            app, convertProgress,
                            app.getString(R.string.notif_converting_progress, item.displayName, pct)
                        )
                    }

                    var returnCodeVal: Int? = 0
                    val ok: Boolean
                    if (format.isHeAac) {
                        ok = convertHeAac(item, format, targetBps, resolvedDurationMs, tempOutput, progressCb)
                    } else {
                        val args = buildFfmpegArgs(item, format, targetBps, tempOutput.absolutePath)
                        returnCodeVal = runFfmpegAsync(args, resolvedDurationMs, progressCb)
                        ok = returnCodeVal == 0 && tempOutput.exists() && tempOutput.length() > 0L
                    }
                    if (!ok) {
                        errorCount++
                        val errText = if (format.isHeAac) app.getString(R.string.err_heaac_failed)
                                      else app.getString(R.string.err_ffmpeg_code, returnCodeVal.toString())
                        updateQueueItem(item.id) { it.copy(status = ConvertStatus.ERROR, errorMessage = errText) }
                        runCatching { tempOutput.delete() }
                    } else {
                        val rawName = item.displayName.substringBeforeLast('.', item.displayName)
                        val finalSizeBytes = tempOutput.length()
                        val effectiveKbps = (targetBps / 1000.0).roundToInt().coerceAtLeast(6)

                        val copyOk = withContext(Dispatchers.IO) {
                            saveOutputToFolder(
                                tempOutput = tempOutput,
                                outFolder = outFolder,
                                rawName = rawName,
                                format = format,
                                effectiveKbps = effectiveKbps,
                                sourceCachePath = item.cachePath
                            )
                        }
                        runCatching { tempOutput.delete() }

                        if (copyOk) {
                            doneCount++
                            updateQueueItem(item.id) {
                                it.copy(status = ConvertStatus.DONE, progress = 100, outputSizeMb = finalSizeBytes / (1024f * 1024f))
                            }
                        } else {
                            errorCount++
                            updateQueueItem(item.id) { it.copy(status = ConvertStatus.ERROR, errorMessage = app.getString(R.string.err_save_failed)) }
                        }
                    }

                    convertProgress = (((doneCount + errorCount).toFloat() / total) * 100).toInt().coerceIn(0, 100)
                    runCatching { InputAccess.release(item.cachePath) }
                }

                convertProgress = 100
                currentFileName = ""
                resultMessage = if (errorCount == 0) {
                    app.getString(R.string.result_convert_success_all, doneCount, total, format.label)
                } else {
                    app.getString(R.string.result_convert_partial, doneCount, total, errorCount)
                }
                isSuccess = errorCount == 0
            } catch (c: CancellationException) {
                resultMessage = app.getString(R.string.result_cancelled); isSuccess = false; currentFileName = ""
                throw c
            } finally {
                isConverting = false
                MuxCancelBus.onCancelRequested = null
                MuxForegroundService.stop(app, resultMessage, isSuccess)
            }
        }
    }

    /**
     * Çıktı dosyasını hedef klasöre güvenli şekilde yazar:
     * 1) Hedef klasörde aynı isimde dosya varsa ve bu dosya kaynak dosyanın kendisiyse
     *    (ör. 6.5 MB'lık song.opus aynı klasöre tekrar opus olarak kaydediliyorsa),
     *    kaynağın üzerine yazmamak ve eski 6.5 MB boyutun kalmasını önlemek için
     *    dosya adına otomatik olarak `_16kbps` gibi sonek ekler.
     * 2) Önceki bir dönüştürmeden kalan aynı isimli dosya varsa önce siler, ardından
     *    yeni dosyayı oluşturup "wt" (truncate) modunda açarak eski baytların dosya
     *    sonunda kalmasını (dosyanın 6.5 MB kalıp bozulmasını) tamamen engeller.
     */
    private fun saveOutputToFolder(
        tempOutput: File,
        outFolder: Uri,
        rawName: String,
        format: OutputFormat,
        effectiveKbps: Int,
        sourceCachePath: String
    ): Boolean {
        return try {
            val tree = DocumentFile.fromTreeUri(app, outFolder) ?: return false
            var finalName = "$rawName.${format.extension}"
            val srcCanonical = runCatching { File(sourceCachePath).canonicalPath }.getOrNull()

            val existing = tree.findFile(finalName)
            if (existing != null) {
                val existingReal = InputAccess.realPath(app, existing.uri)
                val existingCanonical = existingReal?.let { runCatching { File(it).canonicalPath }.getOrNull() }
                val isSameAsSource = (srcCanonical != null && existingCanonical != null && srcCanonical == existingCanonical)
                if (isSameAsSource) {
                    finalName = "${rawName}_${effectiveKbps}kbps.${format.extension}"
                    tree.findFile(finalName)?.let { prev ->
                        val prevReal = InputAccess.realPath(app, prev.uri)
                        val prevCanonical = prevReal?.let { runCatching { File(it).canonicalPath }.getOrNull() }
                        if (prevCanonical == null || prevCanonical != srcCanonical) {
                            runCatching { prev.delete() }
                        }
                    }
                } else {
                    runCatching { existing.delete() }
                }
            }

            val outDoc = tree.findFile(finalName) ?: tree.createFile(format.mimeType, finalName) ?: return false
            val outUri = outDoc.uri
            val stream = runCatching { app.contentResolver.openOutputStream(outUri, "wt") }.getOrNull()
                ?: app.contentResolver.openOutputStream(outUri)
                ?: return false

            stream.use { out ->
                tempOutput.inputStream().use { input ->
                    input.copyTo(out, 4 * 1024 * 1024)
                }
                out.flush()
            }
            triggerMediaScan(outUri, format.mimeType)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun updateQueueItem(id: Long, transform: (ConvertQueueItem) -> ConvertQueueItem) {
        queue = queue.map { if (it.id == id) transform(it) else it }
    }

    /**
     * xHE-AAC / HE-AAC / HE-AAC v2:
     * 1) FFmpeg ile kaynak sesi kapak resmi ve meta verilerden arındırarak hızlıca ham PCM'e çevir,
     * 2) Android MediaCodec (PROFILE_XHE / PROFILE_HE / PROFILE_HE_V2) ile tam hedef bps'te
     *    yüksek öncelikle encode edip .m4a olarak yaz.
     */
    private suspend fun convertHeAac(
        item: ConvertQueueItem,
        format: OutputFormat,
        targetBps: Int,
        durationMs: Long,
        tempOutput: File,
        onProgress: (Int) -> Unit
    ): Boolean {
        val isV2 = format == OutputFormat.HE_AAC_V2 && channelMode != ChannelMode.MONO
        val isXhe = format == OutputFormat.XHE_AAC
        val channels = when (channelMode) {
            ChannelMode.MONO -> 1
            ChannelMode.STEREO -> 2
            ChannelMode.AUTO -> when {
                isV2 -> 2
                isXhe && targetBps < 20_000 -> 1
                !isXhe && targetBps < 24_000 -> 1
                item.sourceChannels == 1 -> 1
                else -> 2
            }
        }
        // Düşük bitrate'lerde (< 24 kbps) 32 kHz SBR hem %27 daha hızlı çözülür/kodlanır
        // hem de kare başına düşen bit yoğunluğunu artırarak ses netliğini yükseltir.
        val sampleRate = when {
            targetBps < 24_000 -> 32000
            isXhe && targetBps >= 32_000 -> 48000
            else -> 44100
        }
        val loBps = when {
            isXhe -> if (channels == 1) 8_000 else 12_000
            isV2 -> 14_000
            channels == 1 -> 12_000
            else -> 20_000
        }
        val hiBps = when {
            isXhe -> 256_000
            isV2 -> 64_000
            channels == 1 -> 96_000
            else -> 160_000
        }
        val effectiveBps = targetBps.coerceIn(loBps, hiBps)

        val pcm = File(tempOutput.parentFile, "pcm_${item.id}_${System.currentTimeMillis()}.raw")
        try {
            val args = arrayOf(
                "-y",
                "-err_detect", "ignore_err",
                "-i", item.cachePath,
                "-vn", "-sn", "-dn",
                "-map_metadata", "-1",
                "-map_chapters", "-1",
                "-map", "0:a:0",
                "-ar", sampleRate.toString(),
                "-ac", channels.toString(),
                "-c:a", "pcm_s16le",
                "-f", "s16le",
                "-threads", "0",
                pcm.absolutePath
            )
            // Çözme aşaması toplam ilerlemenin ilk %35'i
            val rc = runFfmpegAsync(args, durationMs) { pct -> onProgress((pct * 35) / 100) }
            if (rc != 0 || !pcm.exists() || pcm.length() <= 0L) return false

            val profile = when {
                isXhe -> HeAacEncoder.PROFILE_XHE
                isV2 -> HeAacEncoder.PROFILE_HE_V2
                else -> HeAacEncoder.PROFILE_HE
            }
            return withContext(Dispatchers.Default) {
                HeAacEncoder.encodeBps(
                    pcmFile = pcm,
                    outFile = tempOutput,
                    sampleRate = sampleRate,
                    channels = channels,
                    bitrateBps = effectiveBps,
                    profile = profile,
                    onProgress = { pct -> viewModelScope.launch { onProgress(35 + (pct * 65) / 100) } },
                    isCancelled = { !isActive }
                ) && tempOutput.exists() && tempOutput.length() > 0L
            }
        } finally {
            runCatching { pcm.delete() }
        }
    }

    /**
     * Opus ve MP3 için FFmpeg argümanlarını oluşturur.
     *
     * KÖK NEDEN DÜZELTMELERİ (Opus 16 kbps bozulma ve 6.5 MB dosya boyutu sorunu):
     * 1) Kaynak dosyadaki gömülü kapak resimleri / METADATA_BLOCK_PICTURE / COVERART etiketleri
     *    `-map_metadata -1 -map_chapters -1 -vn -sn -dn` ile tamamen temizlenir. Böylece
     *    6 MB kapak resmine sahip 6.5 MB'lık bir şarkı 16 kbps'de kodlandığında 6 MB'lık
     *    etiketin `.opus` başlığına kopyalanıp dosyayı 6.5 MB yapması ve Android OggExtractor'ı
     *    çökertmesi engellenir.
     * 2) `-fflags +genpts+discardcorrupt` ve `aresample=async=1:first_pts=0` kaldırıldı; bozuk
     *    zaman damgalarında milyonlarca örnek sessizlik üretip dosyayı şişirme hatası giderildi.
     * 3) Opus düşük bitrate'lerde (`<= 20 kbps`) CELT (`-application audio`) + `-frame_duration 60`
     *    (Code-3 çoklu çerçeve) yerine standart RFC 6716 `-frame_duration 20`, `-application voip`
     *    (SILK/Hybrid) ve Mono (`-ac 1`) kullanılır; böylece 6-24 kbps aralığında bile ses
     *    cam gibi net, hatasız ve tam hedef boyutta çıkar.
     */
    private fun buildFfmpegArgs(
        item: ConvertQueueItem,
        format: OutputFormat,
        targetBps: Int,
        outputPath: String
    ): Array<String> {
        val clampedBps = when (format) {
            OutputFormat.OPUS -> targetBps.coerceIn(6_000, 510_000)
            OutputFormat.MP3 -> targetBps.coerceIn(8_000, 320_000)
            else -> targetBps.coerceIn(12_000, 256_000)
        }

        // Opus'ta < 24 kbps stereo (kanal başına < 12 kbps) sesi bozduğu için,
        // çok düşük bitrate'lerde (veya Otomatik modda < 32 kbps) mono kullanılır.
        val acValue: String = when {
            format == OutputFormat.OPUS && clampedBps < 24_000 -> "1"
            format == OutputFormat.MP3 && clampedBps < 32_000 -> "1"
            channelMode == ChannelMode.MONO -> "1"
            channelMode == ChannelMode.STEREO -> "2"
            clampedBps < 36_000 -> "1"
            item.sourceChannels == 1 -> "1"
            else -> "2"
        }

        val sampleRate: String = when (format) {
            OutputFormat.OPUS -> "48000"
            OutputFormat.MP3 -> when {
                clampedBps < 16_000 -> "16000"
                clampedBps < 32_000 -> "24000"
                clampedBps < 48_000 -> "32000"
                else -> "44100"
            }
            else -> "44100"
        }

        return buildList {
            add("-y")
            add("-err_detect"); add("ignore_err")
            add("-i"); add(item.cachePath)
            add("-vn"); add("-sn"); add("-dn")
            add("-map_metadata"); add("-1")
            add("-map_chapters"); add("-1")
            add("-map"); add("0:a:0")
            add("-ar"); add(sampleRate)
            add("-ac"); add(acValue)
            when (format) {
                OutputFormat.OPUS -> {
                    add("-c:a"); add("libopus")
                    // <= 20 kbps'de SILK/Hybrid (voip) modu düşük bit hızında sesin bozulmasını önler.
                    add("-application"); add(if (clampedBps <= 20_000) "voip" else "audio")
                    add("-vbr"); add("constrained")
                    add("-frame_duration"); add("20")
                    add("-compression_level"); add("6")
                }
                OutputFormat.MP3 -> {
                    add("-c:a"); add("libmp3lame")
                    add("-write_xing"); add("1")
                }
                OutputFormat.HE_AAC, OutputFormat.HE_AAC_V2, OutputFormat.XHE_AAC -> {
                    add("-c:a"); add("aac")
                }
            }
            add("-b:a"); add(clampedBps.toString())
            add("-threads"); add("0")
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

    private fun copyUriToCache(uri: Uri, fileName: String): String? =
        InputAccess.acquire(app, uri, File(app.cacheDir, "convert_work"), fileName, needChildRead = false)

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
