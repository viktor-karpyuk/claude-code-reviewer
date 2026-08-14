package io.acr.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.acr.AppContext
import io.acr.data.Volume
import io.acr.i18n.t
import io.acr.stats.Period
import io.acr.stats.PeriodPreset
import io.acr.stats.Person
import io.acr.stats.quartersIn
import io.acr.stats.resolvePreset
import io.acr.ui.PersonAvatar

/** Cómo ordenar la tabla. Es una herramienta para encontrar algo, no un veredicto. */
private enum class Orden(val labelKey: String) {
    NAME("team.sort.name"),
    COMMITS("team.sort.commits"),
    TOUCHED("team.sort.touched"),
}

/**
 * Volumen de cambio por persona en un período.
 *
 * Es la métrica más fácil de malinterpretar que tiene el módulo, así que la pantalla está armada
 * para dificultar la lectura equivocada: nunca un número solo —siempre agregado, borrado y neto—,
 * lo generado a la vista en vez de escondido, sin podio ni destacado del primero, y la advertencia
 * arriba y no en un tooltip.
 *
 * Sólo muestra lo que sale de git, que es lo único con cobertura completa. Las métricas de pull
 * request —cuántos abrió cada uno, cuánto tardaron en cerrarse— necesitan el histórico del
 * proveedor: medido sobre estos repositorios, git ve 9 merges de PR en un año donde la app conoce
 * PRs hasta el #152, así que calcularlas desde acá daría números que parecen reales y subcuentan.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeamPanel(ctx: AppContext) {
    var preset by remember { mutableStateOf(PeriodPreset.LAST_90) }
    var orden by remember { mutableStateOf(Orden.TOUCHED) }
    var verEvolucion by remember { mutableStateOf(false) }
    var ficha by remember { mutableStateOf<Person?>(null) }
    var verArchivadas by remember { mutableStateOf(false) }

    val periodo = remember(preset) { resolvePreset(preset) }
    val personas = io.acr.ui.dbState(initial = emptyList<Person>()) { ctx.persons.all() }
    val provisional = io.acr.ui.dbState(initial = false) { ctx.persons.hasUnconfirmed() }
    val volumenes = io.acr.ui.dbState(periodo, initial = emptyMap<String, Volume>()) {
        ctx.commitStats.volumeByPerson(periodo.from, periodo.to)
    }
    val trimestres = remember(periodo) { quartersIn(periodo) }
    val porTrimestre = io.acr.ui.dbState(trimestres, initial = emptyMap<String, Map<String, Volume>>()) {
        trimestres.associate { q -> q.label to ctx.commitStats.volumeByPerson(q.from, q.to) }
    }

    // Quien ya no está deja de aparecer, pero sus datos no se borran: en un trimestre viejo esa
    // persona sí estuvo, y sacarla del histórico haría bajar los totales del equipo sin
    // explicación. Por eso se filtra al mostrar y no al guardar, y se puede volver a ver.
    val visibles = personas.filter { !it.isBot && (verArchivadas || !it.archived) }
    val filas = remember(visibles, volumenes, orden) {
        visibles.map { it to (volumenes[it.id] ?: Volume(0, 0, 0, 0, 0)) }
            .sortedWith(
                when (orden) {
                    Orden.NAME -> compareBy { it.first.displayName.lowercase() }
                    Orden.COMMITS -> compareByDescending { it.second.commits }
                    Orden.TOUCHED -> compareByDescending { it.second.touched }
                },
            )
    }

    // Una sola escala para todas las barras: si cada fila se normalizara a su propio máximo,
    // todas se verían iguales y el gráfico diría que todos hicieron lo mismo.
    val escala = remember(filas) { filas.maxOfOrNull { it.second.touched }?.toDouble() ?: 0.0 }

    // La ficha reemplaza a la tabla en vez de abrirse en un modal: acá se viene a leer, y un
    // diálogo obligaría a cerrarlo para volver a mirar la lista.
    ficha?.let { p ->
        PersonCard(ctx, p, p.displayName, periodo) { ficha = null }
        return
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(t("team.title"), style = MaterialTheme.typography.titleMedium)

        // La advertencia va acá, visible sin scrollear. Un módulo que se lea como evaluación de
        // desempeño va a ser usado así, y las decisiones que se tomen con estos números van a ser
        // malas: ninguna de estas métricas mide qué tan bien programa alguien.
        Text(
            t("team.warning"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        if (provisional) {
            Text(
                t("people.provisional"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PeriodPreset.entries.forEach { p ->
                FilterChip(preset == p, { preset = p }, { Text(t(p.labelKey)) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (preset == PeriodPreset.ALL) t("team.allTime")
                else t("team.range", periodo.from, periodo.to),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (periodo.partial) {
                Spacer(Modifier.width(6.dp))
                Text(
                    t("team.partialQuarter"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.weight(1f))
            if (personas.any { it.archived }) {
                TextButton(onClick = { verArchivadas = !verArchivadas }) {
                    Text(
                        if (verArchivadas) t("team.hideArchived")
                        else t("team.showArchived", personas.count { it.archived }),
                    )
                }
            }
            TextButton(onClick = { verEvolucion = !verEvolucion }) {
                Text(if (verEvolucion) t("team.hideEvolution") else t("team.showEvolution"))
            }
        }

        Spacer(Modifier.height(8.dp))
        if (filas.isEmpty() || filas.all { it.second.commits == 0 }) {
            Text(
                t("team.empty"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Encabezado(t("team.person"), 220.dp, Orden.NAME, orden) { orden = it }
            Encabezado(t("team.commits"), 90.dp, Orden.COMMITS, orden) { orden = it }
            Encabezado(t("team.added"), 90.dp, null, orden) {}
            Encabezado(t("team.deleted"), 90.dp, null, orden) {}
            Encabezado(t("team.net"), 90.dp, null, orden) {}
            Encabezado(t("team.touched"), 100.dp, Orden.TOUCHED, orden) { orden = it }
            Encabezado(t("team.generated"), 110.dp, null, orden) {}
        }
        Legend(
            listOf(t("team.added") to ChartColors.added, t("team.deleted") to ChartColors.deleted),
            Modifier.padding(vertical = 4.dp),
        )
        HorizontalDivider()

        // La tabla puede crecer más que la pantalla: el scroll va acá adentro y no en toda la
        // vista, así los encabezados y el selector de período no se van hacia arriba al bajar.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            filas.forEach { (p, v) ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { ficha = p }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.width(220.dp), verticalAlignment = Alignment.CenterVertically) {
                        PersonAvatar(p.displayName, size = 28.dp)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(p.displayName, style = MaterialTheme.typography.bodySmall)
                            if (p.autoMerged) {
                                Text(
                                    t("people.autoMerged"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    Numero(v.commits.toString(), 90.dp)
                    Numero("+${v.added}", 90.dp)
                    Numero("−${v.deleted}", 90.dp)
                    Numero(if (v.net >= 0) "+${v.net}" else v.net.toString(), 90.dp)
                    Numero(v.touched.toString(), 100.dp)
                    // Lo excluido se dice, no se esconde: es el 25,6% de las líneas y sin verlo no
                    // se puede juzgar si la lista de patrones está bien puesta.
                    Numero(if (v.generated > 0) v.generated.toString() else "—", 110.dp, atenuado = true)
                    Spacer(Modifier.width(12.dp))
                    // Agregado y borrado en la misma barra, no sumados: un refactor que borra dos
                    // mil líneas se vería como producción pura si se sumaran.
                    BarRow(
                        segments = listOf(
                            Segment(v.added.toDouble(), ChartColors.added, "+"),
                            Segment(v.deleted.toDouble(), ChartColors.deleted, "−"),
                        ),
                        max = escala,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (verEvolucion && trimestres.isNotEmpty()) {
                    // El tiempo se lee de izquierda a derecha, así que acá las barras van
                    // verticales. El trimestre en curso sale más tenue: a mitad de camino
                    // siempre parece una caída si no se marca.
                    Row(Modifier.padding(start = 36.dp, bottom = 8.dp, end = 16.dp)) {
                        MiniBars(
                            values = trimestres.map { (porTrimestre[it.label]?.get(p.id)?.touched ?: 0).toDouble() },
                            labels = trimestres.map { it.label },
                            lastIsPartial = trimestres.last().partial,
                            modifier = Modifier.width(360.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            trimestres.forEach { q ->
                                val vq = porTrimestre[q.label]?.get(p.id)
                                Text(
                                    "${q.label}: " + if (vq == null) "—" else "${vq.commits}c · ${vq.touched}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            t("team.openCard"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            t("team.coverage"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Encabezado de columna.
 *
 * Ordenar no destaca al primero ni le cambia el color: la tabla no es un podio, y el orden sirve
 * para encontrar algo, no para coronar a alguien. La flecha marca por dónde se está ordenando,
 * que es información, no un premio.
 */
@Composable
private fun Encabezado(
    texto: String,
    ancho: androidx.compose.ui.unit.Dp,
    orden: Orden?,
    actual: Orden,
    onClick: (Orden) -> Unit,
) {
    val base = Modifier.width(ancho)
    Text(
        texto + if (orden != null && orden == actual) " ↓" else "",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (ancho > 150.dp) TextAlign.Start else TextAlign.End,
        modifier = if (orden == null) base
        else base.clickable { onClick(orden) },
    )
}

@Composable
private fun Numero(texto: String, ancho: androidx.compose.ui.unit.Dp, atenuado: Boolean = false) {
    Text(
        texto,
        style = MaterialTheme.typography.bodySmall,
        color = if (atenuado) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.End,
        modifier = Modifier.width(ancho),
    )
}


