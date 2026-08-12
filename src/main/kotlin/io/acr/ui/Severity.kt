package io.acr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Gravedad de un hallazgo, con su color.
 *
 * Vive en un solo lugar a propósito: la severidad se muestra en cuatro pantallas —el resumen de la
 * review, la conversación, la lista de hallazgos del código y el diff— y cada una elegía su color
 * por su cuenta. `major` usaba el azul del tema y `minor` un gris, que en la práctica se leían
 * igual; el ámbar aparecía suelto en el visor de código sin significar nada en particular.
 */
enum class Severity(val api: String, val labelKey: String) {
    /** Frena el merge: hay que arreglarlo. */
    BLOCKER("blocker", "severity.blocker"),

    /** Importa, pero se puede discutir. */
    MAJOR("major", "severity.major"),

    /** Vale decirlo y se puede descartar sin culpa. */
    MINOR("minor", "severity.minor"),
    ;

    companion object {
        /** Lo que devuelve el modelo viene en minúsculas; un valor raro cae en el menos grave. */
        fun of(raw: String?): Severity =
            entries.firstOrNull { it.api.equals(raw?.trim(), ignoreCase = true) } ?: MINOR
    }
}

/**
 * Los tres colores son de familias distintas —rojo, ámbar, azul— y no tonos del mismo: la
 * diferencia tiene que leerse de reojo, no comparando dos grises al lado.
 */
@Composable
fun Severity.color(): Color = when (this) {
    Severity.BLOCKER -> MaterialTheme.colorScheme.error
    Severity.MAJOR -> Color(0xFFD98324)
    Severity.MINOR -> Color(0xFF4C8DD9)
}

/** Fondo del distintivo: el mismo color muy diluido, para que funcione en claro y en oscuro. */
@Composable
fun Severity.tint(): Color = color().copy(alpha = 0.16f)

/**
 * Marca en texto, para acompañar al color.
 *
 * No todo el mundo distingue rojo de ámbar, y el color solo tampoco sobrevive a una captura en
 * blanco y negro. La cantidad de puntos dice lo mismo sin depender de la vista.
 */
fun Severity.mark(): String = when (this) {
    Severity.BLOCKER -> "●●●"
    Severity.MAJOR -> "●●"
    Severity.MINOR -> "●"
}

/** Distintivo compacto: punto(s), etiqueta y fondo del color. */
@Composable
fun SeverityBadge(raw: String?, modifier: Modifier = Modifier) {
    val s = Severity.of(raw)
    Text(
        "${s.mark()} ${io.acr.i18n.t(s.labelKey)}",
        style = MaterialTheme.typography.labelSmall,
        color = s.color(),
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(s.tint())
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
