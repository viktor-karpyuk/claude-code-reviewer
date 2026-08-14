package io.acr.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import io.acr.forge.MergeStrategy
import io.acr.forge.PullRequest

/** Lo que el usuario decidió en el diálogo de merge. */
data class MergeChoice(
    val message: String,
    val closeSourceBranch: Boolean,
    val strategy: MergeStrategy,
)

/**
 * La confirmación de merge, con la misma información que muestra Bitbucket: de dónde a dónde,
 * con qué estrategia, con qué mensaje de commit y si se borra la rama.
 *
 * Vive en un solo lugar porque se abre desde dos pantallas —la lista y el PR—. Duplicada, la que
 * se tocara primero se llevaría las mejoras y la otra quedaría vieja sin que nadie lo note; y acá
 * la diferencia entre las dos versiones sería un merge hecho con opciones distintas de las que
 * uno creía.
 *
 * @param pendientes lo que queda sin resolver. Mergear con cosas pendientes se puede, pero tiene
 *   que ser una decisión y no un descuido, así que se enumera acá y no sólo en el botón.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MergeDialog(
    pr: PullRequest,
    strategy: MergeStrategy,
    onStrategy: (MergeStrategy) -> Unit,
    pendientes: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (MergeChoice) -> Unit,
) {
    // El formato es el que usa Bitbucket al mergear, para que la historia se lea igual venga de
    // donde venga: "Merged in <rama> (pull request #N)".
    val porDefecto = remember(pr.id, pr.sourceBranch) {
        "Merged in ${pr.sourceBranch} (pull request #${pr.id})\n\n${pr.title}"
    }
    var mensaje by remember(pr.id) { mutableStateOf(porDefecto) }
    var borrarRama by remember(pr.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(io.acr.i18n.t("merge.confirmTitle", pr.id)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Campo(io.acr.i18n.t("merge.source"), pr.sourceBranch)
                Campo(io.acr.i18n.t("merge.destination"), pr.targetBranch)

                Spacer(Modifier.height(10.dp))
                Text(
                    io.acr.i18n.t("merge.strategy"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MergeStrategy.entries.forEach { e ->
                        FilterChip(
                            selected = strategy == e,
                            onClick = { onStrategy(e) },
                            label = { Text(io.acr.i18n.t(e.labelKey)) },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = mensaje,
                    onValueChange = { mensaje = it },
                    label = { Text(io.acr.i18n.t("merge.commitMessage")) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 160.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                        .copy(fontFamily = FontFamily.Monospace),
                )

                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = borrarRama, onCheckedChange = { borrarRama = it })
                    Text(
                        io.acr.i18n.t("merge.closeBranch"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (pendientes.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        io.acr.i18n.t("merge.skipping"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    pendientes.forEach {
                        Text(
                            "· $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    io.acr.i18n.t("merge.confirmBody", pr.sourceBranch, pr.targetBranch),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = mensaje.isNotBlank(),
                onClick = { onConfirm(MergeChoice(mensaje.trim(), borrarRama, strategy)) },
            ) { Text(io.acr.i18n.t("merge.confirm")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(io.acr.i18n.t("common.cancel")) }
        },
    )
}

/** Una fila etiqueta/valor, como las que muestra Bitbucket arriba del formulario. */
@Composable
private fun Campo(etiqueta: String, valor: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            etiqueta,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            valor,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}
