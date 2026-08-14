package io.acr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp

/** Una entrada de la barra: su ícono, su nombre y si está activa. */
data class ActivityItem(
    val icon: ImageVector,
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/**
 * La barra de actividad, al estilo de VS Code: una tira angosta de íconos pegada al borde.
 *
 * Reemplaza a los cuatro `NavigationRailItem` que vivían al pie de la lista de repositorios, donde
 * competían por el mismo espacio: con las etiquetas puestas no entraban en el ancho de la barra y
 * había que partirlos en dos filas de dos, con la última —Ajustes— quedando recortada. Sacarlos a
 * su propia columna resuelve eso y de paso separa dos cosas que son distintas: navegar entre
 * secciones de la app, y elegir en qué repositorio se está trabajando.
 *
 * Sólo íconos: a 48 dp no entra texto legible, y el nombre aparece al pasar el mouse. Es el trato
 * que hace VS Code y funciona porque son pocas entradas y siempre las mismas — la posición se
 * aprende en dos días y después el ícono alcanza.
 *
 * @param bottom entradas ancladas abajo. La configuración y la información no son destinos de
 *   trabajo: mezclarlas con lo demás las pone a la misma altura que aquello que se usa todo el
 *   día.
 */
@Composable
fun ActivityBar(
    items: List<ActivityItem>,
    bottom: List<ActivityItem> = emptyList(),
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(52.dp)
            .fillMaxHeight()
            // Un tono distinto del panel de al lado: sin eso la tira se lee como parte de la
            // lista de repositorios y se pierde justo el corte que la hace útil.
            .background(MaterialTheme.colorScheme.surfaceVariant),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        items.forEach { ActivityButton(it) }
        Spacer(Modifier.weight(1f))
        bottom.forEach { ActivityButton(it) }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ActivityButton(item: ActivityItem) {
    val activo = item.selected
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(item.label) } },
        state = rememberTooltipState(),
    ) {
        Box(
            Modifier.fillMaxWidth().height(48.dp)
                .clickable(onClick = item.onClick)
                .pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.HAND_CURSOR))),
            contentAlignment = Alignment.Center,
        ) {
            // La marca de selección es una barra al borde izquierdo y no un fondo relleno: con
            // fondo, el ícono activo pesa más que el contenido de la pantalla que está al lado.
            if (activo) {
                Box(
                    Modifier.align(Alignment.CenterStart)
                        .width(2.dp).height(24.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Icon(
                item.icon,
                contentDescription = item.label,
                modifier = Modifier.size(22.dp),
                // El inactivo va atenuado y no de otro color: el contraste es lo que dice dónde
                // estás parado, y dos colores plenos harían que las dos entradas compitan.
                tint = if (activo) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
