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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.muxmaster.data.MediaThumbs
import com.example.muxmaster.data.RecentFiles
import com.example.muxmaster.data.RecentMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Ana ekranın sabit renk paleti (koyu mor/lacivert "premium" görünüm).
private val HomeBgTop = Color(0xFF0B0724)
private val HomeBgMid = Color(0xFF1A0B3E)
private val HomeBgBottom = Color(0xFF08050F)
private val HomeAccentText = Color(0xFFB9AEE6)
private val HomeLink = Color(0xFFB9A6F7)
private val HomeCardBg = Color(0xFF171130)
private val HomeCardStroke = Color(0xFF2A2150)

/**
 * UYGULAMANIN ANA EKRANI.
 *
 * Büyük, tek dokunuşla erişilebilen kartlar: Mux · Kes · Birleştir · Oynatıcı,
 * altında tam genişlikte "Ses Dönüştürme" kartı ve "Son Dosyalar" listesi.
 * Kartlara dokunulduğunda ilgili ekran, hiçbir ayar bozulmadan açılır.
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

    Box(modifier = Modifier.fillMaxSize()) {
        HomeBackdrop(Modifier.fillMaxSize())

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "MuxMaster",
                            color = Color.White,
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Video ve ses araçların",
                            color = HomeAccentText,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, "Ayarlar", tint = Color(0xFFCFC6F5))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FeatureCard(
                        title = "Mux",
                        subtitle = "Ses ve altyazı\nparçalarını ayır",
                        icon = Icons.Filled.Extension,
                        gradient = listOf(Color(0xFF8B3DFF), Color(0xFF4C1D95)),
                        glow = Color(0xFFB388FF),
                        onClick = onOpenMux,
                        modifier = Modifier.weight(1f).height(160.dp)
                    )
                    FeatureCard(
                        title = "Kes",
                        subtitle = "Videoyu kes",
                        icon = Icons.Filled.ContentCut,
                        gradient = listOf(Color(0xFF0E8A86), Color(0xFF0B3B48)),
                        glow = Color(0xFF4DE3DC),
                        onClick = onOpenTrim,
                        modifier = Modifier.weight(1f).height(160.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FeatureCard(
                        title = "Birleştir",
                        subtitle = "Videoları birleştir",
                        icon = Icons.Filled.Link,
                        gradient = listOf(Color(0xFF1F5BE0), Color(0xFF0B2A6B)),
                        glow = Color(0xFF7FB0FF),
                        onClick = onOpenMerge,
                        modifier = Modifier.weight(1f).height(160.dp)
                    )
                    FeatureCard(
                        title = "Oynatıcı",
                        subtitle = "Medyanızı oynat",
                        icon = Icons.Filled.PlayCircle,
                        gradient = listOf(Color(0xFFF97316), Color(0xFF7C2D12)),
                        glow = Color(0xFFFFC46B),
                        onClick = onOpenPlayer,
                        modifier = Modifier.weight(1f).height(160.dp)
                    )
                }
                Spacer(Modifier.height(14.dp))
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp)
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
                            .size(52.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.GraphicEq,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Ses Dönüştürme",
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Sesi başka bir formata dönüştür",
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
                Spacer(Modifier.height(22.dp))
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Son Dosyalar",
                        color = Color.White,
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
                            "Tümünü Gör",
                            color = HomeLink,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.ArrowForward,
                            null,
                            tint = HomeLink,
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
                            .background(HomeCardBg)
                            .border(1.dp, HomeCardStroke, RoundedCornerShape(16.dp))
                            .padding(vertical = 22.dp, horizontal = 16.dp)
                    ) {
                        Icon(
                            Icons.Filled.Movie,
                            null,
                            tint = Color(0xFF6D5AA8),
                            modifier = Modifier.size(30.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Henüz dosya açmadın",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Açtığın videolar burada listelenir.",
                            color = HomeAccentText,
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

@Composable
private fun HomeBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to HomeBgTop,
                    0.28f to HomeBgMid,
                    0.62f to Color(0xFF120A2C),
                    1f to HomeBgBottom
                ),
                startY = 0f,
                endY = h
            )
        )

        // Sağ üstteki mor parıltı.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x80A855F7), Color(0x00A855F7)),
                center = Offset(w * 0.9f, -h * 0.02f),
                radius = w * 1.15f
            ),
            radius = w * 1.15f,
            center = Offset(w * 0.9f, -h * 0.02f)
        )

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

        band(h * 0.105f, h * 0.035f, 96.dp.toPx(), 0.05f, Color(0xFFC084FC))
        band(h * 0.105f, h * 0.035f, 26.dp.toPx(), 0.22f, Color(0xFFC084FC))
        band(h * 0.150f, h * 0.028f, 10.dp.toPx(), 0.16f, Color(0xFF818CF8))
        band(h * 0.048f, h * 0.020f, 6.dp.toPx(), 0.10f, Color(0xFFA78BFA))
    }
}

/**
 * Ana ekran kartı: gradyan zemin + arkada yumuşak ışık halkası + ortalanmış ikon/başlık.
 * Dokunma alanı kartın tamamıdır (tek dokunuşla ilgili ekran açılır).
 */
@Composable
private fun FeatureCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    gradient: List<Color>,
    glow: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
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
                .padding(horizontal = 10.dp, vertical = 14.dp)
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(10.dp))
            Text(
                title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                lineHeight = 15.sp,
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
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(HomeCardBg)
            .border(1.dp, HomeCardStroke, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.linearGradient(listOf(Color(0xFF3B2A6B), Color(0xFF221640)))
                ),
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
                    tint = Color(0xFFB9A6F7),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.name,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                buildRecentSubtitle(item.lastUsedMs, durationMs),
                color = HomeAccentText,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.MoreVert,
                    "Diğer seçenekler",
                    tint = Color(0xFF9C8FD6),
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Oynat") },
                    onClick = {
                        menuOpen = false
                        onOpen()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Listeden çıkar") },
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
