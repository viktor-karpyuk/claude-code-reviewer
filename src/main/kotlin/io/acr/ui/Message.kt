package io.acr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Color de quien escribió: el nuestro es el del tema, el ajeno un violeta que no se usa en otro lado. */
@Composable
fun authorColor(ours: Boolean): Color =
    if (ours) MaterialTheme.colorScheme.primary else Color(0xFF8B5CF6)

/**
 * Un mensaje del hilo, con la identidad de quien lo escribió puesta en el fondo y no sólo en el
 * nombre.
 *
 * Antes lo único distinto entre un comentario nuestro y uno del desarrollador era el color del
 * nombre, tres palabras arriba de un bloque de texto idéntico. Leyendo una discusión larga había
 * que ir subiendo la vista para saber quién estaba hablando.
 *
 * La barra vertical de color a la izquierda es lo que se ve de reojo al scrollear; el fondo
 * diluido separa un mensaje del siguiente sin necesidad de líneas.
 */
@Composable
fun MessageBubble(
    author: String,
    body: String,
    ours: Boolean,
    at: String? = null,
    anchor: String? = null,
    modifier: Modifier = Modifier,
) {
    val color = authorColor(ours)
    // height(IntrinsicSize.Min) para que la barra de color llegue justo hasta el final del
    // mensaje: sin eso mide lo que quiera y queda un pedacito de color suelto.
    Row(
        modifier.fillMaxWidth().padding(vertical = 3.dp)
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
    ) {
        Column(
            Modifier.width(3.dp).fillMaxHeight()
                .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                .background(color),
        ) {}
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                .background(color.copy(alpha = if (ours) 0.10f else 0.14f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    author.ifBlank { if (ours) io.acr.i18n.t("msg.us") else io.acr.i18n.t("msg.them") },
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                )
                if (ours) {
                    Text(
                        "  " + io.acr.i18n.t("msg.usTag"),
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                    )
                }
                anchor?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall
                            .copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                at?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it.take(16).replace('T', ' '),
                        style = MaterialTheme.typography.labelSmall
                            .copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SelectionContainer {
                Text(body.take(1_500), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
