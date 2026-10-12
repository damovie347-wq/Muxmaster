package com.example.muxmaster.ui.screens

import android.app.Activity
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.media3.common.PlaybackException
import android.content.res.Configuration
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.unit.Dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.TextureView
import androidx.compose.ui.text.style.TextAlign
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Remove
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.example.muxmaster.model.VideoFile
import com.example.muxmaster.ui.components.VideoCard
import com.example.muxmaster.ui.theme.*
import com.example.muxmaster.viewmodel.TrimViewModel
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

private fun formatClock(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val m = safe / 60000
    val s = (safe % 60000) / 1000
    val msec = safe % 1000
    return String.format(Locale.US, "%02d:%02d.%03d", m, s, msec)
}

private fun Context.findActivity(): Activity? {
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
            // ── OYNATICI: seçilen aralığı birebir önizler ─────────────────────
            if (video != null) {
                TrimPlayer(
                    video = video,
                    startMs = viewModel.trimStartMs,
                    endMs = viewModel.trimEndMs,
                    isTrimming = viewModel.isTrimming
                )
                Spacer(Modifier.height(12.dp))
            }

            // ── KLASÖR SEÇ + SIRADAKİ VİDEO ──────────────────────────────────
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
            // Seçili klasör + sayaç + KAPAT (çarpı)
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
                        viewModel.setTrimRange(range.start.toLong(), range.endInclusive.toLong())
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

/**
 * Sayısal zaman alanı (dakika / saniye / salise).
 *
 * KÖK NEDEN (eski hata - "20 yazan kutuyu silip 12 yazamıyorum"): bu alanın
 * gösterdiği metin eskiden HER recomposition'da dışarıdaki `value`den (üstteki
 * trimStartMs/trimEndMs'ten bölünerek) yeniden hesaplanıyordu. Kullanıcı bir
 * tuşa bastığında bu, üst state'e anında yansıyor, üst state ise (ör. minimum
 * aralık sınırlaması) değeri farklı bir sayıya yuvarlayabiliyordu; Compose'un
 * "value: String" alan TextField'ı da dışarıdan gelen değerle kendi tuttuğu
 * son metin uyuşmayınca imleci/metni dışarıdaki değere göre sıfırlıyordu. Sonuç
 * olarak atılan tuş "yutuluyor" ve kutu eski sayıya (ör. "20") geri dönüyormuş
 * gibi görünüyordu.
 *
 * ÇÖZÜM: Alan artık KENDİ yerel metnini (`text`) tutar ve ekranda her zaman
 * kullanıcının o an gerçekten yazdığını gösterir. Dışarıdaki `value` sadece bu
 * alan odakta DEĞİLKEN metne yansıtılır (video ilk yüklenirken, slider
 * sürüklenirken ya da kardeş bir alan değiştiğinde); alan odaktan çıkınca
 * (blur) nihai/sınırlanmış değere göre normalize edilir. Böylece dışarıdaki
 * yeniden hesaplamalar, kullanıcı o kutuya yazarken asla onun tuşlarının
 * üzerine yazmaz.
 */
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
                // Odaktan çıkınca kutuyu gerçek/sınırlanmış değere göre normalize et
                // (ör. boş bırakılmışsa "0", ya da girilen sayı sınırlandıysa o değer).
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
//  OYNATICI
// ─────────────────────────────────────────────────────────────────────────────

private fun trackLabel(f: Format, isAudio: Boolean, index: Int): String {
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

/** Ses ve altyazı seçim menüleri (müzik notu / altyazı simgeleri). */
@Composable
private fun TrackMenuButtons(
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
            IconButton(onClick = { audioMenu = true }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Filled.MusicNote, "Ses", tint = tint, modifier = Modifier.size(24.dp))
            }
            DropdownMenu(expanded = audioMenu, onDismissRequest = { audioMenu = false }, properties = NonFocusPopup) {
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
            // DOKUN -> altyazı parçası seçimi, BASILI TUT -> altyazı boyutu paneli
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = { textMenu = true },
                        onLongClick = { textMenu = false; sizePanel = true }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (textOff) Icons.Filled.SubtitlesOff else Icons.Filled.Subtitles,
                    "Altyazı (basılı tut: boyut)", tint = tint, modifier = Modifier.size(24.dp)
                )
            }
            if (sizePanel) {
                val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
                Popup(
                    popupPositionProvider = remember(gap) { AboveCenterPositionProvider(gap) },
                    onDismissRequest = { sizePanel = false },
                    properties = NonFocusPopup
                ) {
                    val cur = subtitleSizeState.floatValue
                    Column(
                        modifier = Modifier
                            .width(300.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xF2141418))
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
                                OptionChip(label, kotlin.math.abs(cur - v) < 0.5f, Color.White, Color(0xFF2A2A31)) {
                                    saveSubtitleSize(ctx, v)
                                }
                            }
                        }
                    }
                }
            }
            DropdownMenu(expanded = textMenu, onDismissRequest = { textMenu = false }, properties = NonFocusPopup) {
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

// ── Altyazı boyutu (kalıcı, tüm oynatıcılarda ortak) ─────────────────────────
private const val SUBTITLE_MIN_SP = 12f
private const val SUBTITLE_MAX_SP = 44f
private val SUBTITLE_PRESETS = listOf("Küçük" to 16f, "Normal" to 20f, "Büyük" to 26f, "Çok büyük" to 32f, "Dev" to 40f)
private val subtitleSizeState = mutableFloatStateOf(20f)
private var subtitleSizeLoaded = false

private fun loadSubtitleSize(ctx: Context) {
    if (subtitleSizeLoaded) return
    subtitleSizeLoaded = true
    subtitleSizeState.floatValue = ctx.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)
        .getFloat("subtitle_sp", 20f).coerceIn(SUBTITLE_MIN_SP, SUBTITLE_MAX_SP)
}

private fun saveSubtitleSize(ctx: Context, v: Float) {
    val c = v.coerceIn(SUBTITLE_MIN_SP, SUBTITLE_MAX_SP)
    subtitleSizeState.floatValue = c
    ctx.getSharedPreferences("player_prefs", Context.MODE_PRIVATE).edit().putFloat("subtitle_sp", c).apply()
}

@Composable
private fun SubtitleOverlay(text: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { loadSubtitleSize(ctx) }
    if (text.isBlank()) return
    val sp = subtitleSizeState.floatValue
    Text(
        text,
        color = Color.White,
        fontSize = sp.sp,
        lineHeight = (sp * 1.2f).sp,
        textAlign = TextAlign.Center,
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/**
 * Kırpma önizleme oynatıcısı.
 *
 * - Oynatma seçili aralıkta ([startMs]..[endMs]) yapılır: bitiş noktasında durur.
 * - Başlangıç değişince o kareye, bitiş değişince bitişten ~1.5 sn öncesine atlar.
 * - Tam ekran: aynı oynatıcıyı kullanır, konum KORUNUR (başa sarmaz). Ekrana dokununca
 *   kontroller gizlenir/görünür. Ses ve altyazı parçası seçilebilir.
 */
@Composable
private fun TrimPlayer(
    video: VideoFile,
    startMs: Long,
    endMs: Long,
    isTrimming: Boolean
) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var videoWpx by remember { mutableIntStateOf(0) }
    var videoHpx by remember { mutableIntStateOf(0) }
    var scaleMode by remember { mutableStateOf(ScaleMode.FIT) }
    var speed by remember { mutableFloatStateOf(1f) }
    var fullscreen by remember { mutableStateOf(false) }
    // İnline önizlemede de oynatma sürerken ekran kapanmasın
    val hostView = LocalView.current
    DisposableEffect(hostView, isPlaying) {
        hostView.keepScreenOn = isPlaying
        onDispose { hostView.keepScreenOn = false }
    }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var cueText by remember { mutableStateOf("") }

    val startState = rememberUpdatedState(startMs)
    val endState = rememberUpdatedState(endMs)
    val initialEnd = remember { endMs }

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
        }
        player.addListener(listener)

        // Uygulama arka plana giderken oynatmayı durdur
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

    // Dosya değişince yükle (kopyasız: doğrudan içerik URI'sinden)
    LaunchedEffect(video.uri) {
        player.setMediaItem(MediaItem.fromUri(video.uri))
        player.prepare()
        player.seekTo(startState.value)
        player.playWhenReady = false
    }

    // Başlangıç noktası değişince o noktaya atla (sürüklerken her piksele seek yapma)
    LaunchedEffect(startMs) {
        delay(120)
        player.seekTo(startMs)
    }
    // Bitiş noktası değişince bitişten hemen önceki ana atla
    LaunchedEffect(endMs) {
        if (endMs != initialEnd) {
            delay(120)
            player.seekTo((endMs - 1500L).coerceAtLeast(startState.value))
        }
    }
    // Oynatma hızı (0.25x – 5x)
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }

    // Kırpma başlayınca oynatmayı durdur
    LaunchedEffect(isTrimming) {
        if (isTrimming) player.pause()
    }
    // Yüzey (TextureView) değişince duran videoda kare yeniden çizilsin
    LaunchedEffect(fullscreen) {
        delay(200)
        if (!player.playWhenReady) player.seekTo(player.currentPosition)
    }

    // Konum takibi + bitiş noktasında durdurma
    LaunchedEffect(player) {
        while (true) {
            val state = player.playbackState
            val playing = player.playWhenReady && state != Player.STATE_ENDED && state != Player.STATE_IDLE
            isPlaying = playing
            positionMs = player.currentPosition
            if (state == Player.STATE_ENDED ||
                (player.playWhenReady && positionMs >= endState.value - 20L)
            ) {
                player.pause()
                player.seekTo(startState.value)
                positionMs = startState.value
                isPlaying = false
            }
            delay(50)
        }
    }

    val togglePlay: () -> Unit = {
        val state = player.playbackState
        if (player.playWhenReady && state != Player.STATE_ENDED) {
            player.pause()
        } else {
            val pos = player.currentPosition
            if (pos < startState.value || pos >= endState.value - 50L) player.seekTo(startState.value)
            player.play()
        }
    }
    val seekBy: (Long) -> Unit = { delta ->
        val target = (player.currentPosition + delta).coerceIn(startState.value, endState.value)
        player.seekTo(target)
        positionMs = target
    }
    val seekTo: (Long) -> Unit = { target ->
        player.seekTo(target)
        positionMs = target
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(16.dp))
            .padding(bottom = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (video.isAudioOnly) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.MusicNote, null, tint = PurpleLight, modifier = Modifier.size(48.dp))
                }
            } else if (!fullscreen) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val byWidth = maxWidth / aspect.coerceAtLeast(0.1f)
                    val h = if (byWidth < 280.dp) byWidth else 280.dp
                    Box(modifier = Modifier.fillMaxWidth().height(h)) {
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
            modifier = Modifier.padding(horizontal = 12.dp),
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrackMenuButtons(player, tracks, TextPrimary)
                    if (!video.isAudioOnly) {
                        IconButton(onClick = {
                            val pos = player.currentPosition
                            if (pos < startState.value || pos >= endState.value - 100L) {
                                // aralık dışındaysa kırpma başlangıcından başlat
                                player.seekTo(startState.value)
                                positionMs = startState.value
                                player.play()
                            } else if (pos <= startState.value + 100L && !isPlaying) {
                                player.play() // ilk kez: kırpılan andan itibaren oynat
                            }
                            // aksi halde kaldığı yerden DEVAM eder (başa sarmaz)
                            fullscreen = true
                        }, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Filled.Fullscreen, "Tam ekran", tint = Blue, modifier = Modifier.size(26.dp))
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

@Composable
private fun PlayerControls(
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
    trailing: @Composable () -> Unit = {}
) {
    // Basılı tutunca açılan hız paneli: süre çubuğunun HEMEN ÜSTÜNDE, ortalanmış açılır.
    var speedPanel by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        if (endMs > startMs) {
            Slider(
                value = positionMs.coerceIn(startMs, endMs).toFloat(),
                onValueChange = { onSeekTo(it.toLong()) },
                valueRange = startMs.toFloat()..endMs.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Purple, activeTrackColor = Purple, inactiveTrackColor = SurfaceHigh
                )
            )
        }
        // Süre (solda) + izler / tam ekran (sağda)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                formatClock(positionMs.coerceIn(startMs, maxOf(startMs, endMs))),
                color = TextSec, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            trailing()
        }
        Spacer(Modifier.height(4.dp))
        // Ana kontroller: sade ikonlar (arka plan yok), küçük görünüm ama GENİŞ dokunma
        // alanı (56 dp) ve düğmeler arasında geniş yatay boşluk -> yanlış basma olmaz.
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp)
            ) {
                if (showScale) ScaleButton(scaleMode, onScaleChange, onMenuOpen, textColor)
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .clickable { onSeekBy(-10_000L) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Replay10, "10 sn geri", tint = textColor, modifier = Modifier.size(26.dp))
                }
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onTogglePlay),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (isPlaying) "Duraklat" else "Oynat",
                        tint = textColor, modifier = Modifier.size(38.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .clickable { onSeekBy(10_000L) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Forward10, "10 sn ileri", tint = textColor, modifier = Modifier.size(26.dp))
                }
                SpeedButton(
                    speed = speed,
                    onChange = onSpeedChange,
                    onMenuOpen = onMenuOpen,
                    tint = textColor,
                    onOpenPanel = { speedPanel = true; onMenuOpen(true) }
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

/** Popup'ı bağlı olduğu öğenin (anchor) HEMEN ÜSTÜNE, yatayda ORTALANMIŞ yerleştirir. */
private class AboveCenterPositionProvider(private val gapPx: Int) : PopupPositionProvider {
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

/** Odak ÇALMAYAN popup: tam ekranda sistem çubuklarını açıp kontrolleri aşağı çökertmez. */
private val NonFocusPopup = PopupProperties(focusable = false, dismissOnClickOutside = true, dismissOnBackPress = true)

/** Popup dışına dokunup kapatınca aynı dokunuşla düğmenin tekrar açmasını engeller. */
private var lastSpeedPopupDismissAt = 0L

/** Video ölçekleme modları (oynatıcıdaki ekran sığdırma düğmesi). */
enum class ScaleMode(val label: String) {
    FIT("En uygun"),
    CROP("Klibi genişlet (kırp)"),
    STRETCH("Ekrana yay"),
    ORIGINAL("Orijinal boyut (%100)")
}

private val SPEED_PRESETS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f)

private fun speedLabel(v: Float): String {
    val r = Math.round(v * 100f) / 100f
    val t = if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString().trimEnd('0').trimEnd('.')
    return "${t}X"
}

@Composable
private fun VideoSurface(
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
        val wider = (cw / ch) > a
        // Taban boyut: "En uygun". Diğer modlar GPU üzerinde ölçek (graphicsLayer) ile
        // animasyonlu uygulanır -> ani atlama/titreme olmaz, yumuşak geçiş yapar.
        val fit: Pair<Dp, Dp> = if (wider) Pair(ch * a, ch) else Pair(cw, cw / a)
        val target: Pair<Dp, Dp> = when (mode) {
            ScaleMode.FIT -> fit
            ScaleMode.CROP -> if (wider) Pair(cw, cw / a) else Pair(ch * a, ch)
            ScaleMode.STRETCH -> Pair(cw, ch)
            ScaleMode.ORIGINAL -> if (videoWpx > 0 && videoHpx > 0) {
                with(density) { Pair(videoWpx.toDp(), videoHpx.toDp()) }
            } else fit
        }
        val fw = fit.first.value.coerceAtLeast(1f)
        val fh = fit.second.value.coerceAtLeast(1f)
        val spec = tween<Float>(durationMillis = 380, easing = FastOutSlowInEasing)
        val sx by animateFloatAsState(target.first.value / fw, spec, label = "scaleX")
        val sy by animateFloatAsState(target.second.value / fh, spec, label = "scaleY")
        AndroidView(
            factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
            modifier = Modifier
                .requiredSize(fit.first, fit.second)
                .graphicsLayer {
                    scaleX = sx
                    scaleY = sy
                }
        )
    }
}

/** Tek satırlık seçenek "çipi" (ölçekleme / hız menülerinde yan yana dizilir). */
@Composable
private fun OptionChip(
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
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

/** Kısa süreli bilgi etiketi (dokununca değişen mod/hız gösterilir, soru sorulmaz). */
@Composable
private fun TransientLabel(text: String?, onDone: () -> Unit) {
    if (text == null) return
    LaunchedEffect(text) {
        delay(1200)
        onDone()
    }
    androidx.compose.ui.window.Popup(
        alignment = Alignment.TopCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, -140)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/**
 * Ölçekleme düğmesi:
 *  - DOKUN  -> soru sormadan sıradaki moda geçer (En uygun -> Kırp -> Yay -> Orijinal -> ...)
 *  - BASILI TUT -> tüm seçenekler düğmenin üstünde TEK SATIRDA açılır.
 */
@Composable
private fun ScaleButton(
    mode: ScaleMode,
    onChange: (ScaleMode) -> Unit,
    onMenuOpen: (Boolean) -> Unit,
    tint: Color
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
                .size(56.dp)
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
            Icon(Icons.Filled.AspectRatio, "Ölçekleme: ${mode.label}", tint = tint, modifier = Modifier.size(24.dp))
        }
        TransientLabel(hint) { hint = null }
        if (open) {
            Popup(
                popupPositionProvider = remember(gap) { AboveCenterPositionProvider(gap) },
                onDismissRequest = close,
                properties = NonFocusPopup
            ) {
                Column(
                    modifier = Modifier
                        .width(menuWidth)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xF2141418))
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

/** Dokununca açılan dikey listedeki hızlar (basılı tutunca ince ayar paneli açılır). */
private val SPEED_LIST = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/**
 * Hız düğmesi:
 *  - DOKUN  -> düğmenin ÜSTÜNDE dikey hız listesi açılır (0.25X ... 2X), seçince uygulanır.
 *  - BASILI TUT -> ince ayar çubuğu + TÜM hazır hızlar, süre çubuğunun üstünde ortada açılır.
 */
@Composable
private fun SpeedButton(
    speed: Float,
    onChange: (Float) -> Unit,
    onMenuOpen: (Boolean) -> Unit,
    tint: Color,
    onOpenPanel: () -> Unit
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
                .height(56.dp)
                .widthIn(min = 56.dp)
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
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(speedLabel(speed), color = tint, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        if (listOpen) {
            Popup(
                popupPositionProvider = remember(gap) { AboveCenterPositionProvider(gap) },
                onDismissRequest = close,
                properties = NonFocusPopup
            ) {
                Column(
                    modifier = Modifier
                        .width(124.dp)
                        .heightIn(max = (screenH * 0.6f).dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xF2161616))
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp)
                ) {
                    SPEED_LIST.forEach { v ->
                        val sel = kotlin.math.abs(v - speed) < 0.001f
                        Text(
                            speedLabel(v),
                            color = if (sel) PurpleLight else Color.White,
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

/** Basılı tutunca açılan ince ayar paneli (süre çubuğunun hemen üstünde, ortada). */
@Composable
private fun SpeedPanelPopup(
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
        Column(
            modifier = Modifier
                .width(panelWidth)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xF2141418))
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
                        thumbColor = Purple, activeTrackColor = Purple, inactiveTrackColor = Color(0xFF3A3A42)
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
            // Tüm hazır hızlar TEK SATIRDA (sığmazsa yatay kaydırılır), aralıkları geniş
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                SPEED_PRESETS.forEach { v ->
                    OptionChip(speedLabel(v), kotlin.math.abs(v - speed) < 0.001f, Color.White, Color(0xFF2A2A31)) {
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
    /** Bağımsız oynatıcı: sağ alttaki düğme uygulamadan ÇIKMAZ, tam ekran <-> pencere geçişi yapar. */
    immersive: Boolean = true,
    onToggleImmersive: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }

    // Ekran yönü SERBEST (cihaz dikey/yatay döndükçe video da döner); çıkınca eski yön geri gelir.
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = previous }
    }

    // Oynarken kontroller birkaç saniye sonra kendiliğinden gizlenir
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
        // Sistem çubukları: menü/panel açılınca ya da kontroller değişince TEKRAR gizlenir.
        // (Eskiden açılan menü odağı çalıp çubukları geri getiriyor, alt kontroller aşağı gömülüyordu.)
        LaunchedEffect(view, immersive, menuOpen, controlsVisible) {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                val controller = WindowInsetsControllerCompat(window, view)
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (immersive) controller.hide(WindowInsetsCompat.Type.systemBars())
                else controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        // EKRAN KAPANMA DÜZELTMESİ: oynatma sürerken ekran kararmaz / kapanmaz.
        DisposableEffect(view, isPlaying) {
            val dialogWindow = (view.parent as? DialogWindowProvider)?.window
            val activityWindow = context.findActivity()?.window
            val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            if (isPlaying) {
                view.keepScreenOn = true
                dialogWindow?.addFlags(flag)
                activityWindow?.addFlags(flag)
            } else {
                view.keepScreenOn = false
                dialogWindow?.clearFlags(flag)
                activityWindow?.clearFlags(flag)
            }
            onDispose {
                view.keepScreenOn = false
                dialogWindow?.clearFlags(flag)
                activityWindow?.clearFlags(flag)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .then(if (immersive) Modifier else Modifier.windowInsetsPadding(WindowInsets.systemBars))
        ) {
            VideoSurface(player, aspect, videoWpx, videoHpx, scaleMode, Modifier.fillMaxSize())

            // Dokunma katmanı: ekrana tıklayınca kontroller gizlenir / görünür
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
                Modifier.align(Alignment.BottomCenter).padding(bottom = if (controlsVisible) 190.dp else 24.dp)
            )

            if (overlayText != null) {
                Text(
                    overlayText,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
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
                        .background(Color.Black.copy(alpha = 0.55f))
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
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
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TrackMenuButtons(player, tracks, Color.White) { menuOpen = it }
                                IconButton(onClick = {
                                    interaction++
                                    val act = context.findActivity()
                                    if (act != null) {
                                        val landscape = act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                                        act.requestedOrientation = if (landscape)
                                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                    }
                                }, modifier = Modifier.size(38.dp)) {
                                    Icon(Icons.Filled.ScreenRotation, "Döndür", tint = Color.White, modifier = Modifier.size(24.dp))
                                }
                                IconButton(onClick = {
                                    interaction++
                                    if (onToggleImmersive != null) onToggleImmersive() else onExit()
                                }, modifier = Modifier.size(44.dp)) {
                                    val exitIcon = onToggleImmersive == null || immersive
                                    Icon(
                                        if (exitIcon) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                        if (exitIcon) "Tam ekrandan çık" else "Tam ekran",
                                        tint = Color.White, modifier = Modifier.size(26.dp)
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

/**
 * Bağımsız video oynatıcı: dosya yöneticisi / Google Drive "Birlikte aç" veya bir
 * http(s) bağlantısı ile gelen videoyu doğrudan (indirmeden, akış olarak) oynatır.
 */
@Composable
fun StreamPlayerScreen(uri: Uri, onExit: () -> Unit) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var videoWpx by remember { mutableIntStateOf(0) }
    var videoHpx by remember { mutableIntStateOf(0) }
    var scaleMode by remember { mutableStateOf(ScaleMode.FIT) }
    var speed by remember { mutableFloatStateOf(1f) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var cueText by remember { mutableStateOf("") }
    var buffering by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var immersive by remember { mutableStateOf(true) }

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
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) errorText = null
            }
            override fun onPlayerError(error: PlaybackException) {
                errorText = "Oynatılamadı: " + (error.message ?: error.errorCodeName)
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

    LaunchedEffect(uri) {
        errorText = null
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
    }
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }

    LaunchedEffect(player) {
        while (true) {
            val state = player.playbackState
            isPlaying = player.playWhenReady && state != Player.STATE_ENDED && state != Player.STATE_IDLE
            positionMs = player.currentPosition.coerceAtLeast(0L)
            val d = player.duration
            durationMs = if (d != C.TIME_UNSET && d > 0) d else 0L
            delay(100)
        }
    }

    val togglePlay: () -> Unit = {
        if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }
    val seekTo: (Long) -> Unit = { t ->
        val target = if (durationMs > 0) t.coerceIn(0L, durationMs) else t.coerceAtLeast(0L)
        player.seekTo(target)
        positionMs = target
    }

    Box(Modifier.fillMaxSize().background(Color.Black))

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
        startMs = 0L,
        endMs = durationMs,
        onTogglePlay = togglePlay,
        onSeekBy = { delta -> seekTo(player.currentPosition + delta) },
        onSeekTo = seekTo,
        onExit = onExit,
        overlayText = errorText ?: if (buffering) "Yükleniyor…" else null,
        immersive = immersive,
        onToggleImmersive = { immersive = !immersive }
    )
}
