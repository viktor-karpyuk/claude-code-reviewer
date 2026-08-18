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
    // Si esto viviera dentro del encabezado, la tarjeta de actividad no podría leerlo.
    var analizando by remember(implId) { mutableStateOf(false) }
    var viendoCommits by remember(implId) { mutableStateOf(false) }
    // El padre late lento y a propósito.
    //
    // Lo suyo es el encabezado, los repositorios y los botones: cosas que cambian cuando alguien
    // hace algo, no cuando una tarea avanza. Cada sección se refresca sola al ritmo que necesita.
    // Con un solo latido rápido acá arriba, cada tic recomponía la pantalla entera para mostrar que
    // una barra se movió un punto — y eso es lo que se siente como que todo se recarga solo.
    var latido by remember(implId) { mutableStateOf(0) }
    val impl = io.acr.ui.dbState(implId, version, latido, initial = null as io.acr.impl.Implementation?) {
        ctx.impls.get(implId)
    } ?: return
    val preguntas = io.acr.ui.dbState(implId, version, latido, initial = emptyList<io.acr.impl.ImplQuestion>()) {
        ctx.impls.questions(implId)
    }
    // Cinco segundos: sólo hace falta para que el estado y los botones se enteren de que la
    // implementación arrancó o terminó.
    androidx.compose.runtime.LaunchedEffect(impl.status) {
        while (impl.status == ImplStatus.RUNNING || impl.status == ImplStatus.PLANNING) {
            kotlinx.coroutines.delay(5_000)
            latido++
        }
    }
    val tareas = io.acr.ui.dbState(implId, version, latido, initial = emptyList<io.acr.impl.ImplTask>()) {
        ctx.impls.tasks(implId)
    }
    val suyos = io.acr.ui.dbState(implId, version, initial = emptyList<io.acr.impl.ImplRepo>()) {
        ctx.impls.reposOf(implId)
    }
    // En el orden en que se cargaron: el primero es el principal y el plan arranca mirándolo.
    val misRepos = remember(suyos, repos) { suyos.mapNotNull { r -> repos.firstOrNull { it.id == r.repoId } } }

    // El estado de git se mide cuando cambia algo, no en cada latido.
    //
    // Preguntarle a git por cada repositorio en cada recomposición son dos subprocesos por
    // repositorio por segundo, en el hilo de la interfaz, mientras la implementación corre. Es la
    // clase de trabajo que hace que una pantalla que "sólo muestra" se sienta trabada.
    //
    // `sucios` son los que tienen cambios sin commitear; `ajenos`, los que además están parados en
    // otra rama — esos son los que impiden arrancar, porque lo que hay ahí no es nuestro.
    var sucios by remember(implId) { mutableStateOf(emptySet<String>()) }
    var ajenos by remember(implId) { mutableStateOf(emptyList<io.acr.forge.RepoRecord>()) }
    val rama = impl.branch
    androidx.compose.runtime.LaunchedEffect(misRepos, version, rama, impl.useWorkspace) {
        val medidos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            misRepos.map { r ->
                val d = java.io.File(r.localPath)
                Triple(r, io.acr.claude.Git.isDirty(d), io.acr.claude.Git.currentBranch(d))
            }
        }
        sucios = medidos.filter { it.second }.map { it.first.id }.toSet()
        // Con taller, lo que el clon tenga sin commitear deja de importar: no se toca su árbol de
        // trabajo. Seguir bloqueando por eso mantendría el botón muerto por una razón que ya no
        // existe — que es justo el problema que el taller vino a resolver.
        ajenos = if (impl.useWorkspace) {
            emptyList()
        } else {
            medidos.filter { it.second && it.third != rama }.map { it.first }
        }
    }
    val repo = misRepos.firstOrNull()
    val corriendo = impl.status == ImplStatus.RUNNING || impl.status == ImplStatus.PLANNING

    // Editar reemplaza la pantalla, igual que la tarea abierta. Era un modal y se le fueron
    // sumando decisiones hasta no entrar en una pantalla de catorce pulgadas.
    if (editando) {
        ImplForm(
            ctx = ctx,
            repos = repos,
            impl = impl,
            actuales = suyos,
            onBack = { editando = false },
            onSaved = { editando = false; version++ },
        )
        return
    }

    // Los commits, en su propia pantalla: el diff necesita ancho y ahí lo tiene.
    if (viendoCommits) {
        ImplCommitsScreen(
            ctx = ctx,
            impl = impl,
            misRepos = misRepos,
            suyos = suyos,
            onBack = { viendoCommits = false },
            onHome = { viendoCommits = false; onBack() },
        )
        return
    }

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
                workDir = misRepos.firstOrNull { it.id == tar.repoId }?.let { r ->
                    ctx.workspaces.dirFor(implId, impl.useWorkspace, r.name, r.localPath)
                },
                onRetry = { ctx.impls.resetTask(tar.id); tareaAbierta = null; version++ },
                // Frenar la implementación y no sólo esta tarea: el proceso es de la
                // implementación, y matarlo sin que el motor se entere lo dejaría relanzándolo en
                // la vuelta siguiente. Lo hecho queda commiteado y la tarea vuelve a la cola.
                onPause = {
                    ctx.implEngine.cancel(implId)
                    version++
                },
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
            // Borrar, con lo que eso implica dicho antes y no después.
            //
            // Dos pasos y no un diálogo: lo que hay que leer es corto —qué se pierde— y un modal
            // para eso agrega un click sin agregar información. El aviso nombra el taller cuando
            // hay trabajo sin devolver, que es lo único irrecuperable: las tareas y el historial se
            // pueden rehacer, un commit que sólo existe en una carpeta que se va, no.
            var confirmandoBorrar by remember(implId) { mutableStateOf(false) }
            val tallerSinDevolver = io.acr.ui.dbState(implId, version, initial = false) {
                kotlinx.coroutines.runBlocking {
                    impl.useWorkspace && !ctx.workspaces
                        .inspect(implId, impl.title, misRepos, impl.branch).safeToDelete
                }
            }
            TextButton(
                enabled = !corriendo,
                onClick = {
                    if (!confirmandoBorrar) {
                        confirmandoBorrar = true
                    } else {
                        ctx.impls.delete(implId)
                        onBack()
                    }
                },
            ) {
                Text(
                    if (confirmandoBorrar) t("impl.deleteConfirm") else t("common.delete"),
                    color = if (confirmandoBorrar) StatusColors.FAILED else androidx.compose.ui.graphics.Color.Unspecified,
                )
            }
            if (confirmandoBorrar) {
                Text(
                    if (tallerSinDevolver) t("impl.deleteWarnWorkspace") else t("impl.deleteWarn"),
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.FAILED,
                )
                TextButton(onClick = { confirmandoBorrar = false }) { Text(t("common.cancel")) }
            }
            // Analizar los documentos antes de planificar sobre ellos: un plan no puede ser mejor
            // que las specs de las que sale, y lo que las specs no dicen el planificador lo
            // inventa sin marcarlo.
            io.acr.ui.InfoTip(t("impl.analyzeDocsTip"), t("impl.analyzeDocsTipOut")) {
                TextButton(
                    enabled = !corriendo && !analizando,
                    onClick = {
                        analizando = true
                        ctx.appScope.launch {
                            ctx.implEngine.improveSpecs(misRepos, implId)
                            analizando = false
                            version++
                        }
                    },
                ) { Text(t("impl.analyzeDocs")) }
            }
            if (analizando) {
                CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                // Qué está haciendo ahora mismo, acá arriba y no sólo en el feed del pie: el botón
                // está en el encabezado y el feed queda a una pantalla de scroll, así que quien
                // aprieta no ve nada y cree que no pasó nada.
                TextoActividad(ctx, implId)
            }
            EstadoBadge(impl.status)
        }

        // Qué está pasando ahora mismo, arriba de todo.
        //
        // El feed completo está al pie, a una pantalla de scroll de los botones que lanzan cosas:
        // quien aprieta "analizar" no ve nada y concluye que no pasó nada. Esto muestra las últimas
        // líneas donde se aprieta, y desaparece cuando no hay nada corriendo.
        if (analizando || corriendo) TarjetaActividad(ctx, implId)
        // De dónde parte cada repositorio y en qué rama trabaja. Estaba decidido en silencio por
        // la rama que el clon tuviera abierta, así que no había forma de saberlo sin ir a git.
        Spacer(Modifier.height(8.dp))
        misRepos.forEach { r ->
            val cfg = suyos.firstOrNull { it.repoId == r.id }
            val sucio = r.id in sucios
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.name,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(190.dp),
                )
                Text(
                    t("impl.fromBranch", cfg?.baseBranch ?: t("impl.currentBranch")) +
                        (impl.branch?.let { "  →  $it" }.orEmpty()) +
                        (if (impl.useWorkspace) "  ·  " + t("impl.inWorkspace") else ""),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // Lo sucio se resuelve acá y no en la terminal: el stash no pierde nada y se
                // recupera con `git stash pop`, así que puede ser un botón.
                // Con taller, lo que el clon tenga sin commitear es asunto del usuario y no del
                // motor: ofrecerle guardarlo en el stash sería proponerle tocar su trabajo por una
                // razón que ya no existe.
                if (sucio && !impl.useWorkspace) {
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
                    enabled = repo != null && ajenos.isEmpty(),
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
            // Y por qué no se puede, al lado del botón: el motivo estaba en el error de arriba,
            // que después de la primera vez ya nadie vuelve a leer porque no cambia.
            if (!corriendo && ajenos.isNotEmpty()) {
                Text(
                    t("impl.blockedBy", ajenos.joinToString(", ") { it.name }),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = {
                    ctx.appScope.launch {
                        ajenos.forEach { ctx.implEngine.stashDirty(it) }
                        version++
                    }
                }) { Text(t("impl.stashAll")) }
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

        // --- Todo lo que depende de las tareas, en su propia sección ---
        //
        // El latido vive adentro y no acá. Cuando estaba en el padre, cada tic recomponía la
        // pantalla entera —encabezado, repositorios, botones, plan— aunque lo único que hubiera
        // cambiado fuera el avance de una tarea. Ahora esta sección se relee sola y el resto ni se
        // entera: sus parámetros son los mismos, así que Compose lo saltea.
        SeccionTareas(
            ctx = ctx,
            implId = implId,
            impl = impl,
            misRepos = misRepos,
            version = version,
            suyos = suyos,
            onOpenTask = { tareaAbierta = it },
            onOpenCommits = { viendoCommits = true },
            onChange = { version++ },
        )

        // El feed, aparte de todo lo demás: cambia con cada línea que emite el motor —decenas por
        // segundo— y es lo único que tiene que redibujarse a ese ritmo.
        SeccionFeed(ctx, implId)
    }
}

/**
 * El avance, las revisiones, el esfuerzo, el diagrama, la tabla y los commits.
 *
 * Están juntos porque todos leen las mismas tareas, y separados del resto de la pantalla porque son
 * lo único que cambia mientras algo corre. El latido vive acá adentro: en el padre, cada tic
 * recomponía la pantalla completa para mostrar que una barra avanzó un punto.
 */
@Composable
private fun SeccionTareas(
    ctx: AppContext,
    implId: String,
    impl: io.acr.impl.Implementation,
    misRepos: List<io.acr.forge.RepoRecord>,
    version: Int,
    /** Cómo está configurado cada repositorio: de dónde parte. Para listar los commits de la rama. */
    suyos: List<io.acr.impl.ImplRepo>,
    onOpenTask: (String) -> Unit,
    onOpenCommits: () -> Unit,
    onChange: () -> Unit,
) {
    val corriendo = impl.status == ImplStatus.RUNNING || impl.status == ImplStatus.PLANNING
    val rama = impl.branch
    var tic by remember(implId) { mutableStateOf(0) }
    val tareas = io.acr.ui.dbState(implId, version, tic, initial = emptyList<io.acr.impl.ImplTask>()) {
        ctx.impls.tasks(implId)
    }
    // Cada tres segundos y no cada uno: una tarea dura minutos, así que refrescar tres veces más
    // seguido no adelanta ninguna noticia y sí hace trabajar a la pantalla todo el tiempo.
    androidx.compose.runtime.LaunchedEffect(corriendoAlgo(tareas)) {
        while (corriendoAlgo(tareas)) {
            kotlinx.coroutines.delay(3_000)
            tic++
        }
    }
    val avance = remember(tareas) { progressOf(tareas) }
    // Animado: el salto de una tarea a la siguiente se lee como movimiento y no como un parpadeo.
    val fraccion by androidx.compose.animation.core.animateFloatAsState(
        targetValue = remember(tareas, tic) { avance.fraction(tareas) },
        animationSpec = androidx.compose.animation.core.tween(600),
        label = "avance",
    )
    var tareaAbierta by remember(implId) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(tareaAbierta) {
        tareaAbierta?.let { onOpenTask(it); tareaAbierta = null }
    }

    Column(Modifier.fillMaxWidth()) {
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
                    "  ·  " + t("impl.estimated", io.acr.impl.minutosLegibles(avance.estimatedMin)) +
                    "  ·  " + t("impl.elapsed", io.acr.impl.minutosLegibles(avance.elapsedMin)) + tiempo(avance),
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
                        onDone = onChange,
                    )
                }
            }
        }

        // --- Lo que encontró la revisión ---
        // Va antes del esfuerzo porque es lo que cambia una decisión: una implementación completa
        // con cuatro hallazgos sin arreglar no está en el mismo estado que una limpia, y el costo
        // total no dice nada de eso.
        val paralelas = remember(tareas) { paralelasDe(tareas) }
        val revisiones = io.acr.ui.dbState(implId, version, tic, initial = emptyList<io.acr.impl.ReviewPass>()) {
            ctx.impls.reviews(implId)
        }
        if (revisiones.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            io.acr.ui.CollapsibleCard(
                t("impl.reviews"), ctx.prefs, "implrev-$implId", maxHeight = 300.dp,
            ) {
                Column {
                    revisiones.forEach { rev ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(
                                if (rev.findings == 0) "✓" else "!",
                                color = if (rev.findings == 0) io.acr.ui.stats.ChartColors.added
                                else io.acr.ui.stats.ChartColors.major,
                                modifier = Modifier.width(20.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t("impl.reviewKind" + rev.kind.name) + "  ·  " +
                                        t("impl.reviewPass", rev.pass) +
                                        rev.taskId?.let { id ->
                                            tareas.firstOrNull { it.id == id }?.let { "  ·  #${it.seq}" }
                                        }.orEmpty() +
                                        "  ·  " + (
                                            if (rev.findings == 0) t("impl.reviewClean")
                                            else t("impl.reviewFound", rev.findings, rev.fixed)
                                            ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                rev.summary?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                                // El detalle son las líneas de cada hallazgo. Se muestra entero:
                                // resumir un hallazgo lo convierte en un titular que nadie puede
                                // verificar.
                                rev.detail?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
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
                Metrica(t("impl.mTime"), io.acr.impl.minutosLegibles(esfuerzo.minutes))
                Metrica(t("impl.mCost"), "$" + "%.2f".format(esfuerzo.costUsd))
                // Creado y modificado separados: cuatro archivos nuevos son superficie nueva para
                // mirar entera, y dos modificados son un diff que leer. No son lo mismo.
                Metrica(t("impl.mNew"), esfuerzo.filesAdded.toString())
                Metrica(t("impl.mChanged"), esfuerzo.filesModified.toString())
                if (esfuerzo.filesDeleted > 0) Metrica(t("impl.mDeleted"), esfuerzo.filesDeleted.toString())
                Metrica(t("impl.mLines"), "+${esfuerzo.linesAdded} / −${esfuerzo.linesDeleted}")
            }
        }

        // --- Lo que el plan necesita y nadie declaró ---
        //
        // Va arriba de la consola y de las tareas porque es lo que hay que resolver antes de dejar
        // correr nada: una implementación a la que le falta un repositorio produce tareas que no se
        // pueden hacer, o —peor, antes de que esto existiera— código escrito en un proyecto ajeno.
        //
        // Con el botón para agregarlo de un click cuando el planificador encontró dónde está.
        // Decirle a alguien "falta mail-ms" y dejar que lo busque es la mitad del trabajo.
        if (impl.missingRepos.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(StatusColors.NEEDS_HUMAN.copy(alpha = 0.10f))
                    .padding(12.dp),
            ) {
                Text(
                    t("impl.missingRepos", impl.missingRepos.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = StatusColors.NEEDS_HUMAN,
                )
                Text(
                    t("impl.missingReposNote"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                impl.missingRepos.forEach { m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                m.name + m.path?.let { "  ·  $it" }.orEmpty(),
                                style = MaterialTheme.typography.bodySmall
                                    .copy(fontFamily = FontFamily.Monospace),
                            )
                            // La evidencia, no sólo la conclusión: es lo que permite decidir en un
                            // vistazo si agregarlo o si el modelo buscó mal.
                            Text(
                                m.evidence + m.neededFor?.let { "  —  $it" }.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            // Con la ruta que encontró, o abriendo el selector si no la sabe.
                            val ruta = m.path?.let { p ->
                                java.io.File(p).takeIf { it.isDirectory }?.absolutePath
                                    ?: java.io.File(misRepos.firstOrNull()?.localPath.orEmpty(), p)
                                        .takeIf { it.isDirectory }?.absolutePath
                            } ?: elegirCarpeta()
                            ruta?.let { r ->
                                kotlinx.coroutines.runBlocking { io.acr.claude.Git.init(java.io.File(r)) }
                                val id = ctx.repos.createLocal(r)
                                ctx.impls.update(
                                    implId, impl.title, impl.sources, impl.extraPrompt,
                                    suyos + io.acr.impl.ImplRepo(id, io.acr.impl.RepoRole.OTHER, null),
                                )
                                onChange()
                            }
                        }) { Text(t("impl.declareRepo")) }
                    }
                }
            }
        }

        // El taller de esta implementación: dónde está, cuánto ocupa y si el trabajo ya está
        // devuelto. Vivía sólo en la pantalla de administración, y la pregunta "¿esto ya está en mi
        // clon?" se hace parado acá, mirando lo que se hizo — no yendo a buscar otra pantalla.
        if (impl.useWorkspace) {
            Spacer(Modifier.height(12.dp))
            TallerDeLaImpl(ctx, impl, misRepos, onChange)
        }

        // La consola, arriba de las tareas: lo que se escribe acá se convierte en una y aparece
        // ahí abajo, así que ponerla lejos rompería la relación entre lo que uno pide y dónde
        // aparece.
        if (tareas.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            ImplConsole(ctx, implId, misRepos, tareas, onChange)
        }

        // --- Las tareas: primero la tira, después la tabla ---
        //
        // La tira sale de las mismas tareas que la tabla de abajo, no de otro lado: es el plan
        // visto en el tiempo en vez de en orden. Se ve siempre porque contesta de un vistazo lo
        // único que una tabla no puede contestar —cuánto va hecho y si algo se está yendo de
        // tiempo— y se despliega en el Gantt completo, con las duraciones, cuando eso no alcanza.
        if (tareas.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            var ganttAbierto by remember(implId) { mutableStateOf(false) }
            val transcurrido = avance.elapsedMin.takeIf { it > 0 && avance.done < avance.total }
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { ganttAbierto = !ganttAbierto }
                    .padding(vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (ganttAbierto) "▾  " else "▸  ") + t("impl.gantt"),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (ganttAbierto) t("impl.ganttHide") else t("impl.ganttShow"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                TiraDeAvance(tareas)
                if (ganttAbierto) {
                    Spacer(Modifier.height(14.dp))
                    GanttView(tareas, misRepos, elapsedMin = transcurrido) { tareaAbierta = it.id }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Cab("#", 28.dp)
            Cab("⇉", 20.dp)
            Cab(t("impl.thStatus"), 120.dp)
            Cab(t("impl.thDeps"), 90.dp)
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
                    modifier = Modifier.width(28.dp),
                )
                // Si va a correr acompañada. Sale del mismo calendario que dibuja el Gantt, no de
                // una marca aparte: si fueran dos fuentes distintas podrían decir cosas distintas.
                Text(
                    if (tar.seq in paralelas) "⇉" else "→",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tar.seq in paralelas) StatusColors.RUNNING
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.width(20.dp),
                )
                Row(Modifier.width(120.dp), verticalAlignment = Alignment.CenterVertically) {
                    TaskStatusBadge(tar.status)
                }
                // De quién depende, en su propia columna. Estaba sólo debajo del título y sólo
                // mientras la tarea seguía pendiente, así que una vez hecha no quedaba forma de
                // reconstruir el orden que el plan había decidido.
                Celda(
                    tar.dependsOn.filter { d -> tareas.any { it.seq == d } }
                        .takeIf { it.isNotEmpty() }?.joinToString(", ") { "#$it" } ?: "—",
                    90.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(tar.title, style = MaterialTheme.typography.bodySmall)
                    // Qué la está frenando. Sin esto, una tarea pendiente en medio de otras que
                    // avanzan parece salteada, y no hay forma de saber que está esperando su turno
                    // ni a quién.
                    if (tar.status == io.acr.impl.TaskStatus.PENDING) {
                        val faltan = tar.dependsOn.filter { d ->
                            tareas.firstOrNull { it.seq == d }
                                ?.let { it.status != io.acr.impl.TaskStatus.DONE } == true
                        }
                        if (faltan.isNotEmpty()) {
                            Text(
                                t("impl.waitingFor", faltan.joinToString(", ") { "#$it" }),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    tar.runningMin()?.let { va ->
                        val est = tar.estimateMin ?: tar.size?.minutes
                        Text(
                            t("impl.runningFor", io.acr.impl.minutosLegibles(va)) +
                                (est?.let { " / " + io.acr.impl.minutosLegibles(it) }.orEmpty()) +
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
                    tar.actualMin?.let { io.acr.impl.minutosLegibles(it) }?.plus(
                        (tar.estimateMin ?: tar.size?.minutes)
                            ?.let { " / " + io.acr.impl.minutosLegibles(it) }.orEmpty(),
                    ) ?: (tar.estimateMin ?: tar.size?.minutes)
                        ?.let { "~" + io.acr.impl.minutosLegibles(it) } ?: "—",
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
            // Los commits viven en su propia pantalla: acá sólo el acceso y el número.
            //
            // Estaban en un desplegable, y ahí el diff no entraba —cuatrocientos píxeles de alto
            // compartidos con el resto de la implementación, para leer código que necesita ancho—.
            // Uno terminaba abriendo el repositorio en otra herramienta, que es justo lo que la app
            // venía a evitar.
            val cuantos = io.acr.ui.dbState(implId, version, initial = 0) {
                kotlinx.coroutines.runBlocking {
                    val r2 = impl.branch ?: return@runBlocking 0
                    misRepos.sumOf { r ->
                        val base = suyos.firstOrNull { it.repoId == r.id }?.baseBranch
                            ?: impl.baseBranch ?: "develop"
                        io.acr.claude.Git.commitsBetween(
                            ctx.workspaces.dirFor(implId, impl.useWorkspace, r.name, r.localPath),
                            base, r2,
                        ).size
                    }
                }
            }
            if (cuantos > 0) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onOpenCommits() }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(t("impl.commits", cuantos), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        t("impl.commitsGo"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * El feed de actividad.
 *
 * Aparte de todo lo demás porque cambia con cada línea que emite el motor. Leyéndolo desde el padre,
 * esa frecuencia se contagiaba a la pantalla entera.
 */
@Composable
private fun SeccionFeed(ctx: AppContext, implId: String) {
    val progreso by ctx.implEngine.progress.collectAsState()
    val vivo = progreso[implId]
    Column(Modifier.fillMaxWidth()) {
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

/**
 * El plan en una sola línea: cada tarea, un tramo, coloreado por su estado.
 *
 * Es lo que se ve sin desplegar nada. Una barra de progreso sola dice "8 de 24" y nada más; esta
 * dice además dónde están las que fallaron y las que esperan una decisión, que es lo que decide si
 * hay que abrir el diagrama o no. El ancho de cada tramo es su duración, así que una tarea larga
 * pesa lo que pesa y no lo mismo que una de cinco minutos.
 */
@Composable
private fun TiraDeAvance(tareas: List<io.acr.impl.ImplTask>) {
    val duraciones = remember(tareas) {
        tareas.map { t ->
            t to ((t.actualMin ?: t.runningMin() ?: t.estimateMin?.toDouble()
                ?: t.size?.minutes?.toDouble() ?: 15.0).coerceAtLeast(1.0))
        }
    }
    val total = duraciones.sumOf { it.second }.coerceAtLeast(1.0)
    Row(
        Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(3.dp)),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        duraciones.forEach { (t, dur) ->
            androidx.compose.foundation.layout.Box(
                Modifier
                    .weight((dur / total).toFloat())
                    .height(10.dp)
                    // Lo pendiente en tenue y no en gris pleno: si todo pesa lo mismo, lo hecho
                    // deja de destacarse, que es lo único que se busca acá.
                    .background(
                        StatusColors.of(t.status)
                            .copy(alpha = if (t.status == io.acr.impl.TaskStatus.PENDING) 0.22f else 1f),
                    ),
            )
        }
    }
}

/**
 * Qué está pasando ahora mismo, arriba de todo.
 *
 * Su propio hijo porque lee el feed, y el feed cambia con cada línea que emite el motor. Leído desde
 * el padre, esa frecuencia se contagiaba a toda la pantalla: cada línea de log recomponía el
 * encabezado, los repositorios, los botones y el plan.
 */
@Composable
private fun TarjetaActividad(ctx: AppContext, implId: String) {
    val progreso by ctx.implEngine.progress.collectAsState()
    val vivo = progreso[implId]
            vivo?.lines?.takeIf { it.isNotEmpty() }?.let { lineas ->
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    Modifier.height(14.dp).width(14.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    lineas.last().trim(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            // Las anteriores, atenuadas: lo último dice qué pasa ahora, las de atrás dicen
            // que viene avanzando y no que se colgó en el primer paso.
            lineas.dropLast(1).takeLast(5).reversed().forEach { l ->
                Text(
                    l.trim(),
                    style = MaterialTheme.typography.labelSmall
                        .copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** La última línea del feed, para el encabezado. Aparte por la misma razón que la tarjeta. */
@Composable
private fun TextoActividad(ctx: AppContext, implId: String) {
    val progreso by ctx.implEngine.progress.collectAsState()
    Text(
        progreso[implId]?.lines?.lastOrNull()?.trim()?.take(90) ?: t("impl.analyzing"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * El taller de una implementación, resumido.
 *
 * Sólo lo que se decide desde acá: si el trabajo ya está en los clones y el botón para devolverlo.
 * El detalle por repositorio y el borrado viven en administración — acá estorbarían, y borrar no es
 * algo que uno haga mirando el avance.
 */
@Composable
private fun TallerDeLaImpl(
    ctx: AppContext,
    impl: io.acr.impl.Implementation,
    misRepos: List<io.acr.forge.RepoRecord>,
    onChange: () -> Unit,
) {
    var trabajando by remember(impl.id) { mutableStateOf(false) }
    var version2 by remember(impl.id) { mutableStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val w = io.acr.ui.dbState(impl.id, version2, initial = null as io.acr.impl.Workspace?) {
        kotlinx.coroutines.runBlocking {
            ctx.workspaces.inspect(impl.id, impl.title, misRepos, impl.branch)
        }
    }
    if (w == null || !w.exists) return

    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                t("impl.workshop") + "  ·  " +
                    (if (w.safeToDelete) t("ws.safe") else t("ws.unsynced")),
                style = MaterialTheme.typography.labelSmall,
                color = if (w.safeToDelete) StatusColors.DONE else StatusColors.NEEDS_HUMAN,
            )
            Text(
                w.path,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (trabajando) {
            CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        // Devolver a mano, sin esperar a que termine: es lo que permite mirar el trabajo con las
        // herramientas de siempre mientras la implementación sigue.
        impl.branch?.let { rama ->
            io.acr.ui.InfoTip(t("ws.syncTip"), t("ws.syncTipOut")) {
                TextButton(
                    enabled = !trabajando && !w.safeToDelete,
                    onClick = {
                        trabajando = true
                        scope.launch {
                            ctx.workspaces.syncBack(impl.id, misRepos, rama)
                            trabajando = false
                            version2++
                            onChange()
                        }
                    },
                ) { Text(t("ws.sync")) }
            }
        }
        TextButton(onClick = {
            runCatching { java.awt.Desktop.getDesktop().open(java.io.File(w.path)) }
        }) { Text(t("impl.openRepo")) }
    }
}
