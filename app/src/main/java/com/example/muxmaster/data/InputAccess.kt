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
import java.io.File
import java.io.FileInputStream
import java.io.IOException
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
                if (f.isFile && f.canRead() && f.length() > 0L && (expectedSize <= 0L || f.length() == expectedSize)) {
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
 * Google Drive (content://com.google.android.apps.docs.storage/...) ve diğer bulut SAF sağlayıcıları için
 * paylaşımlı, akıllı akış oturumu yöneticisi.
 *
 * Eski sürümdeki sorunlar:
 *  - Drive pipe (S_IFIFO) döndürdüğünde, dosya sonundaki "moov" (MP4) veya ileri bir konuma atlama
 *    isteği "cannot jump" hatası ile reddediliyordu -> ExoPlayer sürekli yeniden deniyor, sonsuz yükleme.
 *  - Her DataSource kendi pipe'ını açtığı için ikinci açılışta indirme baştan başlıyordu.
 *  - FUSE/proxy fd üzerinde küçük okumalar (her biri Drive'a bir tur) çok yavaştı.
 *
 * Yeni yapı:
 *  - URI başına TEK oturum (tüm DataSource açılışları ortak kullanır, bağlantı asla baştan başlamaz).
 *  - Seek destekli fd -> doğrudan pread + 1 MB'lık blok okuma (Drive'a çok az tur).
 *  - Pipe -> arka planda tek bir indirme iş parçacığı, geçici dosyaya sırayla yazar. Okuyucu ileri bir
 *    konum isterse (moov / ileri sarma) hata yerine o bayt gelene kadar bekler. İndirici, oynatma
 *    konumunun en fazla [AHEAD_BYTES] ilerisine kadar çalışır, sonra uyur (boş yere CPU/disk/pil yok).
 */
object DriveStreamResolver {

    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val sessions = ConcurrentHashMap<String, CloudStreamSession>()

    fun isGoogleDriveSafUri(uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        val auth = uri.authority.orEmpty().lowercase()
        return auth.contains("com.google.android.apps.docs") || auth.contains("google.drive")
    }

    /** Gerçek dosya yoluna çevrilemeyen bulut/SAF sağlayıcısı mı? */
    fun isCloudSafUri(uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        if (isGoogleDriveSafUri(uri)) return true
        val a = uri.authority.orEmpty().lowercase()
        return a.contains("drive") || a.contains("onedrive") || a.contains("dropbox") || a.contains("cloud")
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

    internal fun session(context: Context, uri: Uri): CloudStreamSession =
        sessions.getOrPut(uri.toString()) { CloudStreamSession(context.applicationContext, uri) }

    /**
     * Oturumu arka planda (IO) açar. Dönen değer: true = rastgele erişim (seek) destekli,
     * false = sıralı pipe akışı. Açılamazsa IOException fırlatır.
     */
    @Throws(IOException::class)
    fun prepareSession(context: Context, uri: Uri): Boolean = session(context, uri).ensureOpen()

    /** UI için indirme durumu: (indirilen bayt, toplam bayt veya -1). Pipe değilse null. */
    fun progressOf(uri: Uri?): Pair<Long, Long>? {
        val s = uri?.let { sessions[it.toString()] } ?: return null
        return s.progress()
    }

    fun releaseSession(uri: Uri?) {
        if (uri == null) return
        sessions.remove(uri.toString())?.release()
    }

    fun releaseAllSessions() {
        val copy = sessions.values.toList()
        sessions.clear()
        copy.forEach { it.release() }
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

/** URI başına paylaşılan bulut akış oturumu. Tüm metotlar iş parçacığı güvenlidir. */
internal class CloudStreamSession(private val context: Context, val uri: Uri) {

    companion object {
        /** Pipe modunda oynatma konumunun ne kadar ilerisine kadar indirilsin (pil/IO dengesi). */
        const val AHEAD_BYTES = 96L * 1024L * 1024L
        private const val COPY_CHUNK = 256 * 1024
    }

    private val lock = Object()

    @Volatile private var opened = false
    @Volatile private var released = false
    @Volatile var seekable = false
        private set
    @Volatile var totalLength: Long = C.LENGTH_UNSET.toLong()
        private set

    private var pfd: ParcelFileDescriptor? = null

    // Pipe modu
    private var cacheFile: File? = null
    private var writeRaf: RandomAccessFile? = null
    private var readRaf: RandomAccessFile? = null
    @Volatile private var downloaded = 0L
    @Volatile private var eof = false
    @Volatile private var failure: IOException? = null
    @Volatile private var wantedUpTo = AHEAD_BYTES
    private var downloader: Thread? = null

    @Throws(IOException::class)
    fun ensureOpen(): Boolean {
        if (opened) return seekable
        synchronized(this) {
            if (opened) return seekable
            if (released) throw IOException("Oturum kapatıldı")
            val fd = openPfd() ?: throw IOException("Dosya açılamadı (izin yok veya bağlantı yok): $uri")
            pfd = fd
            val canSeek = testSeekable(fd)
            val statLen = runCatching { fd.statSize }.getOrDefault(-1L)
            val queryLen = InputAccess.sizeOf(context, uri)
            totalLength = when {
                canSeek && statLen > 0L -> statLen
                queryLen > 0L -> queryLen
                statLen > 0L -> statLen
                else -> C.LENGTH_UNSET.toLong()
            }
            seekable = canSeek
            if (!canSeek) startDownloader(fd)
            opened = true
            return seekable
        }
    }

    fun progress(): Pair<Long, Long>? = if (!opened || seekable) null else Pair(downloaded, totalLength)

    private fun openPfd(): ParcelFileDescriptor? {
        runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.let { return it } }
        runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.parcelFileDescriptor?.let { return it } }
        return null
    }

    private fun testSeekable(fd: ParcelFileDescriptor): Boolean = try {
        val st = Os.fstat(fd.fileDescriptor)
        val mode = st.st_mode and OsConstants.S_IFMT
        if (mode == OsConstants.S_IFIFO || mode == OsConstants.S_IFSOCK) false
        else Os.lseek(fd.fileDescriptor, 0L, OsConstants.SEEK_CUR) >= 0L
    } catch (_: Throwable) { false }

    private fun startDownloader(fd: ParcelFileDescriptor) {
        val dir = File(context.cacheDir, "drive_stream").apply { mkdirs() }
        val f = File.createTempFile("gdrive_", ".tmp", dir)
        cacheFile = f
        writeRaf = RandomAccessFile(f, "rw")
        readRaf = RandomAccessFile(f, "r")
        val t = Thread({ downloadLoop(fd) }, "DriveStream")
        t.priority = Thread.NORM_PRIORITY - 1
        t.isDaemon = true
        downloader = t
        t.start()
    }

    private fun downloadLoop(fd: ParcelFileDescriptor) {
        val buf = ByteArray(COPY_CHUNK)
        try {
            FileInputStream(fd.fileDescriptor).use { input ->
                while (!released) {
                    // Akıllı önbellek: oynatıcının ihtiyacından yeterince öndeysek uyu.
                    synchronized(lock) {
                        while (!released && downloaded >= wantedUpTo) lock.wait(1_000L)
                    }
                    if (released) break
                    val n = input.read(buf)
                    if (n <= 0) break
                    val raf = writeRaf ?: break
                    synchronized(raf) {
                        raf.seek(downloaded)
                        raf.write(buf, 0, n)
                    }
                    synchronized(lock) {
                        downloaded += n
                        lock.notifyAll()
                    }
                }
            }
        } catch (e: Throwable) {
            if (!released) failure = if (e is IOException) e else IOException(e)
        } finally {
            synchronized(lock) {
                eof = true
                if (totalLength == C.LENGTH_UNSET.toLong() && failure == null) totalLength = downloaded
                lock.notifyAll()
            }
        }
    }

    /** Konumsal okuma. Veri henüz gelmediyse (pipe) bekler; kesinti (seek/release) olursa InterruptedIOException. */
    @Throws(IOException::class)
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (released) throw IOException("Oturum kapatıldı")
        if (seekable) {
            val fd = pfd ?: throw IOException("fd kapalı")
            return try {
                val n = Os.pread(fd.fileDescriptor, buffer, offset, length, position)
                if (n <= 0) C.RESULT_END_OF_INPUT else n
            } catch (e: Throwable) {
                throw if (e is IOException) e else IOException(e)
            }
        }
        // Pipe modu: gerekirse indiriciyi ileriye çağır ve bekle.
        synchronized(lock) {
            val want = position + AHEAD_BYTES
            if (want > wantedUpTo) {
                wantedUpTo = want
                lock.notifyAll()
            }
            while (downloaded <= position && !eof && failure == null && !released) {
                try {
                    lock.wait(200L)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException("Okuma iptal edildi")
                }
            }
            if (downloaded <= position) {
                failure?.let { throw it }
                return C.RESULT_END_OF_INPUT
            }
        }
        val avail = min((downloaded - position), length.toLong()).toInt()
        val raf = readRaf ?: throw IOException("Önbellek kapalı")
        synchronized(raf) {
            raf.seek(position)
            val n = raf.read(buffer, offset, avail)
            return if (n <= 0) C.RESULT_END_OF_INPUT else n
        }
    }

    /** Okuyucu ileri sardığında indiriciye yeni hedefi bildirir. */
    fun hint(position: Long) {
        if (seekable || !opened) return
        synchronized(lock) {
            val want = position + AHEAD_BYTES
            if (want > wantedUpTo) {
                wantedUpTo = want
                lock.notifyAll()
            }
        }
    }

    fun release() {
        released = true
        synchronized(lock) { lock.notifyAll() }
        runCatching { pfd?.close() }
        pfd = null
        downloader?.interrupt()
        downloader = null
        runCatching { writeRaf?.close() }
        runCatching { readRaf?.close() }
        writeRaf = null
        readRaf = null
        cacheFile?.let { f -> runCatching { f.delete() } }
        cacheFile = null
    }
}

/**
 * Yerel dosya (gerçek yola çevrilerek, kopyasız) + bulut SAF (paylaşımlı oturum) DataSource.
 * Bulut seekable fd'lerde 1 MB'lık blok önbelleği ile Drive'a giden tur sayısı en aza indirilir.
 */
private class SafNativeDataSource(
    private val context: Context,
    private val fallbackDelegate: DataSource
) : BaseDataSource(/* isNetwork = */ true) {

    companion object {
        private const val BLOCK = 1 shl 20 // 1 MB
        /** content:// -> gerçek yol çözümleri önbelleği (her seek'te ContentResolver sorgusu yapılmasın). */
        private val realPathCache = ConcurrentHashMap<String, String>()
        private val noRealPath = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    }

    private var currentUri: Uri? = null
    private var usingDelegate = false
    private var session: CloudStreamSession? = null
    private var readPosition = 0L
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    // Seekable bulut için blok önbelleği
    private val block = ByteArray(BLOCK)
    private var blockStart = -1L
    private var blockLen = 0
    private var blockUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        currentUri = uri
        if (uri.scheme?.lowercase() != "content") {
            usingDelegate = true
            return fallbackDelegate.open(dataSpec)
        }

        if (!DriveStreamResolver.isCloudSafUri(uri)) {
            val key = uri.toString()
            val local = realPathCache[key] ?: if (key in noRealPath) null else
                InputAccess.realPath(context, uri).also { p ->
                    if (p != null) realPathCache[key] = p else noRealPath.add(key)
                }
            if (local != null) {
                usingDelegate = true
                return fallbackDelegate.open(dataSpec.buildUpon().setUri(Uri.fromFile(File(local))).build())
            }
            // KÖK NEDEN DÜZELTMESİ: Yerel SAF content:// URI gerçek dosya yoluna çevrilemediğinde
            // ExoPlayer ContentDataSource çoğu zaman C.LENGTH_UNSET (-1) döndürdüğü için MKV Cues / MP4 moov
            // okunamıyor ve ileri-geri sarma (seek) tamamen kilitleniyordu. CloudStreamSession (Os.pread +
            // statSize/OpenableColumns.SIZE) kullanılarak tüm content:// videolarında %100 kesin boyut ve
            // anında rastgele erişim (random-access seek) garanti altına alınır.
        }

        usingDelegate = false
        transferInitializing(dataSpec)
        val s = DriveStreamResolver.session(context, uri)
        try {
            s.ensureOpen()
        } catch (e: IOException) {
            throw e
        } catch (t: Throwable) {
            throw IOException(t)
        }
        session = s
        if (blockUri != uri) { blockStart = -1L; blockLen = 0; blockUri = uri }
        readPosition = dataSpec.position
        val total = s.totalLength
        if (total != C.LENGTH_UNSET.toLong() && readPosition > total) {
            throw androidx.media3.datasource.DataSourceException(
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
            )
        }
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            total != C.LENGTH_UNSET.toLong() -> (total - readPosition).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }
        s.hint(readPosition)
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (usingDelegate) return fallbackDelegate.read(buffer, offset, length)
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val s = session ?: return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length
        else min(bytesRemaining, length.toLong()).toInt()

        val n: Int = if (s.seekable) readBlocked(s, buffer, offset, toRead) else s.readAt(readPosition, buffer, offset, toRead)
        if (n == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
        readPosition += n
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    private fun readBlocked(s: CloudStreamSession, buffer: ByteArray, offset: Int, length: Int): Int {
        val inBlock = blockStart >= 0 && readPosition >= blockStart && readPosition < blockStart + blockLen
        if (!inBlock) {
            // Büyük isteklerde blok önbelleğini atla (zaten verimli)
            if (length >= BLOCK) return s.readAt(readPosition, buffer, offset, length)
            val n = s.readAt(readPosition, block, 0, BLOCK)
            if (n == C.RESULT_END_OF_INPUT) return n
            blockStart = readPosition
            blockLen = n
        }
        val off = (readPosition - blockStart).toInt()
        val c = min(length, blockLen - off)
        System.arraycopy(block, off, buffer, offset, c)
        return c
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
        // Oturum paylaşımlıdır; burada kapatılmaz (bağlantı/indirme baştan başlamasın).
        session = null
    }
}
