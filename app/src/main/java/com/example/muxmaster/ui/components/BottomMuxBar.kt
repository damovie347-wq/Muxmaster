package com.example.muxmaster.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.muxmaster.ui.theme.*

@Composable
fun BottomMuxBar(
    outputFolderUri: Uri?,
    outputFileName: String,
    isMuxing: Boolean,
    muxProgress: Int,
    resultMessage: String?,
    isSuccess: Boolean,
    canStartMux: Boolean,
    onPickFolder: () -> Unit,
    onFileNameChange: (String) -> Unit,
    onStart: () -> Unit
) {
    Surface(color = BgDark, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {

            // Klasör satırı
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, tint = Amber, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    outputFolderUri?.lastPathSegment ?: "Çıktı klasörü seçilmedi",
                    color = if (outputFolderUri != null) TextPrimary else TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onPickFolder, enabled = !isMuxing) {
                    Text(if (outputFolderUri == null) "SEÇ" else "DEĞİŞTİR", color = PurpleLight, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = outputFileName,
                onValueChange = onFileNameChange,
                label = { Text("Çıktı dosya adı", fontSize = 11.sp) },
                singleLine = true,
                enabled = !isMuxing,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Purple,
                    unfocusedBorderColor = Outline,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            if (isMuxing) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { muxProgress / 100f },
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Purple,
                        trackColor = SurfaceHigh
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("%$muxProgress", color = TextSec, fontSize = 12.sp)
                }
            }

            if (resultMessage != null) {
                Spacer(Modifier.height(10.dp))
                val bg = if (isSuccess) Green.copy(alpha = 0.15f) else Red.copy(alpha = 0.15f)
                val fg = if (isSuccess) Green else Red
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bg, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(
                        if (isSuccess) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(resultMessage, color = fg, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(10.dp))

            Button(
                onClick = onStart,
                enabled = canStartMux && !isMuxing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Purple,
                    disabledContainerColor = SurfaceHigh
                )
            ) {
                if (isMuxing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text("İşleniyor... %$muxProgress", fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("START MUXING", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
