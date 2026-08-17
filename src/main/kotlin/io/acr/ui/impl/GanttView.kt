package io.acr.ui.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.acr.i18n.t
import io.acr.impl.ImplTask
import io.acr.impl.TaskStatus
import io.acr.impl.runningMin

/** Una tarea colocada en el tiempo: en qué minuto empieza, cuánto dura, y en qué carril va. */
data class GanttBar(
    val task: ImplTask,
    val startMin: Double,
    val durationMin: Double,
    val lane: Int,
)

/**
 * Coloca las tareas en el tiempo respetando las dependencias.
 *
 * Cuándo empieza cada una **no se lee del reloj sino del grafo**: una tarea arranca cuando terminan
 * todas de las que depende, y las que no dependen entre sí arrancan juntas. Es la misma regla que
 * usa el motor para ejecutar, y tenía que ser la misma: un diagrama que muestra un orden distinto
 * del que va a pasar no es una previsión, es una ilustración.
 *
 * Para lo ya corrido se usa lo que tardó de verdad; para lo que falta, la estimación. Mezclarlos es
 * a propósito: es exactamente la lectura que sirve —lo que pasó, y desde ahí lo que se espera—.
 *
 * Los carriles son los repositorios, porque son el límite real del paralelismo: dos tareas del
 * mismo repositorio no pueden correr a la vez aunque no dependan entre sí.
 */
fun layout(tasks: List<ImplTask>, repoLanes: Map<String?, Int>): List<GanttBar> {
    val porSeq = tasks.associateBy { it.seq }
    val fin = mutableMapOf<Int, Double>()
    // Cuándo se libera cada repositorio. Sin esto, dos tareas del mismo repo se dibujarían
    // superpuestas y el diagrama prometería un paralelismo que el motor no va a hacer.
    val libre = mutableMapOf<String?, Double>()

    return tasks.sortedBy { it.seq }.map { t ->
        val duracion = (t.actualMin ?: t.runningMin() ?: t.estimateMin?.toDouble() ?: t.size?.minutes?.toDouble() ?: 15.0)
            .coerceAtLeast(1.0)
        val trasDependencias = t.dependsOn
            .mapNotNull { d -> porSeq[d]?.takeIf { it.seq < t.seq }?.let { fin[d] } }
            .maxOrNull() ?: 0.0
        val inicio = maxOf(trasDependencias, libre[t.repoId] ?: 0.0)
        fin[t.seq] = inicio + duracion
        libre[t.repoId] = inicio + duracion
        GanttBar(t, inicio, duracion, repoLanes[t.repoId] ?: 0)
    }
}

/**
 * El plan como diagrama de Gantt, con sus dependencias.
 *
 * Una tabla ordenada por número contesta "qué falta"; no contesta "por qué esta tarea todavía no
 * arrancó" ni "cuánto de esto puede pasar en paralelo". Eso es lo que se ve acá de un vistazo: las
 * barras que empiezan juntas son las que van a correr juntas, y las flechas dicen quién espera a
 * quién.
 *
 * Se redibuja con cada latido mientras algo corre, así que la barra de la tarea en curso crece
 * sola y el diagrama muestra el desvío contra lo estimado en el momento en que pasa, no después.
 */
@Composable
fun GanttView(
    tasks: List<ImplTask>,
    repos: List<io.acr.forge.RepoRecord>,
    onOpen: (ImplTask) -> Unit,
) {
    if (tasks.isEmpty()) return
    val carriles = remember(tasks, repos) {
        tasks.map { it.repoId }.distinct().withIndex().associate { (i, r) -> r to i }
    }
    val barras = remember(tasks, carriles) { layout(tasks, carriles) }
    val total = barras.maxOfOrNull { it.startMin + it.durationMin }?.coerceAtLeast(1.0) ?: 1.0

    // Escala fija por minuto en vez de "que entre en el ancho": si el diagrama se comprimiera para
    // entrar, dos planes de duración muy distinta se verían iguales y el largo dejaría de decir
    // algo. Se scrollea, que es lo que hace un Gantt.
    val porMinuto = (900.0 / total).coerceIn(1.5, 14.0)
    val anchoTotal = (total * porMinuto).dp
    val altoFila = 26.dp
    val etiqueta = 210.dp

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("impl.gantt"), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(10.dp))
            Text(
                t("impl.ganttNote", total.toInt()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column {
                barras.forEach { b ->
                    Row(
                        Modifier.height(altoFila).clickable { onOpen(b.task) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Etiqueta fija a la izquierda: el número y el título recortado. Sin el
                        // número no se puede leer una flecha de dependencia, que habla de números.
                        Row(Modifier.width(etiqueta), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                b.task.seq.toString(),
                                style = MaterialTheme.typography.labelSmall
                                    .copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(28.dp),
                            )
                            Text(
                                b.task.title,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                            )
                        }

                        Box(Modifier.width(anchoTotal).height(altoFila)) {
                            Box(
                                Modifier
                                    .padding(start = (b.startMin * porMinuto).dp, top = 5.dp, bottom = 5.dp)
                                    .width((b.durationMin * porMinuto).dp.coerceAtLeast(6.dp))
                                    .height(16.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(colorDe(b.task.status)),
                            )
                        }
                    }
                }
            }
        }

        // Las dependencias, en texto, debajo. Dibujar flechas entre filas de un scroll horizontal
        // exige coordenadas que Compose no da barato, y el valor real —quién espera a quién— se
        // lee igual de bien así. Sólo las que existen: un listado con "ninguna" en cada renglón
        // sería ruido.
        val conDeps = tasks.filter { t -> t.dependsOn.any { d -> d != t.seq && tasks.any { o -> o.seq == d } } }
        if (conDeps.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                t("impl.ganttDeps"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            conDeps.forEach { t2 ->
                Text(
                    "#${t2.seq} ← " + t2.dependsOn.filter { d -> tasks.any { o -> o.seq == d } }
                        .joinToString(", ") { "#$it" },
                    style = MaterialTheme.typography.labelSmall
                        .copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** El mismo código de color que el resto de la pantalla: dos leyendas distintas serían una de más. */
@Composable
private fun colorDe(s: TaskStatus): Color = when (s) {
    TaskStatus.DONE -> io.acr.ui.stats.ChartColors.added
    TaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
    TaskStatus.FAILED -> MaterialTheme.colorScheme.error
    TaskStatus.BLOCKED -> io.acr.ui.stats.ChartColors.major
    else -> MaterialTheme.colorScheme.surfaceVariant
}
