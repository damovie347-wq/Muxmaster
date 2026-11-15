package com.example.muxmaster.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.muxmaster.R
import com.example.muxmaster.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    outputFolderUri: Uri?,
    onPickOutputFolder: () -> Unit,
    onNavigateBack: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit
) {
    val context = LocalContext.current
    val currentLang = LocalConfiguration.current.locales[0].language

    LaunchedEffect(Unit) { loadSkipSeconds(context) }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
    var batteryOptimizationIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryOptimizationIgnored = isIgnoringBatteryOptimizations()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val languages = listOf(
        Triple("tr", stringResource(R.string.settings_language_tr), null),
        Triple("en", stringResource(R.string.settings_language_en), null),
        Triple("de", stringResource(R.string.settings_language_de), null)
    )

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.settings_nav_back_desc), tint = TextSec)
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
            // ── Dil ──────────────────────────────────────────────────────
            Text(
                stringResource(R.string.settings_language_title),
                color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = BorderStroke(1.dp, Outline),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    languages.forEachIndexed { index, (code, label, _) ->
                        if (index > 0) Divider(color = Outline, thickness = 0.5.dp)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
                                    (context as? Activity)?.recreate()
                                }
                                .padding(horizontal = 14.dp, vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = currentLang == code,
                                onClick = {
                                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
                                    (context as? Activity)?.recreate()
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = Purple, unselectedColor = TextMuted)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(label, color = TextPrimary, fontSize = 14.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Görünüm ──────────────────────────────────────────────────
            Text(
                stringResource(R.string.settings_theme_title),
                color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            // ── TEMA SEÇİMİ (GÖRSEL ÖNİZLEMELİ) ──────────────────────────
            // ÖNEMLİ DEĞİŞİKLİK: Tema artık yalnızca yazılı bir liste değil; her seçenek
            // kendi renklerini gösteren küçük bir önizleme kartı olarak sunulur.
            // Koyu / AMOLED (tam siyah) / Açık / Sistem seçenekleri tek bakışta görünür.
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = BorderStroke(1.dp, Outline),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        ThemePreviewTile(
                            label = stringResource(R.string.settings_theme_light),
                            previewBg = Color(0xFFF5F5F7),
                            previewSurface = Color(0xFFFFFFFF),
                            previewAccent = Color(0xFF6E56CF),
                            selected = themeMode == ThemeMode.LIGHT,
                            onClick = { onThemeModeChange(ThemeMode.LIGHT) },
                            modifier = Modifier.weight(1f)
                        )
                        ThemePreviewTile(
                            label = stringResource(R.string.settings_theme_dark),
                            previewBg = Color(0xFF0F0F10),
                            previewSurface = Color(0xFF1F1F28),
                            previewAccent = Color(0xFF7C6FE0),
                            selected = themeMode == ThemeMode.DARK,
                            onClick = { onThemeModeChange(ThemeMode.DARK) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        ThemePreviewTile(
                            label = stringResource(R.string.settings_theme_amoled),
                            previewBg = Color(0xFF000000),
                            previewSurface = Color(0xFF0A0A0C),
                            previewAccent = Color(0xFF7C6FE0),
                            selected = themeMode == ThemeMode.AMOLED,
                            onClick = { onThemeModeChange(ThemeMode.AMOLED) },
                            modifier = Modifier.weight(1f)
                        )
                        ThemePreviewTile(
                            label = stringResource(R.string.settings_theme_system),
                            previewBg = Color(0xFF20202A),
                            previewSurface = Color(0xFF2E2E3A),
                            previewAccent = Color(0xFFB3A6EE),
                            selected = themeMode == ThemeMode.SYSTEM,
                            onClick = { onThemeModeChange(ThemeMode.SYSTEM) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.settings_theme_desc),
                        color = TextMuted, fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Video Oynatıcı Atlama Süresi (Çift Tıklama & Sarma) ───────
            Text(
                "Video Oynatıcı Atlama Süresi",
                color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = BorderStroke(1.dp, Outline),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Video oynarken ekranın sağ/sol kısmına çift tıkladığında veya ileri/geri butonlarına bastığında kaç saniye atlanacağını seç (orta kısma çift tıklamak videoyu durdurur/oynatır).",
                        color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 10.dp)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SKIP_SECOND_OPTIONS.forEach { sec ->
                            val selected = skipSecondsState.intValue == sec
                            FilterChip(
                                selected = selected,
                                onClick = { saveSkipSeconds(context, sec) },
                                label = { Text("${sec} sn", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f),
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
            }

            Spacer(Modifier.height(24.dp))

            // ── Varsayılan Çıktı Klasörü ─────────────────────────────────
            Text(
                stringResource(R.string.settings_output_folder_title),
                color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = BorderStroke(1.dp, Outline),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        stringResource(R.string.settings_output_folder_desc),
                        color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 10.dp)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceHigh, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Filled.Folder, null, tint = Amber, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            outputFolderUri?.lastPathSegment ?: stringResource(R.string.settings_output_folder_none),
                            color = if (outputFolderUri != null) TextPrimary else TextMuted,
                            fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onPickOutputFolder) {
                            Text(
                                if (outputFolderUri == null) stringResource(R.string.action_select) else stringResource(R.string.action_change),
                                color = PurpleLight, fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Arka Plan Performansı ────────────────────────────────────
            Text(
                stringResource(R.string.settings_battery_title),
                color = TextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = BorderStroke(1.dp, Outline),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        stringResource(R.string.settings_battery_desc),
                        color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 10.dp)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceHigh, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Filled.BatteryChargingFull, null, tint = if (batteryOptimizationIgnored) Green else Amber, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (batteryOptimizationIgnored) stringResource(R.string.settings_battery_status_ok) else stringResource(R.string.settings_battery_status_restricted),
                            color = TextPrimary, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f)
                        )
                        if (!batteryOptimizationIgnored) {
                            TextButton(onClick = {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                runCatching { context.startActivity(intent) }
                            }) {
                                Text(stringResource(R.string.settings_battery_action), color = PurpleLight, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Tema önizleme kartı: seçeneğin kendi renklerini minik bir arayüz olarak gösterir.
 * Seçili kart mor çerçeve + onay simgesiyle işaretlenir.
 */
@Composable
private fun ThemePreviewTile(
    label: String,
    previewBg: Color,
    previewSurface: Color,
    previewAccent: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Purple.copy(alpha = 0.10f) else SurfaceHigh)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) Purple else Outline,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(previewBg)
                .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 6.dp, top = 6.dp)
                    .fillMaxWidth(0.55f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(previewAccent)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .fillMaxWidth(0.62f)
                    .height(22.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(previewSurface)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(16.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(previewAccent.copy(alpha = 0.9f))
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    null,
                    tint = Purple,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                label,
                color = if (selected) PurpleLight else TextSec,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}
