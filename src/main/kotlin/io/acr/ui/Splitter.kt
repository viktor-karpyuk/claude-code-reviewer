package io.acr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.acr.data.PrefsRepo

/**
 * Ancho de un panel, recordado entre sesiones.
 *
 * Se guarda en preferencias porque acomodar los paneles es una decisión de trabajo —depende del
 * monitor y de qué estés mirando— y volver a arrastrarlos en cada arranque sería tratarla como si
 * fuera un capricho del momento.
 */
@Composable
fun rememberPaneWidth(prefs: PrefsRepo, key: String, default: Dp): MutableState<Dp> {
    val estado = remember(key) {
        val guardado = prefs.get("pane.$key")?.toFloatOrNull()
        mutableStateOf(guardado?.dp ?: default)
    }
    return estado
}

/**
 * La línea que separa dos paneles, arrastrable.
 *
 * Es más ancha que la línea que se ve —8dp de zona sensible contra 1dp de línea— porque acertarle
 * a un pelo de un píxel con el mouse es un ejercicio de puntería, no una interacción.
 *
 * @param min y [max] acotan el arrastre: sin tope se puede dejar un panel en cero y no hay forma
 *   de recuperarlo, porque justamente desapareció el borde del que se tira.
 */
@Composable
fun VerticalSplitter(
    width: MutableState<Dp>,
    prefs: PrefsRepo,
    key: String,
    min: Dp = 180.dp,
    max: Dp = 720.dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var arrastrando by remember { mutableStateOf(false) }
    Box(
        modifier
            .width(8.dp)
            .fillMaxHeight()
            .pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.E_RESIZE_CURSOR)))
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    val nuevo = width.value + with(density) { delta.toDp() }
                    width.value = nuevo.coerceIn(min, max)
                },
                onDragStarted = { arrastrando = true },
                onDragStopped = {
                    arrastrando = false
                    // Se guarda al soltar y no en cada píxel: son cientos de escrituras por
                    // arrastre, todas sobre la misma conexión que usa el resto de la app.
                    prefs.put("pane.$key", width.value.value.toString())
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(if (arrastrando) 2.dp else 1.dp)
                .fillMaxHeight()
                .background(
                    if (arrastrando) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                ),
        )
    }
}
