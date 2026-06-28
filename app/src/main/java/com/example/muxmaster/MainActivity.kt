package com.example.muxmaster

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.example.muxmaster.ui.screens.MuxScreen
import com.example.muxmaster.ui.theme.MuxMasterTheme
import com.example.muxmaster.viewmodel.MuxViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MuxViewModel by viewModels()

    // ADIM 1: Video seç
    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "video.mkv"
            viewModel.onVideoSelected(uri, name)
        }
    }

    // ADIM 3: Yeni ses dosyası ekle
    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "audio_track"
            viewModel.addAudioFile(uri, name)
        }
    }

    // ADIM 4: Yeni altyazı dosyası ekle
    private val pickSubtitleLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "subtitle_track"
            viewModel.addSubtitleFile(uri, name)
        }
    }

    // Çıktı klasörü seçimi
    private val pickOutputFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) viewModel.setOutputFolder(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MuxMasterTheme {
                MuxScreen(
                    viewModel = viewModel,
                    onPickVideo = {
                        pickVideoLauncher.launch(arrayOf("video/*"))
                    },
                    onPickAudio = {
                        pickAudioLauncher.launch(arrayOf("audio/*", "video/*", "*/*"))
                    },
                    onPickSubtitle = {
                        pickSubtitleLauncher.launch(
                            arrayOf(
                                "text/*",
                                "application/x-subrip",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    },
                    onPickOutputFolder = {
                        pickOutputFolderLauncher.launch(null)
                    }
                )
            }
        }
    }

    /**
     * SAF Uri.lastPathSegment GÜVENİLMEZ (opak document-id döner, gerçek dosya adı değil).
     * Gerçek dosya adını almak için ContentResolver.query ile OpenableColumns.DISPLAY_NAME okunur.
     */
    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        } catch (e: Exception) {
            null
        }
    }
}
