package io.acr.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.acr.AppContext
import io.acr.claude.RunProgress
import io.acr.data.ReviewRecord
import io.acr.data.ReviewStatus
import io.acr.ui.clickableText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CONSUMO_BG = Color(0x2227C08A)
private val CONSUMO_FG = Color(0xFF2E9E63)

/**
 * Vista global de lo que está pasando.
 *
 * El feed de progreso de un PR sólo sirve si estás mirando ese PR; con el modo automático
 * revisando varios repos de fondo hace falta un lugar donde ver todo junto y, sobre todo, qué
 * quedó listo esperando que alguien lo publique.
 */
@Composable
fun DashboardPanel(ctx: AppContext, onOpenPr: (repoId: String, prId: Long) -> Unit) {
    val scope = rememberCoroutineScope()
    val progressMap by ctx.engine.progress.collectAsState()
    val autoStatus by ctx.auto.status.collectAsState()

    // Tic de 1s: sólo para que el tiempo transcurrido avance; los datos vienen de los flows.
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }

    // Se relee cuando termina algo (cambia la cantidad en curso) o cada 10 tics.
    val running = progressMap.values.sortedBy { it.startedAt }
    var snapshot by remember { mutableStateOf(Snapshot()) }
    // Al tocar una tarjeta se muestra sólo esa sección: el número de arriba y la lista de abajo
    // eran la misma cosa, pero la lista quedaba enterrada bajo los gráficos.
    var focus by remember { mutableStateOf<String?>(null) }
    // Los pendientes salen de consultar a los proveedores, así que NO se refrescan con el tic:
    // sería una ráfaga de llamadas cada 10s contra un token que ya limita por frecuencia.
    var pending by remember { mutableStateOf<List<PendingPr>?>(null) }
    var loadingPending by remember { mutableStateOf(false) }
    var pendingError by remember { mutableStateOf<String?>(null) }

    suspend fun loadPending() {
        loadingPending = true
        pendingError = null
        runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ctx.repos.list().flatMap { repo ->
                    io.acr.forge.Forges.of(repo.provider).listPullRequests(repo)
                        .filter { !ctx.reviews.existsForHead(repo.id, it.id, it.headSha) }
                        .map { PendingPr(repo.id, repo.name, it.id, it.title, it.author, repo.autoReview) }
                }
            }
        }.onSuccess { pending = it }
            .onFailure { pendingError = it.message ?: "No pude traer los PRs." }
        loadingPending = false
    }
    LaunchedEffect(running.size, tick / 10) {
        snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Snapshot(
                ready = ctx.reviews.readyToPublish(),
                recent = ctx.reviews.recent(),
                usage = ctx.reviews.usage(),
                repoNames = ctx.repos.list().associate { it.id to it.name },
                replies = ctx.replies.openOnes(),
                totals = ctx.reviews.totals(),
                weekly = ctx.reviews.statsByPeriod("week", 8),
                monthly = ctx.reviews.statsByPeriod("month", 6),
            )
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(io.acr.i18n.t("dash.title"), style = MaterialTheme.typography.headlineSmall)
                Text(
                    io.acr.i18n.t("dash.subtitle"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                enabled = !autoStatus.running,
                onClick = { scope.launch { ctx.auto.runOnce() } },
            ) { Text(if (autoStatus.running) io.acr.i18n.t("dash.searching") else io.acr.i18n.t("dash.findNew")) }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat(
                io.acr.i18n.t("dash.inProgress"), running.size.toString(),
                "${running.count { it.auto }} automáticas",
                selected = focus == "running",
                onClick = { focus = if (focus == "running") null else "running" },
            )
            Stat(
                io.acr.i18n.t("dash.readyToPublish"), snapshot.ready.size.toString(),
                io.acr.i18n.t("dash.awaitingYou"),
                selected = focus == "ready",
                onClick = { focus = if (focus == "ready") null else "ready" },
            )
            Stat(
                io.acr.i18n.t("dash.repliedToYou"), snapshot.replies.size.toString(),
                io.acr.i18n.t("dash.threadsWaiting"),
                selected = focus == "replies",
                onClick = { focus = if (focus == "replies") null else "replies" },
            )
            Stat(io.acr.i18n.t("dash.prsReviewed"), snapshot.totals.prsReviewed.toString(), "${snapshot.totals.reviews} corridas")
            Stat(io.acr.i18n.t("dash.publishedCount"), snapshot.totals.published.toString(), "de ${snapshot.totals.reviews} terminadas")
        }
        Spacer(Modifier.height(10.dp))
        // El costo va aparte y en verde: no es una métrica de trabajo como las de arriba, y con
        // suscripción ni siquiera es un cargo. Mezclarlo con el resto lo hacía leer como factura.
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(CONSUMO_BG)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "Consumo · ~US$ ${"%.2f".format(snapshot.usage.costUsd)} equivalente API",
                    style = MaterialTheme.typography.titleSmall,
                    color = CONSUMO_FG,
                )
                Text(
                    "${formatTokens(snapshot.usage.tokens)} tokens. Con suscripción no se factura " +
                        "por review: el consumo real son los tokens, no los dólares.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Origen: ${snapshot.totals.auto} automáticas · ${snapshot.totals.manual} manuales" +
                if (snapshot.totals.failed > 0) " · ${snapshot.totals.failed} fallidas" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        Text(
            autoStatus.lastRunAt
                ?.let { "Automático · último barrido ${it.take(16).replace('T', ' ')} · ${autoStatus.lastMessage}" }
                ?: "Automático · todavía no corrió.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()

        focus?.let {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                Text(
                    io.acr.i18n.t("dash.filtered"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = { focus = null }) { Text(io.acr.i18n.t("dash.showAll")) }
            }
        }

        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (focus == null || focus == "ready") {
            item { Section("Listas para publicar (${snapshot.ready.size})") }
            if (snapshot.ready.isEmpty()) {
                item { Empty(io.acr.i18n.t("dash.noneReady")) }
            }
            items(snapshot.ready, key = { "r-${it.id}" }) { r ->
                ReviewRow(r, snapshot.repoNames[r.repoId] ?: r.repoId) { onOpenPr(r.repoId, r.prId) }
            }
            }

            if (focus == null || focus == "replies") {
            item { Section("Te respondieron (${snapshot.replies.size})") }
            if (snapshot.replies.isEmpty()) {
                item { Empty(io.acr.i18n.t("dash.noReplies")) }
            }
            items(snapshot.replies, key = { "rp-${it.id}" }) { d ->
                Row(
                    Modifier.fillMaxWidth().clickableText { onOpenPr(d.repoId, d.prId) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${snapshot.repoNames[d.repoId] ?: d.repoId} · #${d.prId}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(220.dp),
                        maxLines = 1,
                    )
                    Text(
                        "${d.theirAuthor}: ${d.theirBody.replace('\n', ' ').take(90)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(
                        if (d.body.isNullOrBlank()) io.acr.i18n.t("dash.notDrafted") else io.acr.i18n.t("dash.drafted"),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (d.body.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(110.dp),
                    )
                    TextButton(onClick = { onOpenPr(d.repoId, d.prId) }) { Text(io.acr.i18n.t("common.open")) }
                }
            }
            }

            if (focus == null || focus == "running") {
            item { Section("Revisándose ahora (${running.size})") }
            if (running.isEmpty()) {
                item { Empty(io.acr.i18n.t("dash.nothingRunning")) }
            }
            items(running, key = { it.prId }) { p ->
                RunningCard(
                    p = p,
                    elapsed = elapsed(p.startedAt),
                    onOpen = { onOpenPr(p.repoId, p.prId) },
                    onCancel = { ctx.engine.cancel(p.prId) },
                )
            }
            }

            if (focus == null) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Section(
                        pending?.let { "Pendientes de revisar (${it.size})" } ?: "Pendientes de revisar",
                    )
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(enabled = !loadingPending, onClick = { scope.launch { loadPending() } }) {
                        Text(if (loadingPending) "Consultando…" else if (pending == null) "Consultar" else "Actualizar")
                    }
                }
            }
            pendingError?.let { err -> item { Empty("No pude traer los pendientes: $err") } }
            if (pending == null && pendingError == null) {
                item { Empty(io.acr.i18n.t("dash.pendingHint")) }
            }
            if (pending?.isEmpty() == true) {
                item { Empty(io.acr.i18n.t("dash.pendingNone")) }
            }
            items(pending.orEmpty(), key = { "p-${it.repoId}-${it.prId}" }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickableText { onOpenPr(p.repoId, p.prId) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        (if (p.auto) "auto" else "man") + " · ${p.repoName} · #${p.prId}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(220.dp),
                        maxLines = 1,
                    )
                    Text(p.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(
                        p.author,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(150.dp),
                        maxLines = 1,
                    )
                }
            }
            }

            if (focus == null) {
            item { Section(io.acr.i18n.t("dash.recent")) }
            items(snapshot.recent, key = { "h-${it.id}" }) { r ->
                ReviewRow(r, snapshot.repoNames[r.repoId] ?: r.repoId) { onOpenPr(r.repoId, r.prId) }
            }
            }
            if (focus == null) {
            item { Section(io.acr.i18n.t("dash.byWeek")) }
            item { PeriodChart(snapshot.weekly) }
            item { Section(io.acr.i18n.t("dash.byMonth")) }
            item { PeriodChart(snapshot.monthly) }
            }

        }
    }
}

private data class PendingPr(
    val repoId: String,
    val repoName: String,
    val prId: Long,
    val title: String,
    val author: String,
    val auto: Boolean,
)

private data class Snapshot(
    val ready: List<ReviewRecord> = emptyList(),
    val recent: List<ReviewRecord> = emptyList(),
    val usage: io.acr.data.ReviewRepository.Usage = io.acr.data.ReviewRepository.Usage(0.0, 0),
    val repoNames: Map<String, String> = emptyMap(),
    val replies: List<io.acr.data.ReplyDraft> = emptyList(),
    val totals: io.acr.data.ReviewRepository.Totals =
        io.acr.data.ReviewRepository.Totals(0, 0, 0, 0, 0, 0),
    val weekly: List<io.acr.data.ReviewRepository.PeriodStat> = emptyList(),
    val monthly: List<io.acr.data.ReviewRepository.PeriodStat> = emptyList(),
)

/** 1.234.567 -> "1.2M" */
private fun formatTokens(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.0fk".format(n / 1_000.0)
    else -> n.toString()
}

private fun openInBrowser(url: String) {
    runCatching {
        if (java.awt.Desktop.isDesktopSupported()) {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url))
        }
    }
}

private fun elapsed(startedAt: Long): String {
    val seconds = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}

/** Barras simples: la escala es relativa al período con más reviews. */
@Composable
private fun PeriodChart(stats: List<io.acr.data.ReviewRepository.PeriodStat>) {
    if (stats.isEmpty()) {
        Empty(io.acr.i18n.t("dash.noStats"))
        return
    }
    val max = stats.maxOf { it.reviews }.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth()) {
        stats.forEach { st ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    st.period,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.width(80.dp),
                )
                Box(
                    Modifier.width((220 * st.reviews / max).coerceAtLeast(4).dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
                Text(
                    "  ${st.reviews} reviews · ${st.prs} PRs · ~US$ ${"%.2f".format(st.cost)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Stat(
    label: String,
    value: String,
    sub: String = "",
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column(
        Modifier.width(210.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .then(if (onClick != null) Modifier.clickableText(onClick) else Modifier)
            .padding(12.dp),
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (sub.isNotBlank()) {
            Text(
                sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RunningCard(p: RunProgress, elapsed: String, onOpen: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (p.auto) "auto · " else "manual · ") + "${p.repoName.ifBlank { "?" }} · #${p.prId}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.clickableText(onOpen),
            )
            Spacer(Modifier.weight(1f))
            Text(elapsed, style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onCancel) { Text("Cancelar") }
        }
        Text(
            p.prTitle.ifBlank { "(sin título)" },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
        if (p.depth.isNotBlank() || p.model.isNotBlank()) {
            Text(
                listOf(p.depth, p.model).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        // Últimas líneas del feed: alcanza para saber en qué anda sin abrir el PR.
        p.lines.takeLast(3).forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ReviewRow(r: ReviewRecord, repoName: String, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickableText(onOpen).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            r.createdAt.take(16).replace('T', ' '),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            (if (r.auto) "auto" else "man") + " · $repoName · #${r.prId}",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(220.dp),
            maxLines = 1,
        )
        Text(r.prTitle, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
        r.costUsd?.let {
            Text(
                "~US$ ${"%.3f".format(it)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        TextButton(onClick = onOpen) { Text(io.acr.i18n.t("common.open")) }
        r.publishedUrl?.takeIf { it.isNotBlank() }?.let { url ->
            TextButton(onClick = { openInBrowser(url) }) { Text(io.acr.i18n.t("common.inBrowser")) }
        }
        Text(
            when {
                r.publishedUrl != null -> "publicada"
                r.status == ReviewStatus.DONE -> "lista"
                else -> r.status.name.lowercase()
            },
            style = MaterialTheme.typography.labelSmall,
            color = when {
                r.publishedUrl != null -> MaterialTheme.colorScheme.onSurfaceVariant
                r.status == ReviewStatus.DONE -> MaterialTheme.colorScheme.primary
                r.status == ReviewStatus.FAILED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.width(90.dp),
        )
    }
}

@Composable
private fun Section(text: String) {
    Column {
        Spacer(Modifier.height(10.dp))
        Text(text, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Empty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}
