package io.acr.ui.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.acr.AppContext
import io.acr.i18n.t
import io.acr.impl.ImplStatus
import io.acr.impl.TaskStatus
import io.acr.impl.fraction
import io.acr.impl.progressOf
import io.acr.impl.runningMin
import kotlinx.coroutines.launch

/**
 * Una implementación en detalle: su plan, su avance, y las decisiones que espera.
 *
 * Las preguntas van arriba de todo cuando las hay. Es lo único que frena una implementación
 * autónoma, así que si no se ve primero, la corrida queda esperando y quien mira la pantalla no
 * entiende por qué no avanza.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImplDetail(
    ctx: AppContext,
    repos: List<io.acr.forge.RepoRecord>,
    implId: String,
    onBack: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var version by remember(implId) { mutableStateOf(0) }
    val progreso by ctx.implEngine.progress.collectAsState()
    val vivo = progreso[implId]

    val impl = io.acr.ui.dbState(implId, version, vivo, initial = null as io.acr.impl.Implementation?) {
        ctx.impls.get(implId)
    } ?: return
    val tareas = io.acr.ui.dbState(implId, version, vivo, initial = emptyList<io.acr.impl.ImplTask>()) {
        ctx.impls.tasks(implId)
    }
    val preguntas = io.acr.ui.dbState(implId, version, vivo, initial = emptyList<io.acr.impl.ImplQuestion>()) {
        ctx.impls.questions(implId)
    }
    // Un tic por segundo mientras algo corre: sin esto los minutos y la barra sólo se actualizan
    // cuando una tarea termina, y entre medio la pantalla parece colgada.
    var tic by remember(implId) { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(corriendoAlgo(tareas)) {
        while (corriendoAlgo(tareas)) {
            kotlinx.coroutines.delay(1_000)
            tic++
        }
    }
    val avance = remember(tareas, tic) { progressOf(tareas) }
    // Animado: el salto de una tarea a la siguiente se lee como movimiento y no como un parpadeo.
    val fraccion by androidx.compose.animation.core.animateFloatAsState(
        targetValue = remember(tareas, tic) { avance.fraction(tareas) },
        animationSpec = androidx.compose.animation.core.tween(600),
        label = "avance",
    )
    val suyos = io.acr.ui.dbState(implId, version, initial = emptyList<io.acr.impl.ImplRepo>()) {
        ctx.impls.reposOf(implId)
    }
    // En el orden en que se cargaron: el primero es el principal y el plan arranca mirándolo.
    val misRepos = remember(suyos, repos) { suyos.mapNotNull { r -> repos.firstOrNull { it.id == r.repoId } } }
    val repo = misRepos.firstOrNull()
    val corriendo = impl.status == ImplStatus.RUNNING || impl.status == ImplStatus.PLANNING

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(t("common.back")) }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(impl.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(
                        misRepos.joinToString(" + ") { it.name }.takeIf { it.isNotBlank() },
                        impl.branch,
                        impl.codeModel ?: io.acr.impl.ImplEngine.CODE_MODEL,
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            EstadoBadge(impl.status)
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (corriendo) {
                OutlinedButton(onClick = { ctx.implEngine.cancel(implId) }) { Text(t("impl.stop")) }
                CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
            } else {
                Button(
                    enabled = repo != null,
                    onClick = {
                        scope.launch {
                            // De las specs al código de una sola vez: planifica y sigue. Frenar
                            // para aprobar el plan tendría sentido si alguien fuera a mirarlo, y
                            // el punto del módulo es que no haga falta.
                            ctx.appScope.launch {
                                if (impl.branch == null) ctx.implEngine.planAndRun(misRepos, implId)
                                else ctx.implEngine.run(misRepos, implId)
                                version++
                            }
                        }
                    },
                ) {
                    Text(
                        when (impl.status) {
                            ImplStatus.DRAFT -> t("impl.start")
                            ImplStatus.AWAITING -> t("impl.continue")
                            else -> t("impl.resume")
                        },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            impl.costUsd?.let {
                Text(
                    "US$ " + "%.2f".format(it),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        impl.error?.let { err ->
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                // Copiable: estos errores vienen del CLI y son literales que hay que buscar o
                // pegar en otro lado —"unrecognized_model" fue exactamente eso—. Transcribirlos a
                // mano de una pantalla es donde se pierde el detalle que importa.
                TextButton(onClick = {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(java.awt.datatransfer.StringSelection(err), null)
                }) { Text(t("common.copyError")) }
            }
        }

        // --- Decisiones pendientes: lo único que frena ---
        val abiertas = preguntas.filter { it.answer.isNullOrBlank() }
        if (abiertas.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(t("impl.questions"), style = MaterialTheme.typography.titleSmall)
            Text(
                t("impl.questionsNote"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            abiertas.forEach { q -> QuestionCard(ctx, q) { version++ } }
        }

        // --- Avance ---
        if (avance.total > 0) {
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { fraccion },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                t("impl.progress", avance.done, avance.total) +
                    "  ·  " + t("impl.estimated", avance.estimatedMin) +
                    "  ·  " + t("impl.elapsed", avance.elapsedMin.toInt()) + tiempo(avance),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        impl.planSummary?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(10.dp))
            io.acr.ui.CollapsibleCard(t("impl.plan"), ctx.prefs, "implplan-$implId", maxHeight = 180.dp) {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }

        // --- Tareas ---
        Spacer(Modifier.height(12.dp))
        tareas.forEach { tar ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                Text(
                    marca(tar.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = colorDe(tar.status),
                    modifier = Modifier.width(26.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "${tar.seq}. ${tar.title}" +
                            // Con un solo repositorio el nombre no aporta; con varios es lo
                            // primero que uno busca al leer una tarea.
                            (if (misRepos.size > 1) {
                                misRepos.firstOrNull { it.id == tar.repoId }?.let { "  [${it.name}]" }.orEmpty()
                            } else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tar.status == TaskStatus.PENDING)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    // Estimado contra real, en la misma línea: es la única forma de que la
                    // estimación mejore, porque se ve cuánto se equivocó.
                    val est = tar.estimateMin ?: tar.size?.minutes
                    val real = tar.actualMin
                    // La que corre muestra su propio reloj contra su estimación: es lo único que
                    // dice si esta tarea puntual se está yendo de largo.
                    tar.runningMin()?.let { va ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(
                                progress = {
                                    if (est == null || est <= 0) 0f
                                    else (va / est).coerceIn(0.0, 1.0).toFloat()
                                },
                                modifier = Modifier.width(120.dp).height(3.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                t("impl.runningFor", va.toInt()) +
                                    (est?.let { " / $it" }.orEmpty()) +
                                    (if (est != null && va > est) "  " + t("impl.overrun") else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (est != null && va > est) io.acr.ui.stats.ChartColors.major
                                else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (est != null || real != null) {
                        Text(
                            listOfNotNull(
                                est?.let { t("impl.est", it) },
                                real?.let { t("impl.real", it.toInt()) },
                                tar.commitSha?.take(7),
                            ).joinToString("  ·  "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    tar.error?.takeIf { it.isNotBlank() }?.let { err ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                err.take(240),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (tar.status == TaskStatus.BLOCKED)
                                    io.acr.ui.stats.ChartColors.major
                                else MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            if (tar.status == TaskStatus.FAILED) {
                                TextButton(onClick = {
                                    java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                        .setContents(java.awt.datatransfer.StringSelection(err), null)
                                }) { Text(t("common.copyError")) }
                            }
                        }
                    }
                }
                if (tar.status == TaskStatus.FAILED) {
                    TextButton(onClick = { ctx.impls.resetTask(tar.id); version++ }) {
                        Text(t("impl.retry"))
                    }
                }
            }
        }

        // --- Feed en vivo ---
        vivo?.lines?.takeIf { it.isNotEmpty() }?.let { lineas ->
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth().heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                lineas.takeLast(80).forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Una decisión que la implementación no puede tomar sola.
 *
 * Las opciones se ofrecen como botones además del campo libre: contestar eligiendo es más rápido
 * y, sobre todo, contesta exactamente lo que se preguntó. Un campo de texto solo invita a escribir
 * una respuesta que después hay que interpretar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(ctx: AppContext, q: io.acr.impl.ImplQuestion, onAnswered: () -> Unit) {
    var texto by remember(q.id) { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Text(
            t(q.kind.labelKey),
            style = MaterialTheme.typography.labelSmall,
            color = io.acr.ui.stats.ChartColors.major,
        )
        Text(q.question, style = MaterialTheme.typography.bodyMedium)
        q.context?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (q.options.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                q.options.forEach { op ->
                    OutlinedButton(onClick = { ctx.impls.answer(q.id, op); onAnswered() }) {
                        Text(op, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = texto,
                onValueChange = { texto = it },
                label = { Text(t("impl.answer")) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            TextButton(
                enabled = texto.isNotBlank(),
                onClick = { ctx.impls.answer(q.id, texto.trim()); onAnswered() },
            ) { Text(t("common.save")) }
        }
    }
}

/** ¿Hay algo corriendo? Decide si el reloj tiene que seguir tictaqueando. */
private fun corriendoAlgo(tareas: List<io.acr.impl.ImplTask>): Boolean =
    tareas.any { it.status == TaskStatus.RUNNING }

private fun marca(s: TaskStatus): String = when (s) {
    TaskStatus.DONE -> "✓"
    TaskStatus.RUNNING -> "▶"
    TaskStatus.FAILED -> "✗"
    TaskStatus.BLOCKED -> "⏸"
    TaskStatus.SKIPPED -> "–"
    TaskStatus.PENDING -> "·"
}

@Composable
private fun colorDe(s: TaskStatus) = when (s) {
    TaskStatus.DONE -> io.acr.ui.stats.ChartColors.added
    TaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
    TaskStatus.FAILED -> MaterialTheme.colorScheme.error
    TaskStatus.BLOCKED -> io.acr.ui.stats.ChartColors.major
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
