package com.example.muxmaster

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import com.example.muxmaster.ui.screens.ConverterScreen
import com.example.muxmaster.ui.screens.MuxScreen
import com.example.muxmaster.ui.screens.SettingsScreen
import com.example.muxmaster.ui.theme.MuxMasterTheme
import com.example.muxmaster.viewmodel.ConverterViewModel
import com.example.muxmaster.viewmodel.MuxViewModel

class MainActivity : AppCompatActivity() {

    private val muxViewModel: MuxViewModel by viewModels()
    private val converterViewModel: ConverterViewModel by viewModels()

    // Compose dışından (onNewIntent) hangi ekranda olduğumuzu değiştirebilmek için.
    // 0 = Muxlayıcı, 1 = Ses Dönüştürücü, 2 = Ayarlar
    private var screenState: MutableState<Int>? = null

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
    // Ayarlar ekranındaki "Varsayılan Çıktı Klasörü" seçicisi: seçilen klasör
    // hem her iki ViewModel'in o anki oturumuna hem de kalıcı varsayılana yazılır.
    private val pickDefaultOutputFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            muxViewModel.setOutputFolder(uri)
            converterViewModel.setOutputFolder(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MuxMasterTheme {
                val screenHolder = remember { mutableIntStateOf(0) }
                screenState = screenHolder
                var screen by screenHolder

                // Soğuk başlangıç: uygulama bir dosya yöneticisinden "Birlikte Aç" /
                // "Paylaş" ile açıldıysa geleni burada işle.
                LaunchedEffect(Unit) { handleIncomingIntent(intent) }

                when (screen) {
                    0 -> MuxScreen(
                        viewModel = muxViewModel,
                        onPickVideo = { pickVideoLauncher.launch(arrayOf("video/*", "*/*")) },
                        onPickAudio = { pickMuxAudioLauncher.launch(arrayOf("audio/*", "video/*", "*/*")) },
                        onPickSubtitle = { pickSubtitleLauncher.launch(arrayOf("text/*", "application/x-subrip", "*/*")) },
                        onPickOutputFolder = { pickMuxOutputFolderLauncher.launch(null) },
                        onNavigateToConverter = { screen = 1 },
                        onNavigateToSettings = { screen = 2 }
                    )
                    1 -> ConverterScreen(
                        viewModel = converterViewModel,
                        onPickAudio = { pickConverterAudioLauncher.launch(arrayOf("audio/*", "video/*", "*/*")) },
                        onPickOutputFolder = { pickConverterOutputFolderLauncher.launch(null) },
                        onNavigateToMux = { screen = 0 }
                    )
                    else -> SettingsScreen(
                        outputFolderUri = muxViewModel.outputFolderUri,
                        onPickOutputFolder = { pickDefaultOutputFolderLauncher.launch(null) },
                        onNavigateBack = { screen = 0 }
                    )
                }
            }
        }
    }

    // Uygulama zaten açıkken başka bir dosya yöneticisinden "Birlikte Aç" / "Paylaş"
    // ile tekrar çağrılırsa (launchMode="singleTask" sayesinde) onCreate değil bu
    // çağrılır.
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
            mimeType.startsWith("video/") -> {
                muxViewModel.onVideoSelected(uri, displayName)
                screenState?.value = 0
            }
            mimeType.startsWith("audio/") -> {
                converterViewModel.onAudioFilesSelected(listOf(uri to displayName))
                screenState?.value = 1
            }
        }

        intent.action = null
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
