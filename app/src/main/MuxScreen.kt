package com.example.muxmaster.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.muxmaster.R
import com.example.muxmaster.model.AudioTrackItem
import com.example.muxmaster.model.TrackSource
import com.example.muxmaster.ui.components.AudioTrackCard
import com.example.muxmaster.ui.components.BottomMuxBar
import com.example.muxmaster.ui.components.SubtitleTrackCard
import com.example.muxmaster.ui.components.VideoCard
import com.example.muxmaster.ui.theme.*
import com.example.muxmaster.viewmodel.AudioConversionEngine
import com.example.muxmaster.viewmodel.ChannelMode
import com.example.muxmaster.viewmodel.FileSizeUnit
import com.example.muxmaster.viewmodel.MuxViewModel
import com.example.muxmaster.viewmodel.OutputFormat
import com.example.muxmaster.viewmodel.TargetMode
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val INPLACE_OPUS_PRESETS = listOf(16, 24, 32, 64, 96, 128, 192)
private val INPLACE_XHE_PRESETS = listOf(12, 16, 24, 32, 48, 64, 96)
private val INPLACE_HE_PRESETS = listOf(16, 24, 32, 48, 64, 96, 128)
private val INPLACE_MP3_PRESETS = listOf(32, 64, 96, 128, 192, 256, 320)

private val INPLACE_SIZE_PRESETS = listOf(
    Triple("500 KB", "500", FileSizeUnit.KB),
    Triple("750 KB", "750", FileSizeUnit.KB),
    Triple("1 MB", "1", FileSizeUnit.MB),
    Triple("2 MB", "2", FileSizeUnit.MB),
    Triple("5 MB", "5", FileSizeUnit.MB),
    Triple("10 MB", "10", FileSizeUnit.MB)
)

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

    // Kaydırma sırasında alt bar ("primary:Movies" + dosya adı + MUXLAMAYI BAŞLAT) ve üst
    // video kartını otomatik gizleyip çalışma alanını büyüten durum:
    var isBarsVisible by remember { mutableStateOf(true) }
    val audioListState = rememberLazyListState()
    val subtitleListState = rememberLazyListState()

    // Yerinde "Sesi Dönüştür" diyalogu için seçilen ses parçası:
    var trackToConvert by remember { mutableStateOf<AudioTrackItem?>(null) }

    // Video seçili değilken, muxlama sürerken veya sonuç mesajı varken alt bar her zaman görünür.
    LaunchedEffect(viewModel.videoFile, viewModel.isMuxing, viewModel.resultMessage) {
        if (viewModel.videoFile == null || viewModel.isMuxing || viewModel.resultMessage != null) {
            isBarsVisible = true
        }
    }

    val nestedScrollConnection = remember(viewModel.videoFile, viewModel.isMuxing) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (viewModel.videoFile != null && !viewModel.isMuxing) {
                    if (available.y < -8f) {
                        isBarsVisible = false
                    } else if (available.y > 8f) {
                        isBarsVisible = true
                    }
                }
                return Offset.Zero
            }
        }
    }

    // Track export veya yerinde dönüştürme sonucu snackbar olarak gösterilir.
    LaunchedEffect(viewModel.exportMessage) {
        val msg = viewModel.exportMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearExportResult()
        }
    }

    val showBottomBar = isBarsVisible || viewModel.videoFile == null || viewModel.isMuxing || viewModel.resultMessage != null

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
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(initialOffsetY = { it }) + expandVertically() + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + shrinkVertically() + fadeOut()
            ) {
                BottomMuxBar(
                    outputFolderUri = viewModel.outputFolderUri,
                    outputFileName = viewModel.outputFileName,
                    isMuxing = viewModel.isMuxing,
                    muxProgress = viewModel.muxProgress,
                    resultMessage = viewModel.resultMessage,
                    isSuccess = viewModel.isSuccess,
                    canStartMux = viewModel.videoFile != null && viewModel.outputFolderUri != null && !viewModel.isConvertingTrack,
                    onPickFolder = onPickOutputFolder,
                    onFileNameChange = viewModel::updateOutputFileName,
                    onStart = viewModel::startMux,
                    onCancel = viewModel::cancelMux,
                    onDismissResult = {
                        if (viewModel.isSuccess) viewModel.dismissAndReset() else viewModel.clearResult()
                    }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = if (showBottomBar) 10.dp else 4.dp)
            ) {
                val busy = viewModel.isMuxing || viewModel.isLoading || viewModel.isConvertingTrack

                // Kaydırma sırasında üst kısım da daralarak ses/altyazı listesine maksimum alan açar
                AnimatedVisibility(
                    visible = showBottomBar,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column {
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
                        Spacer(Modifier.height(10.dp))

                        VideoCard(
                            video = viewModel.videoFile,
                            isLoading = viewModel.isLoading,
                            loadingMessage = viewModel.loadingMessage,
                            onPickVideo = onPickVideo,
                            onClear = viewModel::clearVideo
                        )

                        Spacer(Modifier.height(12.dp))
                    }
                }

                // Kaydırma sırasında üst kart gizlendiğinde videonun adını gösteren ince bilgi çubuğu
                AnimatedVisibility(
                    visible = !showBottomBar && viewModel.videoFile != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    val v = viewModel.videoFile
                    if (v != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp)
                                .background(Surface, RoundedCornerShape(8.dp))
                                .clickable { isBarsVisible = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Filled.Movie, null, tint = PurpleLight, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                v.displayName,
                                color = TextPrimary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                stringResource(R.string.action_show_mux_bar),
                                color = PurpleLight,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.long_press_track_hint),
                            color = TextMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
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
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .nestedScroll(nestedScrollConnection)
                            .pointerInput(viewModel.isMuxing) {
                                detectVerticalDragGestures { _, dragAmount ->
                                    if (!viewModel.isMuxing) {
                                        if (dragAmount < -12f) isBarsVisible = false
                                        else if (dragAmount > 12f) isBarsVisible = true
                                    }
                                }
                            }
                    ) { page ->
                        if (page == 0) {
                            if (viewModel.audioTracks.isEmpty()) {
                                EmptyHint(stringResource(R.string.empty_audio_hint))
                            } else {
                                LazyColumn(
                                    state = audioListState,
                                    contentPadding = PaddingValues(bottom = 64.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    itemsIndexed(viewModel.audioTracks, key = { _, t -> t.id }) { idx, track ->
                                        AudioTrackCard(
                                            track = track,
                                            canMoveUp = idx > 0,
                                            canMoveDown = idx < viewModel.audioTracks.size - 1,
                                            expanded = viewModel.expandedAudioIds[track.id] ?: true,
                                            onToggleExpand = {
                                                viewModel.expandedAudioIds[track.id] = !(viewModel.expandedAudioIds[track.id] ?: true)
                                            },
                                            onUpdate = viewModel::updateAudio,
                                            onRemove = { viewModel.removeAudio(track.id) },
                                            onMoveUp = { viewModel.moveAudioUp(track.id) },
                                            onMoveDown = { viewModel.moveAudioDown(track.id) },
                                            onExport = { viewModel.exportAudioTrack(track) },
                                            onConvertAudio = { trackToConvert = track },
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
                                LazyColumn(
                                    state = subtitleListState,
                                    contentPadding = PaddingValues(bottom = 64.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
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

            // Alt panel gizliyken sağ altta tek dokunuşla geri açma hapı (yukarı kaydırınca da otomatik açılır)
            AnimatedVisibility(
                visible = !showBottomBar && viewModel.videoFile != null,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 12.dp)
            ) {
                Surface(
                    onClick = { isBarsVisible = true },
                    color = Purple,
                    shape = RoundedCornerShape(24.dp),
                    shadowElevation = 6.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Filled.ExpandLess, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.action_show_mux_bar),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    // ── YERİNDE "SESİ DÖNÜŞTÜR" DİYALOĞU ─────────────────────────────────────
    val activeTrack = trackToConvert
    if (activeTrack != null) {
        InPlaceAudioConvertDialog(
            track = activeTrack,
            videoDurationMs = viewModel.videoFile?.durationMs ?: 0L,
            isConverting = viewModel.isConvertingTrack,
            progress = viewModel.convertingTrackProgress,
            convertingName = viewModel.convertingTrackName,
            onDismiss = {
                if (!viewModel.isConvertingTrack) trackToConvert = null
            },
            onCancelConvert = viewModel::cancelTrackConvert,
            onStartConvert = { format, mode, bitrateKbps, targetSizeBytes, chMode ->
                viewModel.convertAudioTrackInPlace(
                    track = activeTrack,
                    format = format,
                    targetMode = mode,
                    bitrateKbps = bitrateKbps,
                    targetSizeBytes = targetSizeBytes,
                    channelMode = chMode,
                    onComplete = { trackToConvert = null }
                )
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InPlaceAudioConvertDialog(
    track: AudioTrackItem,
    videoDurationMs: Long,
    isConverting: Boolean,
    progress: Int,
    convertingName: String,
    onDismiss: () -> Unit,
    onCancelConvert: () -> Unit,
    onStartConvert: (OutputFormat, TargetMode, Int, Long?, ChannelMode) -> Unit
) {
    var selectedFormat by remember { mutableStateOf(OutputFormat.OPUS) }
    var targetMode by remember { mutableStateOf(TargetMode.BITRATE) }
    var bitrateText by remember { mutableStateOf("64") }
    var targetSizeText by remember { mutableStateOf("1") }
    var targetSizeUnit by remember { mutableStateOf(FileSizeUnit.MB) }
    var channelMode by remember { mutableStateOf(ChannelMode.AUTO) }

    val trackName = track.title.ifBlank {
        if (track.source == TrackSource.EXISTING) {
            stringResource(R.string.audio_track_fallback_title, track.existingStreamIndex)
        } else {
            track.fileDisplayName
        }
    }

    val parsedBitrate = bitrateText.toIntOrNull()
    val parsedTargetBytes: Long? = remember(targetSizeText, targetSizeUnit) {
        val v = targetSizeText.replace(',', '.').toDoubleOrNull()
        if (v != null && v > 0.0) {
            val b = (v * targetSizeUnit.bytesPerUnit.toDouble()).toLong()
            if (b in (8L * 1024L)..(500L * 1024L * 1024L)) b else null
        } else null
    }

    val isInputValid = if (targetMode == TargetMode.BITRATE) {
        parsedBitrate != null && parsedBitrate in 6..512
    } else {
        parsedTargetBytes != null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Surface),
            border = BorderStroke(1.dp, Outline),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Autorenew, null, tint = PurpleLight, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.inplace_convert_title),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            trackName,
                            color = TextSec,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!isConverting) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, stringResource(R.string.action_close), tint = TextSec, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 1) Hedef Format
                Text(stringResource(R.string.target_format_title), color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                OutputFormat.entries.chunked(3).forEach { rowFormats ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowFormats.forEach { fmt ->
                            FilterChip(
                                selected = selectedFormat == fmt,
                                onClick = { if (!isConverting) selectedFormat = fmt },
                                enabled = !isConverting,
                                label = { Text(fmt.label, fontSize = 11.sp) },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Purple, selectedLabelColor = Color.White,
                                    containerColor = SurfaceHigh, labelColor = TextSec
                                )
                            )
                        }
                        repeat(3 - rowFormats.size) { Spacer(Modifier.weight(1f)) }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 2) Kodlama Modu (Bitrate vs Hedef Boyut)
                Text(stringResource(R.string.target_mode_title), color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = targetMode == TargetMode.BITRATE,
                        onClick = { if (!isConverting) targetMode = TargetMode.BITRATE },
                        enabled = !isConverting,
                        label = { Text(stringResource(R.string.target_mode_bitrate), fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Purple, selectedLabelColor = Color.White,
                            containerColor = SurfaceHigh, labelColor = TextSec
                        )
                    )
                    FilterChip(
                        selected = targetMode == TargetMode.FILE_SIZE,
                        onClick = { if (!isConverting) targetMode = TargetMode.FILE_SIZE },
                        enabled = !isConverting,
                        label = { Text(stringResource(R.string.target_mode_filesize), fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Purple, selectedLabelColor = Color.White,
                            containerColor = SurfaceHigh, labelColor = TextSec
                        )
                    )
                }

                Spacer(Modifier.height(10.dp))

                if (targetMode == TargetMode.BITRATE) {
                    val presets = when (selectedFormat) {
                        OutputFormat.OPUS -> INPLACE_OPUS_PRESETS
                        OutputFormat.XHE_AAC -> INPLACE_XHE_PRESETS
                        OutputFormat.HE_AAC, OutputFormat.HE_AAC_V2 -> INPLACE_HE_PRESETS
                        OutputFormat.MP3 -> INPLACE_MP3_PRESETS
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        presets.forEach { preset ->
                            FilterChip(
                                selected = bitrateText == preset.toString(),
                                onClick = { if (!isConverting) bitrateText = preset.toString() },
                                enabled = !isConverting,
                                label = { Text("${preset}k", fontSize = 10.sp) },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Purple, selectedLabelColor = Color.White,
                                    containerColor = SurfaceHigh, labelColor = TextSec
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = bitrateText,
                        onValueChange = { if (!isConverting) bitrateText = it.filter { ch -> ch.isDigit() }.take(4) },
                        label = { Text(stringResource(R.string.custom_bitrate_label), fontSize = 11.sp) },
                        singleLine = true,
                        enabled = !isConverting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        suffix = { Text(stringResource(R.string.kbps_suffix), fontSize = 11.sp, color = TextMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Purple, unfocusedBorderColor = Outline,
                            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                        )
                    )
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        INPLACE_SIZE_PRESETS.forEach { (label, valStr, unit) ->
                            FilterChip(
                                selected = targetSizeText == valStr && targetSizeUnit == unit,
                                onClick = {
                                    if (!isConverting) {
                                        targetSizeText = valStr
                                        targetSizeUnit = unit
                                    }
                                },
                                enabled = !isConverting,
                                label = { Text(label, fontSize = 10.sp, maxLines = 1) },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Purple, selectedLabelColor = Color.White,
                                    containerColor = SurfaceHigh, labelColor = TextSec
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = targetSizeText,
                            onValueChange = { raw ->
                                if (!isConverting) {
                                    val norm = raw.replace(',', '.')
                                    val sb = StringBuilder()
                                    var dot = false
                                    for (ch in norm) {
                                        if (ch.isDigit()) sb.append(ch)
                                        else if (ch == '.' && !dot) { dot = true; sb.append(ch) }
                                        if (sb.length >= 7) break
                                    }
                                    targetSizeText = sb.toString()
                                }
                            },
                            label = { Text(stringResource(R.string.custom_filesize_label), fontSize = 11.sp) },
                            singleLine = true,
                            enabled = !isConverting,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            suffix = { Text(targetSizeUnit.label, fontSize = 11.sp, color = TextMuted) },
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Purple, unfocusedBorderColor = Outline,
                                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                            )
                        )
                        FileSizeUnit.entries.forEach { unit ->
                            FilterChip(
                                selected = targetSizeUnit == unit,
                                onClick = { if (!isConverting) targetSizeUnit = unit },
                                enabled = !isConverting,
                                label = { Text(unit.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Purple, selectedLabelColor = Color.White,
                                    containerColor = SurfaceHigh, labelColor = TextSec
                                )
                            )
                        }
                    }
                    if (parsedTargetBytes != null && videoDurationMs > 0L) {
                        val autoKbps = (AudioConversionEngine.calculateBpsForFileSize(videoDurationMs, parsedTargetBytes, selectedFormat) / 1000.0)
                            .roundToInt().coerceAtLeast(6)
                        Text(
                            stringResource(R.string.filesize_auto_bitrate_preview, autoKbps, "$targetSizeText ${targetSizeUnit.label}"),
                            color = TextSec,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 3) Kanal
                Text("Kanal", color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        ChannelMode.AUTO to "Otomatik",
                        ChannelMode.MONO to stringResource(R.string.channel_mono),
                        ChannelMode.STEREO to stringResource(R.string.channel_stereo)
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = channelMode == mode,
                            onClick = { if (!isConverting) channelMode = mode },
                            enabled = !isConverting,
                            label = { Text(label, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Purple, selectedLabelColor = Color.White,
                                containerColor = SurfaceHigh, labelColor = TextSec
                            )
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                if (isConverting) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = Purple, trackColor = SurfaceHigh
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.progress_percent, progress), color = TextSec, fontSize = 12.sp)
                        }
                        if (convertingName.isNotBlank()) {
                            Text(
                                stringResource(R.string.converting_file, convertingName),
                                color = TextMuted, fontSize = 11.sp, maxLines = 1,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onCancelConvert,
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, Red),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Filled.Close, null, tint = Red, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.cancel_desc), color = Red, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            onStartConvert(
                                selectedFormat,
                                targetMode,
                                parsedBitrate ?: 64,
                                parsedTargetBytes,
                                channelMode
                            )
                        },
                        enabled = isInputValid,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Purple, disabledContainerColor = SurfaceHigh)
                    ) {
                        Icon(Icons.Filled.Autorenew, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.inplace_convert_action, selectedFormat.label.uppercase()),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
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
