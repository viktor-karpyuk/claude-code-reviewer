package io.acr.ui.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.HorizontalDivider
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
    var busqueda by remember { mutableStateOf("") }

    val todas = io.acr.ui.dbState(version, initial = emptyList<Implementation>()) { ctx.impls.list() }
    // El avance de todas de una sola consulta. Antes se pedían las tareas de cada implementación
    // por separado dentro del bucle de dibujo: con veinte implementaciones eran veinte consultas
    // por recomposición.
    // Se busca en memoria y no en SQL: son decenas de implementaciones, no miles, y filtrar acá
    // permite buscar también por el nombre del repositorio, que vive en otra tabla.
    val lista = remember(todas, busqueda, repos) {
        val q = busqueda.trim().lowercase()
        if (q.isBlank()) todas
        else todas.filter { i ->
            i.title.lowercase().contains(q) ||
                i.branch.orEmpty().lowercase().contains(q) ||
                repos.firstOrNull { it.id == i.repoId }?.name?.lowercase()?.contains(q) == true
        }
    }

    val avances = io.acr.ui.dbState(version, lista, tic, initial = emptyMap<String, Progress>()) {
        ctx.impls.progressOfAll()
    }

    // El alta es una pantalla y no un modal: son seis decisiones, cada una con su explicación, y
    // en un modal de 640 el botón de borrar un documento quedaba cortado en una pantalla de 14".
    if (creando) {
        ImplForm(
            ctx = ctx, repos = repos, impl = null,
            onBack = { creando = false },
            onSaved = { id -> creando = false; version++; abierta = id },
        )
        return
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
            // Sin condición: adentro del formulario se puede elegir una carpeta suelta, así que no
            // tener ningún repositorio conectado ya no impide implementar. Deshabilitar acá dejaba
            // sin salida justo al que todavía no conectó nada.
            Button(onClick = { creando = true }) { Text(t("impl.new")) }
        }

        // El buscador aparece recién cuando hay suficientes como para necesitarlo: con tres
        // implementaciones, un campo de búsqueda es un control que ocupa lugar y nunca se usa.
        if (todas.size > 4) {
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.OutlinedTextField(
                value = busqueda,
                onValueChange = { busqueda = it },
                label = { Text(t("impl.search")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (busqueda.isNotBlank()) {
                        androidx.compose.material3.TextButton(onClick = { busqueda = "" }) {
                            Text(t("common.cancel"))
                        }
                    }
                },
            )
            if (busqueda.isNotBlank()) {
                Text(
                    t("impl.searchResults", lista.size, todas.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                if (busqueda.isNotBlank()) t("impl.noMatches") else t("impl.empty"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Tabla y no tarjetas: con veinte implementaciones, las tarjetas obligan a scrollear
        // para comparar dos cosas que en una tabla están una debajo de la otra.
        val porPagina = 10
        var pagina by remember(busqueda) { mutableStateOf(0) }
        val paginas = maxOf(1, (lista.size + porPagina - 1) / porPagina)
        val visibles = lista.drop(pagina * porPagina).take(porPagina)

        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            ColHead(t("impl.thStatus"), 150.dp)
            Text(
                t("impl.thName"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            ColHead(t("impl.thRepos"), 200.dp)
            ColHead(t("impl.thBranch"), 170.dp)
            ColHead(t("impl.thWhere"), 150.dp)
            ColHead(t("impl.thProgress"), 190.dp)
        }
        HorizontalDivider()

        visibles.forEach { impl ->
            val avance = avances[impl.id] ?: Progress(0, 0, 0, 0, 0, 0.0, 0.0, null)
            val suyos = io.acr.ui.dbState(impl.id, version, initial = emptyList<io.acr.impl.ImplRepo>()) {
                ctx.impls.reposOf(impl.id)
            }
            Row(
                Modifier.fillMaxWidth().clickable { abierta = impl.id }.padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.width(150.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (impl.status == ImplStatus.RUNNING || impl.status == ImplStatus.PLANNING) {
                        CircularProgressIndicator(Modifier.height(11.dp).width(11.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    EstadoBadge(impl.status)
                }
                Text(
                    impl.title,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text(
                    suyos.mapNotNull { r -> repos.firstOrNull { it.id == r.repoId }?.name }
                        .joinToString(" + "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(200.dp),
                    maxLines = 1,
                )
                Text(
                    impl.branch ?: "—",
                    style = MaterialTheme.typography.labelSmall
                        .copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(170.dp),
                    maxLines = 1,
                )
                // Dónde escribe: en su taller o directo en los clones. Es la diferencia entre "esto
                // puede estar tocando mi árbol de trabajo ahora mismo" y "no", y no estaba a la
                // vista en ningún lado de la lista.
                //
                // Y si el taller ya no está en disco, se dice: significa que el trabajo se devolvió
                // y se limpió, que es distinto de nunca haber tenido uno.
                val taller = io.acr.ui.dbState(impl.id, version, initial = null as Boolean?) {
                    if (!impl.useWorkspace) null else ctx.workspaces.dirOf(impl.id).isDirectory
                }
                Text(
                    when {
                        !impl.useWorkspace -> t("impl.whereClones")
                        taller == true -> t("impl.whereWorkspace")
                        else -> t("impl.whereDone")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (impl.useWorkspace && taller == true) StatusColors.RUNNING
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(150.dp),
                    maxLines = 1,
                )
                Column(Modifier.width(190.dp)) {
                    if (avance.total > 0) {
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
                        Text(
                            t("impl.progress", avance.done, avance.total) +
                                (if (avance.failed > 0) "  ·  " + t("impl.failedN", avance.failed) else ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (avance.failed > 0) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "—",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider()
        }

        // El paginador aparece sólo si hay más de una página: un control que siempre dice "1 de 1"
        // ocupa lugar para no informar nada.
        if (paginas > 1) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.TextButton(
                    enabled = pagina > 0,
                    onClick = { pagina-- },
                ) { Text("‹ " + t("impl.prev")) }
                Text(
                    t("impl.page", pagina + 1, paginas),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.TextButton(
                    enabled = pagina < paginas - 1,
                    onClick = { pagina++ },
                ) { Text(t("impl.next") + " ›") }
            }
        }

    }
}

/** Encabezado de columna de la tabla. */
@Composable
private fun ColHead(texto: String, ancho: androidx.compose.ui.unit.Dp) {
    Text(
        texto,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(ancho),
    )
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
    val falta = if (p.remainingMin > 0) "  ·  " + t("impl.remaining", io.acr.impl.minutosLegibles(p.remainingMin)) else ""
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
