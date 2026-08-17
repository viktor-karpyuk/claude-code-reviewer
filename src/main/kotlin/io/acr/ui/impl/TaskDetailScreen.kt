package io.acr.ui.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.acr.AppContext
import io.acr.forge.RepoRecord
import io.acr.i18n.t
import io.acr.impl.ImplTask
import io.acr.impl.TaskStatus
import io.acr.impl.runningMin

/**
 * Todo lo que pasó en una tarea.
 *
 * El layout separa dos cosas que se leen distinto: a la izquierda **el relato** —qué se pidió, qué
 * dice que hizo, qué falló— que se lee de corrido; a la derecha **los hechos** —cuánto tardó, qué
 * costó, qué archivos, qué señales— que se barren de un vistazo. Apilarlos en una sola columna
 * obliga a leer prosa para llegar a un número y al revés.
 *
 * El código va abajo y a lo ancho porque es lo único que necesita el espacio completo, y es lo
 * último que se mira: primero uno decide si vale la pena mirarlo.
 */
@Composable
fun TaskDetailScreen(
    ctx: AppContext,
    task: ImplTask,
    repo: RepoRecord?,
    implTitle: String,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRetry: () -> Unit,
) {
    val analisis = task.diff?.files?.let { io.acr.impl.analyze(it) }
    // Una tarea que todavía no corrió no tiene commit, ni código, ni resultado. Mostrarle esas
    // secciones diciendo "todavía no" es llenar la pantalla de ausencias: lo que hay que leer ahí
    // es qué se propone hacer.
    val yaCorrio = task.status != TaskStatus.PENDING || task.commitSha != null || task.result != null

    // Un latido mientras la tarea corre. El contexto se llena mientras trabaja y su `updated_at` no
    // cambia con cada entrada, así que sin esto la pantalla muestra la foto del momento en que se
    // abrió — justo cuando lo interesante es verla crecer.
    var tic by remember(task.id) { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(task.id, task.status) {
        while (task.status == TaskStatus.RUNNING) {
            kotlinx.coroutines.delay(3_000)
            tic++
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        io.acr.ui.Breadcrumbs(
            listOf(
                io.acr.ui.Crumb(t("nav.impls"), onHome),
                io.acr.ui.Crumb(implTitle, onBack),
                io.acr.ui.Crumb("${task.seq}. ${task.title}"),
            ),
            Modifier.padding(bottom = 14.dp),
        )

        // Encabezado: el estado manda, porque decide qué significa todo lo demás.
        Row(verticalAlignment = Alignment.CenterVertically) {
            EstadoGrande(task.status)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(task.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    listOfNotNull(
                        t("impl.taskN", task.seq),
                        repo?.name,
                        task.size?.name?.let { t("impl.sizeN", it) },
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (task.status == TaskStatus.FAILED) {
                TextButton(onClick = onRetry) { Text(t("impl.retry")) }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Los números a lo ancho, antes de las dos columnas.
        //
        // Estaban en un panel de la columna derecha, en renglones etiqueta/valor: eso se lee, no se
        // barre. Lo que uno busca al abrir una tarea es si tardó lo que debía y cuánto código dejó,
        // y esas dos preguntas se contestan con cifras grandes o no se contestan.
        TiraDeCifras(task, yaCorrio)
        Spacer(Modifier.height(16.dp))

        // La línea de vida: creada, arrancó, terminó. Las fechas sueltas obligan a restarlas
        // mentalmente para saber cuánto esperó una tarea antes de arrancar, que suele ser más de lo
        // que tardó en correr.
        LineaDeVida(task)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // --- Izquierda: el relato ---
            Column(Modifier.weight(1.4f)) {
                Seccion(t("impl.whatToDo"))
                if (task.detail.isBlank()) {
                    // Sin descripción, lo único que hay es el título. Decirlo es mejor que dejar
                    // un hueco donde debería haber texto.
                    Text(
                        t("impl.noDetail"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // Las enumeraciones se desarman y se ponen una debajo de otra. Los modelos las
                    // escriben en línea —"hay que 1) migrar, 2) exponer, 3) cablear"— y como
                    // párrafo corrido eso se lee como una sola oración larga donde los números son
                    // ruido. En vertical se cuentan de un vistazo.
                    Enumerado(task.detail)
                }
                Spacer(Modifier.height(18.dp))

                // Los pasos: el cómo. El detalle de arriba dice qué hay que lograr; acá está
                // desarmado en cosas que se pueden tildar de a una. Antes de correr sirve para ver
                // si el plan entendió el problema; después, para ver qué quedó sin hacer.
                if (task.steps.isNotEmpty()) {
                    val hechos = task.steps.count { it.status == TaskStatus.DONE }
                    Seccion(
                        if (yaCorrio) t("impl.stepsDone", hechos, task.steps.size)
                        else t("impl.steps", task.steps.size),
                    )
                    task.steps.forEach { paso -> PasoFila(paso) }
                    Spacer(Modifier.height(18.dp))
                } else {
                    // Un plan viejo no tiene pasos: se agregaron después y no se pueden inventar
                    // sobre un plan ya hecho sin volver a pensarlo. Decirlo es mejor que dejar el
                    // hueco, porque si no parece que la tarea no tuviera nada adentro.
                    Seccion(t("impl.steps", 0))
                    Text(
                        t("impl.noSteps"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(18.dp))
                }

                // El contexto acumulado: lo que de verdad pasó, observado mientras pasaba.
                //
                // Es distinto de "qué hizo", que es lo que el modelo cuenta al final. Esto se
                // escribe mientras corre, así que existe incluso cuando la tarea se cortó y no
                // llegó a contar nada — que es justamente cuando hace falta.
                val contexto = io.acr.ui.dbState(task.id, task.updatedAt, tic, initial = emptyList<io.acr.impl.ContextEntry>()) {
                    ctx.jobs2.contextOf(task.id)
                }
                if (contexto.isNotEmpty()) {
                    Seccion(t("impl.context"))
                    Text(
                        t("impl.contextNote"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    contexto.forEach { e -> ContextoFila(e) }
                    Spacer(Modifier.height(18.dp))
                }

                task.result?.takeIf { it.isNotBlank() }?.let {
                    Seccion(t("impl.whatItDid"))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(18.dp))
                }

                task.error?.takeIf { it.isNotBlank() }?.let { err ->
                    val bloqueada = task.status == TaskStatus.BLOCKED
                    Seccion(if (bloqueada) t("impl.blockedOn") else t("impl.failedWith"))
                    // Barra de color al costado en vez de fondo relleno: el bloque de texto queda
                    // legible y el color dice de qué se trata sin gritar.
                    Row(Modifier.fillMaxWidth()) {
                        Box(
                            Modifier.width(3.dp).height(if (err.length > 120) 60.dp else 24.dp)
                                .background(
                                    if (bloqueada) io.acr.ui.stats.ChartColors.major
                                    else MaterialTheme.colorScheme.error,
                                ),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(err, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                .setContents(java.awt.datatransfer.StringSelection(err), null)
                        }) { Text(t("common.copyError")) }
                    }
                    Spacer(Modifier.height(18.dp))
                }

                // El commit cierra el relato: es la prueba de lo que quedó. Sin haber corrido no
                // hay nada que probar, así que no se muestra.
                if (yaCorrio) {
                    Seccion(t("impl.commit"))
                    CommitBloque(task, repo)
                }
            }

            // --- Derecha: los hechos ---
            Column(Modifier.weight(1f)) {
                if (!yaCorrio) {
                    // Lo que se puede decir de una tarea que todavía no corrió: cuánto se estimó,
                    // dónde va a correr y qué tiene que estar antes. Es lo que uno mira para
                    // decidir si el plan tiene sentido.
                    Panel {
                        Text(
                            t("impl.planned"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        repo?.let { Dato(t("impl.willRunIn"), it.name) }
                        Dato(
                            t("impl.needsFirst"),
                            task.dependsOn.takeIf { it.isNotEmpty() }
                                ?.joinToString(", ") { "#$it" } ?: t("impl.nothing"),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                if (analisis != null) {
                    Spacer(Modifier.height(12.dp))
                    Panel {
                        Text(
                            t("impl.composition"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        val trozos = analisis.byKind.entries
                            .sortedByDescending { it.value.second }
                            .mapIndexed { i, (tipo, v) ->
                                io.acr.ui.stats.Segment(
                                    v.second.toDouble(),
                                    io.acr.ui.stats.SLICE_COLORS[i % io.acr.ui.stats.SLICE_COLORS.size],
                                    t(tipo.labelKey),
                                )
                            }
                        // Barra apilada y no anillo: acá el espacio es angosto y lo que importa es
                        // la proporción entre cuatro categorías, no leer cada porción.
                        io.acr.ui.stats.BarRow(
                            segments = trozos,
                            max = trozos.sumOf { it.value },
                            height = 10.dp,
                        )
                        Spacer(Modifier.height(8.dp))
                        trozos.forEach { seg ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(8.dp).height(8.dp).background(seg.color))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    seg.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "%,.0f".format(seg.value),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    val senales = buildList {
                        if (!analisis.touchedTests && !analisis.onlyDocs) add(t("impl.noTests"))
                        if (analisis.concentration > 0.7 && analisis.biggestFile != null) {
                            add(
                                t(
                                    "impl.concentrated",
                                    Math.round(analisis.concentration * 100).toInt(),
                                    analisis.biggestFile.substringAfterLast('/'),
                                ),
                            )
                        }
                        if (analisis.sensitive.isNotEmpty()) add(t("impl.sensitive", analisis.sensitive.size))
                        if (analisis.onlyDocs) add(t("impl.onlyDocs"))
                    }
                    if (senales.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Panel(borde = io.acr.ui.stats.ChartColors.major) {
                            Text(
                                t("impl.signals"),
                                style = MaterialTheme.typography.labelSmall,
                                color = io.acr.ui.stats.ChartColors.major,
                            )
                            Spacer(Modifier.height(4.dp))
                            senales.forEach {
                                Text(
                                    "· $it",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- El código, a lo ancho ---
        if (!task.diff?.files.isNullOrEmpty()) {
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("impl.code", task.diff!!.filesTouched), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(10.dp))
                Text(
                    t("impl.codeNote"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            TaskDiffView(repo = repo, task = task)
        }

        // --- El prompt, plegado ---
        // Es largo y casi siempre no se mira, así que va cerrado y al final. Pero cuando una tarea
        // hizo algo raro, es lo único que distingue un problema del modelo de un problema de lo que
        // se le pidió, y sin guardarlo esa pregunta no se puede contestar nunca.
        task.prompt?.takeIf { it.isNotBlank() }?.let { p ->
            Spacer(Modifier.height(24.dp))
            var abierto by remember(task.id) { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { abierto = !abierto }) {
                    Text((if (abierto) "▾  " else "▸  ") + t("impl.prompt"))
                }
                Text(
                    t("impl.promptNote"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (abierto) {
                    TextButton(onClick = {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(java.awt.datatransfer.StringSelection(p), null)
                    }) { Text(t("impl.copyPrompt")) }
                }
            }
            if (abierto) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SelectionContainer {
                        Text(
                            p,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            ),
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** El estado, grande y con su nombre: es lo primero que hay que poder leer sin buscar. */
@Composable
private fun EstadoGrande(s: TaskStatus) {
    val color = colorDeEstado(s)
    Column(
        Modifier.width(120.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(marcaDe(s), style = MaterialTheme.typography.headlineSmall, color = color)
        Text(estadoTexto(s), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/**
 * Un panel de datos.
 *
 * Fondo apenas distinto y no una caja gris fuerte: con cuatro paneles apilados, el gris pleno
 * convierte la columna en un bloque uniforme donde no se distingue dónde termina uno y empieza el
 * otro.
 */
@Composable
private fun Panel(borde: Color? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                borde?.copy(alpha = 0.08f) ?: MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            )
            .padding(12.dp),
    ) { content() }
}

/** Etiqueta a la izquierda, valor a la derecha: se barren los valores sin leer las etiquetas. */
@Composable
private fun Dato(etiqueta: String, valor: String, alerta: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(
            etiqueta,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            valor,
            style = MaterialTheme.typography.bodySmall,
            color = if (alerta) io.acr.ui.stats.ChartColors.major else MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/** Título de sección del relato. */
@Composable
private fun Seccion(texto: String) {
    Text(
        texto.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun CommitBloque(task: ImplTask, repo: RepoRecord?) {
    val commit = io.acr.ui.dbState(task.id, initial = null as io.acr.claude.Git.Commit?) {
        val sha = task.commitSha ?: return@dbState null
        repo?.let {
            kotlinx.coroutines.runBlocking {
                io.acr.claude.Git.commitsBetween(java.io.File(it.localPath), "$sha~1", sha).firstOrNull()
            }
        }
    }
    if (task.commitSha == null) {
        Text(
            if (task.status == TaskStatus.DONE) t("impl.noChanges") else t("impl.notCommitted"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            task.commitSha.take(10),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        commit?.let {
            Spacer(Modifier.width(10.dp))
            Text(
                it.date + "  ·  " + it.author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(java.awt.datatransfer.StringSelection(task.commitSha), null)
        }) { Text(t("impl.copySha")) }
    }
    commit?.subject?.takeIf { it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
    Text(
        t("impl.localOnly"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun marcaDe(s: TaskStatus): String = when (s) {
    TaskStatus.DONE -> "✓"
    TaskStatus.RUNNING -> "▶"
    TaskStatus.FAILED -> "✗"
    TaskStatus.BLOCKED -> "⏸"
    TaskStatus.SKIPPED -> "–"
    TaskStatus.PENDING -> "·"
}

@Composable
internal fun colorDeEstado(s: TaskStatus) = io.acr.ui.impl.StatusColors.of(s)

@Composable
internal fun estadoTexto(s: TaskStatus): String = when (s) {
    TaskStatus.PENDING -> t("task.st.pending")
    TaskStatus.RUNNING -> t("task.st.running")
    TaskStatus.DONE -> t("task.st.done")
    TaskStatus.FAILED -> t("task.st.failed")
    TaskStatus.BLOCKED -> t("task.st.blocked")
    TaskStatus.SKIPPED -> t("task.st.skipped")
}

/** El estado con su símbolo y su nombre, para la tabla. */
@Composable
internal fun TaskStatusBadge(s: TaskStatus, conTexto: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(marcaDe(s), style = MaterialTheme.typography.labelMedium, color = colorDeEstado(s))
        if (conTexto) {
            Spacer(Modifier.width(4.dp))
            Text(estadoTexto(s), style = MaterialTheme.typography.labelSmall, color = colorDeEstado(s))
        }
    }
}

/**
 * Un paso, con su estado adelante.
 *
 * El tilde y la cruz van a la izquierda y no al final porque lo primero que se busca en una lista
 * de pasos es cuáles quedaron sin hacer, y eso se escanea por la columna, no leyendo cada línea.
 */
@Composable
private fun PasoFila(paso: io.acr.impl.ImplStep) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        val (simbolo, color) = when (paso.status) {
            TaskStatus.DONE -> "✓" to StatusColors.DONE
            TaskStatus.FAILED -> "✗" to StatusColors.FAILED
            TaskStatus.RUNNING -> "▸" to StatusColors.RUNNING
            else -> "·" to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(simbolo, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(20.dp))
        Text("${paso.seq}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(
                paso.title,
                style = MaterialTheme.typography.bodyMedium,
                // Lo que quedó sin hacer se atenúa pero no se tacha: tachado se lee como
                // descartado, y un paso sin hacer no está descartado, está pendiente de explicación.
                color = if (paso.status == TaskStatus.PENDING || paso.status == TaskStatus.FAILED) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            paso.note?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Fecha corta y local. La ISO completa con zona no se lee de un vistazo y acá se mira de reojo. */
private fun fecha(iso: String): String = runCatching {
    java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm"))
}.getOrDefault(iso.take(16))

/**
 * Un texto que puede traer enumeraciones, puesto en vertical.
 *
 * La marca se conserva tal cual venía —`1)`, `-`, `a)`— en vez de normalizarse a un bullet: si el
 * plan numeró, el número es parte del contenido y alguien lo va a usar para referirse a un item.
 */
@Composable
private fun Enumerado(texto: String) {
    val bloques = remember(texto) { io.acr.impl.splitBlocks(texto) }
    Column {
        bloques.forEach { b ->
            if (b.marker == null) {
                Text(
                    b.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            } else {
                Row(Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
                    Text(
                        b.marker,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Ancho fijo para que los textos queden alineados entre sí: con el ancho
                        // del marcador variando, la columna de texto baila y la lista deja de
                        // leerse como lista.
                        modifier = Modifier.width(26.dp),
                    )
                    Text(b.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Las cifras de la tarea, a lo ancho y en grande.
 *
 * Antes vivían en un panel de la columna derecha, en renglones etiqueta/valor, y eso se lee: hay que
 * recorrer cada línea para encontrar el número. Acá se barren. Son cinco y no diez a propósito: si
 * todo es una cifra destacada, ninguna lo es.
 *
 * El tiempo lleva su estimación al lado porque el número solo no dice nada —cuarenta minutos puede
 * ser rapidísimo o el doble de lo previsto— y se marca en rojo sólo cuando se pasó.
 */
@Composable
private fun TiraDeCifras(task: ImplTask, yaCorrio: Boolean) {
    val real = task.actualMin ?: task.runningMin()
    val est = task.estimateMin ?: task.size?.minutes
    val pasosHechos = task.steps.count { it.status == TaskStatus.DONE }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Cifra(
            etiqueta = t("impl.thTime"),
            valor = when {
                real != null -> "${real.toInt()}′"
                est != null -> "~$est′"
                else -> "—"
            },
            nota = est?.takeIf { real != null }?.let { t("impl.ofEstimated", it) },
            alerta = real != null && est != null && real > est,
            modifier = Modifier.weight(1f),
        )
        Cifra(
            etiqueta = t("impl.mCost"),
            valor = task.costUsd?.let { "$" + "%.2f".format(it) } ?: "—",
            modifier = Modifier.weight(1f),
        )
        if (task.steps.isNotEmpty()) {
            Cifra(
                etiqueta = t("impl.stepsShort"),
                valor = "$pasosHechos/${task.steps.size}",
                // Un paso sin hacer en una tarea terminada es lo que hay que mirar: la tarea dice
                // que salió bien y algo de lo que se propuso no se hizo.
                alerta = yaCorrio && pasosHechos < task.steps.size,
                modifier = Modifier.weight(1f),
            )
        }
        Cifra(
            etiqueta = t("impl.thFiles"),
            valor = task.diff?.filesTouched?.toString() ?: "—",
            nota = task.diff?.let { d ->
                listOfNotNull(
                    d.filesAdded.takeIf { it > 0 }?.let { "+$it" },
                    d.filesModified.takeIf { it > 0 }?.let { "~$it" },
                    d.filesDeleted.takeIf { it > 0 }?.let { "−$it" },
                ).joinToString(" ").takeIf { it.isNotBlank() }
            },
            modifier = Modifier.weight(1f),
        )
        Cifra(
            etiqueta = t("impl.mLines"),
            valor = task.diff?.let { "+${it.linesAdded}" } ?: "—",
            nota = task.diff?.let { "−${it.linesDeleted}" },
            modifier = Modifier.weight(1f),
        )
    }
}

/** Una cifra con su etiqueta arriba y, si aporta, una nota chica debajo. */
@Composable
private fun Cifra(
    etiqueta: String,
    valor: String,
    modifier: Modifier = Modifier,
    nota: String? = null,
    alerta: Boolean = false,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            etiqueta,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            valor,
            style = MaterialTheme.typography.titleMedium,
            color = if (alerta) StatusColors.FAILED else MaterialTheme.colorScheme.onSurface,
        )
        nota?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * La vida de la tarea en una línea: creada, arrancó, terminó.
 *
 * Las cuatro fechas sueltas obligaban a restarlas mentalmente. Lo que importa no son los instantes
 * sino los dos tramos: cuánto esperó antes de arrancar —que en una implementación larga suele ser
 * más de lo que tardó en correr— y cuánto tardó. Puestos como línea, eso se ve sin hacer cuentas.
 *
 * Modificada no entra: es la única de las cuatro que no habla del recorrido de la tarea sino de si
 * el plan se rehízo, y mezclarla acá haría parecer que pasó algo cuando no pasó nada.
 */
@Composable
private fun LineaDeVida(task: ImplTask) {
    val creada = task.createdAt?.let { instante(it) }
    val arranco = task.startedAt?.let { instante(it) }
    val termino = task.finishedAt?.let { instante(it) }
    if (creada == null && arranco == null) return

    val espera = if (creada != null && arranco != null) {
        java.time.Duration.between(creada, arranco).toMinutes()
    } else {
        null
    }
    val corrida = if (arranco != null && termino != null) {
        java.time.Duration.between(arranco, termino).toMinutes()
    } else {
        null
    }

    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Hito(t("impl.dCreated"), task.createdAt)
        Tramo(espera?.let { t("impl.waited", it) })
        Hito(t("impl.dStarted"), task.startedAt)
        Tramo(corrida?.let { t("impl.ran", it) })
        Hito(t("impl.dFinished"), task.finishedAt)
        task.updatedAt?.takeIf { it != task.createdAt && it != task.finishedAt }?.let {
            Spacer(Modifier.width(16.dp))
            Text(
                t("impl.dUpdated") + ": " + fecha(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Un punto de la línea de vida. Vacío si todavía no pasó: el hueco dice tanto como la fecha. */
@Composable
private fun Hito(etiqueta: String, iso: String?) {
    Column {
        Text(
            etiqueta,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            iso?.let { fecha(it) } ?: "—",
            style = MaterialTheme.typography.bodySmall,
            color = if (iso == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** El tramo entre dos hitos, con su duración encima de la línea. */
@Composable
private fun Tramo(texto: String?) {
    Column(
        Modifier.width(140.dp).padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            texto.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier.fillMaxWidth().height(2.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

private fun instante(iso: String): java.time.Instant? =
    runCatching { java.time.Instant.parse(iso) }.getOrNull()

/**
 * Una entrada del contexto, con su marca según de qué habla.
 *
 * Las marcas separan lo que se lee distinto: un archivo tocado se barre, un corte se lee. Sin
 * distinguirlas, treinta renglones iguales esconden el único que explica por qué la tarea está
 * donde está.
 */
@Composable
private fun ContextoFila(e: io.acr.impl.ContextEntry) {
    val (marca, color) = when (e.kind) {
        io.acr.impl.ContextKind.FILE -> "✎" to MaterialTheme.colorScheme.onSurfaceVariant
        io.acr.impl.ContextKind.CMD -> "$" to MaterialTheme.colorScheme.onSurfaceVariant
        io.acr.impl.ContextKind.STEP -> "✓" to StatusColors.DONE
        io.acr.impl.ContextKind.DECISION -> "◆" to StatusColors.NEEDS_HUMAN
        io.acr.impl.ContextKind.INTERRUPT -> "⏸" to StatusColors.NEEDS_HUMAN
        io.acr.impl.ContextKind.RESUME -> "↻" to StatusColors.RUNNING
        io.acr.impl.ContextKind.NOTE -> "·" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(marca, color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(18.dp))
        Text(
            e.text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = if (e.kind == io.acr.impl.ContextKind.FILE || e.kind == io.acr.impl.ContextKind.CMD) {
                    androidx.compose.ui.text.font.FontFamily.Monospace
                } else {
                    androidx.compose.ui.text.font.FontFamily.Default
                },
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}
