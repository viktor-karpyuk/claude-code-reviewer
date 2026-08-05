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

    suspend fun load() {
        loading = true
        error = null
        runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Forges.of(repo.provider).listPullRequests(repo)
        } }
            .onSuccess { list ->
                prs = list
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    lastReviews = ctx.reviews.historyFor(repo.id).groupBy { it.prId }
                        .mapValues { (_, v) -> v.first() }
                    openReplies = ctx.replies.openCountsByPr(repo.id)
                    answered = ctx.replies.answeredPrs(repo.id)
                }
            }
            .onFailure { error = it.message ?: "No pude traer los pull requests." }
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
            OutlinedButton(onClick = { scope.launch { load() } }, enabled = !loading) {
                Text(io.acr.i18n.t("common.refresh"))
            }
        }

        Spacer(Modifier.height(16.dp))
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
                items(prs, key = { it.id }) { pr ->
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
                            last?.status == ReviewStatus.DONE && last.publishedUrl == null ->
                                io.acr.i18n.t("prs.readyToPublish")
                            last?.status == ReviewStatus.DONE && last.headSha != pr.headSha ->
                                io.acr.i18n.t("prs.needsRerun")
                            last == null -> null
                            last.publishedUrl != null && pr.id in answered ->
                                io.acr.i18n.t("prs.publishedAnswered")
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
        Text(
            "#${pr.id}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(pr.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${pr.author} · ${pr.sourceBranch} → ${pr.targetBranch} · ${pr.updatedOn}",
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
