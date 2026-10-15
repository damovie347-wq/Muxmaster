package com.example.muxmaster.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.Mp4Extractor
import com.example.muxmaster.data.DriveStreamResolver
import com.example.muxmaster.data.InputAccess
import com.example.muxmaster.model.VideoFile
import com.example.muxmaster.ui.components.VideoCard
import com.example.muxmaster.ui.theme.*
import com.example.muxmaster.viewmodel.TrimViewModel
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Yüklenme halkası (LoadingRing) ve oynatıcı slider'ı için turkuaz vurgu rengi. */
internal val PlayerTurquoise = Color(0xFF66C2B4)

private fun formatClock(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val m = safe / 60000
    val s = (safe % 60000) / 1000
    val msec = safe % 1000
    return String.format(Locale.US, "%02d:%02d.%03d", m, s, msec)
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimScreen(
    viewModel: TrimViewModel,
    onPickVideo: () -> Unit,
    onPickFolder: () -> Unit,
    onPickOutputFolder: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val video = viewModel.videoFile
    val durationMs = video?.durationMs?.coerceAtLeast(1L) ?: 1L
    val busy = viewModel.isTrimming || viewModel.isLoading

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = { Text("Video / Ses Kırp", fontWeight = FontWeight.Bold, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, "Geri", tint = TextSec)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (video != null) {
                TrimPlayer(
                    video = video,
                    startMs = viewModel.trimStartMs,
                    endMs = viewModel.trimEndMs,
                    isTrimming = viewModel.isTrimming
                )
                Spacer(Modifier.height(12.dp))
            }

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
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
                video = video,
                isLoading = viewModel.isLoading,
                loadingMessage = viewModel.loadingMessage,
                onPickVideo = onPickVideo,
                onClear = viewModel::clearVideo
            )

            if (video == null && viewModel.resultMessage != null && !viewModel.isLoading) {
                Spacer(Modifier.height(12.dp))
                Text(viewModel.resultMessage ?: "", color = Red, fontSize = 13.sp)
            }

            if (video != null) {
                Spacer(Modifier.height(20.dp))
                Text("Kırpma Aralığı", color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatClock(viewModel.trimStartMs), color = Purple, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text("Süre: " + formatClock(viewModel.trimEndMs - viewModel.trimStartMs), color = TextMuted, fontSize = 11.sp)
                    Text(formatClock(viewModel.trimEndMs), color = Purple, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }

                RangeSlider(
                    value = viewModel.trimStartMs.toFloat()..viewModel.trimEndMs.toFloat(),
                    onValueChange = { range ->
                        val newStart = range.start.toLong()
                        val newEnd = range.endInclusive.toLong()
                        val finalStart = if (abs(newStart - viewModel.trimStartMs) <= 25L && abs(newEnd - viewModel.trimEndMs) > 25L) {
                            viewModel.trimStartMs
                        } else {
                            newStart
                        }
                        val finalEnd = if (abs(newEnd - viewModel.trimEndMs) <= 25L && abs(newStart - viewModel.trimStartMs) > 25L) {
                            viewModel.trimEndMs
                        } else {
                            newEnd
                        }
                        viewModel.setTrimRange(finalStart, finalEnd)
                    },
                    valueRange = 0f..durationMs.toFloat(),
                    enabled = !viewModel.isTrimming,
                    colors = SliderDefaults.colors(
                        thumbColor = Purple, activeTrackColor = Purple, inactiveTrackColor = SurfaceHigh
                    )
                )

                Text(
                    if (video.isAudioOnly)
                        "Not: Ses dosyası yeniden kodlanmadan (kalite kaybı olmadan) kırpılır."
                    else
                        "Not: Kalite kaybı olmaması için yeniden kodlama yapılmaz; bu yüzden başlangıç noktası videonun en yakın kare (keyframe) sınırına hizalanabilir.",
                    color = TextMuted, fontSize = 10.sp
                )

                if (!video.isAudioOnly) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Surface, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Başlangıcı tam hassas kes", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Başlangıç ile ilk keyframe arası (birkaç sn) yeniden kodlanır, kalanı kayıpsız kopyalanır. İlk saniyeler atlanmaz, donma olmaz.",
                                color = TextMuted, fontSize = 10.sp
                            )
                        }
                        Switch(
                            checked = viewModel.preciseCut,
                            onCheckedChange = viewModel::updatePreciseCut,
                            enabled = !viewModel.isTrimming,
                            colors = SwitchDefaults.colors(checkedTrackColor = Purple)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                TimeInputGroup(
                    label = "Başlangıç",
                    totalMs = viewModel.trimStartMs,
                    enabled = !viewModel.isTrimming,
                    onChange = viewModel::setTrimStart
                )

                Spacer(Modifier.height(12.dp))

                TimeInputGroup(
                    label = "Bitiş",
                    totalMs = viewModel.trimEndMs,
                    enabled = !viewModel.isTrimming,
                    onChange = viewModel::setTrimEnd
                )

                Spacer(Modifier.height(20.dp))
                Text("Çıktı", color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Surface, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Filled.Folder, null, tint = Amber, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        viewModel.outputFolderUri?.lastPathSegment ?: "Çıktı klasörü seçilmedi",
                        color = if (viewModel.outputFolderUri != null) TextPrimary else TextMuted,
                        fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onPickOutputFolder, enabled = !viewModel.isTrimming) {
                        Text(if (viewModel.outputFolderUri == null) "SEÇ" else "DEĞİŞTİR", color = PurpleLight, fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = viewModel.outputFileName,
                    onValueChange = viewModel::updateOutputFileName,
                    label = { Text("Çıktı dosya adı", fontSize = 11.sp) },
                    singleLine = true,
                    enabled = !viewModel.isTrimming,
                    textStyle = TextStyle(fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Purple, unfocusedBorderColor = Outline,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(Modifier.height(16.dp))

                if (viewModel.resultMessage != null && !viewModel.isTrimming) {
                    val bg = if (viewModel.isSuccess) Green.copy(alpha = 0.15f) else Red.copy(alpha = 0.15f)
                    val fg = if (viewModel.isSuccess) Green else Red
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bg, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(if (viewModel.isSuccess) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, null, tint = fg, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(viewModel.resultMessage ?: "", color = fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { if (viewModel.isSuccess) viewModel.dismissAndReset() else viewModel.clearResult() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Filled.Close, "Kapat", tint = fg.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                if (viewModel.isTrimming) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { viewModel.trimProgress / 100f },
                            modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = Purple, trackColor = SurfaceHigh
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("%" + viewModel.trimProgress, color = TextSec, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth().height(50.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {}, enabled = false,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(disabledContainerColor = SurfaceHigh)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = PurpleLight)
                            Spacer(Modifier.width(10.dp))
                            Text("Kırpılıyor… %" + viewModel.trimProgress, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        }
                        IconButton(
                            onClick = viewModel::cancelTrim,
                            modifier = Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Red.copy(alpha = 0.15f))
                        ) {
                            Icon(Icons.Filled.Close, "İptal", tint = Red, modifier = Modifier.size(22.dp))
                        }
                    }
                } else {
                    Button(
                        onClick = viewModel::startTrim,
                        enabled = viewModel.outputFolderUri != null && viewModel.trimEndMs > viewModel.trimStartMs,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Purple, disabledContainerColor = SurfaceHigh)
                    ) {
                        Icon(Icons.Filled.ContentCut, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("KIRPMAYA BAŞLA", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(24.dp))
            } else {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun TimeInputGroup(
    label: String,
    totalMs: Long,
    enabled: Boolean,
    onChange: (Long) -> Unit
) {
    val minutes = totalMs / 60000
    val seconds = (totalMs % 60000) / 1000
    val millis = totalMs % 1000

    Column {
        Text(label, color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeField(
                value = minutes,
                label = "Dakika",
                enabled = enabled,
                maxDigits = 4,
                modifier = Modifier.weight(1f)
            ) { newMin ->
                onChange(newMin * 60000 + seconds * 1000 + millis)
            }
            TimeField(
                value = seconds,
                label = "Saniye",
                enabled = enabled,
                maxDigits = 2,
                modifier = Modifier.weight(1f)
            ) { newSec ->
                onChange(minutes * 60000 + newSec.coerceAtMost(59) * 1000 + millis)
            }
            TimeField(
                value = millis,
                label = "Salise (ms)",
                enabled = enabled,
                maxDigits = 3,
                modifier = Modifier.weight(1f)
            ) { newMs ->
                onChange(minutes * 60000 + seconds * 1000 + newMs.coerceAtMost(999))
            }
        }
    }
}

@Composable
private fun TimeField(
    value: Long,
    label: String,
    enabled: Boolean,
    maxDigits: Int,
    modifier: Modifier = Modifier,
    onValueCommit: (Long) -> Unit
) {
    var text by remember { mutableStateOf(value.toString()) }
    var hasFocus by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (!hasFocus) {
            text = value.toString()
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(maxDigits)
            text = digits
            onValueCommit(digits.toLongOrNull() ?: 0L)
        },
        label = { Text(label, fontSize = 10.sp) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = TextStyle(fontSize = 13.sp),
        modifier = modifier.onFocusChanged { state ->
            if (hasFocus && !state.isFocused) {
                text = value.toString()
            }
            hasFocus = state.isFocused
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Purple, unfocusedBorderColor = Outline,
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
        )
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  OYNATICI ORTAK BİLEŞENLER VE DONANIM OPTİMİZASYONU
// ─────────────────────────────────────────────────────────────────────────────

internal fun trackLabel(f: Format, isAudio: Boolean, index: Int): String {
    val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { code ->
        val name = runCatching { Locale(code).getDisplayLanguage(Locale.getDefault()) }.getOrDefault("")
        if (name.isBlank() || name.equals(code, ignoreCase = true)) code.uppercase() else name
    }
    val base = f.label?.takeIf { it.isNotBlank() } ?: lang ?: "Parça ${index + 1}"
    if (!isAudio) return base
    val ch = when (f.channelCount) {
        Format.NO_VALUE, 0 -> null
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${f.channelCount} kanal"
    }
    return listOfNotNull(base, ch).joinToString(", ")
}

@Composable
internal fun TrackMenuButtons(
    player: ExoPlayer,
    tracks: Tracks,
    tint: Color,
    onMenuState: (Boolean) -> Unit = {}
) {
    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    var audioMenu by remember { mutableStateOf(false) }
    var textMenu by remember { mutableStateOf(false) }
    var sizePanel by remember { mutableStateOf(false) }
    var audioClosedAt by remember { mutableLongStateOf(0L) }
    var textClosedAt by remember { mutableLongStateOf(0L) }
    var sizeClosedAt by remember { mutableLongStateOf(0L) }
    fun recent(t: Long) = System.currentTimeMillis() - t < 400L
    val ctx = LocalContext.current
    LaunchedEffect(audioMenu, textMenu, sizePanel) { onMenuState(audioMenu || textMenu || sizePanel) }

    val params = player.trackSelectionParameters
    val textOff = params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    val audioAuto = params.overrides.values.none { it.type == C.TRACK_TYPE_AUDIO }
    val textAuto = !textOff && params.overrides.values.none { it.type == C.TRACK_TYPE_TEXT }

    val select: (Tracks.Group, Int) -> Unit = { group, idx ->
        val b = player.trackSelectionParameters.buildUpon()
        if (group.type == C.TRACK_TYPE_TEXT) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        b.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, idx))
        player.trackSelectionParameters = b.build()
    }
    val auto: (Int) -> Unit = { type ->
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(type).setTrackTypeDisabled(type, false).build()
    }

    @Composable
    fun TickIcon(on: Boolean) {
        if (on) Icon(Icons.Filled.Check, null, modifier = Modifier.size(20.dp))
        else Spacer(Modifier.size(20.dp))
    }

    if (audioGroups.sumOf { it.length } >= 2) {
        Box {
            IconButton(onClick = {
                if (audioMenu) audioMenu = false
                else if (!recent(audioClosedAt)) audioMenu = true
            }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.MusicNote, "Ses", tint = tint, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = audioMenu, onDismissRequest = { audioMenu = false; audioClosedAt = System.currentTimeMillis() }, properties = NonFocusPopup) {
                KeepSystemBarsHidden()
                DropdownMenuItem(
                    text = { Text("Otomatik") },
                    leadingIcon = { TickIcon(audioAuto) },
                    onClick = { auto(C.TRACK_TYPE_AUDIO); audioMenu = false }
                )
                var n = 0
                audioGroups.forEach { g ->
                    for (i in 0 until g.length) {
                        val label = trackLabel(g.getTrackFormat(i), true, n++)
                        val sel = !audioAuto && g.isTrackSelected(i)
                        DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = { TickIcon(sel) },
                            onClick = { select(g, i); audioMenu = false }
                        )
                    }
                }
            }
        }
    }
    if (textGroups.isNotEmpty()) {
        Box {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = {
                            when {
                                textMenu -> textMenu = false
                                sizePanel -> sizePanel = false
                                recent(textClosedAt) || recent(sizeClosedAt) -> Unit
                                else -> textMenu = true
                            }
                        },
                        onLongClick = { textMenu = false; if (!recent(sizeClosedAt)) sizePanel = true }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (textOff) Icons.Filled.SubtitlesOff else Icons.Filled.Subtitles,
                    "Altyazı (basılı tut: boyut)", tint = tint, modifier = Modifier.size(20.dp)
                )
            }
            if (sizePanel) {
                val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
                Popup(
                    popupPositionProvider = remember(gap) { AboveCenterPositionProvider(gap) },
                    onDismissRequest = { sizePanel = false; sizeClosedAt = System.currentTimeMillis() },
                    properties = NonFocusPopup
                ) {
                    KeepSystemBarsHidden()
                    val cur = subtitleSizeState.floatValue
                    Column(
                        modifier = Modifier
                            .width(300.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF141418))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text("Altyazı boyutu: ${cur.toInt()}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Slider(
                            value = cur,
                            onValueChange = { saveSubtitleSize(ctx, it) },
                            valueRange = SUBTITLE_MIN_SP..SUBTITLE_MAX_SP,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White, activeTrackColor = Color.White,
                                inactiveTrackColor = Color(0xFF3A3A42)
                            )
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        ) {
                            SUBTITLE_PRESETS.forEach { (label, v) ->
                                OptionChip(label, abs(cur - v) < 0.5f, Color.White, Color(0xFF2A2A31)) {
                                    saveSubtitleSize(ctx, v)
                                }
                            }
                        }
                    }
                }
            }
            DropdownMenu(expanded = textMenu, onDismissRequest = { textMenu = false; textClosedAt = System.currentTimeMillis() }, properties = NonFocusPopup) {
                KeepSystemBarsHidden()
                DropdownMenuItem(
                    text = { Text("Kapalı") },
                    leadingIcon = { TickIcon(textOff) },
                    onClick = {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                        textMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Otomatik") },
                    leadingIcon = { TickIcon(textAuto) },
                    onClick = { auto(C.TRACK_TYPE_TEXT); textMenu = false }
                )
                var n = 0
                textGroups.forEach { g ->
                    for (i in 0 until g.length) {
                        val label = trackLabel(g.getTrackFormat(i), false, n++)
                        val sel = !textOff && !textAuto && g.isTrackSelected(i)
                        DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = { TickIcon(sel) },
                            onClick = { select(g, i); textMenu = false }
                        )
                    }
                }
            }
        }
    }
}

internal const val SUBTITLE_MIN_SP = 12f
internal const val SUBTITLE_MAX_SP = 44f
internal val SUBTITLE_PRESETS = listOf("Küçük" to 16f, "Normal" to 20f, "Büyük" to 26f, "Çok büyük" to 32f, "Dev" to 40f)
internal val subtitleSizeState = mutableFloatStateOf(20f)
private var subtitleSizeLoaded = false

internal fun loadSubtitleSize(ctx: Context) {
    if (subtitleSizeLoaded) return
    subtitleSizeLoaded = true
    subtitleSizeState.floatValue = ctx.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        .getFloat("subtitle_sp", 20f).coerceIn(SUBTITLE_MIN_SP, SUBTITLE_MAX_SP)
}

internal fun saveSubtitleSize(ctx: Context, v: Float) {
    val c = v.coerceIn(SUBTITLE_MIN_SP, SUBTITLE_MAX_SP)
    subtitleSizeState.floatValue = c
    ctx.getSharedPreferences("player_prefs", Context.MODE_PRIVATE).edit().putFloat("subtitle_sp", c).apply()
}

@Composable
internal fun SubtitleOverlay(text: String, modifier: Modifier = Modifier, fontScale: Float = 1f) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { loadSubtitleSize(ctx) }
    if (text.isBlank()) return
    val sp = (subtitleSizeState.floatValue * fontScale).coerceIn(10f, 44f)
    Text(
        text,
        color = Color.White,
        fontSize = sp.sp,
        lineHeight = (sp * 1.2f).sp,
        textAlign = TextAlign.Center,
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(Color(0xB3000000), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun TrimPlayer(
    video: VideoFile,
    startMs: Long,
    endMs: Long,
    isTrimming: Boolean
) {
    val context = LocalContext.current
    val player = remember { buildFastPlayer(context, isMultiView = false) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var pendingSeekTargetMs by remember { mutableLongStateOf(-1L) }
    var pendingSeekAt by remember { mutableLongStateOf(0L) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var videoWpx by remember { mutableIntStateOf(0) }
    var videoHpx by remember { mutableIntStateOf(0) }
    var scaleMode by remember { mutableStateOf(ScaleMode.FIT) }
    var speed by remember { mutableFloatStateOf(1f) }
    var fullscreen by remember { mutableStateOf(false) }
    val hostView = LocalView.current
    DisposableEffect(hostView) {
        hostView.keepScreenOn = true
        onDispose { hostView.keepScreenOn = false }
    }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var cueText by remember { mutableStateOf("") }

    val startState = rememberUpdatedState(startMs)
    val endState = rememberUpdatedState(endMs)
    var lastAppliedStartMs by remember { mutableLongStateOf(startMs) }
    var lastAppliedEndMs by remember { mutableLongStateOf(endMs) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height
                    videoWpx = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
                    videoHpx = videoSize.height
                }
            }
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onCues(cueGroup: CueGroup) {
                cueText = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (System.currentTimeMillis() - pendingSeekAt > 650L) {
                    positionMs = player.currentPosition.coerceAtLeast(0L)
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    player.pause()
                    player.seekTo(startState.value)
                    positionMs = startState.value
                    isPlaying = false
                }
            }
        }
        player.addListener(listener)

        val owner = context.findActivity() as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        owner?.lifecycle?.addObserver(observer)

        onDispose {
            owner?.lifecycle?.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(video.uri) {
        val playable = if (video.uri.scheme == "content" && !isCloudAuthority(video.uri.authority)) {
            withContext(Dispatchers.IO) {
                InputAccess.realPath(context, video.uri)
            }?.let { Uri.fromFile(File(it)) } ?: video.uri
        } else {
            video.uri
        }
        player.setMediaItem(MediaItem.fromUri(playable))
        player.prepare()
        player.seekTo(startState.value)
        player.playWhenReady = false
    }

    LaunchedEffect(startMs) {
        if (abs(startMs - lastAppliedStartMs) > 30L) {
            lastAppliedStartMs = startMs
            pendingSeekTargetMs = startMs
            pendingSeekAt = System.currentTimeMillis()
            positionMs = startMs
            delay(60)
            player.seekTo(startMs)
        }
    }
    LaunchedEffect(endMs) {
        if (abs(endMs - lastAppliedEndMs) > 30L) {
            lastAppliedEndMs = endMs
            val target = (endMs - 1500L).coerceAtLeast(startState.value)
            pendingSeekTargetMs = target
            pendingSeekAt = System.currentTimeMillis()
            positionMs = target
            delay(60)
            player.seekTo(target)
        }
    }
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }

    LaunchedEffect(isTrimming) {
        if (isTrimming) player.pause()
    }

    LaunchedEffect(player, isPlaying) {
        while (isPlaying) {
            val now = System.currentTimeMillis()
            if (now - pendingSeekAt > 650L) {
                val pos = player.currentPosition.coerceAtLeast(0L)
                positionMs = pos
                if (endState.value > startState.value && pos >= endState.value - 20L) {
                    player.pause()
                    player.seekTo(startState.value)
                    positionMs = startState.value
                    isPlaying = false
                    break
                }
            }
            delay(200)
        }
    }

    val togglePlay: () -> Unit = {
        val state = player.playbackState
        if (player.playWhenReady && state != Player.STATE_ENDED) {
            player.pause()
        } else {
            val pos = player.currentPosition
            if (pos < startState.value || (endState.value > startState.value && pos >= endState.value - 50L)) {
                pendingSeekTargetMs = startState.value
                pendingSeekAt = System.currentTimeMillis()
                positionMs = startState.value
                player.seekTo(startState.value)
            }
            player.play()
        }
    }
    val seekBy: (Long) -> Unit = { delta ->
        val safeEnd = if (endState.value > startState.value) endState.value else player.duration.coerceAtLeast(1L)
        val base = if (System.currentTimeMillis() - pendingSeekAt <= 650L && pendingSeekTargetMs >= 0L) {
            pendingSeekTargetMs
        } else {
            player.currentPosition.coerceAtLeast(0L)
        }
        val target = (base + delta).coerceIn(startState.value, safeEnd)
        pendingSeekTargetMs = target
        pendingSeekAt = System.currentTimeMillis()
        positionMs = target
        player.seekTo(target)
    }
    val seekTo: (Long) -> Unit = { target ->
        val safeEnd = if (endState.value > startState.value) endState.value else player.duration.coerceAtLeast(1L)
        val clamped = target.coerceIn(startState.value, safeEnd)
        pendingSeekTargetMs = clamped
        pendingSeekAt = System.currentTimeMillis()
        positionMs = clamped
        player.seekTo(clamped)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(16.dp))
            .padding(bottom = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(Color.Black)
                .clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            if (video.isAudioOnly) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.MusicNote, null, tint = PurpleLight, modifier = Modifier.size(48.dp))
                }
            } else if (!fullscreen) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().clipToBounds()) {
                    val byWidth = maxWidth / aspect.coerceAtLeast(0.1f)
                    val h = if (byWidth < 260.dp) byWidth else 260.dp
                    Box(modifier = Modifier.fillMaxWidth().height(h).clipToBounds()) {
                        VideoSurface(player, aspect, videoWpx, videoHpx, scaleMode, Modifier.fillMaxSize())
                        SubtitleOverlay(cueText, Modifier.align(Alignment.BottomCenter))
                    }
                }
            } else {
                Box(Modifier.fillMaxWidth().height(120.dp))
            }
        }

        PlayerControls(
            isPlaying = isPlaying,
            positionMs = positionMs,
            startMs = startMs,
            endMs = endMs,
            onTogglePlay = togglePlay,
            onSeekBy = seekBy,
            onSeekTo = seekTo,
            speed = speed,
            onSpeedChange = { speed = it },
            scaleMode = scaleMode,
            onScaleChange = { scaleMode = it },
            showScale = !video.isAudioOnly,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrackMenuButtons(player, tracks, TextPrimary)
                    if (!video.isAudioOnly) {
                        IconButton(onClick = {
                            fullscreen = true
                        }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Filled.Fullscreen, "Tam ekran", tint = Blue, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        )
    }

    if (fullscreen) {
        FullscreenPlayerDialog(
            player = player,
            tracks = tracks,
            cueText = cueText,
            aspect = aspect,
            videoWpx = videoWpx,
            videoHpx = videoHpx,
            scaleMode = scaleMode,
            onScaleChange = { scaleMode = it },
            speed = speed,
            onSpeedChange = { speed = it },
            isPlaying = isPlaying,
            positionMs = positionMs,
            startMs = startMs,
            endMs = endMs,
            onTogglePlay = togglePlay,
            onSeekBy = seekBy,
            onSeekTo = seekTo,
            onExit = { fullscreen = false }
        )
    }
}

/**
 * Parmakla sağa/sola sürüklenirken ASLA iptal olmayan, geri sıçramayan ve dekoderi kilitlemeyen
 * akıcı Turkuaz Video Slider bileşeni:
 * - Sürükleme boyunca (isDragging == true) yalnızca yerel dragValueMs güncellenir; ExoPlayer'a
 *   saniyede 20 kez flush() gönderilmez (veya en fazla 300ms'de bir hafif önizleme gönderilir).
 * - Parmak bırakıldığında (onValueChangeFinished) kesin hedef saniyeye tek seferde atlanır.
 */
@Composable
internal fun SmoothVideoSlider(
    positionMs: Long,
    startMs: Long,
    endMs: Long,
    onSeekTo: (Long) -> Unit,
    onDragStateChange: (Boolean) -> Unit = {},
    textColor: Color = Color.White,
    fontSizeSp: Int = 12,
    rowHeight: Dp = 34.dp
) {
    val safeEnd = endMs.coerceAtLeast(startMs + 1L)
    val enabled = endMs > startMs

    var isDragging by remember { mutableStateOf(false) }
    var dragValueMs by remember { mutableLongStateOf(positionMs.coerceIn(startMs, safeEnd)) }
    var lastLiveSeekAt by remember { mutableLongStateOf(0L) }

    // Yalnızca sürüklenmiyorken dışarıdan gelen positionMs değerini takip et
    LaunchedEffect(positionMs, isDragging, startMs, safeEnd) {
        if (!isDragging) {
            dragValueMs = positionMs.coerceIn(startMs, safeEnd)
        }
    }

    val shownMs = (if (isDragging) dragValueMs else positionMs).coerceIn(startMs, safeEnd)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
    ) {
        Text(
            text = formatShortTime(shownMs),
            color = textColor,
            fontSize = fontSizeSp.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        Slider(
            value = shownMs.toFloat(),
            onValueChange = { newVal ->
                if (enabled) {
                    if (!isDragging) {
                        isDragging = true
                        onDragStateChange(true)
                    }
                    val target = newVal.toLong().coerceIn(startMs, safeEnd)
                    dragValueMs = target
                    val now = System.currentTimeMillis()
                    if (now - lastLiveSeekAt >= 320L) {
                        lastLiveSeekAt = now
                        onSeekTo(target)
                    }
                }
            },
            onValueChangeFinished = {
                if (enabled) {
                    val finalTarget = dragValueMs.coerceIn(startMs, safeEnd)
                    onSeekTo(finalTarget)
                }
                isDragging = false
                onDragStateChange(false)
            },
            valueRange = startMs.toFloat()..safeEnd.toFloat(),
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
            colors = SliderDefaults.colors(
                thumbColor = PlayerTurquoise,
                activeTrackColor = PlayerTurquoise,
                inactiveTrackColor = Color(0x55FFFFFF),
                disabledThumbColor = PlayerTurquoise.copy(alpha = 0.5f),
                disabledActiveTrackColor = PlayerTurquoise.copy(alpha = 0.5f),
                disabledInactiveTrackColor = Color(0xFF2A2A31)
            )
        )
        Text(
            text = formatShortTime(if (endMs > 0L) endMs else 0L),
            color = textColor.copy(alpha = 0.88f),
            fontSize = fontSizeSp.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
internal fun PlayerControls(
    isPlaying: Boolean,
    positionMs: Long,
    startMs: Long,
    endMs: Long,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    speed: Float = 1f,
    onSpeedChange: (Float) -> Unit = {},
    scaleMode: ScaleMode = ScaleMode.FIT,
    onScaleChange: (ScaleMode) -> Unit = {},
    showScale: Boolean = true,
    onMenuOpen: (Boolean) -> Unit = {},
    textColor: Color = TextPrimary,
    modifier: Modifier = Modifier,
    onRotateScreen: (() -> Unit)? = null,
    leadingBottom: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    var speedPanel by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (trailing != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp)
            ) {
                trailing()
            }
        }

        // 1. Satır: Sol Süre + Akıcı Turkuaz Slider + Sağ Toplam Süre
        SmoothVideoSlider(
            positionMs = positionMs,
            startMs = startMs,
            endMs = endMs,
            onSeekTo = onSeekTo,
            onDragStateChange = { dragging -> onMenuOpen(dragging) },
            textColor = textColor,
            fontSizeSp = 12,
            rowHeight = 36.dp
        )

        // 2. Satır: Solda (+ Ekle), Ortada En-Boy Oranı, -10sn, Oynat/Duraklat, +10sn, Döndür, Hız (1X)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
            contentAlignment = Alignment.Center
        ) {
            if (leadingBottom != null) {
                Box(modifier = Modifier.align(Alignment.CenterStart)) {
                    leadingBottom()
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                modifier = Modifier
                    .padding(start = if (leadingBottom != null) 72.dp else 0.dp)
            ) {
                if (showScale) {
                    ScaleButton(
                        mode = scaleMode,
                        onChange = onScaleChange,
                        onMenuOpen = onMenuOpen,
                        tint = textColor,
                        buttonSize = 38.dp,
                        iconSize = 21.dp
                    )
                }
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable { onSeekBy(-10_000L) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Replay10, "10 sn geri", tint = textColor, modifier = Modifier.size(23.dp))
                }
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onTogglePlay),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (isPlaying) "Duraklat" else "Oynat",
                        tint = textColor,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable { onSeekBy(10_000L) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Forward10, "10 sn ileri", tint = textColor, modifier = Modifier.size(23.dp))
                }
                if (onRotateScreen != null) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onRotateScreen),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.ScreenRotation, "Döndür", tint = textColor, modifier = Modifier.size(21.dp))
                    }
                }
                SpeedButton(
                    speed = speed,
                    onChange = onSpeedChange,
                    onMenuOpen = onMenuOpen,
                    tint = textColor,
                    onOpenPanel = { speedPanel = true; onMenuOpen(true) },
                    buttonHeight = 38.dp,
                    fontSizeSp = 14
                )
            }
        }
        if (speedPanel) {
            SpeedPanelPopup(
                speed = speed,
                onChange = onSpeedChange,
                onDismiss = {
                    speedPanel = false
                    lastSpeedPopupDismissAt = System.currentTimeMillis()
                    onMenuOpen(false)
                }
            )
        }
    }
}

internal class AboveCenterPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, maxX)
        val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

internal val NonFocusPopup = PopupProperties(focusable = false, dismissOnClickOutside = true, dismissOnBackPress = true)

@Suppress("DEPRECATION")
internal fun hideSystemBarsOf(v: View) {
    v.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        v.windowInsetsController?.let { c ->
            c.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(android.view.WindowInsets.Type.systemBars())
        }
    }
}

@Composable
internal fun KeepSystemBarsHidden() {
    val v = LocalView.current
    DisposableEffect(v) {
        hideSystemBarsOf(v)
        onDispose { }
    }
}

fun prewarmVideoDecoders() {
    for (mime in listOf(
        androidx.media3.common.MimeTypes.VIDEO_AV1, androidx.media3.common.MimeTypes.VIDEO_H265,
        androidx.media3.common.MimeTypes.VIDEO_H264, androidx.media3.common.MimeTypes.VIDEO_VP9,
        androidx.media3.common.MimeTypes.AUDIO_AAC, androidx.media3.common.MimeTypes.AUDIO_E_AC3,
        androidx.media3.common.MimeTypes.AUDIO_AC3, androidx.media3.common.MimeTypes.AUDIO_OPUS
    )) {
        try { androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, false, false) }
        catch (_: Throwable) { }
    }
}

internal fun isCloudAuthority(a: String?): Boolean {
    val x = a.orEmpty().lowercase()
    return x.contains("com.google.android.apps.docs") || x.contains("drive") ||
        x.contains("onedrive") || x.contains("dropbox") || x.contains("cloud")
}

/**
 * 1–4 eşzamanlı video oynatmada %100 donanım hızlandırmalı, sıfır kilitlenmeli ve buz gibi çalışan ExoPlayer:
 * - setEnableAudioTrackPlaybackParams(true) KALDIRILDI: Samsung/Android AudioFlinger HAL'inde 2., 3. ve 4.
 *   videoların ses saatini (MediaClock) 00:00'da kilitleyip videoları donduran hata tamamen giderildi!
 * - forceEnableMediaCodecAsynchronousQueueing() KALDIRILDI: 4 eşzamanlı donanım dekoderinde C2 kuyruk
 *   kilitlenmesini önler; standart donanım kuyruğu ile 4 video aynı anda akıcı çalışır.
 * - setBackBuffer(25_000, true) ve 20ms başlangıç tamponu ile yerel videolarda ileri/geri sarma
 *   bekletmeden (<16ms) anında gerçekleşir.
 */
internal fun buildFastPlayer(context: Context, isMultiView: Boolean = false): ExoPlayer {
    val minBufferMs = if (isMultiView) 2_000 else 2_500
    val maxBufferMs = if (isMultiView) 12_000 else 25_000
    val targetBytes = if (isMultiView) 12 * 1024 * 1024 else 32 * 1024 * 1024

    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(minBufferMs, maxBufferMs, 20, 50)
        .setTargetBufferBytes(targetBytes)
        .setPrioritizeTimeOverSizeThresholds(true)
        .setBackBuffer(if (isMultiView) 10_000 else 25_000, true)
        .build()

    val renderers = DefaultRenderersFactory(context)
        .setEnableDecoderFallback(true)

    val extractorsFactory = DefaultExtractorsFactory()
        .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
        .setConstantBitrateSeekingEnabled(true)

    val dataSourceFactory = DriveStreamResolver.buildDataSourceFactory(context)
    val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)
        .setDataSourceFactory(dataSourceFactory)

    return ExoPlayer.Builder(context, renderers)
        .setMediaSourceFactory(mediaSourceFactory)
        .setLoadControl(loadControl)
        .setSeekParameters(SeekParameters(350_000L, 350_000L))
        .setVideoChangeFrameRateStrategy(
            if (isMultiView) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        )
        .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ false
            )
        }
}

@Composable
internal fun LoadingRing(modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        modifier = modifier.size(38.dp),
        color = PlayerTurquoise,
        strokeWidth = 3.dp
    )
}

private var lastSpeedPopupDismissAt = 0L

enum class ScaleMode(val label: String) {
    FIT("En uygun"),
    CROP("Klibi genişlet (kırp)"),
    STRETCH("Ekrana yay"),
    ORIGINAL("Orijinal boyut (%100)")
}

internal val SPEED_PRESETS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f)

internal fun speedLabel(v: Float): String {
    val r = Math.round(v * 100f) / 100f
    val t = if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString().trimEnd('0').trimEnd('.')
    return "${t}X"
}

/**
 * Maksimum donanım hızlandırmalı, sıfır ısınmalı ve taşmasız VideoSurface:
 * - FIT ("En uygun") ve STRETCH ("Ekrana yay") modlarında boyut hücre sınırlarını (cw, ch) ASLA aşmadığı için
 *   %100 donanım birleştiricisi (SurfaceView / Hardware Composer Overlay) kullanılır -> GPU kopyalaması %0,
 *   cihaz buz gibi çalışır ve döndürme esnasında Surface yeniden oluşturulmadığı için 0 ms'de döner!
 * - Yalnızca CROP ("Klibi genişlet") veya ORIGINAL modlarında hedef boyut hücreden büyük olduğunda
 *   TextureView + clipToBounds() kullanılarak yan hücrelere taşma %100 engellenir.
 */
@Composable
internal fun VideoSurface(
    player: ExoPlayer,
    aspect: Float,
    videoWpx: Int,
    videoHpx: Int,
    mode: ScaleMode,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val cw = maxWidth
        val ch = maxHeight
        val a = aspect.coerceAtLeast(0.1f)
        val wider = (cw / ch.coerceAtLeast(1.dp)) > a
        val fit: Pair<Dp, Dp> = if (wider) Pair(ch * a, ch) else Pair(cw, cw / a)
        val target: Pair<Dp, Dp> = when (mode) {
            ScaleMode.FIT -> fit
            ScaleMode.CROP -> if (wider) Pair(cw, cw / a) else Pair(ch * a, ch)
            ScaleMode.STRETCH -> Pair(cw, ch)
            ScaleMode.ORIGINAL -> if (videoWpx > 0 && videoHpx > 0) {
                with(density) { Pair(videoWpx.toDp(), videoHpx.toDp()) }
            } else fit
        }

        val needsCroppedTexture = (mode == ScaleMode.CROP || mode == ScaleMode.ORIGINAL) &&
            (target.first > cw + 1.dp || target.second > ch + 1.dp)

        if (!needsCroppedTexture) {
            val safeW = target.first.coerceAtMost(cw)
            val safeH = target.second.coerceAtMost(ch)
            key("hw_surface") {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).also { sv ->
                            sv.tag = player
                            player.setVideoSurfaceView(sv)
                        }
                    },
                    update = { sv ->
                        if (sv.tag !== player) {
                            sv.tag = player
                            player.setVideoSurfaceView(sv)
                        }
                    },
                    onRelease = { sv ->
                        if (sv.tag === player) {
                            player.clearVideoSurfaceView(sv)
                        }
                        sv.tag = null
                    },
                    modifier = Modifier.requiredSize(safeW, safeH)
                )
            }
        } else {
            val fw = fit.first.value.coerceAtLeast(1f)
            val fh = fit.second.value.coerceAtLeast(1f)
            val sx = target.first.value / fw
            val sy = target.second.value / fh
            key("cropped_texture") {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).also { tv ->
                            tv.isOpaque = false
                            tv.tag = player
                            player.setVideoTextureView(tv)
                        }
                    },
                    update = { tv ->
                        if (tv.tag !== player) {
                            tv.tag = player
                            player.setVideoTextureView(tv)
                        }
                    },
                    onRelease = { tv ->
                        if (tv.tag === player) {
                            player.clearVideoTextureView(tv)
                        }
                        tv.tag = null
                    },
                    modifier = Modifier
                        .requiredSize(fit.first, fit.second)
                        .graphicsLayer {
                            scaleX = sx
                            scaleY = sy
                        }
                )
            }
        }
    }
}

@Composable
internal fun OptionChip(
    text: String,
    selected: Boolean,
    textColor: Color = TextPrimary,
    bg: Color = SurfaceHigh,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Purple else bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (selected) Color.White else textColor,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
internal fun TransientLabel(text: String?, onDone: () -> Unit) {
    if (text == null) return
    LaunchedEffect(text) {
        delay(1000)
        onDone()
    }
    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, -140)
    ) {
        KeepSystemBarsHidden()
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xD9000000))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
internal fun ScaleButton(
    mode: ScaleMode,
    onChange: (ScaleMode) -> Unit,
    onMenuOpen: (Boolean) -> Unit,
    tint: Color,
    buttonSize: Dp = 44.dp,
    iconSize: Dp = 22.dp
) {
    var open by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val screenW = LocalConfiguration.current.screenWidthDp
    val menuWidth = (screenW - 32).coerceIn(240, 560).dp
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
    val close = { open = false; onMenuOpen(false) }
    Box {
        Box(
            modifier = Modifier
                .size(buttonSize)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = {
                        val all = ScaleMode.values()
                        val next = all[(mode.ordinal + 1) % all.size]
                        onChange(next)
                        hint = next.label
                    },
                    onLongClick = { open = true; onMenuOpen(true) }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.AspectRatio, "Ölçekleme: ${mode.label}", tint = tint, modifier = Modifier.size(iconSize))
        }
        TransientLabel(hint) { hint = null }
        if (open) {
            Popup(
                popupPositionProvider = remember(gap) { AboveCenterPositionProvider(gap) },
                onDismissRequest = close,
                properties = NonFocusPopup
            ) {
                KeepSystemBarsHidden()
                Column(
                    modifier = Modifier
                        .width(menuWidth)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF141418))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text("Ölçekleme", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    ) {
                        ScaleMode.values().forEach { m ->
                            OptionChip(m.label, m == mode, Color.White, Color(0xFF2A2A31)) { onChange(m); close() }
                        }
                    }
                }
            }
        }
    }
}

private val SPEED_LIST = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

@Composable
internal fun SpeedButton(
    speed: Float,
    onChange: (Float) -> Unit,
    onMenuOpen: (Boolean) -> Unit,
    tint: Color,
    onOpenPanel: () -> Unit,
    buttonHeight: Dp = 44.dp,
    fontSizeSp: Int = 14
) {
    var listOpen by remember { mutableStateOf(false) }
    val screenH = LocalConfiguration.current.screenHeightDp
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    val close = {
        listOpen = false
        lastSpeedPopupDismissAt = System.currentTimeMillis()
        onMenuOpen(false)
    }
    Box {
        Box(
            modifier = Modifier
                .height(buttonHeight)
                .widthIn(min = buttonHeight)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = {
                        if (System.currentTimeMillis() - lastSpeedPopupDismissAt > 350L) {
                            listOpen = true
                            onMenuOpen(true)
                        }
                    },
                    onLongClick = { listOpen = false; onOpenPanel() }
                )
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(speedLabel(speed), color = tint, fontSize = fontSizeSp.sp, fontWeight = FontWeight.Medium)
        }
        if (listOpen) {
            Popup(
                popupPositionProvider = remember(gap) { AboveCenterPositionProvider(gap) },
                onDismissRequest = close,
                properties = NonFocusPopup
            ) {
                KeepSystemBarsHidden()
                Column(
                    modifier = Modifier
                        .width(124.dp)
                        .heightIn(max = (screenH * 0.6f).dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF161616))
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp)
                ) {
                    SPEED_LIST.forEach { v ->
                        val sel = abs(v - speed) < 0.001f
                        Text(
                            speedLabel(v),
                            color = if (sel) PlayerTurquoise else Color.White,
                            fontSize = 16.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onChange(v); close() }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SpeedPanelPopup(
    speed: Float,
    onChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val screenW = LocalConfiguration.current.screenWidthDp
    val panelWidth = (screenW - 32).coerceIn(260, 560).dp
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    Popup(
        popupPositionProvider = remember(gap) { AboveCenterPositionProvider(gap) },
        onDismissRequest = onDismiss,
        properties = NonFocusPopup
    ) {
        KeepSystemBarsHidden()
        Column(
            modifier = Modifier
                .width(panelWidth)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF141418))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                "Oynatma hızı: ${speedLabel(speed)}",
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2A2A31))
                        .clickable { onChange(((speed - 0.05f).coerceIn(0.25f, 5f) * 100f).let { Math.round(it) / 100f }) },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Remove, "Yavaşlat", tint = Color.White) }
                Slider(
                    value = speed,
                    onValueChange = { v -> onChange((Math.round(v * 20f) / 20f).coerceIn(0.25f, 5f)) },
                    valueRange = 0.25f..5f,
                    modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = PlayerTurquoise, activeTrackColor = PlayerTurquoise, inactiveTrackColor = Color(0xFF3A3A42)
                    )
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2A2A31))
                        .clickable { onChange(((speed + 0.05f).coerceIn(0.25f, 5f) * 100f).let { Math.round(it) / 100f }) },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Add, "Hızlandır", tint = Color.White) }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                SPEED_PRESETS.forEach { v ->
                    OptionChip(speedLabel(v), abs(v - speed) < 0.001f, Color.White, Color(0xFF2A2A31)) {
                        onChange(v); onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenPlayerDialog(
    player: ExoPlayer,
    tracks: Tracks,
    cueText: String,
    aspect: Float,
    videoWpx: Int,
    videoHpx: Int,
    scaleMode: ScaleMode,
    onScaleChange: (ScaleMode) -> Unit,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    isPlaying: Boolean,
    positionMs: Long,
    startMs: Long,
    endMs: Long,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onExit: () -> Unit,
    overlayText: String? = null,
    showLoading: Boolean = false
) {
    val context = LocalContext.current
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = previous }
    }

    LaunchedEffect(controlsVisible, isPlaying, interaction, menuOpen) {
        if (controlsVisible && isPlaying && !menuOpen) {
            delay(3500)
            controlsVisible = false
        }
    }

    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val view = LocalView.current
        KeepSystemBarsHidden()
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val activityWindow = context.findActivity()?.window
            val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            view.keepScreenOn = true
            window?.addFlags(flag)
            activityWindow?.addFlags(flag)
            window?.let { w ->
                WindowCompat.setDecorFitsSystemWindows(w, false)
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                if (Build.VERSION.SDK_INT >= 28) {
                    val lp = w.attributes
                    lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    w.attributes = lp
                }
            }
            hideSystemBarsOf(view)
            onDispose {
                view.keepScreenOn = false
                window?.clearFlags(flag)
                activityWindow?.clearFlags(flag)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            VideoSurface(player, aspect, videoWpx, videoHpx, scaleMode, Modifier.fillMaxSize())

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { controlsVisible = !controlsVisible }
            )

            SubtitleOverlay(
                cueText,
                Modifier.align(Alignment.BottomCenter).padding(bottom = if (controlsVisible) 110.dp else 24.dp)
            )

            if (showLoading && overlayText == null) {
                LoadingRing(Modifier.align(Alignment.Center))
            }
            if (overlayText != null) {
                Text(
                    overlayText,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }

            if (controlsVisible) {
                IconButton(
                    onClick = onExit,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                ) {
                    Icon(Icons.Filled.ArrowBack, "Geri", tint = Color.White)
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.68f))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    PlayerControls(
                        isPlaying = isPlaying,
                        positionMs = positionMs,
                        startMs = startMs,
                        endMs = endMs,
                        onTogglePlay = { interaction++; onTogglePlay() },
                        onSeekBy = { interaction++; onSeekBy(it) },
                        onSeekTo = { interaction++; onSeekTo(it) },
                        speed = speed,
                        onSpeedChange = { interaction++; onSpeedChange(it) },
                        scaleMode = scaleMode,
                        onScaleChange = { interaction++; onScaleChange(it) },
                        onMenuOpen = { menuOpen = it },
                        textColor = Color.White,
                        onRotateScreen = {
                            interaction++
                            val act = context.findActivity()
                            if (act != null) {
                                val landscape = act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                                act.requestedOrientation = if (landscape)
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            }
                        },
                        trailing = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                TrackMenuButtons(player, tracks, Color.White) { menuOpen = it }
                                IconButton(onClick = {
                                    interaction++
                                    onExit()
                                }, modifier = Modifier.size(34.dp)) {
                                    Icon(
                                        Icons.Filled.FullscreenExit,
                                        "Tam ekrandan çık",
                                        tint = Color.White, modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  ÇOKLU VE TEKLİ VİDEO OYNATICI (1–4 EŞZAMANLI VİDEO, SIFIR WEBVIEW/OTURUM EKRANI,
//  %100 DONANIM HIZLANDIRMALI SURFACEVIEW, ANINDA EKRAN DÖNDÜRME VE AKICI SLIDER)
// ─────────────────────────────────────────────────────────────────────────────

data class PlayerVideoSlot(
    val id: Long,
    val uri: Uri,
    val title: String? = null
)

enum class MultiGridMode(val badge: String) {
    AUTO("Otomatik"),
    SINGLE("1x1"),
    SPLIT_H("1x2"),
    SPLIT_V("2x1"),
    GRID_2X2("2x2")
}

private fun formatShortTime(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L)) / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

@Stable
internal class SlotPlayerHolder(
    val slotId: Long,
    context: Context,
    isMultiView: Boolean
) {
    val appContext: Context = context.applicationContext
    val player: ExoPlayer = buildFastPlayer(appContext, isMultiView = isMultiView)

    var loadedUri: Uri? by mutableStateOf(null)
    var isCloudStream: Boolean by mutableStateOf(false)
    var isPlaying: Boolean by mutableStateOf(false)
    var positionMs: Long by mutableLongStateOf(0L)
    var durationMs: Long by mutableLongStateOf(0L)
    var aspect: Float by mutableFloatStateOf(16f / 9f)
    var videoWpx: Int by mutableIntStateOf(0)
    var videoHpx: Int by mutableIntStateOf(0)
    var scaleMode: ScaleMode by mutableStateOf(ScaleMode.FIT)
    var speed: Float by mutableFloatStateOf(1f)
    var muted: Boolean by mutableStateOf(false)
    var tracks: Tracks by mutableStateOf(Tracks.EMPTY)
    var cueText: String by mutableStateOf("")
    var buffering: Boolean by mutableStateOf(true)
    var errorText: String? by mutableStateOf(null)

    private var pendingSeekTargetMs: Long = -1L
    private var pendingSeekTimestamp: Long = 0L

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                aspect = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height
                videoWpx = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
                videoHpx = videoSize.height
            }
        }
        override fun onRenderedFirstFrame() {
            buffering = false
            errorText = null
        }
        override fun onTracksChanged(t: Tracks) {
            tracks = t
        }
        override fun onCues(cueGroup: CueGroup) {
            cueText = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
        }
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            if (System.currentTimeMillis() - pendingSeekTimestamp > 700L) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
            }
            val d = player.duration
            if (d != C.TIME_UNSET && d > 0) durationMs = d
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            buffering = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) {
                errorText = null
                val d = player.duration
                if (d != C.TIME_UNSET && d > 0) durationMs = d
                if (System.currentTimeMillis() - pendingSeekTimestamp > 700L) {
                    positionMs = player.currentPosition.coerceAtLeast(0L)
                }
            } else if (playbackState == Player.STATE_ENDED) {
                isPlaying = false
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            buffering = false
            errorText = "Oynatılamadı: " + (error.localizedMessage ?: error.errorCodeName)
        }
    }

    init {
        player.addListener(listener)
    }

    fun loadIfNeeded(uri: Uri, scope: CoroutineScope) {
        if (loadedUri == uri) return
        loadedUri = uri
        errorText = null
        buffering = true

        scope.launch {
            val urlStr = uri.toString()
            val isDriveSaf = DriveStreamResolver.isGoogleDriveSafUri(uri)
            val isCloud = isDriveSaf || (uri.scheme == "content" && isCloudAuthority(uri.authority))
            isCloudStream = isCloud || uri.scheme == "http" || uri.scheme == "https"

            if (isCloud) {
                // Cihazda zaten açık olan Google Drive hesabından (content://com.google.android.apps.docs.storage/...)
                // hiçbir WebView veya oturum ekranı açmadan doğrudan SafNativeDataSource ile akış başlat!
                val cloudExtractors = DefaultExtractorsFactory()
                    .setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
                    .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
                    .setConstantBitrateSeekingEnabled(true)
                    .setConstantBitrateSeekingAlwaysEnabled(true)
                val cloudSourceFactory = DefaultMediaSourceFactory(
                    DriveStreamResolver.buildDataSourceFactory(appContext),
                    cloudExtractors
                )
                player.setMediaSource(cloudSourceFactory.createMediaSource(MediaItem.fromUri(uri)))
                player.prepare()
                player.playWhenReady = true
                return@launch
            }

            val finalUri = when {
                (uri.scheme == "http" || uri.scheme == "https") &&
                    DriveStreamResolver.isGoogleDriveUrl(urlStr) &&
                    !urlStr.contains("googleusercontent.com") -> {
                    val driveId = DriveStreamResolver.extractDriveFileId(urlStr)
                    if (driveId != null) {
                        Uri.parse("https://drive.usercontent.google.com/download?id=$driveId&export=download&confirm=t")
                    } else {
                        uri
                    }
                }
                uri.scheme == "content" -> {
                    withContext(Dispatchers.IO) {
                        InputAccess.realPath(appContext, uri)
                    }?.let { Uri.fromFile(File(it)) } ?: uri
                }
                else -> uri
            }

            player.setMediaItem(MediaItem.fromUri(finalUri))
            player.prepare()
            player.playWhenReady = true
        }
    }

    fun retry() {
        val u = loadedUri ?: return
        errorText = null
        buffering = true
        player.prepare()
        player.play()
    }

    fun togglePlay() {
        if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) {
                seekTo(0L)
            }
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.play()
        }
    }

    fun seekTo(targetMs: Long) {
        val safe = if (durationMs > 0) targetMs.coerceIn(0L, durationMs) else targetMs.coerceAtLeast(0L)
        pendingSeekTargetMs = safe
        pendingSeekTimestamp = System.currentTimeMillis()
        positionMs = safe
        player.seekTo(safe)
    }

    fun seekBy(deltaMs: Long) {
        val base = if (System.currentTimeMillis() - pendingSeekTimestamp <= 700L && pendingSeekTargetMs >= 0L) {
            pendingSeekTargetMs
        } else {
            player.currentPosition.coerceAtLeast(0L)
        }
        seekTo(base + deltaMs)
    }

    fun updateSpeed(newSpeed: Float) {
        speed = newSpeed
        player.setPlaybackSpeed(newSpeed)
    }

    fun applyMute(targetMuted: Boolean) {
        muted = targetMuted
        player.volume = if (targetMuted) 0f else 1f
    }

    fun toggleMute() {
        applyMute(!muted)
    }

    fun syncPositionFromPlayer() {
        val d = player.duration
        if (d != C.TIME_UNSET && d > 0 && durationMs != d) {
            durationMs = d
        }
        if (System.currentTimeMillis() - pendingSeekTimestamp > 700L) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
        }
    }

    fun release() {
        player.removeListener(listener)
        player.release()
    }
}

@Composable
fun StreamPlayerScreen(uri: Uri, onExit: () -> Unit) {
    val slots = remember(uri) { mutableStateListOf(PlayerVideoSlot(1L, uri)) }
    var nextId by remember { mutableLongStateOf(2L) }
    StreamPlayerScreen(
        slots = slots,
        onAddVideo = { newUri, title ->
            if (slots.size < 4) {
                slots.add(PlayerVideoSlot(nextId++, newUri, title))
            } else {
                slots[slots.lastIndex] = PlayerVideoSlot(nextId++, newUri, title)
            }
        },
        onRemoveSlot = { slotId ->
            slots.removeAll { it.id == slotId }
            if (slots.isEmpty()) onExit()
        },
        onUpdateSlotUri = { slotId, resolvedUri, resolvedTitle ->
            val idx = slots.indexOfFirst { it.id == slotId }
            if (idx >= 0) {
                slots[idx] = slots[idx].copy(
                    uri = resolvedUri,
                    title = resolvedTitle ?: slots[idx].title
                )
            }
        },
        onPickSystemFileForAdd = {},
        onNeedStorage = {},
        hasStorage = { true },
        onExit = onExit
    )
}

@Composable
fun StreamPlayerScreen(
    slots: List<PlayerVideoSlot>,
    onAddVideo: (Uri, String?) -> Unit,
    onRemoveSlot: (Long) -> Unit,
    onUpdateSlotUri: (Long, Uri, String?) -> Unit,
    onPickSystemFileForAdd: () -> Unit,
    onNeedStorage: () -> Unit,
    hasStorage: () -> Boolean,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    val holders = remember { mutableStateMapOf<Long, SlotPlayerHolder>() }

    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var anyPopupOpen by remember { mutableStateOf(false) }

    var focusedSlotId by remember { mutableStateOf<Long?>(null) }
    var gridMode by remember { mutableStateOf(MultiGridMode.AUTO) }
    var showAddDialog by remember { mutableStateOf(false) }

    val slotIds = slots.map { it.id }.toSet()
    slots.forEach { slot ->
        val holder = holders.getOrPut(slot.id) {
            SlotPlayerHolder(slot.id, context, isMultiView = slots.size > 1)
        }
        holder.loadIfNeeded(slot.uri, scope)
    }
    LaunchedEffect(slotIds) {
        val removedIds = holders.keys.filter { it !in slotIds }
        removedIds.forEach { id ->
            holders.remove(id)?.release()
        }
        if (focusedSlotId != null && focusedSlotId !in slotIds) {
            focusedSlotId = null
        }
    }

    // Tam ekran immersive mod ve ekranı açık tutma: Dialog penceresi OLMADAN doğrudan Activity penceresi
    // üzerinde çalışır; böylece ekran döndürme 0 ms'de gerçekleşir ve Slider sürüklemesi asla iptal olmaz!
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val actWindow = activity?.window
        val prevOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

        view.keepScreenOn = true
        actWindow?.addFlags(flag)
        if (actWindow != null) {
            WindowCompat.setDecorFitsSystemWindows(actWindow, false)
            if (Build.VERSION.SDK_INT >= 28) {
                val lp = actWindow.attributes
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                actWindow.attributes = lp
            }
            WindowInsetsControllerCompat(actWindow, view).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        hideSystemBarsOf(view)

        val owner = activity as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                holders.values.forEach { it.player.pause() }
            }
        }
        owner?.lifecycle?.addObserver(observer)

        onDispose {
            owner?.lifecycle?.removeObserver(observer)
            view.keepScreenOn = false
            actWindow?.clearFlags(flag)
            if (actWindow != null) {
                WindowInsetsControllerCompat(actWindow, view).show(WindowInsetsCompat.Type.systemBars())
            }
            activity?.requestedOrientation = prevOrientation
            holders.values.forEach { it.release() }
            holders.clear()
        }
    }

    val anyPlaying = holders.values.any { it.isPlaying }
    val allMuted = holders.values.isNotEmpty() && holders.values.all { it.muted }

    // Yalnızca kontroller açıkken 250ms'de bir konum güncelle (gizliyken %0 recomposition, %0 CPU!)
    LaunchedEffect(controlsVisible, slots.size) {
        while (controlsVisible) {
            holders.values.forEach { it.syncPositionFromPlayer() }
            delay(250)
        }
    }

    LaunchedEffect(controlsVisible, anyPlaying, interactionTick, anyPopupOpen, showAddDialog) {
        if (controlsVisible && anyPlaying && !anyPopupOpen && !showAddDialog) {
            delay(4000)
            controlsVisible = false
        }
    }

    val handleBack: () -> Unit = {
        if (focusedSlotId != null) {
            focusedSlotId = null
        } else {
            onExit()
        }
    }

    BackHandler(onBack = handleBack)

    val singleSlot = when {
        focusedSlotId != null -> slots.firstOrNull { it.id == focusedSlotId }
        slots.size == 1 -> slots.firstOrNull()
        gridMode == MultiGridMode.SINGLE -> slots.firstOrNull()
        else -> null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (singleSlot != null) {
            val holder = holders[singleSlot.id]
            if (holder != null) {
                SingleOrFocusedPlayerContent(
                    holder = holder,
                    isFocusedFromMulti = focusedSlotId != null && slots.size > 1,
                    controlsVisible = controlsVisible,
                    onToggleControls = {
                        controlsVisible = !controlsVisible
                        if (controlsVisible) holder.syncPositionFromPlayer()
                        interactionTick++
                    },
                    onInteraction = { interactionTick++ },
                    onMenuOpen = { anyPopupOpen = it },
                    onOpenAddDialog = {
                        interactionTick++
                        showAddDialog = true
                    },
                    onBackOrUnfocus = handleBack
                )
            }
        } else {
            val modeToRender = when {
                gridMode != MultiGridMode.AUTO -> gridMode
                slots.size == 2 -> if (isLandscape) MultiGridMode.SPLIT_H else MultiGridMode.SPLIT_V
                else -> MultiGridMode.GRID_2X2
            }
            val effectiveLayoutLabel = when (modeToRender) {
                MultiGridMode.SINGLE -> "1x1"
                MultiGridMode.SPLIT_H -> "1x2"
                MultiGridMode.SPLIT_V -> "2x1"
                MultiGridMode.GRID_2X2, MultiGridMode.AUTO -> "2x2"
            }

            val globalBottomBarHeight = 48.dp

            @Composable
            fun RenderCell(slot: PlayerVideoSlot, isBottomRow: Boolean, mod: Modifier) {
                val holder = holders[slot.id]
                if (holder != null) {
                    key(slot.id) {
                        MultiGridVideoCell(
                            holder = holder,
                            controlsVisible = controlsVisible,
                            bottomBarInset = if (isBottomRow && controlsVisible) globalBottomBarHeight else 0.dp,
                            onToggleControls = {
                                controlsVisible = !controlsVisible
                                if (controlsVisible) holders.values.forEach { it.syncPositionFromPlayer() }
                                interactionTick++
                            },
                            onInteraction = { interactionTick++ },
                            onPopupStateChange = { anyPopupOpen = it },
                            onMaximize = {
                                interactionTick++
                                holder.syncPositionFromPlayer()
                                focusedSlotId = slot.id
                            },
                            onCloseSlot = {
                                interactionTick++
                                onRemoveSlot(slot.id)
                            },
                            modifier = mod
                        )
                    }
                }
            }

            when (modeToRender) {
                MultiGridMode.SPLIT_H -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                        modifier = Modifier.fillMaxSize().background(Color.Black)
                    ) {
                        slots.take(2).forEach { s ->
                            RenderCell(s, isBottomRow = true, mod = Modifier.weight(1f).fillMaxHeight())
                        }
                        if (slots.size < 2) {
                            EmptyVideoSlotCard(
                                onClick = { showAddDialog = true },
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }
                MultiGridMode.SPLIT_V -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                        modifier = Modifier.fillMaxSize().background(Color.Black)
                    ) {
                        slots.take(2).forEachIndexed { idx, s ->
                            RenderCell(s, isBottomRow = (idx == 1), mod = Modifier.weight(1f).fillMaxWidth())
                        }
                        if (slots.size < 2) {
                            EmptyVideoSlotCard(
                                onClick = { showAddDialog = true },
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            )
                        }
                    }
                }
                else -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                        modifier = Modifier.fillMaxSize().background(Color.Black)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(1.dp),
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        ) {
                            val s0 = slots.getOrNull(0)
                            if (s0 != null) RenderCell(s0, isBottomRow = false, mod = Modifier.weight(1f).fillMaxHeight())
                            else EmptyVideoSlotCard({ showAddDialog = true }, Modifier.weight(1f).fillMaxHeight())

                            val s1 = slots.getOrNull(1)
                            if (s1 != null) RenderCell(s1, isBottomRow = false, mod = Modifier.weight(1f).fillMaxHeight())
                            else EmptyVideoSlotCard({ showAddDialog = true }, Modifier.weight(1f).fillMaxHeight())
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(1.dp),
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        ) {
                            val s2 = slots.getOrNull(2)
                            if (s2 != null) RenderCell(s2, isBottomRow = true, mod = Modifier.weight(1f).fillMaxHeight())
                            else EmptyVideoSlotCard({ showAddDialog = true }, Modifier.weight(1f).fillMaxHeight())

                            val s3 = slots.getOrNull(3)
                            if (s3 != null) RenderCell(s3, isBottomRow = true, mod = Modifier.weight(1f).fillMaxHeight())
                            else EmptyVideoSlotCard({ showAddDialog = true }, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            }

            if (controlsVisible) {
                IconButton(
                    onClick = onExit,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(36.dp)
                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                ) {
                    Icon(Icons.Filled.ArrowBack, "Geri", tint = Color.White, modifier = Modifier.size(20.dp))
                }

                // Ekranın EN ALTINDA VE ORTADA Genel Çoklu Oynatıcı Kontrol Barı
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(globalBottomBarHeight)
                        .background(Color(0xD90A0C10))
                        .padding(horizontal = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.align(Alignment.CenterStart),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF181B22))
                                .clickable {
                                    interactionTick++
                                    showAddDialog = true
                                }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Add, "Video Ekle", tint = PlayerTurquoise, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Ekle", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF181B22))
                                .clickable {
                                    interactionTick++
                                    gridMode = when (gridMode) {
                                        MultiGridMode.AUTO -> MultiGridMode.GRID_2X2
                                        MultiGridMode.GRID_2X2 -> MultiGridMode.SPLIT_H
                                        MultiGridMode.SPLIT_H -> MultiGridMode.SPLIT_V
                                        MultiGridMode.SPLIT_V -> MultiGridMode.SINGLE
                                        MultiGridMode.SINGLE -> MultiGridMode.AUTO
                                    }
                                }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.GridView, "Düzen", tint = Color.White, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(effectiveLayoutLabel, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        IconButton(
                            onClick = {
                                interactionTick++
                                holders.values.forEach { it.seekBy(-10_000L) }
                            },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(Icons.Filled.Replay10, "Tümünü 10 sn geri sar", tint = Color.White, modifier = Modifier.size(22.dp))
                        }

                        IconButton(
                            onClick = {
                                interactionTick++
                                if (anyPlaying) {
                                    holders.values.forEach { it.player.pause() }
                                } else {
                                    holders.values.forEach { h ->
                                        if (h.player.playbackState == Player.STATE_ENDED) h.seekTo(0L)
                                        if (h.player.playbackState == Player.STATE_IDLE) h.player.prepare()
                                        h.player.play()
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color(0x2666C2B4), CircleShape)
                        ) {
                            Icon(
                                if (anyPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (anyPlaying) "Tümünü Duraklat" else "Tümünü Oynat",
                                tint = PlayerTurquoise,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                interactionTick++
                                holders.values.forEach { it.seekBy(10_000L) }
                            },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(Icons.Filled.Forward10, "Tümünü 10 sn ileri sar", tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                    }

                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = {
                                interactionTick++
                                val targetMute = !allMuted
                                holders.values.forEach { it.applyMute(targetMute) }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                if (allMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                                "Tüm Sesler",
                                tint = if (allMuted) Color(0xFFFF6B6B) else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                interactionTick++
                                val act = context.findActivity()
                                if (act != null) {
                                    val landscape = act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                                    act.requestedOrientation = if (landscape)
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Filled.ScreenRotation, "Döndür", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddVideoSourceDialog(
            currentCount = slots.size,
            hasStorage = hasStorage,
            onNeedStorage = onNeedStorage,
            onDismiss = { showAddDialog = false },
            onAddUrl = { rawUrl ->
                showAddDialog = false
                val normalized = if (rawUrl.contains("://")) rawUrl else "https://$rawUrl"
                onAddVideo(Uri.parse(normalized), null)
            },
            onPickSystemFile = {
                showAddDialog = false
                onPickSystemFileForAdd()
            },
            onPlayLocalFile = { file ->
                showAddDialog = false
                onAddVideo(Uri.fromFile(file), file.name)
            }
        )
    }
}

@Composable
private fun SingleOrFocusedPlayerContent(
    holder: SlotPlayerHolder,
    isFocusedFromMulti: Boolean,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onInteraction: () -> Unit,
    onMenuOpen: (Boolean) -> Unit,
    onOpenAddDialog: () -> Unit,
    onBackOrUnfocus: () -> Unit
) {
    val context = LocalContext.current
    var showRing by remember { mutableStateOf(false) }

    // Yerel videolarda sarma sırasında gereksiz yüklenme halkası göstermemek için:
    // Bulut/ağ akışlarında 350ms, yerel dosyalarda yalnızca 1000ms'yi aşarsa gösterilir.
    LaunchedEffect(holder.buffering, holder.isCloudStream) {
        if (holder.buffering) {
            delay(if (holder.isCloudStream) 350L else 1000L)
            showRing = true
        } else {
            showRing = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Not: orientationKey KULLANILMAZ; böylece telefonu yan/dik çevirirken SurfaceView
        // yok edilip yeniden oluşturulmaz ve 0 ms'de anında döner!
        key(holder.slotId) {
            VideoSurface(
                player = holder.player,
                aspect = holder.aspect,
                videoWpx = holder.videoWpx,
                videoHpx = holder.videoHpx,
                mode = holder.scaleMode,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleControls
                )
        )

        SubtitleOverlay(
            text = holder.cueText,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (controlsVisible) 110.dp else 24.dp)
        )

        if (showRing && holder.errorText == null) {
            LoadingRing(Modifier.align(Alignment.Center))
        }

        if (holder.errorText != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xDD10131A))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = holder.errorText ?: "",
                    color = Color.White,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { holder.retry() }) {
                    Text("Tekrar Dene", color = PlayerTurquoise, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (controlsVisible) {
            IconButton(
                onClick = onBackOrUnfocus,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(38.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
            ) {
                Icon(Icons.Filled.ArrowBack, "Geri", tint = Color.White)
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        TrackMenuButtons(holder.player, holder.tracks, Color.White) { onMenuOpen(it) }
                        IconButton(
                            onClick = {
                                onInteraction()
                                holder.toggleMute()
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                if (holder.muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                                contentDescription = "Ses",
                                tint = if (holder.muted) Color(0xFFFF6B6B) else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        if (isFocusedFromMulti) {
                            IconButton(
                                onClick = {
                                    onInteraction()
                                    onBackOrUnfocus()
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Filled.FullscreenExit,
                                    "Çoklu Görünüme Dön",
                                    tint = Color.White,
                                    modifier = Modifier.size(21.dp)
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    PlayerControls(
                        isPlaying = holder.isPlaying,
                        positionMs = holder.positionMs,
                        startMs = 0L,
                        endMs = holder.durationMs,
                        onTogglePlay = {
                            onInteraction()
                            holder.togglePlay()
                        },
                        onSeekBy = { delta ->
                            onInteraction()
                            holder.seekBy(delta)
                        },
                        onSeekTo = { pos ->
                            onInteraction()
                            holder.seekTo(pos)
                        },
                        speed = holder.speed,
                        onSpeedChange = { newSpeed ->
                            onInteraction()
                            holder.updateSpeed(newSpeed)
                        },
                        scaleMode = holder.scaleMode,
                        onScaleChange = { newScale ->
                            onInteraction()
                            holder.scaleMode = newScale
                        },
                        onMenuOpen = onMenuOpen,
                        textColor = Color.White,
                        onRotateScreen = {
                            onInteraction()
                            val act = context.findActivity()
                            if (act != null) {
                                val landscape = act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                                act.requestedOrientation = if (landscape)
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            }
                        },
                        leadingBottom = {
                            Box(
                                modifier = Modifier
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF181B22))
                                    .clickable(onClick = onOpenAddDialog)
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = "Video Ekle",
                                        tint = PlayerTurquoise,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "Ekle",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        },
                        trailing = null
                    )
                }
            }
        }
    }
}

@Composable
private fun MultiGridVideoCell(
    holder: SlotPlayerHolder,
    controlsVisible: Boolean,
    bottomBarInset: Dp,
    onToggleControls: () -> Unit,
    onInteraction: () -> Unit,
    onPopupStateChange: (Boolean) -> Unit,
    onMaximize: () -> Unit,
    onCloseSlot: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showRing by remember { mutableStateOf(false) }
    var scaleHint by remember { mutableStateOf<String?>(null) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    var isDraggingSlider by remember { mutableStateOf(false) }

    LaunchedEffect(speedMenuOpen, isDraggingSlider) {
        onPopupStateChange(speedMenuOpen || isDraggingSlider)
    }

    LaunchedEffect(holder.buffering, holder.isCloudStream) {
        if (holder.buffering) {
            delay(if (holder.isCloudStream) 350L else 1000L)
            showRing = true
        } else {
            showRing = false
        }
    }

    LaunchedEffect(scaleHint) {
        if (scaleHint != null) {
            delay(900)
            scaleHint = null
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .clipToBounds()
    ) {
        VideoSurface(
            player = holder.player,
            aspect = holder.aspect,
            videoWpx = holder.videoWpx,
            videoHpx = holder.videoHpx,
            mode = holder.scaleMode,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleControls
                )
        )

        SubtitleOverlay(
            text = holder.cueText,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (controlsVisible) 72.dp + bottomBarInset else 8.dp),
            fontScale = 0.72f
        )

        if (showRing && holder.errorText == null) {
            LoadingRing(Modifier.align(Alignment.Center))
        }

        if (scaleHint != null) {
            Text(
                text = scaleHint ?: "",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xD9000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        if (holder.errorText != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(8.dp)
                    .background(Color(0xDD10131A), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = holder.errorText ?: "",
                    color = Color.White,
                    fontSize = 11.sp
                )
                TextButton(onClick = { holder.retry() }) {
                    Text("Tekrar Dene", color = PlayerTurquoise, fontSize = 11.sp)
                }
            }
        }

        if (controlsVisible) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .clickable {
                            onInteraction()
                            onMaximize()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Fullscreen,
                        contentDescription = "Büyüt",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .clickable {
                            onInteraction()
                            onCloseSlot()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Videoyu Kapat",
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomBarInset)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.76f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                // 1. Satır: Sol Süre + Akıcı Turkuaz Slider + Sağ Toplam Süre
                SmoothVideoSlider(
                    positionMs = holder.positionMs,
                    startMs = 0L,
                    endMs = holder.durationMs,
                    onSeekTo = { target ->
                        onInteraction()
                        holder.seekTo(target)
                    },
                    onDragStateChange = { dragging -> isDraggingSlider = dragging },
                    textColor = Color.White,
                    fontSizeSp = 10,
                    rowHeight = 30.dp
                )

                // 2. Satır: TAM ORTADA (Alignment.CenterHorizontally) ve rahat dokunulabilir büyük butonlar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                ) {
                    IconButton(
                        onClick = {
                            onInteraction()
                            val all = ScaleMode.values()
                            val next = all[(holder.scaleMode.ordinal + 1) % all.size]
                            holder.scaleMode = next
                            scaleHint = next.label
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Filled.AspectRatio,
                            contentDescription = "En-Boy Oranı: ${holder.scaleMode.label}",
                            tint = if (holder.scaleMode != ScaleMode.FIT) PlayerTurquoise else Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            onInteraction()
                            holder.seekBy(-10_000L)
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Filled.Replay10,
                            contentDescription = "10 sn geri",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            onInteraction()
                            holder.togglePlay()
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0x2466C2B4), CircleShape)
                    ) {
                        Icon(
                            if (holder.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (holder.isPlaying) "Duraklat" else "Oynat",
                            tint = PlayerTurquoise,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            onInteraction()
                            holder.seekBy(10_000L)
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Filled.Forward10,
                            contentDescription = "10 sn ileri",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            onInteraction()
                            holder.toggleMute()
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            if (holder.muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                            contentDescription = "Ses",
                            tint = if (holder.muted) Color(0xFFFF6B6B) else Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    Box {
                        Box(
                            modifier = Modifier
                                .height(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF22252E))
                                .clickable {
                                    onInteraction()
                                    speedMenuOpen = true
                                }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = speedLabel(holder.speed),
                                color = if (abs(holder.speed - 1f) > 0.01f) PlayerTurquoise else Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        DropdownMenu(
                            expanded = speedMenuOpen,
                            onDismissRequest = { speedMenuOpen = false },
                            properties = NonFocusPopup
                        ) {
                            KeepSystemBarsHidden()
                            SPEED_PRESETS.forEach { preset ->
                                val selected = abs(preset - holder.speed) < 0.01f
                                DropdownMenuItem(
                                    text = { Text(speedLabel(preset)) },
                                    leadingIcon = {
                                        if (selected) Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                                        else Spacer(Modifier.size(18.dp))
                                    },
                                    onClick = {
                                        holder.updateSpeed(preset)
                                        speedMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyVideoSlotCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.Black)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Video Ekle",
                tint = TextMuted,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text("Video Ekle", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun AddVideoSourceDialog(
    currentCount: Int,
    hasStorage: () -> Boolean,
    onNeedStorage: () -> Unit,
    onDismiss: () -> Unit,
    onAddUrl: (String) -> Unit,
    onPickSystemFile: () -> Unit,
    onPlayLocalFile: (File) -> Unit
) {
    var urlText by remember { mutableStateOf("") }
    var browsingLocal by remember { mutableStateOf(false) }

    if (browsingLocal) {
        Dialog(
            onDismissRequest = { browsingLocal = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            LocalFileBrowser(
                onClose = { browsingLocal = false },
                onPlayFile = { f ->
                    browsingLocal = false
                    onPlayLocalFile(f)
                }
            )
        }
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        KeepSystemBarsHidden()
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Surface)
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (currentCount < 4) "Video Ekle (${currentCount + 1}/4)" else "4. Videoyu Değiştir",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, "Kapat", tint = TextSec)
                }
            }

            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                label = { Text("URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    if (urlText.isNotBlank()) onAddUrl(urlText.trim())
                }),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { if (urlText.isNotBlank()) onAddUrl(urlText.trim()) },
                enabled = urlText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Purple, disabledContainerColor = SurfaceHigh)
            ) {
                Icon(Icons.Filled.Link, null, modifier = Modifier.padding(end = 8.dp))
                Text("URL'yi Ekle ve Oynat")
            }

            OutlinedButton(
                onClick = onPickSystemFile,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.UploadFile, null, tint = PurpleLight, modifier = Modifier.padding(end = 8.dp))
                Text("Dosya Seç", color = TextPrimary)
            }

            OutlinedButton(
                onClick = {
                    if (hasStorage()) browsingLocal = true else onNeedStorage()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.FolderOpen, null, tint = PurpleLight, modifier = Modifier.padding(end = 8.dp))
                Text("Dosya Gezgininden Seç", color = TextPrimary)
            }
        }
    }
}
