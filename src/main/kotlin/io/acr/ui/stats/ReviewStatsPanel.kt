package io.acr.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.acr.AppContext
import io.acr.data.Participation
import io.acr.data.SeverityCount
import io.acr.i18n.t
import io.acr.stats.PeriodPreset
import io.acr.stats.Person
import io.acr.stats.personForDisplayName
import io.acr.stats.resolvePreset
import io.acr.ui.PersonAvatar

/**
 * Lo que sale de nuestras reviews: quién participó revisando y qué se le señaló a cada uno.
 *
 * Todo acá es de **cobertura parcial** —sólo existe para los pull requests que la app revisó o
 * sincronizó— y por eso la pantalla dice arriba sobre cuánto está calculando. Mezclar esto con el
 * volumen de git, que cubre todo lo commiteado, sin aclararlo sería mentir con números ciertos.
 *
 * La participación va primero y no al final a propósito: sin ella el módulo mide sólo a quien
 * escribe código y trata como invisible a quien revisa, que es la mitad del trabajo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewStatsPanel(ctx: AppContext) {
    var preset by remember { mutableStateOf(PeriodPreset.ALL) }
    val periodo = remember(preset) { resolvePreset(preset) }

    val personas = io.acr.ui.dbState(initial = emptyList<Person>()) { ctx.persons.all() }
    val comentarios = io.acr.ui.dbState(periodo, initial = emptyMap<String, Participation>()) {
        ctx.reviewStats.commentsByAuthor(periodo.from, periodo.to)
    }
    val hallazgos = io.acr.ui.dbState(periodo, initial = emptyMap<String, SeverityCount>()) {
        ctx.reviewStats.findingsByPrAuthor(periodo.from, periodo.to)
    }
    val cobertura = io.acr.ui.dbState(periodo, initial = 0 to 0) {
        ctx.reviewStats.coverage(periodo.from, periodo.to)
    }

    // Los nombres vienen del proveedor; se enganchan con las personas de git por nombre
    // normalizado. Lo que no engancha se muestra igual con su nombre crudo en vez de descartarse:
    // suele ser alguien que comenta y no commitea, y perderlo sería perder justo la participación.
    fun etiqueta(nombre: String): Pair<String, Boolean> {
        val p = personForDisplayName(nombre, personas)
        return (p?.displayName ?: nombre) to (p != null)
    }

    Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(t("rstats.title"), style = MaterialTheme.typography.titleMedium)
        Text(
            t("rstats.warning"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PeriodPreset.entries.forEach { p ->
                FilterChip(preset == p, { preset = p }, { Text(t(p.labelKey)) })
            }
        }

        Spacer(Modifier.height(10.dp))
        // La cobertura arriba y no al pie: una tabla que dice "3 bloqueantes" sin aclarar sobre
        // cuántos PRs se calculó invita a conclusiones que los datos no sostienen.
        Text(
            t("rstats.coverage", cobertura.second, cobertura.first),
            style = MaterialTheme.typography.labelMedium,
            color = if (cobertura.second < cobertura.first) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(14.dp))
        Text(t("rstats.participation"), style = MaterialTheme.typography.titleSmall)
        Text(
            t("rstats.participationNote"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))

        if (comentarios.isEmpty()) {
            Text(
                t("rstats.noComments"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Col(t("team.person"), 240.dp)
                Col(t("rstats.comments"), 110.dp)
                Col(t("rstats.onPrs"), 110.dp)
            }
            HorizontalDivider()
            comentarios.entries.sortedByDescending { it.value.comments }.forEach { (nombre, p) ->
                val (mostrar, enganchado) = etiqueta(nombre)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.width(240.dp), verticalAlignment = Alignment.CenterVertically) {
                        PersonAvatar(mostrar, size = 26.dp)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(mostrar, style = MaterialTheme.typography.bodySmall)
                            if (!enganchado) {
                                Text(
                                    t("rstats.notInGit"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Num(p.comments.toString(), 110.dp)
                    Num(p.prs.toString(), 110.dp)
                }
                HorizontalDivider()
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(t("rstats.received"), style = MaterialTheme.typography.titleSmall)
        Text(
            t("rstats.receivedNote"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))

        if (hallazgos.isEmpty()) {
            Text(
                t("rstats.noFindings"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Col(t("team.person"), 240.dp)
                Col(t("rstats.prs"), 70.dp)
                Col(t("rstats.blocker"), 100.dp)
                Col(t("rstats.major"), 100.dp)
                Col(t("rstats.minor"), 90.dp)
                Col(t("rstats.perPr"), 90.dp)
                Col(t("rstats.notFixed"), 130.dp)
            }
            HorizontalDivider()
            hallazgos.entries.sortedByDescending { it.value.total }.forEach { (nombre, s) ->
                val (mostrar, _) = etiqueta(nombre)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.width(240.dp), verticalAlignment = Alignment.CenterVertically) {
                        PersonAvatar(mostrar, size = 26.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(mostrar, style = MaterialTheme.typography.bodySmall)
                    }
                    Num(s.prs.toString(), 70.dp)
                    Num(s.blocker.toString(), 100.dp)
                    Num(s.major.toString(), 100.dp)
                    Num(s.minor.toString(), 90.dp)
                    // Normalizado por PR: quien mandó diez PRs va a acumular más hallazgos que
                    // quien mandó uno, y eso no dice nada de ninguno de los dos.
                    Num(if (s.prs > 0) "%.1f".format(s.total.toDouble() / s.prs) else "—", 90.dp)
                    Num(if (s.notFixedFirstTime > 0) s.notFixedFirstTime.toString() else "—", 130.dp)
                }
                HorizontalDivider()
            }
            Spacer(Modifier.height(6.dp))
            Text(
                t("rstats.depthNote"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Col(texto: String, ancho: androidx.compose.ui.unit.Dp) {
    Text(
        texto,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (ancho > 150.dp) TextAlign.Start else TextAlign.End,
        modifier = Modifier.width(ancho),
    )
}

@Composable
private fun Num(texto: String, ancho: androidx.compose.ui.unit.Dp) {
    Text(
        texto,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.End,
        modifier = Modifier.width(ancho),
    )
}
