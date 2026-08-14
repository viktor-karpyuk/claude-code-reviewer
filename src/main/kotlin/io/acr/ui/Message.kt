package io.acr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.Dp
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
                val quien = author.ifBlank {
                    if (ours) io.acr.i18n.t("msg.us") else io.acr.i18n.t("msg.them")
                }
                PersonAvatar(quien, size = 36.dp)
                Spacer(Modifier.width(6.dp))
                Text(quien, style = MaterialTheme.typography.labelSmall, color = color)
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

/**
 * Cómo se resolvería el hallazgo.
 *
 * Va visualmente separado del cuerpo —fondo propio y monoespaciada— porque son dos cosas
 * distintas: el cuerpo dice qué está mal y esto dice qué hacer. Mezclados en un mismo párrafo,
 * la propuesta se pierde justo cuando es lo más accionable.
 *
 * No se muestra nada cuando no hay sugerencia: la review tiene instrucción de no inventar una si
 * no puede sostenerla, y un bloque vacío haría parecer que falló.
 */
@Composable
fun SuggestionBlock(suggestion: String?, modifier: Modifier = Modifier) {
    val texto = suggestion?.takeIf { it.isNotBlank() } ?: return
    Column(
        modifier.fillMaxWidth().padding(top = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(io.acr.ui.VERDE_OK.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            io.acr.i18n.t("finding.suggestion"),
            style = MaterialTheme.typography.labelSmall,
            color = io.acr.ui.VERDE_OK,
        )
        SelectionContainer {
            Text(
                texto,
                style = MaterialTheme.typography.bodySmall
                    .copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

/** Iniciales de un nombre: "Viktor Karpyuk" → "VK". Con una sola palabra, sus dos primeras letras. */
fun iniciales(nombre: String): String {
    val partes = nombre.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        partes.isEmpty() -> "?"
        partes.size == 1 -> partes[0].take(2).uppercase()
        else -> (partes.first().take(1) + partes.last().take(1)).uppercase()
    }
}

/**
 * Color estable para una persona, derivado de su nombre.
 *
 * Estable importa: si el color cambiara entre aperturas, el círculo dejaría de servir para
 * reconocer a alguien de un vistazo, que es lo único que hace.
 */
fun colorDePersona(nombre: String): Color {
    // Doce y no ocho: con ocho, el equipo real de cinco personas caía en sólo tres colores. Y FNV
    // en vez de `hashCode()`, que agrupa los nombres parecidos —comparten prefijos y longitud— y
    // era la causa de las colisiones.
    val paleta = listOf(
        0xFF4C8DD9, 0xFF8B5CF6, 0xFFD98324, 0xFF2E9E5B,
        0xFFC2410C, 0xFF0891B2, 0xFFBE185D, 0xFF65A30D,
        0xFF7C3AED, 0xFF0D9488, 0xFFB45309, 0xFF4F46E5,
    )
    var h = 2_166_136_261u
    nombre.forEach { c -> h = (h xor c.code.toUInt()) * 16_777_619u }
    return Color(paleta[(h % paleta.size.toUInt()).toInt()])
}

/**
 * Un círculo con las iniciales de quien se pronunció sobre el PR.
 *
 * Con varias personas, una lista de nombres ocupa toda la fila; los círculos entran en el ancho de
 * un badge y se distinguen por color. El nombre completo aparece al pasar el mouse: en una lista
 * hay que poder identificar sin leer, pero también hay que poder confirmar sin adivinar.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PersonAvatar(
    nombre: String,
    /**
     * Qué opinó, si el círculo representa una postura sobre un PR.
     *
     * Cuando viene, el color deja de ser el de la persona y pasa a decir el estado: verde aprobó,
     * rojo pidió cambios, gris participa sin haberse pronunciado. Es más rápido que leerlo, y
     * ahorra el texto al lado —que con varias personas ocupaba toda la fila.
     */
    stance: io.acr.data.ReviewStance? = null,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
) {
    val base = when (stance) {
        io.acr.data.ReviewStance.APPROVED -> VERDE_OK
        io.acr.data.ReviewStance.CHANGES_REQUESTED -> MaterialTheme.colorScheme.error
        io.acr.data.ReviewStance.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
        null -> colorDePersona(nombre)
    }
    // TooltipArea de Compose Desktop y no el TooltipBox de Material3: el de Material sigue siendo
    // experimental en esta versión, y acá el tooltip es la mitad de la función.
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Text(
                // El tooltip dice el nombre y, si hay postura, qué opinó: el color se reconoce de
                // reojo pero no se confirma sin leer.
                nombre + when (stance) {
                    io.acr.data.ReviewStance.APPROVED -> " · " + io.acr.i18n.t("stance.approved")
                    io.acr.data.ReviewStance.CHANGES_REQUESTED -> " · " + io.acr.i18n.t("stance.changes")
                    io.acr.data.ReviewStance.NONE -> " · " + io.acr.i18n.t("stance.none")
                    null -> ""
                },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.inverseOnSurface,
            )
        },
        delayMillis = 300,
    ) {
        Box(
            modifier
                .size(size)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(base.copy(alpha = if (stance == null) 0.22f else 0.30f))
                .then(
                    if (stance == null) Modifier
                    else Modifier.border(
                        if (size >= 32.dp) 2.dp else 1.5.dp,
                        base,
                        androidx.compose.foundation.shape.CircleShape,
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                iniciales(nombre),
                // La tipografía acompaña al círculo: unas iniciales chicas dentro de un círculo
                // grande se ven perdidas, y es lo que se lee cuando el color no alcanza.
                style = if (size >= 32.dp) MaterialTheme.typography.titleSmall
                else MaterialTheme.typography.labelSmall,
                color = base,
            )
        }
    }
}
