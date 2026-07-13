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

/**
 * mkvmerge / mkvextract (MKVToolNix) ikilileri APK'nın İÇİNE gömülüdür
 * (app/src/main/assets/mkvtoolnix/ - derleme sırasında scripts/fetch_mkvtoolnix_assets.py
 * tarafından Termux'un apt deposundan indirilip yerleştirilir, bkz. .github/workflows/build.yml).
 *
 * Uygulama Termux'a, internete veya kullanıcının herhangi bir kurulum yapmasına
 * İHTİYAÇ DUYMAZ: ilk kullanımda bu dosyalar assets'ten uygulamanın kendi private
 * dizinine (filesDir) kopyalanır, ikili dosyalar çalıştırılabilir yapılır ve
 * doğrudan ProcessBuilder ile (Windows'taki mkvmerge.exe gibi) çalıştırılır.
 */
object NativeTools {

    private const val ASSET_DIR = "mkvtoolnix"
    private const val MARKER_NAME = ".installed_v1" // asset içeriği değişirse bu sabiti artır

    data class ExecResult(val exitCode: Int, val output: String)

    private fun installDir(context: Context): File = File(context.filesDir, "mkvtoolnix")

    /**
     * assets/mkvtoolnix/* dosyalarını uygulamanın private dizinine çıkarır ve
     * mkvmerge/mkvextract'i çalıştırılabilir yapar. Zaten yapılmışsa hemen döner.
     * Bu tamamen offline bir dosya kopyalama işlemidir, ağ/izin gerektirmez.
     */
    fun ensureInstalled(context: Context): Boolean {
        val dir = installDir(context)
        val marker = File(dir, MARKER_NAME)
        if (marker.exists() && File(dir, "mkvmerge").canExecute()) return true

        return try {
            dir.mkdirs()
            val names = context.assets.list(ASSET_DIR)
            if (names.isNullOrEmpty()) return false

            for (name in names) {
                val out = File(dir, name)
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    out.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
                }
            }
            File(dir, "mkvmerge").setExecutable(true, false)
            File(dir, "mkvextract").setExecutable(true, false)
            marker.writeText("ok")
            File(dir, "mkvmerge").canExecute() && File(dir, "mkvextract").canExecute()
        } catch (_: Exception) {
            false
        }
    }

    fun mkvmergePath(context: Context): String = File(installDir(context), "mkvmerge").absolutePath
    fun mkvextractPath(context: Context): String = File(installDir(context), "mkvextract").absolutePath

    /**
     * mkvmerge/mkvextract'i doğrudan bir alt işlem olarak çalıştırır (Termux YOK, shell YOK).
     * Bağımlı .so dosyaları aynı dizinde olduğu için LD_LIBRARY_PATH o dizine ayarlanır.
     * mkvmerge'nin `--gui-mode` çıktısındaki `#GUI#progress NN%` satırları [onProgress] ile bildirilir.
     * Coroutine iptal edilirse (kullanıcı "İptal" derse) alt işlem de sonlandırılır.
     */
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
                try { process?.destroyForcibly() } catch (_: Exception) {}
            }
        }
        try {
            val pb = ProcessBuilder(listOf(binaryPath) + args)
            pb.directory(dir)
            pb.environment()["LD_LIBRARY_PATH"] = dir.absolutePath
            pb.redirectErrorStream(true)
            val p = pb.start()
            process = p

            val output = StringBuilder()
            val progressRegex = Regex("#GUI#progress\\s+(\\d+)%")
            var lastProgress = -1

            BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                var line: String?
                while (true) {
                    line = reader.readLine() ?: break
                    val l = line ?: break
                    output.append(l).append('\n')
                    if (onProgress != null) {
                        val m = progressRegex.find(l)
                        val pct = m?.groupValues?.get(1)?.toIntOrNull()
                        if (pct != null && pct != lastProgress) { lastProgress = pct; onProgress(pct) }
                    }
                }
            }
            val code = p.waitFor()
            ExecResult(code, output.toString().takeLast(4000))
        } catch (e: Exception) {
            ExecResult(-1, e.message ?: "İşlem başlatılamadı")
        } finally {
            cancelHandle?.dispose()
        }
    }
}
