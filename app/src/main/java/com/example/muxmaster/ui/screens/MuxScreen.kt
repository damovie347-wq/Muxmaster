package com.example.muxmaster.ui.screens

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
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

    // KÖK NEDEN DÜZELTMESİ (Kaydırma sırasında titreme, ses dosyalarının kaybolması ve iç içe geçmesi):
    // 1) Önceden üstteki VideoCard `expandVertically/shrinkVertically` ile küçülüp büyüdüğü için
    //    altındaki LazyColumn her karede yukarı-aşağı zıplıyor, bu da parmak sabitken bile ters
    //    yönde sahte scroll deltası üretip sonsuz titreme (feedback loop) yaratıyordu.
    // 2) Artık LazyColumn'un boyutu ve konumu %100 SABİT (tam ekran); VideoCard doğrudan listenin
    //    kendi doğal üst başlığı (item) olarak kayıyor ve alttaki BottomMuxBar ise boyutu hiç
    //    değiştirmeden saf GPU `slideInVertically / slideOutVertically` katmanı (overlay) olarak
    //    histerezis korumasıyla kayıp gizleniyor.
    var isBottomBarVisible by remember { mutableStateOf(true) }
    val audioListState = rememberLazyListState()
    val subtitleListState = rememberLazyListState()

    // Yerinde "Sesi Dönüştür" diyalogu için seçilen ses parçası:
    var trackToConvert by remember { mutableStateOf<AudioTrackItem?>(null) }

    LaunchedEffect(viewModel.videoFile, viewModel.isMuxing, viewModel.resultMessage) {
        if (viewModel.videoFile == null || viewModel.isMuxing || viewModel.resultMessage != null) {
            isBottomBarVisible = true
        }
    }

    // Histerezis ve soğuma süreli (cooldown) kaydırma algılayıcı: asla titreme yapmaz
    val nestedScrollConnection = remember(viewModel.videoFile, viewModel.isMuxing) {
        object : NestedScrollConnection {
            private var accY = 0f
            private var lastToggleTime = 0L

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (viewModel.videoFile == null || viewModel.isMuxing) return Offset.Zero
                val dy = available.y
                if ((dy < 0f && accY > 0f) || (dy > 0f && accY < 0f)) {
                    accY = 0f
                }
                accY += dy
                val now = SystemClock.elapsedRealtime()
                if (now - lastToggleTime >= 260L) {
                    if (accY < -36f && isBottomBarVisible) {
                        isBottomBarVisible = false
                        accY = 0f
                        lastToggleTime = now
                    } else if (accY > 36f && !isBottomBarVisible) {
                        isBottomBarVisible = true
                        accY = 0f
                        lastToggleTime = now
                    }
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(viewModel.exportMessage) {
        val msg = viewModel.exportMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearExportResult()
        }
    }

    val showBottomBar = isBottomBarVisible || viewModel.videoFile == null || viewModel.isMuxing || viewModel.resultMessage != null

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
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            val busy = viewModel.isMuxing || viewModel.isLoading

            if (viewModel.videoFile == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    TopVideoAndFolderSection(
                        viewModel = viewModel,
                        busy = busy,
                        onPickFolder = onPickFolder,
                        onPickVideo = onPickVideo
                    )
                    Spacer(Modifier.weight(1f))
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(nestedScrollConnection)
                ) {
                    // Arka planda yerinde ses dönüştürme sürerken üstte ince durum bandı
                    if (viewModel.isConvertingTrack && trackToConvert == null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .background(SurfaceHigh, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = PurpleLight)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${viewModel.convertingTrackName} (%${viewModel.convertingTrackProgress})",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = viewModel::cancelTrackConvert, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Filled.Close, stringResource(R.string.cancel_desc), tint = Red, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) { page ->
                        if (page == 0) {
                            val allAudioSel = viewModel.allAudioSelected
                            val selAudioCnt = viewModel.selectedAudioCount
                            LazyColumn(
                                state = audioListState,
                                contentPadding = PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 8.dp,
                                    bottom = 220.dp
                                ),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                item(key = "header_audio") {
                                    TopVideoAndFolderSection(
                                        viewModel = viewModel,
                                        busy = busy,
                                        onPickFolder = onPickFolder,
                                        onPickVideo = onPickVideo
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    TrackTabsAndAddRow(
                                        currentPage = pagerState.currentPage,
                                        audioCount = viewModel.audioTracks.size,
                                        subtitleCount = viewModel.subtitleTracks.size,
                                        totalInTab = viewModel.audioTracks.size,
                                        selectedInTab = selAudioCnt,
                                        allSelectedInTab = allAudioSel,
                                        isExporting = viewModel.isExporting,
                                        onToggleSelectAll = viewModel::toggleSelectAllAudio,
                                        onExportSelected = viewModel::exportSelectedAudioTracks,
                                        onSelectPage = { target -> scope.launch { pagerState.animateScrollToPage(target) } },
                                        onAddClick = onPickAudio
                                    )
                                }
                                if (viewModel.audioTracks.isEmpty()) {
                                    item(key = "empty_audio") {
                                        EmptyHint(stringResource(R.string.empty_audio_hint))
                                    }
                                } else {
                                    itemsIndexed(
                                        items = viewModel.audioTracks,
                                        key = { _, t -> "audio_${t.id}" }
                                    ) { idx, track ->
                                        val isExpanded = viewModel.expandedAudioIds[track.id] ?: false
                                        AudioTrackCard(
                                            track = track,
                                            selectionModeActive = !allAudioSel,
                                            selectedCount = selAudioCnt,
                                            canMoveUp = idx > 0,
                                            canMoveDown = idx < viewModel.audioTracks.size - 1,
                                            expanded = isExpanded,
                                            onToggleExpand = {
                                                viewModel.expandedAudioIds[track.id] = !isExpanded
                                            },
                                            onUpdate = viewModel::updateAudio,
                                            onRemove = { viewModel.removeAudio(track.id) },
                                            onMoveUp = { viewModel.moveAudioUp(track.id) },
                                            onMoveDown = { viewModel.moveAudioDown(track.id) },
                                            onExport = { viewModel.exportAudioTrack(track) },
                                            onExportSelected = viewModel::exportSelectedAudioTracks,
                                            onConvertAudio = { trackToConvert = track },
                                            isConvertingThisTrack = viewModel.isConvertingTrack && viewModel.convertingTrackId == track.id,
                                            convertingProgress = viewModel.convertingTrackProgress,
                                            onCancelConvert = viewModel::cancelTrackConvert
                                        )
                                    }
                                }
                            }
                        } else {
                            val allSubSel = viewModel.allSubtitlesSelected
                            val selSubCnt = viewModel.selectedSubtitleCount
                            LazyColumn(
                                state = subtitleListState,
                                contentPadding = PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 8.dp,
                                    bottom = 220.dp
                                ),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                item(key = "header_subtitle") {
                                    TopVideoAndFolderSection(
                                        viewModel = viewModel,
                                        busy = busy,
                                        onPickFolder = onPickFolder,
                                        onPickVideo = onPickVideo
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    TrackTabsAndAddRow(
                                        currentPage = pagerState.currentPage,
                                        audioCount = viewModel.audioTracks.size,
                                        subtitleCount = viewModel.subtitleTracks.size,
                                        totalInTab = viewModel.subtitleTracks.size,
                                        selectedInTab = selSubCnt,
                                        allSelectedInTab = allSubSel,
                                        isExporting = viewModel.isExporting,
                                        onToggleSelectAll = viewModel::toggleSelectAllSubtitles,
                                        onExportSelected = viewModel::exportSelectedSubtitleTracks,
                                        onSelectPage = { target -> scope.launch { pagerState.animateScrollToPage(target) } },
                                        onAddClick = onPickSubtitle
                                    )
                                }
                                if (viewModel.subtitleTracks.isEmpty()) {
                                    item(key = "empty_subtitle") {
                                        EmptyHint(stringResource(R.string.empty_subtitle_hint))
                                    }
                                } else {
                                    itemsIndexed(
                                        items = viewModel.subtitleTracks,
                                        key = { _, t -> "sub_${t.id}" }
                                    ) { idx, track ->
                                        val isExpanded = viewModel.expandedSubtitleIds[track.id] ?: false
                                        SubtitleTrackCard(
                                            track = track,
                                            selectionModeActive = !allSubSel,
                                            selectedCount = selSubCnt,
                                            canMoveUp = idx > 0,
                                            canMoveDown = idx < viewModel.subtitleTracks.size - 1,
                                            expanded = isExpanded,
                                            onToggleExpand = {
                                                viewModel.expandedSubtitleIds[track.id] = !isExpanded
                                            },
                                            onUpdate = viewModel::updateSubtitle,
                                            onRemove = { viewModel.removeSubtitle(track.id) },
                                            onMoveUp = { viewModel.moveSubtitleUp(track.id) },
                                            onMoveDown = { viewModel.moveSubtitleDown(track.id) },
                                            onExport = { viewModel.exportSubtitleTrack(track) },
                                            onExportSelected = viewModel::exportSelectedSubtitleTracks
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Alt panel (BottomMuxBar): LazyColumn boyutunu hiç değiştirmeden saf katman olarak kayar
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
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

            // Alt panel gizliyken sağ altta tek dokunuşla geri açma hapı
            AnimatedVisibility(
                visible = !showBottomBar && viewModel.videoFile != null,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 14.dp)
            ) {
                Surface(
                    onClick = { isBottomBarVisible = true },
                    color = Purple,
                    shape = RoundedCornerShape(24.dp),
                    shadowElevation = 6.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
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

    // ── YERİNDE "SESİ DÖNÜŞTÜR" DİYALOĞU (Gizle / Arka Plana At destekli) ─────
    val activeTrack = trackToConvert
    if (activeTrack != null) {
        val isConvertingThis = viewModel.isConvertingTrack && viewModel.convertingTrackId == activeTrack.id
        InPlaceAudioConvertDialog(
            track = activeTrack,
            videoDurationMs = viewModel.videoFile?.durationMs ?: 0L,
            isConverting = isConvertingThis,
            progress = viewModel.convertingTrackProgress,
            convertingName = viewModel.convertingTrackName,
            onDismiss = {
                // Dönüştürme sürerken bile pencere gizlenebilir; işlem arka planda kendi kendine devam eder!
                trackToConvert = null
            },
            onCancelConvert = {
                viewModel.cancelTrackConvert()
                trackToConvert = null
            },
            onStartConvert = { format, mode, bitrateKbps, targetSizeBytes, chMode ->
                viewModel.convertAudioTrackInPlace(
                    track = activeTrack,
                    format = format,
                    targetMode = mode,
                    bitrateKbps = bitrateKbps,
                    targetSizeBytes = targetSizeBytes,
                    channelMode = chMode,
                    onComplete = {
                        if (trackToConvert?.id == activeTrack.id) {
                            trackToConvert = null
                        }
                    }
                )
            }
        )
    }
}

@Composable
private fun TopVideoAndFolderSection(
    viewModel: MuxViewModel,
    busy: Boolean,
    onPickFolder: () -> Unit,
    onPickVideo: () -> Unit
) {
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
}

@Composable
private fun TrackTabsAndAddRow(
    currentPage: Int,
    audioCount: Int,
    subtitleCount: Int,
    totalInTab: Int,
    selectedInTab: Int,
    allSelectedInTab: Boolean,
    isExporting: Boolean,
    onToggleSelectAll: () -> Unit,
    onExportSelected: () -> Unit,
    onSelectPage: (Int) -> Unit,
    onAddClick: () -> Unit
) {
    TabRow(selectedTabIndex = currentPage, containerColor = BgDark, contentColor = Purple) {
        Tab(
            selected = currentPage == 0,
            onClick = { onSelectPage(0) },
            text = { Text(stringResource(R.string.tab_audio, audioCount)) }
        )
        Tab(
            selected = currentPage == 1,
            onClick = { onSelectPage(1) },
            text = { Text(stringResource(R.string.tab_subtitle, subtitleCount)) }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (totalInTab > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                // "Tümünü Seç" (hepsi seçiliyse tekrar basınca tüm seçimleri kaldırır)
                Surface(
                    onClick = onToggleSelectAll,
                    color = if (allSelectedInTab) Purple.copy(alpha = 0.18f) else SurfaceHigh,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (allSelectedInTab) Purple else Outline)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            if (allSelectedInTab) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                            contentDescription = null,
                            tint = if (allSelectedInTab) PurpleLight else TextSec,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "${stringResource(R.string.action_select_all)} ($selectedInTab/$totalInTab)",
                            color = if (allSelectedInTab) PurpleLight else TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }

                // Yalnızca seçili kutucukları dışa aktarma butonu
                if (selectedInTab > 0) {
                    Surface(
                        onClick = { if (!isExporting) onExportSelected() },
                        color = Amber.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Amber.copy(alpha = 0.5f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                        ) {
                            if (isExporting) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Amber)
                            } else {
                                Icon(
                                    Icons.Filled.FileDownload,
                                    contentDescription = null,
                                    tint = Amber,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "${stringResource(R.string.action_export)} ($selectedInTab)",
                                color = Amber,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        } else {
            Text(
                stringResource(R.string.long_press_track_hint),
                color = TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        TextButton(onClick = onAddClick) {
            Icon(Icons.Filled.Add, null, tint = Purple, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                if (currentPage == 0) stringResource(R.string.add_audio_button) else stringResource(R.string.add_subtitle_button),
                color = Purple,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
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
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, stringResource(R.string.action_close), tint = TextSec, modifier = Modifier.size(18.dp))
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
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Gizle (Arka Plana At) butonu: pencereyi kapatır, işlem kendi kendine devam eder!
                            Button(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Purple)
                            ) {
                                Icon(Icons.Filled.VisibilityOff, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.action_hide_background),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }
                            OutlinedButton(
                                onClick = onCancelConvert,
                                modifier = Modifier.height(44.dp),
                                border = BorderStroke(1.dp, Red),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Filled.Close, null, tint = Red, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.cancel_desc), color = Red, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            }
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
