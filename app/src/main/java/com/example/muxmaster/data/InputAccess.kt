package com.example.muxmaster.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Girdi dosyalarına KOPYALAMADAN erişim.
 *
 * Seçilen dosyanın dosya tanımlayıcısı (fd) açık tutulur ve `/proc/<pid>/fd/<n>` yolu
 * ffmpeg / ffprobe / mkvmerge'e doğrudan verilir. Böylece büyük videolar önbelleğe
 * kopyalanmaz, bekleme olmaz. Bu yol bu cihazda/dosyada çalışmıyorsa (ör. bulut sağlayıcı,
 * kısıtlı depolama) otomatik olarak eski yönteme (önbelleğe kopyalama) düşülür.
 */
object InputAccess {
    private val open = ConcurrentHashMap<String, ParcelFileDescriptor>()

    /** Okunabilir bir dosya yolu döndürür (kopyasız veya kopyalı); olmazsa null. */
    fun acquire(context: Context, uri: Uri, copyDir: File, copyName: String, needChildRead: Boolean): String? {
        direct(context, uri, needChildRead)?.let { return it }
        return copyToCache(context, uri, copyDir, copyName)
    }

    private fun direct(context: Context, uri: Uri, needChildRead: Boolean): String? {
        var pfd: ParcelFileDescriptor? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val st = Os.fstat(pfd.fileDescriptor)
            if ((st.st_mode and OsConstants.S_IFMT) != OsConstants.S_IFREG) {
                pfd.close(); return null
            }
            val path = "/proc/${Process.myPid()}/fd/${pfd.fd}"
            // Aynı yoldan yeniden açılabiliyor mu? (Scoped storage bunu engelleyebilir.)
            FileInputStream(path).use { it.read() }
            if (needChildRead && !childCanRead(path)) { pfd.close(); return null }
            open[path] = pfd
            path
        } catch (_: Throwable) {
            runCatching { pfd?.close() }
            null
        }
    }

    /** mkvmerge gibi alt süreçler aynı yolu okuyabiliyor mu? */
    private fun childCanRead(path: String): Boolean = try {
        val p = ProcessBuilder("/system/bin/head", "-c", "1", path).redirectErrorStream(true).start()
        p.inputStream.use { it.readBytes() }
        p.waitFor() == 0
    } catch (_: Throwable) { false }

    private fun copyToCache(context: Context, uri: Uri, dir: File, name: String): String? {
        return try {
            dir.mkdirs()
            val f = File(dir, name)
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { i -> f.outputStream().use { o -> i.copyTo(o, 8 * 1024 * 1024) } }
            if (f.exists() && f.length() > 0) f.absolutePath else null
        } catch (_: Exception) { null }
    }

    /** Kopyasız yolsa fd'yi kapatır; kopya dosyaysa siler. */
    fun release(path: String?) {
        if (path.isNullOrBlank()) return
        val pfd = open.remove(path)
        if (pfd != null) { runCatching { pfd.close() }; return }
        runCatching { File(path).delete() }
    }

    fun length(path: String): Long {
        val pfd = open[path]
        if (pfd != null) {
            val s = runCatching { pfd.statSize }.getOrDefault(-1L)
            if (s > 0) return s
        }
        return File(path).length()
    }

    /** Uygulama önbelleğindeki tüm geçici iş klasörlerini temizler. */
    fun clearWorkCache(context: Context) {
        runCatching {
            context.cacheDir.listFiles()?.forEach { f ->
                // Açık (kopyasız) girdiler önbellekte durmaz; geri kalan her şey geçicidir.
                runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() }
            }
        }
    }
}
