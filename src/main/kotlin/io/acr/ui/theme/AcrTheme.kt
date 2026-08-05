package io.acr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class ThemePref { System, Dark, Light }

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FB3FF),
    onPrimary = Color(0xFF00214D),
    secondary = Color(0xFF9BD1A4),
    background = Color(0xFF10131A),
    surface = Color(0xFF171B24),
    surfaceVariant = Color(0xFF222836),
    onSurface = Color(0xFFE3E7EF),
    onSurfaceVariant = Color(0xFFB4BDCE),
    error = Color(0xFFFF8A80),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5FBF),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF2E7D4F),
    background = Color(0xFFF7F8FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9EDF4),
    onSurface = Color(0xFF12161F),
    onSurfaceVariant = Color(0xFF4A5567),
    error = Color(0xFFB3261E),
)

@Composable
fun AcrTheme(pref: ThemePref, content: @Composable () -> Unit) {
    val dark = when (pref) {
        ThemePref.System -> isSystemInDarkTheme()
        ThemePref.Dark -> true
        ThemePref.Light -> false
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
