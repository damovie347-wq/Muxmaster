package com.example.muxmaster.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.muxmaster.ui.components.VideoCard
import com.example.muxmaster.ui.theme.*
import com.example.muxmaster.viewmodel.TrimViewModel
import java.util.Locale

private fun formatClock(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val m = safe / 60000
    val s = (safe % 60000) / 1000
    val msec = safe % 1000
    return String.format(Locale.US, "%02d:%02d.%03d", m, s, msec)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimScreen(
    viewModel: TrimViewModel,
    onPickVideo: () -> Unit,
    onPickOutputFolder: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val video = viewModel.videoFile
    val durationMs = video?.durationMs?.coerceAtLeast(1L) ?: 1L

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = { Text("Video Kırp", fontWeight = FontWeight.Bold, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, "Geri", tint = TextSec)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            VideoCard(
                video = video,
                isLoading = viewModel.isLoading,
                loadingMessage = viewModel.loadingMessage,
                onPickVideo = onPickVideo,
                onClear = viewModel::clearVideo
            )

            if (video != null) {
                Spacer(Modifier.height(20.dp))
                Text("Kırpma Aralığı", color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatClock(viewModel.trimStartMs), color = Purple, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text("Süre: " + formatClock(viewModel.trimEndMs - viewModel.trimStartMs), color = TextMuted, fontSize = 11.sp)
                    Text(formatClock(viewModel.trimEndMs), color = Purple, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }

                RangeSlider(
                    value = viewModel.trimStartMs.toFloat()..viewModel.trimEndMs.toFloat(),
                    onValueChange = { range ->
                        viewModel.setTrimRange(range.start.toLong(), range.endInclusive.toLong())
                    },
                    valueRange = 0f..durationMs.toFloat(),
                    enabled = !viewModel.isTrimming,
                    colors = SliderDefaults.colors(
                        thumbColor = Purple, activeTrackColor = Purple, inactiveTrackColor = SurfaceHigh
                    )
                )

                Text(
                    "Not: Kalite kaybı olmaması için yeniden kodlama yapılmaz; bu yüzden başlangıç noktası videonun en yakın kare (keyframe) sınırına hizalanabilir.",
                    color = TextMuted, fontSize = 10.sp
                )

                Spacer(Modifier.height(16.dp))

                TimeInputGroup(
                    label = "Başlangıç",
                    totalMs = viewModel.trimStartMs,
                    enabled = !viewModel.isTrimming,
                    onChange = viewModel::setTrimStart
                )

                Spacer(Modifier.height(12.dp))

                TimeInputGroup(
                    label = "Bitiş",
                    totalMs = viewModel.trimEndMs,
                    enabled = !viewModel.isTrimming,
                    onChange = viewModel::setTrimEnd
                )

                Spacer(Modifier.height(20.dp))
                Text("Çıktı", color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Surface, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Filled.Folder, null, tint = Amber, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        viewModel.outputFolderUri?.lastPathSegment ?: "Çıktı klasörü seçilmedi",
                        color = if (viewModel.outputFolderUri != null) TextPrimary else TextMuted,
                        fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onPickOutputFolder, enabled = !viewModel.isTrimming) {
                        Text(if (viewModel.outputFolderUri == null) "SEÇ" else "DEĞİŞTİR", color = PurpleLight, fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = viewModel.outputFileName,
                    onValueChange = viewModel::updateOutputFileName,
                    label = { Text("Çıktı dosya adı", fontSize = 11.sp) },
                    singleLine = true,
                    enabled = !viewModel.isTrimming,
                    textStyle = TextStyle(fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Purple, unfocusedBorderColor = Outline,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(Modifier.height(16.dp))

                if (viewModel.resultMessage != null && !viewModel.isTrimming) {
                    val bg = if (viewModel.isSuccess) Green.copy(alpha = 0.15f) else Red.copy(alpha = 0.15f)
                    val fg = if (viewModel.isSuccess) Green else Red
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bg, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(if (viewModel.isSuccess) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, null, tint = fg, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(viewModel.resultMessage ?: "", color = fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { if (viewModel.isSuccess) viewModel.dismissAndReset() else viewModel.clearResult() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Filled.Close, "Kapat", tint = fg.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                if (viewModel.isTrimming) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { viewModel.trimProgress / 100f },
                            modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = Purple, trackColor = SurfaceHigh
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("%" + viewModel.trimProgress, color = TextSec, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth().height(50.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {}, enabled = false,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(disabledContainerColor = SurfaceHigh)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = PurpleLight)
                            Spacer(Modifier.width(10.dp))
                            Text("Kırpılıyor… %" + viewModel.trimProgress, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        }
                        IconButton(
                            onClick = viewModel::cancelTrim,
                            modifier = Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Red.copy(alpha = 0.15f))
                        ) {
                            Icon(Icons.Filled.Close, "İptal", tint = Red, modifier = Modifier.size(22.dp))
                        }
                    }
                } else {
                    Button(
                        onClick = viewModel::startTrim,
                        enabled = viewModel.outputFolderUri != null && viewModel.trimEndMs > viewModel.trimStartMs,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Purple, disabledContainerColor = SurfaceHigh)
                    ) {
                        Icon(Icons.Filled.ContentCut, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("KIRPMAYA BAŞLA", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(24.dp))
            } else {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun TimeInputGroup(
    label: String,
    totalMs: Long,
    enabled: Boolean,
    onChange: (Long) -> Unit
) {
    val minutes = totalMs / 60000
    val seconds = (totalMs % 60000) / 1000
    val millis = totalMs % 1000

    Column {
        Text(label, color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeField(
                value = minutes.toString(),
                label = "Dakika",
                enabled = enabled,
                maxDigits = 4,
                modifier = Modifier.weight(1f)
            ) { newMin ->
                onChange(newMin * 60000 + seconds * 1000 + millis)
            }
            TimeField(
                value = seconds.toString(),
                label = "Saniye",
                enabled = enabled,
                maxDigits = 2,
                modifier = Modifier.weight(1f)
            ) { newSec ->
                onChange(minutes * 60000 + newSec.coerceAtMost(59) * 1000 + millis)
            }
            TimeField(
                value = millis.toString(),
                label = "Salise (ms)",
                enabled = enabled,
                maxDigits = 3,
                modifier = Modifier.weight(1f)
            ) { newMs ->
                onChange(minutes * 60000 + seconds * 1000 + newMs.coerceAtMost(999))
            }
        }
    }
}

@Composable
private fun TimeField(
    value: String,
    label: String,
    enabled: Boolean,
    maxDigits: Int,
    modifier: Modifier = Modifier,
    onValueCommit: (Long) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(maxDigits)
            onValueCommit(digits.toLongOrNull() ?: 0L)
        },
        label = { Text(label, fontSize = 10.sp) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = TextStyle(fontSize = 13.sp),
        modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Purple, unfocusedBorderColor = Outline,
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
        )
    )
}
