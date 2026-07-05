package com.example.muxmaster.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val MuxDarkColors = darkColorScheme(
    primary = Purple,
    onPrimary = TextPrimary,
    secondary = PurpleLight,
    onSecondary = BgDark,
    background = BgDark,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextSec,
    outline = Outline,
    error = Red,
    onError = TextPrimary
)

@Composable
fun MuxMasterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MuxDarkColors,
        typography = MuxTypography,
        content = content
    )
}
