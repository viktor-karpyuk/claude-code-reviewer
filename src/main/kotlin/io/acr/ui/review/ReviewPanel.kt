package io.acr.ui.review

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.acr.AppContext
import io.acr.claude.ReviewOutcome
import io.acr.data.PublicationRecord
import io.acr.data.ReviewRecord
import io.acr.data.ReviewStatus
import io.acr.data.StoredComment
import io.acr.forge.Forges
import io.acr.forge.PullRequest
import io.acr.forge.RepoRecord
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

private enum class Tab { Review, Codigo, Commits, Respuestas, Historial }

@Composable
fun ReviewPanel(
    ctx: AppContext,
    repo: RepoRecord,
    prId: Long,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pr by remember(repo.id, prId) { mutableStateOf<PullRequest?>(null) }
    // Arranca vacía y la llena el efecto de abajo, fuera del hilo de UI.
    var review by remember(repo.id, prId) { mutableStateOf<io.acr.data.ReviewRecord?>(null) }
    var draft by remember(repo.id, prId) { mutableStateOf(review?.body ?: "") }
    var loadError by remember(repo.id, prId) { mutableStateOf<String?>(null) }
    var publishing by remember(repo.id, prId) { mutableStateOf(false) }
    var tab by remember(repo.id, prId) { mutableStateOf(Tab.Review) }
    var depth by remember(repo.id, prId) { mutableStateOf(repo.defaultDepth) }
    var kind by remember(repo.id, prId) { mutableStateOf(repo.projectKind) }
    var reload by remember(repo.id, prId) { mutableStateOf(0) }
    var replyBusy by remember(repo.id, prId) { mutableStateOf<String?>(null) }

    val history = io.acr.ui.dbState(repo.id, prId, reload, initial = emptyList()) {
        ctx.reviews.historyForPr(repo.id, prId)
    }
    val publications = io.acr.ui.dbState(repo.id, prId, reload, initial = emptyList()) {
        ctx.publications.forPr(repo.id, prId)
    }
    val thread = io.acr.ui.dbState(repo.id, prId, reload, initial = emptyList()) {
        ctx.comments.forPr(repo.id, prId)
    }
    val localNotes = io.acr.ui.dbState(repo.id, prId, reload, tab, initial = emptyList()) {
        ctx.notes.forPr(repo.id, prId)
    }
    // `tab` en la clave: publicar desde la pestaña Código no toca este `reload`, así que al
    // volver acá los hallazgos seguían figurando como no publicados y se podían republicar.
    val replies = io.acr.ui.dbState(repo.id, prId, reload, tab, initial = emptyList()) {
        ctx.replies.forPr(repo.id, prId)
    }
    val findings = io.acr.ui.dbState(repo.id, prId, reload, review?.id, tab, initial = emptyList()) {
        review?.id?.let { ctx.findings.forReview(it) } ?: emptyList()
    }

    val progressMap by ctx.engine.progress.collectAsState()
    val progress = progressMap[prId]
    val running = progress != null

    // Cuando una review termina —la disparada acá, o una automática de fondo— hay que releer de
    // la base: si no, el panel sigue mostrando "todavía no hay review" con el resultado ya guardado.
    LaunchedEffect(running) {
        if (!running) reload++
    }

    // Relee la review vigente cuando algo cambió (terminó una corrida, se publicó algo).
    LaunchedEffect(repo.id, prId, reload) {
        val fresh = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ctx.reviews.latestFor(repo.id, prId)
        }
        if (fresh != null) {
            val isNewRun = fresh.id != review?.id
            review = fresh
            if (isNewRun) draft = fresh.body.orEmpty()
        }
    }

    LaunchedEffect(repo.id, prId) {
        // Un solo request por el PR puntual, en vez de paginar la lista entera para descartarla.
        runCatching { Forges.of(repo.provider).getPullRequest(repo, prId) }
            .onSuccess { found ->
                pr = found
                if (found == null) loadError = "El PR #$prId ya no existe en ${repo.owner}/${repo.slug}."
            }
            .onFailure { loadError = it.message ?: "No pude traer el PR #$prId." }
        ctx.engine.syncComments(repo, prId)
        reload++
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    // El borrador vive en estado de composición; al navegar se descarta. Se
                    // persiste al salir para no perder lo que el usuario escribió y no guardó.
                    // Sólo se guarda si hay contenido: un draft vaciado por accidente pisaba
                    // la review real con string vacío, sin confirmación ni forma de recuperarla.
                    val r = review
                    if (r != null && draft.isNotBlank() && draft != r.body) {
                        ctx.appScope.launch { runCatching { ctx.reviews.updateBody(r.id, draft) } }
                    }
                    onBack()
                },
            ) { Text(io.acr.i18n.t("common.back")) }
            Spacer(Modifier.weight(1f))
            pr?.url?.takeIf { it.isNotBlank() }?.let { url ->
                TextButton(onClick = { openInBrowser(url) }) { Text(io.acr.i18n.t("common.openBrowser")) }
            }
        }

        Text("#$prId ${pr?.title ?: review?.prTitle ?: ""}", style = MaterialTheme.typography.headlineSmall)
        if (pr == null && review != null) {
            // Sin datos vivos del PR igual se puede leer y publicar la review guardada: lo que
            // se pierde es revisar de nuevo y ver código, que sí necesitan las ramas.
            Text(
                "Mostrando la review guardada; no pude traer los datos vivos del PR. " +
                    "Podés publicar igual, pero no volver a revisar ni ver el código.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        pr?.let {
            Text(
                "${it.author} · ${it.sourceBranch} → ${it.targetBranch} · ${it.headSha.take(12)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        loadError?.let {
            io.acr.ui.components.ErrorBox(
                title = "No pude traer los datos del pull request",
                message = it,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(tab == Tab.Review, { tab = Tab.Review }, { Text(io.acr.i18n.t("tab.review")) })
            FilterChip(
                tab == Tab.Codigo,
                { tab = Tab.Codigo },
                { Text(if (localNotes.isEmpty()) io.acr.i18n.t("tab.code") else io.acr.i18n.t("tab.code") + " (${localNotes.size})") },
            )
            FilterChip(tab == Tab.Commits, { tab = Tab.Commits }, { Text(io.acr.i18n.t("tab.commits")) })
            FilterChip(
                tab == Tab.Respuestas,
                { tab = Tab.Respuestas },
                {
                    val open = replies.count { it.status != io.acr.data.ReplyStatus.PUBLISHED }
                    Text(if (open == 0) io.acr.i18n.t("tab.replies") else io.acr.i18n.t("tab.replies") + " ($open)")
                },
            )
            FilterChip(
                tab == Tab.Historial,
                { tab = Tab.Historial },
                { Text("Historial (${history.size + publications.size + thread.size})") },
            )
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        if (tab == Tab.Codigo) {
            io.acr.ui.code.CodePanel(ctx, repo, pr, prId, snackbar)
            return@Column
        }

        if (tab == Tab.Commits) {
            io.acr.ui.code.CommitsPanel(repo, pr)
            return@Column
        }

        if (tab == Tab.Respuestas) {
            RepliesList(
                replies = replies,
                busyId = replyBusy,
                onDraft = { d ->
                    val target = pr
                    if (target == null) {
                        scope.launch { snackbar.showSnackbar("Necesito los datos del PR para analizar.") }
                        return@RepliesList
                    }
                    replyBusy = d.id
                    ctx.appScope.launch {
                        ctx.engine.draftReply(repo, target, d)
                            .onFailure { e ->
                                snackbar.showSnackbar("No pude redactar: ${e.message?.take(140)}")
                            }
                        replyBusy = null
                        reload++
                    }
                },
                onPublish = { d, body ->
                    replyBusy = d.id
                    ctx.appScope.launch {
                        ctx.engine.publishReply(repo, prId, d, body)
                            .onSuccess { snackbar.showSnackbar("Respuesta publicada en el hilo.") }
                            .onFailure { e -> snackbar.showSnackbar("No pude publicar: ${e.message?.take(140)}") }
                        replyBusy = null
                        reload++
                    }
                },
                onEdit = { d, body ->
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            ctx.replies.updateBody(d.id, body)
                        }
                        reload++
                    }
                },
                onOpen = ::openInBrowser,
            )
            return@Column
        }

        if (tab == Tab.Historial) {
            HistoryList(history, publications, thread, localNotes, onOpen = ::openInBrowser)
            return@Column
        }

        // Perfil de esta corrida. Arranca en el default del repo y se puede cambiar por review.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(depth == null, { depth = null }, { Text("Auto") }, enabled = !running)
            io.acr.claude.ReviewDepth.entries.forEach { d ->
                FilterChip(depth == d, { depth = d }, { Text(d.label) }, enabled = !running)
            }
            Spacer(Modifier.weight(1f))
            FilterChip(kind == null, { kind = null }, { Text("Auto") }, enabled = !running)
            io.acr.claude.ProjectKind.entries.forEach { k ->
                FilterChip(kind == k, { kind = k }, { Text(k.label) }, enabled = !running)
            }
        }
        Text(
            depth?.blurb ?: "Auto: la profundidad y el tipo salen del diff; vas a ver el motivo al correr.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = pr != null && !running,
                onClick = {
                    val target = pr ?: return@Button
                    // appScope y no el de la pantalla: la review tiene que sobrevivir a que el
                    // usuario navegue a otro PR mientras corre.
                    ctx.appScope.launch {
                        when (val out = ctx.engine.review(repo, target, depth, kind, repo.defaultModel)) {
                            is ReviewOutcome.Ok -> {
                                review = out.record
                                draft = out.record.body.orEmpty()
                            }
                            is ReviewOutcome.Error -> {
                                review = ctx.reviews.latestFor(repo.id, prId)
                                snackbar.showSnackbar(out.message)
                            }
                        }
                        reload++
                    }
                },
            ) { Text(if (review == null) io.acr.i18n.t("review.run") else io.acr.i18n.t("review.rerun")) }

            if (running) {
                OutlinedButton(onClick = { ctx.engine.cancel(prId) }) { Text(io.acr.i18n.t("common.cancel")) }
                CircularProgressIndicator(Modifier.height(20.dp))
            }

            Spacer(Modifier.weight(1f))
            review?.costUsd?.let {
                Text(
                    "US$ ${"%.4f".format(it)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (running) {
            ProgressFeed(progress!!.lines)
        } else {
            if (findings.isNotEmpty()) {
                FindingsSummary(
                    findings = findings,
                    publishing = publishing,
                    onPublishAll = {
                        val head = pr?.headSha ?: return@FindingsSummary
                        publishing = true
                        scope.launch {
                            var ok = 0
                            var failed = 0
                            try {
                                findings.filter { it.publishedId == null }.forEach { f ->
                                    ctx.engine.publishFinding(repo, prId, f, head)
                                        .onSuccess { ok++ }
                                        .onFailure { failed++ }
                                }
                            } finally {
                                reload++
                                publishing = false
                            }
                            // Antes sólo se contaban los éxitos: los fallos eran invisibles.
                            snackbar.showSnackbar(
                                if (failed == 0) "$ok hallazgo(s) publicados inline."
                                else "$ok publicados, $failed fallaron.",
                            )
                        }
                    },
                )
                Spacer(Modifier.height(10.dp))
            }
            ReviewBody(
                review = review,
                draft = draft,
                onDraft = { draft = it },
                publishing = publishing,
                onSaveDraft = {
                    val r = review
                    if (r != null && draft.isNotBlank()) {
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                ctx.reviews.updateBody(r.id, draft)
                            }
                            reload++
                        }
                    }
                },
                onPublish = {
                    val current = review ?: return@ReviewBody
                    publishing = true
                    scope.launch {
                        // try/finally: si updateBody tira, publishing quedaba en true para siempre
                        // y el botón se deshabilitaba sin ningún mensaje.
                        try {
                            ctx.reviews.updateBody(current.id, draft)
                            ctx.engine.publish(repo, prId, current.id, draft)
                                .onSuccess {
                                    review = ctx.reviews.get(current.id)
                                    reload++
                                    snackbar.showSnackbar("Comentario publicado.")
                                }
                                .onFailure { snackbar.showSnackbar("No pude publicar: ${it.message?.take(160)}") }
                        } catch (e: Exception) {
                            snackbar.showSnackbar("No pude publicar: ${e.message?.take(160)}")
                        } finally {
                            publishing = false
                        }
                    }
                },
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HistoryList(
    reviews: List<ReviewRecord>,
    publications: List<PublicationRecord>,
    thread: List<StoredComment>,
    localNotes: List<io.acr.data.LocalNote>,
    onOpen: (String) -> Unit,
) {
    var author by remember(thread) { mutableStateOf<String?>(null) }

    // Autores ordenados por cantidad: el que más comentó primero, que es a quien más se busca.
    val authors = remember(thread) {
        thread.groupingBy { it.author }.eachCount().entries.sortedByDescending { it.value }
    }
    val shown = remember(thread, author) {
        author?.let { a -> thread.filter { it.author == a } } ?: thread
    }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { SectionTitle("Reviews corridas (${reviews.size})") }
        if (reviews.isEmpty()) item { Empty("Todavía no corriste ninguna review de este PR.") }
        items(reviews, key = { "r-${it.id}" }) { r ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.createdAt.take(16).replace('T', ' '), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        r.status.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (r.status) {
                            ReviewStatus.DONE -> MaterialTheme.colorScheme.primary
                            ReviewStatus.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text(
                    (r.depth?.label?.plus(" · ") ?: "") + (r.projectKind?.label?.plus(" · ") ?: "") +
                        (r.model?.takeIf { it.isNotBlank() }?.plus(" · ") ?: "") +
                        "commit ${r.headSha.take(12)}" +
                        (r.costUsd?.let { " · US$ ${"%.4f".format(it)}" } ?: "") +
                        (r.sessionId?.let { " · sesión ${it.take(8)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                (r.body ?: r.error)?.let { Text(it.take(320), style = MaterialTheme.typography.bodySmall) }
            }
        }

        item { SectionTitle("Publicadas por nosotros (${publications.size})") }
        if (publications.isEmpty()) item { Empty("Ninguna review de este PR fue publicada todavía.") }
        items(publications, key = { "p-${it.id}" }) { p ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.publishedAt.take(16).replace('T', ' '), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    p.url?.let { TextButton(onClick = { onOpen(it) }) { Text("Ver") } }
                }
                Text(p.body.take(320), style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            SectionTitle(
                if (author == null) "Hilo del pull request (${thread.size})"
                else "Hilo del pull request (${shown.size} de ${thread.size})",
            )
        }
        if (authors.size > 1) {
            item {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                ) {
                    FilterChip(
                        selected = author == null,
                        onClick = { author = null },
                        label = { Text("Todos (${thread.size})") },
                    )
                    authors.forEach { (name, count) ->
                        FilterChip(
                            // Volver a tocar el chip activo limpia el filtro, sin ir hasta «Todos».
                            selected = author == name,
                            onClick = { author = if (author == name) null else name },
                            label = { Text("$name ($count)") },
                        )
                    }
                }
            }
        }
        if (thread.isEmpty()) item { Empty("Sin comentarios en el PR, o todavía no se sincronizó.") }
        else if (shown.isEmpty()) item { Empty("$author no dejó comentarios en este PR.") }
        items(shown, key = { "c-${it.commentId}" }) { c ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (c.ours) "${c.author} (nosotros)" else c.author,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (c.ours) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        c.createdOn.take(16).replace('T', ' '),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                c.inlinePath?.let {
                    Text(
                        it + (c.inlineLine?.let { l -> ":$l" } ?: ""),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(c.body.take(320), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
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

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        content = content,
    )
}

@Composable
private fun ProgressFeed(lines: List<String>) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        Text(io.acr.i18n.t("review.running"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
        ) {
            items(lines) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReviewBody(
    review: ReviewRecord?,
    draft: String,
    onDraft: (String) -> Unit,
    publishing: Boolean,
    onSaveDraft: () -> Unit,
    onPublish: () -> Unit,
) {
    if (review == null) {
        Box(Modifier.fillMaxSize()) {
            Text(
                io.acr.i18n.t("review.none"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        return
    }

    if (review.status == ReviewStatus.FAILED || review.status == ReviewStatus.CANCELLED) {
        io.acr.ui.components.ErrorBox(
            title = if (review.status == ReviewStatus.CANCELLED) io.acr.i18n.t("review.cancelled") else io.acr.i18n.t("review.failed"),
            message = review.error.orEmpty().ifBlank { "(sin detalle)" },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(io.acr.i18n.t("review.proposed"), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            review.publishedUrl?.let { url ->
                TextButton(onClick = { openInBrowser(url) }) { Text(io.acr.i18n.t("review.seePublished")) }
            }
        }
        review.deniedTools?.takeIf { it.isNotBlank() }?.let {
            Text(
                "Nota interna (no se publica): esta review corrió con herramientas denegadas ($it), " +
                    "así que puede estar incompleta.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = draft,
            onValueChange = onDraft,
            modifier = Modifier.fillMaxWidth().weight(1f),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            label = { Text(io.acr.i18n.t("review.markdownLabel")) },
        )

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = draft.isNotBlank() && !publishing, onClick = onPublish) {
                Text(if (review.publishedUrl == null) io.acr.i18n.t("review.publishComment") else io.acr.i18n.t("review.publishAgain"))
            }
            OutlinedButton(enabled = !publishing, onClick = onSaveDraft) { Text(io.acr.i18n.t("common.saveDraft")) }
            if (publishing) CircularProgressIndicator(Modifier.height(20.dp))
            Spacer(Modifier.weight(1f))
            review.sessionId?.let {
                Text(
                    "sesión ${it.take(8)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FindingsSummary(
    findings: List<io.acr.data.Finding>,
    publishing: Boolean,
    onPublishAll: () -> Unit,
) {
    val pending = findings.count { it.publishedId == null }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${findings.size} " + io.acr.i18n.t("review.findingsAnchored"), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            if (pending > 0) {
                Button(enabled = !publishing, onClick = onPublishAll) { Text(io.acr.i18n.t("review.publishInline") + " ($pending)") }
            } else {
                Text(
                    io.acr.i18n.t("review.allPublished"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        findings.take(12).forEach { f ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(
                    f.severity.take(3).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (f.severity) {
                        "blocker" -> MaterialTheme.colorScheme.error
                        "major" -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.width(36.dp),
                )
                Text(
                    f.filePath.substringAfterLast('/') + (f.lineNo?.let { ":$it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.width(220.dp),
                    maxLines = 1,
                )
                Text(f.title, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RepliesList(
    replies: List<io.acr.data.ReplyDraft>,
    busyId: String?,
    onDraft: (io.acr.data.ReplyDraft) -> Unit,
    onPublish: (io.acr.data.ReplyDraft, String) -> Unit,
    onEdit: (io.acr.data.ReplyDraft, String) -> Unit,
    onOpen: (String) -> Unit,
) {
    if (replies.isEmpty()) {
        Box(Modifier.fillMaxSize()) {
            Text(
                io.acr.i18n.t("replies.none"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(replies, key = { it.id }) { d ->
            var draftText by remember(d.id, d.body) { mutableStateOf(d.body.orEmpty()) }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${d.theirAuthor} " + io.acr.i18n.t("replies.replied"), style = MaterialTheme.typography.titleSmall)
                    d.filePath?.let {
                        Text(
                            "  ${it.substringAfterLast('/')}${d.lineNo?.let { l -> ":$l" } ?: ""}",
                            style = MaterialTheme.typography.labelSmall
                                .copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        d.status.name.lowercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (d.status == io.acr.data.ReplyStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                d.ourBody?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "Nuestro comentario: ${it.replace('\n', ' ').take(180)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                SelectionContainer { Text(d.theirBody, style = MaterialTheme.typography.bodySmall) }

                d.error?.let {
                    Spacer(Modifier.height(6.dp))
                    io.acr.ui.components.ErrorBox(io.acr.i18n.t("replies.draftError"), it)
                }

                Spacer(Modifier.height(8.dp))
                if (d.status == io.acr.data.ReplyStatus.PUBLISHED) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(io.acr.i18n.t("replies.publishedReply"), style = MaterialTheme.typography.labelMedium)
                        d.publishedUrl?.takeIf { it.isNotBlank() }?.let { url ->
                            TextButton(onClick = { onOpen(url) }) { Text(io.acr.i18n.t("common.openBrowser")) }
                        }
                    }
                    d.body?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                } else if (d.body.isNullOrBlank()) {
                    Button(enabled = busyId != d.id, onClick = { onDraft(d) }) {
                        Text(if (busyId == d.id) io.acr.i18n.t("replies.analyzing") else io.acr.i18n.t("replies.analyze"))
                    }
                } else {
                    OutlinedTextField(
                        value = draftText,
                        onValueChange = { draftText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp),
                        label = { Text(io.acr.i18n.t("replies.draftLabel")) },
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = busyId != d.id && draftText.isNotBlank(),
                            onClick = { onPublish(d, draftText) },
                        ) { Text(if (busyId == d.id) io.acr.i18n.t("replies.analyzing") else io.acr.i18n.t("replies.publishReply")) }
                        OutlinedButton(onClick = { onEdit(d, draftText) }) { Text(io.acr.i18n.t("common.saveDraft")) }
                        OutlinedButton(enabled = busyId != d.id, onClick = { onDraft(d) }) {
                            Text("Redactar de nuevo")
                        }
                    }
                }
            }
        }
    }
}

private fun openInBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url))
    }
}
