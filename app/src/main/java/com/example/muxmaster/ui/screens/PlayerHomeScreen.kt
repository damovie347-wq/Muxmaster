package com.example.muxmaster.ui.screens

import android.net.Uri
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.muxmaster.data.DriveStreamResolver
import com.example.muxmaster.data.GoogleDriveBrowserDialog
import com.example.muxmaster.ui.theme.BgDark
import com.example.muxmaster.ui.theme.PurpleLight
import com.example.muxmaster.ui.theme.TextMuted
import com.example.muxmaster.ui.theme.TextPrimary
import com.example.muxmaster.ui.theme.TextSec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal val PLAYABLE_EXT = setOf(
    "mkv", "mp4", "m4v", "webm", "avi", "mov", "ts", "m2ts", "mts", "3gp", "flv", "wmv", "mpg", "mpeg",
    "ogv", "mp3", "m4a", "aac", "flac", "opus", "ogg", "wav", "mka", "ac3", "eac3"
)
internal val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "opus", "ogg", "wav", "mka", "ac3", "eac3")

/**
 * Oynatıcı giriş ekranı:
 * - URL veya Google Drive bağlantısı aç
 * - Google Drive'dan doğrudan (cihaza indirmeden, akış olarak) video seç ve oynat
 * - Sistem seçicisiyle dosya seç (Google Drive seçilse bile indirmeden akışa yönlendirir)
 * - Uygulama içi yerel dosya gezgini
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerHomeScreen(
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onPickFile: () -> Unit,
    onPlayFile: (File) -> Unit,
    onOpenStreamUri: (Uri) -> Unit = { u -> onOpenUrl(u.toString()) },
    onNeedStorage: () -> Unit,
    hasStorage: () -> Boolean
) {
    var browsing by rememberSaveable { mutableStateOf(false) }
    var showDriveBrowser by rememberSaveable { mutableStateOf(false) }
    var driveInitialFileId by rememberSaveable { mutableStateOf<String?>(null) }

    if (browsing) {
        LocalFileBrowser(onClose = { browsing = false }, onPlayFile = onPlayFile)
        return
    }

    if (showDriveBrowser) {
        GoogleDriveBrowserDialog(
            initialFileId = driveInitialFileId,
            autoSearchFileName = null,
            onDismiss = {
                showDriveBrowser = false
                driveInitialFileId = null
            },
            onStreamReady = { streamUri, _ ->
                showDriveBrowser = false
                driveInitialFileId = null
                onOpenStreamUri(streamUri)
            }
        )
    }

    var url by rememberSaveable { mutableStateOf("") }
    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Geri", tint = TextSec)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PlayCircle, null, tint = PurpleLight, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.size(12.dp))
                        Text("Video Oynatıcı", color = TextPrimary, fontWeight = FontWeight.Medium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp).widthIn(max = 420.dp)
            ) {
                val openUrl = {
                    val trimmed = url.trim()
                    if (trimmed.isNotBlank()) {
                        val driveId = if (DriveStreamResolver.isGoogleDriveUrl(trimmed)) {
                            DriveStreamResolver.extractDriveFileId(trimmed)
                        } else null
                        if (driveId != null) {
                            driveInitialFileId = driveId
                            showDriveBrowser = true
                        } else {
                            onOpenUrl(trimmed)
                        }
                    }
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL veya Google Drive Bağlantısı") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { openUrl() }),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.size(4.dp))
                Button(onClick = openUrl, enabled = url.isNotBlank()) {
                    Icon(Icons.Filled.Link, null, modifier = Modifier.padding(end = 8.dp))
                    Text("URL'yi aç")
                }
                OutlinedButton(onClick = {
                    driveInitialFileId = null
                    showDriveBrowser = true
                }) {
                    Icon(Icons.Filled.CloudQueue, null, modifier = Modifier.padding(end = 8.dp))
                    Text("Google Drive'dan aç (İndirmeden)")
                }
                OutlinedButton(onClick = onPickFile) {
                    Icon(Icons.Filled.UploadFile, null, modifier = Modifier.padding(end = 8.dp))
                    Text("Dosya seç")
                }
                OutlinedButton(onClick = { if (hasStorage()) browsing = true else onNeedStorage() }) {
                    Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.padding(end = 8.dp))
                    Text("Dosya gezginini aç")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalFileBrowser(onClose: () -> Unit, onPlayFile: (File) -> Unit) {
    val root = remember { Environment.getExternalStorageDirectory() }
    var path by rememberSaveable { mutableStateOf(root.absolutePath) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    val dir = File(path)

    LaunchedEffect(path) {
        entries = withContext(Dispatchers.IO) {
            (File(path).listFiles() ?: emptyArray())
                .filter { !it.name.startsWith(".") && (it.isDirectory || it.extension.lowercase() in PLAYABLE_EXT) }
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        }
    }
    val goUp: () -> Unit = {
        if (dir.absolutePath == root.absolutePath || dir.parentFile == null) onClose()
        else path = dir.parentFile!!.absolutePath
    }
    BackHandler(onBack = goUp)

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = goUp) {
                        Icon(Icons.Filled.ArrowBack, "Geri", tint = TextSec)
                    }
                },
                title = {
                    Column {
                        Text(if (dir.absolutePath == root.absolutePath) "Dahili depolama" else dir.name,
                            color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(dir.absolutePath, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        }
    ) { pad ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("Bu klasörde video yok", color = TextMuted)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                items(entries, key = { it.absolutePath }) { f ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (f.isDirectory) path = f.absolutePath else onPlayFile(f) }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        val ext = f.extension.lowercase()
                        Icon(
                            when {
                                f.isDirectory -> Icons.Filled.Folder
                                ext in AUDIO_EXT -> Icons.Filled.MusicNote
                                else -> Icons.Filled.Movie
                            },
                            null,
                            tint = if (f.isDirectory) PurpleLight else TextSec,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.size(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (!f.isDirectory) {
                                Text(sizeLabel(f.length()), color = TextMuted,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sizeLabel(b: Long): String = when {
    b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}
