package io.acr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Un escalón del camino. `onClick` nulo = el lugar donde estás parado. */
data class Crumb(val label: String, val onClick: (() -> Unit)? = null)

/**
 * El camino hasta donde uno está, con cada escalón clickeable.
 *
 * Un botón "volver" sirve cuando hay un solo nivel; con tres —el módulo, la implementación, la
 * tarea— obliga a apretarlo dos veces y adivinar dónde va a caer. El camino además dice **dónde
 * estás**, que es la mitad del problema: una pantalla de tarea abierta sin contexto se parece
 * bastante a la pantalla de otra tarea.
 *
 * El último escalón no es un link y va en color pleno: si todo se pudiera clickear, no se sabría
 * cuál es el lugar actual.
 */
@Composable
fun Breadcrumbs(crumbs: List<Crumb>, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        crumbs.forEachIndexed { i, c ->
            if (i > 0) {
                Text(
                    "›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            val ultimo = i == crumbs.lastIndex
            Text(
                c.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (ultimo || c.onClick == null) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = if (ultimo || c.onClick == null) Modifier
                else Modifier.clickable { c.onClick.invoke() },
            )
        }
        Spacer(Modifier.width(4.dp))
    }
}
