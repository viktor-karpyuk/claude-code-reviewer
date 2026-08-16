package io.acr.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.acr.AppContext
import io.acr.data.percentiles
import io.acr.i18n.t
import io.acr.stats.Period
import io.acr.stats.Person
import io.acr.stats.quartersIn
import io.acr.ui.PersonAvatar

/**
 * La ficha de una persona: sus números del período y, debajo de cada uno, lo que lo compone.
 *
 * El detalle no es un extra. Un agregado que no se puede abrir no sirve para conversar: si la
 * ficha dice "8 pull requests, mediana 4 días", lo primero que uno quiere ver es cuáles fueron y
 * cuál es el que tardó veinte. Sin eso, el número sólo sirve para tener una impresión, que es
 * justo lo que este módulo no debería producir.
 *
 * La participación revisando va arriba, con el mismo peso que el volumen: si quedara al pie se
 * leería como un apéndice, y es la mitad del trabajo.
 */
@Composable
fun PersonCard(
    ctx: AppContext,
    persona: Person,
    /** Nombre con el que aparece en el proveedor. Puede diferir del de git. */
    nombreProveedor: String,
    periodo: Period,
    onClose: () -> Unit,
) {
    var copiado by remember(persona.id) { mutableStateOf(false) }

    val volumen = io.acr.ui.dbState(persona.id, periodo, initial = null as io.acr.data.Volume?) {
        ctx.commitStats.volumeByPerson(periodo.from, periodo.to)[persona.id]
    }
    val prs = io.acr.ui.dbState(nombreProveedor, periodo, initial = emptyList<io.acr.data.PrRow>()) {
        ctx.prStats.listByAuthor(nombreProveedor, periodo.from, periodo.to)
    }
    val hallazgos = io.acr.ui.dbState(nombreProveedor, periodo, initial = emptyList<io.acr.data.FindingRow>()) {
        ctx.reviewStats.findingsFor(nombreProveedor, periodo.from, periodo.to)
    }
    val participacion = io.acr.ui.dbState(nombreProveedor, periodo, initial = null as io.acr.data.Participation?) {
        ctx.reviewStats.commentsByAuthor(periodo.from, periodo.to)[nombreProveedor]
    }
    val trimestres = remember(periodo) { quartersIn(periodo) }
    // Una consulta y no una por trimestre: la ficha dibuja hasta ocho barras.
    val porTrimestre = io.acr.ui.dbState(persona.id, trimestres, initial = emptyList<Int>()) {
        val todos = ctx.commitStats.volumeByPersonByQuarter()[persona.id].orEmpty()
        trimestres.map { q -> todos[q.label] ?: 0 }
    }

    val ciclo = remember(prs) { percentiles(prs.mapNotNull { it.days }) }
    val resumen = remember(persona.id, volumen, prs, hallazgos, participacion, ciclo) {
        armarTexto(persona, periodo, volumen, prs, hallazgos, participacion, ciclo)
    }

    Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text(t("common.back")) }
            Spacer(Modifier.width(6.dp))
            PersonAvatar(persona.displayName, size = 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(persona.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    persona.identities.joinToString("  ·  ") { it.value },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Una uno a uno se prepara con datos, no con capturas de pantalla.
            TextButton(onClick = {
                java.awt.Toolkit.getDefaultToolkit().systemClipboard
                    .setContents(java.awt.datatransfer.StringSelection(resumen), null)
                copiado = true
            }) { Text(if (copiado) t("common.copied") else t("card.copy")) }
        }

        Text(
            t("card.period", periodo.from, periodo.to),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (persona.autoMerged) {
            Text(
                t("card.autoMerged"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(14.dp))
        Seccion(t("card.participation"))
        if (participacion == null) {
            Vacio(t("card.noParticipation"))
        } else {
            Text(
                t("card.participationLine", participacion.comments, participacion.prs),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(14.dp))
        Seccion(t("card.volume"))
        if (volumen == null || volumen.commits == 0) {
            Vacio(t("card.noCommits"))
        } else {
            Text(
                t("card.volumeLine", volumen.commits, volumen.added, volumen.deleted, volumen.net),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (volumen.generated > 0) {
                Text(
                    t("card.generatedLine", volumen.generated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (trimestres.size > 1) {
                Spacer(Modifier.height(6.dp))
                MiniBars(
                    values = porTrimestre.map { it.toDouble() },
                    labels = trimestres.map { it.label },
                    lastIsPartial = trimestres.last().partial,
                    modifier = Modifier.width(420.dp),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Seccion(t("card.prs"))
        if (prs.isEmpty()) {
            Vacio(t("card.noPrs"))
        } else {
            ciclo?.let {
                Text(
                    t("card.cycleLine", prs.size, "%.1f".format(it.first), "%.1f".format(it.second)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(4.dp))
            // La lista completa y no un top: el PR que explica la cola del percentil 90 puede ser
            // cualquiera, y esconderlo dejaría el número sin la única fila que lo justifica.
            prs.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        "#${r.prId}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.width(60.dp),
                    )
                    Text(
                        r.title.take(70),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        r.state.lowercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(80.dp),
                    )
                    Text(
                        r.days?.let { "%.1f d".format(it) } ?: "—",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(60.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Seccion(t("card.findings"))
        if (hallazgos.isEmpty()) {
            Vacio(t("card.noFindings"))
        } else {
            val porGravedad = hallazgos.groupingBy { it.severity }.eachCount()
            Text(
                t(
                    "card.findingsLine",
                    hallazgos.size,
                    porGravedad["blocker"] ?: 0,
                    porGravedad["major"] ?: 0,
                    porGravedad["minor"] ?: 0,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            hallazgos.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        f.severity,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (f.severity) {
                            "blocker" -> ChartColors.blocker
                            "major" -> ChartColors.major
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.width(70.dp),
                    )
                    Text("#${f.prId}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(60.dp))
                    Text(f.title.take(70), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(
                        f.resolution?.lowercase() ?: t("verify.pending"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(110.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            t("card.disclaimer"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Seccion(titulo: String) {
    Text(titulo, style = MaterialTheme.typography.titleSmall)
    HorizontalDivider(Modifier.padding(vertical = 3.dp))
}

@Composable
private fun Vacio(texto: String) {
    Text(
        texto,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * La ficha como texto plano, para pegar en una conversación.
 *
 * Se arma acá y no se captura de la pantalla porque lo que se pega tiene que poder leerse sin la
 * app al lado: incluye el período, la advertencia y las listas completas. Una captura pierde el
 * contexto justo cuando más hace falta, que es cuando el número se discute.
 */
private fun armarTexto(
    persona: Person,
    periodo: Period,
    volumen: io.acr.data.Volume?,
    prs: List<io.acr.data.PrRow>,
    hallazgos: List<io.acr.data.FindingRow>,
    participacion: io.acr.data.Participation?,
    ciclo: Pair<Double, Double>?,
): String = buildString {
    appendLine("${persona.displayName} — ${periodo.from} a ${periodo.to}")
    appendLine()
    appendLine("Participación revisando: " + (participacion?.let { "${it.comments} comentarios en ${it.prs} PRs" } ?: "sin datos"))
    volumen?.let {
        appendLine("Volumen: ${it.commits} commits, +${it.added} / −${it.deleted} (neto ${it.net})")
        if (it.generated > 0) appendLine("  (${it.generated} líneas de archivos generados, excluidas)")
    }
    if (prs.isNotEmpty()) {
        appendLine("Pull requests: ${prs.size}" + (ciclo?.let { ", mediana %.1f d, p90 %.1f d".format(it.first, it.second) } ?: ""))
        prs.forEach { appendLine("  #${it.prId} ${it.state.lowercase()} — ${it.title}" + (it.days?.let { d -> " (%.1f d)".format(d) } ?: "")) }
    }
    if (hallazgos.isNotEmpty()) {
        val g = hallazgos.groupingBy { it.severity }.eachCount()
        appendLine("Hallazgos recibidos: ${hallazgos.size} (${g["blocker"] ?: 0} bloqueantes, ${g["major"] ?: 0} importantes, ${g["minor"] ?: 0} menores)")
        hallazgos.forEach { appendLine("  [${it.severity}] #${it.prId} ${it.title} — ${it.filePath}") }
    }
    appendLine()
    appendLine(
        "Nada de esto mide qué tan bien programa alguien. Los hallazgos dependen de la " +
            "profundidad de review configurada, el tiempo de ciclo incluye la espera del revisor, " +
            "y las métricas de review sólo cubren los PRs que la app revisó.",
    )
}
