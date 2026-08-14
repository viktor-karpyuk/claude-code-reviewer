package io.acr.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
 * La sección Personas, con sus dos vistas.
 *
 * Van juntas y en este orden a propósito: los números salen de las identidades, así que cuando
 * algo se ve raro en el equipo, la pantalla que lo explica está al lado y no en otro menú.
 */
@Composable
fun PeopleSection(ctx: AppContext) {
    var vista by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        androidx.compose.material3.TabRow(selectedTabIndex = vista) {
            androidx.compose.material3.Tab(
                selected = vista == 0,
                onClick = { vista = 0 },
                text = { Text(t("people.tabIdentities")) },
            )
            androidx.compose.material3.Tab(
                selected = vista == 1,
                onClick = { vista = 1 },
                text = { Text(t("people.tabTeam")) },
            )
            androidx.compose.material3.Tab(
                selected = vista == 2,
                onClick = { vista = 2 },
                text = { Text(t("people.tabReview")) },
            )
        }
        when (vista) {
            0 -> PeoplePanel(ctx)
            1 -> TeamPanel(ctx)
            else -> ReviewStatsPanel(ctx)
        }
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
            Button(
                enabled = !recolectando && repos.isNotEmpty(),
                onClick = {
                    recolectando = true
                    scope.launch {
                        // Un año y no todo el historial: la primera corrida sobre siete
                        // repositorios de años sería de miles de commits, y para ver si esto
                        // sirve alcanza con lo reciente. Traer todo es una decisión aparte.
                        repos.forEach { r ->
                            progreso = ctx.statsCollector.collect(r, since = "12 months ago") { progreso = it }
                        }
                        recolectando = false
                        version++
                    }
                },
            ) { Text(t("people.collect")) }

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
