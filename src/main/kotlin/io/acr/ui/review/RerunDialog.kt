package io.acr.ui.review

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.acr.data.PriorReview
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Hace cuánto fue algo, ya redondeado a la unidad que se lee de un vistazo. */
data class Ago(val amount: Long, val unit: Unit) {
    enum class Unit { HOURS, DAYS }
}

/**
 * Convierte una fecha ISO en "hace N horas" o "hace N días".
 *
 * Se redondea a una sola unidad a propósito: para decidir si repetir una review alcanza con saber
 * si fue hoy o hace una semana. "Hace 5 días, 7 horas y 12 minutos" obliga a leer tres números
 * para tomar una decisión binaria.
 *
 * El corte está en 48 horas porque abajo de eso los días quedan gruesos —"hace 1 día" puede ser
 * cualquier cosa entre 24 y 47 horas— y arriba las horas dejan de significar algo: nadie piensa
 * "hace 139 horas", piensa "hace casi una semana".
 *
 * Devuelve null si la fecha no se entiende: mejor no decir nada que inventar una antigüedad.
 */
fun agoFrom(iso: String?, now: Instant): Ago? {
    val cuando = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    // Una fecha futura —relojes desfasados— se trata como recién ocurrida, igual que en PrAge:
    // "hace -3 horas" no significa nada para quien lo lee.
    val horas = ChronoUnit.HOURS.between(cuando, now).coerceAtLeast(0)
    return if (horas < 48) Ago(horas, Ago.Unit.HOURS)
    else Ago(ChronoUnit.DAYS.between(cuando, now).coerceAtLeast(0), Ago.Unit.DAYS)
}

/**
 * Aviso antes de repetir una review sobre un commit que ya se revisó y terminó bien.
 *
 * No bloquea: repetir con otra profundidad, con guías nuevas o simplemente porque sí es un uso
 * legítimo. Lo que evita es el caso que muestran los datos —repeticiones a 57, 127 y 139 horas de
 * distancia—, que no es alguien decidiendo re-correr sino alguien que volvió al PR días después y
 * no se acordaba de que ya lo había mirado. Ahí el costo no es sólo la plata: son siete minutos
 * de espera para redescubrir un resultado que estaba guardado.
 *
 * Sólo aparece si la review previa fue DONE. Si se había caído, se corre sin preguntar: repetir
 * es exactamente lo que hay que hacer y preguntar sería estorbar.
 */
@Composable
fun RerunDialog(
    previa: PriorReview,
    now: Instant = Instant.now(),
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val ago = agoFrom(previa.createdAt, now)
    val cuando = when (ago?.unit) {
        Ago.Unit.HOURS -> io.acr.i18n.t("rerun.agoHours", ago.amount)
        Ago.Unit.DAYS -> io.acr.i18n.t("rerun.agoDays", ago.amount)
        null -> io.acr.i18n.t("rerun.agoUnknown")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(io.acr.i18n.t("rerun.title")) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(io.acr.i18n.t("rerun.body", cuando), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    io.acr.i18n.t("rerun.findings", previa.findings),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // El costo sólo si se conoce: una review vieja puede no tenerlo guardado, y un
                // "US$ 0,00" ahí abajo diría que salió gratis en vez de que no se sabe.
                previa.costUsd?.let {
                    Text(
                        io.acr.i18n.t("rerun.cost", "%.2f".format(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    io.acr.i18n.t("rerun.note"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(io.acr.i18n.t("rerun.confirm")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(io.acr.i18n.t("common.cancel")) }
        },
    )
}
