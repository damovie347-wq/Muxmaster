package com.example.muxmaster.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * SÜRÜKLEME (SCRUB) ZAMAN BALONCUĞU — KOMPAKT.
 *
 * ÖNEMLİ DEĞİŞİKLİK: Ekranın ORTASINDA açılan kocaman "kalan süre / delta / kare"
 * paneli (eski `ScrubHud` + `ScrubHudPopup`) tamamen kaldırıldı. Video izlerken
 * görüntünün üstünü kapatıyordu. Artık yalnızca kaydırılan tutamağın hemen üstünde,
 * çubuğun kendi içinde küçük bir zaman baloncuğu çizilir:
 *
 *  - Video alanı hiç kapanmaz (kare canlı görünür).
 *  - Salise hassasiyeti (mm:ss.cc) doğrudan çubuğun üzerinde okunur.
 *  - Baloncuk çubuğun DIŞINA taşmaz; kenarlarda otomatik olarak içeri kayar.
 */
internal fun scrubClock(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val h = safe / 3_600_000L
    val m = (safe % 3_600_000L) / 60_000L
    val s = (safe % 60_000L) / 1000L
    val cs = (safe % 1000L) / 10L
    return if (h > 0L) String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
    else String.format(Locale.US, "%02d:%02d.%02d", m, s, cs)
}

/** Kaydırma baloncuğunun yazı tipi (tek yerde tanımlı; iki çubuk da bunu kullanır). */
private fun scrubTextPaint(sizePx: Float): android.graphics.Paint =
    android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        textSize = sizePx
        typeface = android.graphics.Typeface.MONOSPACE
        isFakeBoldText = true
    }

/**
 * [centerX] noktasının üstüne küçük zaman baloncuğu çizer.
 * Baloncuk çizim alanının DIŞINA çıkmaz: kenarda otomatik olarak içeri kayar.
 */
internal fun DrawScope.drawScrubBubble(
    centerX: Float,
    topY: Float,
    label: String,
    accent: Color = Color(0xFF66C2B4)
) {
    val paint = scrubTextPaint(10.sp.toPx())
    val textW = paint.measureText(label)
    val padH = 7.dp.toPx()
    val chipH = 16.dp.toPx()
    val chipW = textW + padH * 2f
    val cx = centerX.coerceIn(chipW / 2f, (size.width - chipW / 2f).coerceAtLeast(chipW / 2f))
    val top = topY.coerceIn(0f, (size.height - chipH).coerceAtLeast(0f))
    val left = cx - chipW / 2f

    drawRoundRect(
        color = Color(0xF00B0D12),
        topLeft = androidx.compose.ui.geometry.Offset(left, top),
        size = androidx.compose.ui.geometry.Size(chipW, chipH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(chipH / 2f, chipH / 2f)
    )
    drawRoundRect(
        color = accent.copy(alpha = 0.9f),
        topLeft = androidx.compose.ui.geometry.Offset(left, top),
        size = androidx.compose.ui.geometry.Size(chipW, chipH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(chipH / 2f, chipH / 2f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())
    )
    drawContext.canvas.nativeCanvas.drawText(
        label,
        cx - textW / 2f,
        top + chipH * 0.72f,
        paint
    )
}
