package io.acr.ui.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.acr.forge.RepoRecord
import io.acr.i18n.t
import io.acr.impl.FileChange
import io.acr.impl.ImplTask

/**
 * El código que escribió una tarea, como se lee una review.
 *
 * Saber que una tarea tocó cinco archivos no alcanza para confiar en ella: hay que ver qué escribió.
 * Es la diferencia entre un informe de actividad y algo que se puede revisar.
 *
 * El diff se le pide a git al abrir el archivo y no se guarda: el commit ya lo tiene, guardar una
 * copia sería duplicar el repositorio adentro de la base, y sólo se mira el archivo que alguien
 * abre. Se reutiliza el mismo renderizador que la vista de código de las reviews, así el diff se
 * lee igual venga de donde venga.
 */
@Composable
fun TaskDiffView(
    repo: RepoRecord?,
    task: ImplTask,
) {
    val archivos = task.diff?.files.orEmpty()
    if (archivos.isEmpty() || repo == null || task.commitSha == null) return

    var abierto by remember(task.id) { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth()) {
        archivos.forEach { f ->
            FilaArchivo(f, abierto == f.path) {
                abierto = if (abierto == f.path) null else f.path
            }
            if (abierto == f.path) {
                DiffDeArchivo(repo, task.commitSha, f.path)
            }
        }
    }
}

/** Una fila de archivo: qué le pasó, cuál es y cuánto cambió. */
@Composable
internal fun FilaArchivo(f: FileChange, abierto: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (abierto) "▾" else "▸",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(16.dp),
        )
        Text(
            f.status.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = when (f.status) {
                'A' -> io.acr.ui.stats.ChartColors.added
                'D' -> io.acr.ui.stats.ChartColors.deleted
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.width(18.dp),
        )
        Text(
            f.path,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            "+${f.added}",
            style = MaterialTheme.typography.labelSmall,
            color = io.acr.ui.stats.ChartColors.added,
            modifier = Modifier.width(52.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
        Text(
            "−${f.deleted}",
            style = MaterialTheme.typography.labelSmall,
            color = io.acr.ui.stats.ChartColors.deleted,
            modifier = Modifier.width(52.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/**
 * El diff de un archivo dentro del commit de la tarea.
 *
 * Se acota la altura y scrollea adentro: un archivo nuevo de mil líneas empujaría todo lo que
 * sigue —las otras tareas, el resto de la pantalla— fuera de la vista.
 */
@Composable
internal fun DiffDeArchivo(repo: RepoRecord, sha: String, path: String) {
    val texto = io.acr.ui.dbState(repo.id, sha, path, initial = null as String?) {
        kotlinx.coroutines.runBlocking {
            io.acr.claude.Git.commitDiff(java.io.File(repo.localPath), sha, path)
        }
    }
    val lineas = remember(texto) { texto?.let { io.acr.claude.DiffParser.parse(it) }.orEmpty() }
    val hScroll = rememberScrollState()

    if (texto == null) {
        Text(
            t("impl.loadingDiff"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 34.dp, bottom = 4.dp),
        )
        return
    }
    if (lineas.isEmpty()) {
        Text(
            t("impl.noDiff"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 34.dp, bottom = 4.dp),
        )
        return
    }

    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surface)
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        lineas.forEach { l ->
            io.acr.ui.code.DiffRow(line = l, hScroll = hScroll, onClick = {})
        }
    }
}

/**
 * Los archivos de un commit cualquiera, con su diff.
 *
 * Lo mismo que muestra una tarea, pero partiendo de un sha suelto: la lista de commits de la rama
 * decía qué se hizo y cuándo, y no había forma de ver qué. Abrir el repositorio en una terminal para
 * contestar eso es salirse de la herramienta justo en la pregunta más común.
 *
 * Los archivos se leen del commit y no se guardan: son del historial de git, que no se va a ningún
 * lado, y duplicarlos en la base sería mantener dos versiones de la misma verdad.
 */
@Composable
fun CommitDiffView(repo: RepoRecord, sha: String) {
    val archivos = io.acr.ui.dbState(repo.id, sha, initial = null as List<FileChange>?) {
        kotlinx.coroutines.runBlocking {
            io.acr.claude.Git.commitStats(java.io.File(repo.localPath), sha)
        }
    }
    var abierto by remember(sha) { mutableStateOf<String?>(null) }

    if (archivos == null) {
        Text(
            t("impl.loadingDiff"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (archivos.isEmpty()) {
        Text(
            t("impl.noDiff"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(Modifier.fillMaxWidth()) {
        archivos.forEach { f ->
            FilaArchivo(f, abierto == f.path) { abierto = if (abierto == f.path) null else f.path }
            if (abierto == f.path) DiffDeArchivo(repo, sha, f.path)
        }
    }
}
