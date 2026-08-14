package io.acr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.acr.data.Guideline
import io.acr.data.GuidelineRepository
import java.io.File

/**
 * Abre el selector de archivos nativo y devuelve el `.md` elegido.
 *
 * `FileDialog` de AWT y no `JFileChooser`: en macOS el primero es el diálogo del sistema y el
 * segundo un cuadro de Swing que se ve prestado de otra época.
 */
private fun elegirMarkdown(): File? {
    val dialogo = java.awt.FileDialog(null as java.awt.Frame?, "Elegí el documento", java.awt.FileDialog.LOAD)
    dialogo.setFilenameFilter { _, name -> name.endsWith(".md", ignoreCase = true) }
    dialogo.isVisible = true
    val dir = dialogo.directory ?: return null
    val archivo = dialogo.file ?: return null
    return File(dir, archivo).takeIf { it.isFile }
}

/**
 * Lista de convenciones, con subir, activar y borrar.
 *
 * @param repoId null para las globales, que aplican a todos los repositorios.
 */
@Composable
fun GuidelinesSection(
    repo: GuidelineRepository,
    repoId: String?,
    titulo: String,
    nota: String,
    /** Clon local del repositorio, para ofrecer los CLAUDE.md que ya viven ahí. Null en las globales. */
    localPath: String? = null,
) {
    var version by remember { mutableStateOf(0) }
    val docs = io.acr.ui.dbState(repoId, version, initial = emptyList<Guideline>()) {
        if (repoId == null) repo.globals() else repo.forRepo(repoId, onlyEnabled = false)
    }
    var error by remember { mutableStateOf<String?>(null) }
    // null = cerrado; Pair(id-o-null, texto) = editando una existente o escribiendo una nueva.
    var editor by remember { mutableStateOf<Triple<String?, String, String>?>(null) }
    // `t()` es @Composable y esto se usa dentro de un onClick: se resuelve acá.
    val msgVacio = io.acr.i18n.t("guide.empty")
    val msgSinClaude = io.acr.i18n.t("guide.noneFound")

    Column(Modifier.fillMaxWidth()) {
        Text(titulo, style = MaterialTheme.typography.labelLarge)
        Text(
            nota,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))

        docs.forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = d.enabled,
                    onCheckedChange = { repo.setEnabled(d.id, it); version++ },
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        d.name + if (repoId != null && d.global) "  ·  " + io.acr.i18n.t("guide.global") else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        // El tamaño importa: hay un tope de contexto y conviene verlo antes de
                        // que el recorte se coma media guía.
                        io.acr.i18n.t("guide.size", d.content.length) +
                            (d.source?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // El archivo del que salió puede haber cambiado. No se re-lee solo —eso haría
                    // que cambiar de rama cambiara las reglas sin aviso—, pero callarlo dejaría
                    // revisando con un criterio viejo sin que nadie lo sepa.
                    when (io.acr.data.linkStateOf(d.linkedPath, d.linkedHash)) {
                        io.acr.data.LinkState.STALE -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                io.acr.i18n.t("guide.stale"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = {
                                val f = File(d.linkedPath!!)
                                runCatching { f.readText() }.onSuccess { texto ->
                                    repo.importFromFile(
                                        repoId,
                                        io.acr.data.RepoGuide(d.name, f.absolutePath, texto),
                                    )
                                    version++
                                }
                            }) { Text(io.acr.i18n.t("guide.refresh")) }
                        }

                        io.acr.data.LinkState.MISSING -> Text(
                            io.acr.i18n.t("guide.missing"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        else -> Unit
                    }
                }
                // Sólo las escritas a mano se editan acá: cambiar el texto de una importada la
                // dejaría distinta del archivo del que salió, y el próximo "actualizar" pisaría
                // el cambio sin avisar. Esas se editan en el archivo y se re-importan.
                if (d.linkedPath == null && (repoId == null || !d.global)) {
                    TextButton(onClick = { editor = Triple(d.id, d.name, d.content) }) {
                        Text(io.acr.i18n.t("common.edit"))
                    }
                }
                // Una guía global no se borra desde un repositorio: se administra donde vive.
                if (repoId == null || !d.global) {
                    TextButton(onClick = { repo.delete(d.id); version++ }) {
                        Text(io.acr.i18n.t("common.delete"), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        val total = docs.filter { it.enabled }.sumOf { it.content.length }
        if (total > io.acr.claude.ReviewPrompt.MAX_GUIDELINES_CHARS) {
            Text(
                io.acr.i18n.t("guide.tooBig", total, io.acr.claude.ReviewPrompt.MAX_GUIDELINES_CHARS),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                error = null
                val f = elegirMarkdown() ?: return@OutlinedButton
                val texto = runCatching { f.readText() }.getOrElse {
                    error = it.message; return@OutlinedButton
                }
                if (texto.isBlank()) { error = msgVacio; return@OutlinedButton }
                repo.add(repoId, f.name, texto, f.absolutePath)
                version++
            }) { Text(io.acr.i18n.t("guide.upload")) }

            // La tercera vía, y la que cubre el caso más común: la regla que existe en la cabeza
            // del equipo y en ningún documento. Obligar a crear un archivo para anotar dos
            // renglones es pedir tres pasos, y por eso no se hace.
            OutlinedButton(onClick = { editor = Triple(null, "", "") }) {
                Text(io.acr.i18n.t("guide.write"))
            }

            // El repositorio ya suele traer sus convenciones escritas. Pedirle al usuario que
            // busque a mano un archivo que está a dos directorios de acá es trabajo inventado.
            if (localPath != null) {
                OutlinedButton(onClick = {
                    error = null
                    val hallados = io.acr.data.findRepoGuides(localPath)
                    if (hallados.isEmpty()) { error = msgSinClaude; return@OutlinedButton }
                    hallados.forEach { repo.importFromFile(repoId, it) }
                    version++
                }) { Text(io.acr.i18n.t("guide.importRepo")) }
            }
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }

        editor?.let { (id, nombre, texto) ->
            io.acr.ui.GuidelineEditorDialog(
                tituloInicial = nombre,
                textoInicial = texto,
                onDismiss = { editor = null },
                onSave = { n, t ->
                    if (id == null) repo.add(repoId, n, t, null) else repo.update(id, n, t)
                    editor = null
                    version++
                },
            )
        }
    }
}
