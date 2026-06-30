package com.example.muxmaster

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.muxmaster.ui.screens.ConverterScreen
import com.example.muxmaster.ui.screens.MuxScreen
import com.example.muxmaster.ui.theme.MuxMasterTheme
import com.example.muxmaster.viewmodel.ConverterViewModel
import com.example.muxmaster.viewmodel.MuxViewModel

class MainActivity : ComponentActivity() {

    private val muxViewModel: MuxViewModel by viewModels()
    private val converterViewModel: ConverterViewModel by viewModels()

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
    private val pickConverterAudioLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) converterViewModel.onAudioSelected(uri, queryDisplayName(uri) ?: "audio")
    }
    private val pickConverterOutputFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) converterViewModel.setOutputFolder(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MuxMasterTheme {
                var screen by remember { mutableIntStateOf(0) }
                when (screen) {
                    0 -> MuxScreen(
                        viewModel = muxViewModel,
                        onPickVideo = { pickVideoLauncher.launch(arrayOf("video/*")) },
                        onPickAudio = { pickMuxAudioLauncher.launch(arrayOf("audio/*", "video/*", "*/*")) },
                        onPickSubtitle = { pickSubtitleLauncher.launch(arrayOf("text/*", "application/x-subrip", "*/*")) },
                        onPickOutputFolder = { pickMuxOutputFolderLauncher.launch(null) },
                        onNavigateToConverter = { screen = 1 }
                    )
                    else -> ConverterScreen(
                        viewModel = converterViewModel,
                        onPickAudio = { pickConverterAudioLauncher.launch(arrayOf("audio/*", "video/*", "*/*")) },
                        onPickOutputFolder = { pickConverterOutputFolderLauncher.launch(null) },
                        onNavigateToMux = { screen = 0 }
                    )
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null }
    } catch (_: Exception) { null }
}
