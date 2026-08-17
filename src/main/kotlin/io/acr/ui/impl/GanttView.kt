package io.acr.ui.impl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.acr.i18n.t
import io.acr.impl.ImplTask
import io.acr.impl.TaskStatus
import io.acr.impl.runningMin

/** Una tarea colocada en el tiempo: cuándo empieza, cuánto dura, cuánto lleva hecho. */
data class GanttBar(
    val task: ImplTask,
    val startMin: Double,
    val durationMin: Double,
    val lane: Int,
    /** De 0 a 1. Lo hecho de esta barra al momento de mirar. */
    val progress: Float,
    /**
     * Si va a correr al mismo tiempo que alguna otra.
     *
     * Se deduce del propio calendario en vez de preguntárselo al plan: una tarea es paralelizable
     * si su tramo se superpone con el de otra, y eso ya está decidido por las dependencias y por el
     * repositorio en el que corre. Preguntarlo aparte permitiría que la respuesta y el dibujo
     * dijeran cosas distintas.
     */
    val parallel: Boolean = false,
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
 * Dos tareas del mismo repositorio nunca se superponen, porque el repositorio es el límite real del
 * paralelismo: dibujarlas juntas prometería algo que el motor no va a hacer.
 */
fun layout(tasks: List<ImplTask>, repoLanes: Map<String?, Int>): List<GanttBar> {
    val porSeq = tasks.associateBy { it.seq }
    val fin = mutableMapOf<Int, Double>()
    val libre = mutableMapOf<String?, Double>()

    val barras = tasks.sortedBy { it.seq }.map { t ->
        val corriendo = t.runningMin()
        val duracion = (t.actualMin ?: corriendo ?: t.estimateMin?.toDouble() ?: t.size?.minutes?.toDouble() ?: 15.0)
            .coerceAtLeast(1.0)
        val trasDependencias = t.dependsOn
            .mapNotNull { d -> porSeq[d]?.takeIf { it.seq < t.seq }?.let { fin[d] } }
            .maxOrNull() ?: 0.0
        val inicio = maxOf(trasDependencias, libre[t.repoId] ?: 0.0)
        fin[t.seq] = inicio + duracion
        libre[t.repoId] = inicio + duracion
        GanttBar(t, inicio, duracion, repoLanes[t.repoId] ?: 0, avanceDe(t, corriendo, duracion))
    }

    // El solapamiento se resuelve al final, cuando ya están todas colocadas: mirando de a una no se
    // puede saber si alguien la va a acompañar, porque las que vienen después todavía no existen.
    return barras.map { b ->
        b.copy(
            parallel = barras.any { otra ->
                otra.task.id != b.task.id &&
                    otra.startMin < b.startMin + b.durationMin &&
                    b.startMin < otra.startMin + otra.durationMin
            },
        )
    }
}

/** ¿Esta tarea va a correr acompañada? Para las pantallas que no dibujan el diagrama. */
fun paralelasDe(tasks: List<ImplTask>): Set<Int> {
    val carriles = tasks.map { it.repoId }.distinct().withIndex().associate { (i, r) -> r to i }
    return layout(tasks, carriles).filter { it.parallel }.map { it.task.seq }.toSet()
}

/**
 * Cuánto de una barra está hecho.
 *
 * La que corre se llena con lo que lleva corriendo contra lo que se estimó, y se topea en 0,95: una
 * barra llena mientras la tarea sigue trabajando diría que terminó, que es justo lo contrario de lo
 * que pasa cuando una tarea se pasa de su estimación.
 *
 * La que falló o quedó bloqueada se dibuja llena, pero su color dice otra cosa. Dejarla vacía la
 * haría ver como pendiente, y no es lo mismo: una consumió tiempo y la otra no.
 */
private fun avanceDe(t: ImplTask, corriendo: Double?, duracion: Double): Float = when (t.status) {
    TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.BLOCKED -> 1f
    TaskStatus.RUNNING -> {
        val estimado = (t.estimateMin ?: t.size?.minutes)?.toDouble() ?: duracion
        (((corriendo ?: 0.0) / estimado.coerceAtLeast(1.0)).coerceIn(0.0, 0.95)).toFloat()
    }
    else -> 0f
}

/**
 * El plan como diagrama de Gantt.
 *
 * Una tabla ordenada por número contesta "qué falta"; no contesta "por qué esta tarea todavía no
 * arrancó", ni "cuánto de esto puede pasar a la vez", ni "vamos bien o mal de tiempo".
 *
 * Lo que lo hace un diagrama y no una lista de barras son tres cosas: las **flechas** de
 * dependencia, que dicen quién espera a quién sin tener que cruzar números; el **relleno parcial**
 * de cada barra, que muestra cuánto va hecho al momento de mirar; y la **línea de ahora**, que marca
 * tiempo real sobre un eje dibujado en tiempo planificado. Que esa línea quede a la derecha de lo
 * último terminado es exactamente la señal de que se está yendo de tiempo, y no se ve en ninguna
 * otra pantalla.
 */
@Composable
fun GanttView(
    tasks: List<ImplTask>,
    repos: List<io.acr.forge.RepoRecord>,
    /** Minutos reales desde que arrancó la implementación. Null si todavía no arrancó. */
    elapsedMin: Double?,
    onOpen: (ImplTask) -> Unit,
) {
    if (tasks.isEmpty()) return
    val carriles = remember(tasks) {
        tasks.map { it.repoId }.distinct().withIndex().associate { (i, r) -> r to i }
    }
    val barras = remember(tasks) { layout(tasks, carriles) }
    val total = barras.maxOfOrNull { it.startMin + it.durationMin }?.coerceAtLeast(1.0) ?: 1.0
    val medidor = rememberTextMeasurer()

    // Escala fija por minuto en vez de "que entre en el ancho": comprimido para entrar, dos planes
    // de duración muy distinta se verían iguales y el largo dejaría de decir algo. Se scrollea, que
    // es lo que hace un Gantt.
    val porMinuto = (1000.0 / total).coerceIn(2.0, 16.0)
    val anchoEtiqueta = 250f
    val altoFila = 30f
    val altoEje = 26f
    val ancho = (anchoEtiqueta + total * porMinuto + 50).toFloat()
    val alto = altoEje + barras.size * altoFila + 14f

    val apagado = MaterialTheme.colorScheme.onSurfaceVariant
    val tenue = apagado.copy(alpha = 0.22f)
    val colores = barras.associate { it.task.id to colorDe(it.task.status) }
    val ahora = MaterialTheme.colorScheme.error
    val chico = TextStyle(fontSize = 10.sp, color = apagado)
    val titulo = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
    val mono = TextStyle(fontSize = 10.sp, color = apagado, fontFamily = FontFamily.Monospace)

    // Los textos que se dibujan adentro del Canvas se resuelven acá: `t` es composable y el bloque
    // de dibujo no lo es.
    val textoAhora = t("impl.ganttNow")
    val hechas = tasks.count { it.status == TaskStatus.DONE }
    val minutosHechos = barras.filter { it.task.status == TaskStatus.DONE }.sumOf { it.durationMin }
    val enParalelo = barras.count { it.parallel }

    Column(Modifier.fillMaxWidth()) {
        // El estado al momento de mirar, arriba: es la lectura que casi todos buscan sin tener que
        // interpretar el dibujo.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                t(
                    "impl.ganttSoFar", hechas, tasks.size,
                    io.acr.impl.minutosLegibles(minutosHechos), io.acr.impl.minutosLegibles(total),
                ),
                style = MaterialTheme.typography.labelMedium,
            )
            elapsedMin?.takeIf { it > 0 }?.let {
                Spacer(Modifier.width(10.dp))
                Text(
                    t("impl.ganttElapsed", io.acr.impl.minutosLegibles(it)),
                    style = MaterialTheme.typography.labelSmall,
                    // Pasarse de lo planificado no es un error, pero es lo único acá que puede
                    // cambiar una decisión: se marca.
                    color = if (hechas > 0 && it > minutosHechos * 1.25) ahora else apagado,
                )
            }
            if (enParalelo > 0) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "⇉  " + t("impl.ganttParallel", enParalelo),
                    style = MaterialTheme.typography.labelSmall,
                    color = apagado,
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Canvas(
                Modifier
                    .size(ancho.dp, alto.dp)
                    .pointerInput(barras) {
                        detectTapGestures { p ->
                            val fila = ((p.y - altoEje * density) / (altoFila * density)).toInt()
                            barras.getOrNull(fila)?.let { onOpen(it.task) }
                        }
                    },
            ) {
                val d = density
                val etiqueta = anchoEtiqueta * d
                val filaAlta = altoFila * d
                val eje = altoEje * d
                val escala = porMinuto * d

                fun x(min: Double) = etiqueta + (min * escala).toFloat()
                fun yCentro(i: Int) = eje + i * filaAlta + filaAlta / 2f

                // --- Eje de tiempo ---
                // Marcas redondas en vez de repartidas: "cada 15 minutos" se lee; "cada 13,4" hace
                // que haya que hacer cuentas para ubicar una barra.
                val paso = listOf(5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 240.0, 480.0)
                    .firstOrNull { total / it <= 12 } ?: 960.0
                var m = 0.0
                while (m <= total) {
                    val px = x(m)
                    drawLine(tenue, Offset(px, eje - 4 * d), Offset(px, size.height), strokeWidth = 1f)
                    drawText(
                        medidor, if (m == 0.0) "0" else "${m.toInt()}m",
                        topLeft = Offset(px + 3 * d, 2f), style = chico,
                    )
                    m += paso
                }

                // --- Barras ---
                barras.forEachIndexed { i, b ->
                    val y = yCentro(i)
                    val altoBarra = 13f * d
                    val x0 = x(b.startMin)
                    val anchoBarra = ((b.durationMin * escala).toFloat()).coerceAtLeast(4f * d)
                    val color = colores[b.task.id] ?: apagado

                    drawText(
                        medidor, b.task.seq.toString(),
                        topLeft = Offset(4f * d, y - 6f * d), style = mono,
                    )
                    // El icono de paralelizable, antes del título: es una propiedad de la tarea y
                    // se escanea en columna, no leyendo cada renglón.
                    drawText(
                        medidor, if (b.parallel) "⇉" else "→",
                        topLeft = Offset(26f * d, y - 7f * d),
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = if (b.parallel) StatusColors.RUNNING else tenue,
                        ),
                    )
                    // Recortado por el medidor y no por el layout: así el título largo no se mete
                    // debajo de las barras.
                    drawText(
                        medidor, b.task.title, topLeft = Offset(42f * d, y - 6f * d), style = titulo,
                        size = Size(etiqueta - 50f * d, filaAlta), maxLines = 1,
                    )

                    // El contorno es lo planificado; el relleno, lo hecho. Con una sola forma habría
                    // que elegir entre mostrar el plan o mostrar el avance.
                    drawRoundRect(
                        color = color.copy(alpha = 0.18f),
                        topLeft = Offset(x0, y - altoBarra / 2),
                        size = Size(anchoBarra, altoBarra),
                        cornerRadius = CornerRadius(3f * d),
                    )
                    if (b.progress > 0f) {
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(x0, y - altoBarra / 2),
                            size = Size((anchoBarra * b.progress).coerceAtLeast(3f * d), altoBarra),
                            cornerRadius = CornerRadius(3f * d),
                        )
                    }
                    // Cuántos minutos son. Sin esto hay que medir contra el eje para saber si una
                    // barra son diez minutos o cuarenta.
                    drawText(
                        medidor, io.acr.impl.minutosLegibles(b.durationMin),
                        topLeft = Offset(x0 + anchoBarra + 4f * d, y - 6f * d), style = chico,
                    )
                }

                // --- Flechas de dependencia ---
                // Después de las barras, para quedar encima, y en codo: una diagonal entre dos filas
                // lejanas cruza media pantalla y se confunde con las barras que atraviesa.
                val porSeqBarra = barras.associateBy { it.task.seq }
                val indice = barras.withIndex().associate { (i, b) -> b.task.seq to i }
                barras.forEach { b ->
                    b.task.dependsOn.forEach { dep ->
                        val madre = porSeqBarra[dep] ?: return@forEach
                        if (madre.task.seq >= b.task.seq) return@forEach
                        val iM = indice[dep] ?: return@forEach
                        val iH = indice[b.task.seq] ?: return@forEach
                        val xFin = x(madre.startMin + madre.durationMin)
                        val yFin = yCentro(iM)
                        val xIni = x(b.startMin)
                        val yIni = yCentro(iH)
                        val codo = minOf(xFin + 7f * d, xIni)
                        drawPath(
                            Path().apply {
                                moveTo(xFin, yFin)
                                lineTo(codo, yFin)
                                lineTo(codo, yIni)
                                lineTo(xIni, yIni)
                            },
                            apagado.copy(alpha = 0.5f),
                            style = Stroke(width = 1.2f * d),
                        )
                        // La punta dice en qué dirección corre la dependencia, que es la única
                        // información que la línea aporta.
                        drawPath(
                            Path().apply {
                                moveTo(xIni, yIni)
                                lineTo(xIni - 4f * d, yIni - 3f * d)
                                lineTo(xIni - 4f * d, yIni + 3f * d)
                                close()
                            },
                            apagado.copy(alpha = 0.5f),
                        )
                    }
                }

                // --- La línea de ahora ---
                elapsedMin?.takeIf { it > 0 }?.let { e ->
                    val px = x(e.coerceAtMost(total))
                    drawLine(
                        ahora, Offset(px, eje - 6 * d), Offset(px, size.height),
                        strokeWidth = 1.5f * d,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * d, 3f * d)),
                    )
                    drawText(
                        medidor, textoAhora,
                        topLeft = Offset(px + 3f * d, alto * d - 13f * d),
                        style = TextStyle(fontSize = 9.sp, color = ahora),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf(
                TaskStatus.DONE, TaskStatus.RUNNING, TaskStatus.PENDING,
                TaskStatus.FAILED, TaskStatus.BLOCKED,
            ).forEach { st ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val c = colorDe(st)
                    Canvas(Modifier.size(9.dp, 9.dp)) {
                        drawRoundRect(c, cornerRadius = CornerRadius(2f * density))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        t("task.st." + st.name.lowercase()),
                        style = MaterialTheme.typography.labelSmall,
                        color = apagado,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⇉", color = StatusColors.RUNNING, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(4.dp))
                Text(
                    t("impl.parallelLegend"),
                    style = MaterialTheme.typography.labelSmall,
                    color = apagado,
                )
            }
        }
    }
}

/** El mismo código de color que el resto de la app: dos leyendas distintas serían una de más. */
private fun colorDe(s: TaskStatus): Color = StatusColors.of(s)
