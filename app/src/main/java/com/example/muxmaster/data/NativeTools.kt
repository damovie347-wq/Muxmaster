package com.example.muxmaster.data

import android.content.Context
import android.os.Process as AndroidProcess
import com.example.muxmaster.service.MuxForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.coroutines.coroutineContext

// mkvmerge / mkvextract (MKVToolNix), exhale (gerçek ISO/IEC 23003-3 USAC / xHE-AAC mp4a-40-42
// kodlayıcı) ve opusenc (Termux resmi libopus tabanlı temiz Ogg/Opus kodlayıcı) ikilileri
// APK içine "native library" olarak gömülüdür (app/src/main/jniLibs/arm64-v8a/ - CI derleme
// sırasında scripts/fetch_mkvtoolnix_assets.py tarafından yerleştirilir).
object NativeTools {

    data class ExecResult(val exitCode: Int, val output: String)

    private fun libDir(context: Context): File {
        return File(context.applicationInfo.nativeLibraryDir)
    }

    fun ensureInstalled(context: Context): Boolean {
        val dir = libDir(context)
        return File(dir, "libmkvmerge.so").canExecute() && File(dir, "libmkvextract.so").canExecute()
    }

    fun mkvmergePath(context: Context): String {
        return File(libDir(context), "libmkvmerge.so").absolutePath
    }

    fun mkvextractPath(context: Context): String {
        return File(libDir(context), "libmkvextract.so").absolutePath
    }

    fun exhalePath(context: Context): String {
        return File(libDir(context), "libexhale.so").absolutePath
    }

    fun hasExhale(context: Context): Boolean {
        return File(libDir(context), "libexhale.so").canExecute()
    }

    fun opusencPath(context: Context): String {
        return File(libDir(context), "libopusenc.so").absolutePath
    }

    fun hasOpusenc(context: Context): Boolean {
        return File(libDir(context), "libopusenc.so").canExecute()
    }

    suspend fun run(
        context: Context,
        binaryPath: String,
        args: List<String>,
        onProgress: ((Int) -> Unit)? = null
    ): ExecResult = withContext(Dispatchers.IO) {
        val dir = libDir(context)
        var process: Process? = null
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                try {
                    process?.destroyForcibly()
                } catch (_: Exception) { }
            }
        }
        try {
            val callerTid = AndroidProcess.myTid()
            val previousPriority = runCatching { AndroidProcess.getThreadPriority(callerTid) }
                .getOrDefault(AndroidProcess.THREAD_PRIORITY_DEFAULT)
            runCatching { AndroidProcess.setThreadPriority(callerTid, AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO) }
            MuxForegroundService.boostAllProcessThreads()

            val fullCommand = mutableListOf(binaryPath)
            fullCommand.addAll(args)

            val pb = ProcessBuilder(fullCommand)
            pb.directory(context.filesDir)
            pb.environment().put("LD_LIBRARY_PATH", dir.absolutePath)
            pb.redirectErrorStream(true)

            val p = try {
                pb.start()
            } finally {
                runCatching { AndroidProcess.setThreadPriority(callerTid, previousPriority) }
            }
            process = p

            // Arka plandayken işletim sisteminin yeni alt süreci yavaş çekirdeklere (0..3)
            // hapsetmesini önlemek için tüm çekirdeklerin (0xff) afinitesini aç.
            runCatching {
                val tasksetBin = File("/system/bin/taskset")
                if (tasksetBin.canExecute()) {
                    val pidField = p.javaClass.getDeclaredField("pid").apply { isAccessible = true }
                    val childPid = pidField.getInt(p)
                    if (childPid > 0) {
                        ProcessBuilder("/system/bin/taskset", "-p", "-a", "ff", childPid.toString())
                            .redirectErrorStream(true)
                            .start()
                    }
                }
            }

            val output = StringBuilder()
            val progressRegex = Regex("progress\\s+(\\d+)%")
            var lastProgress = -1

            val reader = BufferedReader(InputStreamReader(p.inputStream))
            reader.use { r ->
                var line: String?
                while (true) {
                    line = r.readLine()
                    if (line == null) break
                    val l = line
                    output.append(l).append("\n")
                    if (onProgress != null) {
                        val m = progressRegex.find(l)
                        val pct = m?.groupValues?.get(1)?.toIntOrNull()
                        if (pct != null && pct != lastProgress) {
                            lastProgress = pct
                            onProgress(pct)
                        }
                    }
                }
            }
            val code = p.waitFor()
            ExecResult(code, output.toString().takeLast(4000))
        } catch (e: Exception) {
            ExecResult(-1, e.message ?: "islem baslatilamadi")
        } finally {
            cancelHandle?.dispose()
        }
    }
}
