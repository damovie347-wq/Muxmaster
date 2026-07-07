package com.example.muxmaster.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalIsLightTheme = staticCompositionLocalOf { false }

internal val BgDarkRaw        = Color(0xFF0F0F10)
internal val SurfaceDarkRaw   = Color(0xFF1A1A22)
internal val SurfaceHighDarkRaw = Color(0xFF22222F)
internal val OutlineDarkRaw   = Color(0xFF2A2A35)
internal val TextPrimaryDarkRaw = Color(0xFFF0F0F5)
internal val TextSecDarkRaw   = Color(0xFF888899)
internal val TextMutedDarkRaw = Color(0xFF555566)
internal val PurpleDarkRaw       = Color(0xFF7C6FE0)
internal val PurpleLightDarkRaw  = Color(0xFFB3A6EE)

internal val BgLightRaw        = Color(0xFFF5F5F7)
internal val SurfaceLightRaw   = Color(0xFFFFFFFF)
internal val SurfaceHighLightRaw = Color(0xFFECECF1)
internal val OutlineLightRaw   = Color(0xFFE3E3E8)
internal val TextPrimaryLightRaw = Color(0xFF1D1D1F)
internal val TextSecLightRaw   = Color(0xFF86868B)
internal val TextMutedLightRaw = Color(0xFFAEAEB4)
internal val PurpleLightRaw       = Color(0xFF6E56CF)
internal val PurpleLightAccentLightRaw = Color(0xFF8F7FE0)

val Green = Color(0xFF34D399)
val Red   = Color(0xFFE24B4A)
val Blue  = Color(0xFF60A5FA)
val Amber = Color(0xFFFBBF24)

val BgDark: Color
    @Composable get() = if (LocalIsLightTheme.current) BgLightRaw else BgDarkRaw
val Surface: Color
    @Composable get() = if (LocalIsLightTheme.current) SurfaceLightRaw else SurfaceDarkRaw
val SurfaceHigh: Color
    @Composable get() = if (LocalIsLightTheme.current) SurfaceHighLightRaw else SurfaceHighDarkRaw
val Outline: Color
    @Composable get() = if (LocalIsLightTheme.current) OutlineLightRaw else OutlineDarkRaw
val TextPrimary: Color
    @Composable get() = if (LocalIsLightTheme.current) TextPrimaryLightRaw else TextPrimaryDarkRaw
val TextSec: Color
    @Composable get() = if (LocalIsLightTheme.current) TextSecLightRaw else TextSecDarkRaw
val TextMuted: Color
    @Composable get() = if (LocalIsLightTheme.current) TextMutedLightRaw else TextMutedDarkRaw
val Purple: Color
    @Composable get() = if (LocalIsLightTheme.current) PurpleLightRaw else PurpleDarkRaw
val PurpleLight: Color
    @Composable get() = if (LocalIsLightTheme.current) PurpleLightAccentLightRaw else PurpleLightDarkRaw
