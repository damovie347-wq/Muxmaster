package com.example.muxmaster.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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

private val MuxAmoledColors = darkColorScheme(
    primary = PurpleDarkRaw, onPrimary = TextPrimaryDarkRaw,
    secondary = PurpleLightDarkRaw, onSecondary = BgAmoledRaw,
    background = BgAmoledRaw, onBackground = TextPrimaryDarkRaw,
    surface = SurfaceAmoledRaw, onSurface = TextPrimaryDarkRaw,
    surfaceVariant = SurfaceHighAmoledRaw, onSurfaceVariant = TextSecDarkRaw,
    outline = OutlineAmoledRaw, error = Red, onError = TextPrimaryDarkRaw
)

private fun Context.findWindowActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

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
    val view = LocalView.current
    val context = LocalContext.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = context.findWindowActivity()
            val window = activity?.window
            if (window != null) {
                val bgArgb = colorScheme.background.toArgb()
                window.statusBarColor = bgArgb
                window.navigationBarColor = bgArgb
                window.setBackgroundDrawable(ColorDrawable(bgArgb))
                window.decorView.setBackgroundColor(bgArgb)
                val controller = WindowCompat.getInsetsController(window, view)
                val isLight = variant == ThemeVariant.LIGHT
                controller.isAppearanceLightStatusBars = isLight
                controller.isAppearanceLightNavigationBars = isLight
            }
        }
    }
    CompositionLocalProvider(LocalThemeVariant provides variant) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MuxTypography,
            content = content
        )
    }
}
