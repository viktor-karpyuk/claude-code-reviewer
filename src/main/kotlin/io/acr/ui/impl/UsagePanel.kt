package io.acr.ui.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.acr.data.UsageWindow
import io.acr.i18n.t

/**
 * Cuánto se está consumiendo.
 *
 * **Lo que este panel puede afirmar y lo que no.** Cuenta lo que gastó esta app: sus reviews, sus
 * planificaciones, sus tareas. No ve lo que uno consume en su propia terminal, ni sabe cuál es el
 * límite real de la cuenta — el CLI no lo expone y el archivo de estadísticas que deja en disco no
 * trae límites y se actualiza cuando quiere. Inventar un porcentaje contra un tope adivinado sería
 * lo peor de los dos mundos: un número que parece exacto y decide por vos.
 *
 * Así que el tope lo declara quien lo conoce, y lo que la app aporta es la cuenta fiel de su propio
 * consumo. Dos ventanas: las últimas cinco horas —la que usa Claude Code— y los últimos siete días.
 * La primera contesta "¿lanzo algo grande ahora o espero?"; la segunda, "¿cuánto me queda del mes?".
 */
@Composable
fun UsagePanel(ctx: AppContext) {
    var tic by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(20_000)
            tic++
        }
    }
    var editandoTope by remember { mutableStateOf(false) }
    var tope by remember {
        mutableStateOf(ctx.prefs.get(AppContext.PREF_WEEKLY_BUDGET).orEmpty())
    }

    val sesion = io.acr.ui.dbState(tic, initial = UsageWindow.VACIA) { ctx.usage.session() }
    val semana = io.acr.ui.dbState(tic, initial = UsageWindow.VACIA) { ctx.usage.week() }
    val porTipo = io.acr.ui.dbState(tic, initial = emptyList<Triple<String, Int, Double>>()) {
        ctx.usage.byKind(java.time.Instant.now().minus(java.time.Duration.ofDays(7)))
    }
    val porModelo = io.acr.ui.dbState(tic, initial = emptyList<Triple<String, Int, Double>>()) {
        ctx.usage.byModel(java.time.Instant.now().minus(java.time.Duration.ofDays(7)))
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("usage.title"), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(10.dp))
            Text(
                t("usage.scope"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { editandoTope = !editandoTope }) { Text(t("usage.setBudget")) }
        }
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Ventana(t("usage.session"), t("usage.sessionNote"), sesion, Modifier.weight(1f))
            Ventana(t("usage.week"), t("usage.weekNote"), semana, Modifier.weight(1f))
        }

        // El tope, declarado por quien lo conoce. La barra sólo aparece si hay uno: sin tope, una
        // barra sin referencia es un adorno que sugiere un límite que la app no sabe.
        val topeNum = tope.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
        if (editandoTope) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = tope,
                onValueChange = {
                    tope = it.filter { c -> c.isDigit() || c == '.' || c == ',' }
                    ctx.prefs.put(AppContext.PREF_WEEKLY_BUDGET, tope)
                },
                label = { Text(t("usage.budgetLabel")) },
                supportingText = { Text(t("usage.budgetNote"), style = MaterialTheme.typography.labelSmall) },
                singleLine = true,
                modifier = Modifier.width(280.dp),
            )
        }
        topeNum?.let { max ->
            Spacer(Modifier.height(10.dp))
            val usado = (semana.costUsd / max).coerceIn(0.0, 1.0).toFloat()
            Text(
                t("usage.ofBudget", "%.2f".format(semana.costUsd), "%.2f".format(max)),
                style = MaterialTheme.typography.labelSmall,
                // Se marca a partir del 80%: avisar al 100% es avisar cuando ya no se puede hacer
                // nada con el aviso.
                color = if (usado >= 0.8f) StatusColors.NEEDS_HUMAN else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.fillMaxWidth().height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier.fillMaxWidth(usado).height(8.dp)
                        .background(
                            when {
                                usado >= 1f -> StatusColors.FAILED
                                usado >= 0.8f -> StatusColors.NEEDS_HUMAN
                                else -> StatusColors.DONE
                            },
                        ),
                )
            }
        }

        // Dónde se va. Las dos preguntas son distintas: por tipo dice qué actividad consume, por
        // modelo dice si conviene bajar de familia en alguna de ellas.
        if (porTipo.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Reparto(t("usage.byKind"), porTipo, Modifier.weight(1f))
                Reparto(t("usage.byModel"), porModelo, Modifier.weight(1f))
            }
        }
    }
}

/** Una ventana de consumo: corridas, tokens y costo. */
@Composable
private fun Ventana(titulo: String, nota: String, u: UsageWindow, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp),
    ) {
        Text(titulo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (u.costUsd > 0) "$" + "%.2f".format(u.costUsd) else t("usage.noCost"),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            t("usage.runs", u.runs) + "  ·  " + t("usage.tokens", miles(u.billable)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // La caché aparte: es casi todo el volumen y se cobra distinto, así que sumarla al resto
        // daría un número enorme que no se parece a nada.
        if (u.cacheRead + u.cacheWrite > 0) {
            Text(
                t("usage.cache", miles(u.cacheRead + u.cacheWrite)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(nota, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Reparto(titulo: String, filas: List<Triple<String, Int, Double>>, modifier: Modifier) {
    Column(modifier) {
        Text(titulo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        filas.take(6).forEach { (nombre, corridas, costo) ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    nombre,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text(
                    "$corridas",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(44.dp),
                )
                Text(
                    if (costo > 0) "$" + "%.2f".format(costo) else "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(64.dp),
                )
            }
        }
    }
}

/** Miles con punto: "1.240.000" se lee de un vistazo y "1240000" hay que contarlo con el dedo. */
private fun miles(n: Long): String =
    n.toString().reversed().chunked(3).joinToString(".").reversed()
