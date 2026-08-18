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
import androidx.compose.material3.OutlinedButton
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
    var auditando by remember(impl.id) { mutableStateOf(false) }
    var revisando by remember(impl.id) { mutableStateOf(false) }

    // Qué se toca y qué no. Lo hecho y lo que está corriendo se quedan: lo primero tiene su código
    // commiteado y su registro es la única forma de saber qué lo produjo; lo segundo tiene un
    // proceso escribiendo archivos ahora mismo. Se reemplaza sólo lo que no empezó.
    val hechas = tasks.count { it.status == TaskStatus.DONE }
    val corriendoAhora = tasks.count { it.status == TaskStatus.RUNNING }
    val porEmpezar = tasks.count {
        it.status != TaskStatus.DONE && it.status != TaskStatus.RUNNING
    }

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

        // Cuántas veces se rehízo y cuándo. El contador dice si el plan es inestable —tres
        // replanificaciones seguidas son una señal de que el problema no está en el plan— y la
        // fecha dice si lo que uno mira es de antes o de después del último cambio.
        if (impl.replans > 0) {
            Text(
                t("impl.replanCount", impl.replans) +
                    impl.replannedAt?.let { "  ·  " + t("impl.replanLast", fechaCorta(it)) }.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            supportingText = {
                Text(
                    t("impl.reviewGuidanceNote"),
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            minLines = 2,
            maxLines = 6,
            enabled = !running && !revisando,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Auditar es lo que llena la guía sin tener que escribirla: va de los documentos al
            // plan, requisito por requisito, y deja anotado lo que falta. No cambia nada por su
            // cuenta —replanificar tira las tareas y eso no puede pasar solo—.
            io.acr.ui.InfoTip(t("impl.auditTip"), t("impl.auditTipOut")) {
            OutlinedButton(
                enabled = !running && !revisando && !auditando && repos.isNotEmpty() && tasks.isNotEmpty(),
                onClick = {
                    auditando = true
                    scope.launch {
                        ctx.implEngine.auditPlan(repos, impl.id)
                        auditando = false
                        guia = ctx.impls.get(impl.id)?.reviewGuidance.orEmpty()
                        onDone()
                    }
                },
            ) { Text(t("impl.audit")) }
            }
            if (auditando) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
            io.acr.ui.InfoTip(t("impl.replanTip"), t("impl.replanTipOut")) {
            Button(
                // Sin exigir guía: el prompt de revisión ya dice qué mirar, así que obligar a
                // escribir algo era pedir que alguien redacte lo que el sistema ya sabe pedir.
                enabled = !running && !revisando && !auditando && repos.isNotEmpty(),
                onClick = {
                    if (porEmpezar > 0 && !confirmando) {
                        confirmando = true
                    } else {
                        confirmando = false
                        revisando = true
                        scope.launch {
                            ctx.implEngine.plan(repos, impl.id, guia, revising = true)
                            revisando = false
                            onDone()
                        }
                    }
                },
            ) {
                Text(if (confirmando) t("impl.reviewConfirm") else t("impl.reviewGo"))
            }
            }
            if (revisando) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
            if (confirmando) {
                Text(
                    t("impl.reviewReplaces", porEmpezar, hechas + corriendoAhora),
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

/** Fecha corta y local: acá se mira de reojo, no se audita. */
private fun fechaCorta(iso: String): String = runCatching {
    java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm"))
}.getOrDefault(iso.take(16))
