package com.example.muxmaster.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.muxmaster.model.AudioTrackItem
import com.example.muxmaster.model.SubtitleTrackItem
import com.example.muxmaster.model.TrackSource
import com.example.muxmaster.ui.theme.*

private val LANGUAGE_CHOICES = listOf(
    "tur" to "TR", "eng" to "EN", "deu" to "DE", "fra" to "FR",
    "spa" to "ES", "ita" to "IT", "jpn" to "JA", "kor" to "KO",
    "rus" to "RU", "ara" to "AR", "und" to "?"
)

@Composable
private fun SourceBadge(source: TrackSource) {
    val (label, color) = if (source == TrackSource.EXISTING) "ORİJİNAL" to Amber else "YENİ" to Green
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun IconBadge(icon: ImageVector, color: Color) {
    Box(
        modifier = Modifier.size(36.dp).background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TrackHeaderRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    source: TrackSource,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    // --- Collapse/expand toggle (new) ---
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconBadge(icon, iconColor)
        Spacer(Modifier.width(10.dp))
        // Title/subtitle column is now clickable to toggle collapse, so the
        // whole header area (not just the tiny icon) can be tapped.
        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onToggleExpand)
        ) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            Text(subtitle, color = TextSec, fontSize = 12.sp, maxLines = 1)
        }
        // Collapse/expand button - "next to" the title, before the badge/controls.
        IconButton(onClick = onToggleExpand, modifier = Modifier.size(32.dp)) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Daralt" else "Genişlet",
                tint = TextSec
            )
        }
        SourceBadge(source)
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Yukarı taşı", tint = if (canMoveUp) TextSec else TextMuted)
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Aşağı taşı", tint = if (canMoveDown) TextSec else TextMuted)
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Sil", tint = Red)
        }
    }
}

private const val DELAY_RANGE_MS = 10000f

/**
 * PERFORMANS NOTU (delay slider akıcılığı):
 * Eski kodda Slider'ın onValueChange'i HEM kendi pozisyonunu (localValue) HEM DE
 * yanındaki OutlinedTextField'ın metnini (text) aynı anda güncelliyordu. İkisi de
 * aynı composable fonksiyonun gövdesinde okunduğu için, parmak her piksel
 * kaydığında (saniyede onlarca-yüzlerce kez) TÜM OutlinedTextField yeniden
 * ölçülüp yeniden çiziliyordu (cursor, IME, decoration box, text layout dahil) -
 * bu da gerçek cihazlarda sürüklemeyi 3-10fps'e kadar düşürüyordu.
 *
 * Çözüm: Sürükleme sırasında sadece ÇOK ucuz bir "değer balonu" (Text) güncellenir;
 * ağır OutlinedTextField'ın `value`'su parmak kaldırılana kadar (onValueChangeFinished)
 * DEĞİŞMEZ. Compose, değişmeyen (stable) parametrelerle çağrılan composable'ları
 * otomatik olarak atlar (skip), böylece TextField sürükleme boyunca hiç yeniden
 * render edilmez ve Slider donanım hızında (60/90/120fps, cihaza bağlı) akar.
 */
@Composable
private fun DelayRow(delayMs: Long, onDelayChange: (Long) -> Unit) {
    // Slider'ın anlık pozisyonu - primitive-specialized state (autoboxing yok)
    var sliderValue by remember(delayMs) { mutableFloatStateOf(delayMs.toFloat()) }
    // TextField'ın gösterdiği metin - SADECE sürükleme bitince veya elle yazılınca değişir
    var text by remember(delayMs) { mutableStateOf(delayMs.toString()) }
    var isDragging by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Delay", color = TextSec, fontSize = 12.sp, modifier = Modifier.width(40.dp))

        BoxWithConstraints(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Slider(
                value = sliderValue,
                onValueChange = { isDragging = true; sliderValue = it },
                onValueChangeFinished = {
                    isDragging = false
                    val finalValue = sliderValue.toLong()
                    text = finalValue.toString()
                    onDelayChange(finalValue)
                },
                valueRange = -DELAY_RANGE_MS..DELAY_RANGE_MS,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(thumbColor = Purple, activeTrackColor = Purple)
            )

            // Sürüklerken parmağın üstünde akan hafif değer balonu (tek bir Text -
            // TextField'a kıyasla neredeyse bedava, akıcılığı bozmaz).
            if (isDragging) {
                val fraction = ((sliderValue + DELAY_RANGE_MS) / (2 * DELAY_RANGE_MS)).coerceIn(0f, 1f)
                val bubbleWidth = 46.dp
                val bubbleX = (maxWidth - bubbleWidth) * fraction
                Box(
                    modifier = Modifier
                        .offset(x = bubbleX, y = (-30).dp)
                        .width(bubbleWidth)
                        .background(Purple, RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${sliderValue.toLong()}ms",
                        color = Color.White,
                        fontSize = 10.sp,
                        maxLines = 1,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        OutlinedTextField(
            value = text,
            onValueChange = { newVal ->
                text = newVal
                newVal.toLongOrNull()?.let { parsed ->
                    val coerced = parsed.coerceIn(-10000, 10000)
                    sliderValue = coerced.toFloat()
                    onDelayChange(coerced)
                }
            },
            modifier = Modifier.width(86.dp),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
            suffix = { Text("ms", fontSize = 10.sp, color = TextMuted) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple, unfocusedBorderColor = Outline)
        )
    }
}

@Composable
private fun LanguageRow(language: String, onLanguageChange: (String) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(LANGUAGE_CHOICES, key = { it.first }) { (code, label) ->
            FilterChip(
                selected = language == code,
                onClick = { onLanguageChange(code) },
                label = { Text(label, fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Purple,
                    selectedLabelColor = Color.White,
                    containerColor = SurfaceHigh,
                    labelColor = TextSec
                )
            )
        }
    }
}

@Composable
fun AudioTrackCard(
    track: AudioTrackItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onUpdate: (AudioTrackItem) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = BorderStroke(1.dp, Outline),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            val title = track.title.ifBlank {
                if (track.source == TrackSource.EXISTING) "Ses Parçası #${track.existingStreamIndex}" else track.fileDisplayName
            }
            val subtitle = if (track.source == TrackSource.EXISTING) {
                "${track.existingCodec.uppercase()} · ${channelLabel(track.existingChannels)} · ${track.existingBitrate}"
            } else track.fileDisplayName

            // Collapse/expand state, keyed by track.id so it survives reordering
            // but resets correctly if the track list changes. Open by default.
            var expanded by remember(track.id) { mutableStateOf(true) }

            TrackHeaderRow(
                icon = Icons.Filled.Audiotrack, iconColor = Blue, title = title, subtitle = subtitle,
                source = track.source, canMoveUp = canMoveUp, canMoveDown = canMoveDown,
                expanded = expanded, onToggleExpand = { expanded = !expanded },
                onMoveUp = onMoveUp, onMoveDown = onMoveDown, onRemove = onRemove
            )

            // --- Collapsible body: Delay slider, language row and switches ---
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    DelayRow(track.delayMs) { onUpdate(track.copy(delayMs = it)) }
                    LanguageRow(track.language) { onUpdate(track.copy(language = it)) }

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Varsayılan", color = TextSec, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = track.isDefault,
                            onCheckedChange = { onUpdate(track.copy(isDefault = it)) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Purple, checkedTrackColor = PurpleLight.copy(alpha = 0.4f))
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Aktif", color = TextSec, fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        Switch(
                            checked = track.isEnabled,
                            onCheckedChange = { onUpdate(track.copy(isEnabled = it)) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Green, checkedTrackColor = Green.copy(alpha = 0.4f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SubtitleTrackCard(
    track: SubtitleTrackItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onUpdate: (SubtitleTrackItem) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = BorderStroke(1.dp, Outline),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            val title = track.title.ifBlank {
                if (track.source == TrackSource.EXISTING) "Altyazı #${track.existingStreamIndex}" else track.fileDisplayName
            }
            val subtitle = if (track.source == TrackSource.EXISTING) track.existingCodec.uppercase() else track.fileDisplayName

            // Collapse/expand state, keyed by track.id. Open by default.
            var expanded by remember(track.id) { mutableStateOf(true) }

            TrackHeaderRow(
                icon = Icons.Filled.Subtitles, iconColor = PurpleLight, title = title, subtitle = subtitle,
                source = track.source, canMoveUp = canMoveUp, canMoveDown = canMoveDown,
                expanded = expanded, onToggleExpand = { expanded = !expanded },
                onMoveUp = onMoveUp, onMoveDown = onMoveDown, onRemove = onRemove
            )

            // --- Collapsible body: Delay slider, language row and switches ---
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    DelayRow(track.delayMs) { onUpdate(track.copy(delayMs = it)) }
                    LanguageRow(track.language) { onUpdate(track.copy(language = it)) }

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Default", color = TextSec, fontSize = 11.sp)
                        Switch(
                            checked = track.isDefault,
                            onCheckedChange = { onUpdate(track.copy(isDefault = it)) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Purple, checkedTrackColor = PurpleLight.copy(alpha = 0.4f))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Forced", color = TextSec, fontSize = 11.sp)
                        Switch(
                            checked = track.isForced,
                            onCheckedChange = { onUpdate(track.copy(isForced = it)) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Amber, checkedTrackColor = Amber.copy(alpha = 0.4f))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("HI", color = TextSec, fontSize = 11.sp)
                        Switch(
                            checked = track.isHearingImpaired,
                            onCheckedChange = { onUpdate(track.copy(isHearingImpaired = it)) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Blue, checkedTrackColor = Blue.copy(alpha = 0.4f))
                        )
                        Spacer(Modifier.weight(1f))
                        Text("Aktif", color = TextSec, fontSize = 11.sp)
                        Switch(
                            checked = track.isEnabled,
                            onCheckedChange = { onUpdate(track.copy(isEnabled = it)) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Green, checkedTrackColor = Green.copy(alpha = 0.4f))
                        )
                    }
                }
            }
        }
    }
}

private fun channelLabel(channels: Int): String = when (channels) {
    1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${channels}ch"
}
