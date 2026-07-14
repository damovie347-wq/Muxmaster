package com.example.muxmaster.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.coroutines.coroutineContext

// mkvmerge / mkvextract MKVToolNix ikilileri APK icine gomuludur.
// app/src/main/assets/mkvtoolnix altina, derleme sirasinda
// scripts/fetch_mkvtoolnix_assets.py tarafindan yerlestirilir.
// Uygulama internete veya baska bir kuruluma ihtiyac duymaz: ilk kullanimda
// bu dosyalar assets icinden uygulamanin kendi private dizinine kopyalanir,
// calistirilabilir yapilir ve ProcessBuilder ile dogrudan calistirilir.
object NativeTools {

    private const val ASSET_DIR = "mkvtoolnix"
    private const val MARKER_NAME = ".installed_v1"

    data class ExecResult(val exitCode: Int, val output: String)

    private fun installDir(context: Context): File {
        return File(context.filesDir, "mkvtoolnix")
    }

    fun ensureInstalled(context: Context): Boolean {
        val dir = installDir(context)
        val marker = File(dir, MARKER_NAME)
        if (marker.exists() && File(dir, "mkvmerge").canExecute()) {
            return true
        }

        return try {
            dir.mkdirs()
            val names = context.assets.list(ASSET_DIR)
            if (names.isNullOrEmpty()) {
                return false
            }

            for (name in names) {
                val out = File(dir, name)
                val input = context.assets.open(ASSET_DIR + "/" + name)
                input.use { i ->
                    out.outputStream().use { o ->
                        i.copyTo(o, 1048576)
                    }
                }
            }
            File(dir, "mkvmerge").setExecutable(true, false)
            File(dir, "mkvextract").setExecutable(true, false)
            marker.writeText("ok")
            File(dir, "mkvmerge").canExecute() && File(dir, "mkvextract").canExecute()
        } catch (e: Exception) {
            false
        }
    }

    fun mkvmergePath(context: Context): String {
        return File(installDir(context), "mkvmerge").absolutePath
    }

    fun mkvextractPath(context: Context): String {
        return File(installDir(context), "mkvextract").absolutePath
    }

    suspend fun run(
        context: Context,
        binaryPath: String,
        args: List<String>,
        onProgress: ((Int) -> Unit)? = null
    ): ExecResult = withContext(Dispatchers.IO) {
        val dir = installDir(context)
        var process: Process? = null
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                try {
                    process?.destroyForcibly()
                } catch (e: Exception) {
                }
            }
        }
        try {
            val fullCommand = mutableListOf(binaryPath)
            fullCommand.addAll(args)
            val pb = ProcessBuilder(fullCommand)
            pb.directory(dir)
            pb.environment().put("LD_LIBRARY_PATH", dir.absolutePath)
            pb.redirectErrorStream(true)
            val p = pb.start()
            process = p

            val output = StringBuilder()
            val progressRegex = Regex("progress\\s+(\\d+)%")
            var lastProgress = -1

            val reader = BufferedReader(InputStreamReader(p.inputStream))
            reader.use { r ->
                var line: String?
                while (true) {
                    line = r.readLine()
                    if (line == null) {
                        break
                    }
                    val l = line as String
                    output.append(l)
                    output.append("\n")
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
