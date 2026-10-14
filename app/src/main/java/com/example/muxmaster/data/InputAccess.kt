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
import android.webkit.CookieManager
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
 * Google Drive SAF (content://com.google.android.apps.docs.storage/...) ve bulut URI'lerini
 * tarayıcıya yönlendirmeden ve tüm dosyanın inmesini beklemeden doğrudan oynatan akış katmanı.
 */
object DriveStreamResolver {

    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    data class ResolvedDriveStream(
        val streamUrl: String,
        val title: String? = null
    )

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
            if (isValidDriveId(id)) return id
        }
        Regex("""[?&]id=([a-zA-Z0-9_-]{18,64})""").find(s)?.groupValues?.getOrNull(1)?.let { id ->
            if (isValidDriveId(id)) return id
        }
        if (s.matches(Regex("""^[a-zA-Z0-9_-]{25,50}$""")) && isValidDriveId(s)) {
            return s
        }
        return null
    }

    private fun isValidDriveId(id: String): Boolean {
        if (id.length < 18) return false
        val lower = id.lowercase()
        return lower !in setOf("my-drive", "shared-with-me", "recent", "starred", "trash", "computers", "search", "folders")
    }

    fun getCombinedCookiesForUrl(url: String): String {
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return ""
        val cookies = linkedMapOf<String, String>()
        fun collectFrom(domainUrl: String) {
            val raw = runCatching { cm.getCookie(domainUrl) }.getOrNull().orEmpty()
            for (part in raw.split(";")) {
                val trimmed = part.trim()
                val eq = trimmed.indexOf('=')
                if (eq > 0) {
                    val k = trimmed.substring(0, eq).trim()
                    val v = trimmed.substring(eq + 1).trim()
                    if (k.isNotEmpty()) cookies[k] = v
                }
            }
        }
        collectFrom("https://www.google.com")
        collectFrom("https://drive.google.com")
        collectFrom("https://drive.usercontent.google.com")
        collectFrom(url)
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    suspend fun resolveDriveFileIdToStream(fileId: String): ResolvedDriveStream? = withContext(Dispatchers.IO) {
        val candidates = listOf(
            "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t",
            "https://drive.google.com/uc?id=$fileId&export=download&confirm=t"
        )
        for (startUrl in candidates) {
            probeAndResolveStreamUrl(startUrl, fileId)?.let { return@withContext it }
        }
        null
    }

    private fun probeAndResolveStreamUrl(startUrl: String, fileId: String): ResolvedDriveStream? {
        val cm = runCatching { CookieManager.getInstance() }.getOrNull()
        var currentUrl = startUrl
        var hops = 0

        while (hops < 8) {
            hops++
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
                    setRequestProperty("Range", "bytes=0-1")
                    val cookie = getCombinedCookiesForUrl(currentUrl)
                    if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
                }
                conn.connect()

                conn.headerFields?.forEach { (key, values) ->
                    if (key != null && key.equals("Set-Cookie", ignoreCase = true)) {
                        values?.forEach { cVal ->
                            runCatching { cm?.setCookie(currentUrl, cVal) }
                        }
                    }
                }
                runCatching { cm?.flush() }

                val code = conn.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    val nextUrl = URL(URL(currentUrl), loc).toString()
                    if (nextUrl.contains("accounts.google.com") || nextUrl.contains("ServiceLogin")) {
                        return null
                    }
                    currentUrl = nextUrl
                    continue
                }

                val contentType = conn.contentType.orEmpty().lowercase()
                val disposition = conn.getHeaderField("Content-Disposition").orEmpty()
                val title = parseFilenameFromDisposition(disposition)

                if ((code == 200 || code == 206) && !contentType.startsWith("text/html")) {
                    return ResolvedDriveStream(streamUrl = currentUrl, title = title)
                }

                if (code == 200 && contentType.startsWith("text/html")) {
                    val html = conn.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(65536)
                        val n = r.read(buf)
                        if (n > 0) String(buf, 0, n) else ""
                    }
                    if (html.contains("ServiceLogin") || html.contains("accounts.google.com")) {
                        return null
                    }
                    val confirmedUrl = parseDriveConfirmHtml(html, currentUrl, fileId)
                    if (confirmedUrl != null && confirmedUrl != currentUrl) {
                        currentUrl = confirmedUrl
                        continue
                    }
                    return null
                }
                return null
            } catch (_: Throwable) {
                return null
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
        return null
    }

    private fun parseFilenameFromDisposition(disposition: String): String? {
        if (disposition.isBlank()) return null
        Regex("""filename\*=UTF-8''([^;]+)""", RegexOption.IGNORE_CASE).find(disposition)
            ?.groupValues?.getOrNull(1)?.let { return Uri.decode(it.trim('"')) }
        Regex("""filename="([^"]+)"""", RegexOption.IGNORE_CASE).find(disposition)
            ?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    private fun parseDriveConfirmHtml(html: String, baseUrl: String, fileId: String): String? {
        val actionMatch = Regex("""<form[^>]*id=["']download-form["'][^>]*action=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)
            ?: Regex("""<form[^>]*action=["'](https://drive\.usercontent\.google\.com/download[^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(html)

        if (actionMatch != null) {
            val actionUrl = actionMatch.groupValues[1].replace("&amp;", "&")
            val params = linkedMapOf<String, String>()
            val inputRegex = Regex("""<input[^>]*type=["']hidden["'][^>]*>""", RegexOption.IGNORE_CASE)
            val nameRegex = Regex("""name=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val valRegex = Regex("""value=["']([^"']*)["']""", RegexOption.IGNORE_CASE)

            for (m in inputRegex.findAll(html)) {
                val tag = m.value
                val k = nameRegex.find(tag)?.groupValues?.getOrNull(1)
                val v = valRegex.find(tag)?.groupValues?.getOrNull(1)
                if (!k.isNullOrBlank() && v != null) {
                    params[k] = v
                }
            }
            if (!params.containsKey("id")) params["id"] = fileId
            if (!params.containsKey("export")) params["export"] = "download"
            if (!params.containsKey("confirm")) params["confirm"] = "t"

            val query = params.entries.joinToString("&") {
                "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
            }
            val sep = if (actionUrl.contains("?")) "&" else "?"
            return "$actionUrl$sep$query"
        }

        Regex("""href=["']([^"']*confirm=[^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
            ?.groupValues?.getOrNull(1)?.let { href ->
                val clean = href.replace("&amp;", "&")
                return runCatching { URL(URL(baseUrl), clean).toString() }.getOrNull()
            }

        val uuid = Regex("""name=["']uuid["']\s+value=["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)
        val at = Regex("""name=["']at["']\s+value=["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)
        if (uuid != null) {
            val sb = StringBuilder("https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t&uuid=$uuid")
            if (at != null) sb.append("&at=").append(URLEncoder.encode(at, "UTF-8"))
            return sb.toString()
        }
        return null
    }

    /**
     * SAF (Google Drive vb.) URI'leri için açık dosya tanımlayıcılarını (PFD) ve
     * boru (pipe) akışlarının ilk 2 MB başlık önbelleğini korur; böylece ExoPlayer
     * başlık okurken akışı kapatıp baştan indirme başlatmaz.
     */
    class SafStreamSession(
        val uri: Uri,
        val pfd: ParcelFileDescriptor,
        val isRegularFile: Boolean,
        val sizeBytes: Long
    ) {
        private val lock = Any()
        private var pipeStream: InputStream? = if (!isRegularFile) FileInputStream(pfd.fileDescriptor) else null
        private var pipeOffset: Long = 0L
        private val headCache = if (!isRegularFile) ByteArray(2 * 1024 * 1024) else ByteArray(0)
        private var headCacheValid: Int = 0

        fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (isRegularFile) {
                return try {
                    val n = Os.pread(pfd.fileDescriptor, buffer, offset, length, position)
                    if (n <= 0) C.RESULT_END_OF_INPUT else n
                } catch (_: Throwable) {
                    C.RESULT_END_OF_INPUT
                }
            }
            synchronized(lock) {
                // 1) İlk 2 MB önbellekte varsa doğrudan RAM'den ver (akışı kapatmadan!)
                if (position < headCacheValid) {
                    val avail = (headCacheValid - position.toInt()).coerceAtMost(length)
                    System.arraycopy(headCache, position.toInt(), buffer, offset, avail)
                    return avail
                }
                val stream = pipeStream ?: return C.RESULT_END_OF_INPUT
                // 2) Küçük ileri atlamalarda (<= 4 MB) akışı kapatmak yerine aradaki baytları oku
                if (position > pipeOffset && (position - pipeOffset) <= 4L * 1024L * 1024L) {
                    var toSkip = position - pipeOffset
                    val skipBuf = ByteArray(16384)
                    while (toSkip > 0) {
                        val step = min(toSkip, skipBuf.size.toLong()).toInt()
                        val got = stream.read(skipBuf, 0, step)
                        if (got <= 0) return C.RESULT_END_OF_INPUT
                        cacheIfInHead(pipeOffset, skipBuf, 0, got)
                        pipeOffset += got
                        toSkip -= got
                    }
                }
                val n = stream.read(buffer, offset, length)
                if (n <= 0) return C.RESULT_END_OF_INPUT
                cacheIfInHead(pipeOffset, buffer, offset, n)
                pipeOffset += n
                return n
            }
        }

        private fun cacheIfInHead(startPos: Long, buf: ByteArray, off: Int, count: Int) {
            if (startPos >= headCache.size) return
            val dstPos = startPos.toInt()
            val copyLen = min(count, headCache.size - dstPos)
            if (copyLen > 0) {
                System.arraycopy(buf, off, headCache, dstPos, copyLen)
                if (dstPos + copyLen > headCacheValid) {
                    headCacheValid = dstPos + copyLen
                }
            }
        }

        fun closeSession() {
            synchronized(lock) {
                runCatching { pipeStream?.close() }
                pipeStream = null
                runCatching { pfd.close() }
            }
        }
    }

    private val activeSessions = ConcurrentHashMap<String, SafStreamSession>()

    fun obtainSession(context: Context, uri: Uri): SafStreamSession? {
        val key = uri.toString()
        activeSessions[key]?.let { existing ->
            if (runCatching { existing.pfd.fileDescriptor.valid() }.getOrDefault(false)) {
                return existing
            } else {
                activeSessions.remove(key)?.closeSession()
            }
        }
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val st = Os.fstat(pfd.fileDescriptor)
            val isReg = (st.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFREG
            val statSize = if (isReg && st.st_size > 0) st.st_size else runCatching { pfd.statSize }.getOrDefault(-1L)
            val totalSize = if (statSize > 0) statSize else InputAccess.sizeOf(context, uri)
            val session = SafStreamSession(uri, pfd, isReg, totalSize)
            activeSessions[key] = session
            session
        } catch (_: Throwable) {
            null
        }
    }

    fun releaseSession(uri: Uri?) {
        if (uri == null) return
        activeSessions.remove(uri.toString())?.closeSession()
    }

    fun releaseAllSessions() {
        val keys = activeSessions.keys.toList()
        for (k in keys) {
            activeSessions.remove(k)?.closeSession()
        }
    }

    private class SmartMediaDataSource(
        private val appContext: Context,
        private val fallbackDataSource: DataSource
    ) : DataSource {
        private var currentUri: Uri? = null
        private var activeSession: SafStreamSession? = null
        private var readPosition: Long = 0L
        private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
        private var usingFallback: Boolean = false

        override fun addTransferListener(transferListener: TransferListener) {
            fallbackDataSource.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val uri = dataSpec.uri
            currentUri = uri
            if (uri.scheme == "content") {
                val session = obtainSession(appContext, uri)
                if (session != null) {
                    activeSession = session
                    usingFallback = false
                    readPosition = dataSpec.position
                    bytesRemaining = when {
                        dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                        session.sizeBytes > 0L -> (session.sizeBytes - dataSpec.position).coerceAtLeast(0L)
                        else -> C.LENGTH_UNSET.toLong()
                    }
                    return bytesRemaining
                }
            }
            usingFallback = true
            activeSession = null
            return fallbackDataSource.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (usingFallback) {
                return fallbackDataSource.read(buffer, offset, length)
            }
            val session = activeSession ?: return C.RESULT_END_OF_INPUT
            if (length == 0) return 0
            if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
            val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length
            else min(length.toLong(), bytesRemaining).toInt()
            val n = session.readAt(readPosition, buffer, offset, toRead)
            if (n == C.RESULT_END_OF_INPUT || n <= 0) return C.RESULT_END_OF_INPUT
            readPosition += n
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                bytesRemaining = (bytesRemaining - n).coerceAtLeast(0L)
            }
            return n
        }

        override fun getUri(): Uri? = if (usingFallback) fallbackDataSource.uri else currentUri

        override fun close() {
            if (usingFallback) {
                runCatching { fallbackDataSource.close() }
                usingFallback = false
            }
            // Not: activeSession kapatılmaz; böylece ExoPlayer seek yaptığında Google Drive PFD'si kopmaz.
            activeSession = null
            currentUri = null
        }
    }

    fun buildDataSourceFactory(context: Context): DataSource.Factory {
        val appCtx = context.applicationContext
        return DataSource.Factory {
            val cookie = getCombinedCookiesForUrl("https://drive.google.com")
            val headers = HashMap<String, String>()
            if (cookie.isNotBlank()) {
                headers["Cookie"] = cookie
            }
            headers["User-Agent"] = DEFAULT_USER_AGENT

            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(DEFAULT_USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setKeepPostFor302Redirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
                .setDefaultRequestProperties(headers)

            val defaultDs = DefaultDataSource(appCtx, httpFactory.createDataSource())
            SmartMediaDataSource(appCtx, defaultDs)
        }
    }
}
