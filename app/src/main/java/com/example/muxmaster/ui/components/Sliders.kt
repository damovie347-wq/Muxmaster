package com.example.muxmaster.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.muxmaster.model.TimeRange
import kotlin.math.abs

/** Parmağın "sürükleme" sayılması için gereken en küçük hareket (px). */
private const val SCRUB_SLOP_PX = 3f

/**
 * Neden özel slider?
 *
 * Material3'ün hazır `Slider` bileşenleri, dikey olarak kaydırılabilen (verticalScroll) bir
 * sütunun içinde, daraltılmış yükseklikte kullanıldığında bazı cihazlarda sürükleme (drag)
 * hareketini yutuyor; yalnızca tek dokunuş çalışıyordu.
 *
 * ÇÖZÜM: jest, [PointerEventPass.Initial] aşamasında yakalanır ve her karede tüketilir.
 * Initial aşaması yukarıdan aşağıya dağıtıldığı için, üstteki kaydırılabilir kapsayıcı
 * Main aşamasında olayı "tüketilmiş" görür ve kendi kaydırma jestini iptal eder.
 *
 * DOKUNMA OFSETİ (YouTube / profesyonel oynatıcı davranışı):
 * Çubuğun herhangi bir yerine dokunup sürükleyebilirsiniz; dokunduğunuz nokta ile tutamaç
 * arasındaki mesafe KORUNUR. Tutamaç parmağınızın altına atlamaz: parmak ne kadar
 * hareket ederse tutamaç da o kadar hareket eder (göreli sürükleme). Parmak kalkınca
 * seçilen GERÇEK karede kalır.
 *
 * KARE HASSASİYETİ: [snapToFrame] verildiğinde hedef an her zaman videonun gerçek kare
 * başlangıcına oturtulur. Ara önizlemeler hızlıdır; parmak bırakılınca hedef kare kesin aranır.
 *
 * 500 MS ÖRNEKLEME: [sampleMs] > 0 verildiğinde hedef an önce bu ızgaranın en yakın katına
 * (örn. 1,35 sn -> 1,5 sn), sonra gerçek kare sınırına oturtulur. Böylece sürükleme boyunca
 * 0,0 -> 0,5 -> 1,0 -> 1,5 ... noktalarının GERÇEK kareleri görülebilir.
 *
 * GECİKME: Sürükleme sırasında istekler ARTIK zamanla kısıtlanmaz (eski 80 ms kısıtlama
 * kaldırıldı); her yeni örnek anında iletilir. İstek birikmesini ve eski karenin yeni karenin
 * üzerine yazılmasını `ScrubSeeker` "en son istek kazanır" mantığıyla engeller.
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
    sliderHeight: Dp = 36.dp,
    enabled: Boolean = true,
    /** Kesilecek (çıkarılacak) aralıklar: çubuk üzerinde kırmızı gösterilir. */
    markedRanges: List<TimeRange> = emptyList(),
    /** A noktası damgası (ms). -1 = damga yok; damga ile tutamaç arası sarı gösterilir. */
    markStartMs: Long = -1L,
    /**
     * Kare hassasiyeti: ms -> en yakın kare başlangıcı (bkz. `FrameClock.snap`).
     * `null` verilirse yuvarlama yapılmaz.
     */
    snapToFrame: ((Long) -> Long)? = null,
    /**
     * Sürükleme örnekleme adımı (ms). 500 = her yarım saniyede bir gerçek kare.
     * 0 = ızgara yok (yalnızca kare sınırına oturtulur).
     */
    sampleMs: Long = 0L,
    /** Dokunulan nokta ile tutamaç arasındaki mesafeyi koru (parmak tutamağı çekmez). */
    preserveTouchOffset: Boolean = true,
    /**
     * Sürükleme sırasında ekranın ortasında büyük zaman göstergesi göster (mpv tarzı).
     * Gösterge parmağın konumundan beslenir: dekoder yetişemese bile anında okunur.
     */
    showScrubHud: Boolean = true,
    /**
     * Sürükleme sırasında hedef anı üst katmana bildirir (-1 = sürükleme bitti).
     * Süre etiketleri böylece oynatıcıyı beklemeden ANINDA güncellenir.
     */
    onScrubPreview: (Long) -> Unit = {}
) {
    val span = (endMs - startMs).coerceAtLeast(1L)
    // Sürükleme sırasında tutamağın yeri; -1 = sürükleme yok (oynatıcının konumu kullanılır).
    var dragFraction by remember { mutableFloatStateOf(-1f) }
    // Sürükleme göstergesi: -1 = gizli. Parmak kalkınca temizlenir.
    var hudMs by remember { mutableLongStateOf(-1L) }
    var hudBaseMs by remember { mutableLongStateOf(-1L) }

    val onSeekState = rememberUpdatedState(onSeek)
    val onScrubState = rememberUpdatedState(onScrubChange)
    val startState = rememberUpdatedState(startMs)
    val spanState = rememberUpdatedState(span)
    val positionState = rememberUpdatedState(positionMs)
    val snapState = rememberUpdatedState(snapToFrame)
    val sampleState = rememberUpdatedState(sampleMs)
    val previewState = rememberUpdatedState(onScrubPreview)

    val shownFraction = if (dragFraction >= 0f) {
        dragFraction
    } else {
        ((positionMs - startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(sliderHeight)
            .pointerInput(enabled, preserveTouchOffset) {
                if (!enabled) return@pointerInput
                val thumbPx = thumbRadius.toPx()

                fun fractionOf(ms: Long): Float =
                    ((ms - startState.value).toFloat() / spanState.value.toFloat()).coerceIn(0f, 1f)

                fun msAt(fraction: Float): Long =
                    startState.value + (fraction * spanState.value.toFloat()).toLong()

                fun snapped(ms: Long): Long {
                    val last = startState.value + spanState.value
                    val raw = ms.coerceIn(startState.value, last)
                    val s = snapState.value?.invoke(raw) ?: raw
                    return s.coerceIn(startState.value, last)
                }

                /** 500 ms ızgarası + gerçek kare sınırı. */
                fun quantized(ms: Long): Long {
                    val g = sampleState.value
                    if (g <= 1L) return snapped(ms)
                    val grid = ((ms.coerceAtLeast(0L) + g / 2L) / g) * g
                    return snapped(grid)
                }

                awaitEachGesture {
                    // Initial aşaması: üstteki verticalScroll bu olayları tüketilmiş görür.
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    down.consume()

                    val usable = (size.width - thumbPx * 2f).coerceAtLeast(1f)
                    // Dokunma anındaki tutamaç oranı. Ofset korunur: bu oran referans alınır,
                    // parmağın nerede olduğu değil. Böylece tutamaç parmağın altına atlamaz.
                    val baseFraction = fractionOf(positionState.value)
                    val baseMs = positionState.value
                        .coerceIn(startState.value, startState.value + spanState.value)
                    val downX = down.position.x
                    val thumbX = thumbPx + usable * baseFraction
                    var fraction = baseFraction
                    var moved = false
                    var reported = false
                    // Aynı örnek noktası için tekrar tekrar istek gönderilmez; yeni örnek
                    // noktasına geçildiği ANDA gönderilir (gecikme yok, kısıtlama yok).
                    var lastSentMs = Long.MIN_VALUE

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()

                        // Ofset korunurken parmağın toplam hareketi, tutamağın toplam
                        // hareketine eşittir (göreli sürükleme).
                        val dx = if (preserveTouchOffset) change.position.x - downX
                        else change.position.x - thumbX
                        if (!moved && abs(dx) > SCRUB_SLOP_PX) {
                            moved = true
                            reported = true
                            // Sürükleme başladı: üst katman oynatmayı durdurup kareyi sabitler.
                            onScrubState.value(true)
                            hudBaseMs = baseMs
                        }
                        if (moved) {
                            fraction = (baseFraction + dx / usable).coerceIn(0f, 1f)
                            val target = quantized(msAt(fraction))
                            // Tutamaç, kareye oturmuş hedefe göre çizilir (kare kare ilerler).
                            fraction = fractionOf(target)
                            dragFraction = fraction
                            // ANLIK GERİ BİLDİRİM: gösterge ve süre etiketleri parmaktan beslenir.
                            hudMs = target
                            previewState.value(target)
                            if (change.pressed && target != lastSentMs) {
                                lastSentMs = target
                                onSeekState.value(target)
                            }
                        }
                        if (!change.pressed) break
                    }

                    if (moved) {
                        // Parmak kalktı: seçilen GERÇEK kareye tek seferde otur.
                        val target = quantized(msAt(fraction))
                        dragFraction = fractionOf(target)
                        hudMs = target
                        previewState.value(target)
                        if (target != lastSentMs) onSeekState.value(target)
                    }
                    if (reported) onScrubState.value(false)
                    dragFraction = -1f
                    hudMs = -1L
                    previewState.value(-1L)
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

        // ── KESİM İŞARETLERİ ────────────────────────────────────────────────
        // Damgalanmış kesim aralıkları kırmızı, henüz bitişi seçilmemiş A noktası
        // ile tutamaç arası sarı gösterilir. Böylece "ne kesiliyor" tek bakışta görünür.
        val markerHeight = stroke * 3.2f
        val markerTop = centerY - markerHeight / 2f
        if (markedRanges.isNotEmpty() && span > 0L) {
            markedRanges.forEach { r ->
                val f1 = ((r.startMs - startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
                val f2 = ((r.endMs - startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
                val x1 = left + (right - left) * f1
                val x2 = left + (right - left) * f2
                if (x2 - x1 > 0.5f) {
                    drawRect(
                        color = Color(0xFFE24B4A),
                        topLeft = Offset(x1, markerTop),
                        size = Size(x2 - x1, markerHeight)
                    )
                }
            }
        }
        if (markStartMs >= 0L && span > 0L) {
            val fA = ((markStartMs - startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
            val xA = left + (right - left) * fA
            if (abs(xA - thumbX) > 0.5f) {
                drawRect(
                    color = Color(0x99FFC24B),
                    topLeft = Offset(minOf(xA, thumbX), markerTop),
                    size = Size(abs(thumbX - xA), markerHeight)
                )
            }
            drawLine(
                color = Color(0xFFFFC24B),
                start = Offset(xA, centerY - markerHeight),
                end = Offset(xA, centerY + markerHeight),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        // Oynatma tutamacı: kırmızı halka + beyaz gövde.
        // Halka, kare hassas sürüklemede tutamağın nerede olduğunu net gösterir.
        drawCircle(color = activeColor.copy(alpha = 0.28f), radius = thumbPx * 1.9f, center = Offset(thumbX, centerY))
        drawCircle(color = Color(0xFFE24B4A), radius = thumbPx * 1.18f, center = Offset(thumbX, centerY))
        drawCircle(color = Color.White, radius = thumbPx * 0.82f, center = Offset(thumbX, centerY))
    }

    // Sürükleme göstergesi: parmak çubukta gezerken ekranın ortasında hedef an.
    ScrubHudPopup(
        visible = showScrubHud && hudMs >= 0L,
        targetMs = hudMs,
        totalMs = if (endMs > startMs) endMs else 0L,
        baseMs = hudBaseMs,
        accent = activeColor
    )
}

/**
 * Tüm video süresini tek bir çubukta gösterir: korunacak (mor) ve kesilecek
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
    height: Dp = 34.dp,
    onTapMs: ((Long) -> Unit)? = null
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .then(
                if (onTapMs == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(durationMs, onTapMs) {
                        // Tek dokunuş: çubuğun dokunulan yerine atla (hızlı kesim akışı).
                        detectTapGestures { offset ->
                            val w = size.width.toFloat()
                            if (w > 0f) {
                                val fraction = (offset.x / w).coerceIn(0f, 1f)
                                onTapMs((fraction * durationMs.toFloat()).toLong())
                            }
                        }
                    }
                }
            )
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
