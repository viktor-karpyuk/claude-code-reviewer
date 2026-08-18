package io.acr.ui.code

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.unit.sp
import io.acr.AppContext
import io.acr.claude.DiffLine
import io.acr.claude.DiffParser
import io.acr.claude.Git
import io.acr.data.LocalNote
import io.acr.forge.PullRequest
import io.acr.forge.RepoRecord
import io.acr.ui.clickableText
import io.acr.ui.color
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File
import java.net.URI

private fun openInBrowser(url: String) {
    runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url)) }
}

/** Colores del diff. Se mantienen legibles sobre fondo claro y oscuro. */
// Con 0x33 sobre el fondo oscuro anterior las líneas agregadas y borradas quedaban barrosas y
// casi del mismo tono. Más opacidad y tonos más separados: el verde y el rojo tienen que
// distinguirse de un vistazo, que es para lo que están.
internal val ADDED_BG = Color(0x4022C083)
internal val REMOVED_BG = Color(0x40E05B52)
internal val HUNK_BG = Color(0x3380B4FF)

/** A dónde saltar en el visor de código: el archivo y, si la hay, la línea. */
data class CodeFocus(val filePath: String, val lineNo: Int?)

@Composable
fun CodePanel(
    ctx: AppContext,
    repo: RepoRecord,
    pr: PullRequest?,
    prId: Long,
    snackbar: SnackbarHostState,
    /** Pedido de salto desde otra pantalla —la conversación— o null si se entró de frente. */
    focus: CodeFocus? = null,
    onFocusConsumed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val workDir = remember(repo.localPath) { File(repo.localPath) }
    val range = pr?.let { "origin/${it.targetBranch}...origin/${it.sourceBranch}" }

    var files by remember(repo.id, prId) { mutableStateOf<List<Git.FileChange>>(emptyList()) }
    var selected by remember(repo.id, prId) { mutableStateOf<String?>(null) }
    var lines by remember(repo.id, prId) { mutableStateOf<List<DiffLine>>(emptyList()) }
    var loading by remember(repo.id, prId) { mutableStateOf(true) }
    var notesVersion by remember(repo.id, prId) { mutableStateOf(0) }
    var composing by remember(repo.id, prId) { mutableStateOf<Pair<String, Int?>?>(null) }
    var editing by remember(repo.id, prId) { mutableStateOf<LocalNote?>(null) }
    // Sin esto, un doble click lanzaba dos publicaciones: ambas leían publishedId == null del
    // mismo closure y el POST no es idempotente, así que quedaban dos comentarios iguales.
    val publishingIds = remember(repo.id, prId) { mutableStateListOf<String>() }
    // Recorrido de hallazgos: en qué punto va, y a qué línea hay que bajar cuando el archivo
    // termine de cargar (el diff se lee en otro hilo, así que el scroll no puede ser inmediato).
    var cursor by remember(repo.id, prId) { mutableStateOf(-1) }
    var pendingLine by remember(repo.id, prId) { mutableStateOf<Int?>(null) }
    var highlight by remember(repo.id, prId) { mutableStateOf<Pair<String, Int>?>(null) }
    var sidebar by remember(repo.id, prId) { mutableStateOf(Sidebar.ARCHIVOS) }
    val diffState = rememberLazyListState()

    val notes = io.acr.ui.dbState(repo.id, prId, notesVersion, initial = emptyList()) {
        ctx.notes.forPr(repo.id, prId)
    }
    val findings = io.acr.ui.dbState(repo.id, prId, notesVersion, initial = emptyList()) {
        ctx.findings.forLatestReview(repo.id, prId)
    }

    // Por qué la lista está vacía, cuando lo está. Un panel en blanco no distingue "este PR no
    // cambió nada" de "no puedo ver la rama", y son dos cosas muy distintas: la primera no tiene
    // nada que hacer y la segunda tiene arreglo.
    var motivoVacio by remember(repo.id, prId) { mutableStateOf<String?>(null) }

    LaunchedEffect(repo.id, prId, range) {
        if (range == null) return@LaunchedEffect
        loading = true
        motivoVacio = null
        // Traer las ramas antes de mirar. Sin esto, un clon que nunca vio esta rama muestra cero
        // archivos y no dice nada: el diff pedía un ref que no existe y `numstat` devolvía vacío.
        val traida = pr?.let { Git.fetch(workDir, it.targetBranch, it.sourceBranch) }
        files = Git.numstat(workDir, range)
        if (files.isEmpty()) {
            val faltaRama = pr?.sourceBranch?.let { !Git.exists(workDir, "origin/$it") } ?: false
            motivoVacio = when {
                // El caso más común y el más confuso: la rama se borró al mergear el PR. El clon
                // puede tener todavía un ref viejo, o ninguno, y en los dos casos el panel quedaba
                // mudo.
                faltaRama && traida?.ok == false ->
                    io.acr.i18n.t2("code.branchGone", pr.sourceBranch)
                traida?.ok == false -> io.acr.i18n.t2("code.fetchFailed", traida.output.take(200))
                else -> io.acr.i18n.t2("code.noChanges")
            }
        }
        selected = files.firstOrNull()?.path
        loading = false
    }

    LaunchedEffect(selected, range) {
        val path = selected
        if (path == null || range == null) { lines = emptyList(); return@LaunchedEffect }
        lines = DiffParser.parse(Git.diffFile(workDir, range, path))
    }

    // Todo lo que hay para revisar, en el orden en que se lee: por archivo del diff y, dentro de
    // cada archivo, por línea. Los hallazgos de la review y las notas propias van juntos: para
    // recorrerlos uno por uno da igual quién los escribió.
    val anchors = remember(files, findings, notes) { buildAnchors(files, findings, notes) }

    fun irA(i: Int) {
        if (i !in anchors.indices) return
        cursor = i
        val a = anchors[i]
        if (selected != a.filePath) selected = a.filePath
        pendingLine = a.lineNo
        highlight = a.lineNo?.let { a.filePath to it }
        if (a.lineNo == null) {
            // Sin línea (hallazgo de archivo entero) no hay adónde bajar: alcanza con abrirlo.
            pendingLine = null
        }
    }

    // Salto pedido desde la conversación: abre el archivo, baja a la línea y la resalta. El
    // cursor del recorrido se mueve al hallazgo correspondiente, si hay uno, para que "Siguiente"
    // siga desde donde uno estaba mirando y no desde el principio.
    LaunchedEffect(focus, files) {
        val destino = focus ?: return@LaunchedEffect
        if (files.isEmpty()) return@LaunchedEffect
        selected = destino.filePath
        pendingLine = destino.lineNo
        highlight = destino.lineNo?.let { destino.filePath to it }
        val i = anchors.indexOfFirst {
            it.filePath == destino.filePath && it.lineNo == destino.lineNo
        }
        if (i >= 0) cursor = i
        onFocusConsumed()
    }

    // El scroll espera a que el archivo cargue: al cambiar de archivo, `lines` llega después.
    LaunchedEffect(lines, pendingLine) {
        val target = pendingLine ?: return@LaunchedEffect
        if (lines.isEmpty()) return@LaunchedEffect
        val idx = lines.indexOfFirst { it.anchorLine == target && it.kind != DiffLine.Kind.HUNK }
        if (idx >= 0) {
            // Unas líneas de contexto arriba: pegado al borde superior no se entiende qué se mira.
            diffState.scrollToItem(maxOf(0, idx - 4))
        }
        pendingLine = null
    }

    if (range == null) {
        Box(Modifier.fillMaxSize()) {
            Text(
                "No puedo mostrar el código: todavía no cargaron los datos del PR.\nFijate el error arriba y probá «Refrescar».",
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val anchoPanel = io.acr.ui.rememberPaneWidth(ctx.prefs, "code", 330.dp)
    Row(Modifier.fillMaxSize()) {
        // Lista de archivos
        Column(Modifier.width(anchoPanel.value).fillMaxHeight()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    sidebar == Sidebar.ARCHIVOS,
                    { sidebar = Sidebar.ARCHIVOS },
                    { Text(io.acr.i18n.t("code.tabFiles", files.size)) },
                )
                FilterChip(
                    sidebar == Sidebar.HALLAZGOS,
                    { sidebar = Sidebar.HALLAZGOS },
                    { Text(io.acr.i18n.t("code.tabFindings", anchors.size)) },
                )
            }
            Spacer(Modifier.height(6.dp))
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            // El motivo, donde iría la lista. Un panel en blanco se lee como "todavía cargando" y
            // uno se queda esperando algo que no va a llegar.
            motivoVacio?.takeIf { !loading && files.isEmpty() }?.let { motivo ->
                Text(
                    motivo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
            if (sidebar == Sidebar.ARCHIVOS) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(files, key = { it.path }) { f ->
                        FileRow(
                            f = f,
                            active = selected == f.path,
                            notes = notes.count { it.filePath == f.path },
                            findings = findings.count { it.filePath == f.path },
                            worst = findings.filter { it.filePath == f.path }
                                .minByOrNull { io.acr.ui.Severity.of(it.severity).ordinal }
                                ?.severity,
                        ) { selected = f.path }
                    }
                }
            } else if (anchors.isEmpty()) {
                Text(
                    io.acr.i18n.t("code.noFindings"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                // Agrupados por archivo: lo que el usuario quiere ver de un vistazo es en qué
                // archivo cae cada observación, no una lista plana de títulos.
                LazyColumn(Modifier.fillMaxSize()) {
                    anchors.groupBy { it.filePath }.forEach { (path, delArchivo) ->
                        item(key = "h:$path") {
                            Text(
                                path,
                                style = MaterialTheme.typography.labelSmall
                                    .copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                            )
                        }
                        items(delArchivo, key = { it.id }) { a ->
                            AnchorRow(
                                a = a,
                                active = cursor >= 0 && anchors.getOrNull(cursor)?.id == a.id,
                                onClick = { irA(anchors.indexOfFirst { it.id == a.id }) },
                            )
                        }
                    }
                }
            }
        }

        io.acr.ui.VerticalSplitter(anchoPanel, ctx.prefs, "code", min = 200.dp, max = 700.dp)

        // Diff del archivo elegido
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (anchors.isNotEmpty()) {
                // Recorrido secuencial: avanza de hallazgo en hallazgo y cambia de archivo solo
                // cuando se terminan los del actual.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        enabled = cursor > 0,
                        onClick = { irA(cursor - 1) },
                    ) { Text(io.acr.i18n.t("code.prev")) }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(
                        enabled = cursor < anchors.size - 1,
                        onClick = { irA(if (cursor < 0) 0 else cursor + 1) },
                    ) { Text(io.acr.i18n.t("code.next")) }
                    Spacer(Modifier.width(10.dp))
                    val actual = anchors.getOrNull(cursor)
                    Text(
                        if (actual == null) io.acr.i18n.t("code.walkStart", anchors.size)
                        else io.acr.i18n.t("code.walkAt", cursor + 1, anchors.size) +
                            " · " + actual.filePath.substringAfterLast('/') +
                            (actual.lineNo?.let { ":$it" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            selected?.let {
                Text(it, style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace))
                Text(
                    io.acr.i18n.t("code.clickLine"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
            }
            val hScroll = rememberScrollState()
            LazyColumn(Modifier.fillMaxSize(), state = diffState) {
                itemsIndexed(lines) { idx, line ->
                    DiffRow(
                        line = line,
                        hScroll = hScroll,
                        highlighted = highlight?.let { (f, l) ->
                            f == selected && line.anchorLine == l && line.kind != DiffLine.Kind.HUNK
                        } == true,
                        onClick = {
                            if (line.kind != DiffLine.Kind.META && line.kind != DiffLine.Kind.HUNK) {
                                composing = (selected ?: "") to line.anchorLine
                            }
                        },
                    )
                    // Hallazgos de la review anclados a esta línea.
                    findings.filter { it.filePath == selected && it.lineNo != null && it.lineNo == line.anchorLine }
                        .forEach { f ->
                            FindingCard(
                                finding = f,
                                // Cuál es el que el recorrido está señalando ahora. Con varios
                                // comentarios en el mismo archivo, resaltar sólo la línea no
                                // alcanza para saber de cuál se está hablando.
                                active = anchors.getOrNull(cursor)?.id == f.id,
                                posicion = anchors.indexOfFirst { it.id == f.id }.takeIf { it >= 0 },
                                total = anchors.size,
                                busy = publishingIds.contains(f.id),
                                onPublish = {
                                    if (publishingIds.contains(f.id)) return@FindingCard
                                    publishingIds.add(f.id)
                                    scope.launch {
                                        try {
                                            ctx.engine.publishFinding(repo, prId, f, pr.headSha)
                                                .onSuccess { notesVersion++; snackbar.showSnackbar("Hallazgo publicado inline.") }
                                                .onFailure { snackbar.showSnackbar("No pude publicar: ${it.message?.take(140)}") }
                                        } finally {
                                            publishingIds.remove(f.id)
                                        }
                                    }
                                },
                            )
                        }

                    // Notas ancladas a esta línea, debajo, como en Bitbucket.
                    notes.filter { it.filePath == selected && it.lineNo != null && it.lineNo == line.anchorLine }
                        .forEach { note ->
                            NoteCard(
                                note = note,
                                active = anchors.getOrNull(cursor)?.id == note.id,
                                onEdit = { editing = note },
                                onDelete = {
                                    scope.launch {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            ctx.notes.delete(note.id)
                                        }
                                        notesVersion++
                                    }
                                },
                                busy = publishingIds.contains(note.id),
                                onPublish = {
                                    if (publishingIds.contains(note.id)) return@NoteCard
                                    publishingIds.add(note.id)
                                    scope.launch {
                                        try {
                                            ctx.engine.publishNote(repo, prId, note, pr.headSha, ctx.notes)
                                                .onSuccess { notesVersion++; snackbar.showSnackbar("Nota publicada en el PR.") }
                                                .onFailure { snackbar.showSnackbar("No pude publicar: ${it.message?.take(140)}") }
                                        } finally {
                                            publishingIds.remove(note.id)
                                        }
                                    }
                                },
                            )
                        }
                }
            }
        }
    }

    composing?.let { (path, line) ->
        NoteDialog(
            title = "Nota en ${path.substringAfterLast('/')}${line?.let { ":$it" } ?: ""}",
            initial = "",
            onDismiss = { composing = null },
            onSave = { body ->
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        ctx.notes.add(repo.id, prId, path, line, body)
                    }
                    notesVersion++
                }
                composing = null
            },
        )
    }

    editing?.let { note ->
        NoteDialog(
            title = "Editar nota",
            initial = note.body,
            onDismiss = { editing = null },
            onSave = { body ->
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        ctx.notes.update(note.id, body)
                    }
                    notesVersion++
                }
                editing = null
            },
        )
    }
}

@Composable
private fun FileRow(
    f: Git.FileChange,
    active: Boolean,
    notes: Int,
    findings: Int,
    /** La gravedad más alta del archivo: el color del contador tiene que ser el del peor. */
    worst: String?,
    onClick: () -> Unit,
) {
    val bg = if (active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
    Column(
        Modifier.fillMaxWidth().background(bg).clickableText(onClick).padding(8.dp),
    ) {
        Text(
            f.path.substringAfterLast('/'),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
        Row {
            Text(
                f.path.substringBeforeLast('/', ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text("+${f.added}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF35A66F))
            Spacer(Modifier.width(6.dp))
            Text("−${f.deleted}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFCB5A50))
            if (findings > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "$findings ▲",
                    style = MaterialTheme.typography.labelSmall,
                    color = io.acr.ui.Severity.of(worst).color(),
                )
            }
            if (notes > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "$notes ✎",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
internal fun DiffRow(
    line: DiffLine,
    hScroll: androidx.compose.foundation.ScrollState,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    // El resaltado gana sobre el color del diff: al saltar a un hallazgo hay que ver dónde cayó
    // sin buscarlo con la vista, y el verde de "línea agregada" no alcanza para distinguirla.
    val bg = when {
        highlighted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        line.kind == DiffLine.Kind.ADDED -> ADDED_BG
        line.kind == DiffLine.Kind.REMOVED -> REMOVED_BG
        line.kind == DiffLine.Kind.HUNK -> HUNK_BG
        else -> Color.Transparent
    }
    if (line.kind == DiffLine.Kind.META) return

    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    Row(
        Modifier.fillMaxWidth().background(bg).clickableText(onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            line.oldNo?.toString().orEmpty(),
            style = mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp).padding(end = 4.dp),
            maxLines = 1,
        )
        Text(
            line.newNo?.toString().orEmpty(),
            style = mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp).padding(end = 8.dp),
            maxLines = 1,
        )
        Text(
            when (line.kind) {
                DiffLine.Kind.ADDED -> "+"
                DiffLine.Kind.REMOVED -> "−"
                else -> " "
            },
            style = mono,
            modifier = Modifier.width(14.dp),
        )
        Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
            Text(line.text, style = mono, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun FindingCard(
    finding: io.acr.data.Finding,
    busy: Boolean,
    active: Boolean = false,
    posicion: Int? = null,
    total: Int = 0,
    onPublish: () -> Unit,
) {
    val accent = io.acr.ui.Severity.of(finding.severity).color()
    Column(
        Modifier.fillMaxWidth().padding(start = 110.dp, top = 4.dp, bottom = 4.dp, end = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            // El que se está recorriendo lleva borde del color de su gravedad y fondo más marcado:
            // es lo que hace que "Siguiente" señale algo y no sólo mueva el scroll.
            .then(
                if (active) {
                    Modifier.border(2.dp, accent, RoundedCornerShape(6.dp))
                        .background(accent.copy(alpha = 0.10f))
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                },
            )
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            io.acr.ui.SeverityBadge(finding.severity)
            Spacer(Modifier.width(8.dp))
            Text(finding.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            // "3 de 7" en la tarjeta activa: dice dónde estás parado sin mirar la barra de arriba.
            if (active && posicion != null) {
                io.acr.ui.StatusBadge(
                    io.acr.i18n.t("code.walkAt", posicion + 1, total),
                    color = accent,
                )
            }
        }
        Text(
            finding.body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        io.acr.ui.SuggestionBlock(finding.suggestion)
        if (finding.publishedId == null) {
            TextButton(enabled = !busy, onClick = onPublish) {
                Text(if (busy) "Publicando…" else "Publicar inline")
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    io.acr.i18n.t("common.published"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                finding.publishedUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    TextButton(onClick = { openInBrowser(url) }) { Text("Ver en el navegador") }
                }
            }
        }
    }
}

@Composable
private fun NoteCard(
    note: LocalNote,
    busy: Boolean,
    active: Boolean = false,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPublish: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth().padding(start = 110.dp, top = 4.dp, bottom = 4.dp, end = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (active) {
                    Modifier.border(2.dp, accent, RoundedCornerShape(6.dp))
                        .background(accent.copy(alpha = 0.10f))
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                },
            )
            .padding(8.dp),
    ) {
        Text(note.body, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (note.publishedId == null) {
                TextButton(enabled = !busy, onClick = onPublish) {
                    Text(if (busy) "Publicando…" else "Publicar")
                }
                TextButton(onClick = onEdit) { Text("Editar") }
                TextButton(onClick = onDelete) { Text("Borrar") }
            } else {
                io.acr.ui.StatusBadge(io.acr.i18n.t("common.published"))
                note.publishedUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    TextButton(onClick = { openInBrowser(url) }) { Text("Ver en el navegador") }
                }
            }
        }
    }
}

@Composable
private fun NoteDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var body by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                modifier = Modifier.width(520.dp).height(200.dp),
                label = { Text("Comentario") },
            )
        },
        confirmButton = {
            Button(enabled = body.isNotBlank(), onClick = { onSave(body.trim()) }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Qué muestra la columna izquierda: el árbol del diff o lo que hay para revisar. */
internal enum class Sidebar { ARCHIVOS, HALLAZGOS }

/**
 * Una observación anclada al código: un hallazgo de la review o una nota propia.
 *
 * Existe para poder recorrerlas en orden sin importar de dónde salió cada una. El orden es el de
 * lectura —archivo del diff, después línea—, que es como uno revisa; el orden en que la review las
 * devolvió no le sirve a nadie.
 */
internal data class Anchor(
    val id: String,
    val kind: Kind,
    val filePath: String,
    val lineNo: Int?,
    val title: String,
    val severity: String?,
    val published: Boolean,
) {
    enum class Kind { HALLAZGO, NOTA }
}

/**
 * Arma el recorrido: hallazgos de la review y notas propias, en orden de lectura.
 *
 * El orden es el del diff —archivo por archivo, y dentro de cada uno por línea—, que es como uno
 * revisa. El orden en que la review devolvió los hallazgos no le sirve a nadie. Un archivo que ya
 * no está en el diff (porque el PR cambió desde la review) va al final en vez de descolocar todo.
 */
internal fun buildAnchors(
    files: List<Git.FileChange>,
    findings: List<io.acr.data.Finding>,
    notes: List<LocalNote>,
): List<Anchor> {
    val orden = files.withIndex().associate { (i, f) -> f.path to i }
    val deHallazgos = findings.map {
        Anchor(it.id, Anchor.Kind.HALLAZGO, it.filePath, it.lineNo, it.title, it.severity, it.publishedId != null)
    }
    val deNotas = notes.map {
        Anchor(
            it.id, Anchor.Kind.NOTA, it.filePath, it.lineNo,
            it.body.lineSequence().firstOrNull().orEmpty(), null, it.publishedId != null,
        )
    }
    return (deHallazgos + deNotas).sortedWith(
        compareBy(
            { orden[it.filePath] ?: Int.MAX_VALUE },
            { it.lineNo ?: Int.MAX_VALUE },
            { it.kind.ordinal },
            { it.id },
        ),
    )
}

@Composable
private fun AnchorRow(a: Anchor, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    Row(
        Modifier.fillMaxWidth().background(bg).clickableText(onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Text(
            a.lineNo?.let { ":$it" } ?: "—",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(46.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(a.title, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            Row {
                if (a.kind == Anchor.Kind.HALLAZGO) {
                    io.acr.ui.SeverityBadge(a.severity)
                } else {
                    Text(
                        "✎",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (a.published) {
                    Spacer(Modifier.width(6.dp))
                    io.acr.ui.StatusBadge(io.acr.i18n.t("common.published"))
                }
            }
        }
    }
}
