package io.acr.ui.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import io.acr.AppContext
import io.acr.forge.RepoRecord
import io.acr.i18n.t
import io.acr.impl.ImplRepo
import io.acr.impl.Implementation

/**
 * Los commits de la rama, como pantalla.
 *
 * Estaban en un desplegable dentro del detalle, y ahí el diff no entraba: cuatrocientos píxeles de
 * alto compartidos con el resto de la implementación, para leer código que necesita ancho y
 * contexto. Uno terminaba abriendo el repositorio en otra herramienta, que es exactamente lo que
 * esta pantalla venía a evitar.
 *
 * La lista a la izquierda y el contenido a la derecha, en vez de una lista que se expande: con
 * quince commits, expandir el octavo empuja los siete anteriores fuera de la vista y perdés el
 * lugar donde estabas. Al costado, la lista queda quieta y sólo cambia lo que mirás.
 */
@Composable
fun ImplCommitsScreen(
    ctx: AppContext,
    impl: Implementation,
    misRepos: List<RepoRecord>,
    suyos: List<ImplRepo>,
    /** Volver a la implementación, que es donde están las tareas. */
    onBack: () -> Unit,
    onHome: () -> Unit,
) {
    val rama = impl.branch
    val commits = io.acr.ui.dbState(impl.id, rama, initial = null as List<Pair<String, io.acr.claude.Git.Commit>>?) {
        kotlinx.coroutines.runBlocking {
            if (rama == null) {
                emptyList()
            } else {
                misRepos.flatMap { r ->
                    val base = suyos.firstOrNull { it.repoId == r.id }?.baseBranch
                        ?: impl.baseBranch ?: "develop"
                    // Donde el trabajo esté ahora: el taller mientras corre, el clon cuando ya se
                    // devolvió y se borró. Leyendo siempre el clon, la lista quedaba vacía durante
                    // toda la implementación.
                    io.acr.claude.Git.commitsBetween(
                        ctx.workspaces.dirFor(impl.id, impl.useWorkspace, r.name, r.localPath),
                        base, rama,
                    ).map { r.name to it }
                }
            }
        }
    }
    var elegido by remember(impl.id) { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
            io.acr.ui.Breadcrumbs(
                listOf(
                    io.acr.ui.Crumb(t("nav.impls"), onHome),
                    io.acr.ui.Crumb(impl.title, onBack),
                    io.acr.ui.Crumb(t("impl.commitsSection")),
                ),
                Modifier.weight(1f),
            )
            // Además de la miga: el camino de vuelta a las tareas es el que más se usa desde acá
            // —se mira un commit para entender una tarea— y no tiene que depender de reconocer
            // que el título del medio es un enlace.
            TextButton(onClick = onBack) { Text("←  " + t("impl.backToTasks")) }
        }

        if (commits == null) {
            Text(t("impl.loadingDiff"), style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        if (commits.isEmpty()) {
            Text(
                t("impl.noCommits"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // --- La lista, quieta ---
            Column(
                Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
            ) {
                Text(
                    t("impl.commits", commits.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                commits.forEach { (repoNombre, c) ->
                    val activo = elegido == c.sha
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (activo) MaterialTheme.colorScheme.surfaceVariant
                                else androidx.compose.ui.graphics.Color.Transparent,
                            )
                            .clickable { elegido = if (activo) null else c.sha }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            c.subject,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                c.sha.take(7),
                                style = MaterialTheme.typography.labelSmall
                                    .copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            if (misRepos.size > 1) {
                                Text(
                                    repoNombre,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                c.date,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // --- El commit elegido, con todo el ancho ---
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                val actual = commits.firstOrNull { it.second.sha == elegido }
                if (actual == null) {
                    Text(
                        t("impl.pickCommit"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val (repoNombre, c) = actual
                    val repo = misRepos.firstOrNull { it.name == repoNombre } ?: misRepos.firstOrNull()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.subject, style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOfNotNull(c.sha.take(10), repoNombre, c.date).joinToString("  ·  "),
                                style = MaterialTheme.typography.labelSmall
                                    .copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                .setContents(java.awt.datatransfer.StringSelection(c.sha), null)
                        }) { Text(t("impl.copySha")) }
                        repo?.let { r ->
                            TextButton(onClick = {
                                runCatching {
                                    java.awt.Desktop.getDesktop().open(java.io.File(r.localPath))
                                }
                            }) { Text(t("impl.openRepo")) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    repo?.let {
                        CommitDiffView(
                            it, c.sha,
                            ctx.workspaces.dirFor(impl.id, impl.useWorkspace, it.name, it.localPath),
                        )
                    }
                }
            }
        }
    }
}
