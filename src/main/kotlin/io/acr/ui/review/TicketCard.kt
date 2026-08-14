package io.acr.ui.review

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.acr.AppContext
import io.acr.i18n.t
import io.acr.jira.JiraIssue
import io.acr.ui.CollapsibleCard
import io.acr.ui.StatusBadge
import kotlinx.coroutines.launch

/**
 * Lo que pide el ticket, arriba del PR.
 *
 * Revisar sin esto es revisar sólo el código: se puede aprobar un cambio impecable que resuelve
 * otra cosa. Por eso la tarjeta va antes que los hallazgos y muestra la descripción, no sólo el
 * título — el título dice de qué habla y la descripción dice qué había que hacer.
 *
 * Plegable y plegada por defecto cuando ya se leyó: ocupa lugar y no hace falta tenerla abierta
 * todo el tiempo.
 */
@Composable
fun TicketCard(
    ctx: AppContext,
    repoId: String,
    prId: Long,
    branch: String?,
    title: String?,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var version by remember(repoId, prId) { mutableStateOf(0) }
    var trayendo by remember(repoId, prId) { mutableStateOf(false) }
    var error by remember(repoId, prId) { mutableStateOf<String?>(null) }

    // Las claves salen de la rama y del título; se guardan para que las estadísticas puedan cruzar
    // tickets con PRs sin volver a mirar el texto.
    val claves = remember(branch, title) { io.acr.jira.issueKeysFor(branch, title) }
    androidx.compose.runtime.LaunchedEffect(claves) {
        if (claves.isNotEmpty()) ctx.jira.link(repoId, prId, claves)
    }
    val tickets = io.acr.ui.dbState(repoId, prId, version, claves, initial = emptyList<JiraIssue>()) {
        ctx.jira.issuesOf(repoId, prId)
    }
    val configurado = remember(version) { ctx.jiraConfig().configured }

    if (claves.isEmpty()) return

    CollapsibleCard(
        title = t("jira.ticket") + "  " + claves.joinToString(" "),
        prefs = ctx.prefs,
        key = "ticket-$repoId-$prId",
        maxHeight = 260.dp,
        // Plegada de entrada: el ticket se lee una vez y después estorba.
        defaultCollapsed = tickets.isNotEmpty(),
    ) {
        if (!configurado) {
            Text(
                t("jira.notConfigured"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@CollapsibleCard
        }

        tickets.forEach { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    i.key,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.width(8.dp))
                if (i.status.isNotBlank()) StatusBadge(i.status)
                Spacer(Modifier.width(8.dp))
                Text(
                    listOfNotNull(i.type.takeIf { it.isNotBlank() }, i.assignee).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(i.url)) }
                }) { Text(t("jira.open")) }
            }
            Text(i.summary, style = MaterialTheme.typography.bodyMedium)
            if (i.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    i.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // Faltan los que todavía no se trajeron. Se piden a mano y no solos: es una llamada a un
        // servicio externo por PR, y hacerla sin que nadie la pida sorprende.
        val faltan = claves.filter { k -> tickets.none { it.key == k } }
        if (faltan.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = !trayendo,
                    onClick = {
                        trayendo = true
                        error = null
                        scope.launch {
                            faltan.forEach { k ->
                                ctx.jiraClient().issue(k)
                                    .onSuccess { it?.let { i -> ctx.jira.save(i) } }
                                    .onFailure { error = it.message }
                            }
                            trayendo = false
                            version++
                        }
                    },
                ) { Text(t("jira.fetch") + " (" + faltan.joinToString(", ") + ")") }
                error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
