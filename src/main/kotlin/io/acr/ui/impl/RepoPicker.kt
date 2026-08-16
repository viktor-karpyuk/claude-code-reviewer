package io.acr.ui.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import io.acr.impl.ImplRepo
import io.acr.impl.RepoRole

/**
 * Elegir repositorios y, para cada uno, su rol y de qué rama parte.
 *
 * Antes era una fila de chips de repositorios y, debajo, otra fila mezclando roles con nombres de
 * rama: todo del mismo tamaño y del mismo color, sin decir qué era cada cosa. Con dos repositorios
 * ya no se entendía cuál rama pertenecía a cuál.
 *
 * Ahora cada repositorio elegido es una tarjeta con sus dos decisiones etiquetadas y separadas. La
 * rama base va en un desplegable con las ramas reales del clon —escribir un nombre que no existe
 * no falla al guardar, falla al correr, media hora después—.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RepoPicker(
    repos: List<RepoRecord>,
    elegidos: List<ImplRepo>,
    onChange: (List<ImplRepo>) -> Unit,
    sugerirRol: (String) -> RepoRole = { RepoRole.OTHER },
) {
    Column(Modifier.fillMaxWidth()) {
        Text(t("impl.pickRepos"), style = MaterialTheme.typography.labelLarge)
        Text(
            t("impl.pickReposNote"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))

        // Con casilla y no con chip: un chip seleccionado y uno no se distinguen de reojo, y acá
        // la pregunta es "¿está incluido o no?", que es exactamente lo que una casilla contesta.
        repos.forEach { r ->
            val puesto = elegidos.firstOrNull { it.repoId == r.id }
            Row(
                Modifier.fillMaxWidth()
                    .clickable {
                        onChange(
                            if (puesto != null) elegidos - puesto
                            else elegidos + ImplRepo(r.id, sugerirRol(r.name)),
                        )
                    }
                    .padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = puesto != null, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(r.name, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${r.owner}/${r.slug}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (puesto != null) {
                TarjetaRepo(r, puesto) { nuevo ->
                    onChange(elegidos.map { if (it.repoId == r.id) nuevo else it })
                }
            }
        }
    }
}

/** Las dos decisiones de un repositorio elegido: qué es, y de dónde parte. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TarjetaRepo(repo: RepoRecord, actual: ImplRepo, onChange: (ImplRepo) -> Unit) {
    val ramas = io.acr.ui.dbState(repo.id, initial = emptyList<String>()) {
        kotlinx.coroutines.runBlocking {
            io.acr.claude.Git.branches(java.io.File(repo.localPath))
        }
    }
    val actualRama = io.acr.ui.dbState(repo.id, initial = null as String?) {
        kotlinx.coroutines.runBlocking {
            io.acr.claude.Git.currentBranch(java.io.File(repo.localPath))
        }
    }
    var abierto by remember(repo.id) { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().padding(start = 32.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                t("impl.role"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(120.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RepoRole.entries.forEach { rol ->
                    FilterChip(
                        selected = actual.role == rol,
                        onClick = { onChange(actual.copy(role = rol)) },
                        label = { Text(t(rol.labelKey)) },
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                t("impl.baseBranch"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(120.dp),
            )
            Box {
                TextButton(onClick = { abierto = true }) {
                    Text(
                        actual.baseBranch ?: (actualRama?.let { t("impl.branchNow", it) } ?: t("impl.pickBranch")),
                        style = MaterialTheme.typography.bodySmall
                            .copy(fontFamily = FontFamily.Monospace),
                    )
                    Text("  ▾", style = MaterialTheme.typography.labelSmall)
                }
                DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
                    // La opción de "la que esté abierta" queda explícita y arriba: es el valor por
                    // defecto y hay que poder volver a él después de elegir otra cosa.
                    DropdownMenuItem(
                        text = {
                            Text(actualRama?.let { t("impl.branchNow", it) } ?: t("impl.currentBranch"))
                        },
                        onClick = { onChange(actual.copy(baseBranch = null)); abierto = false },
                    )
                    ramas.forEach { rama ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    rama,
                                    style = MaterialTheme.typography.bodySmall
                                        .copy(fontFamily = FontFamily.Monospace),
                                )
                            },
                            onClick = { onChange(actual.copy(baseBranch = rama)); abierto = false },
                        )
                    }
                }
            }
        }
        Text(
            t("impl.baseBranchNote"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Box(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box { content() }
}
