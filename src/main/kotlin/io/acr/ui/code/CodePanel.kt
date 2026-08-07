package io.acr.ui.code

import androidx.compose.foundation.background
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
import androidx.compose.material3.VerticalDivider
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
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File
import java.net.URI

private fun openInBrowser(url: String) {
    runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url)) }
}

/** Colores del diff. Se mantienen legibles sobre fondo claro y oscuro. */
internal val ADDED_BG = Color(0x3327C08A)
internal val REMOVED_BG = Color(0x33E4685F)
internal val HUNK_BG = Color(0x228FBEFF)

@Composable
fun CodePanel(
    ctx: AppContext,
    repo: RepoRecord,
    pr: PullRequest?,
    prId: Long,
    snackbar: SnackbarHostState,
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

    LaunchedEffect(repo.id, prId, range) {
        if (range == null) return@LaunchedEffect
        loading = true
        files = Git.numstat(workDir, range)
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

    Row(Modifier.fillMaxSize()) {
        // Lista de archivos
        Column(Modifier.width(330.dp).fillMaxHeight()) {
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
            if (sidebar == Sidebar.ARCHIVOS) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(files, key = { it.path }) { f ->
                        FileRow(
                            f = f,
                            active = selected == f.path,
                            notes = notes.count { it.filePath == f.path },
                            findings = findings.count { it.filePath == f.path },
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

        VerticalDivider(Modifier.fillMaxHeight().padding(horizontal = 8.dp))

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
private fun FileRow(f: Git.FileChange, active: Boolean, notes: Int, findings: Int, onClick: () -> Unit) {
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
                Text("$findings ▲", style = MaterialTheme.typography.labelSmall, color = Color(0xFFD08A2C))
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

    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
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
private fun FindingCard(finding: io.acr.data.Finding, busy: Boolean, onPublish: () -> Unit) {
    val accent = when (finding.severity) {
        "blocker" -> Color(0xFFCB5A50)
        "major" -> Color(0xFFD08A2C)
        else -> Color(0xFF7A879C)
    }
    Column(
        Modifier.fillMaxWidth().padding(start = 110.dp, top = 4.dp, bottom = 4.dp, end = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(finding.severity.uppercase(), style = MaterialTheme.typography.labelSmall, color = accent)
            Spacer(Modifier.width(8.dp))
            Text(finding.title, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            finding.body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (finding.publishedId == null) {
            TextButton(enabled = !busy, onClick = onPublish) {
                Text(if (busy) "Publicando…" else "Publicar inline")
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "publicado",
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
private fun NoteCard(note: LocalNote, busy: Boolean, onEdit: () -> Unit, onDelete: () -> Unit, onPublish: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = 110.dp, top = 4.dp, bottom = 4.dp, end = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
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
                Text(
                    "publicada",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
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
                Text(
                    if (a.kind == Anchor.Kind.HALLAZGO) "▲ " + (a.severity ?: "") else "✎",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (a.kind == Anchor.Kind.HALLAZGO) Color(0xFFD08A2C)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (a.published) {
                    Text(
                        "  " + io.acr.i18n.t("common.published"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
