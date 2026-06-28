package com.example.muxmaster.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.muxmaster.model.VideoFile
import com.example.muxmaster.ui.theme.*

@Composable
private fun InfoBadge(text: String, color: androidx.compose.ui.graphics.Color = PurpleLight) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "?"
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun formatSize(mb: Float): String {
    return if (mb >= 1024) "%.1f GB".format(mb / 1024f) else "%.0f MB".format(mb)
}

@Composable
fun VideoCard(
    video: VideoFile?,
    isLoading: Boolean,
    loadingMessage: String,
    onPickVideo: () -> Unit,
    onClear: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = BorderStroke(1.dp, Outline),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            if (video == null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Movie, contentDescription = null, tint = TextMuted, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (isLoading) loadingMessage.ifBlank { "Video analiz ediliyor..." } else "Henüz video seçilmedi",
                        color = TextSec,
                        modifier = Modifier.weight(1f)
                    )
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Purple)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onPickVideo,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Purple)
                ) {
                    Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("VİDEO SEÇ (MKV / MP4 / AVI)")
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Movie, contentDescription = null, tint = PurpleLight, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        video.displayName,
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Purple)
                    } else {
                        IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Kaldır", tint = TextSec)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    InfoBadge(video.videoCodec)
                    Spacer(Modifier.width(6.dp))
                    InfoBadge(video.resolution, Blue)
                    Spacer(Modifier.width(6.dp))
                    InfoBadge(formatDuration(video.durationMs), Amber)
                    Spacer(Modifier.width(6.dp))
                    InfoBadge(formatSize(video.fileSizeMb), Green)
                }
            }
        }
    }
}
