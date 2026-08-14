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
) {
    var version by remember { mutableStateOf(0) }
    val docs = io.acr.ui.dbState(repoId, version, initial = emptyList<Guideline>()) {
        if (repoId == null) repo.globals() else repo.forRepo(repoId, onlyEnabled = false)
    }
    var error by remember { mutableStateOf<String?>(null) }
    // `t()` es @Composable y esto se usa dentro de un onClick: se resuelve acá.
    val msgVacio = io.acr.i18n.t("guide.empty")

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
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
