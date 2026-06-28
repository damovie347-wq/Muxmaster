package com.example.muxmaster.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
        modifier = Modifier
            .size(36.dp)
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
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
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconBadge(icon, iconColor)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            Text(subtitle, color = TextSec, fontSize = 12.sp, maxLines = 1)
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

@Composable
private fun DelayRow(delayMs: Long, onDelayChange: (Long) -> Unit) {
    var text by remember(delayMs) { mutableStateOf(delayMs.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Delay", color = TextSec, fontSize = 12.sp, modifier = Modifier.width(40.dp))
        Slider(
            value = delayMs.toFloat(),
            onValueChange = {
                onDelayChange(it.toLong())
                text = it.toLong().toString()
            },
            valueRange = -10000f..10000f,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(thumbColor = Purple, activeTrackColor = Purple)
        )
        OutlinedTextField(
            value = text,
            onValueChange = { newVal ->
                text = newVal
                val parsed = newVal.toLongOrNull()
                if (parsed != null) onDelayChange(parsed.coerceIn(-10000, 10000))
            },
            modifier = Modifier.width(86.dp),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
            suffix = { Text("ms", fontSize = 10.sp, color = TextMuted) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Purple,
                unfocusedBorderColor = Outline
            )
        )
    }
}

@Composable
private fun LanguageRow(language: String, onLanguageChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        LANGUAGE_CHOICES.forEach { (code, label) ->
            FilterChip(
                selected = language == code,
                onClick = { onLanguageChange(code) },
                label = { Text(label, fontSize = 11.sp) },
                modifier = Modifier.padding(end = 6.dp),
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
    onMoveDown: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = BorderStroke(1.dp, Outline),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            val title = track.title.ifBlank {
                if (track.source == TrackSource.EXISTING) "Ses Parçası #${track.existingStreamIndex}" else track.fileDisplayName
            }
            val subtitle = if (track.source == TrackSource.EXISTING) {
                "${track.existingCodec.uppercase()} · ${channelLabel(track.existingChannels)} · ${track.existingBitrate}"
            } else {
                track.fileDisplayName
            }
            TrackHeaderRow(
                icon = Icons.Filled.Audiotrack,
                iconColor = Blue,
                title = title,
                subtitle = subtitle,
                source = track.source,
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onRemove = onRemove
            )

            DelayRow(track.delayMs) { onUpdate(track.copy(delayMs = it)) }
            LanguageRow(track.language) { onUpdate(track.copy(language = it)) }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Varsayılan (Default)", color = TextSec, fontSize = 12.sp, modifier = Modifier.weight(1f))
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

@Composable
fun SubtitleTrackCard(
    track: SubtitleTrackItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onUpdate: (SubtitleTrackItem) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = BorderStroke(1.dp, Outline),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            val title = track.title.ifBlank {
                if (track.source == TrackSource.EXISTING) "Altyazı #${track.existingStreamIndex}" else track.fileDisplayName
            }
            val subtitle = if (track.source == TrackSource.EXISTING) {
                track.existingCodec.uppercase()
            } else {
                track.fileDisplayName
            }
            TrackHeaderRow(
                icon = Icons.Filled.Subtitles,
                iconColor = PurpleLight,
                title = title,
                subtitle = subtitle,
                source = track.source,
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onRemove = onRemove
            )

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

private fun channelLabel(channels: Int): String = when (channels) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "${channels}ch"
}
