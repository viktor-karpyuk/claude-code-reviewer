package io.acr.ui

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Un botón con una explicación al pasar el mouse.
 *
 * Los tres botones de análisis —documentos, auditoría, replanificación— hacen cosas parecidas de
 * describir y muy distintas de ejecutar: uno lee las specs, otro compara el plan contra ellas, otro
 * rehace el plan. El nombre solo no alcanza para elegir, y el que duda termina apretando el que
 * suena más inofensivo, que no siempre es el que necesita.
 *
 * `TooltipArea` de Compose Desktop y no el de Material 3: ese sigue siendo experimental en esta
 * versión, y acá el tooltip no es un adorno sino la mitad de la explicación.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun InfoTip(
    texto: String,
    /** Qué deja como resultado. Va aparte porque es la pregunta que decide cuál apretar. */
    resultado: String? = null,
    content: @Composable () -> Unit,
) {
    TooltipArea(
        tooltip = {
            Column(
                Modifier
                    .widthIn(max = 340.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    texto,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
                resultado?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.75f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        delayMillis = 250,
        content = content,
    )
}
