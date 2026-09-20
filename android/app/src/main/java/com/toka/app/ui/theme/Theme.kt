package com.toka.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private fun lightScheme(p: TokaColors) = lightColorScheme(
    primary            = p.pink,
    onPrimary          = p.cardBg,
    primaryContainer   = p.overdueBg,
    secondary          = p.violet,
    tertiary           = p.teal,
    background         = p.surfaceBg,
    surface            = p.cardBg,
    surfaceVariant     = p.cardBg,
    onBackground       = p.textPrimary,
    onSurface          = p.textPrimary,
    onSurfaceVariant   = p.textSecondary,
    outline            = p.textMuted,
    error              = p.skipRed,
    errorContainer     = p.skipBg,
)

private fun darkScheme(p: TokaColors) = darkColorScheme(
    primary            = p.pink,
    onPrimary          = p.surfaceBg,
    primaryContainer   = p.overdueBg,
    secondary          = p.violet,
    tertiary           = p.teal,
    background         = p.surfaceBg,
    surface            = p.cardBg,
    surfaceVariant     = p.cardBg,
    onBackground       = p.textPrimary,
    onSurface          = p.textPrimary,
    onSurfaceVariant   = p.textSecondary,
    outline            = p.textMuted,
    error              = p.skipRed,
    errorContainer     = p.skipBg,
)

@Composable
fun TokaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) DarkTokaColors else LightTokaColors
    CompositionLocalProvider(LocalTokaColors provides palette) {
        MaterialTheme(
            colorScheme = if (dark) darkScheme(palette) else lightScheme(palette),
            typography = TokaTypography,
            content = content
        )
    }
}
