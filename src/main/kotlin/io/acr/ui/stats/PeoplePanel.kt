package io.acr.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import io.acr.stats.CollectProgress
import io.acr.stats.IdentityKind
import io.acr.stats.Person
import io.acr.stats.mergeSuggestions
import io.acr.ui.PersonAvatar
import io.acr.ui.StatusBadge
import kotlinx.coroutines.launch

/**
 * Quién es quién, antes de mostrar un solo número.
 *
 * No es una pantalla de estadísticas: es la que las hace posibles. Medido sobre los siete
 * repositorios conectados, la misma persona aparece hasta con dos nombres y dos emails —"Viktor K"
 * con 223 commits y "Viktor Karpyuk" con 26 son la misma—. Agrupar por email parte a esa persona
 * en dos; agrupar por nombre parte a quien commitea con y sin tilde. Cualquier número calculado
 * antes de resolver esto está mal, y peor: parece bien.
 */
/**
 * La sección de Estadísticas, con su portada y sus vistas.
 *
 * Arranca en una portada y no directo en una tabla a propósito: los números de acá no existen
 * hasta que se lee el historial y se trae el de pull requests, y una tabla vacía sin explicación
 * parece una función rota. La portada dice qué hay cargado, qué falta y con qué botón se
 * consigue.
 */
@Composable
fun StatsSection(ctx: AppContext) {
    var vista by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        androidx.compose.material3.TabRow(selectedTabIndex = vista) {
            listOf("stats.tabHome", "people.tabIdentities", "people.tabTeam", "people.tabReview")
                .forEachIndexed { i, clave ->
                    androidx.compose.material3.Tab(
                        selected = vista == i,
                        onClick = { vista = i },
                        text = { Text(t(clave)) },
                    )
                }
        }
        when (vista) {
            0 -> StatsHome(ctx) { vista = it }
            1 -> PeoplePanel(ctx)
            2 -> TeamPanel(ctx)
            else -> ReviewStatsPanel(ctx)
        }
    }
}

/**
 * Portada de Estadísticas: en qué estado está el equipo y si se puede confiar en estos números.
 *
 * El orden responde a las tres preguntas que uno tiene al abrir esto, y en ese orden:
 *
 * 1. **¿Puedo creerle a estos números?** Va primero porque si la respuesta es no, el resto sobra.
 *    Acá viven la cobertura, las identidades sin revisar y el aviso de commits atípicos.
 * 2. **¿Cómo se reparte el trabajo?** El anillo de volumen, calculado sin las importaciones.
 * 3. **¿Qué encontró la revisión, y qué quedó sin cerrar?**
 *
 * Más la actividad por trimestre, que es lo único que muestra una tendencia en vez de una foto.
 */
@Composable
private fun StatsHome(ctx: AppContext, irA: (Int) -> Unit) {
    val personas = io.acr.ui.dbState(initial = emptyList<Person>()) { ctx.persons.all() }
    val commits = io.acr.ui.dbState(initial = 0) { ctx.commitStats.countAll() }
    val prs = io.acr.ui.dbState(initial = 0) { ctx.prStats.count() }
    val sinConfirmar = io.acr.ui.dbState(initial = false) { ctx.persons.hasUnconfirmed() }
    val cobertura = io.acr.ui.dbState(initial = 0 to 0) { ctx.reviewStats.coverage("1970-01-01", "2999-12-31") }
    val trimestres = io.acr.ui.dbState(initial = emptyList<io.acr.data.QuarterActivity>()) {
        ctx.commitStats.activityByQuarter()
    }
    val bulk = io.acr.ui.dbState(initial = Triple(0, 0L, 0L)) {
        ctx.commitStats.bulkShare("1970-01-01", "2999-12-31")
    }
    // El reparto se calcula SIN las importaciones: con ellas describe unos pocos commits de
    // mover archivos y no el trabajo. Medido acá: 40 commits llevaban el 69,5% de las líneas.
    val volumen = io.acr.ui.dbState(initial = emptyMap<String, io.acr.data.Volume>()) {
        ctx.commitStats.volumeByPerson(
            "1970-01-01", "2999-12-31",
            maxLines = io.acr.data.CommitStatRepository.BULK_COMMIT_LINES,
        )
    }
    val gravedad = io.acr.ui.dbState(initial = emptyMap<String, io.acr.data.SeverityCount>()) {
        ctx.reviewStats.findingsByPrAuthor("1970-01-01", "2999-12-31")
    }
    val hallazgos = io.acr.ui.dbState(initial = Triple(0, 0, 0)) { ctx.findings.severityTotals() }
    val sinVerificar = io.acr.ui.dbState(initial = 0 to 0) { ctx.findings.verificationTotals() }

    val activas = personas.filter { !it.isBot && !it.archived }
    val porPersona = remember(personas, volumen) {
        activas.mapNotNull { p -> volumen[p.id]?.let { p.displayName to it.touched.toDouble() } }
            .sortedByDescending { it.second }
    }

    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
        Text(t("stats.title"), style = MaterialTheme.typography.titleMedium)
        Text(
            t("stats.note"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tarjeta(t("stats.people"), activas.size.toString(), t("stats.peopleGo")) { irA(1) }
            Tarjeta(t("stats.commits"), "%,d".format(commits), t("stats.commitsGo")) { irA(2) }
            Tarjeta(t("stats.prs"), prs.toString(), t("stats.prsGo")) { irA(3) }
            Tarjeta(t("stats.findings"), "%,d".format(hallazgos.first + hallazgos.second + hallazgos.third), t("stats.findingsGo")) { irA(3) }
        }

        // --- 1. ¿Se puede confiar en esto? ---
        Spacer(Modifier.height(20.dp))
        Text(t("stats.trust"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        if (commits == 0) Aviso(t("stats.needCommits"), t("stats.goIdentities"), true) { irA(1) }
        if (prs == 0) Aviso(t("stats.needPrs"), t("stats.goReview"), true) { irA(3) }
        if (sinConfirmar && commits > 0) Aviso(t("stats.needConfirm"), t("stats.goIdentities"), true) { irA(1) }
        if (cobertura.first > 0) {
            Aviso(t("stats.coverage", cobertura.second, cobertura.first), null, cobertura.second < cobertura.first) {}
        }
        // Los commits gigantes distorsionan el reparto más que cualquier otra cosa. Decirlo acá,
        // con el número, es lo que evita leer el anillo como si describiera el trabajo.
        if (bulk.first > 0 && bulk.third > 0) {
            Aviso(
                t("stats.bulk", bulk.first, Math.round(100.0 * bulk.second / bulk.third).toInt()),
                null,
                true,
            ) {}
        }

        // --- 2. ¿Cómo se reparte el trabajo? ---
        if (porPersona.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(t("stats.split"), style = MaterialTheme.typography.titleSmall)
            Text(
                t("stats.splitNote", io.acr.data.CommitStatRepository.BULK_COMMIT_LINES),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            // Seis porciones y el resto agrupado: con trece, ninguna se distingue de la de al lado.
            val top = porPersona.take(6)
            val resto = porPersona.drop(6).sumOf { it.second }
            val trozos = top.mapIndexed { i, (n, v) -> Segment(v, SLICE_COLORS[i % SLICE_COLORS.size], n) } +
                if (resto > 0) listOf(Segment(resto, SLICE_COLORS[6], t("stats.others", porPersona.size - 6))) else emptyList()
            Row(verticalAlignment = Alignment.CenterVertically) {
                DonutChart(
                    trozos,
                    centerValue = "%,.0f".format(porPersona.sumOf { it.second }),
                    centerLabel = t("stats.linesTouched"),
                )
                Spacer(Modifier.width(16.dp))
                DonutLegend(trozos)
            }
        }

        // --- 3. ¿Qué encontró la revisión? ---
        val totalHallazgos = hallazgos.first + hallazgos.second + hallazgos.third
        if (totalHallazgos > 0) {
            Spacer(Modifier.height(20.dp))
            Text(t("stats.review"), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            val porGravedad = listOf(
                Segment(hallazgos.first.toDouble(), ChartColors.blocker, t("rstats.blocker")),
                Segment(hallazgos.second.toDouble(), ChartColors.major, t("rstats.major")),
                Segment(hallazgos.third.toDouble(), ChartColors.minor, t("rstats.minor")),
            ).filter { it.value > 0 }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DonutChart(porGravedad, totalHallazgos.toString(), t("stats.findingsCenter"))
                Spacer(Modifier.width(16.dp))
                Column {
                    DonutLegend(porGravedad)
                    Spacer(Modifier.height(8.dp))
                    // Lo que quedó sin mirar es tan informativo como lo que se encontró: son
                    // observaciones publicadas de las que nadie sabe si se atendieron.
                    Text(
                        t("stats.unverified", sinVerificar.first, sinVerificar.second),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (sinVerificar.first > sinVerificar.second / 2)
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // --- Tendencia ---
        if (trimestres.size > 1) {
            Spacer(Modifier.height(20.dp))
            Text(t("stats.trend"), style = MaterialTheme.typography.titleSmall)
            Text(
                t("stats.trendNote"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            MiniBars(
                values = trimestres.map { it.commits.toDouble() },
                labels = trimestres.map { it.label.substringAfter('-') + " " + it.label.take(4).drop(2) },
                modifier = Modifier.width(460.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                trimestres.joinToString("   ") { "${it.label.substringAfter('-')}: ${it.commits}c · ${it.people}p" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            t("stats.disclaimer"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Una línea de estado, con su acción si la tiene. Rojo sólo cuando algo falta o distorsiona. */
@Composable
private fun Aviso(texto: String, accion: String?, alerta: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            texto,
            style = MaterialTheme.typography.bodySmall,
            color = if (alerta) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (accion != null) TextButton(onClick = onClick) { Text(accion) }
    }
}

@Composable
private fun Tarjeta(titulo: String, valor: String, accion: String, onClick: () -> Unit) {
    Column(
        Modifier.width(200.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Text(valor, style = MaterialTheme.typography.headlineSmall)
        Text(titulo, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        Text(
            accion,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeoplePanel(ctx: AppContext) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    var progreso by remember { mutableStateOf<CollectProgress?>(null) }
    var recolectando by remember { mutableStateOf(false) }
    var seleccion by remember { mutableStateOf<String?>(null) }

    val personas = io.acr.ui.dbState(version, initial = emptyList<Person>()) { ctx.persons.all() }
    val repos = io.acr.ui.dbState(version, initial = emptyList<io.acr.forge.RepoRecord>()) { ctx.repos.list() }
    val provisional = io.acr.ui.dbState(version, initial = false) { ctx.persons.hasUnconfirmed() }
    val sugerencias = remember(personas) { mergeSuggestions(personas) }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(t("people.title"), style = MaterialTheme.typography.titleMedium)
        Text(
            t("people.note"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Leer es incremental cuando ya se leyó antes: continúa desde el commit anotado en
            // la corrida anterior en vez de releer todo. La primera vez toma un año, que es lo
            // que alcanza para ver si esto sirve; traer todo es una decisión aparte y explícita.
            Button(
                enabled = !recolectando && repos.isNotEmpty(),
                onClick = {
                    recolectando = true
                    scope.launch {
                        repos.forEach { r ->
                            progreso = ctx.statsCollector.collect(r, since = "12 months ago") { progreso = it }
                        }
                        recolectando = false
                        version++
                    }
                },
            ) { Text(t("people.collect")) }
            OutlinedButton(
                enabled = !recolectando && repos.isNotEmpty(),
                onClick = {
                    recolectando = true
                    scope.launch {
                        // Sin `since`: el historial entero. Puede ser de años y miles de commits,
                        // por eso es un botón aparte y no el comportamiento por defecto.
                        repos.forEach { r ->
                            progreso = ctx.statsCollector.collect(r, since = null) { progreso = it }
                        }
                        recolectando = false
                        version++
                    }
                },
            ) { Text(t("people.collectAll")) }

            if (recolectando) {
                CircularProgressIndicator(Modifier.height(20.dp).width(20.dp))
                progreso?.let {
                    Text(
                        t("people.progress", it.repoName, it.commits),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            if (personas.isNotEmpty() && provisional) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { ctx.persons.confirmAll(); version++ }) {
                    Text(t("people.confirmAll"))
                }
            }
        }

        progreso?.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        // "12 commits" y "12 commits nuevos" no significan lo mismo: sin decir el modo, una
        // corrida incremental parece una recolección que perdió casi todo.
        progreso?.takeIf { it.done && it.error == null }?.let {
            Text(
                if (it.incremental) t("people.doneIncremental", it.commits) else t("people.doneFull", it.commits),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // El aviso de datos provisionales. Está acá arriba y no en un tooltip: un número con
        // asterisco es preferible a uno limpio y equivocado, pero sólo si el asterisco se ve.
        if (provisional && personas.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                t("people.provisional"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (sugerencias.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(t("people.suggestions"), style = MaterialTheme.typography.titleSmall)
            Text(
                t("people.suggestionsNote"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sugerencias.forEach { (a, b) ->
                val pa = personas.firstOrNull { it.id == a }
                val pb = personas.firstOrNull { it.id == b }
                if (pa != null && pb != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PersonAvatar(pa.displayName, size = 28.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(pa.displayName, style = MaterialTheme.typography.bodySmall)
                        Text("  ·  ", style = MaterialTheme.typography.bodySmall)
                        PersonAvatar(pb.displayName, size = 28.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(pb.displayName, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        // Se conserva la que más identidades tiene: suele ser la cuenta principal.
                        val (queda, absorbe) =
                            if (pa.identities.size >= pb.identities.size) pa to pb else pb to pa
                        TextButton(onClick = {
                            ctx.persons.merge(queda.id, absorbe.id)
                            version++
                        }) { Text(t("people.merge")) }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        if (personas.isEmpty()) {
            Text(
                t("people.empty"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Text(t("people.count", personas.size), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))

        personas.forEach { p ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PersonAvatar(p.displayName, size = 36.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.displayName, style = MaterialTheme.typography.bodyMedium)
                            if (p.isBot) {
                                Spacer(Modifier.width(6.dp))
                                StatusBadge(t("people.bot"))
                            }
                            if (p.archived) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    t("people.archived"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (p.autoMerged) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    t("people.autoMerged"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            p.identities.sortedBy { it.kind.name }.forEach { ident ->
                                Text(
                                    ident.value,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = if (ident.kind == IdentityKind.GIT_EMAIL) FontFamily.Monospace
                                        else FontFamily.Default,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // Marcar como bot lo saca de todos los agregados. El mecanismo existe aunque
                    // hoy no haya ninguno en estos repositorios: en cuanto entre un dependabot,
                    // sus cientos de commits taparían a las personas.
                    // Archivar en vez de borrar: borrar dejaría los commits sin persona y haría
                    // bajar los totales de trimestres viejos donde esa persona sí trabajó.
                    TextButton(onClick = { ctx.persons.setArchived(p.id, !p.archived); version++ }) {
                        Text(if (p.archived) t("people.unarchive") else t("people.archive"))
                    }
                    TextButton(onClick = { ctx.persons.setBot(p.id, !p.isBot); version++ }) {
                        Text(if (p.isBot) t("people.unbot") else t("people.markBot"))
                    }
                    TextButton(onClick = { seleccion = if (seleccion == p.id) null else p.id }) {
                        Text(if (seleccion == p.id) t("people.hide") else t("people.identities"))
                    }
                }

                if (seleccion == p.id) {
                    val ids = ctx.persons.identityIds(p.id)
                    Column(Modifier.padding(start = 44.dp, top = 4.dp)) {
                        ids.forEach { (idId, ident) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AssistChip(onClick = {}, label = { Text(ident.kind.name) })
                                Spacer(Modifier.width(6.dp))
                                Text(ident.value, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.weight(1f))
                                // Separar tiene que ser posible: la heurística une por nombre y
                                // dos personas distintas pueden llamarse igual. Si fusionar no se
                                // pudiera deshacer, nadie se animaría a fusionar.
                                if (ids.size > 1) {
                                    TextButton(onClick = {
                                        ctx.persons.split(idId, ident.value)
                                        seleccion = null
                                        version++
                                    }) { Text(t("people.split")) }
                                }
                            }
                        }
                        if (personas.size > 1) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                t("people.mergeInto"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                personas.filter { it.id != p.id }.forEach { otra ->
                                    OutlinedButton(onClick = {
                                        ctx.persons.merge(p.id, otra.id)
                                        seleccion = null
                                        version++
                                    }) {
                                        Text(otra.displayName, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
