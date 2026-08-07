package io.acr.ui.prs

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.acr.AppContext
import io.acr.data.ReviewStatus
import io.acr.forge.Forges
import io.acr.forge.PullRequest
import io.acr.forge.RepoRecord
import io.acr.ui.clickableText
import kotlinx.coroutines.launch

/** "1m 20s" */
private fun elapsed(startedAt: Long, @Suppress("UNUSED_PARAMETER") tick: Int = 0): String {
    val s = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
    return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
}

@Composable
fun PrsPanel(
    ctx: AppContext,
    repo: RepoRecord,
    snackbar: SnackbarHostState,
    onOpenReview: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var prs by remember(repo.id) { mutableStateOf<List<PullRequest>>(emptyList()) }
    var loading by remember(repo.id) { mutableStateOf(true) }
    var error by remember(repo.id) { mutableStateOf<String?>(null) }
    // Una sola consulta para todo el listado. Antes se hacía una por fila, dentro de items{},
    // en el hilo de UI: se repetía en cada scroll que reingresaba la fila al viewport.
    var lastReviews by remember(repo.id) { mutableStateOf<Map<Long, io.acr.data.ReviewRecord>>(emptyMap()) }
    var openReplies by remember(repo.id) { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var answered by remember(repo.id) { mutableStateOf<Set<Long>>(emptySet()) }
    // Por PR: hallazgos totales y ya publicados, para distinguir "sin publicar" de "a medias".
    var findingProgress by remember(repo.id) { mutableStateOf<Map<Long, Pair<Int, Int>>>(emptyMap()) }
    // El orden se guarda en preferencias: es una decisión de trabajo, no algo que uno quiera
    // volver a elegir cada vez que abre la app.
    var sort by remember { mutableStateOf(PrSort.fromKey(ctx.prefs.get(AppContext.PREF_PR_SORT))) }
    var sortOpen by remember { mutableStateOf(false) }
    // Qué estados se muestran. Sólo abiertos por defecto y a propósito: los históricos son
    // cientos —148 contra 4 en kubrik-erp-be— y traerlos siempre sería pagar todo eso para ver
    // los cuatro que importan. Se piden con el botón, y no tocan el caché de abiertos.
    var estados by remember(repo.id) { mutableStateOf(setOf(io.acr.forge.PrState.OPEN)) }
    var historicos by remember(repo.id) { mutableStateOf<List<PullRequest>>(emptyList()) }
    var buscandoHistorico by remember(repo.id) { mutableStateOf(false) }
    var errorHistorico by remember(repo.id) { mutableStateOf<String?>(null) }
    val allProgress by ctx.engine.progress.collectAsState()
    // Filtrado por repo: el mapa se indexa por número de PR, y el #18 de un repo no tiene nada
    // que ver con el #18 de otro. Sin esto, un PR ajeno se marcaba como "revisando" acá.
    val progress = allProgress.filterValues { it.repoId == repo.id }

    // El tiempo transcurrido sólo se refrescaría cuando llega una línea de progreso; con un tic
    // avanza parejo mientras algo corre, y no gasta nada cuando no hay nada corriendo.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(progress.isNotEmpty()) {
        while (progress.isNotEmpty()) {
            kotlinx.coroutines.delay(1_000)
            tick++
        }
    }

    suspend fun load(force: Boolean = false) {
        // Primero lo que ya sabemos: la lista aparece sin esperar a la red.
        val cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ctx.prLoader.cached(repo.id)
        }
        if (cached.isNotEmpty() && prs.isEmpty()) prs = cached

        loading = true
        error = null
        runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ctx.prLoader.refresh(repo, force)
        } }
            .onSuccess { list ->
                prs = list
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    lastReviews = ctx.reviews.historyFor(repo.id).groupBy { it.prId }
                        .mapValues { (_, v) -> v.first() }
                    openReplies = ctx.replies.openCountsByPr(repo.id)
                    answered = ctx.replies.answeredPrs(repo.id)
                    findingProgress = ctx.reviews.findingProgressByPr(repo.id)
                }
            }
            .onFailure {
                // Con caché en pantalla, un fallo de red no debe vaciar la lista: se avisa y se
                // deja lo último conocido, que sigue siendo útil.
                if (prs.isEmpty()) error = it.message ?: "No pude traer los pull requests."
                else error = null
            }
        loading = false
    }

    // Requirement 2: the PRs of a connected repo load on their own when you pick it.
    LaunchedEffect(repo.id) { load() }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(repo.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${repo.provider.label} · ${repo.owner}/${repo.slug}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (progress.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                    CircularProgressIndicator(Modifier.height(16.dp))
                    Text(
                        "  ${progress.size} " + io.acr.i18n.t("prs.reviewingCount"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Box {
                OutlinedButton(onClick = { sortOpen = true }) {
                    Text(io.acr.i18n.t("prs.sortLabel") + ": " + io.acr.i18n.t(sort.labelKey))
                }
                androidx.compose.material3.DropdownMenu(sortOpen, { sortOpen = false }) {
                    PrSort.entries.forEach { s ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(io.acr.i18n.t(s.labelKey)) },
                            onClick = {
                                sort = s
                                ctx.prefs.put(AppContext.PREF_PR_SORT, s.key)
                                sortOpen = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { scope.launch { load(force = true) } }, enabled = !loading) {
                Text(io.acr.i18n.t("common.refresh"))
            }
        }

        Spacer(Modifier.height(10.dp))

        // Filtro de estado. Cambiarlo NO dispara nada solo: el histórico se trae al apretar
        // "Buscar", que es la acción explícita.
        Row(verticalAlignment = Alignment.CenterVertically) {
            io.acr.forge.PrState.entries.forEach { e ->
                androidx.compose.material3.FilterChip(
                    selected = e in estados,
                    onClick = {
                        estados = if (e in estados) (estados - e).ifEmpty { setOf(io.acr.forge.PrState.OPEN) }
                        else estados + e
                    },
                    label = { Text(io.acr.i18n.t(e.labelKey)) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            val soloAbiertos = estados == setOf(io.acr.forge.PrState.OPEN)
            if (!soloAbiertos) {
                OutlinedButton(
                    enabled = !buscandoHistorico,
                    onClick = {
                        buscandoHistorico = true
                        errorHistorico = null
                        scope.launch {
                            runCatching {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    Forges.of(repo.provider).searchPullRequests(
                                        repo, estados - io.acr.forge.PrState.OPEN,
                                    )
                                }
                            }
                                .onSuccess { historicos = it }
                                .onFailure { errorHistorico = it.message ?: "No pude buscar." }
                            buscandoHistorico = false
                        }
                    },
                ) {
                    Text(
                        if (buscandoHistorico) io.acr.i18n.t("prs.searching")
                        else io.acr.i18n.t("prs.searchHistory"),
                    )
                }
                if (historicos.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        io.acr.i18n.t("prs.historyLoaded", historicos.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        errorHistorico?.let {
            Spacer(Modifier.height(6.dp))
            io.acr.ui.components.ErrorBox(io.acr.i18n.t("prs.searchError"), it)
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider()

        // Barra indeterminada pegada al divisor: ocupa el ancho, no desplaza el contenido y deja
        // ver de inmediato la lista anterior en un refresco.
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            Spacer(Modifier.height(4.dp))
        }

        when {
            loading && prs.isEmpty() -> Text(
                io.acr.i18n.t("prs.loading") + " ${repo.owner}/${repo.slug}…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            error != null -> Column(Modifier.padding(top = 24.dp)) {
                io.acr.ui.components.ErrorBox(
                    title = io.acr.i18n.t("prs.loadError"),
                    message = error!!,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { scope.launch { load() } }) { Text(io.acr.i18n.t("common.retry")) }
            }
            prs.isEmpty() -> Text(
                io.acr.i18n.t("prs.none"),
                modifier = Modifier.padding(top = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                // Los abiertos salen del caché; los históricos, sólo si se buscaron. Se combinan
                // por id para que un PR que se mergeó mientras tanto no aparezca dos veces.
                val visibles = (
                    (if (io.acr.forge.PrState.OPEN in estados) prs else emptyList()) +
                        historicos.filter { it.state in estados }
                    ).distinctBy { it.id }
                items(visibles.sortedBy(sort), key = { it.id }) { pr ->
                    val last = lastReviews[pr.id]
                    PrRow(
                        pr = pr,
                        tick = tick,
                        running = progress[pr.id],
                        // Prioridad por lo accionable: primero lo que espera algo de vos.
                        badge = when {
                            progress.containsKey(pr.id) -> null
                            (openReplies[pr.id] ?: 0) > 0 ->
                                io.acr.i18n.t("prs.repliedCount", openReplies[pr.id] ?: 0)
                            last?.status == ReviewStatus.DONE && last.publishedUrl == null -> {
                                val (total, done) = findingProgress[pr.id] ?: (0 to 0)
                                if (done in 1 until total) io.acr.i18n.t("prs.partlyPublished", done, total)
                                else io.acr.i18n.t("prs.readyToPublish")
                            }
                            last?.status == ReviewStatus.DONE && last.headSha != pr.headSha ->
                                io.acr.i18n.t("prs.needsRerun")
                            last == null -> null
                            last.publishedUrl != null && pr.id in answered ->
                                io.acr.i18n.t("prs.publishedAnswered")
                            // Haber contestado también es un estado: sin esto, al publicar la
                            // última respuesta el PR volvía a mostrarse como si nada hubiera pasado.
                            pr.id in answered -> io.acr.i18n.t("prs.answered")
                            last.publishedUrl != null -> io.acr.i18n.t("common.published")
                            last.status == ReviewStatus.DONE -> io.acr.i18n.t("prs.reviewed")
                            last.status == ReviewStatus.FAILED -> io.acr.i18n.t("prs.failed")
                            else -> null
                        },
                        onOpen = { onOpenReview(pr.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PrRow(
    pr: PullRequest,
    badge: String?,
    running: io.acr.claude.RunProgress?,
    tick: Int,
    onOpen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickableText(onOpen).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(84.dp)) {
            Text(
                "#${pr.id}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // El estado se muestra siempre, no sólo al buscar histórico: un PR abierto que se
            // mergeó mientras estaba en pantalla ya no hay que revisarlo.
            Text(
                io.acr.i18n.t(pr.state.labelKey),
                style = MaterialTheme.typography.labelSmall,
                color = when (pr.state) {
                    io.acr.forge.PrState.OPEN -> MaterialTheme.colorScheme.primary
                    io.acr.forge.PrState.MERGED -> androidx.compose.ui.graphics.Color(0xFF7A5CD0)
                    io.acr.forge.PrState.DECLINED -> MaterialTheme.colorScheme.error
                },
            )
        }
        Column(Modifier.weight(1f)) {
            Text(pr.title, style = MaterialTheme.typography.bodyLarge)
            // Se muestra cuándo se abrió, no sólo la última actividad: es el dato por el que se
            // ordena por defecto, y sin verlo el orden parece arbitrario.
            val fechas = buildString {
                pr.createdOn.takeIf { it.isNotBlank() }?.let {
                    append(io.acr.i18n.t("prs.opened")).append(' ').append(it)
                }
                if (pr.updatedOn.isNotBlank() && pr.updatedOn != pr.createdOn) {
                    if (isNotEmpty()) append(" · ")
                    append("↻ ").append(pr.updatedOn)
                }
            }
            Text(
                "${pr.author} · ${pr.sourceBranch} → ${pr.targetBranch}" +
                    if (fechas.isBlank()) "" else " · $fechas",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        running?.let { p ->
            // Estado explícito, no una palabra al pasar: qué está haciendo y desde cuándo.
            Column(Modifier.width(260.dp).padding(end = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (p.auto) io.acr.i18n.t("prs.reviewingAuto") else io.acr.i18n.t("prs.reviewing"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        // `tick` se lee acá sólo para que Compose recomponga cada segundo.
                        elapsed(p.startedAt, tick),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 2.dp))
                Text(
                    p.lines.lastOrNull().orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        badge?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        Button(onClick = onOpen) { Text(if (running != null) io.acr.i18n.t("prs.seeProgress") else io.acr.i18n.t("common.open")) }
    }
}
