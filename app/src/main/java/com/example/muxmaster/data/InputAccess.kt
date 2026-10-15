package com.example.muxmaster.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Girdi dosyalarına KOPYALAMADAN erişim.
 */
object InputAccess {
    private val open = ConcurrentHashMap<String, ParcelFileDescriptor>()
    /** Kullanıcının asıl dosyaları: release() bunları ASLA silmez. */
    private val realPaths: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    fun hasStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Okunabilir bir dosya yolu döndürür (kopyasız veya kopyalı); olmazsa null. */
    fun acquire(context: Context, uri: Uri, copyDir: File, copyName: String, needChildRead: Boolean): String? {
        realPath(context, uri)?.let { realPaths.add(it); return it }
        direct(context, uri, needChildRead)?.let { return it }
        return copyToCache(context, uri, copyDir, copyName)
    }

    /** URI'yi cihazdaki gerçek dosya yoluna çevirir (okunabiliyorsa). */
    fun realPath(context: Context, uri: Uri): String? {
        val candidates = mutableListOf<String>()
        try {
            when {
                uri.scheme == "file" -> uri.path?.let { candidates += it }
                DocumentsContract.isDocumentUri(context, uri) -> {
                    val docId = DocumentsContract.getDocumentId(uri)
                    when (uri.authority) {
                        "com.android.externalstorage.documents" -> {
                            val parts = docId.split(":", limit = 2)
                            if (parts.size == 2) {
                                if (parts[0].equals("primary", true))
                                    candidates += "${Environment.getExternalStorageDirectory().absolutePath}/${parts[1]}"
                                else candidates += "/storage/${parts[0]}/${parts[1]}"
                            }
                        }
                        "com.android.providers.downloads.documents" -> {
                            if (docId.startsWith("raw:")) candidates += docId.removePrefix("raw:")
                            else if (docId.startsWith("msf:")) {
                                docId.removePrefix("msf:").toLongOrNull()?.let { id ->
                                    queryData(context, ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id))?.let { candidates += it }
                                }
                            } else docId.toLongOrNull()?.let { id ->
                                queryData(context, ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), id))?.let { candidates += it }
                            }
                        }
                        "com.android.providers.media.documents" -> {
                            val parts = docId.split(":", limit = 2)
                            val id = parts.getOrNull(1)?.toLongOrNull()
                            if (id != null) {
                                val base = when (parts[0]) {
                                    "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                    "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                                    "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                                    else -> MediaStore.Files.getContentUri("external")
                                }
                                queryData(context, ContentUris.withAppendedId(base, id))?.let { candidates += it }
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) { }

        if (uri.scheme == "content") {
            queryData(context, uri)?.let { candidates += it }
            val decoded = Uri.decode(uri.toString())
            for (marker in listOf("/storage/", "/sdcard/", "/mnt/")) {
                val i = decoded.indexOf(marker)
                if (i >= 0) candidates += decoded.substring(i).substringBefore('?')
            }
            uri.path?.let { p ->
                val ext = Environment.getExternalStorageDirectory().absolutePath
                val segs = p.trimStart('/').split('/', limit = 2)
                if (segs.size == 2) candidates += "$ext/${segs[1]}"
            }
        }

        val expectedSize = sizeOf(context, uri)
        for (c in candidates) {
            try {
                val f = File(c)
                if (f.isFile && f.canRead() && (expectedSize <= 0L || f.length() == expectedSize)) {
                    FileInputStream(f).use { it.read() }
                    return f.absolutePath
                }
            } catch (_: Throwable) { }
        }
        return null
    }

    private fun queryData(context: Context, uri: Uri): String? = try {
        @Suppress("DEPRECATION")
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (idx >= 0) c.getString(idx)?.takeIf { it.isNotBlank() } else null
            } else null
        }
    } catch (_: Throwable) { null }

    fun sizeOf(context: Context, uri: Uri): Long = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else -1L
            } else -1L
        } ?: -1L
    } catch (_: Throwable) { -1L }

    private fun direct(context: Context, uri: Uri, needChildRead: Boolean): String? {
        var pfd: ParcelFileDescriptor? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val st = Os.fstat(pfd.fileDescriptor)
            if ((st.st_mode and OsConstants.S_IFMT) != OsConstants.S_IFREG) {
                pfd.close(); return null
            }
            val path = "/proc/${Process.myPid()}/fd/${pfd.fd}"
            FileInputStream(path).use { it.read() }
            if (needChildRead && !childCanRead(path)) { pfd.close(); return null }
            open[path] = pfd
            path
        } catch (_: Throwable) {
            runCatching { pfd?.close() }
            null
        }
    }

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

    fun release(path: String?) {
        if (path.isNullOrBlank()) return
        if (realPaths.remove(path)) return
        val pfd = open.remove(path)
        if (pfd != null) { runCatching { pfd.close() }; return }
        if (path.contains("/cache/")) runCatching { File(path).delete() }
    }

    fun length(path: String): Long {
        val pfd = open[path]
        if (pfd != null) {
            val s = runCatching { pfd.statSize }.getOrDefault(-1L)
            if (s > 0) return s
        }
        return File(path).length()
    }

    fun clearWorkCache(context: Context) {
        runCatching {
            context.cacheDir.listFiles()?.forEach { f ->
                runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() }
            }
        }
    }
}

/**
 * Cihazda zaten açık olan Google Drive hesaplarından (content://com.google.android.apps.docs.storage/...)
 * veya yerel SAF sağlayıcılarından seçilen videoları HİÇBİR WebView / "Oturum Açın" ekranına yönlendirmeden,
 * doğrudan Android ContentResolver ve ParcelFileDescriptor üzerinden oynatan yerel akış motoru.
 *
 * Neden Media3'ün varsayılan ContentDataSource'u Google Drive'da takılıyordu?
 * 1) Google Drive uygulaması önbellekte olmayan büyük videoları pipe (S_IFIFO) olarak akıtır.
 * 2) Android'de FileInputStream.skip() ve FileChannel.position() pipe üzerinde Os.lseek() çağırır ve
 *    "ErrnoException: lseek failed: ESPIPE (Illegal seek)" fırlatarak oynatmayı çökertir.
 * 3) Bu özel SafNativeDataSource:
 *    - Dosya tanımlayıcısı gerçek seek destekliyorsa (S_IFREG / FUSE ProxyFileDescriptor), Os.pread / Os.lseek
 *      ile 0 ms'de doğrudan okur.
 *    - Pipe (S_IFIFO) ise ASLA lseek veya FileInputStream.skip() çağırmaz; akışı kesmeden okur ve
 *      okunan baytları geçici bir RandomAccessFile önbelleğinde tutarak başlık geri dönüşlerini anında yanıtlar.
 */
object DriveStreamResolver {

    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val activeTempFiles = Collections.newSetFromMap(ConcurrentHashMap<File, Boolean>())

    fun isGoogleDriveSafUri(uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        val auth = uri.authority.orEmpty().lowercase()
        return auth.contains("com.google.android.apps.docs") || auth.contains("google.drive")
    }

    fun isGoogleDriveUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("drive.google.com") ||
            lower.contains("drive.usercontent.google.com") ||
            lower.contains("docs.google.com/file")
    }

    fun extractDriveFileId(input: String): String? {
        val s = Uri.decode(input).trim()
        Regex("""/(?:file/)?d/([a-zA-Z0-9_-]{18,64})""").find(s)?.groupValues?.getOrNull(1)?.let { id ->
            if (id.length >= 18) return id
        }
        Regex("""[?&]id=([a-zA-Z0-9_-]{18,64})""").find(s)?.groupValues?.getOrNull(1)?.let { id ->
            if (id.length >= 18) return id
        }
        return null
    }

    fun releaseSession(uri: Uri?) {
        // Temp files are cleaned up on player release or releaseAllSessions
    }

    fun releaseAllSessions() {
        val copy = activeTempFiles.toList()
        activeTempFiles.clear()
        for (f in copy) {
            runCatching { f.delete() }
        }
    }

    internal fun registerTempFile(f: File) {
        activeTempFiles.add(f)
    }

    internal fun unregisterTempFile(f: File) {
        activeTempFiles.remove(f)
        runCatching { f.delete() }
    }

    fun buildDataSourceFactory(context: Context): DataSource.Factory {
        val appCtx = context.applicationContext
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(DEFAULT_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
        val defaultFactory = DefaultDataSource.Factory(appCtx, httpFactory)

        return DataSource.Factory {
            SafNativeDataSource(appCtx, defaultFactory.createDataSource())
        }
    }
}

/**
 * Her ExoPlayer örneği için bağımsız oluşturulan (4'lü oynatmada birbirini asla bloklamayan)
 * yüksek hızlı yerel dosya + Google Drive SAF Pipe/FUSE DataSource.
 */
private class SafNativeDataSource(
    private val context: Context,
    private val fallbackDelegate: DataSource
) : BaseDataSource(/* isNetwork = */ false) {

    private var currentUri: Uri? = null
    private var usingDelegate: Boolean = false

    // Seekable regular file or FUSE proxy descriptor state
    private var seekablePfd: ParcelFileDescriptor? = null
    private var seekableTotalLength: Long = C.LENGTH_UNSET.toLong()
    private var isSeekableFd: Boolean = false

    // Sequential pipe stream + local random-access tee cache for non-seekable Google Drive pipes
    private var pipePfd: ParcelFileDescriptor? = null
    private var pipeStream: InputStream? = null
    private var pipeCacheFile: File? = null
    private var pipeCacheRaf: RandomAccessFile? = null
    private var pipeDownloadedBytes: Long = 0L
    private var pipeEofReached: Boolean = false
    private val skipScratch = ByteArray(64 * 1024)

    private var readPosition: Long = 0L
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val scheme = uri.scheme?.lowercase()

        // HTTP/HTTPS veya file:// doğrudan optimize edilmiş varsayılan kaynağa gider
        if (scheme != "content") {
            usingDelegate = true
            currentUri = uri
            return fallbackDelegate.open(dataSpec)
        }

        // Yerel depolamadaki bir content:// URI ise ve gerçek dosya yoluna çevrilebiliyorsa doğrudan File olarak aç
        if (!DriveStreamResolver.isGoogleDriveSafUri(uri)) {
            val localPath = InputAccess.realPath(context, uri)
            if (localPath != null) {
                val fileSpec = dataSpec.buildUpon().setUri(Uri.fromFile(File(localPath))).build()
                usingDelegate = true
                currentUri = uri
                return fallbackDelegate.open(fileSpec)
            }
        }

        usingDelegate = false
        transferInitializing(dataSpec)

        try {
            // Farklı bir URI'ye geçildiyse önceki kaynakları temizle
            if (currentUri != uri) {
                releaseNativeResources()
                currentUri = uri
            }

            // Henüz açılmamışsa ParcelFileDescriptor aç ve seekable olup olmadığını test et
            if (seekablePfd == null && pipeStream == null) {
                val pfd = openContentPfd(uri)
                    ?: throw IOException("Dosya açılamadı: $uri")

                val canSeek = testFdSeekable(pfd)
                if (canSeek) {
                    seekablePfd = pfd
                    isSeekableFd = true
                    val statLen = runCatching { pfd.statSize }.getOrDefault(-1L)
                    val stLen = runCatching { Os.fstat(pfd.fileDescriptor).st_size }.getOrDefault(-1L)
                    val queryLen = InputAccess.sizeOf(context, uri)
                    seekableTotalLength = when {
                        statLen > 0L -> statLen
                        stLen > 0L -> stLen
                        queryLen > 0L -> queryLen
                        else -> C.LENGTH_UNSET.toLong()
                    }
                } else {
                    // Pipe (S_IFIFO) veya seek desteklemeyen bulut akışı:
                    // ASLA Os.lseek veya FileInputStream.skip() çağırma!
                    isSeekableFd = false
                    pipePfd = pfd
                    pipeStream = BufferedInputStream(FileInputStream(pfd.fileDescriptor), 512 * 1024)
                    val tmp = File.createTempFile("gdrive_stream_", ".tmp", context.cacheDir)
                    DriveStreamResolver.registerTempFile(tmp)
                    pipeCacheFile = tmp
                    pipeCacheRaf = RandomAccessFile(tmp, "rw")
                    pipeDownloadedBytes = 0L
                    pipeEofReached = false
                }
            }

            readPosition = dataSpec.position

            if (isSeekableFd) {
                val pfd = seekablePfd ?: throw IOException("Seekable PFD kayboldu")
                if (seekableTotalLength != C.LENGTH_UNSET.toLong() && readPosition > seekableTotalLength) {
                    throw EOFException("Position out of range: $readPosition > $seekableTotalLength")
                }
                bytesRemaining = when {
                    dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                    seekableTotalLength != C.LENGTH_UNSET.toLong() -> (seekableTotalLength - readPosition).coerceAtLeast(0L)
                    else -> C.LENGTH_UNSET.toLong()
                }
            } else {
                // Pipe akışında eğer istenen pozisyon henüz indirilmemiş bir ileri noktaysa:
                // Makul bir ileri atlama ise (<= 16 MB) pipe'dan okuyarak ilerle;
                // çok uzaktaysa (ör. 1.1 GB dosyanın sonundaki Cues tablosu) EOF/LENGTH_UNSET döndür ki oynatıcı takılmasın!
                val ahead = readPosition - pipeDownloadedBytes
                if (ahead > 0L) {
                    if (ahead <= 16L * 1024L * 1024L) {
                        drainPipeUpTo(readPosition)
                    } else {
                        throw IOException("Sequential stream cannot jump ${ahead / (1024 * 1024)} MB ahead")
                    }
                }
                bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    dataSpec.length
                } else {
                    // C.LENGTH_UNSET döndürerek MatroskaExtractor ve Mp4Extractor'ın dosya sonuna
                    // (1+ GB ilerisine) atlamasını engelliyoruz; 0. bayttan anında oynatır!
                    C.LENGTH_UNSET.toLong()
                }
            }

            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (e: IOException) {
            throw e
        } catch (t: Throwable) {
            throw IOException(t)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (usingDelegate) {
            return fallbackDelegate.read(buffer, offset, length)
        }
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            min(bytesRemaining, length.toLong()).toInt()
        }

        try {
            val bytesRead: Int = if (isSeekableFd) {
                val pfd = seekablePfd ?: return C.RESULT_END_OF_INPUT
                val n = try {
                    Os.pread(pfd.fileDescriptor, buffer, offset, toRead, readPosition)
                } catch (_: Throwable) {
                    Os.lseek(pfd.fileDescriptor, readPosition, OsConstants.SEEK_SET)
                    Os.read(pfd.fileDescriptor, buffer, offset, toRead)
                }
                if (n <= 0) C.RESULT_END_OF_INPUT else n
            } else {
                readFromPipeOrCache(buffer, offset, toRead)
            }

            if (bytesRead == C.RESULT_END_OF_INPUT) {
                return C.RESULT_END_OF_INPUT
            }

            readPosition += bytesRead
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                bytesRemaining -= bytesRead
            }
            bytesTransferred(bytesRead)
            return bytesRead
        } catch (e: IOException) {
            throw e
        } catch (t: Throwable) {
            throw IOException(t)
        }
    }

    private fun readFromPipeOrCache(buffer: ByteArray, offset: Int, toRead: Int): Int {
        val raf = pipeCacheRaf
        val ins = pipeStream
        if (raf == null || ins == null) return C.RESULT_END_OF_INPUT

        // 1) İstenen pozisyon zaten önbelleğe alındıysa doğrudan yerel geçici dosyadan (0.01ms) oku
        if (readPosition < pipeDownloadedBytes) {
            val availableInCache = (pipeDownloadedBytes - readPosition).coerceAtMost(toRead.toLong()).toInt()
            raf.seek(readPosition)
            val n = raf.read(buffer, offset, availableInCache)
            if (n > 0) return n
        }

        // 2) Eğer okuma pozisyonu önbelleğin biraz ilerisindeyse aradaki boşluğu pipe'dan doldur
        if (readPosition > pipeDownloadedBytes && !pipeEofReached) {
            drainPipeUpTo(readPosition)
        }

        if (pipeEofReached && readPosition >= pipeDownloadedBytes) {
            return C.RESULT_END_OF_INPUT
        }

        // 3) Pipe'dan yeni veri oku ve aynı anda geçici dosyaya yaz
        val n = ins.read(buffer, offset, toRead)
        if (n <= 0) {
            pipeEofReached = true
            return C.RESULT_END_OF_INPUT
        }
        raf.seek(pipeDownloadedBytes)
        raf.write(buffer, offset, n)
        pipeDownloadedBytes += n
        return n
    }

    private fun drainPipeUpTo(targetPosition: Long) {
        val raf = pipeCacheRaf ?: return
        val ins = pipeStream ?: return
        while (pipeDownloadedBytes < targetPosition && !pipeEofReached) {
            val need = min((targetPosition - pipeDownloadedBytes), skipScratch.size.toLong()).toInt()
            val n = ins.read(skipScratch, 0, need)
            if (n <= 0) {
                pipeEofReached = true
                break
            }
            raf.seek(pipeDownloadedBytes)
            raf.write(skipScratch, 0, n)
            pipeDownloadedBytes += n
        }
    }

    override fun getUri(): Uri? = if (usingDelegate) fallbackDelegate.uri else currentUri

    override fun close() {
        if (usingDelegate) {
            usingDelegate = false
            fallbackDelegate.close()
            return
        }
        if (opened) {
            opened = false
            transferEnded()
        }
        // Not: seekablePfd ve pipeStream aynı URI üzerindeki ardışık open/close çağrıları
        // (ör. başlık okuma sonrası veri okuma) arasında açık tutulur; böylece Google Drive
        // bağlantısı kopmaz ve yeniden bağlanma gecikmesi yaşanmaz!
    }

    fun releaseNativeResources() {
        runCatching { seekablePfd?.close() }
        seekablePfd = null
        runCatching { pipeStream?.close() }
        pipeStream = null
        runCatching { pipePfd?.close() }
        pipePfd = null
        runCatching { pipeCacheRaf?.close() }
        pipeCacheRaf = null
        pipeCacheFile?.let { DriveStreamResolver.unregisterTempFile(it) }
        pipeCacheFile = null
        pipeDownloadedBytes = 0L
        pipeEofReached = false
    }

    protected fun finalize() {
        releaseNativeResources()
    }

    private fun openContentPfd(uri: Uri): ParcelFileDescriptor? {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.let { return it }
        }
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.parcelFileDescriptor?.let { return it }
        }
        return null
    }

    private fun testFdSeekable(pfd: ParcelFileDescriptor): Boolean {
        return try {
            val st = Os.fstat(pfd.fileDescriptor)
            val mode = st.st_mode and OsConstants.S_IFMT
            if (mode == OsConstants.S_IFIFO || mode == OsConstants.S_IFSOCK) {
                false
            } else {
                val pos = Os.lseek(pfd.fileDescriptor, 0L, OsConstants.SEEK_CUR)
                pos >= 0L
            }
        } catch (_: Throwable) {
            false
        }
    }
}
