package com.example.muxmaster.data

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Message
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.example.muxmaster.ui.theme.BgDark
import com.example.muxmaster.ui.theme.Purple
import com.example.muxmaster.ui.theme.PurpleLight
import com.example.muxmaster.ui.theme.Surface
import com.example.muxmaster.ui.theme.TextMuted
import com.example.muxmaster.ui.theme.TextPrimary
import com.example.muxmaster.ui.theme.TextSec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    private fun sizeOf(context: Context, uri: Uri): Long = try {
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
 * Google Drive videolarını cihaza İNDİRMEDEN doğrudan HTTP Range (206 Partial Content)
 * akışı olarak ExoPlayer'da oynatmayı sağlayan çözümleyici ve uygulama içi tarayıcı.
 */
object DriveStreamResolver {

    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    @Volatile
    var lastUserAgent: String = DEFAULT_USER_AGENT

    data class SafDriveInfo(
        val fileId: String?,
        val displayName: String,
        val sizeBytes: Long
    )

    data class ResolvedDriveStream(
        val streamUrl: String,
        val title: String? = null
    )

    /** Verilen URI Google Drive'ın SAF / içerik sağlayıcısına mı ait? */
    fun isGoogleDriveSafUri(uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        val auth = uri.authority.orEmpty().lowercase()
        return auth.contains("com.google.android.apps.docs") || auth.contains("google.drive")
    }

    /** Verilen metin/URL bir Google Drive bağlantısı mı? */
    fun isGoogleDriveUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("drive.google.com") ||
            lower.contains("drive.usercontent.google.com") ||
            lower.contains("docs.google.com/file")
    }

    /**
     * Herhangi bir Google Drive URL'sinden veya intent:// bağlantısından
     * Google Drive dosya kimliğini (fileId) ayıklar.
     */
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
     * Google Drive SAF URI'sinden (content://com.google.android.apps.docs.storage/document/...)
     * openDocument() ÇAĞIRMADAN (indirme başlatmadan!) sadece SQLite üst verisini okur.
     */
    fun inspectSafDriveUri(context: Context, uri: Uri): SafDriveInfo {
        var displayName = "Google Drive Video"
        var sizeBytes = -1L
        var docIdStr: String? = null

        runCatching {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                docIdStr = DocumentsContract.getDocumentId(uri)
            }
        }

        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE,
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID
                ),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) {
                        c.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                    }
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) {
                        sizeBytes = c.getLong(sizeIdx)
                    }
                    val docIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    if (docIdx >= 0 && !c.isNull(docIdx)) {
                        docIdStr = c.getString(docIdx) ?: docIdStr
                    }
                }
            }
        }

        var extractedFileId: String? = null
        val rawDoc = docIdStr ?: uri.lastPathSegment.orEmpty()
        val afterDoc = rawDoc.substringAfter("doc=", rawDoc).substringBefore(";")
        if (afterDoc.length >= 22 && !afterDoc.all { it.isDigit() } && isValidDriveId(afterDoc)) {
            extractedFileId = afterDoc
        }

        return SafDriveInfo(
            fileId = extractedFileId,
            displayName = displayName,
            sizeBytes = sizeBytes
        )
    }

    /**
     * CookieManager'daki mevcut Google oturum çerezlerini verilen URL için birleştirir.
     */
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
     * Google Drive fileId'sini doğrudan ExoPlayer'ın HTTP Range ile oynatabileceği
     * akış URL'sine çözümler. Büyük dosyalardaki (>100 MB) virüs taraması onay sayfasını
     * (confirm=t, uuid, at parametreleri) otomatik olarak aşar.
     */
    suspend fun resolveDriveFileIdToStream(fileId: String): ResolvedDriveStream? = withContext(Dispatchers.IO) {
        val candidates = listOf(
            "https://drive.google.com/uc?id=$fileId&export=download&confirm=t",
            "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
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

        while (hops < 10) {
            hops++
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", lastUserAgent)
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
     * ExoPlayer için her HTTP açılışında CookieManager'daki Google Drive oturum çerezlerini
     * ve User-Agent başlığını otomatik ekleyen DataSource.Factory.
     */
    fun buildDataSourceFactory(context: Context): DataSource.Factory {
        val appCtx = context.applicationContext
        return DataSource.Factory {
            val cookie = getCombinedCookiesForUrl("https://drive.google.com")
            val headers = HashMap<String, String>()
            if (cookie.isNotBlank()) {
                headers["Cookie"] = cookie
            }
            headers["User-Agent"] = lastUserAgent

            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(lastUserAgent)
                .setAllowCrossProtocolRedirects(true)
                .setKeepPostFor302Redirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
                .setDefaultRequestProperties(headers)

            DefaultDataSource(appCtx, httpFactory.createDataSource())
        }
    }
}

class DriveJsBridge(
    private val autoSearchNameProvider: () -> String,
    private val onFileIdDetected: (fileId: String, fileName: String?) -> Unit,
    private val onUrlIntercepted: (url: String) -> Unit
) {
    @JavascriptInterface
    fun getAutoSearchName(): String = autoSearchNameProvider()

    @JavascriptInterface
    fun selectFile(fileId: String?, fileName: String?) {
        val cleanId = fileId?.trim().orEmpty()
        if (cleanId.length >= 18) {
            onFileIdDetected(cleanId, fileName?.trim()?.takeIf { it.isNotBlank() })
        }
    }

    @JavascriptInterface
    fun interceptUrl(url: String?) {
        val u = url?.trim().orEmpty()
        if (u.isNotBlank()) {
            onUrlIntercepted(u)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GoogleDriveBrowserDialog(
    initialFileId: String? = null,
    autoSearchFileName: String? = null,
    onDismiss: () -> Unit,
    onStreamReady: (uri: Uri, title: String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    var statusText by remember {
        mutableStateOf(
            when {
                !initialFileId.isNullOrBlank() -> "Google Drive video akışı hazırlanıyor..."
                !autoSearchFileName.isNullOrBlank() -> "\"$autoSearchFileName\" için akış hazırlanıyor..."
                else -> "Oynatmak istediğiniz videoya dokunun (cihaza indirilmeden anında açılır)"
            }
        )
    }
    var isResolving by remember { mutableStateOf(!initialFileId.isNullOrBlank()) }
    var progress by remember { mutableIntStateOf(0) }
    var selectedCandidateId by remember { mutableStateOf<String?>(initialFileId) }
    var selectedCandidateName by remember { mutableStateOf<String?>(autoSearchFileName) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var delivered by remember { mutableStateOf(false) }

    fun deliverStream(url: String, title: String?) {
        if (delivered) return
        delivered = true
        runCatching { CookieManager.getInstance().flush() }
        onStreamReady(Uri.parse(url), title ?: selectedCandidateName)
    }

    fun triggerFileIdResolution(fileId: String, fileName: String?) {
        if (delivered) return
        selectedCandidateId = fileId
        if (!fileName.isNullOrBlank()) selectedCandidateName = fileName
        isResolving = true
        statusText = "${fileName ?: "Video"} doğrudan akış olarak açılıyor..."

        scope.launch {
            val resolved = DriveStreamResolver.resolveDriveFileIdToStream(fileId)
            if (resolved != null && !delivered) {
                deliverStream(resolved.streamUrl, resolved.title ?: fileName)
                return@launch
            }
            val wv = webViewRef
            if (wv != null && !delivered) {
                wv.loadUrl("https://drive.google.com/uc?id=$fileId&export=download&confirm=t")
            }
        }
    }

    LaunchedEffect(initialFileId) {
        if (!initialFileId.isNullOrBlank()) {
            triggerFileIdResolution(initialFileId, autoSearchFileName)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                IconButton(onClick = {
                    val wv = webViewRef
                    if (wv != null && wv.canGoBack() && !isResolving) {
                        wv.goBack()
                    } else {
                        onDismiss()
                    }
                }) {
                    Icon(Icons.Filled.ArrowBack, "Geri", tint = TextPrimary)
                }
                Icon(
                    Icons.Filled.CloudDone,
                    contentDescription = null,
                    tint = PurpleLight,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Google Drive Doğrudan Oynatıcı",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        statusText,
                        color = TextSec,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (selectedCandidateId != null && !isResolving) {
                    Button(
                        onClick = { triggerFileIdResolution(selectedCandidateId!!, selectedCandidateName) },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Purple),
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Oynat", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                IconButton(onClick = { webViewRef?.reload() }) {
                    Icon(Icons.Filled.Refresh, "Yenile", tint = TextSec)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, "Kapat", tint = TextSec)
                }
            }

            if (progress in 1..99 || isResolving) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = PurpleLight,
                    trackColor = Surface
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                loadsImagesAutomatically = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                userAgentString = DriveStreamResolver.DEFAULT_USER_AGENT
                                DriveStreamResolver.lastUserAgent = userAgentString
                            }

                            addJavascriptInterface(
                                DriveJsBridge(
                                    autoSearchNameProvider = { autoSearchFileName.orEmpty() },
                                    onFileIdDetected = { id, name ->
                                        post { triggerFileIdResolution(id, name) }
                                    },
                                    onUrlIntercepted = { url ->
                                        post {
                                            val id = DriveStreamResolver.extractDriveFileId(url)
                                            if (id != null) {
                                                triggerFileIdResolution(id, null)
                                            }
                                        }
                                    }
                                ),
                                "MuxDriveBridge"
                            )

                            setDownloadListener(DownloadListener { url, userAgent, contentDisposition, _, _ ->
                                if (!url.isNullOrBlank()) {
                                    if (!userAgent.isNullOrBlank()) {
                                        DriveStreamResolver.lastUserAgent = userAgent
                                    }
                                    post {
                                        stopLoading()
                                        val fileName = contentDisposition
                                            ?.let {
                                                Regex("""filename\*=UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)
                                                    .find(it)?.groupValues?.getOrNull(1)?.let { s -> Uri.decode(s.trim('"')) }
                                                    ?: Regex("""filename="([^"]+)"""", RegexOption.IGNORE_CASE)
                                                        .find(it)?.groupValues?.getOrNull(1)
                                            }
                                        deliverStream(url, fileName ?: selectedCandidateName)
                                    }
                                }
                            })

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }

                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: Message?
                                ): Boolean {
                                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                    val tempWebView = WebView(ctx)
                                    tempWebView.webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            v: WebView?,
                                            req: WebResourceRequest?
                                        ): Boolean {
                                            val target = req?.url?.toString().orEmpty()
                                            val id = DriveStreamResolver.extractDriveFileId(target)
                                            if (id != null) {
                                                triggerFileIdResolution(id, null)
                                            } else if (target.startsWith("http")) {
                                                view?.loadUrl(target)
                                            }
                                            tempWebView.destroy()
                                            return true
                                        }
                                    }
                                    transport.webView = tempWebView
                                    resultMsg.sendToTarget()
                                    return true
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                private fun checkAndInterceptUrl(view: WebView?, url: String): Boolean {
                                    if (url.isBlank()) return false

                                    if (url.contains("googleusercontent.com/docs/securesc/") ||
                                        url.contains("videoplayback?")
                                    ) {
                                        view?.stopLoading()
                                        deliverStream(url, selectedCandidateName)
                                        return true
                                    }

                                    if (url.startsWith("intent://")) {
                                        val id = DriveStreamResolver.extractDriveFileId(url)
                                        if (id != null) {
                                            triggerFileIdResolution(id, null)
                                            return true
                                        }
                                        val fallback = Regex("""S\.browser_fallback_url=([^;]+)""")
                                            .find(url)?.groupValues?.getOrNull(1)?.let { Uri.decode(it) }
                                        if (!fallback.isNullOrBlank()) {
                                            val fbId = DriveStreamResolver.extractDriveFileId(fallback)
                                            if (fbId != null) {
                                                triggerFileIdResolution(fbId, null)
                                            } else if (fallback.contains("/folders/")) {
                                                view?.loadUrl(fallback)
                                            }
                                        }
                                        return true
                                    }

                                    if (url.startsWith("market://") || url.contains("play.google.com/store")) {
                                        return true
                                    }

                                    if (!url.contains("/uc?") && !url.contains("drive.usercontent.google.com/download")) {
                                        val id = DriveStreamResolver.extractDriveFileId(url)
                                        if (id != null && (url.contains("/file/d/") || url.contains("/open?"))) {
                                            triggerFileIdResolution(id, null)
                                            return true
                                        }
                                    }
                                    return false
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url?.toString().orEmpty()
                                    return checkAndInterceptUrl(view, url)
                                }

                                override fun doUpdateVisitedHistory(
                                    view: WebView?,
                                    url: String?,
                                    isReload: Boolean
                                ) {
                                    super.doUpdateVisitedHistory(view, url, isReload)
                                    if (!url.isNullOrBlank()) {
                                        checkAndInterceptUrl(view, url)
                                    }
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    if (!url.isNullOrBlank()) {
                                        checkAndInterceptUrl(view, url)
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    runCatching { CookieManager.getInstance().flush() }
                                    val currentUrl = url.orEmpty()

                                    if (currentUrl.contains("drive.usercontent.google.com/download") ||
                                        currentUrl.contains("drive.google.com/uc")
                                    ) {
                                        view?.evaluateJavascript(
                                            "(function() {" +
                                                "var form = document.getElementById('download-form') || document.querySelector('form[action*=\"download\"]');" +
                                                "if (form) { form.submit(); return 'FORM_SUBMITTED'; }" +
                                                "var link = document.getElementById('uc-download-link') || document.querySelector('a[href*=\"confirm=\"]');" +
                                                "if (link) { link.click(); return 'LINK_CLICKED'; }" +
                                                "return 'NO_FORM';" +
                                                "})();",
                                            null
                                        )
                                    }

                                    view?.evaluateJavascript(
                                        """
                                        (function() {
                                            if (window.__muxDriveHooked) return;
                                            window.__muxDriveHooked = true;

                                            var origOpen = window.open;
                                            window.open = function(u) {
                                                if (u && window.MuxDriveBridge) {
                                                    window.MuxDriveBridge.interceptUrl(String(u));
                                                    return null;
                                                }
                                                return origOpen ? origOpen.apply(this, arguments) : null;
                                            };

                                            var videoExts = new RegExp("\\.(mkv|mp4|m4v|webm|avi|mov|ts|m2ts|mts|3gp|flv|wmv|mpg|mpeg|ogv|mp3|m4a|aac|flac|opus|ogg|wav)(\\b| )", "i");
                                            var folderWords = /(klasör|folder|google drive folder)/i;

                                            function hasVideoExt(str) {
                                                var s = (str || '') + ' ';
                                                return videoExts.test(s);
                                            }

                                            function isFolderElement(el, text) {
                                                var target = (el.getAttribute('data-target') || '').toLowerCase();
                                                if (target === 'folder') return true;
                                                var aria = (el.getAttribute('aria-label') || '') + ' ' + text;
                                                if (folderWords.test(aria) && !hasVideoExt(aria)) return true;
                                                return false;
                                            }

                                            function isVideoElement(el, text) {
                                                var aria = (el.getAttribute('aria-label') || '') + ' ' + text;
                                                if (hasVideoExt(aria)) return true;
                                                if (/\b(video|film|movie|matroska|mp4)\b/i.test(aria) && !isFolderElement(el, text)) return true;
                                                return false;
                                            }

                                            document.addEventListener('click', function(e) {
                                                var el = e.target ? e.target.closest('[data-id]') : null;
                                                if (!el) return;
                                                var id = el.getAttribute('data-id');
                                                if (!id || id.length < 18) return;
                                                var text = (el.innerText || el.textContent || '').trim().split('\n')[0];
                                                if (isVideoElement(el, text)) {
                                                    e.preventDefault();
                                                    e.stopPropagation();
                                                    if (window.MuxDriveBridge) {
                                                        window.MuxDriveBridge.selectFile(id, text);
                                                    }
                                                }
                                            }, true);

                                            var autoName = window.MuxDriveBridge ? String(window.MuxDriveBridge.getAutoSearchName() || '') : '';
                                            if (autoName.length > 0 && !window.__muxAutoPicked) {
                                                var tries = 0;
                                                var timer = setInterval(function() {
                                                    tries++;
                                                    var items = document.querySelectorAll('[data-id]');
                                                    for (var i = 0; i < items.length; i++) {
                                                        var item = items[i];
                                                        var id = item.getAttribute('data-id');
                                                        if (!id || id.length < 18) continue;
                                                        var txt = (item.innerText || item.textContent || '') + ' ' + (item.getAttribute('aria-label') || '');
                                                        if (txt.toLowerCase().indexOf(autoName.toLowerCase()) !== -1 && !isFolderElement(item, txt)) {
                                                            window.__muxAutoPicked = true;
                                                            clearInterval(timer);
                                                            if (window.MuxDriveBridge) {
                                                                window.MuxDriveBridge.selectFile(id, autoName);
                                                            }
                                                            return;
                                                        }
                                                    }
                                                    if (tries > 25) clearInterval(timer);
                                                }, 350);
                                            }
                                        })();
                                        """.trimIndent(),
                                        null
                                    )
                                }
                            }

                            val startUrl = when {
                                !initialFileId.isNullOrBlank() ->
                                    "https://drive.google.com/uc?id=$initialFileId&export=download&confirm=t"
                                !autoSearchFileName.isNullOrBlank() -> {
                                    val q = URLEncoder.encode(autoSearchFileName, "UTF-8")
                                    "https://drive.google.com/drive/search?q=$q"
                                }
                                else -> "https://drive.google.com/drive/my-drive"
                            }
                            loadUrl(startUrl)
                            webViewRef = this
                        }
                    }
                )

                if (isResolving) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xD90B0C10)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .padding(24.dp)
                                .background(Surface, RoundedCornerShape(12.dp))
                                .padding(horizontal = 24.dp, vertical = 20.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = PurpleLight,
                                strokeWidth = 3.dp
                            )
                            Text(
                                statusText,
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Dosya cihaza indirilmiyor; doğrudan akış (stream) bağlantısı kuruluyor.",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                            TextButton(onClick = { isResolving = false }) {
                                Text("Tarayıcıyı Göster / İptal", color = PurpleLight)
                            }
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
            }
        }
    }
}
