package com.example.muxmaster.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.muxmaster.data.FrameClock
import com.example.muxmaster.model.TimeRange
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Kırpma zaman çizelgesinde izin verilen en yüksek yakınlaştırma. */
const val FRAME_TIMELINE_MAX_ZOOM = 60f

/**
 * KARE HASSAS KIRPMA ZAMAN ÇİZELGESİ (zoom'lu).
 *
 * Özellikler:
 * - İki tutamaç (başlangıç / bitiş) BİRBİRİNDEN BAĞIMSIZ hareket eder.
 * - Dokunma ofseti korunur: tutamaç parmağın altına atlamaz, parmakla birlikte göreli
 *   olarak kayar (YouTube / profesyonel editör davranışı).
 * - Tüm değerler videonun GERÇEK kare sınırlarına oturtulur ([FrameClock]).
 * - İki parmakla (pinch) veya dışarıdaki +/- düğmeleriyle yakınlaştırma; tek parmakla
 *   boş alanı sürükleyerek görünür pencereyi kaydırma.
 * - Thumbnail şeridi YOKTUR: hiçbir kare çözülmez, hiçbir kare önbelleğe alınmaz.
 *   Sadece geometri ve kare zamanlaması çizilir; önizleme ana oynatıcıda gösterilir.
 */
@Composable
fun FrameTrimTimeline(
    durationMs: Long,
    frameClock: FrameClock,
    startMs: Long,
    endMs: Long,
    windowStartMs: Long,
    zoom: Float,
    playheadMs: () -> Long,
    cutRanges: List<TimeRange> = emptyList(),
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 76.dp,
    activeColor: Color = Color(0xFF7C6FE0),
    cutColor: Color = Color(0xFFE24B4A),
    onRangeChange: (Long, Long) -> Unit = { _, _ -> },
    onWindowChange: (Float, Long) -> Unit = { _, _ -> },
    /** Tutamaç sürüklenirken: 0 = başlangıç, 1 = bitiş; ms = o tutamacın kare hizalı anı. */
    onHandleDrag: (Int, Long) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {}
) {
    if (durationMs <= 0L) return

    val durationState = rememberUpdatedState(durationMs)
    val clockState = rememberUpdatedState(frameClock)
    val startState = rememberUpdatedState(startMs)
    val endState = rememberUpdatedState(endMs)
    val windowStartState = rememberUpdatedState(windowStartMs)
    val zoomState = rememberUpdatedState(zoom)
    val rangeState = rememberUpdatedState(onRangeChange)
    val windowState = rememberUpdatedState(onWindowChange)
    val handleState = rememberUpdatedState(onHandleDrag)
    val dragEndState = rememberUpdatedState(onDragEnd)
    val cutState = rememberUpdatedState(cutRanges)

    val frameStep = frameClock.frameStepMs.coerceAtLeast(1L)
    val minWindowMs = max(frameStep * 4L, 160L)

    // Sürükleme göstergesi: tutamaç çekilirken ekranın ortasında o karenin anı yazar.
    // -1 = sürükleme yok. Değer parmaktan beslenir, oynatıcıdan değil: anında okunur.
    var hudMs by remember { mutableLongStateOf(-1L) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(enabled, durationMs) {
                if (!enabled) return@pointerInput

                val inset = 14.dp.toPx()
                val hitRadius = 30.dp.toPx()

                fun usableW(): Float = (size.width - inset * 2f).coerceAtLeast(1f)

                fun xOf(t: Long, winStart: Long, winLen: Long): Float {
                    val f = ((t - winStart).toFloat() / winLen.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    return inset + usableW() * f
                }

                fun timeOf(x: Float, winStart: Long, winLen: Long): Long {
                    val f = ((x - inset) / usableW()).coerceIn(0f, 1f)
                    return winStart + (f * winLen.toFloat()).toLong()
                }

                fun windowLen(z: Float): Long {
                    val total = durationState.value
                    val raw = (total.toFloat() / z.coerceIn(1f, FRAME_TIMELINE_MAX_ZOOM)).toLong()
                    return raw.coerceIn(minWindowMs.coerceAtMost(total), total)
                }

                fun clampWindowStart(s: Long, winLen: Long): Long {
                    val total = durationState.value
                    return s.coerceIn(0L, (total - winLen).coerceAtLeast(0L))
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    down.consume()

                    var curZoom = zoomState.value
                    var curLen = windowLen(curZoom)
                    var curStart = clampWindowStart(windowStartState.value, curLen)

                    // Tutamaç seçimi: dokunulan noktaya en yakın tutamaç (eşik içindeyse).
                    val startX = xOf(startState.value, curStart, curLen)
                    val endX = xOf(endState.value, curStart, curLen)
                    val dStart = abs(down.position.x - startX)
                    val dEnd = abs(down.position.x - endX)
                    var handle = when {
                        dStart <= hitRadius && dStart <= dEnd -> 0
                        dEnd <= hitRadius -> 1
                        else -> -1
                    }
                    // DOKUNMA OFSETİ: tutamacın parmağa göre konumu sabit tutulur.
                    val grabOffset = when (handle) {
                        0 -> startX - down.position.x
                        1 -> endX - down.position.x
                        else -> 0f
                    }

                    var localStart = startState.value
                    var localEnd = endState.value
                    var lastX = down.position.x
                    val minGap = frameStep
                    val total = durationState.value
                    // Arayüz güncellemeleri ~20 ms'de bir yapılır: sürükleme akıcı kalır,
                    // gereksiz yeniden çizim ve sarma isteği yağmuru oluşmaz.
                    var lastReport = 0L
                    var draggedHandle = false

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2) {
                            // ── İKİ PARMAK: YAKINLAŞTIRMA (zoom) + pencere kaydırma ──
                            handle = -1
                            val zoomChange = event.calculateZoom()
                            val pan = event.calculatePan()
                            val cx = pressed.map { it.position.x }.average().toFloat()
                            val f = ((cx - inset) / usableW()).coerceIn(0f, 1f)
                            val focusTime = curStart + (f * curLen.toFloat()).toLong()
                            val newZoom = (curZoom * zoomChange).coerceIn(1f, FRAME_TIMELINE_MAX_ZOOM)
                            val newLen = windowLen(newZoom)
                            val panTime = (pan.x / usableW() * newLen.toFloat()).toLong()
                            val newStart = clampWindowStart(
                                focusTime - (f * newLen.toFloat()).toLong() - panTime,
                                newLen
                            )
                            curZoom = newZoom
                            curLen = newLen
                            curStart = newStart
                            windowState.value(curZoom, curStart)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else {
                            val change = pressed.first()
                            if (handle >= 0) {
                                // ── TUTAMAÇ SÜRÜKLEME (ofset korunur, kareye oturur) ──
                                val x = change.position.x + grabOffset
                                val clock = clockState.value
                                val snapped = clock.snap(timeOf(x, curStart, curLen))
                                if (handle == 0) {
                                    val upper = (localEnd - minGap).coerceAtLeast(0L)
                                    localStart = snapped.coerceIn(0L, upper)
                                } else {
                                    val lower = (localStart + minGap).coerceAtMost(total)
                                    localEnd = snapped.coerceIn(lower, total)
                                }
                                draggedHandle = true
                                hudMs = if (handle == 0) localStart else localEnd
                                val now = System.currentTimeMillis()
                                if (now - lastReport >= 20L || !change.pressed) {
                                    lastReport = now
                                    rangeState.value(localStart, localEnd)
                                    handleState.value(handle, if (handle == 0) localStart else localEnd)
                                }
                                if (change.positionChanged()) change.consume()
                            } else if (curZoom > 1.001f) {
                                // ── BOŞ ALAN: görünür pencereyi kaydır ──
                                val dx = change.position.x - lastX
                                if (abs(dx) > 0.5f) {
                                    val dxTime = (-dx / usableW() * curLen.toFloat()).toLong()
                                    curStart = clampWindowStart(curStart + dxTime, curLen)
                                    windowState.value(curZoom, curStart)
                                }
                                if (change.positionChanged()) change.consume()
                            }
                            lastX = change.position.x
                        }
                    }
                    // Son kare kesin olarak bildirilir (sürükleme bittiğinde kaçırma olmaz).
                    if (draggedHandle && handle >= 0) {
                        rangeState.value(localStart, localEnd)
                        handleState.value(handle, if (handle == 0) localStart else localEnd)
                    }
                    hudMs = -1L
                    dragEndState.value()
                }
            }
    ) {
        val total = durationMs.coerceAtLeast(1L)
        val winLen = ((total.toFloat() / zoom.coerceIn(1f, FRAME_TIMELINE_MAX_ZOOM)).toLong())
            .coerceIn(minWindowMs.coerceAtMost(total), total)
        val winStart = windowStartMs.coerceIn(0L, (total - winLen).coerceAtLeast(0L))
        val winEnd = winStart + winLen

        val inset = 14.dp.toPx()
        val usable = (size.width - inset * 2f).coerceAtLeast(1f)
        val bandH = 30.dp.toPx()
        val bandTop = (size.height - bandH) / 2f
        val radius = CornerRadius(7.dp.toPx(), 7.dp.toPx())

        fun xOf(t: Long): Float {
            val f = ((t - winStart).toFloat() / winLen.toFloat()).coerceIn(0f, 1f)
            return inset + usable * f
        }

        // 1) Şerit zemini
        drawRoundRect(
            color = Color(0xFF23252E),
            topLeft = Offset(inset, bandTop),
            size = Size(usable, bandH),
            cornerRadius = radius
        )

        // 2) Kare çentikleri (yalnızca çizim; kare çözme/thumbnail YOK)
        val pxPerFrame = usable / winLen.toFloat() * frameStep.toFloat()
        if (pxPerFrame > 3.5f) {
            val stepFrames = if (pxPerFrame >= 7f) 1L else ceil(7f / pxPerFrame).toLong()
            val clock = frameClock
            var idx = clock.frameIndexAt(winStart)
            var guard = 0
            while (guard < 600) {
                val t = clock.frameTimeMs(idx)
                if (t > winEnd) break
                if (t >= winStart) {
                    val x = xOf(t)
                    drawLine(
                        color = Color.White.copy(alpha = if (stepFrames == 1L) 0.16f else 0.10f),
                        start = Offset(x, bandTop + bandH * 0.62f),
                        end = Offset(x, bandTop + bandH),
                        strokeWidth = 1f
                    )
                }
                idx += stepFrames
                guard++
            }
        }

        // 3) Tutulacak aralık (start..end) vurgusu
        val sx = xOf(startMs)
        val ex = xOf(endMs)
        if (ex > sx) {
            drawRoundRect(
                color = activeColor.copy(alpha = 0.34f),
                topLeft = Offset(sx, bandTop),
                size = Size(ex - sx, bandH),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )
            drawRect(
                color = activeColor,
                topLeft = Offset(sx, bandTop + bandH - 2.dp.toPx()),
                size = Size(ex - sx, 2.dp.toPx())
            )
        }

        // 4) Kesilecek (çıkarılacak) aralıklar
        cutRanges.forEach { r ->
            val c1 = xOf(r.startMs)
            val c2 = xOf(r.endMs)
            if (c2 > c1) {
                drawRect(
                    color = cutColor.copy(alpha = 0.85f),
                    topLeft = Offset(c1, bandTop),
                    size = Size(c2 - c1, bandH)
                )
            }
        }

        // 5) Oynatıcının konumu
        val ph = playheadMs()
        if (ph in winStart..winEnd) {
            val px = xOf(ph)
            drawLine(
                color = Color.White,
                start = Offset(px, bandTop - 6.dp.toPx()),
                end = Offset(px, bandTop + bandH + 6.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // 6) İki tutamaç (bağımsız)
        fun drawHandle(x: Float) {
            val w = 3.5.dp.toPx()
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(x - w, bandTop - 3.dp.toPx()),
                size = Size(w * 2f, bandH + 6.dp.toPx()),
                cornerRadius = CornerRadius(w, w)
            )
            drawCircle(color = activeColor, radius = 8.dp.toPx(), center = Offset(x, bandTop - 1.dp.toPx()))
            drawCircle(color = Color.White, radius = 3.5.dp.toPx(), center = Offset(x, bandTop - 1.dp.toPx()))
            drawCircle(color = activeColor, radius = 8.dp.toPx(), center = Offset(x, bandTop + bandH + 1.dp.toPx()))
            drawCircle(color = Color.White, radius = 3.5.dp.toPx(), center = Offset(x, bandTop + bandH + 1.dp.toPx()))
        }
        drawHandle(sx)
        drawHandle(ex)
    }

    // Tutamaç çekilirken ekranın ortasında büyük zaman göstergesi (mpv tarzı).
    ScrubHudPopup(
        visible = hudMs >= 0L,
        targetMs = hudMs,
        totalMs = durationMs,
        frameNumber = if (frameClock.totalFrames > 0L) frameClock.frameNumber(hudMs) else -1L,
        accent = activeColor
    )
}
