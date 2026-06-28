package com.example.muxmaster.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.muxmaster.ui.components.AudioTrackCard
import com.example.muxmaster.ui.components.BottomMuxBar
import com.example.muxmaster.ui.components.SubtitleTrackCard
import com.example.muxmaster.ui.components.VideoCard
import com.example.muxmaster.ui.theme.*
import com.example.muxmaster.viewmodel.MuxViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuxScreen(
    viewModel: MuxViewModel,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onPickSubtitle: () -> Unit,
    onPickOutputFolder: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = { Text("MuxMaster", fontWeight = FontWeight.Bold, color = TextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        },
        bottomBar = {
            BottomMuxBar(
                outputFolderUri = viewModel.outputFolderUri,
                outputFileName = viewModel.outputFileName,
                isMuxing = viewModel.isMuxing,
                muxProgress = viewModel.muxProgress,
                resultMessage = viewModel.resultMessage,
                isSuccess = viewModel.isSuccess,
                canStartMux = viewModel.videoFile != null,
                onPickFolder = onPickOutputFolder,
                onFileNameChange = viewModel::setOutputFileName,
                onStart = viewModel::startMux
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            VideoCard(
                video = viewModel.videoFile,
                isLoading = viewModel.isLoading,
                loadingMessage = viewModel.loadingMessage,
                onPickVideo = onPickVideo,
                onClear = viewModel::clearVideo
            )

            Spacer(Modifier.height(16.dp))

            if (viewModel.videoFile != null) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = BgDark,
                    contentColor = Purple
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Sesler (${viewModel.audioTracks.size})") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Altyazılar (${viewModel.subtitleTracks.size})") }
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = if (selectedTab == 0) onPickAudio else onPickSubtitle) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = Purple, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (selectedTab == 0) "Ses Dosyası Ekle" else "Altyazı Dosyası Ekle",
                            color = Purple,
                            fontSize = 13.sp
                        )
                    }
                }

                if (selectedTab == 0) {
                    if (viewModel.audioTracks.isEmpty()) {
                        EmptyHint("Bu videoda ses track'i bulunamadı. + ile ekleyebilirsin.")
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(viewModel.audioTracks, key = { it.id }) { track ->
                                val idx = viewModel.audioTracks.indexOf(track)
                                AudioTrackCard(
                                    track = track,
                                    canMoveUp = idx > 0,
                                    canMoveDown = idx < viewModel.audioTracks.size - 1,
                                    onUpdate = viewModel::updateAudio,
                                    onRemove = { viewModel.removeAudio(track.id) },
                                    onMoveUp = { viewModel.moveAudioUp(track.id) },
                                    onMoveDown = { viewModel.moveAudioDown(track.id) }
                                )
                            }
                        }
                    }
                } else {
                    if (viewModel.subtitleTracks.isEmpty()) {
                        EmptyHint("Bu videoda altyazı track'i bulunamadı. + ile ekleyebilirsin.")
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(viewModel.subtitleTracks, key = { it.id }) { track ->
                                val idx = viewModel.subtitleTracks.indexOf(track)
                                SubtitleTrackCard(
                                    track = track,
                                    canMoveUp = idx > 0,
                                    canMoveDown = idx < viewModel.subtitleTracks.size - 1,
                                    onUpdate = viewModel::updateSubtitle,
                                    onRemove = { viewModel.removeSubtitle(track.id) },
                                    onMoveUp = { viewModel.moveSubtitleUp(track.id) },
                                    onMoveDown = { viewModel.moveSubtitleDown(track.id) }
                                )
                            }
                        }
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = TextMuted, fontSize = 13.sp)
    }
}
