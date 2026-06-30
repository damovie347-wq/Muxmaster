package com.example.muxmaster.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.example.muxmaster.viewmodel.ConverterViewModel
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

            // ── 1) KAYNAK DOSYA SEÇİMİ ────────────────────────────────────────
            SectionTitle("Kaynak Ses Dosyası")
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = CardDefaults.outlinedCardBorder().let {
                    androidx.compose.foundation.BorderStroke(1.dp, Outline)
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    val src = viewModel.sourceAudio
                    if (src == null) {
                        // Dosya seçilmemiş
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.MusicNote, null, tint = TextMuted, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (viewModel.isLoading) viewModel.loadingMessage.ifBlank { "Dosya analiz ediliyor..." }
                                else "Henüz dosya seçilmedi",
                                color = TextSec, modifier = Modifier.weight(1f)
                            )
                            if (viewModel.isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Purple)
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onPickAudio,
                            enabled = !viewModel.isLoading,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Purple)
                        ) {
                            Icon(Icons.Filled.FileOpen, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("SES DOSYASI SEÇ (MP3, AAC, FLAC, MKV…)")
                        }
                    } else {
                        // Dosya seçilmiş → bilgi kartı
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.MusicNote, null, tint = PurpleLight, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(src.displayName, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            IconButton(onClick = viewModel::clearAudio, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Close, "Kaldır", tint = TextSec)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row {
                            InfoChip(src.sourceCodec)
                            Spacer(Modifier.width(6.dp))
                            InfoChip(channelLabel(src.sourceChannels), Blue)
                            Spacer(Modifier.width(6.dp))
                            InfoChip(src.sourceBitrateLabel, Amber)
                            Spacer(Modifier.width(6.dp))
                            InfoChip("%.1f MB".format(src.fileSizeMb), Green)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── 2) HEDEF BİTRATE ──────────────────────────────────────────────
            SectionTitle("Hedef Bitrate (Opus)")

            // Preset butonları
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BITRATE_PRESETS.forEach { preset ->
                    val selected = viewModel.bitrateKbpsText == preset.toString()
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.updateBitrateText(preset.toString()) },
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

            // Serbest girdi
            OutlinedTextField(
                value = viewModel.bitrateKbpsText,
                onValueChange = viewModel::updateBitrateText,
                label = { Text("Özel bitrate (kbps)", fontSize = 11.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                suffix = { Text("kbps", fontSize = 12.sp, color = TextMuted) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Purple, unfocusedBorderColor = Outline,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                )
            )

            // Seçili bitrate bilgisi
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

            // ── 3) ÇIKTI AYARLARI ────────────────────────────────────────────
            SectionTitle("Çıktı")
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
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = viewModel.outputFileName,
                onValueChange = viewModel::updateOutputFileName,
                label = { Text("Çıktı dosya adı", fontSize = 11.sp) },
                singleLine = true,
                enabled = !viewModel.isConverting,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Purple, unfocusedBorderColor = Outline,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                )
            )

            Spacer(Modifier.height(16.dp))

            // ── 4) SONUÇ KARTI ────────────────────────────────────────────────
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

            // ── 5) DÖNÜŞTÜR / İPTAL BUTONU ───────────────────────────────────
            if (viewModel.isConverting) {
                // İlerleme
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
                Button(
                    onClick = viewModel::startConvert,
                    enabled = viewModel.sourceAudio != null && viewModel.outputFolderUri != null && (viewModel.bitrateKbpsText.toIntOrNull() ?: 0) >= 6,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple, disabledContainerColor = SurfaceHigh)
                ) {
                    Icon(Icons.Filled.Autorenew, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("OPUS'A DÖNÜŞTÜR", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun InfoChip(text: String, color: Color = PurpleLight) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

private fun channelLabel(ch: Int) = when(ch) { 1->"Mono"; 2->"Stereo"; 6->"5.1"; 8->"7.1"; else->"${ch}ch" }
