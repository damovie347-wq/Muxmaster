package com.example.muxmaster

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import com.example.muxmaster.data.AppPreferences
import com.example.muxmaster.ui.screens.ConverterScreen
import com.example.muxmaster.ui.screens.MergeScreen
import com.example.muxmaster.ui.screens.MuxScreen
import com.example.muxmaster.ui.screens.SettingsScreen
import com.example.muxmaster.ui.screens.TrimScreen
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

    /** "Birlikte aç" ile gelen video: kullanıcıya ne yapmak istediği sorulur. */
    private val pendingOpen = mutableStateOf<Pair<Uri, String>?>(null)
    /** Depolama (Tüm dosyalara erişim) izni sorulsun mu? */
    private val askStorage = mutableStateOf(false)

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) muxViewModel.onVideoSelected(uri, queryDisplayName(uri) ?: "video.mkv")
    }
    private val pickMuxAudioLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) muxViewModel.addAudioFile(uri, queryDisplayName(uri) ?: "audio_track")
    }
    private val pickSubtitleLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) muxViewModel.addSubtitleFile(uri, queryDisplayName(uri) ?: "subtitle_track")
    }
    private val pickMuxOutputFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) muxViewModel.setOutputFolder(uri)
    }
    private val pickConverterAudioLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            converterViewModel.onAudioFilesSelected(uris.map { u -> u to (queryDisplayName(u) ?: "audio") })
        }
    }
    private val pickConverterOutputFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) converterViewModel.setOutputFolder(uri)
    }
    private val pickTrimVideoLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) trimViewModel.onVideoSelected(uri, queryDisplayName(uri) ?: "video.mkv")
    }
    private val pickTrimFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) trimViewModel.onFolderSelected(uri)
    }
    private val pickTrimOutputFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) trimViewModel.setOutputFolder(uri)
    }
    private val pickMergeVideosLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
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
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* sonuç ne olursa olsun akış devam eder */ }

    private val appPrefs by lazy { AppPreferences(applicationContext) }

    private fun ThemeMode.toPrefValue(): String = when (this) {
        ThemeMode.LIGHT -> "light"; ThemeMode.DARK -> "dark"; ThemeMode.AMOLED -> "amoled"; ThemeMode.SYSTEM -> "system"
    }
    private fun String.toThemeMode(): ThemeMode = when (this) {
        "light" -> ThemeMode.LIGHT; "system" -> ThemeMode.SYSTEM; "amoled" -> ThemeMode.AMOLED; else -> ThemeMode.DARK
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Önceki oturumdan kalan geçici dosyaları temizle (çalışan bir işlem yoksa).
        if (savedInstanceState == null &&
            !muxViewModel.isMuxing && !trimViewModel.isTrimming &&
            !converterViewModel.isConverting && !mergeViewModel.isMerging
        ) {
            Thread { com.example.muxmaster.data.InputAccess.clearWorkCache(applicationContext) }.start()
        }
        // Arka planda mux ilerleme bildirimi gösterilebilmesi için (Android 13+/API 33+
        // bildirim izni ister); reddedilirse mux yine çalışır, sadece bildirim görünmez.
        // Depolama izni: videolar önbelleğe KOPYALANMADAN doğrudan cihazdaki yoldan açılır.
        if (!com.example.muxmaster.data.InputAccess.hasStorageAccess(this)) {
            if (Build.VERSION.SDK_INT >= 30) askStorage.value = true
            else requestStorageAccess()
        } else if (Build.VERSION.SDK_INT >= 33) {
            notifAsked = true
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            var themeMode by remember { mutableStateOf(appPrefs.themeMode.toThemeMode()) }
            MuxMasterTheme(themeMode = themeMode) {
                // rememberSaveable: ekran döndürülünce / Activity yeniden oluşturulunca
                // kullanıcı bulunduğu ekranda kalır (eskiden ana ekrana atıyordu).
                val screenHolder = rememberSaveable { mutableStateOf(0) }
                screenState = screenHolder
                var screen by screenHolder

                // Geri tuşu: alt ekrandaysan ana ekrana döner (uygulamadan çıkmaz).
                BackHandler(enabled = screen != 0) { screen = 0 }

                LaunchedEffect(Unit) { handleIncomingIntent(intent) }

                if (askStorage.value) {
                    AlertDialog(
                        onDismissRequest = { askStorage.value = false },
                        title = { Text("Depolama izni") },
                        text = {
                            Text(
                                "Videoların önbelleğe kopyalanmadan, beklemeden doğrudan açılması için " +
                                    "\"Tüm dosyalara erişim\" iznini açın."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { askStorage.value = false; requestStorageAccess() }) { Text("İzin ver") }
                        },
                        dismissButton = {
                            TextButton(onClick = { askStorage.value = false }) { Text("Şimdi değil") }
                        }
                    )
                }

                pendingOpen.value?.let { (uri, name) ->
                    OpenWithChooser(
                        fileName = name,
                        onDismiss = { pendingOpen.value = null },
                        onMux = { pendingOpen.value = null; muxViewModel.onVideoSelected(uri, name); screen = 0 },
                        onTrim = { pendingOpen.value = null; trimViewModel.onVideoSelected(uri, name); screen = 2 },
                        onMerge = { pendingOpen.value = null; mergeViewModel.onVideosSelected(listOf(uri to name)); screen = 4 },
                        onConvert = { pendingOpen.value = null; converterViewModel.onAudioFilesSelected(listOf(uri to name)); screen = 1 }
                    )
                }

                when (screen) {
                    0 -> MuxScreen(
                        viewModel = muxViewModel,
                        onPickVideo = { pickVideoLauncher.launch(arrayOf("video/*", "*/*")) },
                        onPickAudio = { pickMuxAudioLauncher.launch(arrayOf("audio/*", "video/*", "*/*")) },
                        onPickSubtitle = { pickSubtitleLauncher.launch(arrayOf("text/*", "application/x-subrip", "*/*")) },
                        onPickOutputFolder = { pickMuxOutputFolderLauncher.launch(null) },
                        onNavigateToConverter = { screen = 1 },
                        onNavigateToTrim = { screen = 2 },
                        onNavigateToMerge = { screen = 4 },
                        onNavigateToSettings = { screen = 3 }
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
        screenState?.let { handleIncomingIntent(intent) }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getStreamUriCompat()
            else -> null
        } ?: return

        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { }

        val displayName = queryDisplayName(uri) ?: "file"
        val mimeType = intent.type ?: contentResolver.getType(uri).orEmpty()

        when {
            mimeType.startsWith("video/") || displayName.substringAfterLast('.', "").lowercase() in
                setOf("mkv", "mp4", "avi", "mov", "webm", "ts", "m2ts", "m4v", "3gp", "flv", "wmv") -> {
                // Doğrudan Mux'a atmak yerine kullanıcıya ne yapmak istediğini sor
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
        // İzin ekranından dönünce: izin verildiyse bildirim iznini de iste (bir kez)
        if (!notifAsked && com.example.muxmaster.data.InputAccess.hasStorageAccess(this) && Build.VERSION.SDK_INT >= 33) {
            notifAsked = true
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

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null }
    } catch (_: Exception) { null }
}

@Composable
private fun OpenWithChooser(
    fileName: String,
    onDismiss: () -> Unit,
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
