package io.acr.ui.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import io.acr.impl.ImplTask
import io.acr.impl.Implementation
import io.acr.impl.TaskStatus
import kotlinx.coroutines.launch

/**
 * Revisar el plan antes de dejarlo correr.
 *
 * El módulo es autónomo y ese sigue siendo el punto: no hay que aprobar nada para que arranque. Pero
 * autónomo no quiere decir que el primer plan sea el bueno, y hasta ahora la única salida ante un
 * plan flojo era dejarlo correr y arreglar después, o borrar la implementación y empezar de nuevo
 * perdiendo las specs cargadas.
 *
 * La guía se escribe en una línea —"separá la tarea 4", "falta la migración"— y se le da al modelo
 * junto con el plan actual. Revisar y rehacer no dan lo mismo: desde cero se pierden las decisiones
 * de orden que ya estaban bien y aparecen otras distintas, y entonces no hay forma de saber si la
 * revisión mejoró algo o sólo barajó de nuevo.
 */
@Composable
fun PlanReview(
    ctx: AppContext,
    impl: Implementation,
    tasks: List<ImplTask>,
    repos: List<RepoRecord>,
    running: Boolean,
    onDone: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var guia by remember(impl.id) { mutableStateOf(impl.reviewGuidance.orEmpty()) }
    var verPrompt by remember(impl.id) { mutableStateOf(false) }
    var confirmando by remember(impl.id) { mutableStateOf(false) }
    var revisando by remember(impl.id) { mutableStateOf(false) }

    // Replanificar reemplaza las tareas, así que el registro de las que ya corrieron se pierde. Los
    // commits siguen en la rama —el trabajo no se va— pero saber qué tarea los hizo, cuánto tardó y
    // qué tocó, sí. Por eso se avisa con el número y se pide confirmar en vez de deshacer solo.
    val hechas = tasks.count { it.status == TaskStatus.DONE }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("impl.review"), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(10.dp))
            Text(
                t("impl.reviewNote"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { verPrompt = !verPrompt }) {
                Text((if (verPrompt) "▾  " else "▸  ") + t("impl.reviewPrompt"))
            }
        }

        // Qué se le pide al modelo por defecto. Está a la vista para no escribir una guía que
        // repita lo que ya está: pedir "tareas más chicas" cuando el prompt ya lo dice gasta una
        // corrida entera en nada.
        if (verPrompt) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                SelectionContainer {
                    Text(
                        io.acr.impl.ImplPrompt.REVIEW_RULES,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        OutlinedTextField(
            value = guia,
            onValueChange = { guia = it; confirmando = false },
            label = { Text(t("impl.reviewGuidance")) },
            placeholder = { Text(t("impl.reviewPlaceholder")) },
            minLines = 2,
            maxLines = 6,
            enabled = !running && !revisando,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = !running && !revisando && guia.isNotBlank() && repos.isNotEmpty(),
                onClick = {
                    if (hechas > 0 && !confirmando) {
                        confirmando = true
                    } else {
                        confirmando = false
                        revisando = true
                        scope.launch {
                            ctx.implEngine.plan(repos, impl.id, guia)
                            revisando = false
                            onDone()
                        }
                    }
                },
            ) {
                Text(if (confirmando) t("impl.reviewConfirm") else t("impl.reviewGo"))
            }
            if (revisando) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
            if (confirmando) {
                Text(
                    t("impl.reviewLoses", hechas),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { confirmando = false }) { Text(t("common.cancel")) }
            } else if (running) {
                Text(
                    t("impl.reviewWhileRunning"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
