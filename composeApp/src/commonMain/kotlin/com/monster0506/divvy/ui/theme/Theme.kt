package com.monster0506.divvy.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object DivvyColors {
    val Page = Color(0xFFECEEF0)
    val Surface = Color(0xFFFFFFFF)
    val Ink = Color(0xFF16191D)
    val Dim = Color(0xFF666D75)
    val Line = Color(0xFFE2E5E8)
    val Outline = Color(0xFFC4C9CE)
    val Accent = Color(0xFF1F6F4A)
    val Warn = Color(0xFF9A5B00)
    val WarnBg = Color(0xFFFBF1DE)
}

private val scheme = lightColorScheme(
    primary = DivvyColors.Accent,
    onPrimary = Color.White,
    background = DivvyColors.Surface,
    surface = DivvyColors.Surface,
    onSurface = DivvyColors.Ink,
    outline = DivvyColors.Outline,
)

@Composable
fun DivvyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
