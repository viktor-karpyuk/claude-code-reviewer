package io.acr.ui.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.acr.AppContext
import io.acr.forge.RepoRecord
import io.acr.i18n.t
import io.acr.impl.ImplRepo
import io.acr.impl.Implementation
import io.acr.impl.RepoRole

/**
 * Ajustar una implementación en marcha.
 *
 * Sumar un repositorio que recién se abre, corregir un parámetro, agregar una spec que faltaba:
 * todo eso pasa a mitad de camino y no puede obligar a empezar de cero. Lo ya construido queda —su
 * código está commiteado— y lo único que se puede tirar es el plan de lo que falta, que se armó
 * con información distinta.
 *
 * Replanificar es una acción aparte y explícita: cambiar el título no debería rehacer un plan de
 * veinte tareas, y rehacerlo solo cada vez que se toca algo sería peor que no poder tocar nada.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditImplDialog(
    ctx: AppContext,
    repos: List<RepoRecord>,
    impl: Implementation,
    actuales: List<ImplRepo>,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var titulo by remember(impl.id) { mutableStateOf(impl.title) }
    var elegidos by remember(impl.id) { mutableStateOf(actuales) }
    var rutas by remember(impl.id) { mutableStateOf(impl.sources) }
    var extra by remember(impl.id) { mutableStateOf(impl.extraPrompt.orEmpty()) }
    var replanificar by remember(impl.id) { mutableStateOf(false) }

    val hechas = io.acr.ui.dbState(impl.id, initial = 0) {
        ctx.impls.tasks(impl.id).count { it.status == io.acr.impl.TaskStatus.DONE }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("impl.edit")) },
        text = {
            Column(Modifier.width(640.dp)) {
                OutlinedTextField(
                    value = titulo,
                    onValueChange = { titulo = it },
                    label = { Text(t("impl.name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))
                Text(t("impl.repo"), style = MaterialTheme.typography.labelSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repos.forEach { r ->
                        val puesto = elegidos.firstOrNull { it.repoId == r.id }
                        FilterChip(
                            selected = puesto != null,
                            onClick = {
                                elegidos = if (puesto != null) elegidos - puesto
                                else elegidos + ImplRepo(r.id, RepoRole.OTHER)
                            },
                            label = { Text(r.name) },
                        )
                    }
                }
                elegidos.forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            repos.firstOrNull { it.id == e.repoId }?.name.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(180.dp),
                        )
                        // De qué rama parte. Se ofrecen las del clon en vez de un campo libre:
                        // escribir mal el nombre no falla al guardar, falla al correr.
                        val ramas = io.acr.ui.dbState(e.repoId, initial = emptyList<String>()) {
                            kotlinx.coroutines.runBlocking {
                                repos.firstOrNull { it.id == e.repoId }?.let {
                                    io.acr.claude.Git.branches(java.io.File(it.localPath))
                                }.orEmpty()
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            RepoRole.entries.forEach { rol ->
                                FilterChip(
                                    selected = e.role == rol,
                                    onClick = {
                                        elegidos = elegidos.map {
                                            if (it.repoId == e.repoId) it.copy(role = rol) else it
                                        }
                                    },
                                    label = { Text(t(rol.labelKey), style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                            ramas.take(6).forEach { rama ->
                                FilterChip(
                                    selected = e.baseBranch == rama,
                                    onClick = {
                                        elegidos = elegidos.map {
                                            if (it.repoId == e.repoId) {
                                                it.copy(baseBranch = if (it.baseBranch == rama) null else rama)
                                            } else it
                                        }
                                    },
                                    label = { Text(rama, style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(t("impl.docs"), style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { elegirDocs(false)?.let { rutas = rutas + it } }) {
                        Text(t("impl.addFiles"))
                    }
                    OutlinedButton(onClick = { elegirDocs(true)?.let { rutas = rutas + it } }) {
                        Text(t("impl.addFolder"))
                    }
                }
                rutas.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            r,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        TextButton(onClick = { rutas = rutas - r }) { Text(t("common.delete")) }
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = extra,
                    onValueChange = { extra = it },
                    label = { Text(t("impl.extra")) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 160.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                )

                // Lo importante del diálogo: qué pasa con lo ya hecho. Sin decirlo, cambiar un
                // parámetro se siente como algo que puede romper el trabajo de una hora.
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = replanificar,
                        onClick = { replanificar = !replanificar },
                        label = { Text(t("impl.replan")) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (replanificar) t("impl.replanOn", hechas) else t("impl.replanOff"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = titulo.isNotBlank() && elegidos.isNotEmpty() && rutas.isNotEmpty(),
                onClick = {
                    ctx.impls.update(
                        impl.id, titulo.trim(), rutas,
                        extra.trim().takeIf { it.isNotBlank() }, elegidos,
                    )
                    // Tirar el plan pendiente es opcional y sólo toca lo que no se hizo: las
                    // tareas terminadas tienen su código commiteado y siguen existiendo.
                    if (replanificar) ctx.impls.clearPendingPlan(impl.id)
                    onSaved()
                },
            ) { Text(t("common.save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}
