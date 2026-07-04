package com.example.muxmaster.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MuxScreen(
    viewModel: MuxViewModel,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onPickSubtitle: () -> Unit,
    onPickOutputFolder: () -> Unit,
    onNavigateToConverter: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()

    val expandedAudioIds = remember { mutableStateMapOf<Int, Boolean>() }
    val expandedSubtitleIds = remember { mutableStateMapOf<Int, Boolean>() }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = { Text("MuxMaster", fontWeight = FontWeight.Bold, color = TextPrimary) },
                actions = {
                    IconButton(onClick = onNavigateToConverter) {
                        Icon(Icons.Filled.Tune, "Ses Dönüştürücü", tint = TextSec)
                    }
                },
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
                canStartMux = viewModel.videoFile != null && viewModel.outputFolderUri != null,
                onPickFolder = onPickOutputFolder,
                onFileNameChange = viewModel::updateOutputFileName,
                onStart = viewModel::startMux,
                onCancel = viewModel::cancelMux,
                onDismissResult = {
                    if (viewModel.isSuccess) viewModel.dismissAndReset() else viewModel.clearResult()
                }
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
                TabRow(selectedTabIndex = pagerState.currentPage, containerColor = BgDark, contentColor = Purple) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                        text = { Text("Sesler (${viewModel.audioTracks.size})") }
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                        text = { Text("Altyazılar (${viewModel.subtitleTracks.size})") }
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = if (pagerState.currentPage == 0) onPickAudio else onPickSubtitle) {
                        Icon(Icons.Filled.Add, null, tint = Purple, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (pagerState.currentPage == 0) "Ses Dosyası Ekle" else "Altyazı Dosyası Ekle", color = Purple, fontSize = 13.sp)
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) { page ->
                    if (page == 0) {
                        if (viewModel.audioTracks.isEmpty()) {
                            EmptyHint("Bu videoda ses track'i yok. + ile ekleyebilirsin.")
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                itemsIndexed(viewModel.audioTracks, key = { _, t -> t.id }) { idx, track ->
                                    AudioTrackCard(
                                        track = track,
                                        canMoveUp = idx > 0,
                                        canMoveDown = idx < viewModel.audioTracks.size - 1,
                                        expanded = expandedAudioIds[track.id] ?: true,
                                        onToggleExpand = {
                                            expandedAudioIds[track.id] = !(expandedAudioIds[track.id] ?: true)
                                        },
                                        onUpdate = viewModel::updateAudio,
                                        onRemove = { viewModel.removeAudio(track.id) },
                                        onMoveUp = { viewModel.moveAudioUp(track.id) },
                                        onMoveDown = { viewModel.moveAudioDown(track.id) },
                                        modifier = Modifier.animateItemPlacement(
                                            spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)
                                        )
                                    )
                                }
                            }
                        }
                    } else {
                        if (viewModel.subtitleTracks.isEmpty()) {
                            EmptyHint("Bu videoda altyazı yok. + ile ekleyebilirsin.")
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                itemsIndexed(viewModel.subtitleTracks, key = { _, t -> t.id }) { idx, track ->
                                    SubtitleTrackCard(
                                        track = track,
                                        canMoveUp = idx > 0,
                                        canMoveDown = idx < viewModel.subtitleTracks.size - 1,
                                        expanded = expandedSubtitleIds[track.id] ?: true,
                                        onToggleExpand = {
                                            expandedSubtitleIds[track.id] = !(expandedSubtitleIds[track.id] ?: true)
                                        },
                                        onUpdate = viewModel::updateSubtitle,
                                        onRemove = { viewModel.removeSubtitle(track.id) },
                                        onMoveUp = { viewModel.moveSubtitleUp(track.id) },
                                        onMoveDown = { viewModel.moveSubtitleDown(track.id) },
                                        modifier = Modifier.animateItemPlacement(
                                            spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)
                                        )
                                    )
                                }
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
    Box(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = TextMuted, fontSize = 13.sp)
    }
}
