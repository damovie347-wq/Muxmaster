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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.muxmaster.data.AppPreferences
import com.example.muxmaster.data.DriveStreamResolver
import com.example.muxmaster.ui.screens.ConverterScreen
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

    /** Dışarıdan (dosya yöneticisi "Birlikte aç" / "Paylaş") gelen bekleyen medya. */
    private val pendingExternal = mutableStateOf<Pair<Uri, String>?>(null)

    /** Video Oynatıcı ekranı (screen=5) için aktif video yuvaları (1..4). */
    private val playSlots = mutableStateListOf<PlayerVideoSlot>()
    private var nextSlotId = 1L
    /** Oynatıcı dışarıdan (Birlikte Aç) mı açıldı? Geri basınca uygulamadan çıkılsın. */
    private val playFromExternal = mutableStateOf(false)
    /** Oynatıcı zaten açıkken yeni bir video geldiğinde "Tek mi Çoklu mu?" sorusu. */
    private val pendingSingleOrMulti = mutableStateOf<Pair<Uri, String?>?>(null)
    /** Oynatıcı içindeki "+" butonuyla mı dosya seçiliyor? */
    private var pickingToAddSlot = false

    private var screenState: MutableState<Int>? = null

    private fun openSingleInPlayer(uri: Uri, title: String?, fromExternal: Boolean) {
        DriveStreamResolver.releaseAllSessions()
        playSlots.clear()
        playSlots.add(PlayerVideoSlot(nextSlotId++, uri, title))
        playFromExternal.value = fromExternal
        screenState?.value = 5
    }

    private fun addSlotInPlayer(uri: Uri, title: String?, fromExternal: Boolean = false) {
        if (playSlots.isEmpty()) {
            openSingleInPlayer(uri, title, fromExternal)
        } else if (playSlots.size < 4) {
            playSlots.add(PlayerVideoSlot(nextSlotId++, uri, title))
            screenState?.value = 5
        } else {
            val removed = playSlots.lastOrNull()
            DriveStreamResolver.releaseSession(removed?.uri)
            playSlots[playSlots.lastIndex] = PlayerVideoSlot(nextSlotId++, uri, title)
            screenState?.value = 5
        }
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
    private fun createUniversalSingleDocumentContract() = object : ActivityResultContracts.OpenDocument() {
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

    private fun createUniversalMultipleDocumentsContract() = object : ActivityResultContracts.OpenMultipleDocuments() {
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
        screenState?.value = if (external) 0 else 6
        if (external) {
            if (!moveTaskToBack(true)) finish()
        }
    }

    /** Depolama (Tüm dosyalara erişim) izni sorulsun mu? */
    private val askStorage = mutableStateOf(false)

    private val pickVideoLauncher = registerForActivityResult(createUniversalSingleDocumentContract()) { uri ->
        if (uri != null) muxViewModel.onVideoSelected(uri, queryDisplayName(uri) ?: "video.mkv")
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
            converterViewModel.onAudioFilesSelected(uris.map { u -> u to (queryDisplayName(u) ?: "audio") })
        }
    }
    private val pickConverterOutputFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) converterViewModel.setOutputFolder(uri)
    }
    private val pickTrimVideoLauncher = registerForActivityResult(createUniversalSingleDocumentContract()) { uri ->
        if (uri != null) trimViewModel.onVideoSelected(uri, queryDisplayName(uri) ?: "video.mkv")
    }
    private val pickTrimFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) trimViewModel.onFolderSelected(uri)
    }
    private val pickTrimOutputFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) trimViewModel.setOutputFolder(uri)
    }
    private val pickMergeVideosLauncher = registerForActivityResult(createUniversalMultipleDocumentsContract()) { uris ->
        if (uris.isNotEmpty()) {
            mergeViewModel.onVideosSelected(uris.map { u -> u to (queryDisplayName(u) ?: "video.mkv") })
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // İlk açılışta (henüz dosya izni yokken) kullanıcıya sor
        if (!com.example.muxmaster.data.InputAccess.hasStorageAccess(this)) {
            val prefs = getSharedPreferences("perm_prefs", MODE_PRIVATE)
            if (!prefs.getBoolean("storage_asked", false)) {
                prefs.edit().putBoolean("storage_asked", true).apply()
                askStorage.value = true
            }
        }
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        // Video oynatıcı dışarıdan (Birlikte Aç) açılmadıysa arka planda bildirim iznini kontrol et;
        // doğrudan video oynatıcıya girildiyse sistem izin diyaloğu videonun üstünü kapatmasın.
        if (playSlots.isEmpty()) {
            requestNotificationPermissionIfNeeded()
        }

        // Medya kodeklerini (video/ses çözücülerini) uygulama açılır açılmaz arka planda ısıt;
        // böylece kullanıcı bir videoya tıkladığında ilk kare anında açılır.
        Thread { prewarmVideoDecoders() }.start()

        val appPrefs = AppPreferences(this)

        setContent {
            var themeMode by remember { mutableStateOf(ThemeMode.fromPrefValue(appPrefs.themeMode)) }

            MuxMasterTheme(themeMode = themeMode) {
                // 0 = Mux, 1 = Ses Dönüştürücü, 2 = Kırp, 3 = Ayarlar, 4 = Birleştir, 5 = Video Oynatıcı (aktif), 6 = Video Oynatıcı (ana ekran)
                val screenStateLocal = rememberSaveable { mutableStateOf(if (playSlots.isNotEmpty()) 5 else 0) }
                screenState = screenStateLocal
                var screen by screenStateLocal

                BackHandler(enabled = screen != 0) {
                    if (screen == 5) exitPlayer() else screen = 0
                }

                if (askStorage.value && screen != 5) {
                    AlertDialog(
                        onDismissRequest = { askStorage.value = false },
                        title = { Text("Dosya Erişim İzni") },
                        text = {
                            Text(
                                "Videoları doğrudan ve anında (kopyalamadan, tam hızda) " +
                                    "oynatabilmek ve işleyebilmek için 'Tüm dosyalara erişim' " +
                                    "iznini açmanız önerilir."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                askStorage.value = false
                                requestStorageAccess()
                            }) { Text("İzni Aç") }
                        },
                        dismissButton = {
                            TextButton(onClick = { askStorage.value = false }) { Text("Daha Sonra") }
                        }
                    )
                }

                // Oynatıcı zaten açıkken yeni video seçildiğinde: "Tek video mu, Çoklu videoya ekle mi?"
                val singleOrMulti = pendingSingleOrMulti.value
                if (singleOrMulti != null) {
                    val (u, t) = singleOrMulti
                    SingleOrMultiPlayDialog(
                        fileName = t ?: (u.lastPathSegment ?: "Medya"),
                        onDismiss = { pendingSingleOrMulti.value = null },
                        onPlaySingle = {
                            pendingSingleOrMulti.value = null
                            openSingleInPlayer(u, t, fromExternal = false)
                        },
                        onPlayMulti = {
                            pendingSingleOrMulti.value = null
                            addSlotInPlayer(u, t, fromExternal = false)
                        }
                    )
                }

                // Dışarıdan yalnızca "Paylaş (SEND)" ile geldiğinde ne yapılacağını soran seçici
                val ext = pendingExternal.value
                if (ext != null) {
                    val (uri, name) = ext
                    OpenWithChooser(
                        fileName = name,
                        onDismiss = { pendingExternal.value = null },
                        onPlaySingle = {
                            pendingExternal.value = null
                            openSingleInPlayer(uri, name, fromExternal = true)
                        },
                        onPlayMulti = {
                            pendingExternal.value = null
                            addSlotInPlayer(uri, name, fromExternal = true)
                        },
                        onMux = { pendingExternal.value = null; muxViewModel.onVideoSelected(uri, name); screen = 0 },
                        onTrim = { pendingExternal.value = null; trimViewModel.onVideoSelected(uri, name); screen = 2 },
                        onMerge = { pendingExternal.value = null; mergeViewModel.onVideosSelected(listOf(uri to name)); screen = 4 },
                        onConvert = { pendingExternal.value = null; converterViewModel.onAudioFilesSelected(listOf(uri to name)); screen = 1 }
                    )
                }

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
                        onOpenPlayer = { screen = 6 }
                    )
                    1 -> ConverterScreen(
                        viewModel = converterViewModel,
                        onPickAudio = { pickConverterAudioLauncher.launch(arrayOf("audio/*", "video/*", "*/*")) },
                        onPickOutputFolder = { pickConverterOutputFolderLauncher.launch(null) },
                        onNavigateToMux = { screen = 0 }
                    )
                    2 -> TrimScreen(
                        viewModel = trimViewModel,
                        onPickVideo = { pickTrimVideoLauncher.launch(arrayOf("video/*", "audio/*", "*/*")) },
                        onPickFolder = { pickTrimFolderLauncher.launch(null) },
                        onPickOutputFolder = { pickTrimOutputFolderLauncher.launch(null) },
                        onNavigateBack = { screen = 0 }
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
                        onBack = { screen = 0 },
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
                        onNavigateBack = { screen = 0 }
                    )
                    else -> SettingsScreen(
                        outputFolderUri = muxViewModel.outputFolderUri,
                        onPickOutputFolder = { pickDefaultOutputFolderLauncher.launch(null) },
                        onNavigateBack = { screen = 0 },
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            themeMode = mode
                            appPrefs.themeMode = mode.toPrefValue()
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val uri: Uri? = when (action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getStreamUriCompat()
            else -> null
        }
        if (uri != null) {
            if (!DriveStreamResolver.isGoogleDriveSafUri(uri)) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) { }
            }
            val name = queryDisplayName(uri) ?: (uri.lastPathSegment ?: "video.mp4")
            if (action == Intent.ACTION_VIEW) {
                pendingExternal.value = null
                if (playSlots.isNotEmpty() && screenState?.value == 5) {
                    playFromExternal.value = true
                    pendingSingleOrMulti.value = uri to name
                } else {
                    openSingleInPlayer(uri, name, fromExternal = true)
                }
            } else {
                pendingExternal.value = uri to name
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (notifAsked) return
        notifAsked = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    private var notifAsked = false

    @Suppress("DEPRECATION")
    private fun Intent.getStreamUriCompat(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableExtra(Intent.EXTRA_STREAM)

    private fun queryDisplayName(uri: Uri): String? {
        val queried = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null }
        } catch (_: Exception) { null }
        if (!queried.isNullOrBlank()) return queried
        val fromPath = uri.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        return fromPath ?: uri.lastPathSegment
    }
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
        title = { Text("Videoyu nasıl oynatmak istiyorsunuz?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(fileName, maxLines = 2)
                OutlinedButton(onClick = onPlaySingle, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.PlayCircle, null, modifier = Modifier.padding(end = 10.dp))
                    Text("Tek video olarak oynat", modifier = Modifier.weight(1f))
                }
                OutlinedButton(onClick = onPlayMulti, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.GridView, null, modifier = Modifier.padding(end = 10.dp))
                    Text("Çoklu videoya ekle (Multi Video)", modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("İptal") } }
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
        title = { Text("Ne yapmak istiyorsunuz?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(fileName, maxLines = 2)
                Choice(Icons.Filled.PlayCircle, "Oynat (video oynatıcı)", onPlaySingle)
                Choice(Icons.Filled.GridView, "Çoklu Oynat (Multi Video)", onPlayMulti)
                Choice(Icons.Filled.Layers, "Mux Master (ses / altyazı ekle-çıkar)", onMux)
                Choice(Icons.Filled.ContentCut, "Kırp / Kes", onTrim)
                Choice(Icons.Filled.CallMerge, "Birleştir", onMerge)
                Choice(Icons.Filled.GraphicEq, "Sesi dönüştür", onConvert)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("İptal") } }
    )
}
