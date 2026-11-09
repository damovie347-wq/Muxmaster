package com.example.muxmaster.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.muxmaster.model.TimeRange
import kotlin.math.abs

/**
 * Neden özel slider?
 *
 * Material3'ün hazır `Slider` / `RangeSlider` bileşenleri, dikey olarak kaydırılabilen
 * (verticalScroll) bir sütunun içinde, daraltılmış yükseklikte (36-40dp) kullanıldığında
 * bazı cihaz/sürüm kombinasyonlarında sürükleme (drag) hareketini yutuyor; sadece tek
 * dokunuş (tap) çalışıyordu. Bu dosyadaki bileşenler dokunma + sürükleme davranışını
 * TEK bir `pointerInput` bloğunda, `awaitEachGesture` ile baştan sona kendileri yönetir:
 * parmak aşağı indiği an konum uygulanır, parmak kaydıkça her karede güncellenir,
 * parmak kalkınca kesin değer gönderilir. Böylece hangi Compose/Material sürümü olursa
 * olsun "sağa sola kaydırma" garantili çalışır.
 */
@Composable
fun MuxSeekSlider(
    positionMs: Long,
    startMs: Long,
    endMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onScrubChange: (Boolean) -> Unit = {},
    activeColor: Color = Color(0xFF66C2B4),
    inactiveColor: Color = Color(0x55FFFFFF),
    trackHeight: Dp = 4.dp,
    thumbRadius: Dp = 7.dp,
    sliderHeight: Dp = 32.dp
) {
    val span = (endMs - startMs).coerceAtLeast(1L)
    var dragFraction by remember { mutableFloatStateOf(-1f) }

    val onSeekState = rememberUpdatedState(onSeek)
    val onScrubState = rememberUpdatedState(onScrubChange)
    val startState = rememberUpdatedState(startMs)
    val spanState = rememberUpdatedState(span)

    val shownFraction = if (dragFraction >= 0f) {
        dragFraction
    } else {
        ((positionMs - startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(sliderHeight)
            .pointerInput(startMs, endMs) {
                val thumbPx = thumbRadius.toPx()

                fun fractionAt(x: Float): Float {
                    val left = thumbPx
                    val right = size.width - thumbPx
                    if (right <= left) return 0f
                    return ((x - left) / (right - left)).coerceIn(0f, 1f)
                }

                fun msAt(fraction: Float): Long =
                    startState.value + (fraction * spanState.value.toFloat()).toLong()

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()

                    var fraction = fractionAt(down.position.x)
                    dragFraction = fraction
                    onScrubState.value(true)
                    onSeekState.value(msAt(fraction))
                    var lastPreviewAt = System.currentTimeMillis()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        fraction = fractionAt(change.position.x)
                        dragFraction = fraction
                        if (!change.pressed) {
                            // Parmak kalktı: kesin hedefe tek seferde otur.
                            change.consume()
                            onSeekState.value(msAt(fraction))
                            break
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastPreviewAt >= 90L) {
                            lastPreviewAt = now
                            onSeekState.value(msAt(fraction))
                        }
                        change.consume()
                    }
                    dragFraction = -1f
                    onScrubState.value(false)
                }
            }
    ) {
        val centerY = size.height / 2f
        val thumbPx = thumbRadius.toPx()
        val left = thumbPx
        val right = size.width - thumbPx
        val thumbX = left + (right - left) * shownFraction
        val stroke = trackHeight.toPx()

        drawLine(
            color = inactiveColor,
            start = Offset(left, centerY),
            end = Offset(right, centerY),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        if (thumbX > left) {
            drawLine(
                color = activeColor,
                start = Offset(left, centerY),
                end = Offset(thumbX, centerY),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
        drawCircle(color = activeColor.copy(alpha = 0.28f), radius = thumbPx * 1.9f, center = Offset(thumbX, centerY))
        drawCircle(color = Color.White, radius = thumbPx, center = Offset(thumbX, centerY))
    }
}

/**
 * İki tutamaçlı (başlangıç + bitiş) aralık slider'ı. Her iki tutamaç da parmakla
 * sürüklenebilir; tutamaçlardan uzak bir yere dokunulursa en yakın tutamaç oraya taşınır.
 */
@Composable
fun MuxRangeSlider(
    startMs: Long,
    endMs: Long,
    durationMs: Long,
    onRangeChange: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    activeColor: Color = Color(0xFF7C6FE0),
    inactiveColor: Color = Color(0xFF2A2A35),
    minGapMs: Long = 100L,
    thumbRadius: Dp = 9.dp,
    sliderHeight: Dp = 40.dp
) {
    val safeDuration = durationMs.coerceAtLeast(minGapMs + 1L)

    var dragActive by remember { mutableStateOf(false) }
    var dragStartMs by remember { mutableLongStateOf(startMs) }
    var dragEndMs by remember { mutableLongStateOf(endMs) }

    val onRangeState = rememberUpdatedState(onRangeChange)
    val durationState = rememberUpdatedState(safeDuration)
    val gapState = rememberUpdatedState(minGapMs)
    val startState = rememberUpdatedState(startMs)
    val endState = rememberUpdatedState(endMs)

    val shownStart = if (dragActive) dragStartMs else startMs
    val shownEnd = if (dragActive) dragEndMs else endMs

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(sliderHeight)
            .pointerInput(enabled, durationMs) {
                if (!enabled) return@pointerInput
                val thumbPx = thumbRadius.toPx()

                fun fractionAt(x: Float): Float {
                    val left = thumbPx
                    val right = size.width - thumbPx
                    if (right <= left) return 0f
                    return ((x - left) / (right - left)).coerceIn(0f, 1f)
                }

                fun msAt(fraction: Float): Long =
                    (fraction * durationState.value.toFloat()).toLong()

                fun pxOf(ms: Long): Float {
                    val left = thumbPx
                    val right = size.width - thumbPx
                    val f = (ms.toFloat() / durationState.value.toFloat()).coerceIn(0f, 1f)
                    return left + (right - left) * f
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()

                    val gap = gapState.value
                    val dur = durationState.value
                    val x = down.position.x
                    var s = startState.value
                    var e = endState.value
                    val distStart = abs(x - pxOf(s))
                    val distEnd = abs(x - pxOf(e))
                    val handle = if (distStart <= distEnd) 0 else 1
                    val touchPx = thumbPx * 2.6f

                    // Tutamaçlardan uzak bir yere dokunuldu: en yakın tutamaç oraya taşınır.
                    if (minOf(distStart, distEnd) > touchPx) {
                        val target = msAt(fractionAt(x))
                        if (handle == 0) s = target.coerceIn(0L, (e - gap).coerceAtLeast(0L))
                        else e = target.coerceIn((s + gap).coerceAtMost(dur), dur)
                    }

                    dragActive = true
                    dragStartMs = s
                    dragEndMs = e
                    onRangeState.value(s, e)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val target = msAt(fractionAt(change.position.x))
                        if (handle == 0) {
                            s = target.coerceIn(0L, (e - gap).coerceAtLeast(0L))
                        } else {
                            e = target.coerceIn((s + gap).coerceAtMost(dur), dur)
                        }
                        dragStartMs = s
                        dragEndMs = e
                        onRangeState.value(s, e)
                        change.consume()
                        if (!change.pressed) break
                    }
                    dragActive = false
                }
            }
    ) {
        val centerY = size.height / 2f
        val thumbPx = thumbRadius.toPx()
        val left = thumbPx
        val right = size.width - thumbPx
        val trackHeightPx = 5.dp.toPx()

        fun xOf(ms: Long): Float {
            val f = (ms.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
            return left + (right - left) * f
        }

        val startX = xOf(shownStart)
        val endX = xOf(shownEnd)

        drawLine(
            color = inactiveColor,
            start = Offset(left, centerY),
            end = Offset(right, centerY),
            strokeWidth = trackHeightPx,
            cap = StrokeCap.Round
        )
        if (endX > startX) {
            drawLine(
                color = activeColor,
                start = Offset(startX, centerY),
                end = Offset(endX, centerY),
                strokeWidth = trackHeightPx,
                cap = StrokeCap.Round
            )
        }
        drawCircle(color = activeColor, radius = thumbPx, center = Offset(startX, centerY))
        drawCircle(color = Color.White, radius = thumbPx * 0.42f, center = Offset(startX, centerY))
        drawCircle(color = activeColor, radius = thumbPx, center = Offset(endX, centerY))
        drawCircle(color = Color.White, radius = thumbPx * 0.42f, center = Offset(endX, centerY))
    }
}

/**
 * Tüm video süresini tek bir çubukta gösterir: korunacak (mavi/mor) ve kesilecek
 * (kırmızı) bölgeler ayrı renklerde çizilir, oynatıcının anlık konumu beyaz çizgiyle işaretlenir.
 */
@Composable
fun CutTimelineBar(
    durationMs: Long,
    cutRanges: List<TimeRange>,
    playheadMs: () -> Long,
    modifier: Modifier = Modifier,
    keepColor: Color = Color(0xFF7C6FE0),
    cutColor: Color = Color(0xFFE24B4A),
    height: Dp = 34.dp
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val total = durationMs.coerceAtLeast(1L).toFloat()
        val barHeight = 16.dp.toPx()
        val top = (size.height - barHeight) / 2f
        val radius = CornerRadius(barHeight / 2f, barHeight / 2f)

        fun xOf(ms: Long): Float = (ms.toFloat() / total).coerceIn(0f, 1f) * size.width

        // Tamamı "korunacak" kabul edilip mor çizilir.
        drawRoundRect(
            color = keepColor.copy(alpha = 0.85f),
            topLeft = Offset(0f, top),
            size = Size(size.width, barHeight),
            cornerRadius = radius
        )
        // Kesilecek aralıklar kırmızı ile üzerine boyanır.
        cutRanges.forEach { range ->
            val x1 = xOf(range.startMs)
            val x2 = xOf(range.endMs)
            if (x2 > x1) {
                drawRect(
                    color = cutColor,
                    topLeft = Offset(x1, top),
                    size = Size(x2 - x1, barHeight)
                )
            }
        }
        // Oynatıcının konumu (draw sırasında okunur; her karede yeniden kompozisyon yapmaz).
        val px = xOf(playheadMs())
        drawLine(
            color = Color.White,
            start = Offset(px, top - 5.dp.toPx()),
            end = Offset(px, top + barHeight + 5.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}
