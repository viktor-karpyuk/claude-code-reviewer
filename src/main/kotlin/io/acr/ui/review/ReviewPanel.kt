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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import io.acr.AppContext
import io.acr.claude.ReviewOutcome
import io.acr.data.PublicationRecord
import io.acr.data.ReviewRecord
import io.acr.data.ReviewStatus
import io.acr.data.StoredComment
import io.acr.forge.Forges
import io.acr.forge.PullRequest
import io.acr.forge.RepoRecord
import io.acr.ui.clickableText
import io.acr.ui.prs.color
import io.acr.ui.prs.mark
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

private enum class Tab { Review, Codigo, Commits, Conversacion, Historial }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    // Conjunto y no un solo id: se pueden analizar y publicar varias respuestas a la vez, y cada
    // tarjeta necesita saber si LA SUYA está ocupada, no si hay algo ocupado en la pantalla.
    var replyBusy by remember(repo.id, prId) { mutableStateOf<Set<String>>(emptySet()) }
    var confirmarMerge by remember(repo.id, prId) { mutableStateOf(false) }
    // La review previa de este mismo commit, cuando hay que preguntar si repetirla.
    var repetir by remember(repo.id, prId) { mutableStateOf<io.acr.data.PriorReview?>(null) }
    var mergeando by remember(repo.id, prId) { mutableStateOf(false) }
    var verificando by remember(repo.id, prId) { mutableStateOf(false) }
    var aprobando by remember(repo.id, prId) { mutableStateOf(false) }
    // Lo que ya dijimos sobre este PR, si dijimos algo: aprobado, cambios pedidos, o nada.
    val miPostura = io.acr.ui.dbState(repo.id, prId, reload, initial = null) {
        ctx.approvals.forPr(repo.id, prId).firstOrNull { it.byUs }?.stance
    }
    val nuestroNombre = io.acr.ui.dbState(repo.id, reload, initial = null) {
        ctx.comments.ourDisplayName()
    }
    val posturas = io.acr.ui.dbState(repo.id, prId, reload, initial = emptyList()) {
        ctx.approvals.forPr(repo.id, prId)
    }
    var confirmarDeclinar by remember(repo.id, prId) { mutableStateOf(false) }
    // La estrategia elegida se recuerda: en un equipo se usa casi siempre la misma.
    var estrategia by remember {
        mutableStateOf(io.acr.forge.MergeStrategy.fromName(ctx.prefs.get(AppContext.PREF_MERGE_STRATEGY)))
    }
    // Salto pendiente al visor de código, pedido desde la conversación.
    var codeFocus by remember(repo.id, prId) { mutableStateOf<io.acr.ui.code.CodeFocus?>(null) }
    // Pantalla completa: esconde todo lo de arriba y deja las pestañas con el alto entero.
    var pantallaCompleta by remember(repo.id, prId) { mutableStateOf(false) }
    val altoCabecera = io.acr.ui.rememberPaneWidth(ctx.prefs, "prHeader", 300.dp)
    // De qué hilo se salió al código, para poder volver exactamente ahí. Null = se entró al
    // código de frente por la pestaña, y entonces no hay a dónde volver.
    var volverAlHilo by remember(repo.id, prId) { mutableStateOf<String?>(null) }
    // Hilo al que hay que llevar la conversación al volver.
    var hiloDestacado by remember(repo.id, prId) { mutableStateOf<String?>(null) }
    // Hilo sobre el que se está por mandar un recordatorio.
    var seguimientoDe by remember(repo.id, prId) { mutableStateOf<ConversationThread?>(null) }
    // Conjunto y no un booleano global: publicar un comentario no puede apagar los botones de los
    // otros. Con un solo flag, apretar uno dejaba el resto deshabilitado —y un botón deshabilitado
    // de Material tiene tan poco contraste que parece que desapareció.
    var publicandoIds by remember(repo.id, prId) { mutableStateOf<Set<String>>(emptySet()) }
    var pasadaFinal by remember(repo.id, prId) { mutableStateOf(false) }
    val diasParaRecordar = remember {
        ctx.prefs.get(AppContext.PREF_FOLLOWUP_DAYS)?.toLongOrNull()?.coerceAtLeast(1) ?: 3L
    }

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
            ctx.reviews.latestUsableFor(repo.id, prId)
        }
        if (fresh != null) {
            val isNewRun = fresh.id != review?.id
            review = fresh
            if (isNewRun) draft = fresh.body.orEmpty()
        }
    }

    var cargandoPr by remember(repo.id, prId) { mutableStateOf(false) }

    // Extraída del efecto para poder reintentar: con el 401 intermitente de Bitbucket, que la
    // primera carga falle es común, y hasta ahora la única forma de reintentar era salir y volver.
    suspend fun cargarPr() {
        cargandoPr = true
        loadError = null
        // Un solo request por el PR puntual, en vez de paginar la lista entera para descartarla.
        runCatching { Forges.of(repo.provider).getPullRequest(repo, prId) }
            .onSuccess { found ->
                pr = found
                // El PR individual sí trae quiénes aprobaron; se guardan para que la lista lo
                // sepa sin tener que pedir cada PR por separado.
                if (found != null) ctx.engine.rememberApprovals(repo.id, found)
                if (found == null) loadError = "El PR #$prId ya no existe en ${repo.owner}/${repo.slug}."
            }
            .onFailure { loadError = it.message ?: "No pude traer el PR #$prId." }
        cargandoPr = false
        ctx.engine.syncComments(repo, prId)
        reload++
    }

    LaunchedEffect(repo.id, prId) { cargarPr() }

    // La conversación se arma acá porque la usan tanto el contador de la pestaña como la vista.
    val hilos = buildConversation(findings, thread, replies)

    // Lanzar una redacción: sirve para una sola o para todas las pendientes.
    fun draft(d: io.acr.data.ReplyDraft, target: PullRequest) {
        if (d.id in replyBusy) return
        replyBusy = replyBusy + d.id
        // appScope y no el de la pantalla: la redacción sobrevive a navegar a otro lado.
        ctx.appScope.launch {
            ctx.engine.draftReply(repo, target, d)
                .onFailure { e -> snackbar.showSnackbar("No pude redactar: ${e.message?.take(140)}") }
            replyBusy = replyBusy - d.id
            reload++
        }
    }

    // Si te vas del código por tu cuenta —tocando otra pestaña— el "volver" deja de tener sentido:
    // apuntaría a un hilo del que ya saliste. Se limpia salvo cuando el salto acaba de ponerlo.
    LaunchedEffect(tab) { if (tab != Tab.Codigo) volverAlHilo = null }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        val bloqueo = mergeBlocker(pr, review, findings, localNotes, replies)
        // La pasada final sólo vale para el commit sobre el que corrió: si el PR avanzó, quedó
        // vieja y vuelve a faltar.
        val finalHecha = review?.finalPassHead != null && pr != null &&
            review!!.finalPassHead == pr!!.headSha
        val listo = mergeReadiness(
            pr, review, hilos, findings,
            finalPassDone = finalHecha,
            finalPassBlockers = if (finalHecha) review!!.finalPassBlockers else 0,
        )
        // La fila de acciones NO se esconde cuando faltan datos: esconderla hacía imposible saber
        // si la función existe. Se muestra el motivo y, si es un fallo de red, cómo reintentar.
        // Sólo cuando ya terminó de intentar: durante la carga inicial `pr` es null un instante
        // y mostrar "reintentar" ahí sería un parpadeo.

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
            // El link del PR: se copia sin salir de la app, que es lo que uno hace para pegarlo
            // en un chat o en un ticket.
            val urlPr = pr?.url?.takeIf { it.isNotBlank() }
                ?: "https://bitbucket.org/${repo.owner}/${repo.slug}/pull-requests/$prId"
            val portapapeles = androidx.compose.ui.platform.LocalClipboardManager.current
            TextButton(onClick = {
                portapapeles.setText(androidx.compose.ui.text.AnnotatedString(urlPr))
                scope.launch { snackbar.showSnackbar(io.acr.i18n.t2("common.urlCopied")) }
            }) { Text(io.acr.i18n.t("common.copyUrl")) }
            pr?.url?.takeIf { it.isNotBlank() }?.let { url ->
                TextButton(onClick = { openInBrowser(url) }) { Text(io.acr.i18n.t("common.openBrowser")) }
            }
            // Un click para dedicarle toda la pantalla al contenido; otro para volver.
            TextButton(onClick = { pantallaCompleta = !pantallaCompleta }) {
                Text(
                    if (pantallaCompleta) io.acr.i18n.t("pr.exitFullScreen")
                    else io.acr.i18n.t("pr.fullScreen"),
                )
            }
        }

        if (!pantallaCompleta) {
        Column(
            Modifier.fillMaxWidth().height(altoCabecera.value)
                .verticalScroll(rememberScrollState()),
        ) {
        Text("#$prId ${pr?.title ?: review?.prTitle ?: ""}", style = MaterialTheme.typography.headlineSmall)
        // Si lo último que corrió falló pero hay una review buena anterior, se muestra esa y se
        // dice por qué, en vez de dejar la pantalla en un error sin contenido.
        val newest = io.acr.ui.dbState(repo.id, prId, reload, initial = null) {
            ctx.reviews.latestFor(repo.id, prId)
        }
        if (newest != null && review != null && newest.id != review!!.id) {
            Text(
                io.acr.i18n.t("review.showingPrevious", newest.status.name.lowercase()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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
            // Desde que se abrió el PR, no desde el último commit: es cuánto lleva esperando.
            io.acr.ui.prs.ageInDays(it.createdOn, java.time.LocalDate.now())?.let { dias ->
                val nivel = io.acr.ui.prs.Urgency.fromDays(dias)!!
                Text(
                    io.acr.i18n.t("prs.created") + " " + it.createdOn +
                        (it.updatedOn.takeIf { u -> u.isNotBlank() && u != it.createdOn }
                            ?.let { u -> " · " + io.acr.i18n.t("prs.updated") + " " + u } ?: "") +
                        " · " + io.acr.i18n.t("prs.ageTitle", dias) +
                        nivel.mark().let { m -> if (m.isBlank()) "" else "  $m " } +
                        (if (nivel == io.acr.ui.prs.Urgency.FRESCO) "" else io.acr.i18n.t(nivel.labelKey)),
                    style = MaterialTheme.typography.labelSmall,
                    color = nivel.color(),
                )
            }
        }
        loadError?.let {
            io.acr.ui.components.ErrorBox(
                title = "No pude traer los datos del pull request",
                message = it,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }

        // Mergear: la única acción de la app que cambia el repositorio y no se puede deshacer.
        // Se habilita sólo cuando no queda nada esperando y el autor subió código después de la
        // review; en cualquier otro caso el botón dice por qué no.
        if (pr == null && !cargandoPr) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = !cargandoPr,
                    onClick = { scope.launch { cargarPr() } },
                ) { Text(if (cargandoPr) io.acr.i18n.t("prs.loading") else io.acr.i18n.t("common.retry")) }
                Spacer(Modifier.width(10.dp))
                Text(
                    io.acr.i18n.t("merge.actionsHidden"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (pr != null && pr!!.state != io.acr.forge.PrState.OPEN) {
            Spacer(Modifier.height(10.dp))
            Text(
                io.acr.i18n.t("merge.notOpen"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Sin exigir que haya review: aprobar y declinar no la necesitan, y esconder los cuatro
        // botones porque falta uno dejaba sin acciones a un PR todavía sin revisar. Mergear sigue
        // pidiéndola, pero eso ya lo dice su propia condición.
        if (pr != null && pr!!.state == io.acr.forge.PrState.OPEN) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Verificar va antes de mergear en el orden de lectura porque ese es el orden
                // real: primero se comprueba que lo señalado se arregló, después se mergea.
                val publicados = findings.count { it.publishedId != null && it.dismissedAt == null }
                if (publicados > 0) {
                    OutlinedButton(
                        enabled = !verificando && !mergeando,
                        onClick = {
                            val objetivo = pr ?: return@OutlinedButton
                            val rev = review ?: return@OutlinedButton
                            verificando = true
                            ctx.appScope.launch {
                                ctx.engine.verifyResolution(repo, objetivo, rev)
                                    .onFailure {
                                        snackbar.showSnackbar(
                                            io.acr.i18n.t2("verify.failed") + ": " + it.message?.take(160),
                                        )
                                    }
                                verificando = false
                                reload++
                            }
                        },
                    ) {
                        Text(
                            if (verificando) io.acr.i18n.t("verify.running")
                            else io.acr.i18n.t("verify.action"),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                // Aprobar y pedir cambios son excluyentes y las dos se pueden retirar, igual que
                // en Bitbucket. Los botones cambian según lo que ya dijiste: ofrecer "Aprobar" a
                // quien ya aprobó no dice nada y esconde la acción que sí sirve.
                fun accion(bloque: suspend () -> Unit, ok: String) {
                    aprobando = true
                    ctx.appScope.launch {
                        runCatching {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { bloque() }
                        }
                            .onSuccess { snackbar.showSnackbar(io.acr.i18n.t2(ok)) }
                            .onFailure {
                                snackbar.showSnackbar(
                                    io.acr.i18n.t2("approve.failed") + ": " + it.message?.take(160),
                                )
                            }
                        aprobando = false
                        reload++
                    }
                }
                // El nombre con el que el proveedor nos nombra, no un genérico: es el que va a
                // aparecer en "aprobado por …" y el que hace que coincida con lo que trae la API.
                val yo = nuestroNombre ?: io.acr.i18n.t("msg.us")
                // Los círculos de todos los que se pronunciaron, no sólo el nuestro.
                if (posturas.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        posturas.forEach { p ->
                            io.acr.ui.PersonAvatar(p.approvedBy, stance = p.stance)
                        }
                    }
                }
                if (miPostura == io.acr.data.ReviewStance.APPROVED) {
                    Spacer(Modifier.width(4.dp))
                    OutlinedButton(
                        enabled = !aprobando,
                        onClick = {
                            accion({
                                ctx.engine.withdrawStance(repo, prId, io.acr.data.ReviewStance.APPROVED)
                                    .getOrThrow()
                            }, "approve.withdrawn")
                        },
                    ) { Text(io.acr.i18n.t("approve.withdraw")) }
                } else {
                    OutlinedButton(
                        enabled = !aprobando,
                        onClick = { accion({ ctx.engine.approve(repo, prId, yo).getOrThrow() }, "approve.done") },
                    ) {
                        Text(
                            if (aprobando) io.acr.i18n.t("approve.running")
                            else io.acr.i18n.t("approve.action"),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (miPostura == io.acr.data.ReviewStance.CHANGES_REQUESTED) {
                    Text(
                        io.acr.i18n.t("changes.mine"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        enabled = !aprobando,
                        onClick = {
                            accion({
                                ctx.engine.withdrawStance(
                                    repo, prId, io.acr.data.ReviewStance.CHANGES_REQUESTED,
                                ).getOrThrow()
                            }, "changes.withdrawn")
                        },
                    ) { Text(io.acr.i18n.t("changes.withdraw")) }
                } else {
                    OutlinedButton(
                        enabled = !aprobando,
                        onClick = {
                            accion({ ctx.engine.requestChanges(repo, prId, yo).getOrThrow() }, "changes.done")
                        },
                    ) { Text(io.acr.i18n.t("changes.action")) }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { confirmarDeclinar = true }) {
                    Text(io.acr.i18n.t("decline.action"), color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    enabled = !pasadaFinal && pr != null && review != null,
                    onClick = {
                        val objetivo = pr ?: return@OutlinedButton
                        val rev = review ?: return@OutlinedButton
                        pasadaFinal = true
                        ctx.appScope.launch {
                            ctx.engine.finalPass(repo, objetivo, rev)
                                .onFailure {
                                    snackbar.showSnackbar(
                                        io.acr.i18n.t2("final.failed") + ": " + it.message?.take(160),
                                    )
                                }
                            pasadaFinal = false
                            reload++
                        }
                    },
                ) {
                    Text(
                        if (pasadaFinal) io.acr.i18n.t("final.running") else io.acr.i18n.t("final.action"),
                    )
                }
                Spacer(Modifier.width(8.dp))
                // Siempre habilitado: mergear con cosas sin resolver es una decisión legítima
                // —urgencias, comentarios que ya no aplican, un fix que no puede esperar— y la app
                // no está para impedirla. Lo que sí hace es decir qué se saltea, en el botón y de
                // nuevo en la confirmación, para que sea una decisión y no un descuido.
                Button(
                    enabled = !mergeando,
                    onClick = { confirmarMerge = true },
                ) { Text(if (mergeando) io.acr.i18n.t("merge.merging") else io.acr.i18n.t("merge.action")) }
                Spacer(Modifier.width(10.dp))
                Text(
                    bloqueo?.let { io.acr.i18n.t(it) } ?: io.acr.i18n.t("merge.ready"),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bloqueo == null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            ReadinessCard(listo, ctx.prefs)

            review?.finalPassSummary?.takeIf { it.isNotBlank() && finalHecha }?.let { resumen ->
                Spacer(Modifier.height(8.dp))
                val conBloqueantes = review!!.finalPassBlockers > 0
                io.acr.ui.CollapsibleCard(
                    title = io.acr.i18n.t("final.title"),
                    prefs = ctx.prefs,
                    key = "finalPass",
                    accent = if (conBloqueantes) MaterialTheme.colorScheme.error else io.acr.ui.VERDE_OK,
                    trailing = if (conBloqueantes) {
                        io.acr.i18n.t("final.blockers", review!!.finalPassBlockers)
                    } else io.acr.i18n.t("final.clean"),
                    // Arranca plegada: el resumen de la pasada final del PR #149 son 3.728
                    // caracteres, y abierto por defecto empuja abajo todo el resto de la
                    // pantalla. La conclusión —"nada que frene el merge" o "2 bloqueantes"— sigue
                    // a la vista en el título, que es lo que uno mira primero.
                    defaultCollapsed = true,
                ) {
                    SelectionContainer {
                        Text(resumen, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            review?.resolutionSummary?.takeIf { it.isNotBlank() }?.let { resumen ->
                Spacer(Modifier.height(8.dp))
                // Una verificación vieja es peor que ninguna: dice "corregido" sobre un código
                // que ya cambió. Si llegaron commits después, se avisa en el título.
                val vieja = review!!.resolutionHead != null &&
                    pr != null && pr!!.headSha.isNotBlank() &&
                    review!!.resolutionHead != pr!!.headSha
                io.acr.ui.CollapsibleCard(
                    title = io.acr.i18n.t("verify.title"),
                    prefs = ctx.prefs,
                    key = "verify",
                    accent = if (vieja) MaterialTheme.colorScheme.error else io.acr.ui.VERDE_OK,
                    trailing = if (vieja) io.acr.i18n.t("verify.stale") else null,
                    trailingColor = MaterialTheme.colorScheme.error,
                    // Igual que la anterior: el aviso de "quedó vieja" se ve plegada.
                    defaultCollapsed = true,
                ) {
                    SelectionContainer {
                        Text(resumen, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        }
        // Arrastrable: cuánto se lleva la cabecera y cuánto queda para el código lo decide quien
        // mira, no el layout. Se puede bajar hasta cero, y el botón de pantalla completa hace lo
        // mismo de un click.
        io.acr.ui.HorizontalSplitter(altoCabecera, ctx.prefs, "prHeader", min = 0.dp, max = 620.dp)
        }

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(tab == Tab.Review, { tab = Tab.Review }, { Text(io.acr.i18n.t("tab.review")) })
            FilterChip(
                tab == Tab.Codigo,
                { tab = Tab.Codigo },
                { Text(if (localNotes.isEmpty()) io.acr.i18n.t("tab.code") else io.acr.i18n.t("tab.code") + " (${localNotes.size})") },
            )
            FilterChip(tab == Tab.Commits, { tab = Tab.Commits }, { Text(io.acr.i18n.t("tab.commits")) })
            FilterChip(
                tab == Tab.Conversacion,
                { tab = Tab.Conversacion },
                {
                    val abiertos = hilos.count { it.open }
                    Text(
                        if (abiertos == 0) io.acr.i18n.t("tab.conversation")
                        else io.acr.i18n.t("tab.conversation") + " ($abiertos)",
                    )
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

        // Recordatorio: se publica en el PR de otra persona, así que el texto se muestra y se
        // puede editar antes de mandarlo. La app nunca lo manda sola.
        seguimientoDe?.let { hilo ->
            val plantilla = io.acr.i18n.t("followup.message", hilo.waitingDays ?: 0)
            var texto by remember(hilo.findingId) { mutableStateOf(plantilla) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { seguimientoDe = null },
                title = { Text(io.acr.i18n.t("followup.title")) },
                text = {
                    Column {
                        Text(
                            (hilo.filePath?.substringAfterLast('/') ?: "") +
                                (hilo.lineNo?.let { ":$it" } ?: "") + " · " + hilo.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = texto,
                            onValueChange = { texto = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                            supportingText = { Text(io.acr.i18n.t("followup.note")) },
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = texto.isNotBlank(),
                        onClick = {
                            val f = findings.firstOrNull { it.id == hilo.findingId }
                            seguimientoDe = null
                            if (f != null) {
                                ctx.appScope.launch {
                                    ctx.engine.postFollowUp(repo, prId, f, texto.trim())
                                        .onSuccess { snackbar.showSnackbar(io.acr.i18n.t2("followup.done")) }
                                        .onFailure {
                                            snackbar.showSnackbar(
                                                io.acr.i18n.t2("followup.failed") + ": " + it.message?.take(160),
                                            )
                                        }
                                    reload++
                                }
                            }
                        },
                    ) { Text(io.acr.i18n.t("followup.send")) }
                },
                dismissButton = {
                    TextButton(onClick = { seguimientoDe = null }) {
                        Text(io.acr.i18n.t("common.cancel"))
                    }
                },
            )
        }

        // Declinar cierra el PR del otro lado. Se pide el motivo en el mismo paso: un rechazo sin
        // explicación obliga a quien lo recibe a adivinar, y una vez cerrado el hilo queda menos
        // a la vista.
        if (confirmarDeclinar) {
            var motivo by remember { mutableStateOf("") }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmarDeclinar = false },
                title = { Text(io.acr.i18n.t("decline.confirmTitle", prId)) },
                text = {
                    Column {
                        Text(io.acr.i18n.t("decline.confirmBody"))
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = motivo,
                            onValueChange = { motivo = it },
                            label = { Text(io.acr.i18n.t("decline.reason")) },
                            supportingText = { Text(io.acr.i18n.t("decline.reasonNote")) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = motivo.isNotBlank(),
                        onClick = {
                            confirmarDeclinar = false
                            ctx.appScope.launch {
                                runCatching {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        Forges.of(repo.provider).decline(repo, prId, motivo.trim())
                                    }
                                }
                                    .onSuccess {
                                        snackbar.showSnackbar(io.acr.i18n.t2("decline.done"))
                                        reload++
                                    }
                                    .onFailure {
                                        snackbar.showSnackbar(
                                            io.acr.i18n.t2("decline.failed") + ": " + it.message?.take(160),
                                        )
                                    }
                            }
                        },
                    ) {
                        Text(io.acr.i18n.t("decline.confirm"), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmarDeclinar = false }) {
                        Text(io.acr.i18n.t("common.cancel"))
                    }
                },
            )
        }

        // Confirmación: mergear es irreversible y hacia afuera. Es el caso donde un modal
        // corresponde, porque interrumpe a propósito. El diálogo es compartido con la lista.
        if (confirmarMerge && pr != null) {
            val objetivo = pr!!
            io.acr.ui.MergeDialog(
                pr = objetivo,
                strategy = estrategia,
                onStrategy = {
                    estrategia = it
                    ctx.prefs.put(AppContext.PREF_MERGE_STRATEGY, it.name)
                },
                pendientes = listo.missing.map {
                    io.acr.i18n.t(it.key) + if (it.detail.isBlank()) "" else " (${it.detail})"
                },
                onDismiss = { confirmarMerge = false },
                onConfirm = { eleccion ->
                    confirmarMerge = false
                    mergeando = true
                    ctx.appScope.launch {
                        runCatching {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                Forges.of(repo.provider).merge(
                                    repo, prId, eleccion.message,
                                    eleccion.closeSourceBranch, eleccion.strategy,
                                )
                            }
                        }
                            .onSuccess {
                                snackbar.showSnackbar(io.acr.i18n.t2("merge.done"))
                                reload++
                            }
                            .onFailure {
                                snackbar.showSnackbar(
                                    io.acr.i18n.t2("merge.failed") + ": " + it.message?.take(160),
                                )
                            }
                        mergeando = false
                    }
                },
            )
        }

        if (tab == Tab.Codigo) {
            // Volver: el salto desde la conversación era de ida y la única forma de regresar era
            // la pestaña, que además te dejaba al principio de la lista.
            volverAlHilo?.let { origen ->
                TextButton(onClick = {
                    hiloDestacado = origen
                    volverAlHilo = null
                    tab = Tab.Conversacion
                }) { Text(io.acr.i18n.t("thread.backToConversation")) }
                Spacer(Modifier.height(4.dp))
            }
            io.acr.ui.code.CodePanel(
                ctx, repo, pr, prId, snackbar,
                focus = codeFocus,
                // Se limpia al aplicarlo: si no, volver a la pestaña saltaría de nuevo al mismo
                // lugar y perdería dónde estabas leyendo.
                onFocusConsumed = { codeFocus = null },
            )
            return@Column
        }

        if (tab == Tab.Commits) {
            io.acr.ui.code.CommitsPanel(repo, pr, ctx.prefs, reviewHeadSha = review?.headSha)
            return@Column
        }

        if (tab == Tab.Conversacion) {
            ConversationList(
                threads = hilos,
                busy = replyBusy,
                ourName = nuestroNombre,
                onDraft = { d ->
                    val target = pr
                    if (target == null) {
                        scope.launch { snackbar.showSnackbar("Necesito los datos del PR para analizar.") }
                    } else {
                        draft(d, target)
                    }
                },
                onDraftAll = {
                    val target = pr
                    if (target == null) {
                        scope.launch { snackbar.showSnackbar("Necesito los datos del PR para analizar.") }
                    } else {
                        replies.filter { it.status != io.acr.data.ReplyStatus.PUBLISHED && it.body.isNullOrBlank() }
                            .forEach { draft(it, target) }
                    }
                },
                onPublish = { d, body ->
                    if (d.id !in replyBusy) {
                        replyBusy = replyBusy + d.id
                        ctx.appScope.launch {
                            ctx.engine.publishReply(repo, prId, d, body)
                                .onSuccess { snackbar.showSnackbar("Respuesta publicada en el hilo.") }
                                .onFailure { e -> snackbar.showSnackbar("No pude publicar: ${e.message?.take(140)}") }
                            replyBusy = replyBusy - d.id
                            reload++
                        }
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
                onShowCode = { path, line, threadId ->
                    codeFocus = io.acr.ui.code.CodeFocus(path, line)
                    volverAlHilo = threadId
                    tab = Tab.Codigo
                },
                focusId = hiloDestacado,
                onFocusConsumed = { hiloDestacado = null },
                followUpAfterDays = diasParaRecordar,
                onFollowUp = { seguimientoDe = it },
                publishingIds = publicandoIds,
                onDismissReply = { d ->
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            ctx.replies.dismiss(d.id, true)
                        }
                        reload++
                    }
                },
                onDismissAllReplies = {
                    scope.launch {
                        val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            ctx.replies.dismissAllForPr(repo.id, prId)
                        }
                        reload++
                        snackbar.showSnackbar(io.acr.i18n.t2("replies.dismissedN", n))
                    }
                },
                onCloseThread = { id, cerrar ->
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            ctx.findings.close(id, cerrar)
                            findings.firstOrNull { it.id == id }?.let {
                                ctx.reviews.markPublishedIfComplete(it.reviewId, null)
                            }
                        }
                        reload++
                    }
                },
                onPublishFinding = { id ->
                    val head = pr?.headSha
                    val f = findings.firstOrNull { it.id == id }
                    if (head == null || f == null) {
                        scope.launch { snackbar.showSnackbar("Necesito los datos del PR para publicar.") }
                    } else if (id !in publicandoIds) {
                        publicandoIds = publicandoIds + id
                        // appScope: publicar sobrevive a cambiar de pestaña o de PR.
                        ctx.appScope.launch {
                            ctx.engine.publishFinding(repo, prId, f, head)
                                .onSuccess { snackbar.showSnackbar("Comentario publicado.") }
                                .onFailure { e ->
                                    snackbar.showSnackbar("No pude publicar: ${e.message?.take(140)}")
                                }
                            publicandoIds = publicandoIds - id
                            reload++
                        }
                    }
                },
                onDismissFinding = { id ->
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            ctx.findings.dismiss(id)
                            findings.firstOrNull { it.id == id }?.let {
                                ctx.reviews.markPublishedIfComplete(it.reviewId, null)
                            }
                        }
                        reload++
                    }
                },
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
            // Correr de verdad. Se define acá para que el botón y la confirmación disparen
            // exactamente lo mismo: si fueran dos copias, la de la confirmación quedaría vieja.
            fun lanzar(completa: Boolean = false) {
                val target = pr ?: return
                // appScope y no el de la pantalla: la review tiene que sobrevivir a que el
                // usuario navegue a otro PR mientras corre.
                ctx.appScope.launch {
                    when (val out = ctx.engine.review(repo, target, depth, kind, repo.defaultModel, forceFull = completa)) {
                        is ReviewOutcome.Ok -> {
                            review = out.record
                            draft = out.record.body.orEmpty()
                        }
                        is ReviewOutcome.Error -> {
                            review = ctx.reviews.latestUsableFor(repo.id, prId)
                            snackbar.showSnackbar(out.message)
                        }
                    }
                    reload++
                }
            }

            Button(
                enabled = pr != null && !running,
                onClick = {
                    val target = pr ?: return@Button
                    // Si este commit ya se revisó y terminó bien, preguntar antes de gastar otra
                    // corrida. La consulta ignora las que fallaron: ahí repetir es lo correcto.
                    val previa = ctx.reviews.doneForHead(repo.id, prId, target.headSha)
                    if (previa != null) repetir = previa else lanzar()
                },
            ) { Text(if (review == null) io.acr.i18n.t("review.run") else io.acr.i18n.t("review.rerun")) }

            // La escotilla. Una review incremental se apoya en que la anterior miró bien lo suyo;
            // si hay motivo para dudar de eso —o si el cambio nuevo toca algo transversal— tiene
            // que haber forma de pedir la rama entera sin tener que borrar nada.
            if (pr != null && !running && ctx.reviews.latestDoneFor(repo.id, prId) != null) {
                TextButton(onClick = { lanzar(completa = true) }) {
                    Text(io.acr.i18n.t("review.runFull"))
                }
            }

            repetir?.let { previa ->
                RerunDialog(
                    previa = previa,
                    onDismiss = { repetir = null },
                    onConfirm = { repetir = null; lanzar() },
                )
            }

            if (running) {
                OutlinedButton(onClick = { ctx.engine.cancel(prId) }) { Text(io.acr.i18n.t("common.cancel")) }
                CircularProgressIndicator(Modifier.height(20.dp))
            }

            Spacer(Modifier.weight(1f))
            // Que la corrida haya mirado sólo una parte no puede quedar implícito: leer un
            // resultado sin saber su alcance lleva a creer que se revisó algo que nadie miró.
            review?.sinceSha?.let { desde ->
                Text(
                    io.acr.i18n.t("review.incrementalFrom", desde.take(7)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
            }
            review?.costUsd?.let {
                Text(
                    "US$ ${"%.4f".format(it)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Mientras corre se muestra una tarjeta compacta arriba y, debajo, la review anterior si
        // la hay: antes el feed de log ocupaba la pantalla entera y tapaba lo único que en ese
        // momento se podía leer.
        if (running) {
            RunningCard(progress!!)
            Spacer(Modifier.height(12.dp))
        }
        run {
            if (findings.isNotEmpty() || localNotes.isNotEmpty()) {
                FindingsSummary(
                    findings = findings,
                    notes = localNotes,
                    publishing = publishing,
                    onDismiss = { f ->
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                ctx.findings.dismiss(f.id)
                                // Puede haber sido el último pendiente: si lo era, la review pasa
                                // a resuelta y el PR deja de figurar en el panel.
                                ctx.reviews.markPublishedIfComplete(f.reviewId, null)
                            }
                            reload++
                        }
                    },
                    onShowCode = { path, line ->
                        codeFocus = io.acr.ui.code.CodeFocus(path, line)
                        tab = Tab.Codigo
                    },
                    onRestore = { f ->
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                ctx.findings.restore(f.id)
                            }
                            reload++
                        }
                    },
                    onPublishAll = {
                        val head = pr?.headSha ?: return@FindingsSummary
                        publishing = true
                        scope.launch {
                            var ok = 0
                            var failed = 0
                            try {
                                findings.filter { !it.settled }.forEach { f ->
                                    ctx.engine.publishFinding(repo, prId, f, head)
                                        .onSuccess { ok++ }
                                        .onFailure { failed++ }
                                }
                                // Las notas propias van en la misma tanda: para el PR son
                                // comentarios inline iguales, y separarlas obligaba a ir a otra
                                // pestaña a terminar de publicar lo mismo.
                                localNotes.filter { it.publishedId == null }.forEach { n ->
                                    ctx.engine.publishNote(repo, prId, n, head, ctx.notes)
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

    val eventos = remember(reviews, publications, thread, localNotes) {
        buildTimeline(reviews, publications, thread, localNotes)
    }
    // Autores ordenados por cantidad: el que más comentó primero, que es a quien más se busca.
    val autores = remember(eventos) {
        eventos.filter { it.author.isNotBlank() }
            .groupingBy { it.author }.eachCount().entries.sortedByDescending { it.value }
    }
    val visibles = remember(eventos, author) {
        author?.let { a -> eventos.filter { it.author == a } } ?: eventos
    }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    io.acr.i18n.t("hist.events", visibles.size),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (autores.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        author == null,
                        { author = null },
                        { Text(io.acr.i18n.t("hist.everyone")) },
                    )
                    autores.forEach { (a, n) ->
                        FilterChip(author == a, { author = a }, { Text("$a ($n)") })
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        if (visibles.isEmpty()) {
            item { Empty(io.acr.i18n.t("hist.none")) }
        }

        // Agrupado por día: es como uno recuerda lo que pasó en un PR.
        visibles.groupBy { it.day() }.forEach { (dia, delDia) ->
            item(key = "d-$dia") {
                Spacer(Modifier.height(8.dp))
                Text(
                    dia,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(delDia, key = { "${it.at}-${it.kind}-${it.body.take(24)}" }) { e ->
                TimelineRow(e, onOpen)
            }
        }
    }
}

/** Una fila del historial: cuándo, de qué tipo, de quién y qué dice. */
@Composable
private fun TimelineRow(e: TimelineEvent, onOpen: (String) -> Unit) {
    // El mismo criterio que las burbujas: nuestro es el color del tema, ajeno el violeta. El ámbar
    // quedó reservado para la gravedad, que es otra cosa.
    val color = when (e.kind) {
        EventKind.COMMENT_OURS -> io.acr.ui.authorColor(ours = true)
        EventKind.COMMENT_THEIRS -> io.acr.ui.authorColor(ours = false)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                io.acr.i18n.t(e.kind.labelKey),
                style = MaterialTheme.typography.labelSmall,
                color = color,
                modifier = Modifier.width(110.dp),
            )
            Text(
                e.at.take(16).replace('T', ' '),
                style = MaterialTheme.typography.labelSmall
                    .copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (e.author.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                io.acr.ui.PersonAvatar(e.author, size = 36.dp)
                Spacer(Modifier.width(4.dp))
                Text(e.author, style = MaterialTheme.typography.labelSmall)
            }
            e.anchor?.let {
                Spacer(Modifier.width(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall
                        .copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            e.url?.takeIf { it.isNotBlank() }?.let { url ->
                TextButton(onClick = { onOpen(url) }) {
                    Text(
                        io.acr.i18n.t("common.inBrowser"),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        if (e.title.isNotBlank()) {
            Text(e.title, style = MaterialTheme.typography.labelSmall, color = color)
        }
        if (e.body.isNotBlank()) {
            // Los comentarios van como burbuja con la identidad de quien los escribió; una review
            // o una nota local no son de nadie en ese sentido y se leen como texto.
            val esComentario = e.kind == EventKind.COMMENT_OURS || e.kind == EventKind.COMMENT_THEIRS
            if (esComentario) {
                io.acr.ui.MessageBubble(
                    author = e.author,
                    body = e.body,
                    ours = e.kind == EventKind.COMMENT_OURS,
                )
            } else {
                SelectionContainer {
                    Text(e.body.take(600), style = MaterialTheme.typography.bodySmall)
                }
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

/**
 * Estado de la corrida, en una tarjeta y no en un muro de log.
 *
 * Lo que importa mientras espera es una línea: qué está haciendo ahora y hace cuánto. El detalle
 * completo sigue estando, plegado, para cuando algo se atasca y hay que mirar.
 */
@Composable
private fun RunningCard(progress: io.acr.claude.RunProgress) {
    var expandido by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(progress.prId) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            tick++
        }
    }
    // `tick` va como clave del remember sólo para que esto se recalcule cada segundo: el valor
    // sale del reloj, no del contador.
    val segundos = remember(tick) {
        ((System.currentTimeMillis() - progress.startedAt) / 1000).coerceAtLeast(0)
    }
    val transcurrido = if (segundos < 60) "${segundos}s" else "${segundos / 60}m ${segundos % 60}s"

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.CircularProgressIndicator(
                Modifier.height(16.dp).width(16.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(io.acr.i18n.t("review.running"), style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(
                        progress.depth.takeIf { it.isNotBlank() },
                        progress.model.takeIf { it.isNotBlank() },
                        transcurrido,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { expandido = !expandido }) {
                Text(
                    if (expandido) io.acr.i18n.t("review.hideDetail")
                    else io.acr.i18n.t("review.showDetail", progress.lines.size),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        androidx.compose.material3.LinearProgressIndicator(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.height(8.dp))
        // El paso actual, en texto normal y una sola línea: es lo único que se mira de reojo.
        Text(
            progress.lines.lastOrNull().orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        if (expandido) {
            Spacer(Modifier.height(8.dp))
            val listState = rememberLazyListState()
            LaunchedEffect(progress.lines.size) {
                if (progress.lines.isNotEmpty()) listState.animateScrollToItem(progress.lines.lastIndex)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(240.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(10.dp),
            ) {
                items(progress.lines) { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
            // Vacía y no nula: una review publicada hallazgo por hallazgo puede quedar sin link
            // si el proveedor devolvió el comentario sin URL. Sigue estando publicada, pero no
            // hay adónde llevar, así que no se ofrece el botón.
            review.publishedUrl?.takeIf { it.isNotBlank() }?.let { url ->
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
    notes: List<io.acr.data.LocalNote>,
    publishing: Boolean,
    onPublishAll: () -> Unit,
    onDismiss: (io.acr.data.Finding) -> Unit,
    onRestore: (io.acr.data.Finding) -> Unit,
    onShowCode: (String, Int?) -> Unit,
) {
    // Las notas propias cuentan igual que los hallazgos: son comentarios anclados a archivo y
    // línea que también hay que publicar. Antes vivían sólo en la vista de código, así que desde
    // acá no había forma de saber que quedaban pendientes.
    val pending = findings.count { !it.settled } + notes.count { it.publishedId == null }
    val fallidos = findings.filter { it.publishError != null && !it.settled }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${findings.size + notes.size} " + io.acr.i18n.t("review.findingsAnchored") +
                    if (notes.isEmpty()) "" else " · " + io.acr.i18n.t("review.ownNotes", notes.size),
                style = MaterialTheme.typography.titleSmall,
            )
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
        if (fallidos.isNotEmpty()) {
            Text(
                io.acr.i18n.t("review.publishFailed", fallidos.size) + ": " +
                    fallidos.first().publishError.orEmpty().take(160),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        findings.take(12).forEach { f ->
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
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
                // Qué clase de problema es, al lado de cuán urgente: son ejes distintos y el
                // desarrollador necesita los dos para saber si lo arregla ahora o lo conversa.
                f.category?.let { c ->
                    Text(
                        io.acr.i18n.t(c.labelKey),
                        style = MaterialTheme.typography.labelSmall,
                        color = when (c) {
                            io.acr.data.FindingCategory.FUNCTIONAL -> MaterialTheme.colorScheme.error
                            io.acr.data.FindingCategory.BUG -> io.acr.ui.stats.ChartColors.major
                            io.acr.data.FindingCategory.DESIGN -> io.acr.ui.stats.ChartColors.neutral
                            io.acr.data.FindingCategory.CONVENTION -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.width(78.dp),
                    )
                }
                // Link al código, como en la conversación: leer un hallazgo sin poder ver la
                // línea que señala obliga a buscarla a mano en la otra pestaña.
                Text(
                    f.filePath.substringAfterLast('/') + (f.lineNo?.let { ":$it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(220.dp).clickableText { onShowCode(f.filePath, f.lineNo) },
                    maxLines = 1,
                )
                Text(
                    f.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                when {
                    // Publicado: lo que importa ya no es que se mandó sino si lo arreglaron.
                    // Corregido es terminal y va con recuadro verde; lo demás sigue esperando.
                    f.publishedId != null -> VerificationMark(f.resolution)
                    f.dismissedAt != null -> TextButton(onClick = { onRestore(f) }) {
                        Text(io.acr.i18n.t("review.dismissed"), style = MaterialTheme.typography.labelSmall)
                    }
                    else -> TextButton(onClick = { onDismiss(f) }) {
                        Text(io.acr.i18n.t("review.dismiss"), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            // La evidencia es lo que permite discutir el veredicto en vez de creerlo.
            f.resolutionNote?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 36.dp, bottom = 4.dp),
                )
            }
        }
        notes.take(12).forEach { n ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(
                    "✎",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(36.dp),
                )
                Text(
                    n.filePath.substringAfterLast('/') + (n.lineNo?.let { ":$it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.width(220.dp),
                    maxLines = 1,
                )
                Text(
                    n.body.lineSequence().firstOrNull().orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
                if (n.publishedId != null) {
                    Text(
                        "  ✓",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * La conversación del PR: cada pregunta con su historia y su estado.
 *
 * Ordenada por lo que espera algo tuyo: arriba lo que hay que contestar, abajo lo cerrado. Es la
 * vista con la que se decide si el PR está listo, así que muestra las cuatro cosas juntas —qué
 * preguntamos, qué contestaron, qué se preparó para responder, y si el código quedó arreglado—
 * en vez de repartirlas en pestañas.
 */
@Composable
private fun ConversationList(
    threads: List<ConversationThread>,
    busy: Set<String>,
    onDraft: (io.acr.data.ReplyDraft) -> Unit,
    onDraftAll: () -> Unit,
    onPublish: (io.acr.data.ReplyDraft, String) -> Unit,
    onEdit: (io.acr.data.ReplyDraft, String) -> Unit,
    onOpen: (String) -> Unit,
    onShowCode: (String, Int?, String?) -> Unit,
    onPublishFinding: (String) -> Unit,
    onDismissFinding: (String) -> Unit,
    /** Qué comentarios se están publicando ahora mismo, por id. */
    publishingIds: Set<String>,
    /** Hilo al que hay que llevar la lista al volver del código. */
    focusId: String?,
    onFocusConsumed: () -> Unit,
    followUpAfterDays: Long,
    onFollowUp: (ConversationThread) -> Unit,
    /** Con qué nombre aparecemos en el proveedor, para que el avatar diga quién preguntó. */
    ourName: String?,
    onCloseThread: (String, Boolean) -> Unit,
    onDismissReply: (io.acr.data.ReplyDraft) -> Unit,
    onDismissAllReplies: () -> Unit,
) {
    if (threads.isEmpty()) {
        Box(Modifier.fillMaxSize()) {
            Text(
                io.acr.i18n.t("thread.none"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        return
    }
    val abiertos = threads.count { it.open }
    val porContestar = threads.count { it.state == ThreadState.NEEDS_ANSWER }
    val listState = rememberLazyListState()
    // Al volver del código, la lista baja hasta la tarjeta de donde se salió. Sin esto uno vuelve
    // al principio y tiene que buscar de nuevo dónde estaba.
    LaunchedEffect(focusId, threads) {
        val id = focusId ?: return@LaunchedEffect
        val i = threads.indexOfFirst { it.findingId == id }
        if (i >= 0) listState.scrollToItem(i + 1) // +1: el encabezado es el primer item
        onFocusConsumed()
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (abiertos == 0) io.acr.i18n.t("thread.allSettled")
                    else io.acr.i18n.t("thread.openCount", abiertos, threads.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (abiertos == 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                if (porContestar > 1) {
                    Spacer(Modifier.width(10.dp))
                    Button(onClick = onDraftAll) {
                        Text(io.acr.i18n.t("replies.analyzeAll", porContestar))
                    }
                    Spacer(Modifier.width(8.dp))
                    // Con 24 respuestas del tipo "corregido", cerrarlas una por una no es opción.
                    OutlinedButton(onClick = onDismissAllReplies) {
                        Text(io.acr.i18n.t("replies.dismissAll", porContestar))
                    }
                }
            }
        }
        items(threads, key = { it.findingId ?: it.title }) { h ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // El ancla es un link: leer la discusión sin ver el código al lado obliga a
                    // buscarlo a mano en la otra pestaña.
                    val ancla = (h.filePath?.substringAfterLast('/') ?: "") +
                        (h.lineNo?.let { ":$it" } ?: "")
                    Text(
                        ancla,
                        style = MaterialTheme.typography.labelSmall
                            .copy(fontFamily = FontFamily.Monospace),
                        color = if (h.filePath != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = if (h.filePath == null) Modifier
                        else Modifier.clickableText { onShowCode(h.filePath, h.lineNo, h.findingId) },
                    )
                    h.filePath?.let {
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = { onShowCode(it, h.lineNo, h.findingId) }) {
                            Text(
                                io.acr.i18n.t("thread.showCode"),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // Resuelto es el único estado terminal del hilo: recuadro verde. Los otros
                    // cinco esperan algo y se leen como texto.
                    if (h.state == ThreadState.OK) {
                        io.acr.ui.StatusBadge(io.acr.i18n.t(h.state.labelKey))
                    } else {
                        Text(
                            io.acr.i18n.t(h.state.labelKey),
                            style = MaterialTheme.typography.labelSmall,
                            color = h.state.color(),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    io.acr.ui.SeverityBadge(h.severity)
                    Spacer(Modifier.width(8.dp))
                    Text(h.title, style = MaterialTheme.typography.titleSmall)
                }

                Spacer(Modifier.height(6.dp))
                io.acr.ui.MessageBubble(
                    author = io.acr.i18n.t("thread.ourQuestion"),
                    body = h.question,
                    ours = true,
                    // El rótulo dice qué es el mensaje; el avatar, quién lo escribió. Sin esto las
                    // iniciales salían del propio rótulo y mostraban "LP", que no es nadie.
                    avatarName = ourName,
                )
                io.acr.ui.SuggestionBlock(h.suggestion)

                // El ida y vuelta, en orden. Es "por dónde arranqué y qué me contestaron".
                h.entries.forEach { e ->
                    io.acr.ui.MessageBubble(
                        author = e.author,
                        body = e.body,
                        ours = e.ours,
                        at = e.at,
                    )
                }

                // El veredicto sobre el código, con su evidencia.
                h.resolutionNote?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        io.acr.i18n.t("verify.title") + ": " + it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Y lo que hay para hacer, acá mismo.
                // Hace cuánto esperamos, y la opción de insistir. Sólo aparece cuando la pelota
                // está del otro lado y pasó el plazo: recordar algo de ayer sería molestar.
                h.waitingDays?.let { dias ->
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (h.followedUpAt != null) io.acr.i18n.t("followup.sent", dias)
                            else io.acr.i18n.t("followup.waiting", dias),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (h.needsFollowUp(followUpAfterDays)) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (h.needsFollowUp(followUpAfterDays)) {
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { onFollowUp(h) }) {
                                Text(
                                    io.acr.i18n.t("followup.action"),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }

                // Una pregunta que todavía no se hizo: se puede publicar o descartar desde acá,
                // que es donde uno se da cuenta de que faltaba.
                if (h.state == ThreadState.UNPUBLISHED && h.findingId != null) {
                    Spacer(Modifier.height(8.dp))
                    val publicandoEste = h.findingId in publishingIds
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            enabled = !publicandoEste,
                            onClick = { onPublishFinding(h.findingId) },
                        ) {
                            Text(
                                if (publicandoEste) io.acr.i18n.t("review.publishing")
                                else io.acr.i18n.t("review.publishInline"),
                            )
                        }
                        OutlinedButton(
                            enabled = !publicandoEste,
                            onClick = { onDismissFinding(h.findingId) },
                        ) { Text(io.acr.i18n.t("review.dismiss")) }
                        // Se ve cuál está trabajando, en vez de deducirlo por un botón apagado.
                        if (publicandoEste) CircularProgressIndicator(Modifier.height(18.dp))
                    }
                }

                // Cerrar sin esperar cambios: para el comentario que sólo validaba una respuesta
                // o que ya quedó saldado hablando. Sin esto quedaba esperando una corrección que
                // nunca iba a llegar, y el PR no podía darse por listo.
                if (h.open && h.state != ThreadState.UNPUBLISHED && h.findingId != null) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { onCloseThread(h.findingId, true) }) {
                        Text(io.acr.i18n.t("thread.close"), style = MaterialTheme.typography.labelSmall)
                    }
                } else if (h.findingId != null && h.state == ThreadState.OK) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { onCloseThread(h.findingId, false) }) {
                        Text(io.acr.i18n.t("thread.reopen"), style = MaterialTheme.typography.labelSmall)
                    }
                }

                val d = h.draft
                if (d != null && d.status != io.acr.data.ReplyStatus.PUBLISHED) {
                    Spacer(Modifier.height(8.dp))
                    if (d.body.isNullOrBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = d.id !in busy, onClick = { onDraft(d) }) {
                                Text(
                                    if (d.id in busy) io.acr.i18n.t("replies.analyzing")
                                    else io.acr.i18n.t("replies.analyze"),
                                )
                            }
                            OutlinedButton(onClick = { onDismissReply(d) }) {
                                Text(io.acr.i18n.t("replies.dismiss"))
                            }
                        }
                    } else {
                        var texto by remember(d.id, d.body) { mutableStateOf(d.body.orEmpty()) }
                        OutlinedTextField(
                            value = texto,
                            onValueChange = { texto = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 220.dp),
                            label = { Text(io.acr.i18n.t("replies.draftLabel")) },
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = d.id !in busy && texto.isNotBlank(),
                                onClick = { onPublish(d, texto) },
                            ) { Text(io.acr.i18n.t("replies.publishReply")) }
                            OutlinedButton(onClick = { onDismissReply(d) }) {
                                Text(io.acr.i18n.t("replies.dismiss"))
                            }
                            OutlinedButton(onClick = { onEdit(d, texto) }) {
                                Text(io.acr.i18n.t("common.saveDraft"))
                            }
                            OutlinedButton(enabled = d.id !in busy, onClick = { onDraft(d) }) {
                                Text(io.acr.i18n.t("replies.redraft"))
                            }
                        }
                    }
                } else if (d?.publishedUrl?.isNotBlank() == true) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { onOpen(d.publishedUrl!!) }) {
                        Text(io.acr.i18n.t("common.openBrowser"))
                    }
                }
            }
        }
    }
}

@Composable
private fun RepliesList(
    replies: List<io.acr.data.ReplyDraft>,
    busy: Set<String>,
    onDraftAll: () -> Unit,
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
    val pending = replies.filter {
        it.status != io.acr.data.ReplyStatus.PUBLISHED && it.body.isNullOrBlank()
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Con varias respuestas sin analizar, hacerlo de a una es trabajo manual innecesario:
        // se lanzan todas y el motor las encola.
        if (pending.size > 1 || busy.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = pending.any { it.id !in busy },
                        onClick = onDraftAll,
                    ) { Text(io.acr.i18n.t("replies.analyzeAll", pending.count { it.id !in busy })) }
                    if (busy.isNotEmpty()) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            io.acr.i18n.t("replies.inFlight", busy.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
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
                    Button(enabled = d.id !in busy, onClick = { onDraft(d) }) {
                        Text(if (d.id in busy) io.acr.i18n.t("replies.analyzing") else io.acr.i18n.t("replies.analyze"))
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
                            enabled = d.id !in busy && draftText.isNotBlank(),
                            onClick = { onPublish(d, draftText) },
                        ) { Text(if (d.id in busy) io.acr.i18n.t("replies.analyzing") else io.acr.i18n.t("replies.publishReply")) }
                        OutlinedButton(onClick = { onEdit(d, draftText) }) { Text(io.acr.i18n.t("common.saveDraft")) }
                        OutlinedButton(enabled = d.id !in busy, onClick = { onDraft(d) }) {
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

/**
 * Por qué NO se puede mergear todavía, o null si se puede.
 *
 * Mergear es la única acción de la app que cambia el repositorio y no tiene vuelta atrás, así que
 * las condiciones son explícitas y se muestran: no alcanza con deshabilitar un botón sin decir por
 * qué. Se exige que no quede nada esperando —hallazgos sin resolver, notas sin publicar, respuestas
 * sin contestar— y, además, que el autor haya subido código DESPUÉS de la review: si el head del PR
 * sigue siendo el que se revisó, nadie corrigió nada y mergear sería aprobar sin verificar.
 */
internal fun mergeBlocker(
    pr: PullRequest?,
    review: ReviewRecord?,
    findings: List<io.acr.data.Finding>,
    notes: List<io.acr.data.LocalNote>,
    replies: List<io.acr.data.ReplyDraft>,
): String? = mergeBlocker(
    prHeadSha = pr?.headSha,
    reviewHeadSha = review?.headSha,
    hayReview = review != null,
    hallazgosPendientes = findings.count { !it.settled },
    notasPendientes = notes.count { it.publishedId == null },
    respuestasPendientes = replies.count { !it.settled },
    // `settled` cubre los tres cierres —publicado, descartado y cerrado en la conversación—; acá
    // hay que excluir explícitamente los dos últimos, porque un hilo cerrado hablando no espera
    // ninguna verificación contra el código.
    comentariosPublicados = findings.count {
        it.publishedId != null && it.dismissedAt == null && it.closedAt == null
    },
    sinVerificar = findings.count {
        it.publishedId != null && it.dismissedAt == null && it.closedAt == null &&
            it.resolution == null
    },
    noResueltos = findings.count {
        it.publishedId != null && it.dismissedAt == null && it.closedAt == null &&
            it.resolution != null && it.resolution != io.acr.data.Resolution.RESOLVED
    },
)

/**
 * La misma regla, sobre contadores.
 *
 * La lista de PRs no carga los hallazgos de cada fila —sería una consulta por fila— pero sí tiene
 * los conteos. Es la misma función a propósito: dos criterios distintos para habilitar un merge
 * terminarían divergiendo, y el que se relaje de más no se puede deshacer.
 */
internal fun mergeBlocker(
    prHeadSha: String?,
    reviewHeadSha: String?,
    hayReview: Boolean,
    hallazgosPendientes: Int,
    notasPendientes: Int,
    respuestasPendientes: Int,
    sinVerificar: Int = 0,
    noResueltos: Int = 0,
    /** Cuántos comentarios llegamos a publicar. Cero significa que no pedimos ningún cambio. */
    comentariosPublicados: Int = 0,
): String? = when {
    prHeadSha == null -> "merge.noPr"
    !hayReview -> "merge.noReview"
    hallazgosPendientes > 0 -> "merge.pendingFindings"
    notasPendientes > 0 -> "merge.pendingNotes"
    respuestasPendientes > 0 -> "merge.pendingReplies"
    // Exigir commits nuevos sólo tiene sentido si pedimos algún cambio. Si la review no publicó
    // nada —no encontró qué decir— no hay nada que corregir, y pedir un commit dejaría ese PR
    // sin poder mergearse nunca. Es el caso de talos-apirest #1517, con cero hallazgos.
    comentariosPublicados > 0 &&
        !reviewHeadSha.isNullOrBlank() && reviewHeadSha == prHeadSha -> "merge.noNewCommits"
    // Publicado no es resuelto. Sin verificar contra el código, mergear es confiar en que
    // alguien lo arregló porque lo dijo.
    noResueltos > 0 -> "merge.notResolved"
    sinVerificar > 0 -> "merge.notVerified"
    else -> null
}

/**
 * Cuán listo está el PR, en un número que se puede desarmar.
 *
 * El porcentaje solo sería decorativo: lo que sirve es ver de qué está compuesto. Debajo va lo que
 * falta, ordenado por cuánto pesa, así se sabe qué mover para que suba.
 */
@Composable
private fun ReadinessCard(r: Readiness, prefs: io.acr.data.PrefsRepo) {
    val color = when {
        r.percent == 100 -> io.acr.ui.VERDE_OK
        r.percent >= 70 -> androidx.compose.ui.graphics.Color(0xFFD98324)
        else -> MaterialTheme.colorScheme.error
    }
    // El porcentaje va en el título: plegada, la tarjeta sigue diciendo lo único que se mira de
    // reojo —"85% · todavía no está listo"— y lo que se esconde es el detalle de qué falta.
    io.acr.ui.CollapsibleCard(
        title = "${r.percent}% · " +
            (if (r.ready) io.acr.i18n.t("ready.yes") else io.acr.i18n.t("ready.no")),
        prefs = prefs,
        key = "readiness",
        accent = color,
        maxHeight = null,
    ) {
        androidx.compose.material3.LinearProgressIndicator(
            progress = { r.percent / 100f },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
        if (r.missing.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 140.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                r.missing.forEach { item ->
                    Text(
                        "· " + io.acr.i18n.t(item.key) +
                            if (item.detail.isBlank()) "" else " (${item.detail})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Si el hallazgo se verificó contra el código, y con qué resultado.
 *
 * Cada estado tiene su ícono además del color. "Sin verificar" llevaba un ✓ —el mismo símbolo que
 * "corregido"— así que se leía como hecho cuando en realidad nadie lo había mirado todavía.
 */
@Composable
private fun VerificationMark(resolution: io.acr.data.Resolution?) {
    val icono: androidx.compose.ui.graphics.vector.ImageVector
    val color: androidx.compose.ui.graphics.Color
    val clave: String
    when (resolution) {
        io.acr.data.Resolution.RESOLVED -> {
            icono = Icons.Default.CheckCircle
            color = io.acr.ui.VERDE_OK
            clave = "verify.RESOLVED"
        }
        io.acr.data.Resolution.PARTIAL -> {
            icono = Icons.Default.Warning
            color = androidx.compose.ui.graphics.Color(0xFFD98324)
            clave = "verify.PARTIAL"
        }
        io.acr.data.Resolution.UNRESOLVED -> {
            icono = Icons.Default.Close
            color = MaterialTheme.colorScheme.error
            clave = "verify.UNRESOLVED"
        }
        // Sin veredicto: nadie lo miró. El ícono es neutro a propósito, ni éxito ni fracaso.
        null -> {
            icono = Icons.Default.Info
            color = MaterialTheme.colorScheme.onSurfaceVariant
            clave = "verify.pending"
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        androidx.compose.material3.Icon(
            icono,
            contentDescription = null,
            tint = color,
            modifier = Modifier.height(13.dp).width(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(io.acr.i18n.t(clave), style = MaterialTheme.typography.labelSmall, color = color)
    }
}
