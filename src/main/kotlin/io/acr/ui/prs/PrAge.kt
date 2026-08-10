package io.acr.ui.prs

import java.time.LocalDate

/**
 * Hace cuántos días está abierto el pull request.
 *
 * Es el número que dice si algo se está durmiendo. La fecha cruda —"2026-08-03 19:24"— obliga a
 * calcularlo de cabeza cada vez, y con varios PRs en pantalla nadie lo hace.
 *
 * Devuelve null si no se conoce la fecha: un PR cacheado antes de que se guardara la de creación
 * viene vacío, y ahí lo honesto es no mostrar nada en vez de inventar un cero.
 */
fun ageInDays(createdOn: String?, today: LocalDate): Long? {
    val fecha = createdOn?.take(10)?.takeIf { it.length == 10 } ?: return null
    val abierto = runCatching { LocalDate.parse(fecha) }.getOrNull() ?: return null
    // Una fecha futura —relojes desfasados entre el servidor y esta máquina— se trata como hoy:
    // "hace -1 días" no significa nada para quien lo lee.
    return java.time.temporal.ChronoUnit.DAYS.between(abierto, today).coerceAtLeast(0)
}

/**
 * Cuánto apura un PR según lo que lleva abierto.
 *
 * Un umbral único —viejo o no— mete en la misma bolsa uno de ocho días y uno de mil doscientos,
 * y los dos casos existen de verdad en esta instalación. Con escalones, la lista ordena la
 * atención sola: lo rojo se mira primero.
 *
 * No cambia ninguna regla de la app: no bloquea, no ordena, no notifica. Sólo se ve.
 */
enum class Urgency(val desdeDias: Long, val labelKey: String) {
    /** Recién abierto: todavía es trabajo en curso normal. */
    FRESCO(0, "urgency.fresh"),

    /** Ya lleva unos días. Nada alarmante, pero conviene no perderlo de vista. */
    ATENCION(3, "urgency.watch"),

    /** Más de una semana esperando: alguien tiene que moverlo. */
    ALTO(7, "urgency.high"),

    /** Dos semanas o más: dejó de avanzar. */
    CRITICO(14, "urgency.critical"),

    /** Meses. A esta altura la pregunta no es cuándo se revisa sino si sigue teniendo sentido. */
    ABANDONADO(90, "urgency.stale"),
    ;

    companion object {
        /** El escalón más alto que alcanza. Null si no se conoce la fecha: no se inventa urgencia. */
        fun fromDays(days: Long?): Urgency? =
            days?.let { d -> entries.last { d >= it.desdeDias } }
    }
}

/** Color del nivel. Va acá para que la lista y la pantalla del PR no elijan cada una el suyo. */
@androidx.compose.runtime.Composable
fun Urgency.color(): androidx.compose.ui.graphics.Color = when (this) {
    Urgency.FRESCO -> androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    Urgency.ATENCION -> androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    Urgency.ALTO -> androidx.compose.ui.graphics.Color(0xFFD08A2C)
    Urgency.CRITICO -> androidx.compose.material3.MaterialTheme.colorScheme.error
    Urgency.ABANDONADO -> androidx.compose.material3.MaterialTheme.colorScheme.error
}

/** Marca visual: crece con el nivel, así se distingue de reojo sin leer el número. */
fun Urgency.mark(): String = when (this) {
    Urgency.FRESCO -> ""
    Urgency.ATENCION -> "•"
    Urgency.ALTO -> "▲"
    Urgency.CRITICO -> "▲▲"
    Urgency.ABANDONADO -> "▲▲▲"
}
