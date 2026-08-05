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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
@Composable
fun CommitsPanel(repo: RepoRecord, pr: PullRequest?) {
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

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(330.dp).fillMaxHeight()) {
            Text("${commits.size} commits", style = MaterialTheme.typography.labelMedium)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(commits, key = { it.sha }) { c ->
                    CommitRow(c, active = selected?.sha == c.sha) { selected = c }
                }
            }
        }

        VerticalDivider(Modifier.fillMaxHeight().padding(horizontal = 8.dp))

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
private fun CommitRow(c: Git.Commit, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
    Column(Modifier.fillMaxWidth().background(bg).clickableText(onClick).padding(8.dp)) {
        Text(c.subject, style = MaterialTheme.typography.bodySmall, maxLines = 2)
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
