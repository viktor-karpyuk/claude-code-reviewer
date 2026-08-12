package io.acr.ui.code

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.acr.claude.DiffLine
import io.acr.claude.DiffParser
import io.acr.claude.Git
import io.acr.forge.PullRequest
import io.acr.forge.RepoRecord
import io.acr.ui.clickableText
import java.io.File

/** Historial del PR: sus commits, y el diff de cada uno. */
/**
 * Cuántos commits llegaron después del que se revisó.
 *
 * La lista viene del más nuevo al más viejo, así que todo lo que está por encima del commit
 * revisado es posterior. Los sha se comparan por prefijo porque unos vienen cortos y otros
 * completos según de dónde salgan.
 *
 * Devuelve null si no se sabe cuál se revisó, o si ese commit ya no está en la rama —un rebase lo
 * reescribe—: ahí decir "0 nuevos" sería mentir, y decir "todos" también.
 */
internal fun commitsSinceReview(commits: List<Git.Commit>, reviewHead: String?): Int? {
    val head = reviewHead?.takeIf { it.isNotBlank() } ?: return null
    val i = commits.indexOfFirst { it.sha.startsWith(head) || head.startsWith(it.sha) }
    return if (i < 0) null else i
}

@Composable
fun CommitsPanel(
    repo: RepoRecord,
    pr: PullRequest?,
    prefs: io.acr.data.PrefsRepo,
    reviewHeadSha: String? = null,
) {
    val workDir = remember(repo.localPath) { File(repo.localPath) }

    var commits by remember(repo.id, pr?.id) { mutableStateOf<List<Git.Commit>>(emptyList()) }
    var selected by remember(repo.id, pr?.id) { mutableStateOf<Git.Commit?>(null) }
    var files by remember(repo.id, pr?.id) { mutableStateOf<List<Git.FileChange>>(emptyList()) }
    var selectedFile by remember(repo.id, pr?.id) { mutableStateOf<String?>(null) }
    var lines by remember(repo.id, pr?.id) { mutableStateOf<List<DiffLine>>(emptyList()) }
    var loading by remember(repo.id, pr?.id) { mutableStateOf(true) }

    LaunchedEffect(repo.id, pr?.id) {
        val p = pr ?: return@LaunchedEffect
        loading = true
        commits = Git.commits(workDir, p.targetBranch, p.sourceBranch)
        selected = commits.firstOrNull()
        loading = false
    }

    LaunchedEffect(selected?.sha) {
        val sha = selected?.sha
        if (sha == null) {
            files = emptyList()
            selectedFile = null
            return@LaunchedEffect
        }
        files = Git.commitFiles(workDir, sha)
        selectedFile = files.firstOrNull()?.path
    }

    LaunchedEffect(selected?.sha, selectedFile) {
        val sha = selected?.sha
        val path = selectedFile
        if (sha == null || path == null) {
            lines = emptyList()
            return@LaunchedEffect
        }
        lines = DiffParser.parse(Git.commitDiff(workDir, sha, path))
    }

    if (pr == null) {
        Box(Modifier.fillMaxSize()) {
            Text(
                "No puedo mostrar los commits: todavía no cargaron los datos del PR.\nFijate el error arriba y probá «Refrescar».",
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val anchoPanel = io.acr.ui.rememberPaneWidth(prefs, "commits", 330.dp)
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(anchoPanel.value).fillMaxHeight()) {
            val nuevos = commitsSinceReview(commits, reviewHeadSha)
            Text("${commits.size} commits", style = MaterialTheme.typography.labelMedium)
            Text(
                when {
                    nuevos == null -> io.acr.i18n.t("commits.unknownSinceReview")
                    nuevos == 0 -> io.acr.i18n.t("commits.noneSinceReview")
                    nuevos == 1 -> io.acr.i18n.t("commits.oneSinceReview")
                    else -> io.acr.i18n.t("commits.sinceReview", nuevos)
                },
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    nuevos == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    nuevos == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                },
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(commits, key = { _, c -> c.sha }) { i, c ->
                    CommitRow(
                        c,
                        active = selected?.sha == c.sha,
                        // Posterior a la review: son los que hay que mirar para saber si
                        // corrigieron lo que se marcó.
                        nuevo = nuevos != null && i < nuevos,
                    ) { selected = c }
                }
            }
        }

        io.acr.ui.VerticalSplitter(anchoPanel, prefs, "commits", min = 200.dp, max = 700.dp)

        Column(Modifier.weight(1f).fillMaxHeight()) {
            selected?.let { c ->
                // Seleccionable: el sha y el mensaje son justo lo que uno quiere copiar.
                SelectionContainer {
                    Column {
                        Text(c.subject, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${c.sha} · ${c.author} · ${c.date}",
                            style = MaterialTheme.typography.labelSmall
                                .copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (c.body.isNotBlank()) {
                            Text(
                                c.body,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 6.dp)
                                    .heightIn(max = 160.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                // Archivos del commit, en fila: suelen ser pocos.
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(files, key = { it.path }) { f ->
                        FileChip(f, active = selectedFile == f.path) { selectedFile = f.path }
                    }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
            }

            val hScroll = rememberScrollState()
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(lines) { line -> DiffRow(line = line, hScroll = hScroll, onClick = {}) }
                }
            }
        }
    }
}

@Composable
private fun CommitRow(c: Git.Commit, active: Boolean, nuevo: Boolean, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
    Column(Modifier.fillMaxWidth().background(bg).clickableText(onClick).padding(8.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (nuevo) {
                Text(
                    "● ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(c.subject, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
        Text(
            "${c.sha.take(8)} · ${c.author} · ${c.date}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun FileChip(f: Git.FileChange, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(bg).clickableText(onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            f.path.substringAfterLast('/'),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        Text("+${f.added}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF35A66F))
        Spacer(Modifier.width(3.dp))
        Text("−${f.deleted}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFCB5A50))
    }
}
