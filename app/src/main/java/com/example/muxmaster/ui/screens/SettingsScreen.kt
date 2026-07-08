package com.example.muxmaster.ui.screens

import android.app.Activity
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
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
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = BorderStroke(1.dp, Outline),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    val themeOptions = listOf(
                        ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
                        ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
                        ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
                        ThemeMode.AMOLED to stringResource(R.string.settings_theme_amoled)
                    )
                    themeOptions.forEachIndexed { index, (mode, label) ->
                        if (index > 0) Divider(color = Outline, thickness = 0.5.dp)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onThemeModeChange(mode) }
                                .padding(horizontal = 14.dp, vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = themeMode == mode,
                                onClick = { onThemeModeChange(mode) },
                                colors = RadioButtonDefaults.colors(selectedColor = Purple, unselectedColor = TextMuted)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(label, color = TextPrimary, fontSize = 14.sp)
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
        }
    }
}
