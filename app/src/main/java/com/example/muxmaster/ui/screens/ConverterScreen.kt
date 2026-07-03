package com.example.muxmaster.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.muxmaster.viewmodel.ConvertQueueItem
import com.example.muxmaster.viewmodel.ConvertStatus
import com.example.muxmaster.viewmodel.ConverterViewModel
import com.example.muxmaster.viewmodel.OutputFormat
import com.example.muxmaster.ui.theme.*

private val BITRATE_PRESETS = listOf(32, 64, 96, 128, 192, 256, 320)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen(
    viewModel: ConverterViewModel,
    onPickAudio: () -> Unit,
    onPickOutputFolder: () -> Unit,
    onNavigateToMux: () -> Unit
) {
    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = { Text("Ses Dönüştürücü", fontWeight = FontWeight.Bold, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateToMux) {
                        Icon(Icons.Filled.ArrowBack, "Muxlayıcıya dön", tint = TextSec)
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

            // ── 1) KAYNAK DOSYALAR (çoklu) ────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                SectionTitle("Kaynak Ses Dosyaları" + if (viewModel.queue.isNotEmpty()) " (${viewModel.queue.size})" else "")
                Spacer(Modifier.weight(1f))
                if (viewModel.queue.isNotEmpty() && !viewModel.isConverting) {
                    TextButton(onClick = viewModel::clearQueue, contentPadding = PaddingValues(0.dp)) {
                        Text("TÜMÜNÜ TEMİZLE", color = TextSec, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = BorderStroke(1.dp, Outline),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    if (viewModel.queue.isEmpty() && !viewModel.isLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.MusicNote, null, tint = TextMuted, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Henüz dosya seçilmedi", color = TextSec, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(12.dp))
                    } else {
                        viewModel.queue.forEachIndexed { index, item ->
                            if (index > 0) Divider(color = Outline, thickness = 0.5.dp)
                            QueueItemRow(
                                item = item,
                                onRemove = { viewModel.removeFromQueue(item.id) },
                                removeEnabled = !viewModel.isConverting
                            )
                        }
                        if (viewModel.isLoading) {
                            if (viewModel.queue.isNotEmpty()) Divider(color = Outline, thickness = 0.5.dp)
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Purple)
                                Spacer(Modifier.width(10.dp))
                                Text(viewModel.loadingMessage, color = TextSec, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = onPickAudio,
                        enabled = !viewModel.isLoading && !viewModel.isConverting,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Purple)
                    ) {
                        Icon(Icons.Filled.FileOpen, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (viewModel.queue.isEmpty()) "SES DOSYALARI SEÇ (çoklu seçilebilir)" else "DAHA FAZLA DOSYA EKLE")
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── 2) HEDEF FORMAT ───────────────────────────────────────────────
            SectionTitle("Hedef Format")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutputFormat.entries.forEach { fmt ->
                    val selected = viewModel.outputFormat == fmt
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.setOutputFormat(fmt) },
                        enabled = !viewModel.isConverting,
                        label = { Text(fmt.label, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Purple, selectedLabelColor = Color.White,
                            containerColor = SurfaceHigh, labelColor = TextSec
                        )
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── 3) HEDEF BİTRATE ──────────────────────────────────────────────
            SectionTitle("Hedef Bitrate (${viewModel.outputFormat.label})")

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BITRATE_PRESETS.forEach { preset ->
                    val selected = viewModel.bitrateKbpsText == preset.toString()
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.updateBitrateText(preset.toString()) },
                        enabled = !viewModel.isConverting,
                        label = { Text("${preset}k", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Purple, selectedLabelColor = Color.White,
                            containerColor = SurfaceHigh, labelColor = TextSec
                        )
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = viewModel.bitrateKbpsText,
                onValueChange = viewModel::updateBitrateText,
                label = { Text("Özel bitrate (kbps)", fontSize = 11.sp) },
                singleLine = true,
                enabled = !viewModel.isConverting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                suffix = { Text("kbps", fontSize = 12.sp, color = TextMuted) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Purple, unfocusedBorderColor = Outline,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                )
            )

            val parsedBr = viewModel.bitrateKbpsText.toIntOrNull()
            val brHint = when {
                parsedBr == null || parsedBr < 6 -> "⚠ Geçersiz (min. 6 kbps)"
                parsedBr < 48  -> "Çok düşük — sadece konuşma için"
                parsedBr < 96  -> "Düşük kalite müzik"
                parsedBr <= 128 -> "İyi kalite — önerilen"
                parsedBr <= 192 -> "Yüksek kalite"
                else -> "Çok yüksek — dosya büyük olur"
            }
            Text(brHint, color = if (parsedBr != null && parsedBr >= 6) TextSec else Red, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))

            Spacer(Modifier.height(20.dp))

            // ── 4) ÇIKTI KLASÖRÜ ──────────────────────────────────────────────
            SectionTitle("Çıktı Klasörü")
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
                TextButton(onClick = onPickOutputFolder, enabled = !viewModel.isConverting) {
                    Text(if (viewModel.outputFolderUri == null) "SEÇ" else "DEĞİŞTİR", color = PurpleLight, fontSize = 12.sp)
                }
            }
            Text(
                "Her dosya, orijinal adı + seçili formatın uzantısıyla bu klasöre kaydedilir.",
                color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(16.dp))

            // ── 5) SONUÇ ──────────────────────────────────────────────────────
            AnimatedVisibility(visible = viewModel.resultMessage != null && !viewModel.isConverting, enter = fadeIn(), exit = fadeOut()) {
                val msg = viewModel.resultMessage
                if (msg != null) {
                    val bg = if (viewModel.isSuccess) Green.copy(alpha = 0.15f) else Red.copy(alpha = 0.15f)
                    val fg = if (viewModel.isSuccess) Green else Red
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .background(bg, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(if (viewModel.isSuccess) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, null, tint = fg, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(msg, color = fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { if (viewModel.isSuccess) viewModel.dismissAndReset() else viewModel.clearResult() }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, "Kapat", tint = fg.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            // ── 6) DÖNÜŞTÜR / İPTAL ──────────────────────────────────────────
            if (viewModel.isConverting) {
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { viewModel.convertProgress / 100f },
                            modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = Purple, trackColor = SurfaceHigh
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("%${viewModel.convertProgress}", color = TextSec, fontSize = 12.sp)
                    }
                    if (viewModel.currentFileName.isNotBlank()) {
                        Text(
                            "İşleniyor: ${viewModel.currentFileName}",
                            color = TextMuted, fontSize = 11.sp, maxLines = 1,
                            modifier = Modifier.padding(top = 4.dp)
                        )
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
                            Text("Dönüştürülüyor... %${viewModel.convertProgress}", fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        }
                        IconButton(
                            onClick = viewModel::cancelConvert,
                            modifier = Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)).background(Red.copy(alpha = 0.15f))
                        ) {
                            Icon(Icons.Filled.Close, "İptal", tint = Red, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            } else {
                val pendingCount = viewModel.queue.count { it.status == ConvertStatus.PENDING || it.status == ConvertStatus.ERROR }
                Button(
                    onClick = viewModel::startConvert,
                    enabled = pendingCount > 0 && viewModel.outputFolderUri != null && (viewModel.bitrateKbpsText.toIntOrNull() ?: 0) >= 6,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple, disabledContainerColor = SurfaceHigh)
                ) {
                    Icon(Icons.Filled.Autorenew, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    val label = if (pendingCount > 1) "$pendingCount DOSYAYI" else "DOSYAYI"
                    Text("$label ${viewModel.outputFormat.label.uppercase()} FORMATINA DÖNÜŞTÜR", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun QueueItemRow(item: ConvertQueueItem, onRemove: () -> Unit, removeEnabled: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        if (item.status == ConvertStatus.CONVERTING) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Purple)
        } else {
            val (icon, tint) = when (item.status) {
                ConvertStatus.PENDING -> Icons.Filled.Schedule to TextMuted
                ConvertStatus.DONE -> Icons.Filled.CheckCircle to Green
                ConvertStatus.ERROR -> Icons.Filled.ErrorOutline to Red
                else -> Icons.Filled.Schedule to TextMuted
            }
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.displayName, color = TextPrimary, fontSize = 13.sp, maxLines = 1, fontWeight = FontWeight.Medium)
            val subtitle = when (item.status) {
                ConvertStatus.PENDING -> "${item.sourceCodec} · ${channelLabel(item.sourceChannels)} · %.1f MB".format(item.fileSizeMb)
                ConvertStatus.CONVERTING -> "Dönüştürülüyor... %${item.progress}"
                ConvertStatus.DONE -> "Tamamlandı — %.1f MB".format(item.outputSizeMb ?: 0f)
                ConvertStatus.ERROR -> item.errorMessage ?: "Hata"
            }
            Text(subtitle, color = if (item.status == ConvertStatus.ERROR) Red else TextSec, fontSize = 11.sp, maxLines = 1)
        }
        if (removeEnabled && item.status != ConvertStatus.CONVERTING) {
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, "Kaldır", tint = TextSec, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
}

private fun channelLabel(ch: Int) = when(ch) { 1->"Mono"; 2->"Stereo"; 6->"5.1"; 8->"7.1"; else->"${ch}ch" }
