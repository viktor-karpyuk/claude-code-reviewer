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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.acr.AppContext
import io.acr.forge.RepoRecord
import io.acr.i18n.t

/**
 * Alta de una implementación: qué construir, dónde y con qué documentos.
 *
 * Se puede elegir una carpeta entera además de archivos sueltos, porque las specs de algo real
 * casi nunca son un archivo: son requerimientos, mockups y un plan conviviendo en un directorio.
 * Elegirlos de a uno sería pedirle a alguien que arme a mano una lista que ya existe.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewImplDialog(
    ctx: AppContext,
    repos: List<RepoRecord>,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
) {
    var titulo by remember { mutableStateOf("") }
    // Varios, con su rol. En orden de selección: el primero es el principal.
    var elegidos by remember { mutableStateOf(listOf<io.acr.impl.ImplRepo>()) }
    var rutas by remember { mutableStateOf(listOf<String>()) }
    var extra by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("impl.new")) },
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
                Text(
                    t("impl.repoNote"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repos.forEach { r ->
                        val puesto = elegidos.firstOrNull { it.repoId == r.id }
                        FilterChip(
                            selected = puesto != null,
                            onClick = {
                                elegidos = if (puesto != null) elegidos - puesto
                                else elegidos + io.acr.impl.ImplRepo(r.id, adivinarRol(r.name))
                            },
                            label = { Text(r.name) },
                        )
                    }
                }
                // El rol no cambia la ejecución: le dice al planificador qué es cada repositorio
                // para que no proponga una pantalla en el backend.
                elegidos.forEach { e ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            repos.firstOrNull { it.id == e.repoId }?.name.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(180.dp),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            io.acr.impl.RepoRole.entries.forEach { rol ->
                                FilterChip(
                                    selected = e.role == rol,
                                    onClick = {
                                        elegidos = elegidos.map { if (it.repoId == e.repoId) it.copy(role = rol) else it }
                                    },
                                    label = { Text(t(rol.labelKey), style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(t("impl.docs"), style = MaterialTheme.typography.labelSmall)
                Text(
                    t("impl.docsNote"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { elegir(false)?.let { rutas = rutas + it } }) {
                        Text(t("impl.addFiles"))
                    }
                    OutlinedButton(onClick = { elegir(true)?.let { rutas = rutas + it } }) {
                        Text(t("impl.addFolder"))
                    }
                }
                rutas.forEach { r ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            r,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        TextButton(onClick = { rutas = rutas - r }) { Text(t("common.delete")) }
                    }
                }
                // Cuántos .md se van a leer de verdad. Sin esto, elegir una carpeta es un acto de
                // fe: se sabe recién cuando el plan sale mal.
                val encontrados = remember(rutas) { io.acr.impl.loadSources(rutas).size }
                if (rutas.isNotEmpty()) {
                    Text(
                        t("impl.found", encontrados),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (encontrados == 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = extra,
                    onValueChange = { extra = it },
                    label = { Text(t("impl.extra")) },
                    placeholder = { Text(t("impl.extraPlaceholder")) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 180.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Text(
                    t("impl.extraNote"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = titulo.isNotBlank() && elegidos.isNotEmpty() && rutas.isNotEmpty(),
                onClick = {
                    ctx.impls.create(elegidos, titulo.trim(), rutas, extra.trim().takeIf { it.isNotBlank() })
                    onCreated()
                },
            ) { Text(t("common.save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("common.cancel")) } },
    )
}

/**
 * Adivina el rol por el nombre del repositorio.
 *
 * Es una sugerencia y se puede cambiar de un click. Acertar la mayoría de las veces ahorra el
 * trabajo de clasificar a mano cinco repositorios cuyo nombre ya lo dice —`kubrik-erp-be`,
 * `pds-inspections-app`—, y equivocarse cuesta un toque.
 */
private fun adivinarRol(nombre: String): io.acr.impl.RepoRole {
    val n = nombre.lowercase()
    return when {
        n.endsWith("-be") || n.contains("backend") || n.contains("apirest") || n.contains("api") ->
            io.acr.impl.RepoRole.BACKEND
        n.endsWith("-fe") || n.contains("frontend") || n.contains("-web") || n.contains("app") ->
            io.acr.impl.RepoRole.FRONTEND
        else -> io.acr.impl.RepoRole.OTHER
    }
}

/**
 * Selector nativo de archivos o carpetas.
 *
 * En macOS elegir un directorio con `FileDialog` requiere esta propiedad de sistema; sin ella el
 * diálogo deja seleccionar sólo archivos y no hay forma de apuntar a una carpeta de specs.
 */
private fun elegir(carpeta: Boolean): List<String>? {
    val previa = System.getProperty("apple.awt.fileDialogForDirectories")
    if (carpeta) System.setProperty("apple.awt.fileDialogForDirectories", "true")
    return try {
        val d = java.awt.FileDialog(null as java.awt.Frame?, if (carpeta) "Elegí la carpeta" else "Elegí los .md")
        d.isMultipleMode = !carpeta
        d.isVisible = true
        d.files?.map { it.absolutePath }?.takeIf { it.isNotEmpty() }
    } finally {
        if (previa == null) System.clearProperty("apple.awt.fileDialogForDirectories")
        else System.setProperty("apple.awt.fileDialogForDirectories", previa)
    }
}
