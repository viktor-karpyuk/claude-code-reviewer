package io.acr.ui.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import io.acr.i18n.t
import io.acr.impl.ImplStatus
import io.acr.impl.Implementation
import io.acr.impl.Progress
import io.acr.impl.progressOf

/**
 * El módulo de implementaciones: de las specs al código, sin supervisión.
 *
 * Lo que la pantalla tiene que contestar mientras corre es "¿va bien y cuánto falta?", y eso no lo
 * dice una barra sola: dice cuántas tareas hay, cuál corre, cuánto se estimó y cuánto se está
 * desviando de esa estimación. Un porcentaje sin el desvío parece información y no lo es, porque
 * la estimación inicial es una apuesta de quien planificó.
 */
@Composable
fun ImplPanel(ctx: AppContext, repos: List<io.acr.forge.RepoRecord>) {
    var version by remember { mutableStateOf(0) }
    var abierta by remember { mutableStateOf<String?>(null) }
    var creando by remember { mutableStateOf(false) }

    val lista = io.acr.ui.dbState(version, initial = emptyList<Implementation>()) { ctx.impls.list() }

    abierta?.let { id ->
        ImplDetail(ctx, repos, id) { abierta = null; version++ }
        return
    }

    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t("impl.title"), style = MaterialTheme.typography.titleMedium)
                Text(
                    t("impl.note"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(enabled = repos.isNotEmpty(), onClick = { creando = true }) { Text(t("impl.new")) }
        }

        Spacer(Modifier.height(16.dp))
        if (lista.isEmpty()) {
            Text(
                t("impl.empty"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        lista.forEach { impl ->
            val tareas = io.acr.ui.dbState(impl.id, version, initial = emptyList<io.acr.impl.ImplTask>()) {
                ctx.impls.tasks(impl.id)
            }
            val avance = remember(tareas) { progressOf(tareas) }
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { abierta = impl.id }
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(impl.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    EstadoBadge(impl.status)
                }
                Text(
                    listOfNotNull(repos.firstOrNull { it.id == impl.repoId }?.name, impl.branch)
                        .joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (avance.total > 0) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { avance.done.toFloat() / avance.total },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t("impl.progress", avance.done, avance.total) + tiempo(avance),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (creando) {
            NewImplDialog(ctx, repos, { creando = false }) { creando = false; version++ }
        }
    }
}

/**
 * El texto de tiempo: cuánto falta y cuánto se está desviando lo real de lo estimado.
 *
 * El desvío aparece recién con tareas terminadas. Antes de eso sería siempre 1,0 y mostraría una
 * precisión que no existe.
 */
@Composable
fun tiempo(p: Progress): String {
    if (p.total == 0) return ""
    val falta = if (p.remainingMin > 0) "  ·  " + t("impl.remaining", p.remainingMin.toInt()) else ""
    val desvio = p.drift?.takeIf { p.done > 0 }?.let { "  ·  " + t("impl.drift", "%.1f".format(it)) }.orEmpty()
    return falta + desvio
}

@Composable
fun EstadoBadge(s: ImplStatus) {
    val (texto, color) = when (s) {
        ImplStatus.DRAFT -> t("impl.st.draft") to MaterialTheme.colorScheme.onSurfaceVariant
        ImplStatus.PLANNING -> t("impl.st.planning") to MaterialTheme.colorScheme.primary
        ImplStatus.PLANNED -> t("impl.st.planned") to MaterialTheme.colorScheme.primary
        ImplStatus.RUNNING -> t("impl.st.running") to MaterialTheme.colorScheme.primary
        ImplStatus.DONE -> t("impl.st.done") to io.acr.ui.stats.ChartColors.added
        ImplStatus.FAILED -> t("impl.st.failed") to MaterialTheme.colorScheme.error
        ImplStatus.STOPPED -> t("impl.st.stopped") to MaterialTheme.colorScheme.onSurfaceVariant
        ImplStatus.AWAITING -> t("impl.st.awaiting") to io.acr.ui.stats.ChartColors.major
    }
    Text(texto, style = MaterialTheme.typography.labelSmall, color = color)
}
