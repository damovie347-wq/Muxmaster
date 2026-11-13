package com.example.muxmaster.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.util.Locale

/**
 * SÜRÜKLEME (SCRUB) ZAMAN GÖSTERGESİ.
 *
 * mpv / Just Player davranışı: parmak çubukta gezerken ekranın ortasında hedef an
 * salise hassasiyetiyle yazar, parmağın nereden nereye gittiği (delta) ve kalan süre
 * görünür. Gösterge oynatıcıdan DEĞİL, doğrudan parmağın konumundan beslenir:
 * dekoder yetişemese bile kullanıcı kaydırdığı anı anında okur.
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

@Composable
fun ScrubHud(
    targetMs: Long,
    totalMs: Long = 0L,
    baseMs: Long = -1L,
    frameNumber: Long = -1L,
    accent: Color = Color(0xFF66C2B4),
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xE6101216), RoundedCornerShape(16.dp))
            .padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = scrubClock(targetMs),
            color = Color.White,
            fontSize = 30.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(2.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (baseMs >= 0L) {
                val delta = targetMs - baseMs
                val sign = if (delta < 0L) "−" else "+"
                Text(
                    text = sign + scrubClock(if (delta < 0L) -delta else delta),
                    color = accent,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (totalMs > 0L) {
                Text(
                    text = "kalan " + scrubClock((totalMs - targetMs).coerceAtLeast(0L)),
                    color = Color(0xFFB9BDC7),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (frameNumber > 0L) {
                Text(
                    text = "kare $frameNumber",
                    color = Color(0xFF8E939F),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/**
 * [ScrubHud] göstergeyi ekranın ortasında, dokunmayı yutmadan gösterir.
 * [visible] false olduğunda hiçbir katman oluşturulmaz.
 */
@Composable
fun ScrubHudPopup(
    visible: Boolean,
    targetMs: Long,
    totalMs: Long = 0L,
    baseMs: Long = -1L,
    frameNumber: Long = -1L,
    accent: Color = Color(0xFF66C2B4)
) {
    if (!visible) return
    Popup(
        alignment = Alignment.Center,
        offset = IntOffset(0, 0),
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        ScrubHud(
            targetMs = targetMs,
            totalMs = totalMs,
            baseMs = baseMs,
            frameNumber = frameNumber,
            accent = accent
        )
    }
}