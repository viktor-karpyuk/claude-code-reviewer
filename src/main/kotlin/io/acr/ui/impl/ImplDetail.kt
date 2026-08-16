package io.acr.ui.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
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
    var editando by remember(implId) { mutableStateOf(false) }
    // Qué tarea se está mirando. Expandir la fila obligaba a empujar el resto de la tabla fuera de
    // la vista para leer algo que igual no entraba.
    var tareaAbierta by remember(implId) { mutableStateOf<String?>(null) }
    // Un tic por segundo mientras algo corre. Es la clave que hace que las lecturas de la base se
    // repitan: sin él, la pantalla depende de que el motor emita una línea de log para enterarse
    // de que una tarea cambió de estado.
    var tic by remember(implId) { mutableStateOf(0) }
    val progreso by ctx.implEngine.progress.collectAsState()
    val vivo = progreso[implId]

    val impl = io.acr.ui.dbState(implId, version, vivo, tic, initial = null as io.acr.impl.Implementation?) {
        ctx.impls.get(implId)
    } ?: return
    val tareas = io.acr.ui.dbState(implId, version, vivo, tic, initial = emptyList<io.acr.impl.ImplTask>()) {
        ctx.impls.tasks(implId)
    }
    val preguntas = io.acr.ui.dbState(implId, version, vivo, tic, initial = emptyList<io.acr.impl.ImplQuestion>()) {
        ctx.impls.questions(implId)
    }
    // Late mientras haya algo corriendo, esté abierta la lista o el detalle de una tarea: es lo
    // que hace que el estado persistido llegue a la pantalla sin tener que salir y volver.
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

    // La tarea abierta reemplaza a la pantalla: lo que pasó en una tarea es un tema en sí mismo y
    // no un desplegable dentro de una tabla.
    tareaAbierta?.let { id ->
        // Se busca en la lista que ya se releyó de la base en esta composición, y esa lista se
        // refresca con cada latido y con cada avance: así una tarea que cambia de estado mientras
        // su pantalla está abierta se actualiza sola en vez de mostrar la foto de cuando se abrió.
        tareas.firstOrNull { it.id == id }?.let { tar ->
            TaskDetailScreen(
                ctx = ctx,
                task = tar,
                repo = misRepos.firstOrNull { it.id == tar.repoId } ?: misRepos.firstOrNull(),
                implTitle = impl.title,
                onBack = { tareaAbierta = null; version++ },
                onHome = { tareaAbierta = null; onBack() },
                onRetry = { ctx.impls.resetTask(tar.id); tareaAbierta = null; version++ },
            )
            return
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        io.acr.ui.Breadcrumbs(
            listOf(io.acr.ui.Crumb(t("nav.impls"), onBack), io.acr.ui.Crumb(impl.title)),
            Modifier.padding(bottom = 10.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            // Ajustar sin perder lo hecho: cambiar un parámetro o sumar un repositorio no puede
            // obligar a empezar de cero.
            TextButton(onClick = { editando = true }) { Text(t("common.edit")) }
            EstadoBadge(impl.status)
        }

        if (editando) {
            EditImplDialog(
                ctx = ctx,
                repos = repos,
                impl = impl,
                actuales = suyos,
                onDismiss = { editando = false },
                onSaved = { editando = false; version++ },
            )
        }

        // De dónde parte cada repositorio y en qué rama trabaja. Estaba decidido en silencio por
        // la rama que el clon tuviera abierta, así que no había forma de saberlo sin ir a git.
        Spacer(Modifier.height(8.dp))
        misRepos.forEach { r ->
            val cfg = suyos.firstOrNull { it.repoId == r.id }
            val sucio = io.acr.ui.dbState(r.id, version, initial = false) {
                kotlinx.coroutines.runBlocking { io.acr.claude.Git.isDirty(java.io.File(r.localPath)) }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.name,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(190.dp),
                )
                Text(
                    t("impl.fromBranch", cfg?.baseBranch ?: t("impl.currentBranch")) +
                        (impl.branch?.let { "  →  $it" }.orEmpty()),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // Lo sucio se resuelve acá y no en la terminal: el stash no pierde nada y se
                // recupera con `git stash pop`, así que puede ser un botón.
                if (sucio) {
                    Text(
                        t("impl.dirty"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = {
                        ctx.appScope.launch { ctx.implEngine.stashDirty(r); version++ }
                    }) { Text(t("impl.stash")) }
                }
            }
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
            io.acr.ui.CollapsibleCard(t("impl.plan"), ctx.prefs, "implplan-$implId", maxHeight = 320.dp) {
                Column {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(14.dp))
                    PlanReview(
                        ctx = ctx,
                        impl = impl,
                        tasks = tareas,
                        repos = misRepos,
                        running = corriendo,
                        onDone = { version++ },
                    )
                }
            }
        }

        // --- Esfuerzo invertido ---
        val esfuerzo = remember(tareas) { io.acr.impl.effortOf(tareas) }
        if (esfuerzo.filesTouched > 0 || esfuerzo.minutes > 0) {
            Spacer(Modifier.height(12.dp))
            Text(t("impl.effort"), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Metrica(t("impl.mTime"), "${esfuerzo.minutes.toInt()} min")
                Metrica(t("impl.mCost"), "$" + "%.2f".format(esfuerzo.costUsd))
                // Creado y modificado separados: cuatro archivos nuevos son superficie nueva para
                // mirar entera, y dos modificados son un diff que leer. No son lo mismo.
                Metrica(t("impl.mNew"), esfuerzo.filesAdded.toString())
                Metrica(t("impl.mChanged"), esfuerzo.filesModified.toString())
                if (esfuerzo.filesDeleted > 0) Metrica(t("impl.mDeleted"), esfuerzo.filesDeleted.toString())
                Metrica(t("impl.mLines"), "+${esfuerzo.linesAdded} / −${esfuerzo.linesDeleted}")
            }
        }

        // --- Tareas, como tabla ---
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Cab("#", 40.dp)
            Cab(t("impl.thStatus"), 120.dp)
            Text(
                t("impl.thTask"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (misRepos.size > 1) Cab(t("impl.thRepo"), 110.dp)
            Cab(t("impl.thTime"), 110.dp)
            Cab(t("impl.thFiles"), 110.dp)
            Cab(t("impl.thLines"), 110.dp)
            Spacer(Modifier.width(34.dp))
        }
        HorizontalDivider()

        tareas.forEach { tar ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable { tareaAbierta = tar.id }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // El número en su propia columna: pegado al título se lee como parte del texto y
                // no sirve para lo que sirve un número de tarea, que es referenciarla.
                Text(
                    tar.seq.toString(),
                    style = MaterialTheme.typography.labelSmall
                        .copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(40.dp),
                )
                Row(Modifier.width(120.dp), verticalAlignment = Alignment.CenterVertically) {
                    TaskStatusBadge(tar.status)
                }
                Column(Modifier.weight(1f)) {
                    Text(tar.title, style = MaterialTheme.typography.bodySmall)
                    tar.runningMin()?.let { va ->
                        val est = tar.estimateMin ?: tar.size?.minutes
                        Text(
                            t("impl.runningFor", va.toInt()) + (est?.let { " / $it" }.orEmpty()) +
                                (if (est != null && va > est) "  " + t("impl.overrun") else ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (est != null && va > est) io.acr.ui.stats.ChartColors.major
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (misRepos.size > 1) {
                    Celda(misRepos.firstOrNull { it.id == tar.repoId }?.name.orEmpty(), 110.dp)
                }
                Celda(
                    tar.actualMin?.let { "${it.toInt()}′" }?.plus(
                        (tar.estimateMin ?: tar.size?.minutes)?.let { " / $it′" }.orEmpty(),
                    ) ?: (tar.estimateMin ?: tar.size?.minutes)?.let { "~$it′" } ?: "—",
                    110.dp,
                )
                Celda(
                    tar.diff?.let { d ->
                        listOfNotNull(
                            d.filesAdded.takeIf { it > 0 }?.let { "+$it" },
                            d.filesModified.takeIf { it > 0 }?.let { "~$it" },
                            d.filesDeleted.takeIf { it > 0 }?.let { "−$it" },
                        ).joinToString(" ").ifBlank { "—" }
                    } ?: "—",
                    110.dp,
                )
                Celda(tar.diff?.let { "+${it.linesAdded}/−${it.linesDeleted}" } ?: "—", 110.dp)
                Text(
                    "›",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(34.dp),
                )
            }
            HorizontalDivider()
        }

        // --- Commits de la rama ---
        impl.branch?.let { rama ->
            val commits = io.acr.ui.dbState(implId, version, tic, initial = emptyList<Pair<String, io.acr.claude.Git.Commit>>()) {
                kotlinx.coroutines.runBlocking {
                    misRepos.flatMap { r ->
                        val base = suyos.firstOrNull { it.repoId == r.id }?.baseBranch
                            ?: impl.baseBranch ?: "develop"
                        io.acr.claude.Git.commitsBetween(java.io.File(r.localPath), base, rama)
                            .map { r.name to it }
                    }
                }
            }
            if (commits.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                io.acr.ui.CollapsibleCard(
                    t("impl.commits", commits.size),
                    ctx.prefs,
                    "implcommits-$implId",
                    maxHeight = 240.dp,
                    defaultCollapsed = true,
                ) {
                    commits.forEach { (repoNombre, c) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                            Text(
                                c.sha.take(7),
                                style = MaterialTheme.typography.labelSmall
                                    .copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.width(70.dp),
                            )
                            if (misRepos.size > 1) {
                                Text(
                                    repoNombre,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(120.dp),
                                    maxLines = 1,
                                )
                            }
                            Text(
                                c.subject,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            Text(
                                c.date,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(84.dp),
                            )
                        }
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

/** Encabezado de columna de ancho fijo. */
@Composable
private fun Cab(texto: String, ancho: androidx.compose.ui.unit.Dp) {
    Text(
        texto,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(ancho),
    )
}

/** Una celda numérica de la tabla. */
@Composable
private fun Celda(texto: String, ancho: androidx.compose.ui.unit.Dp) {
    Text(
        texto,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(ancho),
    )
}

/** Una cifra del bloque de esfuerzo. */
@Composable
private fun Metrica(titulo: String, valor: String) {
    Column {
        Text(valor, style = MaterialTheme.typography.bodyMedium)
        Text(
            titulo,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
