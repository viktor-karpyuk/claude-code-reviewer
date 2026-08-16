package io.acr.ui.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.acr.AppContext
import io.acr.forge.RepoRecord
import io.acr.i18n.t
import io.acr.impl.ImplTask
import io.acr.impl.TaskStatus
import io.acr.impl.runningMin

/**
 * Todo lo que pasó en una tarea, en su propia pantalla.
 *
 * Era una fila que se expandía, y para una tarea que tocó quince archivos eso significaba empujar
 * el resto de la tabla fuera de la vista para leer algo que igual no entraba. Lo que se hizo en una
 * tarea es un tema en sí mismo: lo que se pidió, lo que se escribió, cuánto costó y qué quedó
 * commiteado.
 *
 * El orden responde a cómo se revisa: primero qué había que hacer, después qué dice que hizo,
 * después el commit —que es la prueba— y recién ahí el código, que es lo que lleva tiempo mirar.
 */
@Composable
fun TaskDetailScreen(
    ctx: AppContext,
    task: ImplTask,
    repo: RepoRecord?,
    /** Nombre de la implementación, para el camino de vuelta. */
    implTitle: String,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        // El camino, no un botón: con tres niveles, "volver" obliga a apretarlo dos veces y a
        // adivinar dónde cae.
        io.acr.ui.Breadcrumbs(
            listOf(
                io.acr.ui.Crumb(t("nav.impls"), onHome),
                io.acr.ui.Crumb(implTitle, onBack),
                io.acr.ui.Crumb("${task.seq}. ${task.title}"),
            ),
            Modifier.padding(bottom = 10.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                marcaDe(task.status),
                style = MaterialTheme.typography.titleMedium,
                color = colorDeEstado(task.status),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("${task.seq}. ${task.title}", style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(repo?.name, estadoTexto(task.status)).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (task.status == TaskStatus.FAILED) {
                TextButton(onClick = onRetry) { Text(t("impl.retry")) }
            }
        }

        // --- Cifras de la tarea ---
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val real = task.actualMin ?: task.runningMin()
            val est = task.estimateMin ?: task.size?.minutes
            Cifra(
                t("impl.thTime"),
                (real?.let { "${it.toInt()}′" } ?: "—") + (est?.let { " / $it′" }.orEmpty()),
                // Se marca sólo si se pasó: la estimación es una apuesta y clavarla no es noticia.
                alerta = real != null && est != null && real > est,
            )
            task.costUsd?.let { Cifra(t("impl.mCost"), "$" + "%.2f".format(it)) }
            task.diff?.let { d ->
                Cifra(t("impl.mNew"), d.filesAdded.toString())
                Cifra(t("impl.mChanged"), d.filesModified.toString())
                if (d.filesDeleted > 0) Cifra(t("impl.mDeleted"), d.filesDeleted.toString())
                Cifra(t("impl.mLines"), "+${d.linesAdded} / −${d.linesDeleted}")
            }
        }

        // --- Lo que se pidió ---
        task.detail.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(16.dp))
            Bloque(t("impl.whatToDo")) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        task.dependsOn.takeIf { it.isNotEmpty() }?.let { deps ->
            Text(
                t("impl.dependsOn", deps.joinToString(", ")),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // --- Lo que dice que hizo ---
        task.result?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(14.dp))
            Bloque(t("impl.whatItDid")) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        task.error?.takeIf { it.isNotBlank() }?.let { err ->
            Spacer(Modifier.height(14.dp))
            Bloque(
                if (task.status == TaskStatus.BLOCKED) t("impl.blockedOn") else t("impl.failedWith"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (task.status == TaskStatus.BLOCKED)
                            io.acr.ui.stats.ChartColors.major
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(java.awt.datatransfer.StringSelection(err), null)
                    }) { Text(t("common.copyError")) }
                }
            }
        }

        // --- El commit: la prueba de lo que quedó ---
        Spacer(Modifier.height(14.dp))
        val commit = io.acr.ui.dbState(task.id, initial = null as io.acr.claude.Git.Commit?) {
            val sha = task.commitSha ?: return@dbState null
            repo?.let {
                kotlinx.coroutines.runBlocking {
                    io.acr.claude.Git.commitsBetween(java.io.File(it.localPath), "$sha~1", sha)
                        .firstOrNull()
                }
            }
        }
        Bloque(t("impl.commit")) {
            if (task.commitSha == null) {
                // No es lo mismo que no haya código a que no se haya commiteado. Una tarea que
                // terminó sin tocar nada existe —puede estar ya hecha— y hay que poder verlo.
                Text(
                    if (task.status == TaskStatus.DONE) t("impl.noChanges") else t("impl.notCommitted"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        task.commitSha.take(10),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    Spacer(Modifier.width(10.dp))
                    commit?.let {
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
                    }) { Text(t("common.copied").let { _ -> t("impl.copySha") }) }
                }
                commit?.subject?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                // Local y no empujado: el push lo decide una persona, y decirlo evita que alguien
                // lo busque en el remoto y no lo encuentre.
                Text(
                    t("impl.localOnly"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // --- De qué se compone el cambio ---
        val analisis = task.diff?.files?.let { io.acr.impl.analyze(it) }
        if (analisis != null) {
            Spacer(Modifier.height(14.dp))
            Text(t("impl.composition"), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            val trozos = analisis.byKind.entries
                .sortedByDescending { it.value.second }
                .mapIndexed { i, (tipo, v) ->
                    io.acr.ui.stats.Segment(
                        v.second.toDouble(),
                        io.acr.ui.stats.SLICE_COLORS[i % io.acr.ui.stats.SLICE_COLORS.size],
                        t(tipo.labelKey) + " (${v.first})",
                    )
                }
            Row(verticalAlignment = Alignment.CenterVertically) {
                io.acr.ui.stats.DonutChart(
                    trozos,
                    centerValue = "%,d".format(analisis.byKind.values.sumOf { it.second }),
                    centerLabel = t("stats.linesTouched"),
                    size = 112.dp,
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    io.acr.ui.stats.DonutLegend(trozos)
                    Spacer(Modifier.height(8.dp))
                    // Las señales: cada una responde algo que uno se pregunta al revisar código
                    // que escribió alguien sin supervisión.
                    if (!analisis.touchedTests && !analisis.onlyDocs) {
                        Senal(t("impl.noTests"), true)
                    }
                    if (analisis.concentration > 0.7 && analisis.biggestFile != null) {
                        Senal(
                            t(
                                "impl.concentrated",
                                Math.round(analisis.concentration * 100).toInt(),
                                analisis.biggestFile.substringAfterLast('/'),
                            ),
                            false,
                        )
                    }
                    if (analisis.sensitive.isNotEmpty()) {
                        Senal(t("impl.sensitive", analisis.sensitive.size), true)
                        analisis.sensitive.take(4).forEach {
                            Text(
                                "   " + it,
                                style = MaterialTheme.typography.labelSmall
                                    .copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (analisis.onlyDocs) Senal(t("impl.onlyDocs"), true)
                }
            }
        }

        // --- El código ---
        if (!task.diff?.files.isNullOrEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                t("impl.code", task.diff!!.filesTouched),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                t("impl.codeNote"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            HorizontalDivider()
            TaskDiffView(repo = repo, task = task)
        }
    }
}

/**
 * Una señal sobre el cambio.
 *
 * Se marcan en ámbar y no en rojo: no son errores, son cosas que conviene mirar. Pintarlas de
 * error haría que una tarea correcta parezca rota y, peor, que el rojo deje de significar algo.
 */
@Composable
private fun Senal(texto: String, atencion: Boolean) {
    Text(
        "· " + texto,
        style = MaterialTheme.typography.labelSmall,
        color = if (atencion) io.acr.ui.stats.ChartColors.major
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Un bloque con su título. */
@Composable
private fun Bloque(titulo: String, contenido: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Text(
            titulo,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        contenido()
    }
}

@Composable
private fun Cifra(titulo: String, valor: String, alerta: Boolean = false) {
    Column {
        Text(
            valor,
            style = MaterialTheme.typography.titleSmall,
            color = if (alerta) io.acr.ui.stats.ChartColors.major else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            titulo,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
internal fun colorDeEstado(s: TaskStatus) = when (s) {
    TaskStatus.DONE -> io.acr.ui.stats.ChartColors.added
    TaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
    TaskStatus.FAILED -> MaterialTheme.colorScheme.error
    TaskStatus.BLOCKED -> io.acr.ui.stats.ChartColors.major
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Cómo se llama cada estado de una tarea.
 *
 * Se nombran uno por uno y no se reusan los de la implementación: "pendiente" y "sin planificar"
 * no son lo mismo, y una tarea que espera una decisión no está fallada aunque las dos frenen.
 */
@Composable
internal fun estadoTexto(s: TaskStatus): String = when (s) {
    TaskStatus.PENDING -> t("task.st.pending")
    TaskStatus.RUNNING -> t("task.st.running")
    TaskStatus.DONE -> t("task.st.done")
    TaskStatus.FAILED -> t("task.st.failed")
    TaskStatus.BLOCKED -> t("task.st.blocked")
    TaskStatus.SKIPPED -> t("task.st.skipped")
}

/** El estado con su símbolo y su color, para que se lea igual en la tabla y en el detalle. */
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
