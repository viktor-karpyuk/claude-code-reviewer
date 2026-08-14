package io.acr.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
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

/**
 * Anillo de proporciones, con el total en el centro.
 *
 * Anillo y no torta: el agujero deja lugar para el total, que es el dato que uno busca primero, y
 * de paso quita la tentación de comparar áreas —que es lo que peor se lee de una torta—. Los
 * porcentajes van en la referencia, al lado del nombre, porque un ángulo no se lee con precisión
 * por más bien dibujado que esté.
 *
 * Sólo sirve para partes de un todo. Usarlo para cosas que no suman al total —tiempos, promedios—
 * produce un dibujo lindo que no significa nada.
 *
 * @param segments ya ordenados y acotados: con trece personas, trece porciones no se distinguen.
 *   Quien llama agrupa la cola en "otros" antes de pasar la lista.
 */
@Composable
fun DonutChart(
    segments: List<Segment>,
    centerValue: String,
    centerLabel: String,
    size: androidx.compose.ui.unit.Dp = 132.dp,
    modifier: Modifier = Modifier,
) {
    val total = segments.sumOf { it.value }
    val vacio = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val grosor = this.size.minDimension * 0.22f
            val radio = (this.size.minDimension - grosor) / 2f
            val esquina = Offset(
                (this.size.width - radio * 2) / 2f,
                (this.size.height - radio * 2) / 2f,
            )
            val medida = Size(radio * 2, radio * 2)
            if (total <= 0.0) {
                // Sin datos se dibuja el anillo vacío igual: un hueco donde debería haber algo se
                // lee como que el gráfico se rompió.
                drawArc(
                    color = vacio, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    topLeft = esquina, size = medida,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(grosor),
                )
                return@Canvas
            }
            // Se arranca arriba, como un reloj: es de donde todo el mundo espera que salga.
            var angulo = -90f
            segments.forEach { s ->
                val barrido = (s.value / total * 360.0).toFloat()
                drawArc(
                    color = s.color, startAngle = angulo, sweepAngle = barrido, useCenter = false,
                    topLeft = esquina, size = medida,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(grosor),
                )
                angulo += barrido
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerValue, style = MaterialTheme.typography.titleMedium)
            Text(
                centerLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Referencia vertical con el valor y el porcentaje de cada porción.
 *
 * Va al lado del anillo y no debajo porque se lee en paralelo con él. Y lleva los números: el
 * gráfico sirve para ver de un vistazo quién pesa más, no para leer cuánto.
 */
@Composable
fun DonutLegend(segments: List<Segment>, modifier: Modifier = Modifier) {
    val total = segments.sumOf { it.value }.takeIf { it > 0.0 } ?: 1.0
    Column(modifier) {
        segments.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                Canvas(Modifier.size(9.dp)) { drawRect(s.color) }
                Spacer(Modifier.width(6.dp))
                Text(
                    s.label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(150.dp),
                    maxLines = 1,
                )
                Text(
                    "%,.0f".format(s.value),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(64.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
                Text(
                    " %d%%".format(Math.round(s.value / total * 100)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(44.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

/**
 * Paleta para las porciones de un anillo.
 *
 * Se recorre en orden y se repite si hace falta, pero quien llama acota la lista antes: doce
 * colores distinguibles es el techo, y más allá el gráfico deja de informar.
 */
val SLICE_COLORS = listOf(
    Color(0xFF4A6FA5), Color(0xFF2E7D5B), Color(0xFFD08A2C), Color(0xFF9B5DA8),
    Color(0xFF3E8E93), Color(0xFFB4543A), Color(0xFF6C7A89), Color(0xFF8A9B3D),
)

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
