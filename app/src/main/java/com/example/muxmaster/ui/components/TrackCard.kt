package com.example.muxmaster.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.muxmaster.R
import com.example.muxmaster.model.AudioTrackItem
import com.example.muxmaster.model.SubtitleTrackItem
import com.example.muxmaster.model.TrackSource
import com.example.muxmaster.ui.theme.*
import kotlin.math.abs
import kotlin.math.max

private val LANGUAGE_CHOICES = listOf(
    "tur" to "TR", "eng" to "EN", "deu" to "DE", "fra" to "FR",
    "spa" to "ES", "ita" to "IT", "jpn" to "JA", "kor" to "KO",
    "rus" to "RU", "ara" to "AR", "und" to "?"
)

@Composable
private fun SourceBadge(source: TrackSource) {
    val (label, color) = if (source == TrackSource.EXISTING)
        stringResource(R.string.source_original) to Amber
    else
        stringResource(R.string.source_new) to Green
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun IconBadge(icon: ImageVector, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .clickable(onClick = onClick),
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
    isChecked: Boolean,
    selectionModeActive: Boolean,
    selectedCount: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    expanded: Boolean,
    onToggleChecked: () -> Unit,
    onToggleExpand: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onExport: () -> Unit,
    onExportSelected: (() -> Unit)? = null,
    onConvertAudio: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Sol taraftaki seçim kutucuğu (Checkbox)
        Checkbox(
            checked = isChecked,
            onCheckedChange = { onToggleChecked() },
            colors = CheckboxDefaults.colors(
                checkedColor = Purple,
                uncheckedColor = TextSec,
                checkmarkColor = Color.White
            ),
            modifier = Modifier.size(32.dp)
        )
        Spacer(Modifier.width(4.dp))
        IconBadge(icon, iconColor, onClick = onToggleChecked)
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            // Seçim modundayken (bazı veya tüm kutucuklar kaldırılmışken) satıra tıklamak
                            // doğrudan o altyazıyı/sesi seçer veya kaldırır; tümü seçiliyken detayları açar.
                            if (selectionModeActive) onToggleChecked() else onToggleExpand()
                        },
                        onLongClick = { showMenu = true }
                    )
            ) {
                Text(
                    text = title,
                    color = if (isChecked) TextPrimary else TextSec,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = TextSec,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Filled.FileDownload, null, tint = PurpleLight, modifier = Modifier.size(18.dp)) },
                    text = { Text(stringResource(R.string.action_export)) },
                    onClick = { showMenu = false; onExport() }
                )
                if (onExportSelected != null && selectedCount > 0) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Filled.FileDownload, null, tint = Amber, modifier = Modifier.size(18.dp)) },
                        text = { Text(stringResource(R.string.action_export_selected, selectedCount), fontWeight = FontWeight.SemiBold) },
                        onClick = { showMenu = false; onExportSelected() }
                    )
                }
                if (onConvertAudio != null) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Filled.Autorenew, null, tint = Green, modifier = Modifier.size(18.dp)) },
                        text = { Text(stringResource(R.string.action_convert_audio), fontWeight = FontWeight.SemiBold) },
                        onClick = { showMenu = false; onConvertAudio() }
                    )
                }
            }
        }
        IconButton(onClick = onToggleExpand, modifier = Modifier.size(28.dp)) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) stringResource(R.string.collapse_desc) else stringResource(R.string.expand_desc),
                tint = TextSec
            )
        }
        SourceBadge(source)
        Spacer(Modifier.width(2.dp))
        IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up_desc), tint = if (canMoveUp) TextSec else TextMuted)
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down_desc), tint = if (canMoveDown) TextSec else TextMuted)
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_desc), tint = Red)
        }
    }
}

private const val DELAY_BASE_RANGE_MS = 10000f

@Composable
private fun DelayRow(delayMs: Long, onDelayChange: (Long) -> Unit) {
    var sliderValue by remember(delayMs) { mutableFloatStateOf(delayMs.toFloat()) }
    var text by remember(delayMs) { mutableStateOf(delayMs.toString()) }

    val dynamicRange = remember(delayMs) { max(DELAY_BASE_RANGE_MS, abs(delayMs.toFloat()) * 1.2f) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Text(
            stringResource(R.string.delay_label),
            color = TextSec,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(44.dp)
        )

        Slider(
            value = sliderValue.coerceIn(-dynamicRange, dynamicRange),
            onValueChange = {
                sliderValue = it
                text = it.toLong().toString()
            },
            onValueChangeFinished = {
                val finalValue = sliderValue.toLong()
                text = finalValue.toString()
                onDelayChange(finalValue)
            },
            valueRange = -dynamicRange..dynamicRange,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
            colors = SliderDefaults.colors(thumbColor = Purple, activeTrackColor = Purple)
        )

        OutlinedTextField(
            value = text,
            onValueChange = { newVal ->
                text = newVal
                newVal.toLongOrNull()?.let { parsed ->
                    sliderValue = parsed.toFloat()
                    onDelayChange(parsed)
                }
            },
            modifier = Modifier.width(88.dp),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
            suffix = { Text("ms", fontSize = 10.sp, color = TextMuted) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Purple, unfocusedBorderColor = Outline)
        )
    }
}

private const val GAIN_MAX_DB = 15f

private fun dbToBoostPercent(db: Float): Float =
    (100.0 * Math.pow(10.0, db / 20.0) - 100.0).toFloat()

private fun percentToDb(percent: Float): Float =
    (20.0 * Math.log10(1.0 + percent / 100.0)).toFloat()

@Composable
private fun GainRow(gainDb: Float, onGainChange: (Float) -> Unit) {
    var showPercent by remember { mutableStateOf(false) }
    val maxPercent = remember { dbToBoostPercent(GAIN_MAX_DB) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Text(
            stringResource(R.string.gain_label),
            color = TextSec,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(86.dp)
        )
        Slider(
            value = if (showPercent) dbToBoostPercent(gainDb) else gainDb,
            onValueChange = { newVal -> onGainChange(if (showPercent) percentToDb(newVal) else newVal) },
            valueRange = if (showPercent) 0f..maxPercent else 0f..GAIN_MAX_DB,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
            colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber)
        )
        Text(
            if (showPercent) "+%.0f%%".format(java.util.Locale.US, dbToBoostPercent(gainDb))
            else "+%.1f dB".format(java.util.Locale.US, gainDb),
            color = TextSec,
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier.width(56.dp)
        )
        Text(
            if (showPercent) "%" else "dB",
            color = Amber,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier
                .clickable { showPercent = !showPercent }
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun LanguageRow(language: String, onLanguageChange: (String) -> Unit) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
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
    selectionModeActive: Boolean = false,
    selectedCount: Int = 0,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onUpdate: (AudioTrackItem) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onExport: () -> Unit,
    onExportSelected: (() -> Unit)? = null,
    onConvertAudio: (() -> Unit)? = null,
    isConvertingThisTrack: Boolean = false,
    convertingProgress: Int = 0,
    onCancelConvert: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = BorderStroke(
            1.dp,
            when {
                isConvertingThisTrack -> Purple
                track.isEnabled && selectionModeActive -> Purple.copy(alpha = 0.65f)
                else -> Outline
            }
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .clipToBounds()
            .alpha(if (track.isEnabled) 1f else 0.65f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
                .padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            val title = track.title.ifBlank {
                if (track.source == TrackSource.EXISTING) {
                    stringResource(R.string.audio_track_fallback_title, track.existingStreamIndex)
                } else {
                    track.fileDisplayName
                }
            }
            val subtitle = if (track.existingCodec.isNotBlank()) {
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
                isChecked = track.isEnabled,
                selectionModeActive = selectionModeActive,
                selectedCount = selectedCount,
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                expanded = expanded,
                onToggleChecked = { onUpdate(track.copy(isEnabled = !track.isEnabled)) },
                onToggleExpand = onToggleExpand,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onRemove = onRemove,
                onExport = onExport,
                onExportSelected = onExportSelected,
                onConvertAudio = onConvertAudio
            )

            if (isConvertingThisTrack) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(SurfaceHigh, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = PurpleLight)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.converting_label, convertingProgress),
                        color = PurpleLight,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(8.dp))
                    LinearProgressIndicator(
                        progress = { convertingProgress / 100f },
                        modifier = Modifier
                            .weight(1f)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Purple,
                        trackColor = Outline
                    )
                    if (onCancelConvert != null) {
                        Spacer(Modifier.width(6.dp))
                        IconButton(onClick = onCancelConvert, modifier = Modifier.size(22.dp)) {
                            Icon(Icons.Filled.Close, stringResource(R.string.cancel_desc), tint = Red, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }

            if (expanded) {
                Divider(
                    color = Outline.copy(alpha = 0.5f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                )
                Column(modifier = Modifier.fillMaxWidth().clipToBounds()) {
                    DelayRow(track.delayMs) { onUpdate(track.copy(delayMs = it)) }
                    LanguageRow(track.language) { onUpdate(track.copy(language = it)) }
                    GainRow(track.gainDb) { onUpdate(track.copy(gainDb = it)) }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                stringResource(R.string.label_default),
                                color = TextSec,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(6.dp))
                            Switch(
                                checked = track.isDefault,
                                onCheckedChange = { onUpdate(track.copy(isDefault = it)) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Purple, checkedTrackColor = PurpleLight.copy(alpha = 0.4f))
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                stringResource(R.string.label_active),
                                color = TextSec,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
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
}

@Composable
fun SubtitleTrackCard(
    track: SubtitleTrackItem,
    selectionModeActive: Boolean = false,
    selectedCount: Int = 0,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onUpdate: (SubtitleTrackItem) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onExport: () -> Unit,
    onExportSelected: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = BorderStroke(
            1.dp,
            if (track.isEnabled && selectionModeActive) Purple.copy(alpha = 0.65f) else Outline
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .clipToBounds()
            .alpha(if (track.isEnabled) 1f else 0.65f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
                .padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            val title = track.title.ifBlank {
                if (track.source == TrackSource.EXISTING) stringResource(R.string.subtitle_track_fallback_title, track.existingStreamIndex) else track.fileDisplayName
            }
            val subtitle = if (track.source == TrackSource.EXISTING) track.existingCodec.uppercase() else track.fileDisplayName

            TrackHeaderRow(
                icon = Icons.Filled.Subtitles,
                iconColor = PurpleLight,
                title = title,
                subtitle = subtitle,
                source = track.source,
                isChecked = track.isEnabled,
                selectionModeActive = selectionModeActive,
                selectedCount = selectedCount,
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                expanded = expanded,
                onToggleChecked = { onUpdate(track.copy(isEnabled = !track.isEnabled)) },
                onToggleExpand = onToggleExpand,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onRemove = onRemove,
                onExport = onExport,
                onExportSelected = onExportSelected
            )

            if (expanded) {
                Divider(
                    color = Outline.copy(alpha = 0.5f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                )
                Column(modifier = Modifier.fillMaxWidth().clipToBounds()) {
                    DelayRow(track.delayMs) { onUpdate(track.copy(delayMs = it)) }
                    LanguageRow(track.language) { onUpdate(track.copy(language = it)) }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                stringResource(R.string.label_default),
                                color = TextSec,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(6.dp))
                            Switch(
                                checked = track.isDefault,
                                onCheckedChange = { onUpdate(track.copy(isDefault = it)) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Purple, checkedTrackColor = PurpleLight.copy(alpha = 0.4f))
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                stringResource(R.string.label_active),
                                color = TextSec,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(6.dp))
                            Switch(
                                checked = track.isEnabled,
                                onCheckedChange = { onUpdate(track.copy(isEnabled = it)) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Green, checkedTrackColor = Green.copy(alpha = 0.4f))
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                stringResource(R.string.label_forced),
                                color = TextSec,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(6.dp))
                            Switch(
                                checked = track.isForced,
                                onCheckedChange = { onUpdate(track.copy(isForced = it)) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Amber, checkedTrackColor = Amber.copy(alpha = 0.4f))
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                stringResource(R.string.label_hi),
                                color = TextSec,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(6.dp))
                            Switch(
                                checked = track.isHearingImpaired,
                                onCheckedChange = { onUpdate(track.copy(isHearingImpaired = it)) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Blue, checkedTrackColor = Blue.copy(alpha = 0.4f))
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun channelLabel(channels: Int): String = when (channels) {
    1 -> stringResource(R.string.channel_mono)
    2 -> stringResource(R.string.channel_stereo)
    6 -> stringResource(R.string.channel_51)
    8 -> stringResource(R.string.channel_71)
    else -> stringResource(R.string.channel_generic, channels)
}
