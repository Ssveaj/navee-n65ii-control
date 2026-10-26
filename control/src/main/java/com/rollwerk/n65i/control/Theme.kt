package com.rollwerk.n65i.control

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dark theme for N65i Control. A single teal-green accent on near-black surfaces. */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF4CD6B4),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF00513F),
    onPrimaryContainer = Color(0xFF6FF7D4),
    secondary = Color(0xFFB0CCC2),
    onSecondary = Color(0xFF1C352E),
    background = Color(0xFF0E1513),
    onBackground = Color(0xFFE1E3E0),
    surface = Color(0xFF151D1B),
    onSurface = Color(0xFFE1E3E0),
    surfaceVariant = Color(0xFF202A27),
    onSurfaceVariant = Color(0xFFBFC9C4),
    outline = Color(0xFF3A4541),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun N65iControlTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = Typography(), content = content)
}
