package com.example.muxmaster.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.muxmaster.R
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
    onPickFolder: () -> Unit = {},
    onPickAudio: () -> Unit,
    onPickSubtitle: () -> Unit,
    onPickOutputFolder: () -> Unit,
    onNavigateToConverter: () -> Unit,
    onNavigateToTrim: () -> Unit,
    onNavigateToMerge: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onOpenPlayer: () -> Unit = {}
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Track export sonucu (Dışa Aktar) kısa bir snackbar olarak gösterilir.
    LaunchedEffect(viewModel.exportMessage) {
        val msg = viewModel.exportMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearExportResult()
        }
    }

    Scaffold(
        containerColor = BgDark,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("MuxMaster", fontWeight = FontWeight.Bold, color = TextPrimary) },
                actions = {
                    IconButton(onClick = onOpenPlayer) {
                        Icon(Icons.Filled.PlayCircle, "Video Oynatıcı", tint = TextSec)
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.nav_settings_desc), tint = TextSec)
                    }
                    IconButton(onClick = onNavigateToTrim) {
                        Icon(Icons.Filled.ContentCut, "Video Kırp", tint = TextSec)
                    }
                    IconButton(onClick = onNavigateToMerge) {
                        Icon(Icons.Filled.CallMerge, "Video Birleştir", tint = TextSec)
                    }
                    IconButton(onClick = onNavigateToConverter) {
                        Icon(Icons.Filled.Tune, stringResource(R.string.nav_converter_desc), tint = TextSec)
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
            // ── KLASÖR SEÇ + ÖNCEKİ / SONRAKİ VİDEO (toplu dublaj için) ──────────
            val busy = viewModel.isMuxing || viewModel.isLoading
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onPickFolder,
                    enabled = !busy,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Purple)
                ) {
                    Icon(Icons.Filled.FolderOpen, null, tint = PurpleLight, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Klasör Seç", color = PurpleLight, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                if (viewModel.folderQueue.isNotEmpty()) {
                    IconButton(onClick = viewModel::prevInQueue, enabled = viewModel.hasPrevInQueue && !busy) {
                        Icon(
                            Icons.Filled.SkipPrevious, "Önceki video",
                            tint = if (viewModel.hasPrevInQueue && !busy) TextPrimary else TextMuted
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = viewModel::nextInQueue,
                        enabled = viewModel.hasNextInQueue && !busy,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Purple, disabledContainerColor = SurfaceHigh)
                    ) {
                        Icon(Icons.Filled.ArrowForward, "Sonraki video", modifier = Modifier.size(22.dp))
                    }
                }
            }
            if (viewModel.folderQueue.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceHigh, RoundedCornerShape(10.dp))
                        .padding(start = 12.dp, end = 4.dp)
                ) {
                    Icon(Icons.Filled.Folder, null, tint = Amber, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        viewModel.folderName.ifBlank { "Klasör" },
                        color = TextPrimary, fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${viewModel.queueIndex + 1}/${viewModel.folderQueue.size}",
                        color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.Medium
                    )
                    IconButton(onClick = viewModel::clearFolderQueue) {
                        Icon(Icons.Filled.Close, "Klasörü kapat", tint = TextSec, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

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
                        text = { Text(stringResource(R.string.tab_audio, viewModel.audioTracks.size)) }
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                        text = { Text(stringResource(R.string.tab_subtitle, viewModel.subtitleTracks.size)) }
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = if (pagerState.currentPage == 0) onPickAudio else onPickSubtitle) {
                        Icon(Icons.Filled.Add, null, tint = Purple, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (pagerState.currentPage == 0) stringResource(R.string.add_audio_button) else stringResource(R.string.add_subtitle_button),
                            color = Purple, fontSize = 13.sp
                        )
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) { page ->
                    if (page == 0) {
                        if (viewModel.audioTracks.isEmpty()) {
                            EmptyHint(stringResource(R.string.empty_audio_hint))
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                itemsIndexed(viewModel.audioTracks, key = { _, t -> t.id }) { idx, track ->
                                    AudioTrackCard(
                                        track = track,
                                        canMoveUp = idx > 0,
                                        canMoveDown = idx < viewModel.audioTracks.size - 1,
                                        // Not: viewModel.expandedAudioIds kasıtlı olarak ViewModel'de
                                        // tutuluyor ki Muxlayıcı ↔ Dönüştürücü/Ayarlar arasında geçince
                                        // (composable tamamen unmount olsa bile) durum sıfırlanmasın.
                                        expanded = viewModel.expandedAudioIds[track.id] ?: true,
                                        onToggleExpand = {
                                            viewModel.expandedAudioIds[track.id] = !(viewModel.expandedAudioIds[track.id] ?: true)
                                        },
                                        onUpdate = viewModel::updateAudio,
                                        onRemove = { viewModel.removeAudio(track.id) },
                                        onMoveUp = { viewModel.moveAudioUp(track.id) },
                                        onMoveDown = { viewModel.moveAudioDown(track.id) },
                                        onExport = { viewModel.exportAudioTrack(track) },
                                        modifier = Modifier.animateItemPlacement(
                                            spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)
                                        )
                                    )
                                }
                            }
                        }
                    } else {
                        if (viewModel.subtitleTracks.isEmpty()) {
                            EmptyHint(stringResource(R.string.empty_subtitle_hint))
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                itemsIndexed(viewModel.subtitleTracks, key = { _, t -> t.id }) { idx, track ->
                                    SubtitleTrackCard(
                                        track = track,
                                        canMoveUp = idx > 0,
                                        canMoveDown = idx < viewModel.subtitleTracks.size - 1,
                                        expanded = viewModel.expandedSubtitleIds[track.id] ?: true,
                                        onToggleExpand = {
                                            viewModel.expandedSubtitleIds[track.id] = !(viewModel.expandedSubtitleIds[track.id] ?: true)
                                        },
                                        onUpdate = viewModel::updateSubtitle,
                                        onRemove = { viewModel.removeSubtitle(track.id) },
                                        onMoveUp = { viewModel.moveSubtitleUp(track.id) },
                                        onMoveDown = { viewModel.moveSubtitleDown(track.id) },
                                        onExport = { viewModel.exportSubtitleTrack(track) },
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
