package com.example.muxmaster.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val MuxDarkColors = darkColorScheme(
    primary = PurpleDarkRaw, onPrimary = TextPrimaryDarkRaw,
    secondary = PurpleLightDarkRaw, onSecondary = BgDarkRaw,
    background = BgDarkRaw, onBackground = TextPrimaryDarkRaw,
    surface = SurfaceDarkRaw, onSurface = TextPrimaryDarkRaw,
    surfaceVariant = SurfaceHighDarkRaw, onSurfaceVariant = TextSecDarkRaw,
    outline = OutlineDarkRaw, error = Red, onError = TextPrimaryDarkRaw
)

private val MuxLightColors = lightColorScheme(
    primary = PurpleLightRaw, onPrimary = SurfaceLightRaw,
    secondary = PurpleLightAccentLightRaw, onSecondary = TextPrimaryLightRaw,
    background = BgLightRaw, onBackground = TextPrimaryLightRaw,
    surface = SurfaceLightRaw, onSurface = TextPrimaryLightRaw,
    surfaceVariant = SurfaceHighLightRaw, onSurfaceVariant = TextSecLightRaw,
    outline = OutlineLightRaw, error = Red, onError = SurfaceLightRaw
)

@Composable
fun MuxMasterTheme(themeMode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val isLight = when (themeMode) {
        ThemeMode.LIGHT -> true
        ThemeMode.DARK -> false
        ThemeMode.SYSTEM -> !isSystemInDarkTheme()
    }
    CompositionLocalProvider(LocalIsLightTheme provides isLight) {
        MaterialTheme(
            colorScheme = if (isLight) MuxLightColors else MuxDarkColors,
            typography = MuxTypography,
            content = content
        )
    }
}
