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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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

object DriveStreamResolver {

    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

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

    /**
     * SAF (content://com.google.android.apps.docs.storage/...) URI'sinden
     * openFileDescriptor ÇAĞIRMADAN tüm olası sütunları tarayarak varsa gerçek Drive fileId'sini bulur.
     */
    fun tryExtractFileIdFromSafUri(context: Context, uri: Uri): String? {
        val rawUri = Uri.decode(uri.toString())
        val afterDoc = rawUri.substringAfter("doc=", "").substringBefore(";")
        if (afterDoc.length >= 22 && !afterDoc.startsWith("encoded=") && !afterDoc.all { it.isDigit() } && isValidDriveId(afterDoc)) {
            return afterDoc
        }
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    for (i in 0 until c.columnCount) {
                        val v = runCatching { c.getString(i) }.getOrNull().orEmpty()
                        if (v.length in 25..48 && v.matches(Regex("""^[a-zA-Z0-9_-]{25,48}$""")) && isValidDriveId(v)) {
                            return v
                        }
                        val sub = v.substringAfter("doc=", "").substringBefore(";")
                        if (sub.length in 25..48 && !sub.startsWith("encoded=") && sub.matches(Regex("""^[a-zA-Z0-9_-]{25,48}$""")) && isValidDriveId(sub)) {
                            return sub
                        }
                    }
                }
            }
        }
        return null
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

    /**
     * CookieManager'daki aktif oturumla masaüstü Drive araması yaparak dosya adından fileId bulur.
     */
    suspend fun searchDriveFileIdByName(fileName: String): String? = withContext(Dispatchers.IO) {
        val cleanName = fileName.trim()
        if (cleanName.isBlank()) return@withContext null
        val cookie = getCombinedCookiesForUrl("https://drive.google.com")
        if (cookie.isBlank()) return@withContext null

        val stem = cleanName.substringBeforeLast('.')
        val queryTerm = if (stem.length >= 6) stem.take(40) else cleanName
        val encodedQ = URLEncoder.encode(queryTerm, "UTF-8")

        for (accountIdx in 0..3) {
            val searchUrl = "https://drive.google.com/drive/u/$accountIdx/search?q=$encodedQ"
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(searchUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 6_000
                    readTimeout = 6_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
                    setRequestProperty("Cookie", cookie)
                }
                val code = conn.responseCode
                if (code == 200) {
                    val html = conn.inputStream.bufferedReader().use { it.readText() }
                    val unescaped = html
                        .replace("\\x22", "\"")
                        .replace("\\x5b", "[")
                        .replace("\\x5d", "]")
                        .replace("\\/", "/")
                        .replace("\\\"", "\"")
                    val namePos = unescaped.indexOf(cleanName, ignoreCase = true)
                        .takeIf { it >= 0 }
                        ?: unescaped.indexOf(stem, ignoreCase = true)
                    if (namePos > 0) {
                        val windowStart = (namePos - 600).coerceAtLeast(0)
                        val snippet = unescaped.substring(windowStart, namePos)
                        val idMatches = Regex("""\"([a-zA-Z0-9_-]{25,44})\"""").findAll(snippet).toList()
                        for (m in idMatches.asReversed()) {
                            val candidate = m.groupValues[1]
                            if (isValidDriveId(candidate)) {
                                return@withContext candidate
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
        null
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

    fun releaseSession(uri: Uri?) { }
    fun releaseAllSessions() { }

    /**
     * Yerel dosyalarda CookieManager'ı HİÇ çağırmadan doğrudan FileDataSource/ContentDataSource kullanan,
     * yalnızca http/https isteklerinde Cookie başlığı ekleyen hızlı DataSource.Factory.
     */
    fun buildDataSourceFactory(context: Context): DataSource.Factory {
        val appCtx = context.applicationContext
        val httpFactory = DataSource.Factory {
            val httpDs = DefaultHttpDataSource.Factory()
                .setUserAgent(DEFAULT_USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setKeepPostFor302Redirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
                .createDataSource()

            object : DataSource {
                override fun addTransferListener(transferListener: TransferListener) {
                    httpDs.addTransferListener(transferListener)
                }

                override fun open(dataSpec: DataSpec): Long {
                    val u = dataSpec.uri.toString()
                    if (isGoogleDriveUrl(u) || u.contains("googleusercontent.com")) {
                        val cookie = getCombinedCookiesForUrl("https://drive.google.com")
                        if (cookie.isNotBlank()) {
                            httpDs.setRequestProperty("Cookie", cookie)
                        }
                    }
                    return httpDs.open(dataSpec)
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    httpDs.read(buffer, offset, length)

                override fun getUri(): Uri? = httpDs.uri

                override fun getResponseHeaders(): Map<String, List<String>> = httpDs.responseHeaders

                override fun close() {
                    httpDs.close()
                }
            }
        }
        return DefaultDataSource.Factory(appCtx, httpFactory)
    }
}
