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

// mkvmerge / mkvextract MKVToolNix ikilileri APK icine "native library" olarak
// gomuludur (app/src/main/jniLibs/arm64-v8a/ - CI derleme sirasinda
// scripts/fetch_mkvtoolnix_assets.py tarafindan yerlestirilir, libmkvmerge.so
// ve libmkvextract.so adlariyla).
//
// ONEMLI: Android 10+ (API 29+), uygulamanin KENDI YAZDIGI (assets'ten kopyalayip
// chmod +x yaptigi) dosyalari calistirmasini guvenlik geregi ENGELLER (W^X kurali,
// "Permission denied" / error=13 ile sonuclanir). Bu kisitlama SADECE APK kurulurken
// sistemin kendisinin cikardigi native library dizinini (nativeLibraryDir) muaf
// tutar. Bu yuzden dosyalar assets yerine jniLibs ile gomulur; ekstra kopyalama/
// chmod adimina hic gerek yoktur, Android bunu kurulumda otomatik yapar.
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
                } catch (e: Exception) {
                }
            }
        }
        try {
            val fullCommand = mutableListOf(binaryPath)
            fullCommand.addAll(args)
            val pb = ProcessBuilder(fullCommand)
            pb.directory(context.filesDir)
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
