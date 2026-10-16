package com.example.muxmaster.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.system.Os
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
import com.example.muxmaster.data.NativeTools
import com.example.muxmaster.data.TrackProber
import com.example.muxmaster.service.MuxCancelBus
import com.example.muxmaster.service.MuxForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.resume
import kotlin.math.roundToInt

enum class OutputFormat(val extension: String, val mimeType: String, val label: String) {
    OPUS("opus", "audio/opus", "Opus"),
    XHE_AAC("m4a", "audio/mp4", "xHE-AAC"),
    HE_AAC("m4a", "audio/mp4", "HE-AAC"),
    HE_AAC_V2("m4a", "audio/mp4", "HE-AAC v2"),
    MP3("mp3", "audio/mpeg", "MP3");

    /** HE-AAC v1 / v2: Android MediaCodec ile encode edilir. */
    val isHeAac: Boolean get() = this == HE_AAC || this == HE_AAC_V2
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

    private fun resetFinishedItemsForReconvert() {
        if (queue.any { it.status == ConvertStatus.DONE || it.status == ConvertStatus.ERROR }) {
            queue = queue.map {
                if (it.status == ConvertStatus.DONE || it.status == ConvertStatus.ERROR) {
                    it.copy(status = ConvertStatus.PENDING, progress = 0, errorMessage = null)
                } else it
            }
        }
    }

    fun selectFormat(format: OutputFormat) {
        if (!isConverting) {
            outputFormat = format
            resetFinishedItemsForReconvert()
        }
    }

    fun selectTargetMode(mode: TargetMode) {
        if (!isConverting) {
            targetMode = mode
            resetFinishedItemsForReconvert()
        }
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
        if (!isConverting) resetFinishedItemsForReconvert()
    }

    fun selectTargetSizeUnit(unit: FileSizeUnit) {
        if (!isConverting) {
            targetSizeUnit = unit
            resetFinishedItemsForReconvert()
        }
    }

    fun applySizePreset(valueText: String, unit: FileSizeUnit) {
        if (isConverting) return
        targetSizeText = valueText
        targetSizeUnit = unit
        resetFinishedItemsForReconvert()
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
            OutputFormat.XHE_AAC -> 12_000
            OutputFormat.MP3 -> 8_000
            OutputFormat.HE_AAC, OutputFormat.HE_AAC_V2 -> 12_000
        }
        val maxBps = when (format) {
            OutputFormat.OPUS -> 510_000
            OutputFormat.MP3 -> 320_000
            OutputFormat.XHE_AAC -> 192_000
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

    fun selectChannelMode(mode: ChannelMode) {
        channelMode = mode
        if (!isConverting) resetFinishedItemsForReconvert()
    }

    fun updateBitrateText(value: String) {
        bitrateKbpsText = value.filter { it.isDigit() }.take(4)
        if (!isConverting) resetFinishedItemsForReconvert()
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

    override fun onCleared() {
        super.onCleared()
        val old = queue
        old.forEach { runCatching { InputAccess.release(it.cachePath) } }
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
                    val ok: Boolean = when (format) {
                        OutputFormat.XHE_AAC -> {
                            convertXheAac(item, targetBps, resolvedDurationMs, tempOutput, progressCb)
                        }
                        OutputFormat.HE_AAC, OutputFormat.HE_AAC_V2 -> {
                            convertHeAac(item, format, targetBps, resolvedDurationMs, tempOutput, progressCb)
                        }
                        OutputFormat.OPUS -> {
                            convertOpus(item, targetBps, targetBytes, resolvedDurationMs, tempOutput, progressCb)
                        }
                        OutputFormat.MP3 -> {
                            val args = buildMp3FfmpegArgs(item, targetBps, tempOutput.absolutePath)
                            returnCodeVal = runFfmpegAsync(args, resolvedDurationMs, progressCb)
                            returnCodeVal == 0 && tempOutput.exists() && tempOutput.length() > 0L
                        }
                    }

                    if (!ok) {
                        errorCount++
                        val errText = if (format == OutputFormat.XHE_AAC || format.isHeAac) {
                            app.getString(R.string.err_heaac_failed)
                        } else {
                            app.getString(R.string.err_ffmpeg_code, (returnCodeVal ?: -1).toString())
                        }
                        updateQueueItem(item.id) { it.copy(status = ConvertStatus.ERROR, errorMessage = errText) }
                        runCatching { tempOutput.delete() }
                    } else {
                        val rawName = item.displayName.substringBeforeLast('.', item.displayName)
                        val finalSizeBytes = tempOutput.length()
                        val effectiveKbps = (targetBps / 1000.0).roundToInt().coerceAtLeast(6)

                        val copyOk = withContext(Dispatchers.IO) {
                            saveOutputToFolder(
                                item = item,
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
     * Gerçek xHE-AAC (ISO/IEC 23003-3 USAC / Kodek Kimliği: mp4a-40-42) kodlayıcı:
     * APK içine gömülü `libexhale.so` ikilisini kullanarak MediaInfo'da:
     *   Biçim: USAC
     *   Ticari adı: xHE-AAC
     *   Kodek Kimliği: mp4a-40-42
     * olan gerçek xHE-AAC (.m4a) dosyası üretir.
     */
    private data class ExhaleConfig(val preset: String, val sampleRate: Int, val channels: Int)

    private fun exhaleConfigsFor(targetBps: Int, mode: ChannelMode, sourceChannels: Int): List<ExhaleConfig> {
        val kbps = (targetBps / 1000.0).roundToInt().coerceIn(10, 256)
        val useMono = when (mode) {
            ChannelMode.MONO -> true
            ChannelMode.STEREO -> false
            ChannelMode.AUTO -> kbps <= 24 || sourceChannels <= 1
        }
        val list = mutableListOf<ExhaleConfig>()
        fun addCfg(preset: String, sr: Int, ch: Int) {
            val c = ExhaleConfig(preset, sr, ch)
            if (c !in list) list.add(c)
        }

        if (useMono) {
            when {
                kbps <= 13 -> {
                    addCfg("a", 22050, 1)
                    addCfg("0", 24000, 1)
                    addCfg("a", 32000, 1)
                }
                kbps <= 17 -> {
                    addCfg("0", 32000, 1)
                    addCfg("a", 32000, 1)
                    addCfg("a", 22050, 1)
                }
                kbps <= 21 -> {
                    addCfg("a", 44100, 1)
                    addCfg("b", 32000, 1)
                    addCfg("a", 32000, 1)
                }
                kbps <= 26 -> {
                    addCfg("b", 44100, 1)
                    addCfg("c", 32000, 1)
                    addCfg("1", 32000, 1)
                }
                kbps <= 30 -> {
                    addCfg("c", 44100, 1)
                    addCfg("1", 32000, 1)
                }
                kbps <= 35 -> {
                    addCfg("1", 44100, 1)
                    addCfg("d", 44100, 1)
                }
                kbps <= 44 -> addCfg("2", 44100, 1)
                kbps <= 52 -> addCfg("3", 48000, 1)
                kbps <= 60 -> addCfg("4", 48000, 1)
                kbps <= 68 -> addCfg("5", 48000, 1)
                kbps <= 76 -> addCfg("6", 48000, 1)
                kbps <= 84 -> addCfg("7", 48000, 1)
                kbps <= 92 -> addCfg("8", 48000, 1)
                else -> addCfg("9", 48000, 1)
            }
            addCfg("a", 32000, 1)
            addCfg("1", 32000, 1)
        } else {
            when {
                kbps <= 26 -> {
                    addCfg("a", 22050, 2)
                    addCfg("0", 24000, 2)
                    addCfg("a", 32000, 2)
                }
                kbps <= 34 -> {
                    addCfg("a", 32000, 2)
                    addCfg("0", 32000, 2)
                    addCfg("a", 44100, 2)
                }
                kbps <= 43 -> {
                    addCfg("a", 44100, 2)
                    addCfg("b", 32000, 2)
                    addCfg("1", 32000, 2)
                }
                kbps <= 52 -> {
                    addCfg("b", 44100, 2)
                    addCfg("1", 32000, 2)
                }
                kbps <= 59 -> {
                    addCfg("c", 44100, 2)
                    addCfg("1", 44100, 2)
                }
                kbps <= 68 -> {
                    addCfg("1", 44100, 2)
                    addCfg("d", 44100, 2)
                }
                kbps <= 75 -> {
                    addCfg("e", 44100, 2)
                    addCfg("2", 44100, 2)
                }
                kbps <= 88 -> {
                    addCfg("2", 44100, 2)
                    addCfg("f", 44100, 2)
                }
                kbps <= 104 -> {
                    addCfg("3", 48000, 2)
                    addCfg("g", 48000, 2)
                }
                kbps <= 120 -> addCfg("4", 48000, 2)
                kbps <= 136 -> addCfg("5", 48000, 2)
                kbps <= 152 -> addCfg("6", 48000, 2)
                kbps <= 168 -> addCfg("7", 48000, 2)
                kbps <= 184 -> addCfg("8", 48000, 2)
                else -> addCfg("9", 48000, 2)
            }
            addCfg("a", 32000, 2)
            addCfg("1", 44100, 2)
        }
        return list
    }

    private suspend fun convertXheAac(
        item: ConvertQueueItem,
        targetBps: Int,
        durationMs: Long,
        tempOutput: File,
        onProgress: (Int) -> Unit
    ): Boolean {
        if (!NativeTools.hasExhale(app)) return false

        val configs = exhaleConfigsFor(targetBps, channelMode, item.sourceChannels)
        var lastWavKey = ""
        val wavFile = File(tempOutput.parentFile, "xhe_in_${item.id}_${System.currentTimeMillis()}.wav")

        try {
            for (cfg in configs) {
                val wavKey = "${cfg.sampleRate}_${cfg.channels}"
                if (wavKey != lastWavKey || !wavFile.exists() || wavFile.length() <= 44L) {
                    wavFile.delete()
                    val wavArgs = arrayOf(
                        "-y",
                        "-err_detect", "ignore_err",
                        "-i", item.cachePath,
                        "-vn", "-sn", "-dn",
                        "-map_metadata", "-1",
                        "-map_chapters", "-1",
                        "-map", "0:a:0",
                        "-ar", cfg.sampleRate.toString(),
                        "-ac", cfg.channels.toString(),
                        "-bitexact",
                        "-c:a", "pcm_s16le",
                        "-f", "wav",
                        "-threads", "0",
                        wavFile.absolutePath
                    )
                    val rc = runFfmpegAsync(wavArgs, durationMs) { pct -> onProgress((pct * 35) / 100) }
                    if (rc != 0 || !wavFile.exists() || wavFile.length() <= 44L) continue
                    lastWavKey = wavKey
                }

                tempOutput.delete()
                val realDurSec = ((wavFile.length() - 44L).toDouble() / (cfg.sampleRate * cfg.channels * 2.0)).coerceAtLeast(1.0)
                val estOutBytes = ((targetBps / 8.0) * realDurSec).toLong().coerceAtLeast(8_192L)

                val watcherJob = viewModelScope.launch(Dispatchers.IO) {
                    while (isActive) {
                        delay(120L)
                        val cur = if (tempOutput.exists()) tempOutput.length() else 0L
                        if (cur > 0L) {
                            val pct = ((cur * 100L) / estOutBytes).toInt().coerceIn(1, 95)
                            onProgress(35 + (pct * 63) / 100)
                        }
                    }
                }

                val res = try {
                    NativeTools.run(
                        app,
                        NativeTools.exhalePath(app),
                        listOf(cfg.preset, wavFile.absolutePath, tempOutput.absolutePath)
                    )
                } finally {
                    watcherJob.cancel()
                }

                if (res.exitCode == 0 && tempOutput.exists() && tempOutput.length() > 256L) {
                    onProgress(100)
                    return true
                }
            }
            return false
        } finally {
            runCatching { wavFile.delete() }
        }
    }

    /**
     * Opus kodlayıcı (6–510 kbps, özellikle 12–16 kbps düşük bitrate düzeltmesi):
     * 1) Kaynak dosyayı FFmpeg ile tüm konteyner zaman damgası, kapak resmi ve meta verilerinden
     *    arındırılmış ham 48 kHz 16-bit PCM'e (`pcm.raw`) çözer.
     * 2) APK içine gömülü `libopusenc.so` (Termux'un resmi, `-fno-fast-math` ile derlenmiş ve
     *    ffmpeg-kit'ten izole edilmiş `libmuxtn_opus.so` kütüphanesini kullanan kodlayıcı) ile
     *    çerçeve boyutu üst sınırı (`max_frame_bytes`) denetimli olarak `.opus` üretir.
     * 3) Böylece ffmpeg-kit'in bozuk SILK kodlayıcısı nedeniyle 16 kbps'de oluşan 6.5 MB'lık
     *    bozuk dosya hatası %100 ortadan kaldırılır.
     */
    private suspend fun convertOpus(
        item: ConvertQueueItem,
        targetBps: Int,
        targetBytes: Long?,
        durationMs: Long,
        tempOutput: File,
        onProgress: (Int) -> Unit
    ): Boolean {
        val channels = when {
            targetBps < 24_000 -> 1
            channelMode == ChannelMode.MONO -> 1
            channelMode == ChannelMode.STEREO -> 2
            targetBps < 32_000 -> 1
            item.sourceChannels == 1 -> 1
            else -> 2
        }

        val pcm = File(tempOutput.parentFile, "opus_pcm_${item.id}_${System.currentTimeMillis()}.raw")
        try {
            val pcmArgs = arrayOf(
                "-y",
                "-err_detect", "ignore_err",
                "-i", item.cachePath,
                "-vn", "-sn", "-dn",
                "-map_metadata", "-1",
                "-map_chapters", "-1",
                "-map", "0:a:0",
                "-ar", "48000",
                "-ac", channels.toString(),
                "-c:a", "pcm_s16le",
                "-f", "s16le",
                "-threads", "0",
                pcm.absolutePath
            )
            val rc = runFfmpegAsync(pcmArgs, durationMs) { pct -> onProgress((pct * 35) / 100) }
            if (rc != 0 || !pcm.exists() || pcm.length() <= 0L) return false

            val realDurationMs = ((pcm.length().toDouble() / (48000.0 * channels * 2.0)) * 1000.0).toLong().coerceAtLeast(1000L)
            val effectiveBps = if (targetMode == TargetMode.FILE_SIZE && targetBytes != null) {
                calculateBpsForFileSize(realDurationMs, targetBytes, OutputFormat.OPUS)
            } else {
                targetBps.coerceIn(6_000, 510_000)
            }

            val expectedRawBytes = ((realDurationMs / 1000.0) * (effectiveBps / 8.0)).toLong().coerceAtLeast(4096L)
            val maxAllowedBytes = (expectedRawBytes * 2L) + 65_536L

            // 1. Öncelik: Gömülü temiz libopusenc.so (Termux resmi libmuxtn_opus.so ile)
            if (NativeTools.hasOpusenc(app)) {
                tempOutput.delete()
                val res = NativeTools.run(
                    app,
                    NativeTools.opusencPath(app),
                    listOf(pcm.absolutePath, tempOutput.absolutePath, channels.toString(), effectiveBps.toString())
                ) { pct ->
                    viewModelScope.launch { onProgress(35 + (pct * 65) / 100) }
                }
                if (res.exitCode == 0 && tempOutput.exists() && tempOutput.length() in 64L..maxAllowedBytes) {
                    onProgress(100)
                    return true
                }
            }

            // 2. Yedek: Ham PCM üzerinden saf CELT (-application lowdelay) ile FFmpeg
            tempOutput.delete()
            val fallbackArgs = arrayOf(
                "-y",
                "-f", "s16le",
                "-ar", "48000",
                "-ac", channels.toString(),
                "-i", pcm.absolutePath,
                "-c:a", "libopus",
                "-application", "lowdelay",
                "-vbr", "constrained",
                "-frame_duration", "20",
                "-compression_level", "6",
                "-b:a", effectiveBps.toString(),
                "-threads", "0",
                tempOutput.absolutePath
            )
            val fbRc = runFfmpegAsync(fallbackArgs, realDurationMs) { pct -> onProgress(35 + (pct * 65) / 100) }
            return fbRc == 0 && tempOutput.exists() && tempOutput.length() in 64L..maxAllowedBytes
        } finally {
            runCatching { pcm.delete() }
        }
    }

    /**
     * Çıktı dosyasını hedef klasöre güvenli şekilde yazar:
     * 1) Önce doğrudan dosya sistemi yolunu (`resolveTreeFolderToFile`) dener: bu sayede Android
     *    SAF'ın `audio/ogg` için `.opus` sonuna `.ogg` eklemesi veya eski 6.5 MB'lık dosyayı
     *    kesmeden (truncate etmeden) üzerine yazması tamamen önlenir.
     * 2) Hedef dosya kaynak dosyanın kendisiyse (aynı klasöre aynı uzantıyla kaydetme), kaynağın
     *    üzerine yazmamak için dosya adına otomatik olarak `_16kbps` gibi sonek ekler.
     * 3) SAF DocumentFile kullanıldığında da `Os.ftruncate` ile dosya boyutunu tam çıktı
     *    boyutuna keser.
     */
    private fun saveOutputToFolder(
        item: ConvertQueueItem,
        tempOutput: File,
        outFolder: Uri,
        rawName: String,
        format: OutputFormat,
        effectiveKbps: Int,
        sourceCachePath: String
    ): Boolean {
        val expectedLen = tempOutput.length()
        if (expectedLen <= 0L) return false

        // 1) Doğrudan yerel klasör erişimi (hızlı ve %100 kesin boyut/uzantı)
        val directFolder = resolveTreeFolderToFile(outFolder)
        if (directFolder != null && directFolder.isDirectory && directFolder.canWrite()) {
            try {
                var finalName = "$rawName.${format.extension}"
                var targetFile = File(directFolder, finalName)
                if (isSameAsSourceFile(item, sourceCachePath, targetFile)) {
                    finalName = "${rawName}_${effectiveKbps}kbps.${format.extension}"
                    targetFile = File(directFolder, finalName)
                }
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                tempOutput.inputStream().use { input ->
                    targetFile.outputStream().use { out ->
                        input.copyTo(out, 4 * 1024 * 1024)
                        out.flush()
                    }
                }
                if (targetFile.length() != expectedLen) {
                    runCatching { RandomAccessFile(targetFile, "rw").use { it.setLength(expectedLen) } }
                }
                if (targetFile.exists() && targetFile.length() == expectedLen) {
                    android.media.MediaScannerConnection.scanFile(
                        app,
                        arrayOf(targetFile.absolutePath),
                        arrayOf(format.mimeType),
                        null
                    )
                    return true
                }
            } catch (_: Throwable) {
                // SAF DocumentFile yedek yoluna devam et
            }
        }

        // 2) SAF DocumentFile yolu
        return try {
            val tree = DocumentFile.fromTreeUri(app, outFolder) ?: return false
            var finalName = "$rawName.${format.extension}"
            val sameExtAsSource = item.displayName.equals(finalName, ignoreCase = true)

            val existing = tree.findFile(finalName)
            if (existing != null) {
                val existingReal = InputAccess.realPath(app, existing.uri)
                val isSame = sameExtAsSource ||
                    existing.uri == item.uri ||
                    (existingReal != null && isSameAsSourceFile(item, sourceCachePath, File(existingReal)))
                if (isSame) {
                    finalName = "${rawName}_${effectiveKbps}kbps.${format.extension}"
                    tree.findFile(finalName)?.let { prev -> runCatching { prev.delete() } }
                } else {
                    val deleted = runCatching { existing.delete() }.getOrDefault(false)
                    if (!deleted && tree.findFile(finalName) != null) {
                        finalName = "${rawName}_${effectiveKbps}kbps.${format.extension}"
                        tree.findFile(finalName)?.let { prev -> runCatching { prev.delete() } }
                    }
                }
            }

            // "application/octet-stream" ile oluşturmak Android'in "song.opus" adına ".ogg" eklemesini önler
            val outDoc = tree.findFile(finalName)
                ?: tree.createFile("application/octet-stream", finalName)
                ?: tree.createFile(format.mimeType, finalName)
                ?: return false

            if (outDoc.name != null && outDoc.name != finalName) {
                runCatching { outDoc.renameTo(finalName) }
            }

            val outUri = outDoc.uri
            val stream = runCatching { app.contentResolver.openOutputStream(outUri, "rwt") }.getOrNull()
                ?: runCatching { app.contentResolver.openOutputStream(outUri, "wt") }.getOrNull()
                ?: app.contentResolver.openOutputStream(outUri)
                ?: return false

            stream.use { out ->
                tempOutput.inputStream().use { input ->
                    input.copyTo(out, 4 * 1024 * 1024)
                }
                out.flush()
            }
            runCatching {
                app.contentResolver.openFileDescriptor(outUri, "rw")?.use { pfd ->
                    Os.ftruncate(pfd.fileDescriptor, expectedLen)
                }
            }
            triggerMediaScan(outUri, format.mimeType)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveTreeFolderToFile(treeUri: Uri): File? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val parts = docId.split(":", limit = 2)
            if (parts.size == 2) {
                val base = if (parts[0].equals("primary", ignoreCase = true)) {
                    Environment.getExternalStorageDirectory().absolutePath
                } else {
                    "/storage/${parts[0]}"
                }
                val rel = parts[1].trim('/')
                val dir = if (rel.isEmpty()) File(base) else File(base, rel)
                if (dir.exists() && dir.isDirectory) dir else null
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun normalizeStoragePath(path: String): String {
        val prefixes = listOf(
            "/storage/emulated/0/",
            "/mnt/user/0/emulated/0/",
            "/mnt/pass_through/0/emulated/0/",
            "/sdcard/"
        )
        for (p in prefixes) {
            if (path.startsWith(p)) return path.removePrefix(p)
        }
        return path
    }

    private fun isSameAsSourceFile(item: ConvertQueueItem, sourceCachePath: String, targetFile: File): Boolean {
        if (!targetFile.exists()) return false
        val srcReal = InputAccess.realPath(app, item.uri) ?: sourceCachePath
        if (normalizeStoragePath(srcReal).equals(normalizeStoragePath(targetFile.absolutePath), ignoreCase = true)) {
            return true
        }
        val c1 = runCatching { File(srcReal).canonicalPath }.getOrNull()
        val c2 = runCatching { targetFile.canonicalPath }.getOrNull()
        if (c1 != null && c2 != null && normalizeStoragePath(c1) == normalizeStoragePath(c2)) {
            return true
        }
        return false
    }

    private fun updateQueueItem(id: Long, transform: (ConvertQueueItem) -> ConvertQueueItem) {
        queue = queue.map { if (it.id == id) transform(it) else it }
    }

    /**
     * HE-AAC v1 / HE-AAC v2:
     * 1) FFmpeg ile kaynak sesi kapak resmi ve meta verilerden arındırarak hızlıca ham PCM'e çevir,
     * 2) Android MediaCodec (PROFILE_HE / PROFILE_HE_V2) ile tam hedef bps'te
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
        val channels = when (channelMode) {
            ChannelMode.MONO -> 1
            ChannelMode.STEREO -> 2
            ChannelMode.AUTO -> when {
                isV2 -> 2
                targetBps < 24_000 -> 1
                item.sourceChannels == 1 -> 1
                else -> 2
            }
        }
        val sampleRate = if (targetBps < 24_000) 32000 else 44100
        val loBps = if (isV2) 14_000 else if (channels == 1) 12_000 else 20_000
        val hiBps = if (isV2) 64_000 else if (channels == 1) 96_000 else 160_000
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
            val rc = runFfmpegAsync(args, durationMs) { pct -> onProgress((pct * 35) / 100) }
            if (rc != 0 || !pcm.exists() || pcm.length() <= 0L) return false

            val profile = if (isV2) HeAacEncoder.PROFILE_HE_V2 else HeAacEncoder.PROFILE_HE
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

    private fun buildMp3FfmpegArgs(
        item: ConvertQueueItem,
        targetBps: Int,
        outputPath: String
    ): Array<String> {
        val clampedBps = targetBps.coerceIn(8_000, 320_000)
        val acValue: String = when {
            clampedBps < 32_000 -> "1"
            channelMode == ChannelMode.MONO -> "1"
            channelMode == ChannelMode.STEREO -> "2"
            clampedBps < 40_000 -> "1"
            item.sourceChannels == 1 -> "1"
            else -> "2"
        }
        val sampleRate: String = when {
            clampedBps < 16_000 -> "16000"
            clampedBps < 32_000 -> "24000"
            clampedBps < 48_000 -> "32000"
            else -> "44100"
        }
        return arrayOf(
            "-y",
            "-err_detect", "ignore_err",
            "-i", item.cachePath,
            "-vn", "-sn", "-dn",
            "-map_metadata", "-1",
            "-map_chapters", "-1",
            "-map", "0:a:0",
            "-ar", sampleRate,
            "-ac", acValue,
            "-c:a", "libmp3lame",
            "-write_xing", "1",
            "-b:a", clampedBps.toString(),
            "-threads", "0",
            outputPath
        )
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
        InputAccess.acquire(app, uri, File(app.cacheDir, "convert_work"), fileName, needChildRead = true)

    private fun extensionFromName(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        if (dot < 0 || dot == displayName.length - 1) return ""
        return displayName.substring(dot + 1).lowercase().filter { it.isLetterOrDigit() }
    }

    private fun triggerMediaScan(docUri: Uri, mimeType: String) {
        try {
            val docId = DocumentsContract.getDocumentId(docUri)
            val parts = docId.split(":", limit = 2)
            if (parts.size == 2 && parts[0].equals("primary", ignoreCase = true)) {
                val realPath = "${Environment.getExternalStorageDirectory().absolutePath}/${parts[1]}"
                if (File(realPath).exists()) {
                    android.media.MediaScannerConnection.scanFile(app, arrayOf(realPath), arrayOf(mimeType), null)
                }
            }
        } catch (_: Exception) { }
    }
}
