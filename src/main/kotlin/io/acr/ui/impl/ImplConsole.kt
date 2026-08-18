package io.acr.ui.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.acr.AppContext
import io.acr.forge.RepoRecord
import io.acr.i18n.t
import io.acr.impl.ImplTask
import io.acr.impl.TaskStatus

/**
 * La consola de la implementación: darle trabajo mientras corre.
 *
 * El módulo es autónomo y ese sigue siendo el punto, pero autónomo no quiere decir sordo. Mirando
 * el resultado uno ve cosas que el plan no podía ver —una tarea que resolvió algo de una forma que
 * no sirve, un detalle que faltaba en las specs— y hasta ahora la única salida era frenar todo,
 * editar, replanificar y volver a arrancar. Para una corrección de dos líneas eso es tirar media
 * hora de trabajo en curso.
 *
 * Lo que se escribe acá entra como una tarea más. Con dos diferencias que importan:
 *
 * - **Puede pasar al frente.** Una corrección existe para atenderse antes que lo que quedaba; si
 *   tuviera que esperar su turno al final de la fila, llegaría cuando ya no sirve.
 * - **Queda marcada como escrita a mano**, y quien la ejecuta lo sabe: manda sobre el plan, y suele
 *   ser una corrección de algo que ya está escrito, así que lo primero es ir a mirarlo.
 *
 * No mata lo que está corriendo. Una tarea a mitad de camino tiene un proceso escribiendo archivos,
 * y cortarlo para adelantar otra deja el árbol a medias: la urgente entra en la próxima vuelta, que
 * es en cuanto termine la que está. Para algo que no puede esperar eso, está el botón de frenar.
 */
@Composable
fun ImplConsole(
    ctx: AppContext,
    implId: String,
    repos: List<RepoRecord>,
    tareas: List<ImplTask>,
    onChange: () -> Unit,
) {
    var texto by remember(implId) { mutableStateOf("") }
    var urgente by remember(implId) { mutableStateOf(true) }
    var repoElegido by remember(implId) { mutableStateOf(repos.firstOrNull()?.id) }
    var menuAbierto by remember(implId) { mutableStateOf(false) }

    // Lo que ya se pidió por acá, con en qué quedó. Es el historial de la consola: sin él, escribir
    // una instrucción y verla desaparecer en una lista de veinticinco tareas se siente como hablarle
    // a un pozo.
    val mias = tareas.filter { it.fromUser }.sortedByDescending { it.seq }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("impl.console"), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(10.dp))
            Text(
                t("impl.consoleNote"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))

        if (mias.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                mias.forEach { t2 ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(
                            marcaDe(t2.status),
                            color = StatusColors.of(t2.status),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(18.dp),
                        )
                        Text(
                            "#${t2.seq}",
                            style = MaterialTheme.typography.labelSmall
                                .copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(34.dp),
                        )
                        Text(
                            t2.title,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                        )
                        if (t2.priority > 0 && t2.status == TaskStatus.PENDING) {
                            Text(
                                t("impl.consoleNext"),
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusColors.NEEDS_HUMAN,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = texto,
            onValueChange = { texto = it },
            label = { Text(t("impl.consoleInput")) },
            placeholder = { Text(t("impl.consolePlaceholder")) },
            minLines = 2,
            maxLines = 6,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = texto.isNotBlank() && repoElegido != null,
                onClick = {
                    val limpio = texto.trim()
                    ctx.impls.addUserTask(
                        implId = implId,
                        repoId = repoElegido,
                        // El título es la primera línea; el detalle, todo. Una instrucción de un
                        // párrafo no entra en una fila de tabla, y recortarla ahí perdería
                        // justamente lo que la hace específica.
                        title = limpio.lineSequence().first().take(120),
                        detail = limpio,
                        urgent = urgente,
                    )
                    texto = ""
                    onChange()
                },
            ) { Text(t("impl.consoleSend")) }

            FilterChip(
                selected = urgente,
                onClick = { urgente = !urgente },
                label = { Text(t("impl.consoleUrgent")) },
            )

            // Sólo cuando hay más de uno: preguntar en qué repositorio va algo cuando hay uno solo
            // es pedir que alguien conteste una pregunta que no existe.
            if (repos.size > 1) {
                Box {
                    TextButton(onClick = { menuAbierto = true }) {
                        Text(repos.firstOrNull { it.id == repoElegido }?.name ?: t("impl.consoleRepo"))
                    }
                    DropdownMenu(expanded = menuAbierto, onDismissRequest = { menuAbierto = false }) {
                        repos.forEach { r ->
                            DropdownMenuItem(
                                text = { Text(r.name) },
                                onClick = { repoElegido = r.id; menuAbierto = false },
                            )
                        }
                    }
                }
            }

            Text(
                if (urgente) t("impl.consoleUrgentNote") else t("impl.consoleQueuedNote"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Box(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box { content() }
}
