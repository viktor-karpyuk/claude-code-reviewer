package io.acr.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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

/**
 * Escribir una convención a mano, sin pasar por un archivo.
 *
 * Es la tercera vía junto con subir un `.md` e importarlo del repositorio, y cubre el caso más
 * común de todos: la regla que existe en la cabeza del equipo y en ningún documento. Obligar a
 * crear un archivo para anotar "los controladores no llevan lógica" es pedirle a alguien que
 * abra un editor, invente una ruta y vuelva — tres pasos para dos renglones, y por eso no se hace.
 *
 * El campo de texto es monoespaciado y grande a propósito: lo que se escribe acá es Markdown que
 * va a viajar dentro de un prompt, y verlo en una sola línea invita a escribir una sola línea.
 */
@Composable
fun GuidelineEditorDialog(
    tituloInicial: String = "",
    textoInicial: String = "",
    onDismiss: () -> Unit,
    onSave: (nombre: String, texto: String) -> Unit,
) {
    var nombre by remember { mutableStateOf(tituloInicial) }
    var texto by remember { mutableStateOf(textoInicial) }
    val editando = textoInicial.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(io.acr.i18n.t(if (editando) "guide.editTitle" else "guide.writeTitle")) },
        text = {
            Column(Modifier.width(620.dp)) {
                Text(
                    io.acr.i18n.t("guide.writeNote"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = nombre,
                    onValueChange = { nombre = it },
                    label = { Text(io.acr.i18n.t("guide.name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = texto,
                    onValueChange = { texto = it },
                    label = { Text(io.acr.i18n.t("guide.content")) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 380.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                        .copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    io.acr.i18n.t("guide.size", texto.length),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (texto.length > io.acr.claude.ReviewPrompt.MAX_GUIDELINES_CHARS)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            // Sin texto no hay convención: guardar una vacía deja una fila que no dice nada y
            // encima ocupa lugar en la lista.
            TextButton(
                enabled = texto.isNotBlank(),
                onClick = { onSave(nombre.trim().ifBlank { io.acr.i18n.t2("guide.untitled") }, texto) },
            ) { Text(io.acr.i18n.t("common.save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(io.acr.i18n.t("common.cancel")) } },
    )
}
