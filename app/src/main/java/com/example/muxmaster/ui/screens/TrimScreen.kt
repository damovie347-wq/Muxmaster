package com.example.muxmaster.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
                    Text(
                        "${viewModel.queueIndex + 1}/${viewModel.folderQueue.size}",
                        color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(8.dp))
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

/**
 * Kırpma önizleme oynatıcısı.
 *
 * - Oynatma HER ZAMAN seçili aralıkta ([startMs]..[endMs]) yapılır: oynat'a basınca
 *   başlangıç noktasından başlar, bitiş noktasında durur.
 * - Başlangıç değişince o kareye, bitiş değişince bitişten ~1.5 sn öncesine atlar.
 * - Sağdaki tam ekran düğmesi videoyu kırpma başlangıcından itibaren tam ekran oynatır.
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
    var fullscreen by remember { mutableStateOf(false) }

    val startState = rememberUpdatedState(startMs)
    val endState = rememberUpdatedState(endMs)
    val initialEnd = remember { endMs }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height
                }
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

    // Dosya değişince yükle
    LaunchedEffect(video.cachePath) {
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(video.cachePath))))
        player.prepare()
        player.seekTo(startState.value)
        player.playWhenReady = false
    }

    // Başlangıç noktası değişince o noktaya atla (kısa gecikmeyle: sürüklerken her piksele seek yapma)
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
    // Kırpma başlayınca oynatmayı durdur
    LaunchedEffect(isTrimming) {
        if (isTrimming) player.pause()
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
                AndroidView(
                    factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
                    modifier = Modifier.aspectRatio(aspect)
                )
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
            modifier = Modifier.padding(horizontal = 12.dp),
            trailing = {
                if (!video.isAudioOnly) {
                    IconButton(onClick = {
                        player.seekTo(startState.value)
                        positionMs = startState.value
                        player.play()
                        fullscreen = true
                    }) {
                        Icon(Icons.Filled.Fullscreen, "Tam ekran", tint = Blue, modifier = Modifier.size(28.dp))
                    }
                }
            }
        )
    }

    if (fullscreen) {
        FullscreenPlayerDialog(
            player = player,
            aspect = aspect,
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
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {}
) {
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
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                formatClock(positionMs.coerceIn(startMs, endMs)),
                color = TextSec, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterStart)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.align(Alignment.Center)
            ) {
                IconButton(onClick = { onSeekBy(-10_000L) }) {
                    Icon(Icons.Filled.Replay10, "10 sn geri", tint = TextPrimary, modifier = Modifier.size(30.dp))
                }
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Purple)
                        .clickable(onClick = onTogglePlay),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (isPlaying) "Duraklat" else "Oynat",
                        tint = Color.White, modifier = Modifier.size(32.dp)
                    )
                }
                IconButton(onClick = { onSeekBy(10_000L) }) {
                    Icon(Icons.Filled.Forward10, "10 sn ileri", tint = TextPrimary, modifier = Modifier.size(30.dp))
                }
            }
            Box(modifier = Modifier.align(Alignment.CenterEnd)) { trailing() }
        }
    }
}

@Composable
private fun FullscreenPlayerDialog(
    player: ExoPlayer,
    aspect: Float,
    isPlaying: Boolean,
    positionMs: Long,
    startMs: Long,
    endMs: Long,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current

    // Yatay video ise ekranı yatay yap; çıkınca eski haline döndür
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (aspect >= 1f) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity?.requestedOrientation = previous }
    }

    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                val controller = WindowInsetsControllerCompat(window, view)
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
            onDispose { }
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
                modifier = Modifier.align(Alignment.Center).aspectRatio(aspect)
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                PlayerControls(
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    startMs = startMs,
                    endMs = endMs,
                    onTogglePlay = onTogglePlay,
                    onSeekBy = onSeekBy,
                    onSeekTo = onSeekTo,
                    trailing = {
                        IconButton(onClick = onExit) {
                            Icon(Icons.Filled.FullscreenExit, "Tam ekrandan çık", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                    }
                )
            }
        }
    }
}
