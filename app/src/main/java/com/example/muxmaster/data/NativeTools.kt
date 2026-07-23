package com.example.muxmaster.data

import android.content.Context
import android.os.Process as AndroidProcess
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

            // KOK NEDEN (arka planda hala yavas kalabiliyordu): mkvmerge/mkvextract
            // ayri bir OS prosesi olarak fork+exec ile baslatiliyor ve nice (oncelik)
            // degerini baslatan thread'den miras alir. Uygulama arka plana alindiginda
            // varsayilan nice degeriyle baslayan bu processler, ayni cgroup icindeki
            // diger islerle CPU paylasiminda geride kalabiliyordu. Baslatmadan hemen
            // once bu thread'i izin verilen en yuksek oncelige (THREAD_PRIORITY_URGENT_AUDIO,
            // root gerektirmeyen en dusuk/en yuksek oncelikli nice degeri) cekiyoruz;
            // yeni process bu onceligi devralarak ayni cgroup icinde daha fazla CPU
            // zamani alir. Islem baslar baslamaz thread onceligini eski haline donduruyoruz
            // ki IO thread pool'daki bu thread baska islerde etkilenmesin.
            val callerTid = AndroidProcess.myTid()
            val previousPriority = runCatching { AndroidProcess.getThreadPriority(callerTid) }.getOrDefault(AndroidProcess.THREAD_PRIORITY_DEFAULT)
            runCatching { AndroidProcess.setThreadPriority(callerTid, AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO) }
            val p = try {
                pb.start()
            } finally {
                runCatching { AndroidProcess.setThreadPriority(callerTid, previousPriority) }
            }
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
