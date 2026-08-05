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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
            Text(
                "${files.size} archivos · ${notes.size} notas · ${findings.size} hallazgos",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
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
        }

        VerticalDivider(Modifier.fillMaxHeight().padding(horizontal = 8.dp))

        // Diff del archivo elegido
        Column(Modifier.weight(1f).fillMaxHeight()) {
            selected?.let {
                Text(it, style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace))
                Text(
                    "Hacé clic en una línea para dejar una nota local.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
            }
            val hScroll = rememberScrollState()
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(lines) { idx, line ->
                    DiffRow(
                        line = line,
                        hScroll = hScroll,
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
internal fun DiffRow(line: DiffLine, hScroll: androidx.compose.foundation.ScrollState, onClick: () -> Unit) {
    val bg = when (line.kind) {
        DiffLine.Kind.ADDED -> ADDED_BG
        DiffLine.Kind.REMOVED -> REMOVED_BG
        DiffLine.Kind.HUNK -> HUNK_BG
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
