package com.example.muxmaster.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.muxmaster.R
import com.example.muxmaster.ui.theme.LocalThemeVariant
import com.example.muxmaster.ui.theme.ThemeVariant
import com.example.muxmaster.data.MediaThumbs
import com.example.muxmaster.data.RecentFiles
import com.example.muxmaster.data.RecentMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ANA EKRAN RENK PALETİ — TEMA DUYARLI.
 *
 * ÖNEMLİ DEĞİŞİKLİK: Ana ekranın arka planı, kartları ve yazı renkleri artık SABİT mor
 * değil; Ayarlar > Görünüm'de seçilen temaya göre değişir.
 *  - KOYU  : nötr koyu gri/siyah zemin (mor parıltı çok hafif).
 *  - AMOLED: tam siyah (#000000) zemin ve kartlar; parıltı/dalga çizilmez (piksel kapanır).
 *  - AÇIK  : açık gri/beyaz zemin, koyu yazı.
 * Kartların renkli gradyanları (Mux/Kes/Birleştir/Oynatıcı) her temada canlı kalır.
 */
private data class HomePalette(
    val bgTop: Color,
    val bgMid: Color,
    val bgMid2: Color,
    val bgBottom: Color,
    val accentText: Color,
    val link: Color,
    val cardBg: Color,
    val cardStroke: Color,
    val title: Color,
    val glow: Color,
    val bandAlpha: Float,
    val thumbGradient: List<Color>
)

@Composable
private fun homePalette(): HomePalette = when (LocalThemeVariant.current) {
    ThemeVariant.LIGHT -> HomePalette(
        bgTop = Color(0xFFF7F7FA),
        bgMid = Color(0xFFEFEDFA),
        bgMid2 = Color(0xFFF6F6FA),
        bgBottom = Color(0xFFECECF2),
        accentText = Color(0xFF6B6B78),
        link = Color(0xFF6E56CF),
        cardBg = Color(0xFFFFFFFF),
        cardStroke = Color(0xFFE3E3E8),
        title = Color(0xFF1D1D1F),
        glow = Color(0x269B7BF5),
        bandAlpha = 0.18f,
        thumbGradient = listOf(Color(0xFFEDEBFB), Color(0xFFDCD6F7))
    )
    ThemeVariant.AMOLED -> HomePalette(
        bgTop = Color(0xFF000000),
        bgMid = Color(0xFF000000),
        bgMid2 = Color(0xFF000000),
        bgBottom = Color(0xFF000000),
        accentText = Color(0xFF8A8A96),
        link = Color(0xFF9C8CE8),
        cardBg = Color(0xFF050506),
        cardStroke = Color(0xFF1B1B20),
        title = Color(0xFFF0F0F5),
        glow = Color(0x00000000),
        bandAlpha = 0f,
        thumbGradient = listOf(Color(0xFF141418), Color(0xFF0A0A0C))
    )
    ThemeVariant.DARK -> HomePalette(
        bgTop = Color(0xFF101014),
        bgMid = Color(0xFF15151C),
        bgMid2 = Color(0xFF0D0D11),
        bgBottom = Color(0xFF08080A),
        accentText = Color(0xFF9C9CAC),
        link = Color(0xFF9C8CE8),
        cardBg = Color(0xFF17171D),
        cardStroke = Color(0xFF26262E),
        title = Color(0xFFF0F0F5),
        glow = Color(0x338B5CF6),
        bandAlpha = 0.30f,
        thumbGradient = listOf(Color(0xFF23232B), Color(0xFF16161B))
    )
}

/**
 * UYGULAMANIN ANA EKRANI.
 *
 * Büyük, tek dokunuşla erişilebilen kartlar: Mux · Kes · Birleştir · Oynatıcı,
 * altında tam genişlikte "Ses Dönüştürme" kartı ve "Son Dosyalar" listesi.
 *
 * Duyarlı (responsive) yerleşim:
 * - Dikey telefonda 2×2 kart ızgarası.
 * - Yatay modda / tablette 4 kart TEK satırda, daha alçak kart + daha küçük ikon;
 *   içerik ortalanır ve [MAX_CONTENT_WIDE] ile sınırlanır (ekran ne kadar geniş
 *   olursa olsun arayüz dağılmaz, simgeler devleşmez).
 *
 * Yazıların tamamı `strings.xml`'den gelir: Ayarlar'dan dil değiştirildiğinde ana
 * ekran da anında o dile geçer.
 */
@Composable
fun HomeScreen(
    onOpenMux: () -> Unit,
    onOpenTrim: () -> Unit,
    onOpenMerge: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenConverter: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFile: (RecentMedia) -> Unit,
    onShowAllFiles: () -> Unit
) {
    val context = LocalContext.current
    val pal = homePalette()
    var recents by remember { mutableStateOf<List<RecentMedia>>(emptyList()) }
    var thumbs by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var durations by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { RecentFiles.load(context) }
        recents = loaded
        // Süre ve küçük önizlemeler kullanıcıyı bekletmemek için tek tek yüklenir.
        loaded.take(8).forEach { item ->
            val uri = runCatching { Uri.parse(item.uriString) }.getOrNull() ?: return@forEach
            val duration = if (item.durationMs > 0L) {
                item.durationMs
            } else {
                withContext(Dispatchers.IO) { MediaThumbs.durationMs(context, uri) }
            }
            if (duration > 0L) durations = durations + (item.uriString to duration)
            if (!item.isAudio) {
                val bitmap = withContext(Dispatchers.IO) { MediaThumbs.thumbnail(context, uri) }
                if (bitmap != null) thumbs = thumbs + (item.uriString to bitmap)
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Yatay mod / tablet: geniş ve alçak ekran.
        val wide = maxWidth > maxHeight || maxWidth >= 600.dp
        val contentMaxWidth = if (wide) 780.dp else 640.dp
        val cardHeight = if (wide) 126.dp else 160.dp
        val iconSize = if (wide) 34.dp else 44.dp
        val cardTitleSp = if (wide) 17 else 20
        val cardSubSp = if (wide) 11 else 12
        val converterHeight = if (wide) 74.dp else 90.dp
        val insets = WindowInsets.safeDrawing.asPaddingValues()

        HomeBackdrop(Modifier.fillMaxSize())

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = contentMaxWidth)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 10.dp + insets.calculateTopPadding(),
                    bottom = 28.dp + insets.calculateBottomPadding()
                )
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "MuxMaster",
                                color = pal.title,
                                fontSize = 23.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                stringResource(R.string.home_tagline),
                                color = pal.accentText,
                                fontSize = 12.sp
                            )
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Filled.Settings,
                                stringResource(R.string.home_settings_desc),
                                tint = pal.accentText
                            )
                        }
                    }
                    Spacer(Modifier.height(if (wide) 10.dp else 16.dp))
                }

                item {
                    if (wide) {
                        // Yatay mod: dört kart tek satırda, alçak ve dengeli.
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FeatureCard(
                                title = stringResource(R.string.home_mux_title),
                                subtitle = stringResource(R.string.home_mux_sub),
                                icon = Icons.Filled.Extension,
                                gradient = listOf(Color(0xFF8B3DFF), Color(0xFF4C1D95)),
                                glow = Color(0xFFB388FF),
                                onClick = onOpenMux,
                                iconSize = iconSize,
                                titleSp = cardTitleSp,
                                subtitleSp = cardSubSp,
                                modifier = Modifier.weight(1f).height(cardHeight)
                            )
                            FeatureCard(
                                title = stringResource(R.string.home_trim_title),
                                subtitle = stringResource(R.string.home_trim_sub),
                                icon = Icons.Filled.ContentCut,
                                gradient = listOf(Color(0xFF0E8A86), Color(0xFF0B3B48)),
                                glow = Color(0xFF4DE3DC),
                                onClick = onOpenTrim,
                                iconSize = iconSize,
                                titleSp = cardTitleSp,
                                subtitleSp = cardSubSp,
                                modifier = Modifier.weight(1f).height(cardHeight)
                            )
                            FeatureCard(
                                title = stringResource(R.string.home_merge_title),
                                subtitle = stringResource(R.string.home_merge_sub),
                                icon = Icons.Filled.Link,
                                gradient = listOf(Color(0xFF1F5BE0), Color(0xFF0B2A6B)),
                                glow = Color(0xFF7FB0FF),
                                onClick = onOpenMerge,
                                iconSize = iconSize,
                                titleSp = cardTitleSp,
                                subtitleSp = cardSubSp,
                                modifier = Modifier.weight(1f).height(cardHeight)
                            )
                            FeatureCard(
                                title = stringResource(R.string.home_player_title),
                                subtitle = stringResource(R.string.home_player_sub),
                                icon = Icons.Filled.PlayCircle,
                                gradient = listOf(Color(0xFFF97316), Color(0xFF7C2D12)),
                                glow = Color(0xFFFFC46B),
                                onClick = onOpenPlayer,
                                iconSize = iconSize,
                                titleSp = cardTitleSp,
                                subtitleSp = cardSubSp,
                                modifier = Modifier.weight(1f).height(cardHeight)
                            )
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FeatureCard(
                                title = stringResource(R.string.home_mux_title),
                                subtitle = stringResource(R.string.home_mux_sub),
                                icon = Icons.Filled.Extension,
                                gradient = listOf(Color(0xFF8B3DFF), Color(0xFF4C1D95)),
                                glow = Color(0xFFB388FF),
                                onClick = onOpenMux,
                                iconSize = iconSize,
                                titleSp = cardTitleSp,
                                subtitleSp = cardSubSp,
                                modifier = Modifier.weight(1f).height(cardHeight)
                            )
                            FeatureCard(
                                title = stringResource(R.string.home_trim_title),
                                subtitle = stringResource(R.string.home_trim_sub),
                                icon = Icons.Filled.ContentCut,
                                gradient = listOf(Color(0xFF0E8A86), Color(0xFF0B3B48)),
                                glow = Color(0xFF4DE3DC),
                                onClick = onOpenTrim,
                                iconSize = iconSize,
                                titleSp = cardTitleSp,
                                subtitleSp = cardSubSp,
                                modifier = Modifier.weight(1f).height(cardHeight)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FeatureCard(
                                title = stringResource(R.string.home_merge_title),
                                subtitle = stringResource(R.string.home_merge_sub),
                                icon = Icons.Filled.Link,
                                gradient = listOf(Color(0xFF1F5BE0), Color(0xFF0B2A6B)),
                                glow = Color(0xFF7FB0FF),
                                onClick = onOpenMerge,
                                iconSize = iconSize,
                                titleSp = cardTitleSp,
                                subtitleSp = cardSubSp,
                                modifier = Modifier.weight(1f).height(cardHeight)
                            )
                            FeatureCard(
                                title = stringResource(R.string.home_player_title),
                                subtitle = stringResource(R.string.home_player_sub),
                                icon = Icons.Filled.PlayCircle,
                                gradient = listOf(Color(0xFFF97316), Color(0xFF7C2D12)),
                                glow = Color(0xFFFFC46B),
                                onClick = onOpenPlayer,
                                iconSize = iconSize,
                                titleSp = cardTitleSp,
                                subtitleSp = cardSubSp,
                                modifier = Modifier.weight(1f).height(cardHeight)
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(converterHeight)
                            .clip(RoundedCornerShape(22.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFF6D28D9), Color(0xFF9B5CF6))
                                )
                            )
                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(22.dp))
                            .clickable(onClick = onOpenConverter)
                            .padding(horizontal = 16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (wide) 44.dp else 52.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.GraphicEq,
                                null,
                                tint = Color.White,
                                modifier = Modifier.size(if (wide) 26.dp else 30.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.home_converter_title),
                                color = Color.White,
                                fontSize = if (wide) 17.sp else 19.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                stringResource(R.string.home_converter_sub),
                                color = Color.White.copy(alpha = 0.88f),
                                fontSize = 12.sp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.ChevronRight,
                                null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(if (wide) 14.dp else 22.dp))
                }

                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.home_recent_title),
                            color = pal.title,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onShowAllFiles)
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text(
                                stringResource(R.string.home_recent_show_all),
                                color = pal.link,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Filled.ArrowForward,
                                null,
                                tint = pal.link,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                if (recents.isEmpty()) {
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(pal.cardBg)
                                .border(1.dp, pal.cardStroke, RoundedCornerShape(16.dp))
                                .padding(vertical = 22.dp, horizontal = 16.dp)
                        ) {
                            Icon(
                                Icons.Filled.Movie,
                                null,
                                tint = pal.accentText,
                                modifier = Modifier.size(30.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.home_recent_empty_title),
                                color = pal.title.copy(alpha = 0.9f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                stringResource(R.string.home_recent_empty_sub),
                                color = pal.accentText,
                                fontSize = 11.sp
                            )
                        }
                    }
                } else {
                    items(recents, key = { it.uriString }) { item ->
                        RecentRow(
                            item = item,
                            thumbnail = thumbs[item.uriString],
                            durationMs = durations[item.uriString] ?: item.durationMs,
                            onOpen = { onOpenFile(item) },
                            onRemove = {
                                recents = recents.filter { it.uriString != item.uriString }
                                RecentFiles.remove(context, item.uriString)
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeBackdrop(modifier: Modifier = Modifier) {
    val pal = homePalette()
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to pal.bgTop,
                    0.28f to pal.bgMid,
                    0.62f to pal.bgMid2,
                    1f to pal.bgBottom
                ),
                startY = 0f,
                endY = h
            )
        )

        // Sağ üstteki parıltı (AMOLED'de hiç çizilmez: piksel kapanır).
        if (pal.glow.alpha > 0f) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(pal.glow, pal.glow.copy(alpha = 0f)),
                    center = Offset(w * 0.9f, -h * 0.02f),
                    radius = w * 1.15f
                ),
                radius = w * 1.15f,
                center = Offset(w * 0.9f, -h * 0.02f)
            )
        }

        // Işıklı dalga bantları (görseldeki üst dalga efekti).
        fun band(centerY: Float, amplitude: Float, thickness: Float, alpha: Float, color: Color) {
            val path = Path().apply {
                moveTo(-w * 0.1f, centerY)
                cubicTo(
                    w * 0.18f, centerY - amplitude,
                    w * 0.42f, centerY + amplitude * 0.55f,
                    w * 0.62f, centerY - amplitude * 0.45f
                )
                cubicTo(
                    w * 0.78f, centerY - amplitude * 1.15f,
                    w * 0.9f, centerY + amplitude * 0.35f,
                    w * 1.1f, centerY - amplitude * 0.35f
                )
            }
            drawPath(
                path = path,
                brush = Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        color.copy(alpha = alpha),
                        color.copy(alpha = alpha * 0.85f),
                        Color.Transparent
                    )
                ),
                style = Stroke(width = thickness, cap = StrokeCap.Round)
            )
        }

        if (pal.bandAlpha > 0f) {
            // Işık bantları temaya göre ölçeklenir (KOYU'da eski parlaklık korunur).
            val f = pal.bandAlpha / 0.30f
            band(h * 0.105f, h * 0.035f, 96.dp.toPx(), 0.05f * f, Color(0xFFC084FC))
            band(h * 0.105f, h * 0.035f, 26.dp.toPx(), 0.22f * f, Color(0xFFC084FC))
            band(h * 0.150f, h * 0.028f, 10.dp.toPx(), 0.16f * f, Color(0xFF818CF8))
            band(h * 0.048f, h * 0.020f, 6.dp.toPx(), 0.10f * f, Color(0xFFA78BFA))
        }
    }
}

/**
 * Ana ekran kartı: gradyan zemin + arkada yumuşak ışık halkası + ortalanmış ikon/başlık.
 * Dokunma alanı kartın tamamıdır (tek dokunuşla ilgili ekran açılır).
 *
 * [iconSize] / [titleSp] / [subtitleSp] dışarıdan verilir; yatay modda kartlar daha
 * alçak ve simgeler daha küçük olduğu için ekran "dev simgeler" görüntüsünden kurtulur.
 */
@Composable
private fun FeatureCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    gradient: List<Color>,
    glow: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 44.dp,
    titleSp: Int = 20,
    subtitleSp: Int = 12
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(gradient))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val center = Offset(size.width / 2f, size.height * 0.34f)
            val radius = size.minDimension * 0.72f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glow.copy(alpha = 0.40f), Color.Transparent),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(iconSize))
            Spacer(Modifier.height(if (iconSize.value >= 40f) 10.dp else 6.dp))
            Text(
                title,
                color = Color.White,
                fontSize = titleSp.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = subtitleSp.sp,
                lineHeight = (subtitleSp + 3).sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RecentRow(
    item: RecentMedia,
    thumbnail: Bitmap?,
    durationMs: Long,
    onOpen: () -> Unit,
    onRemove: () -> Unit
) {
    val pal = homePalette()
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(pal.cardBg)
            .border(1.dp, pal.cardStroke, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(pal.thumbGradient)),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    if (item.isAudio) Icons.Filled.MusicNote else Icons.Filled.Movie,
                    null,
                    tint = pal.link,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.name,
                color = pal.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                buildRecentSubtitle(item.lastUsedMs, durationMs),
                color = pal.accentText,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.MoreVert,
                    stringResource(R.string.home_more_desc),
                    tint = pal.accentText,
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_action_play)) },
                    onClick = {
                        menuOpen = false
                        onOpen()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_action_remove)) },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    }
                )
            }
        }
    }
}

private fun buildRecentSubtitle(lastUsedMs: Long, durationMs: Long): String {
    val date = if (lastUsedMs > 0L) {
        SimpleDateFormat("dd.MM", Locale.getDefault()).format(Date(lastUsedMs))
    } else {
        ""
    }
    val duration = formatRecentDuration(durationMs)
    return when {
        date.isNotEmpty() && duration.isNotEmpty() -> "$date  ·  $duration"
        date.isNotEmpty() -> date
        else -> duration
    }
}

private fun formatRecentDuration(ms: Long): String {
    if (ms <= 0L) return ""
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
