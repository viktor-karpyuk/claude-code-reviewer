package io.acr.ui.repos

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.acr.AppContext
import io.acr.forge.RepoRecord
import io.acr.i18n.t

/**
 * La sección de repositorios: todos a la vista, con su estado y desde dónde agregar uno nuevo.
 *
 * Antes los repositorios sólo existían como una lista angosta pegada al costado, presente en toda
 * la app, y agregar uno era un ítem del menú principal. Dos cosas mal: la lista ocupaba lugar
 * también cuando estabas mirando el panel o las estadísticas, donde no sirve de nada; y "agregar
 * repositorio" quedaba al mismo nivel que las secciones, cuando es una acción de esta sección y no
 * un lugar al que ir.
 *
 * Acá cada repositorio es una tarjeta con lo que uno quiere saber de un vistazo —cuántos PRs tiene
 * abiertos, si hay reviews corriendo, cuándo fue la última— y desde acá se entra a su lista de
 * pull requests.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ReposPanel(
    ctx: AppContext,
    repos: List<RepoRecord>,
    running: Map<String, Int>,
    onOpen: (RepoRecord) -> Unit,
    onEdit: (RepoRecord) -> Unit,
    onAdd: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t("repos.title"), style = MaterialTheme.typography.titleMedium)
                Text(
                    t("repos.note"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // La acción principal de esta sección, acá y no en el menú: agregar un repositorio es
            // algo que se hace estando en repositorios, no un destino al que ir.
            Button(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.height(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("repos.add"))
            }
        }

        Spacer(Modifier.height(16.dp))

        if (repos.isEmpty()) {
            Text(
                t("repos.empty"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repos.forEach { repo ->
                RepoCard(
                    ctx = ctx,
                    repo = repo,
                    running = running[repo.id] ?: 0,
                    onOpen = { onOpen(repo) },
                    onEdit = { onEdit(repo) },
                )
            }
        }
    }
}

/**
 * Una tarjeta por repositorio.
 *
 * Muestra lo que hace falta para decidir a cuál entrar: cuántos pull requests abiertos tiene,
 * cuántas reviews están corriendo ahora y cuándo fue la última que terminó. Sin esos tres datos la
 * tarjeta sería sólo un nombre, y para eso alcanzaba la lista de antes.
 */
@Composable
private fun RepoCard(
    ctx: AppContext,
    repo: RepoRecord,
    running: Int,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
) {
    val abiertos = io.acr.ui.dbState(repo.id, initial = 0) { ctx.prCache.get(repo.id).prs.size }
    val ultima = io.acr.ui.dbState(repo.id, initial = null as String?) {
        ctx.reviews.lastFinishedAt(repo.id)
    }
    val revisados = io.acr.ui.dbState(repo.id, initial = 0) { ctx.reviews.doneCount(repo.id) }

    Column(
        Modifier.width(300.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(repo.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (running > 0) {
                CircularProgressIndicator(Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)
            }
        }
        Text(
            "${repo.owner}/${repo.slug}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(10.dp))
        Row {
            Dato(t("repos.openPrs"), abiertos.toString())
            Spacer(Modifier.width(18.dp))
            Dato(t("repos.reviewed"), revisados.toString())
            if (running > 0) {
                Spacer(Modifier.width(18.dp))
                Dato(t("repos.running"), running.toString(), destacado = true)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            ultima?.let { t("repos.lastReview", it.take(10)) } ?: t("repos.neverReviewed"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onOpen) { Text(t("repos.openPrsAction")) }
            TextButton(onClick = onEdit) { Text(t("common.edit")) }
        }
    }
}

@Composable
private fun Dato(etiqueta: String, valor: String, destacado: Boolean = false) {
    Column {
        Text(
            valor,
            style = MaterialTheme.typography.titleSmall,
            color = if (destacado) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            etiqueta,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
