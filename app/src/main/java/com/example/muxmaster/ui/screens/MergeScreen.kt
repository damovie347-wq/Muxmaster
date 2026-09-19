package com.example.muxmaster.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.muxmaster.model.MergeItem
import com.example.muxmaster.ui.theme.*
import com.example.muxmaster.viewmodel.MergeViewModel
import java.util.Locale

private fun formatDurationShort(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format(Locale.US, "%d:%02d", m, s)
}

private fun formatSizeShort(mb: Float): String =
    if (mb >= 1024f) String.format(Locale.US, "%.2f GB", mb / 1024f) else String.format(Locale.US, "%.1f MB", mb)

/**
 * Video Birleştirme (Merge) ekranı.
 *
 * Kullanıcı birden çok video seçer, sırasını yukarı/aşağı okları ile ayarlar ve
 * "Birleştirmeye Başla"ya basar. Birleştirme kayıpsızdır (bkz. MergeViewModel);
 * videolar uyumsuzsa (farklı codec/çözünürlük/ses) işlem başlamadan önce açık bir
 * uyarı gösterilir.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeScreen(
    viewModel: MergeViewModel,
    onPickVideos: () -> Unit,
    onPickOutputFolder: () -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = { Text("Video Birleştir", fontWeight = FontWeight.Bold, color = TextPrimary) },
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

            // ── 1) VİDEOLAR (sıralı liste) ────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Videolar" + if (viewModel.items.isNotEmpty()) " (${viewModel.items.size})" else "",
                    color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                if (viewModel.items.isNotEmpty() && !viewModel.isMerging) {
                    TextButton(onClick = viewModel::clearAll, contentPadding = PaddingValues(0.dp)) {
                        Text("Tümünü Temizle", color = TextSec, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = BorderStroke(1.dp, Outline),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    if (viewModel.items.isEmpty() && !viewModel.isLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Movie, null, tint = TextMuted, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Henüz video seçilmedi. Birleştirmek için en az 2 video ekle.",
                                color = TextSec, fontSize = 12.sp, modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    } else {
                        viewModel.items.forEachIndexed { index, item ->
                            if (index > 0) Divider(color = Outline, thickness = 0.5.dp)
                            MergeItemRow(
                                index = index,
                                item = item,
                                isFirst = index == 0,
                                isLast = index == viewModel.items.size - 1,
                                enabled = !viewModel.isMerging,
                                onMoveUp = { viewModel.moveUp(item.id) },
                                onMoveDown = { viewModel.moveDown(item.id) },
                                onRemove = { viewModel.removeItem(item.id) }
                            )
                        }
                        if (viewModel.isLoading) {
                            if (viewModel.items.isNotEmpty()) Divider(color = Outline, thickness = 0.5.dp)
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Purple)
                                Spacer(Modifier.width(10.dp))
                                Text(viewModel.loadingMessage, color = TextSec, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = onPickVideos,
                        enabled = !viewModel.isLoading && !viewModel.isMerging,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Purple)
                    ) {
                        Icon(Icons.Filled.FileOpen, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (viewModel.items.isEmpty()) "Video Seç (Çoklu)" else "Daha Fazla Video Ekle")
                    }
                }
            }

            if (viewModel.items.size == 1) {
                Spacer(Modifier.height(6.dp))
                Text("Birleştirmek için en az bir video daha eklemelisin.", color = TextMuted, fontSize = 10.sp)
            }

            Spacer(Modifier.height(20.dp))

            // ── 2) ÇIKTI ────────────────────────────────────────────────────────
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
                TextButton(onClick = onPickOutputFolder, enabled = !viewModel.isMerging) {
                    Text(if (viewModel.outputFolderUri == null) "SEÇ" else "DEĞİŞTİR", color = PurpleLight, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = viewModel.outputFileName,
                onValueChange = viewModel::updateOutputFileName,
                label = { Text("Çıktı dosya adı", fontSize = 11.sp) },
                singleLine = true,
                enabled = !viewModel.isMerging,
                textStyle = TextStyle(fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Purple, unfocusedBorderColor = Outline,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                )
            )

            Text(
                "Not: Birleştirme kayıpsızdır (yeniden kodlama yapılmaz), bu yüzden çok hızlıdır. Bunun için tüm videoların video codec'i, çözünürlüğü ve ses formatı aynı olmalıdır; farklıysa başlamadan önce uyarı gösterilir.",
                color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(16.dp))

            // ── 3) SONUÇ ────────────────────────────────────────────────────────
            if (viewModel.resultMessage != null && !viewModel.isMerging) {
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

            // ── 4) BİRLEŞTİR / İPTAL ──────────────────────────────────────────
            if (viewModel.isMerging) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { viewModel.mergeProgress / 100f },
                        modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = Purple, trackColor = SurfaceHigh
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("%" + viewModel.mergeProgress, color = TextSec, fontSize = 12.sp)
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
                        Text("Birleştiriliyor… %" + viewModel.mergeProgress, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    }
                    IconButton(
                        onClick = viewModel::cancelMerge,
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
                    onClick = viewModel::startMerge,
                    enabled = viewModel.items.size >= 2 && viewModel.outputFolderUri != null,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple, disabledContainerColor = SurfaceHigh)
                ) {
                    Icon(Icons.Filled.CallMerge, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("BİRLEŞTİRMEYE BAŞLA", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MergeItemRow(
    index: Int,
    item: MergeItem,
    isFirst: Boolean,
    isLast: Boolean,
    enabled: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Purple.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text("${index + 1}", color = PurpleLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.displayName, color = TextPrimary, fontSize = 13.sp, maxLines = 1, fontWeight = FontWeight.Medium)
            val audioLabel = if (item.hasAudio) item.audioCodec.uppercase() else "sessiz"
            Text(
                "${item.videoCodec} • ${item.resolution} • $audioLabel • ${formatDurationShort(item.durationMs)} • ${formatSizeShort(item.fileSizeMb)}",
                color = TextSec, fontSize = 10.sp, maxLines = 1
            )
        }
        IconButton(onClick = onMoveUp, enabled = enabled && !isFirst, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Filled.KeyboardArrowUp, "Yukarı taşı",
                tint = if (enabled && !isFirst) TextSec else TextMuted.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(onClick = onMoveDown, enabled = enabled && !isLast, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Filled.KeyboardArrowDown, "Aşağı taşı",
                tint = if (enabled && !isLast) TextSec else TextMuted.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Filled.Delete, "Kaldır",
                tint = if (enabled) Red.copy(alpha = 0.85f) else TextMuted.copy(alpha = 0.4f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
