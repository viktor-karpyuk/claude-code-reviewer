package io.acr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class ThemePref { System, Dark, Light }

/**
 * Paleta oscura.
 *
 * Los tres niveles —fondo, superficie y superficie variante— estaban muy juntos y muy abajo: las
 * tarjetas no se despegaban del fondo y todo se leía como una masa oscura. Acá cada nivel sube un
 * escalón visible respecto del anterior, y el texto secundario gana contraste, que es lo que hacía
 * costar leer los datos de un PR.
 *
 * El fondo no se aclara del todo a propósito: el código se lee mejor sobre oscuro, y subir todo
 * cambiaría eso por un gris lavado.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FBEFF),
    onPrimary = Color(0xFF00214D),
    secondary = Color(0xFF9BD1A4),
    background = Color(0xFF13171F),
    surface = Color(0xFF1C2230),
    surfaceVariant = Color(0xFF2A3242),
    onSurface = Color(0xFFEDF1F8),
    // Antes B4BDCE: el texto secundario —autores, fechas, rutas— quedaba al borde de lo legible.
    onSurfaceVariant = Color(0xFFC5CEDE),
    outline = Color(0xFF6B7688),
    outlineVariant = Color(0xFF3A4356),
    error = Color(0xFFFF9A90),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5FBF),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF2E7D4F),
    background = Color(0xFFF4F6FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE6EBF3),
    onSurface = Color(0xFF11151E),
    onSurfaceVariant = Color(0xFF445063),
    outline = Color(0xFF8B95A6),
    outlineVariant = Color(0xFFCBD3E0),
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
