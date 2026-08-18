package io.acr.ui.impl

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.acr.AppContext
import io.acr.i18n.t
import io.acr.impl.Job
import io.acr.impl.JobState

/**
 * Qué hay corriendo, en toda la app.
 *
 * Existe porque el estado de una tarea no alcanza para contestar la pregunta que importa después de
 * un corte: **lo que figura corriendo, ¿está vivo o es un cadáver?** La tarea quedó en RUNNING en
 * los dos casos. El job late, y un job sin latido reciente está muerto — eso sí se puede afirmar.
 *
 * La pantalla ordena por eso: primero lo que está vivo, después lo que se quedó sin latir, después
 * lo cerrado. Y muestra la edad del latido en vez de la hora, porque lo que se decide mirando esto
 * es si hace falta hacer algo ahora, no cuándo pasó.
 */
@Composable
fun JobsPanel(ctx: AppContext) {
    var version by remember { mutableStateOf(0) }
    // Late en la pantalla también: sin esto la edad de cada latido se congela en el momento en que
    // se abrió, que es justo el número que uno vino a mirar.
    var tic by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5_000)
            tic++
        }
    }

    val impls = io.acr.ui.dbState(version, initial = emptyList<io.acr.impl.Implementation>()) {
        ctx.impls.list()
    }
    val titulos = remember(impls) { impls.associate { it.id to it.title } }
    val jobs = io.acr.ui.dbState(version, tic, initial = emptyList<Job>()) { ctx.jobs2.all() }

    val ahora = java.time.Instant.now()
    val vivos = jobs.filter { it.state == JobState.RUNNING && !it.stale(ahora) }
    val muertos = jobs.filter { it.state == JobState.RUNNING && it.stale(ahora) }
    val cerrados = jobs.filter { it.state != JobState.RUNNING }
        .sortedByDescending { it.finishedAt ?: it.createdAt }
        .take(60)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(t("jobs.title"), style = MaterialTheme.typography.titleMedium)
        Text(
            t("jobs.note"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        if (vivos.isEmpty() && muertos.isEmpty()) {
            Text(
                t("jobs.none"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (vivos.isNotEmpty()) {
            Seccion(t("jobs.alive", vivos.size))
            vivos.forEach { Fila(ctx, it, titulos, ahora) { version++ } }
            Spacer(Modifier.height(16.dp))
        }

        // Los que dicen correr pero no laten. Es la única fila de esta pantalla que pide una
        // decisión, así que va aparte y no mezclada con las vivas.
        if (muertos.isNotEmpty()) {
            Seccion(t("jobs.stale", muertos.size))
            Text(
                t("jobs.staleNote"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            muertos.forEach { Fila(ctx, it, titulos, ahora) { version++ } }
            Spacer(Modifier.height(16.dp))
        }

        if (cerrados.isNotEmpty()) {
            Seccion(t("jobs.closed", cerrados.size))
            cerrados.forEach { Fila(ctx, it, titulos, ahora) { version++ } }
        }
    }
}

@Composable
private fun Seccion(texto: String) {
    Text(texto, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Fila(
    ctx: AppContext,
    job: Job,
    titulos: Map<String, String>,
    ahora: java.time.Instant,
    onChange: () -> Unit,
) {
    val muerto = job.stale(ahora)
    // El contexto de la tarea que este job estaba corriendo.
    //
    // Es lo que contesta la pregunta que trae a alguien acá cuando ve un job muerto: "¿qué alcanzó
    // a hacer antes de cortarse?". Sin esto, la fila dice que algo se interrumpió y no dice nada
    // sobre qué quedó a medias — que es justamente lo que hay que saber para decidir si retomar.
    var abierto by remember(job.id) { mutableStateOf(false) }
    val contexto = if (abierto && job.taskId != null) {
        io.acr.ui.dbState(job.taskId, abierto, initial = emptyList<io.acr.impl.ContextEntry>()) {
            ctx.jobs2.contextOf(job.taskId)
        }
    } else {
        emptyList()
    }
    val tarea = if (abierto && job.taskId != null) {
        io.acr.ui.dbState(job.taskId, abierto, initial = null as io.acr.impl.ImplTask?) {
            ctx.impls.tasks(job.implId).firstOrNull { it.id == job.taskId }
        }
    } else {
        null
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                job.kind.name,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(70.dp),
            )
            Text(
                estadoTexto(job, muerto),
                style = MaterialTheme.typography.labelSmall,
                color = colorDe(job.state, muerto),
                modifier = Modifier.width(110.dp),
            )
            Text(
                (if (job.taskId != null) (if (abierto) "▾  " else "▸  ") else "") +
                    (titulos[job.implId] ?: job.implId.take(8)),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.weight(1f)
                    .then(
                        if (job.taskId != null) {
                            Modifier.clickable { abierto = !abierto }
                        } else {
                            Modifier
                        },
                    ),
            )
            // El intento importa: un job que va por el tercero está contando una historia distinta
            // de uno que arrancó recién, aunque los dos digan "corriendo".
            if (job.attempt > 1) {
                Text(
                    t("jobs.attempt", job.attempt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(80.dp),
                )
            }
            Text(
                edad(job, ahora),
                style = MaterialTheme.typography.labelSmall,
                color = if (muerto) StatusColors.FAILED else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(90.dp),
            )
            if (job.state == JobState.RUNNING) {
                TextButton(onClick = {
                    // Frenar la implementación entera y no este job suelto: matar un proceso sin
                    // que el motor se entere lo dejaría relanzándolo en la vuelta siguiente.
                    ctx.implEngine.cancel(job.implId)
                    if (muerto) ctx.jobs2.finish(job.id, JobState.INTERRUPTED, "Cerrado a mano.")
                    onChange()
                }) { Text(if (muerto) t("jobs.close") else t("impl.stop")) }
            }
        }
        // La sesión es lo que permite retomar de verdad: sin ella, un intento nuevo empieza de cero
        // con el contexto acumulado. Se muestra para poder saber cuál de los dos casos es.
        val detalle = listOfNotNull(
            job.sessionId?.take(8)?.let { t("jobs.session", it) },
            job.pid?.let { "pid $it" },
            job.error?.take(120),
        )
        if (detalle.isNotEmpty()) {
            Text(
                detalle.joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 70.dp),
            )
        }
        if (abierto) {
            Column(Modifier.fillMaxWidth().padding(start = 70.dp, top = 4.dp, bottom = 6.dp)) {
                tarea?.let {
                    Text(
                        "#${it.seq}  ${it.title}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (contexto.isEmpty()) {
                    Text(
                        t("impl.contextEmpty"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        t("impl.contextNote"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(3.dp))
                    contexto.forEach { e ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                marcaDe(e.kind),
                                style = MaterialTheme.typography.labelSmall,
                                color = colorDeContexto(e.kind),
                                modifier = Modifier.width(18.dp),
                            )
                            Text(
                                e.text,
                                style = MaterialTheme.typography.labelSmall
                                    .copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    }
}

/** La marca de cada clase de hecho. Las mismas que en el detalle de la tarea. */
private fun marcaDe(k: io.acr.impl.ContextKind): String = when (k) {
    io.acr.impl.ContextKind.FILE -> "✎"
    io.acr.impl.ContextKind.CMD -> "$"
    io.acr.impl.ContextKind.STEP -> "✓"
    io.acr.impl.ContextKind.DECISION -> "◆"
    io.acr.impl.ContextKind.INTERRUPT -> "⏸"
    io.acr.impl.ContextKind.RESUME -> "↻"
    io.acr.impl.ContextKind.NOTE -> "·"
}

@Composable
private fun colorDeContexto(k: io.acr.impl.ContextKind) = when (k) {
    io.acr.impl.ContextKind.STEP -> StatusColors.DONE
    io.acr.impl.ContextKind.INTERRUPT, io.acr.impl.ContextKind.DECISION -> StatusColors.NEEDS_HUMAN
    io.acr.impl.ContextKind.RESUME -> StatusColors.RUNNING
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun estadoTexto(job: Job, muerto: Boolean): String =
    if (muerto) t("jobs.st.dead") else t("jobs.st." + job.state.name.lowercase())

@Composable
private fun colorDe(s: JobState, muerto: Boolean) = when {
    muerto -> StatusColors.FAILED
    s == JobState.RUNNING -> StatusColors.RUNNING
    s == JobState.DONE -> StatusColors.DONE
    s == JobState.FAILED -> StatusColors.FAILED
    s == JobState.PAUSED || s == JobState.INTERRUPTED -> StatusColors.NEEDS_HUMAN
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Hace cuánto late, o hace cuánto terminó.
 *
 * En edad y no en hora: lo que se decide mirando esta pantalla es si hay que hacer algo ahora, y
 * para eso "hace 3 min" se lee de un vistazo mientras que "14:32" hay que restarlo.
 */
@Composable
private fun edad(job: Job, ahora: java.time.Instant): String {
    val ref = (if (job.state == JobState.RUNNING) job.heartbeatAt ?: job.startedAt else job.finishedAt)
        ?: return "—"
    val i = runCatching { java.time.Instant.parse(ref) }.getOrNull() ?: return "—"
    val seg = java.time.Duration.between(i, ahora).seconds
    return when {
        seg < 60 -> t("jobs.secondsAgo", seg)
        seg < 3_600 -> t("jobs.minutesAgo", seg / 60)
        else -> t("jobs.hoursAgo", seg / 3_600)
    }
}
