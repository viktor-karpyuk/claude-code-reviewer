package io.acr.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Un tramo de una barra: cuánto vale y de qué color.
 *
 * Las barras se arman con tramos y no con un número solo porque las métricas de acá casi siempre
 * tienen partes que no se pueden sumar sin perder información —agregado contra borrado, bloqueante
 * contra menor—. Sumarlas para dibujar una barra sería tirar justo lo que hay que ver.
 */
data class Segment(val value: Double, val color: Color, val label: String)

/**
 * Barras horizontales, una por fila, todas contra la misma escala.
 *
 * La escala compartida es la decisión importante: si cada fila se normalizara a su propio máximo,
 * todas se verían del mismo largo y el gráfico diría que todos hicieron lo mismo. Compartirla es
 * lo único que hace que un gráfico de barras signifique algo.
 *
 * No hay eje numérico dibujado: al lado de cada barra va la tabla con los números exactos, y un
 * eje aproximado invita a leer valores del gráfico cuando están escritos ahí al lado. El gráfico
 * es para comparar tamaños de un vistazo, no para leer cifras.
 */
@Composable
fun BarRow(
    segments: List<Segment>,
    max: Double,
    height: androidx.compose.ui.unit.Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    val fondo = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(height)) {
        val total = segments.sumOf { it.value }
        if (max <= 0.0 || total <= 0.0) return@Canvas
        // El ancho se reparte proporcional al máximo global, no al total de esta fila.
        var x = 0f
        val usable = size.width
        segments.forEach { s ->
            val w = (s.value / max * usable).toFloat()
            if (w > 0f) {
                drawRect(color = s.color, topLeft = Offset(x, 0f), size = Size(w, size.height))
                x += w
            }
        }
        // Lo que falta hasta el máximo queda insinuado, para que se vea contra qué se compara.
        if (x < usable) {
            drawRect(
                color = fondo.copy(alpha = 0.35f),
                topLeft = Offset(x, size.height * 0.4f),
                size = Size(usable - x, size.height * 0.2f),
            )
        }
    }
}

/**
 * Barras verticales para una serie corta, típicamente los trimestres.
 *
 * Vertical y no horizontal porque acá el eje que importa es el tiempo, y el tiempo se lee de
 * izquierda a derecha. El último se dibuja más tenue cuando está en curso: un trimestre a mitad
 * de camino siempre parece una caída, y esa lectura equivocada es automática si no se marca.
 */
@Composable
fun MiniBars(
    values: List<Double>,
    labels: List<String>,
    lastIsPartial: Boolean = false,
    color: Color = MaterialTheme.colorScheme.primary,
    height: androidx.compose.ui.unit.Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    if (values.isEmpty()) return
    val max = values.maxOrNull() ?: 0.0
    val vacio = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val n = values.size
            val hueco = size.width / n * 0.25f
            val ancho = (size.width / n) - hueco
            values.forEachIndexed { i, v ->
                val h = if (max <= 0.0) 0f else (v / max * size.height).toFloat()
                val x = i * (ancho + hueco)
                val esParcial = lastIsPartial && i == n - 1
                // Una barra en cero se dibuja igual, finita: si desapareciera, un trimestre sin
                // actividad se vería como si no existiera en vez de como un cero.
                drawRect(
                    color = if (esParcial) color.copy(alpha = 0.45f) else color,
                    topLeft = Offset(x, size.height - maxOf(h, 1.5f)),
                    size = Size(ancho, maxOf(h, 1.5f)),
                )
                if (h < 1.5f) {
                    drawRect(
                        color = vacio,
                        topLeft = Offset(x, size.height - 1.5f),
                        size = Size(ancho, 1.5f),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                Text(
                    l + if (lastIsPartial && i == labels.lastIndex) "*" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Referencia de colores. Sin esto una barra apilada es un adorno: no se sabe qué es cada parte. */
@Composable
fun Legend(items: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (texto, color) ->
            Canvas(Modifier.size(9.dp)) { drawRect(color) }
            Spacer(Modifier.width(4.dp))
            Text(
                texto,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}

/**
 * Paleta de los gráficos.
 *
 * Se elige a mano y no del tema porque el primario es azul y se usa para navegar: reusarlo acá
 * mezclaría dos significados. Los pares que van juntos —agregado contra borrado— se distinguen por
 * tono y por claridad a la vez, así siguen siendo distinguibles sin percibir el color.
 */
object ChartColors {
    val added = Color(0xFF2E7D5B)
    val deleted = Color(0xFFB4543A)
    val blocker = Color(0xFFC0392B)
    val major = Color(0xFFD08A2C)
    val minor = Color(0xFF7F8C9A)
    val neutral = Color(0xFF4A6FA5)
}
