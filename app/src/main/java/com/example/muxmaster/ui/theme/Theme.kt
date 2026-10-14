package com.example.muxmaster.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

// AMOLED artık Koyu temanın bir alt seçeneği değil, doğrudan seçilebilen 4.
// bir mod: Sistem / Açık / Koyu / AMOLED (tam siyah arka plan).
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

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

// AMOLED: Koyu temayla birebir aynı metin/vurgu renkleri, ama arka plan ve
// surface tam siyah (#000000) -- AMOLED ekranlarda o pikselleri tamamen
// kapatarak pil tasarrufu sağlar.
private val MuxAmoledColors = darkColorScheme(
    primary = PurpleDarkRaw, onPrimary = TextPrimaryDarkRaw,
    secondary = PurpleLightDarkRaw, onSecondary = BgAmoledRaw,
    background = BgAmoledRaw, onBackground = TextPrimaryDarkRaw,
    surface = SurfaceAmoledRaw, onSurface = TextPrimaryDarkRaw,
    surfaceVariant = SurfaceHighAmoledRaw, onSurfaceVariant = TextSecDarkRaw,
    outline = OutlineAmoledRaw, error = Red, onError = TextPrimaryDarkRaw
)

@Composable
fun MuxMasterTheme(themeMode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val variant = when (themeMode) {
        ThemeMode.LIGHT -> ThemeVariant.LIGHT
        ThemeMode.DARK -> ThemeVariant.DARK
        ThemeMode.AMOLED -> ThemeVariant.AMOLED
        ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) ThemeVariant.DARK else ThemeVariant.LIGHT
    }
    val colorScheme = when (variant) {
        ThemeVariant.LIGHT -> MuxLightColors
        ThemeVariant.DARK -> MuxDarkColors
        ThemeVariant.AMOLED -> MuxAmoledColors
    }
    CompositionLocalProvider(LocalThemeVariant provides variant) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MuxTypography,
            content = content
        )
    }
}
