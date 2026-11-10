package com.example.muxmaster

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.muxmaster.data.AppPreferences
import com.example.muxmaster.data.RecentFiles
import com.example.muxmaster.data.DriveStreamResolver
import com.example.muxmaster.ui.screens.ConverterScreen
import com.example.muxmaster.ui.screens.HomeScreen
import com.example.muxmaster.ui.screens.MergeScreen
import com.example.muxmaster.ui.screens.MuxScreen
import com.example.muxmaster.ui.screens.PlayerHomeScreen
import com.example.muxmaster.ui.screens.PlayerVideoSlot
import com.example.muxmaster.ui.screens.SettingsScreen
import com.example.muxmaster.ui.screens.StreamPlayerScreen
import com.example.muxmaster.ui.screens.TrimScreen
import com.example.muxmaster.ui.screens.prewarmVideoDecoders
import com.example.muxmaster.ui.theme.MuxMasterTheme
import com.example.muxmaster.ui.theme.ThemeMode
import com.example.muxmaster.viewmodel.ConverterViewModel
import com.example.muxmaster.viewmodel.MergeViewModel
import com.example.muxmaster.viewmodel.MuxViewModel
import com.example.muxmaster.viewmodel.TrimViewModel

class MainActivity : AppCompatActivity() {

    private val muxViewModel: MuxViewModel by viewModels()
    private val converterViewModel: ConverterViewModel by viewModels()
    private val trimViewModel: TrimViewModel by viewModels()
    private val mergeViewModel: MergeViewModel by viewModels()

    private var screenState: MutableState<Int>? = null

    /** Çoklu video oynatıcıdaki aktif video yuvaları (en fazla 4 video aynı anda). */
    private val playSlots = mutableStateListOf<PlayerVideoSlot>()
    private var nextSlotId = 1L

    /** Sistem seçicisinden yeni video ekleme mi yapılıyor, yoksa tekli video mu açılıyor? */
    private var pickingToAddSlot = false

    /** "Birlikte aç" ile gelen video: kullanıcıya ne yapmak istediği sorulur. */
    private val pendingOpen = mutableStateOf<Pair<Uri, String>?>(null)

    /** Uygulama içinden zaten video oynarken yeni bir video seçildiğinde tekli mi çoklu mu sorulur. */
    private val pendingSingleOrMulti = mutableStateOf<Pair<Uri, String?>?>(null)

    /** Video başka bir uygulamadan (dosya yöneticisi vb.) mı açıldı? Öyleyse geri = o uygulamaya dön. */
    private val playFromExternal = mutableStateOf(false)

    private fun openSingleInPlayer(uri: Uri, title: String? = null, fromExternal: Boolean = false) {
        rememberRecent(uri, title)
        playFromExternal.value = fromExternal
        DriveStreamResolver.releaseAllSessions()
        playSlots.clear()
        playSlots.add(PlayerVideoSlot(nextSlotId++, uri, title))
        screenState?.value = 5
    }

    private fun addSlotInPlayer(uri: Uri, title: String? = null, fromExternal: Boolean? = null) {
        rememberRecent(uri, title)
        if (fromExternal != null && playSlots.isEmpty()) {
            playFromExternal.value = fromExternal
        }
        if (playSlots.size < 4) {
            playSlots.add(PlayerVideoSlot(nextSlotId++, uri, title))
        } else {
            val removed = playSlots.lastOrNull()
            DriveStreamResolver.releaseSession(removed?.uri)
            playSlots[playSlots.lastIndex] = PlayerVideoSlot(nextSlotId++, uri, title)
        }
        screenState?.value = 5
    }

    /** Açılan dosyayı ana ekrandaki "Son Dosyalar" listesine ekler (arka planda, sessizce). */
    private fun rememberRecent(uri: Uri, name: String?) {
        if (name.isNullOrBlank()) return
        val appContext = applicationContext
        Thread {
            runCatching { RecentFiles.add(appContext, uri, name) }
        }.start()
    }

    private fun handleInternalPlayerOpen(uri: Uri, title: String?) {
        if (playSlots.isNotEmpty()) {
            pendingSingleOrMulti.value = uri to title
        } else {
            openSingleInPlayer(uri, title, fromExternal = false)
        }
    }

    /**
     * Cihazdaki TÜM üçüncü parti dosya yöneticisi uygulamalarını (ZArchiver, Solid Explorer,
     * MiXplorer, CX Dosya Gezgini, Samsung Dosyalarım vb.) ve Google Drive / yerel depolama
     * sağlayıcılarını sistem dosya seçicisinin sol üst "Şuradan aç:" menüsünde eksiksiz listeler.
     */
    private fun createUniversalSingleDocumentContract(): ActivityResultContracts.OpenDocument = object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: android.content.Context, input: Array<String>): Intent =
            super.createIntent(context, input).apply {
                action = Intent.ACTION_GET_CONTENT
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                removeExtra(Intent.EXTRA_MIME_TYPES)
                putExtra(Intent.EXTRA_LOCAL_ONLY, false)
                putExtra(
                    android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:")
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
    }

    private fun createUniversalMultipleDocumentsContract(): ActivityResultContracts.OpenMultipleDocuments = object : ActivityResultContracts.OpenMultipleDocuments() {
        override fun createIntent(context: android.content.Context, input: Array<String>): Intent =
            super.createIntent(context, input).apply {
                action = Intent.ACTION_GET_CONTENT
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                removeExtra(Intent.EXTRA_MIME_TYPES)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                putExtra(Intent.EXTRA_LOCAL_ONLY, false)
                putExtra(
                    android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:")
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
    }

    /** Uygulama içinden "Video Oynatıcı" ile video seçme (tüm 3. parti dosya yöneticileri dahil). */
    private val pickPlayVideoLauncher = registerForActivityResult(createUniversalSingleDocumentContract()) { uri ->
        val addMode = pickingToAddSlot
        pickingToAddSlot = false
        if (uri != null) {
            if (!DriveStreamResolver.isGoogleDriveSafUri(uri)) {
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: Exception) { }
            }
            val name = queryDisplayName(uri)
            if (addMode) {
                addSlotInPlayer(uri, name, fromExternal = false)
            } else {
                handleInternalPlayerOpen(uri, name)
            }
        }
    }

    /** Oynatıcıdan çıkış: dışarıdan açıldıysa çağıran uygulamaya (dosya yöneticisi) geri döner. */
    private fun exitPlayer() {
        val external = playFromExternal.value
        playFromExternal.value = false
        DriveStreamResolver.releaseAllSessions()
        playSlots.clear()
        screenState?.value = if (external) HOME_SCREEN else 6
        if (external) {
            if (!moveTaskToBack(true)) finish()
        }
    }

    /** Depolama (Tüm dosyalara erişim) izni sorulsun mu? */
    private val askStorage = mutableStateOf(false)

    private val pickVideoLauncher = registerForActivityResult(createUniversalSingleDocumentContract()) { uri ->
        if (uri != null) {
            val name = queryDisplayName(uri) ?: "video.mkv"
            rememberRecent(uri, name)
            muxViewModel.onVideoSelected(uri, name)
        }
    }
    private val pickMuxFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) muxViewModel.onFolderSelected(uri)
    }
    private val pickMuxAudioLauncher = registerForActivityResult(createUniversalSingleDocumentContract()) { uri ->
        if (uri != null) muxViewModel.addAudioFile(uri, queryDisplayName(uri) ?: "audio_track")
    }
    private val pickSubtitleLauncher = registerForActivityResult(createUniversalSingleDocumentContract()) { uri ->
        if (uri != null) muxViewModel.addSubtitleFile(uri, queryDisplayName(uri) ?: "subtitle_track")
    }
    private val pickMuxOutputFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) muxViewModel.setOutputFolder(uri)
    }
    private val pickConverterAudioLauncher = registerForActivityResult(createUniversalMultipleDocumentsContract()) { uris ->
        if (uris.isNotEmpty()) {
            val pairs = uris.map { u -> u to (queryDisplayName(u) ?: "audio") }
            pairs.forEach { (u, n) -> rememberRecent(u, n) }
            converterViewModel.onAudioFilesSelected(pairs)
        }
    }
    private val pickConverterOutputFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) converterViewModel.setOutputFolder(uri)
    }
    private val pickTrimVideoLauncher = registerForActivityResult(createUniversalSingleDocumentContract()) { uri ->
        if (uri != null) {
            val name = queryDisplayName(uri) ?: "video.mkv"
            rememberRecent(uri, name)
            trimViewModel.onVideoSelected(uri, name)
        }
    }
    private val pickTrimFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) trimViewModel.onFolderSelected(uri)
    }
    private val pickTrimOutputFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) trimViewModel.setOutputFolder(uri)
    }
    private val pickMergeVideosLauncher = registerForActivityResult(createUniversalMultipleDocumentsContract()) { uris ->
        if (uris.isNotEmpty()) {
            val pairs = uris.map { u -> u to (queryDisplayName(u) ?: "video.mkv") }
            pairs.forEach { (u, n) -> rememberRecent(u, n) }
            mergeViewModel.onVideosSelected(pairs)
        }
    }
    private val pickMergeOutputFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) mergeViewModel.setOutputFolder(uri)
    }
    private val pickDefaultOutputFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            muxViewModel.setOutputFolder(uri)
            converterViewModel.setOutputFolder(uri)
            trimViewModel.setOutputFolder(uri)
            mergeViewModel.setOutputFolder(uri)
        }
    }
    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val manageStorageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                manageStorageLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                try { manageStorageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                catch (_: Exception) { }
            }
        } else {
            storagePermissionLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val appPrefs by lazy { AppPreferences(applicationContext) }

    private fun ThemeMode.toPrefValue(): String = when (this) {
        ThemeMode.LIGHT -> "light"; ThemeMode.DARK -> "dark"; ThemeMode.AMOLED -> "amoled"; ThemeMode.SYSTEM -> "system"
    }
    private fun String.toThemeMode(): ThemeMode = when (this) {
        "light" -> ThemeMode.LIGHT; "system" -> ThemeMode.SYSTEM; "amoled" -> ThemeMode.AMOLED; else -> ThemeMode.DARK
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // KENARDAN KENARA (edge-to-edge) — TAM EKRAN DÜZELTMESİ:
        // Uygulama artık sistem çubuklarının (durum çubuğu / gezinme çubuğu) ARKASINA
        // kadar çiziyor. Böylece üstteki panel gerçekten ekranın en üstüne kadar
        // uzanıyor; başlık ve içerik ise her ekranda güvenli alan kadar içeride
        // kalıyor (Scaffold/WindowInsets). Önceden Android 15+ ve bazı üretici
        // ROM'larında içerik durum çubuğunun ALTINDA kalıp başlıkla çakışıyordu.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (savedInstanceState == null &&
            !muxViewModel.isMuxing && !trimViewModel.isTrimming &&
            !converterViewModel.isConverting && !mergeViewModel.isMerging
        ) {
            Thread { com.example.muxmaster.data.InputAccess.clearWorkCache(applicationContext) }.start()
        }
        if (!com.example.muxmaster.data.InputAccess.hasStorageAccess(this)) {
            if (Build.VERSION.SDK_INT >= 30) askStorage.value = true
            else requestStorageAccess()
        } else if (Build.VERSION.SDK_INT >= 33) {
            notifAsked = true
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        Thread { prewarmVideoDecoders() }.start()
        setContent {
            var themeMode by remember { mutableStateOf(appPrefs.themeMode.toThemeMode()) }
            MuxMasterTheme(themeMode = themeMode) {
                val screenHolder = rememberSaveable { mutableStateOf(HOME_SCREEN) }
                screenState = screenHolder
                var screen by screenHolder

                BackHandler(enabled = screen != HOME_SCREEN) {
                    if (screen == 5) exitPlayer() else screen = HOME_SCREEN
                }

                LaunchedEffect(Unit) { handleIncomingIntent(intent) }

                if (askStorage.value) {
                    AlertDialog(
                        onDismissRequest = { askStorage.value = false },
                        title = { Text(stringResource(R.string.dialog_storage_title)) },
                        text = { Text(stringResource(R.string.dialog_storage_text)) },
                        confirmButton = {
                            TextButton(onClick = { askStorage.value = false; requestStorageAccess() }) {
                                Text(stringResource(R.string.dialog_storage_allow))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { askStorage.value = false }) {
                                Text(stringResource(R.string.dialog_storage_later))
                            }
                        }
                    )
                }

                pendingSingleOrMulti.value?.let { (uri, title) ->
                    SingleOrMultiPlayDialog(
                        fileName = title ?: uri.lastPathSegment ?: "Video",
                        onDismiss = { pendingSingleOrMulti.value = null },
                        onPlaySingle = {
                            pendingSingleOrMulti.value = null
                            openSingleInPlayer(uri, title, fromExternal = false)
                        },
                        onPlayMulti = {
                            pendingSingleOrMulti.value = null
                            addSlotInPlayer(uri, title, fromExternal = false)
                        }
                    )
                }

                pendingOpen.value?.let { (uri, name) ->
                    OpenWithChooser(
                        fileName = name,
                        onDismiss = { pendingOpen.value = null },
                        onPlaySingle = {
                            pendingOpen.value = null
                            openSingleInPlayer(uri, name, fromExternal = true)
                        },
                        onPlayMulti = {
                            pendingOpen.value = null
                            addSlotInPlayer(uri, name, fromExternal = true)
                        },
                        onMux = { pendingOpen.value = null; muxViewModel.onVideoSelected(uri, name); screen = 0 },
                        onTrim = { pendingOpen.value = null; trimViewModel.onVideoSelected(uri, name); screen = 2 },
                        onMerge = { pendingOpen.value = null; mergeViewModel.onVideosSelected(listOf(uri to name)); screen = 4 },
                        onConvert = { pendingOpen.value = null; converterViewModel.onAudioFilesSelected(listOf(uri to name)); screen = 1 }
                    )
                }

                // ── YUMUŞAK DÖNME GEÇİŞİ ──────────────────────────────────────
                // Cihaz yatay/dikey çevrildiğinde yerleşim anında değişiyordu ("çat"
                // etkisi). Artık dönme anında tam ekran bir perde kısa süreliğine
                // kapanıp yumuşakça açılıyor: geçiş akıcı görünüyor, kare kaybı yok.
                // Perde sadece GPU'da saydamlık olarak çizilir (alpha, draw fazında
                // okunur) — yeniden kompozisyon, ek kare üretimi veya CPU yükü yok.
                val orientation = LocalConfiguration.current.orientation
                val rotationScrim = remember { Animatable(0f) }
                var firstOrientation by remember { mutableStateOf(true) }
                LaunchedEffect(orientation) {
                    if (firstOrientation) {
                        firstOrientation = false
                    } else {
                        rotationScrim.snapTo(0.75f)
                        rotationScrim.animateTo(0f, animationSpec = tween(220, easing = LinearOutSlowInEasing))
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                when (screen) {
                    0 -> MuxScreen(
                        viewModel = muxViewModel,
                        onPickVideo = { pickVideoLauncher.launch(arrayOf("video/*", "*/*")) },
                        onPickFolder = { pickMuxFolderLauncher.launch(null) },
                        onPickAudio = { pickMuxAudioLauncher.launch(arrayOf("audio/*", "video/*", "*/*")) },
                        onPickSubtitle = { pickSubtitleLauncher.launch(arrayOf("text/*", "application/x-subrip", "*/*")) },
                        onPickOutputFolder = { pickMuxOutputFolderLauncher.launch(null) },
                        onNavigateToConverter = { screen = 1 },
                        onNavigateToTrim = { screen = 2 },
                        onNavigateToMerge = { screen = 4 },
                        onNavigateToSettings = { screen = 3 },
                        onOpenPlayer = { screen = 6 },
                        onNavigateHome = { screen = HOME_SCREEN }
                    )
                    1 -> ConverterScreen(
                        viewModel = converterViewModel,
                        onPickAudio = { pickConverterAudioLauncher.launch(arrayOf("audio/*", "video/*", "*/*")) },
                        onPickOutputFolder = { pickConverterOutputFolderLauncher.launch(null) },
                        onNavigateBack = { screen = HOME_SCREEN }
                    )
                    2 -> TrimScreen(
                        viewModel = trimViewModel,
                        onPickVideo = { pickTrimVideoLauncher.launch(arrayOf("video/*", "audio/*", "*/*")) },
                        onPickFolder = { pickTrimFolderLauncher.launch(null) },
                        onPickOutputFolder = { pickTrimOutputFolderLauncher.launch(null) },
                        onNavigateBack = { screen = HOME_SCREEN }
                    )
                    5 -> {
                        if (playSlots.isEmpty()) {
                            LaunchedEffect(Unit) { screen = 6 }
                        } else {
                            StreamPlayerScreen(
                                slots = playSlots,
                                onAddVideo = { uri, title -> addSlotInPlayer(uri, title) },
                                onRemoveSlot = { id ->
                                    val target = playSlots.firstOrNull { it.id == id }
                                    DriveStreamResolver.releaseSession(target?.uri)
                                    playSlots.removeAll { it.id == id }
                                    if (playSlots.isEmpty()) exitPlayer()
                                },
                                onUpdateSlotUri = { id, newUri, newTitle ->
                                    val idx = playSlots.indexOfFirst { it.id == id }
                                    if (idx >= 0) {
                                        playSlots[idx] = playSlots[idx].copy(
                                            uri = newUri,
                                            title = newTitle ?: playSlots[idx].title
                                        )
                                    }
                                },
                                onPickSystemFileForAdd = {
                                    pickingToAddSlot = true
                                    pickPlayVideoLauncher.launch(arrayOf("video/*", "audio/*"))
                                },
                                onNeedStorage = { requestStorageAccess() },
                                hasStorage = { com.example.muxmaster.data.InputAccess.hasStorageAccess(this@MainActivity) },
                                onExit = { exitPlayer() }
                            )
                        }
                    }
                    6 -> PlayerHomeScreen(
                        onBack = { screen = HOME_SCREEN },
                        onOpenUrl = { text ->
                            val u = if (text.contains("://")) text else "https://$text"
                            handleInternalPlayerOpen(Uri.parse(u), null)
                        },
                        onPickFile = {
                            pickingToAddSlot = false
                            pickPlayVideoLauncher.launch(arrayOf("video/*", "audio/*"))
                        },
                        onPlayFile = { f ->
                            handleInternalPlayerOpen(Uri.fromFile(f), f.name)
                        },
                        onOpenStreamUri = { streamUri ->
                            handleInternalPlayerOpen(streamUri, null)
                        },
                        onNeedStorage = { requestStorageAccess() },
                        hasStorage = { com.example.muxmaster.data.InputAccess.hasStorageAccess(this@MainActivity) }
                    )
                    4 -> MergeScreen(
                        viewModel = mergeViewModel,
                        onPickVideos = { pickMergeVideosLauncher.launch(arrayOf("video/*", "*/*")) },
                        onPickOutputFolder = { pickMergeOutputFolderLauncher.launch(null) },
                        onNavigateBack = { screen = HOME_SCREEN }
                    )
                    7 -> HomeScreen(
                        onOpenMux = { screen = 0 },
                        onOpenTrim = { screen = 2 },
                        onOpenMerge = { screen = 4 },
                        onOpenPlayer = { screen = 6 },
                        onOpenConverter = { screen = 1 },
                        onOpenSettings = { screen = 3 },
                        onOpenFile = { media ->
                            val uri = runCatching { Uri.parse(media.uriString) }.getOrNull()
                            if (uri != null) {
                                openSingleInPlayer(uri, media.name, fromExternal = false)
                            }
                        },
                        onShowAllFiles = { screen = 6 }
                    )
                    else -> SettingsScreen(
                        outputFolderUri = muxViewModel.outputFolderUri,
                        onPickOutputFolder = { pickDefaultOutputFolderLauncher.launch(null) },
                        onNavigateBack = { screen = HOME_SCREEN },
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            themeMode = mode
                            appPrefs.themeMode = mode.toPrefValue()
                        }
                    )
                }

                // Dönme perdesi (dokunma olaylarını yakalamaz, sadece görsel).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .graphicsLayer { alpha = rotationScrim.value }
                )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        screenState?.let { handleIncomingIntent(intent) }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getStreamUriCompat()
            else -> null
        } ?: return

        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            if (playSlots.isNotEmpty()) {
                pendingOpen.value = uri to (uri.lastPathSegment ?: uri.toString())
            } else {
                openSingleInPlayer(uri, fromExternal = true)
            }
            intent.action = null
            return
        }

        if (!DriveStreamResolver.isGoogleDriveSafUri(uri)) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: Exception) { }
        }

        val displayName = queryDisplayName(uri) ?: "file"
        val mimeType = intent.type ?: contentResolver.getType(uri).orEmpty()

        when {
            mimeType.startsWith("video/") || displayName.substringAfterLast('.', "").lowercase() in
                setOf("mkv", "mp4", "avi", "mov", "webm", "ts", "m2ts", "m4v", "3gp", "flv", "wmv") -> {
                pendingOpen.value = uri to displayName
            }
            mimeType.startsWith("audio/") -> {
                converterViewModel.onAudioFilesSelected(listOf(uri to displayName))
                screenState?.value = 1
            }
        }

        intent.action = null
    }

    override fun onResume() {
        super.onResume()
        if (!notifAsked && com.example.muxmaster.data.InputAccess.hasStorageAccess(this) && Build.VERSION.SDK_INT >= 33) {
            notifAsked = true
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    private var notifAsked = false

    companion object {
        /** Ana ekran (kart menüsü) ekran numarası. */
        private const val HOME_SCREEN = 7
    }

    @Suppress("DEPRECATION")
    private fun Intent.getStreamUriCompat(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableExtra(Intent.EXTRA_STREAM)

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null }
    } catch (_: Exception) { null }
}

@Composable
private fun SingleOrMultiPlayDialog(
    fileName: String,
    onDismiss: () -> Unit,
    onPlaySingle: () -> Unit,
    onPlayMulti: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_play_how_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(fileName, maxLines = 2)
                OutlinedButton(onClick = onPlaySingle, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.PlayCircle, null, modifier = Modifier.padding(end = 10.dp))
                    Text(stringResource(R.string.dialog_play_single), modifier = Modifier.weight(1f))
                }
                OutlinedButton(onClick = onPlayMulti, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.GridView, null, modifier = Modifier.padding(end = 10.dp))
                    Text(stringResource(R.string.dialog_play_multi), modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } }
    )
}

@Composable
private fun OpenWithChooser(
    fileName: String,
    onDismiss: () -> Unit,
    onPlaySingle: () -> Unit,
    onPlayMulti: () -> Unit,
    onMux: () -> Unit,
    onTrim: () -> Unit,
    onMerge: () -> Unit,
    onConvert: () -> Unit
) {
    @Composable
    fun Choice(icon: ImageVector, label: String, onClick: () -> Unit) {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Icon(icon, null, modifier = Modifier.padding(end = 10.dp))
            Text(label, modifier = Modifier.weight(1f))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_open_with_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(fileName, maxLines = 2)
                Choice(Icons.Filled.PlayCircle, stringResource(R.string.open_with_play), onPlaySingle)
                Choice(Icons.Filled.GridView, stringResource(R.string.open_with_multi), onPlayMulti)
                Choice(Icons.Filled.Layers, stringResource(R.string.open_with_mux), onMux)
                Choice(Icons.Filled.ContentCut, stringResource(R.string.open_with_trim), onTrim)
                Choice(Icons.Filled.CallMerge, stringResource(R.string.open_with_merge), onMerge)
                Choice(Icons.Filled.GraphicEq, stringResource(R.string.open_with_convert), onConvert)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } }
    )
}
