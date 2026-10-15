package com.example.muxmaster.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.view.SurfaceView
import android.view.TextureView
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.example.muxmaster.data.DriveStreamResolver
import com.example.muxmaster.data.InputAccess
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val AccentTurquoise = Color(0xFF66C2B4)
private val PanelScrimColor = Color(0xB8080A0F)
private val PillBgColor = Color(0x99141820)
private val PillBorderColor = Color(0x28FFFFFF)

enum class ScaleMode(val label: String) {
    FIT("Sığdır (Oran Koru)"),
    FILL("Doldur (Esnet)"),
    CROP("Kırp (Ekranı Kapla)"),
    RATIO_16_9("16:9"),
    RATIO_4_3("4:3"),
    ORIGINAL("Orijinal")
}

enum class MultiLayoutMode(val label: String) {
    AUTO("Otomatik (2x2)"),
    SIDE_BY_SIDE("Yan Yana"),
    STACKED("Alt Alta")
}

class VideoSlotState(val slotId: Int, initialUri: Uri) {
    var uri by mutableStateOf(initialUri)
    var resolvedStreamUrl by mutableStateOf<String?>(null)
    var isDriveSaf by mutableStateOf(false)
    var scaleMode by mutableStateOf(ScaleMode.FIT)
    var speed by mutableFloatStateOf(1f)
    var isMuted by mutableStateOf(false)
    var isPlaying by mutableStateOf(true)
    var durationMs by mutableLongStateOf(0L)
    var currentPosMs by mutableLongStateOf(0L)
    var trimStartMs by mutableLongStateOf(0L)
    var trimEndMs by mutableLongStateOf(0L)
    var showTrimBar by mutableStateOf(false)
    var zoomScale by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
    var playerRef by mutableStateOf<ExoPlayer?>(null)
}

private data class TrackOption(
    val label: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val isSelected: Boolean
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(UnstableApi::class)
private fun buildFastPlayer(context: Context, totalSlots: Int): ExoPlayer {
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            2_500,
            30_000,
            100,
            300
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    val renderersFactory = DefaultRenderersFactory(context)
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

    // Standard fast extractors: keep full Cues seek table enabled for local/HTTP MKV & MP4 files
    // so seeking never resets to 00:00!
    val extractorsFactory = DefaultExtractorsFactory()
        .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
        .setConstantBitrateSeekingEnabled(true)

    val dataSourceFactory = InputAccess.buildDataSourceFactory(context)
    val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

    return ExoPlayer.Builder(context, renderersFactory)
        .setMediaSourceFactory(mediaSourceFactory)
        .setLoadControl(loadControl)
        .setSeekParameters(SeekParameters.CLOSEST_SYNC)
        .setHandleAudioBecomingNoisy(true)
        .build()
        .apply {
            val handleFocus = totalSlots <= 1
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                handleFocus
            )
        }
}

@OptIn(UnstableApi::class)
@Composable
fun TrimScreen(
    uris: List<Uri>,
    onBack: () -> Unit,
    onAddVideos: () -> Unit,
    onDriveAuthNeeded: ((onSuccess: () -> Unit) -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val slots = remember { mutableStateListOf<VideoSlotState>() }
    var nextSlotId by remember { mutableIntStateOf(1) }

    LaunchedEffect(uris) {
        if (uris.isEmpty()) return@LaunchedEffect
        if (slots.isEmpty()) {
            uris.take(4).forEach { u ->
                slots.add(VideoSlotState(nextSlotId++, u))
            }
        } else {
            val existing = slots.map { it.uri }.toSet()
            uris.forEach { u ->
                if (slots.size < 4 && u !in existing) {
                    slots.add(VideoSlotState(nextSlotId++, u))
                }
            }
        }
    }

    var focusedSlotId by remember { mutableStateOf<Int?>(null) }
    var layoutMode by remember { mutableStateOf(MultiLayoutMode.AUTO) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var isLockedLandscape by remember { mutableStateOf(false) }

    fun registerInteraction() {
        controlsVisible = true
        interactionTick++
    }

    // Immersive full-screen mode & orientation control
    DisposableEffect(activity) {
        val window = activity?.window
        val insetsController = if (window != null) {
            WindowCompat.getInsetsController(window, window.decorView)
        } else null

        insetsController?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    fun toggleOrientation() {
        val act = activity ?: return
        isLockedLandscape = !isLockedLandscape
        act.requestedOrientation = if (isLockedLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(controlsVisible, interactionTick, slots.size) {
        if (controlsVisible) {
            val anyTrimBarOpen = slots.any { it.showTrimBar }
            if (!anyTrimBarOpen) {
                delay(4200)
                controlsVisible = false
            }
        }
    }

    BackHandler {
        if (focusedSlotId != null) {
            focusedSlotId = null
        } else {
            onBack()
        }
    }

    val isMultiMode = slots.size > 1
    val focusedSlot = slots.firstOrNull { it.slotId == focusedSlotId }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (slots.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentTurquoise)
            }
        } else {
            val config = LocalConfiguration.current
            val isLandscape = config.screenWidthDp >= config.screenHeightDp

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val maxW = maxWidth
                val maxH = maxHeight
                val count = slots.size
                // Bottom bar inset for bottom-row cells in multi-video mode so global bottom bar never overlaps mini-controls
                val globalBarHeight = 48.dp

                slots.forEachIndexed { index, slot ->
                    key(slot.slotId) {
                        val isHidden = focusedSlot != null && focusedSlot.slotId != slot.slotId
                        val cellWidth: Dp
                        val cellHeight: Dp
                        val offsetX: Dp
                        val offsetY: Dp
                        val isBottomRowCell: Boolean

                        if (focusedSlot != null) {
                            if (focusedSlot.slotId == slot.slotId) {
                                cellWidth = maxW
                                cellHeight = maxH
                                offsetX = 0.dp
                                offsetY = 0.dp
                            } else {
                                cellWidth = 1.dp
                                cellHeight = 1.dp
                                offsetX = (-10000).dp
                                offsetY = (-10000).dp
                            }
                            isBottomRowCell = false
                        } else if (count == 1) {
                            cellWidth = maxW
                            cellHeight = maxH
                            offsetX = 0.dp
                            offsetY = 0.dp
                            isBottomRowCell = false
                        } else {
                            when (layoutMode) {
                                MultiLayoutMode.SIDE_BY_SIDE -> {
                                    cellWidth = maxW / count
                                    cellHeight = maxH
                                    offsetX = cellWidth * index
                                    offsetY = 0.dp
                                    isBottomRowCell = true
                                }
                                MultiLayoutMode.STACKED -> {
                                    cellWidth = maxW
                                    cellHeight = maxH / count
                                    offsetX = 0.dp
                                    offsetY = cellHeight * index
                                    isBottomRowCell = (index == count - 1)
                                }
                                MultiLayoutMode.AUTO -> {
                                    when (count) {
                                        2 -> {
                                            if (isLandscape) {
                                                cellWidth = maxW / 2
                                                cellHeight = maxH
                                                offsetX = cellWidth * index
                                                offsetY = 0.dp
                                                isBottomRowCell = true
                                            } else {
                                                cellWidth = maxW
                                                cellHeight = maxH / 2
                                                offsetX = 0.dp
                                                offsetY = cellHeight * index
                                                isBottomRowCell = (index == 1)
                                            }
                                        }
                                        3 -> {
                                            val rowH = maxH / 2
                                            if (index < 2) {
                                                cellWidth = maxW / 2
                                                cellHeight = rowH
                                                offsetX = cellWidth * index
                                                offsetY = 0.dp
                                                isBottomRowCell = false
                                            } else {
                                                cellWidth = maxW
                                                cellHeight = rowH
                                                offsetX = 0.dp
                                                offsetY = rowH
                                                isBottomRowCell = true
                                            }
                                        }
                                        else -> {
                                            val colW = maxW / 2
                                            val rowH = maxH / 2
                                            val col = index % 2
                                            val row = index / 2
                                            cellWidth = colW
                                            cellHeight = rowH
                                            offsetX = colW * col
                                            offsetY = rowH * row
                                            isBottomRowCell = (row == 1)
                                        }
                                    }
                                }
                            }
                        }

                        val density = LocalDensity.current
                        val offsetXPx = with(density) { offsetX.roundToPx() }
                        val offsetYPx = with(density) { offsetY.roundToPx() }

                        Box(
                            modifier = Modifier
                                .offset { IntOffset(offsetXPx, offsetYPx) }
                                .size(cellWidth, cellHeight)
                                .border(
                                    width = if (count > 1 && focusedSlot == null) 0.5.dp else 0.dp,
                                    color = if (count > 1 && focusedSlot == null) Color(0x33FFFFFF) else Color.Transparent
                                )
                                .clipToBounds()
                        ) {
                            VideoCellPlayer(
                                slot = slot,
                                totalSlots = slots.size,
                                isSingleFullView = (count == 1 || focusedSlot?.slotId == slot.slotId),
                                isHidden = isHidden,
                                controlsVisible = controlsVisible,
                                bottomBarInset = if (isMultiMode && focusedSlot == null && isBottomRowCell) globalBarHeight else 0.dp,
                                onToggleControls = {
                                    controlsVisible = !controlsVisible
                                    if (controlsVisible) interactionTick++
                                },
                                onUserInteract = { registerInteraction() },
                                onAddVideos = onAddVideos,
                                onToggleOrientation = { toggleOrientation() },
                                onToggleFocus = {
                                    registerInteraction()
                                    focusedSlotId = if (focusedSlotId == slot.slotId) null else slot.slotId
                                },
                                onRemoveSlot = {
                                    registerInteraction()
                                    if (slots.size > 1) {
                                        if (focusedSlotId == slot.slotId) focusedSlotId = null
                                        slots.remove(slot)
                                    } else {
                                        onBack()
                                    }
                                },
                                onDriveAuthNeeded = onDriveAuthNeeded
                            )
                        }
                    }
                }
            }
        }

        // Top-Left Back Button (Minimal, clean, always easy to see when controlsVisible)
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Box(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(start = 12.dp, top = 8.dp)
            ) {
                IconButton(
                    onClick = {
                        if (focusedSlot != null) {
                            focusedSlotId = null
                        } else {
                            onBack()
                        }
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .background(Color(0x88000000), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Geri",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Bottom Global Master Bar in Multi-Video Mode (Centered at Bottom for easy thumb reach!)
        AnimatedVisibility(
            visible = controlsVisible && isMultiMode && focusedSlot == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val allPlaying = slots.isNotEmpty() && slots.all { it.isPlaying }
            val allMuted = slots.isNotEmpty() && slots.all { it.isMuted }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background( Color(0xD9080A0F) )
                    .navigationBarsPadding()
                    .height(48.dp)
                    .padding(horizontal = 12.dp)
            ) {
                // Left side: + Ekle & Layout switcher
                Row(
                    modifier = Modifier.align(Alignment.CenterStart),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (slots.size < 4) {
                        OverlayPillButton(
                            icon = Icons.Default.Add,
                            text = "Ekle",
                            onClick = {
                                registerInteraction()
                                onAddVideos()
                            }
                        )
                    }
                    var showLayoutMenu by remember { mutableStateOf(false) }
                    Box {
                        OverlayPillButton(
                            icon = Icons.Default.GridView,
                            text = when (layoutMode) {
                                MultiLayoutMode.AUTO -> "2x2"
                                MultiLayoutMode.SIDE_BY_SIDE -> "Yan"
                                MultiLayoutMode.STACKED -> "Alt"
                            },
                            onClick = {
                                registerInteraction()
                                showLayoutMenu = true
                            }
                        )
                        DropdownMenu(
                            expanded = showLayoutMenu,
                            onDismissRequest = { showLayoutMenu = false }
                        ) {
                            MultiLayoutMode.values().forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    onClick = {
                                        registerInteraction()
                                        layoutMode = mode
                                        showLayoutMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Center: Global -10s, Global Play/Pause, Global +10s
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    IconButton(
                        onClick = {
                            registerInteraction()
                            slots.forEach { s ->
                                val p = s.playerRef ?: return@forEach
                                val floor = if (s.showTrimBar) s.trimStartMs else 0L
                                val target = max(floor, p.currentPosition - 10_000L)
                                p.seekTo(target)
                                s.currentPosMs = target
                            }
                        },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay10,
                            contentDescription = "Tümünü 10s Geri Sar",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            registerInteraction()
                            val targetPlay = !allPlaying
                            slots.forEach { s ->
                                s.isPlaying = targetPlay
                                val p = s.playerRef
                                if (p != null) {
                                    if (targetPlay) {
                                        if (p.playbackState == Player.STATE_ENDED) {
                                            p.seekTo(if (s.showTrimBar) s.trimStartMs else 0L)
                                        }
                                        p.play()
                                    } else {
                                        p.pause()
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color(0x2666C2B4), CircleShape)
                    ) {
                        Icon(
                            imageVector = if (allPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (allPlaying) "Tümünü Duraklat" else "Tümünü Oynat",
                            tint = AccentTurquoise,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            registerInteraction()
                            slots.forEach { s ->
                                val p = s.playerRef ?: return@forEach
                                val dur = if (s.durationMs > 0L) s.durationMs else p.duration.coerceAtLeast(0L)
                                val ceil = if (s.showTrimBar && s.trimEndMs > 0L) s.trimEndMs else dur
                                val target = if (ceil > 0L) min(ceil, p.currentPosition + 10_000L) else p.currentPosition + 10_000L
                                p.seekTo(target)
                                s.currentPosMs = target
                            }
                        },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forward10,
                            contentDescription = "Tümünü 10s İleri Sar",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Right side: Global Mute All + Screen Rotation
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = {
                            registerInteraction()
                            val targetMute = !allMuted
                            slots.forEach { s ->
                                s.isMuted = targetMute
                                s.playerRef?.volume = if (targetMute) 0f else 1f
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (allMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = "Tüm Sesler",
                            tint = if (allMuted) Color(0xFFFF6B6B) else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            registerInteraction()
                            toggleOrientation()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ScreenRotation,
                            contentDescription = "Ekranı Döndür",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayPillButton(
    icon: ImageVector?,
    text: String,
    onClick: () -> Unit,
    tint: Color = Color.White
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = PillBgColor,
        border = BorderStroke(0.5.dp, PillBorderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = text,
                    tint = tint,
                    modifier = Modifier.size(15.dp)
                )
            }
            Text(
                text = text,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun VideoCellPlayer(
    slot: VideoSlotState,
    totalSlots: Int,
    isSingleFullView: Boolean,
    isHidden: Boolean,
    controlsVisible: Boolean,
    bottomBarInset: Dp,
    onToggleControls: () -> Unit,
    onUserInteract: () -> Unit,
    onAddVideos: () -> Unit,
    onToggleOrientation: () -> Unit,
    onToggleFocus: () -> Unit,
    onRemoveSlot: () -> Unit,
    onDriveAuthNeeded: ((onSuccess: () -> Unit) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val player = remember(slot.slotId) {
        buildFastPlayer(context, totalSlots)
    }

    LaunchedEffect(player) {
        slot.playerRef = player
    }

    var videoWidth by remember { mutableIntStateOf(16) }
    var videoHeight by remember { mutableIntStateOf(9) }
    var isHdrVideo by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var hasRenderedFirstFrame by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var audioTracks by remember { mutableStateOf<List<TrackOption>>(emptyList()) }
    var subtitleTracks by remember { mutableStateOf<List<TrackOption>>(emptyList()) }
    var subtitlesEnabled by remember { mutableStateOf(true) }
    var subtitleViewRef by remember { mutableStateOf<SubtitleView?>(null) }

    // Interactive gesture feedback overlays
    var gestureFeedbackText by remember { mutableStateOf<String?>(null) }
    var gestureFeedbackTick by remember { mutableIntStateOf(0) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosMs by remember { mutableLongStateOf(0L) }
    var lastSeekDispatchMs by remember { mutableLongStateOf(0L) }

    // Invisible WebView fallback state for uncached/large Google Drive files
    var needInvisibleDriveWebView by remember { mutableStateOf(false) }
    var driveFallbackFileId by remember { mutableStateOf<String?>(null) }
    var driveFallbackFileName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(gestureFeedbackTick) {
        if (gestureFeedbackText != null) {
            delay(850)
            gestureFeedbackText = null
        }
    }

    fun showFeedback(msg: String) {
        gestureFeedbackText = msg
        gestureFeedbackTick++
    }

    fun refreshTracks(tracks: Tracks) {
        val aList = mutableListOf<TrackOption>()
        val sList = mutableListOf<TrackOption>()

        tracks.groups.forEachIndexed { groupIdx, group ->
            val type = group.type
            if (type == C.TRACK_TYPE_AUDIO || type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val lang = format.language?.takeIf { it.isNotBlank() && it != "und" } ?: ""
                    val label = format.label?.takeIf { it.isNotBlank() } ?: ""
                    val mime = format.sampleMimeType?.substringAfter('/')?.uppercase() ?: ""
                    val ch = if (type == C.TRACK_TYPE_AUDIO && format.channelCount > 0) "${format.channelCount}ch" else ""
                    val parts = listOf(label, lang.uppercase(), mime, ch).filter { it.isNotBlank() }
                    val display = if (parts.isNotEmpty()) parts.joinToString(" • ") else "Parça ${i + 1}"
                    val option = TrackOption(
                        label = display,
                        groupIndex = groupIdx,
                        trackIndex = i,
                        isSelected = group.isTrackSelected(i)
                    )
                    if (type == C.TRACK_TYPE_AUDIO) aList.add(option) else sList.add(option)
                }
            }
        }
        audioTracks = aList
        subtitleTracks = sList
    }

    // Load MediaItem: supports local files, HTTP streams, and Google Drive files
    LaunchedEffect(slot.uri, slot.resolvedStreamUrl) {
        errorMessage = null
        isBuffering = true
        val activeUrl = slot.resolvedStreamUrl
        if (activeUrl != null) {
            val item = MediaItem.fromUri(Uri.parse(activeUrl))
            player.setMediaItem(item)
            player.prepare()
            player.playWhenReady = slot.isPlaying
        } else {
            val isDrive = DriveStreamResolver.isGoogleDriveSafUri(slot.uri)
            slot.isDriveSaf = isDrive
            if (isDrive) {
                // Start immediate ContentDataSource playback using streaming extractor flags
                // (avoids EOF seek hang on content:// streams while keeping local MKV seeking intact)
                val streamExtractors = DefaultExtractorsFactory()
                    .setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
                    .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
                    .setConstantBitrateSeekingEnabled(true)
                    .setConstantBitrateSeekingAlwaysEnabled(true)
                val streamSourceFactory = DefaultMediaSourceFactory(
                    InputAccess.buildDataSourceFactory(context),
                    streamExtractors
                )
                val mediaSource = streamSourceFactory.createMediaSource(MediaItem.fromUri(slot.uri))
                player.setMediaSource(mediaSource)
                player.prepare()
                player.playWhenReady = slot.isPlaying

                // Parallel background resolution to direct HTTP 206 stream if Drive SAF blocks or needs random access
                val meta = withContext(Dispatchers.IO) {
                    InputAccess.getUriMetadata(context, slot.uri)
                }
                driveFallbackFileName = meta.displayName
                var fileId = withContext(Dispatchers.IO) {
                    DriveStreamResolver.tryExtractFileIdFromSafUri(context, slot.uri)
                }
                if (fileId == null && DriveStreamResolver.hasDriveSessionCookies()) {
                    fileId = withContext(Dispatchers.IO) {
                        DriveStreamResolver.searchDriveFileIdByName(meta.displayName, meta.sizeBytes)
                    }
                }
                driveFallbackFileId = fileId
                if (fileId != null) {
                    val directUrl = withContext(Dispatchers.IO) {
                        DriveStreamResolver.resolveDirectVideoUrl(fileId)
                    }
                    if (!directUrl.isNullOrBlank()) {
                        slot.resolvedStreamUrl = directUrl
                        return@LaunchedEffect
                    }
                }
                // Wait 2.2 seconds: if local Drive SAF hasn't rendered a frame yet, launch invisible WebView resolver
                delay(2200)
                if (!hasRenderedFirstFrame && slot.resolvedStreamUrl == null) {
                    needInvisibleDriveWebView = true
                }
            } else {
                val item = MediaItem.fromUri(slot.uri)
                player.setMediaItem(item)
                player.prepare()
                player.playWhenReady = slot.isPlaying
            }
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoWidth = videoSize.width
                    videoHeight = videoSize.height
                }
            }

            override fun onRenderedFirstFrame() {
                hasRenderedFirstFrame = true
                isBuffering = false
                errorMessage = null
            }

            override fun onTracksChanged(tracks: Tracks) {
                refreshTracks(tracks)
                var hdrDetected = false
                for (group in tracks.groups) {
                    if (group.type == C.TRACK_TYPE_VIDEO && group.isSelected) {
                        for (i in 0 until group.length) {
                            if (group.isTrackSelected(i)) {
                                val colorTransfer = group.getTrackFormat(i).colorInfo?.colorTransfer
                                if (colorTransfer == C.COLOR_TRANSFER_ST2084 || colorTransfer == C.COLOR_TRANSFER_HLG) {
                                    hdrDetected = true
                                }
                            }
                        }
                    }
                }
                isHdrVideo = hdrDetected
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    val dur = player.duration
                    if (dur > 0 && dur != C.TIME_UNSET) {
                        slot.durationMs = dur
                        if (slot.trimEndMs == 0L || slot.trimEndMs > dur) {
                            slot.trimEndMs = dur
                        }
                    }
                } else if (playbackState == Player.STATE_ENDED) {
                    val startTarget = if (slot.showTrimBar) slot.trimStartMs else 0L
                    player.seekTo(startTarget)
                    if (slot.showTrimBar) {
                        player.play()
                    } else {
                        slot.isPlaying = false
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                if (player.playbackState != Player.STATE_BUFFERING) {
                    slot.isPlaying = playing
                }
            }

            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                subtitleViewRef?.setCues(cueGroup.cues)
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                if (slot.isDriveSaf && slot.resolvedStreamUrl == null && !needInvisibleDriveWebView) {
                    needInvisibleDriveWebView = true
                } else {
                    errorMessage = error.localizedMessage ?: "Video oynatılamadı"
                }
            }
        }

        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            slot.playerRef = null
            player.release()
        }
    }

    // Position ticker & trim loop check (pauses updates while user is actively scrubbing!)
    LaunchedEffect(player, isScrubbing) {
        while (isActive) {
            if (!isScrubbing) {
                val pos = player.currentPosition.coerceAtLeast(0L)
                slot.currentPosMs = pos
                val dur = player.duration
                if (dur > 0 && dur != C.TIME_UNSET && slot.durationMs != dur) {
                    slot.durationMs = dur
                    if (slot.trimEndMs == 0L) slot.trimEndMs = dur
                }
                if (slot.showTrimBar && slot.trimEndMs > slot.trimStartMs + 300L) {
                    if (pos >= slot.trimEndMs) {
                        player.seekTo(slot.trimStartMs)
                        slot.currentPosMs = slot.trimStartMs
                    }
                }
            }
            delay(150)
        }
    }

    val currentToggleControls by rememberUpdatedState(onToggleControls)
    val currentUserInteract by rememberUpdatedState(onUserInteract)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
    ) {
        if (!isHidden) {
            VideoSurface(
                player = player,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                scaleMode = slot.scaleMode,
                isHdrVideo = isHdrVideo,
                zoomScale = slot.zoomScale,
                panX = slot.panX,
                panY = slot.panY,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(slot.slotId) {
                        detectTapGestures(
                            onTap = { currentToggleControls() },
                            onDoubleTap = { offset ->
                                currentUserInteract()
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                when {
                                    offset.x < w * 0.35f -> {
                                        val floor = if (slot.showTrimBar) slot.trimStartMs else 0L
                                        val target = max(floor, player.currentPosition - 10_000L)
                                        player.seekTo(target)
                                        slot.currentPosMs = target
                                        showFeedback("⏪ -10 sn")
                                    }
                                    offset.x > w * 0.65f -> {
                                        val dur = if (slot.durationMs > 0L) slot.durationMs else player.duration.coerceAtLeast(0L)
                                        val ceil = if (slot.showTrimBar && slot.trimEndMs > 0L) slot.trimEndMs else dur
                                        val target = if (ceil > 0L) min(ceil, player.currentPosition + 10_000L) else player.currentPosition + 10_000L
                                        player.seekTo(target)
                                        slot.currentPosMs = target
                                        showFeedback("⏩ +10 sn")
                                    }
                                    else -> {
                                        if (slot.zoomScale > 1.05f) {
                                            slot.zoomScale = 1f
                                            slot.panX = 0f
                                            slot.panY = 0f
                                            showFeedback("1.0x Görünüm")
                                        } else {
                                            val nextPlay = !slot.isPlaying
                                            slot.isPlaying = nextPlay
                                            if (nextPlay) {
                                                if (player.playbackState == Player.STATE_ENDED) {
                                                    player.seekTo(if (slot.showTrimBar) slot.trimStartMs else 0L)
                                                }
                                                player.play()
                                                showFeedback("▶ Oynat")
                                            } else {
                                                player.pause()
                                                showFeedback("⏸ Duraklat")
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                    .pointerInput(slot.slotId, isSingleFullView) {
                        if (isSingleFullView) {
                            var startPosMs = 0L
                            var accumDx = 0f
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    startPosMs = player.currentPosition.coerceAtLeast(0L)
                                    accumDx = 0f
                                    isScrubbing = true
                                    scrubPosMs = startPosMs
                                },
                                onDragEnd = {
                                    player.seekTo(scrubPosMs)
                                    slot.currentPosMs = scrubPosMs
                                    isScrubbing = false
                                },
                                onDragCancel = {
                                    isScrubbing = false
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    if (slot.zoomScale <= 1.05f) {
                                        change.consume()
                                        accumDx += dragAmount
                                        val dur = slot.durationMs.coerceAtLeast(60_000L)
                                        val sensitivityMs = min(dur, 180_000L).toFloat()
                                        val deltaMs = ((accumDx / size.width.coerceAtLeast(1)) * sensitivityMs).toLong()
                                        val floor = if (slot.showTrimBar) slot.trimStartMs else 0L
                                        val ceil = if (slot.showTrimBar && slot.trimEndMs > 0L) slot.trimEndMs else slot.durationMs.coerceAtLeast(1L)
                                        val target = (startPosMs + deltaMs).coerceIn(floor, ceil)
                                        scrubPosMs = target
                                        slot.currentPosMs = target
                                        val now = System.currentTimeMillis()
                                        if (now - lastSeekDispatchMs >= 70L) {
                                            lastSeekDispatchMs = now
                                            player.seekTo(target)
                                        }
                                        val sign = if (deltaMs >= 0) "+" else "-"
                                        showFeedback("${formatShortTime(target)} ($sign${abs(deltaMs / 1000)}s)")
                                    }
                                }
                            )
                        }
                    }
                    .pointerInput(slot.slotId) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            if (zoom != 1f || slot.zoomScale > 1.01f) {
                                val newZoom = (slot.zoomScale * zoom).coerceIn(1f, 4f)
                                slot.zoomScale = newZoom
                                if (newZoom > 1.01f) {
                                    slot.panX += pan.x
                                    slot.panY += pan.y
                                } else {
                                    slot.panX = 0f
                                    slot.panY = 0f
                                }
                            }
                        }
                    }
            )

            if (subtitlesEnabled) {
                AndroidView(
                    factory = { ctx ->
                        SubtitleView(ctx).apply {
                            setStyle(
                                CaptionStyleCompat(
                                    AndroidColor.WHITE,
                                    AndroidColor.argb(140, 0, 0, 0),
                                    AndroidColor.TRANSPARENT,
                                    CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                                    AndroidColor.BLACK,
                                    null
                                )
                            )
                            setFractionalTextSize(if (isSingleFullView) 0.045f else 0.055f)
                            subtitleViewRef = this
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = if (controlsVisible) (if (isSingleFullView) 84.dp else 64.dp + bottomBarInset) else 12.dp)
                )
            }
        }

        // Invisible background WebView stream resolver for uncached/large Google Drive videos
        if (needInvisibleDriveWebView && slot.resolvedStreamUrl == null) {
            InvisibleDriveStreamResolver(
                fileId = driveFallbackFileId,
                fileName = driveFallbackFileName,
                onStreamUrlFound = { resolvedUrl ->
                    needInvisibleDriveWebView = false
                    slot.resolvedStreamUrl = resolvedUrl
                },
                onAuthRequired = {
                    needInvisibleDriveWebView = false
                    onDriveAuthNeeded?.invoke {
                        needInvisibleDriveWebView = true
                    }
                }
            )
        }

        // Loading indicator (only while waiting for initial frame or buffering)
        if (isBuffering && errorMessage == null) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .align(Alignment.Center)
                    .background(Color(0x77000000), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = AccentTurquoise,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        // Gesture Feedback Chip
        AnimatedVisibility(
            visible = gestureFeedbackText != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xCC0D1017),
                border = BorderStroke(0.5.dp, Color(0x33FFFFFF))
            ) {
                Text(
                    text = gestureFeedbackText ?: "",
                    color = Color.White,
                    fontSize = if (isSingleFullView) 15.sp else 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        // Error Recovery Banner
        if (errorMessage != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xDD141820), RoundedCornerShape(12.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = errorMessage ?: "Video yüklenemedi",
                    color = Color(0xFFFF8A80),
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            errorMessage = null
                            isBuffering = true
                            if (slot.isDriveSaf) {
                                needInvisibleDriveWebView = true
                            } else {
                                player.prepare()
                                player.play()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentTurquoise),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Tekrar Dene", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (slot.isDriveSaf && onDriveAuthNeeded != null) {
                        TextButton(
                            onClick = {
                                onDriveAuthNeeded.invoke {
                                    errorMessage = null
                                    isBuffering = true
                                    needInvisibleDriveWebView = true
                                }
                            }
                        ) {
                            Text("Drive Oturumu Aç", color = AccentTurquoise, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Top-Right Mini Slot Controls (Fullscreen Focus & Remove Slot in Multi-Video Mode)
        AnimatedVisibility(
            visible = controlsVisible && totalSlots > 1,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Row(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(6.dp)
                    .background(Color(0x88000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = onToggleFocus,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (isSingleFullView) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = "Büyüt / Küçült",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(
                    onClick = onRemoveSlot,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Kaldır",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Bottom Controls Overlay (Compact, Reference-Matched Layout!)
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomBarInset)
        ) {
            val dur = slot.durationMs.coerceAtLeast(1L)
            val displayPos = (if (isScrubbing) scrubPosMs else slot.currentPosMs).coerceIn(0L, dur)

            if (isSingleFullView) {
                // SINGLE VIDEO / FULLSCREEN VIEW CONTROLS (Matches User's Reference Image 2!)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    // Floating right-aligned utility icons above the slider (outside the dark bar background)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .background(Color(0x77000000), RoundedCornerShape(18.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TrackMenuButtons(
                                audioTracks = audioTracks,
                                subtitleTracks = subtitleTracks,
                                subtitlesEnabled = subtitlesEnabled,
                                compact = false,
                                onSelectAudio = { opt ->
                                    onUserInteract()
                                    val group = player.currentTracks.groups.getOrNull(opt.groupIndex)?.mediaTrackGroup
                                    if (group != null) {
                                        player.trackSelectionParameters = player.trackSelectionParameters
                                            .buildUpon()
                                            .setOverrideForType(TrackSelectionOverride(group, opt.trackIndex))
                                            .build()
                                    }
                                },
                                onSelectSubtitle = { opt ->
                                    onUserInteract()
                                    if (opt == null) {
                                        subtitlesEnabled = false
                                        player.trackSelectionParameters = player.trackSelectionParameters
                                            .buildUpon()
                                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                            .build()
                                    } else {
                                        subtitlesEnabled = true
                                        val group = player.currentTracks.groups.getOrNull(opt.groupIndex)?.mediaTrackGroup
                                        if (group != null) {
                                            player.trackSelectionParameters = player.trackSelectionParameters
                                                .buildUpon()
                                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                                .setOverrideForType(TrackSelectionOverride(group, opt.trackIndex))
                                                .build()
                                        }
                                    }
                                }
                            )

                            // Trim Range Toggle Button
                            IconButton(
                                onClick = {
                                    onUserInteract()
                                    slot.showTrimBar = !slot.showTrimBar
                                    if (slot.showTrimBar && slot.trimEndMs <= 0L) {
                                        slot.trimEndMs = dur
                                    }
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCut,
                                    contentDescription = "Aralık Seç",
                                    tint = if (slot.showTrimBar) AccentTurquoise else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Volume Mute/Unmute
                            IconButton(
                                onClick = {
                                    onUserInteract()
                                    slot.isMuted = !slot.isMuted
                                    player.volume = if (slot.isMuted) 0f else 1f
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = if (slot.isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                    contentDescription = "Ses",
                                    tint = if (slot.isMuted) Color(0xFFFF6B6B) else Color.White,
                                    modifier = Modifier.size(19.dp)
                                )
                            }

                            if (totalSlots > 1) {
                                IconButton(
                                    onClick = onToggleFocus,
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FullscreenExit,
                                        contentDescription = "Küçült",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Optional Trim Range Bar (Only shown when user toggles ContentCut)
                    AnimatedVisibility(visible = slot.showTrimBar) {
                        TrimRangeSubBar(
                            slot = slot,
                            durationMs = dur,
                            onSeekTo = { targetMs ->
                                onUserInteract()
                                player.seekTo(targetMs)
                                slot.currentPosMs = targetMs
                            }
                        )
                    }

                    // Compact Dark Bottom Bar: Row 1 = [00:00] -- Slider -- [03:52], Row 2 = [+ Ekle] & Centered Buttons
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(PanelScrimColor)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        // Row 1: Start Time on Left + Turquoise Slider + Total Duration on Right
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(28.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatShortTime(displayPos),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            )
                            Slider(
                                value = displayPos.toFloat(),
                                onValueChange = { newVal ->
                                    onUserInteract()
                                    isScrubbing = true
                                    val target = newVal.toLong().coerceIn(0L, dur)
                                    scrubPosMs = target
                                    slot.currentPosMs = target
                                    val now = System.currentTimeMillis()
                                    if (now - lastSeekDispatchMs >= 60L) {
                                        lastSeekDispatchMs = now
                                        player.seekTo(target)
                                    }
                                },
                                onValueChangeFinished = {
                                    player.seekTo(scrubPosMs)
                                    slot.currentPosMs = scrubPosMs
                                    isScrubbing = false
                                },
                                valueRange = 0f..dur.toFloat().coerceAtLeast(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = AccentTurquoise,
                                    activeTrackColor = AccentTurquoise,
                                    inactiveTrackColor = Color(0x55FFFFFF)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                            )
                            Text(
                                text = formatShortTime(slot.durationMs),
                                color = Color(0xFFE0E0E0),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Row 2: Left (+ Ekle) + Centered Transport & Aspect Ratio / Orientation / Speed
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                        ) {
                            if (totalSlots < 4) {
                                Box(modifier = Modifier.align(Alignment.CenterStart)) {
                                    OverlayPillButton(
                                        icon = Icons.Default.Add,
                                        text = "Ekle",
                                        onClick = {
                                            onUserInteract()
                                            onAddVideos()
                                        }
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.align(Alignment.Center),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(18.dp)
                            ) {
                                ScaleButton(
                                    current = slot.scaleMode,
                                    compact = false,
                                    onSelect = { mode ->
                                        onUserInteract()
                                        slot.scaleMode = mode
                                        slot.zoomScale = 1f
                                        slot.panX = 0f
                                        slot.panY = 0f
                                        showFeedback(mode.label)
                                    }
                                )

                                IconButton(
                                    onClick = {
                                        onUserInteract()
                                        val floor = if (slot.showTrimBar) slot.trimStartMs else 0L
                                        val target = max(floor, player.currentPosition - 10_000L)
                                        player.seekTo(target)
                                        slot.currentPosMs = target
                                    },
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Replay10,
                                        contentDescription = "10 sn Geri",
                                        tint = Color.White,
                                        modifier = Modifier.size(23.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        onUserInteract()
                                        val nextPlay = !slot.isPlaying
                                        slot.isPlaying = nextPlay
                                        if (nextPlay) {
                                            if (player.playbackState == Player.STATE_ENDED) {
                                                player.seekTo(if (slot.showTrimBar) slot.trimStartMs else 0L)
                                            }
                                            player.play()
                                        } else {
                                            player.pause()
                                        }
                                    },
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    Icon(
                                        imageVector = if (slot.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (slot.isPlaying) "Duraklat" else "Oynat",
                                        tint = Color.White,
                                        modifier = Modifier.size(29.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        onUserInteract()
                                        val ceil = if (slot.showTrimBar && slot.trimEndMs > 0L) slot.trimEndMs else dur
                                        val target = min(ceil, player.currentPosition + 10_000L)
                                        player.seekTo(target)
                                        slot.currentPosMs = target
                                    },
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Forward10,
                                        contentDescription = "10 sn İleri",
                                        tint = Color.White,
                                        modifier = Modifier.size(23.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        onUserInteract()
                                        onToggleOrientation()
                                    },
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ScreenRotation,
                                        contentDescription = "Ekranı Döndür",
                                        tint = Color.White,
                                        modifier = Modifier.size(21.dp)
                                    )
                                }

                                SpeedButton(
                                    currentSpeed = slot.speed,
                                    compact = false,
                                    onSelect = { spd ->
                                        onUserInteract()
                                        slot.speed = spd
                                        player.setPlaybackSpeed(spd)
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                // MULTI-VIDEO MINI CELL CONTROLS (Centered buttons, larger touch targets for phone & tablet, time on left & right of slider!)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PanelScrimColor)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    AnimatedVisibility(visible = slot.showTrimBar) {
                        TrimRangeSubBar(
                            slot = slot,
                            durationMs = dur,
                            onSeekTo = { targetMs ->
                                onUserInteract()
                                player.seekTo(targetMs)
                                slot.currentPosMs = targetMs
                            }
                        )
                    }

                    // Row 1: Left Time + Turquoise Slider + Right Total Duration
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatShortTime(displayPos),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                        Slider(
                            value = displayPos.toFloat(),
                            onValueChange = { newVal ->
                                onUserInteract()
                                isScrubbing = true
                                val target = newVal.toLong().coerceIn(0L, dur)
                                scrubPosMs = target
                                slot.currentPosMs = target
                                val now = System.currentTimeMillis()
                                if (now - lastSeekDispatchMs >= 60L) {
                                    lastSeekDispatchMs = now
                                    player.seekTo(target)
                                }
                            },
                            onValueChangeFinished = {
                                player.seekTo(scrubPosMs)
                                slot.currentPosMs = scrubPosMs
                                isScrubbing = false
                            },
                            valueRange = 0f..dur.toFloat().coerceAtLeast(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = AccentTurquoise,
                                activeTrackColor = AccentTurquoise,
                                inactiveTrackColor = Color(0x55FFFFFF)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        )
                        Text(
                            text = formatShortTime(slot.durationMs),
                            color = Color(0xFFDDDDDD),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Row 2: CENTERED & Larger, easy-to-tap mini player controls!
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                    ) {
                        ScaleButton(
                            current = slot.scaleMode,
                            compact = true,
                            onSelect = { mode ->
                                onUserInteract()
                                slot.scaleMode = mode
                                slot.zoomScale = 1f
                                slot.panX = 0f
                                slot.panY = 0f
                                showFeedback(mode.label)
                            }
                        )

                        IconButton(
                            onClick = {
                                onUserInteract()
                                val floor = if (slot.showTrimBar) slot.trimStartMs else 0L
                                val target = max(floor, player.currentPosition - 10_000L)
                                player.seekTo(target)
                                slot.currentPosMs = target
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Replay10,
                                contentDescription = "10 sn Geri",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                onUserInteract()
                                val nextPlay = !slot.isPlaying
                                slot.isPlaying = nextPlay
                                if (nextPlay) {
                                    if (player.playbackState == Player.STATE_ENDED) {
                                        player.seekTo(if (slot.showTrimBar) slot.trimStartMs else 0L)
                                    }
                                    player.play()
                                } else {
                                    player.pause()
                                }
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(0x2266C2B4), CircleShape)
                        ) {
                            Icon(
                                imageVector = if (slot.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (slot.isPlaying) "Duraklat" else "Oynat",
                                tint = AccentTurquoise,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                onUserInteract()
                                val ceil = if (slot.showTrimBar && slot.trimEndMs > 0L) slot.trimEndMs else dur
                                val target = min(ceil, player.currentPosition + 10_000L)
                                player.seekTo(target)
                                slot.currentPosMs = target
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Forward10,
                                contentDescription = "10 sn İleri",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                onUserInteract()
                                slot.isMuted = !slot.isMuted
                                player.volume = if (slot.isMuted) 0f else 1f
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = if (slot.isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                contentDescription = "Ses",
                                tint = if (slot.isMuted) Color(0xFFFF6B6B) else Color.White,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                onUserInteract()
                                slot.showTrimBar = !slot.showTrimBar
                                if (slot.showTrimBar && slot.trimEndMs <= 0L) {
                                    slot.trimEndMs = dur
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCut,
                                contentDescription = "Aralık",
                                tint = if (slot.showTrimBar) AccentTurquoise else Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        SpeedButton(
                            currentSpeed = slot.speed,
                            compact = true,
                            onSelect = { spd ->
                                onUserInteract()
                                slot.speed = spd
                                player.setPlaybackSpeed(spd)
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrimRangeSubBar(
    slot: VideoSlotState,
    durationMs: Long,
    onSeekTo: (Long) -> Unit
) {
    val safeDur = durationMs.coerceAtLeast(1000L)
    val startVal = slot.trimStartMs.coerceIn(0L, safeDur)
    val endVal = (if (slot.trimEndMs <= 0L) safeDur else slot.trimEndMs).coerceIn(startVal, safeDur)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xD910151F))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Aralık: ${formatShortTime(startVal)} - ${formatShortTime(endVal)}",
                color = AccentTurquoise,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Baştan Oynat",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .background(Color(0x3366C2B4), RoundedCornerShape(6.dp))
                        .clickable { onSeekTo(startVal) }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
                Text(
                    text = "Sıfırla",
                    color = Color(0xFFBBBBBB),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable {
                            slot.trimStartMs = 0L
                            slot.trimEndMs = safeDur
                            slot.showTrimBar = false
                        }
                        .padding(horizontal = 4.dp, vertical = 3.dp)
                )
            }
        }

        RangeSlider(
            value = startVal.toFloat()..endVal.toFloat(),
            onValueChange = { range ->
                val newStart = range.start.toLong().coerceIn(0L, safeDur)
                val newEnd = range.endInclusive.toLong().coerceIn(newStart, safeDur)
                val startChanged = abs(newStart - slot.trimStartMs) > abs(newEnd - slot.trimEndMs)
                slot.trimStartMs = newStart
                slot.trimEndMs = newEnd
                if (startChanged) {
                    onSeekTo(newStart)
                }
            },
            valueRange = 0f..safeDur.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = AccentTurquoise,
                activeTrackColor = AccentTurquoise,
                inactiveTrackColor = Color(0x44FFFFFF)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
        )
    }
}

@Composable
private fun VideoSurface(
    player: ExoPlayer,
    videoWidth: Int,
    videoHeight: Int,
    scaleMode: ScaleMode,
    isHdrVideo: Boolean,
    zoomScale: Float,
    panX: Float,
    panY: Float,
    modifier: Modifier = Modifier
) {
    // TextureView strictly obeys Compose's clipToBounds() and graphicsLayer scaling in multi-video grids
    // without hardware overlay bleed or black flashes when changing aspect ratio.
    BoxWithConstraints(
        modifier = modifier.clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        val containerW = maxWidth.value.coerceAtLeast(1f)
        val containerH = maxHeight.value.coerceAtLeast(1f)
        val safeW = videoWidth.coerceAtLeast(1).toFloat()
        val safeH = videoHeight.coerceAtLeast(1).toFloat()
        val videoAspect = safeW / safeH
        val containerAspect = containerW / containerH

        // Base FIT dimensions inside the cell
        val fitW: Float
        val fitH: Float
        if (videoAspect > containerAspect) {
            fitW = containerW
            fitH = containerW / videoAspect
        } else {
            fitH = containerH
            fitW = containerH * videoAspect
        }

        // Target dimensions according to selected ScaleMode
        val targetW: Float
        val targetH: Float
        when (scaleMode) {
            ScaleMode.FIT -> {
                targetW = fitW
                targetH = fitH
            }
            ScaleMode.FILL -> {
                targetW = containerW
                targetH = containerH
            }
            ScaleMode.CROP -> {
                if (videoAspect > containerAspect) {
                    targetH = containerH
                    targetW = containerH * videoAspect
                } else {
                    targetW = containerW
                    targetH = containerW / videoAspect
                }
            }
            ScaleMode.RATIO_16_9 -> {
                val targetRatio = 16f / 9f
                if (containerAspect > targetRatio) {
                    targetH = containerH
                    targetW = containerH * targetRatio
                } else {
                    targetW = containerW
                    targetH = containerW / targetRatio
                }
            }
            ScaleMode.RATIO_4_3 -> {
                val targetRatio = 4f / 3f
                if (containerAspect > targetRatio) {
                    targetH = containerH
                    targetW = containerH * targetRatio
                } else {
                    targetW = containerW
                    targetH = containerW / targetRatio
                }
            }
            ScaleMode.ORIGINAL -> {
                val density = LocalDensity.current.density.coerceAtLeast(1f)
                val rawW = safeW / density
                val rawH = safeH / density
                // Clamp within cell if larger, or show 1:1 pixel size
                val scaleDown = min(1f, min(containerW / rawW.coerceAtLeast(1f), containerH / rawH.coerceAtLeast(1f)))
                targetW = rawW * scaleDown
                targetH = rawH * scaleDown
            }
        }

        val sx = (targetW / fitW.coerceAtLeast(1f)) * zoomScale
        val sy = (targetH / fitH.coerceAtLeast(1f)) * zoomScale

        if (isHdrVideo) {
            // Clamped SurfaceView for HDR10/HLG
            val clampedW = min(targetW * zoomScale, containerW)
            val clampedH = min(targetH * zoomScale, containerH)
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        player.setVideoSurfaceView(this)
                    }
                },
                update = { view ->
                    player.setVideoSurfaceView(view)
                },
                modifier = Modifier
                    .requiredSize(clampedW.dp, clampedH.dp)
                    .graphicsLayer {
                        translationX = panX
                        translationY = panY
                    }
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        isOpaque = false
                        player.setVideoTextureView(this)
                    }
                },
                update = { view ->
                    player.setVideoTextureView(view)
                },
                modifier = Modifier
                    .requiredSize(fitW.dp, fitH.dp)
                    .graphicsLayer {
                        scaleX = sx
                        scaleY = sy
                        translationX = panX
                        translationY = panY
                    }
            )
        }
    }
}

@Composable
private fun ScaleButton(
    current: ScaleMode,
    compact: Boolean,
    onSelect: (ScaleMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(if (compact) 34.dp else 38.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AspectRatio,
                contentDescription = "En Boy Oranı",
                tint = if (current != ScaleMode.FIT) AccentTurquoise else Color.White,
                modifier = Modifier.size(if (compact) 19.dp else 21.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ScaleMode.values().forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (mode == current) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = AccentTurquoise,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Spacer(Modifier.size(16.dp))
                            }
                            Text(
                                text = mode.label,
                                color = if (mode == current) AccentTurquoise else Color.White,
                                fontWeight = if (mode == current) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(mode)
                    }
                )
            }
        }
    }
}

@Composable
private fun SpeedButton(
    currentSpeed: Float,
    compact: Boolean,
    onSelect: (Float) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    val label = if (currentSpeed == 1.0f) "1X" else "${currentSpeed}X"

    Box {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(6.dp),
            color = if (currentSpeed != 1.0f) Color(0x3366C2B4) else Color.Transparent
        ) {
            Text(
                text = label,
                color = if (currentSpeed != 1.0f) AccentTurquoise else Color.White,
                fontSize = if (compact) 12.sp else 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = if (compact) 6.dp else 8.dp, vertical = 4.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            speeds.forEach { spd ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "${spd}X",
                            color = if (spd == currentSpeed) AccentTurquoise else Color.White,
                            fontWeight = if (spd == currentSpeed) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(spd)
                    }
                )
            }
        }
    }
}

@Composable
private fun TrackMenuButtons(
    audioTracks: List<TrackOption>,
    subtitleTracks: List<TrackOption>,
    subtitlesEnabled: Boolean,
    compact: Boolean,
    onSelectAudio: (TrackOption) -> Unit,
    onSelectSubtitle: (TrackOption?) -> Unit
) {
    var showAudioMenu by remember { mutableStateOf(false) }
    var showSubMenu by remember { mutableStateOf(false) }

    if (audioTracks.size > 1) {
        Box {
            IconButton(
                onClick = { showAudioMenu = true },
                modifier = Modifier.size(if (compact) 30.dp else 34.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Audiotrack,
                    contentDescription = "Ses Parçası",
                    tint = Color.White,
                    modifier = Modifier.size(if (compact) 17.dp else 19.dp)
                )
            }
            DropdownMenu(
                expanded = showAudioMenu,
                onDismissRequest = { showAudioMenu = false }
            ) {
                audioTracks.forEach { opt ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = opt.label,
                                color = if (opt.isSelected) AccentTurquoise else Color.White
                            )
                        },
                        onClick = {
                            showAudioMenu = false
                            onSelectAudio(opt)
                        }
                    )
                }
            }
        }
    }

    if (subtitleTracks.isNotEmpty()) {
        Box {
            IconButton(
                onClick = { showSubMenu = true },
                modifier = Modifier.size(if (compact) 30.dp else 34.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Subtitles,
                    contentDescription = "Altyazı",
                    tint = if (subtitlesEnabled) AccentTurquoise else Color.White,
                    modifier = Modifier.size(if (compact) 17.dp else 19.dp)
                )
            }
            DropdownMenu(
                expanded = showSubMenu,
                onDismissRequest = { showSubMenu = false }
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Kapalı",
                            color = if (!subtitlesEnabled) AccentTurquoise else Color.White
                        )
                    },
                    onClick = {
                        showSubMenu = false
                        onSelectSubtitle(null)
                    }
                )
                subtitleTracks.forEach { opt ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = opt.label,
                                color = if (subtitlesEnabled && opt.isSelected) AccentTurquoise else Color.White
                            )
                        },
                        onClick = {
                            showSubMenu = false
                            onSelectSubtitle(opt)
                        }
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InvisibleDriveStreamResolver(
    fileId: String?,
    fileName: String?,
    onStreamUrlFound: (String) -> Unit,
    onAuthRequired: () -> Unit
) {
    val currentOnFound by rememberUpdatedState(onStreamUrlFound)
    val currentOnAuth by rememberUpdatedState(onAuthRequired)

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.userAgentString =
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(this, true)

                var resolved = false

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): android.webkit.WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return null
                        if (!resolved && (
                                reqUrl.contains("videoplayback?") ||
                                    (reqUrl.contains("googleusercontent.com") && reqUrl.contains("export=download")) ||
                                    (reqUrl.contains("drive.usercontent.google.com/download") && !reqUrl.contains("authuser"))
                                )
                        ) {
                            resolved = true
                            post { currentOnFound(reqUrl) }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val u = url ?: return
                        if (u.contains("accounts.google.com/ServiceLogin") || u.contains("accounts.google.com/v3/signin")) {
                            if (!resolved) {
                                resolved = true
                                post { currentOnAuth() }
                            }
                            return
                        }
                        // If we landed on Drive search results, extract the first matching file ID and redirect to direct download
                        if (u.contains("drive.google.com/drive/") && u.contains("search")) {
                            evaluateJavascript(
                                """
                                (function() {
                                    var el = document.querySelector('[data-id]');
                                    return el ? el.getAttribute('data-id') : '';
                                })();
                                """.trimIndent()
                            ) { rawId ->
                                val cleanId = rawId?.trim('"', ' ', '\'') ?: ""
                                if (cleanId.length >= 20 && !resolved) {
                                    loadUrl("https://drive.usercontent.google.com/download?id=$cleanId&export=download&confirm=t")
                                }
                            }
                        } else if (u.contains("drive.usercontent.google.com/download")) {
                            // Click virus scan warning form button if present
                            evaluateJavascript(
                                """
                                (function() {
                                    var form = document.getElementById('download-form');
                                    if (form) { form.submit(); return 'submitted'; }
                                    var btn = document.querySelector('input[type="submit"], button[type="submit"]');
                                    if (btn) { btn.click(); return 'clicked'; }
                                    return '';
                                })();
                                """.trimIndent(),
                                null
                            )
                        }
                    }
                }

                setDownloadListener { url, _, _, _, _ ->
                    if (!resolved && url.startsWith("http")) {
                        resolved = true
                        post { currentOnFound(url) }
                    }
                }

                val targetUrl = when {
                    !fileId.isNullOrBlank() ->
                        "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
                    !fileName.isNullOrBlank() -> {
                        val q = URLEncoder.encode(fileName.substringBeforeLast('.'), "UTF-8")
                        "https://drive.google.com/drive/u/0/search?q=$q"
                    }
                    else -> "https://drive.google.com/drive/my-drive"
                }
                loadUrl(targetUrl)
            }
        },
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
    )
}

private fun formatShortTime(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L)) / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) {
        String.format("%d:%02d:%02d", h, m, s)
    } else {
        String.format("%02d:%02d", m, s)
    }
}
