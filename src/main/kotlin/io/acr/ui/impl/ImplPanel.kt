package io.acr.ui.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
    // Un tic mientras hay algo corriendo: sin esto la lista muestra el avance del momento en que
    // se abrió y no se entera de nada hasta que se navega a otro lado y se vuelve.
    var tic by remember { mutableStateOf(0) }

    val lista = io.acr.ui.dbState(version, initial = emptyList<Implementation>()) { ctx.impls.list() }
    // El avance de todas de una sola consulta. Antes se pedían las tareas de cada implementación
    // por separado dentro del bucle de dibujo: con veinte implementaciones eran veinte consultas
    // por recomposición.
    val avances = io.acr.ui.dbState(version, lista, tic, initial = emptyMap<String, Progress>()) {
        ctx.impls.progressOfAll()
    }

    abierta?.let { id ->
        ImplDetail(ctx, repos, id) { abierta = null; version++ }
        return
    }

    // Lo que se mira al abrir: qué hay corriendo ahora y qué está esperando algo mío. Una lista
    // de tarjetas sin eso obliga a abrir una por una para descubrir cuál se frenó.
    val enCurso = lista.filter { it.status == ImplStatus.RUNNING || it.status == ImplStatus.PLANNING }
    androidx.compose.runtime.LaunchedEffect(enCurso.isNotEmpty()) {
        while (enCurso.isNotEmpty()) {
            kotlinx.coroutines.delay(2_000)
            tic++
        }
    }
    val esperando = lista.filter { it.status == ImplStatus.AWAITING }
    val pendientes = io.acr.ui.dbState(version, lista, initial = 0) {
        lista.sumOf { i -> ctx.impls.questions(i.id).count { it.answer.isNullOrBlank() } }
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

        if (lista.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kpi(t("impl.kpiRunning"), enCurso.size.toString(), enCurso.isNotEmpty())
                Kpi(t("impl.kpiWaiting"), pendientes.toString(), pendientes > 0)
                Kpi(t("impl.kpiDone"), lista.count { it.status == ImplStatus.DONE }.toString(), false)
                Kpi(
                    t("impl.kpiCost"),
                    "$" + "%.0f".format(lista.sumOf { it.costUsd ?: 0.0 }),
                    false,
                )
            }

            // Las decisiones pendientes van arriba y con nombre: son lo único que frena, y si hay
            // que entrar a cada implementación para encontrarlas, la corrida queda esperando.
            if (esperando.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    t("impl.waitingOn", esperando.joinToString(", ") { it.title }),
                    style = MaterialTheme.typography.bodySmall,
                    color = io.acr.ui.stats.ChartColors.major,
                )
            }
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
            val avance = avances[impl.id] ?: Progress(0, 0, 0, 0, 0, 0.0, 0.0, null)
            val suyos = io.acr.ui.dbState(impl.id, version, initial = emptyList<io.acr.impl.ImplRepo>()) {
                ctx.impls.reposOf(impl.id)
            }
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { abierta = impl.id }
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(impl.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (impl.status == ImplStatus.RUNNING || impl.status == ImplStatus.PLANNING) {
                        CircularProgressIndicator(Modifier.height(12.dp).width(12.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    EstadoBadge(impl.status)
                }
                Text(
                    listOfNotNull(
                        suyos.mapNotNull { r -> repos.firstOrNull { it.id == r.repoId }?.name }
                            .joinToString(" + ").takeIf { it.isNotBlank() },
                        impl.branch,
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (avance.total > 0) {
                    Spacer(Modifier.height(6.dp))
                    val objetivo = avance.done.toFloat() / avance.total
                    val animado by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = objetivo,
                        animationSpec = androidx.compose.animation.core.tween(600),
                        label = "avance-${impl.id}",
                    )
                    LinearProgressIndicator(
                        progress = { animado },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t("impl.progress", avance.done, avance.total) + tiempo(avance) +
                            (if (avance.failed > 0) "  ·  " + t("impl.failedN", avance.failed) else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (avance.failed > 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (creando) {
            NewImplDialog(ctx, repos, { creando = false }) { creando = false; version++ }
        }
    }
}

/** Una cifra del encabezado. Se destaca sólo si pide atención: si todo resalta, nada resalta. */
@Composable
private fun Kpi(titulo: String, valor: String, alerta: Boolean) {
    Column(
        Modifier.width(150.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
    ) {
        Text(
            valor,
            style = MaterialTheme.typography.titleMedium,
            color = if (alerta) io.acr.ui.stats.ChartColors.major else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            titulo,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
