package io.acr.stats

import java.time.LocalDate

/**
 * Un tramo de tiempo, con su nombre.
 *
 * Las fechas se manejan como texto ISO porque es como las guarda git y como las compara SQLite:
 * convertir a fecha y volver sólo agrega dos lugares donde perder la zona horaria.
 */
data class Period(val label: String, val from: String, val to: String) {
    /** Un trimestre en curso no terminó: leerlo como caída sería leer mal. */
    val partial: Boolean get() = to > LocalDate.now().toString()
}

/** Los presets del selector. Rango libre aparte. */
enum class PeriodPreset(val labelKey: String) {
    ALL("period.all"),
    LAST_90("period.last90"),
    CURRENT_QUARTER("period.currentQuarter"),
    PREVIOUS_QUARTER("period.previousQuarter"),
}

/**
 * El trimestre calendario al que pertenece una fecha. Q1 = enero-marzo.
 *
 * Por año calendario y no por año fiscal: no hay un año fiscal configurado en ningún lado, e
 * inventar uno haría que los cortes no coincidan con ningún otro reporte del que se hable.
 */
fun quarterOf(date: LocalDate): Pair<Int, Int> = date.year to ((date.monthValue - 1) / 3 + 1)

fun quarterRange(year: Int, quarter: Int): Period {
    val primerMes = (quarter - 1) * 3 + 1
    val desde = LocalDate.of(year, primerMes, 1)
    val hasta = desde.plusMonths(3).minusDays(1)
    return Period("Q$quarter $year", desde.toString(), hasta.toString())
}

fun resolvePreset(preset: PeriodPreset, today: LocalDate = LocalDate.now()): Period {
    val (anio, q) = quarterOf(today)
    return when (preset) {
        // "Todo" arranca antes de que existiera git: cualquier fecha real cae adentro y no hay que
        // preguntarle a la base cuál es la más vieja.
        PeriodPreset.ALL -> Period("all", "1970-01-01", "2999-12-31")
        PeriodPreset.LAST_90 -> Period("last90", today.minusDays(90).toString(), today.toString())
        PeriodPreset.CURRENT_QUARTER -> quarterRange(anio, q)
        PeriodPreset.PREVIOUS_QUARTER ->
            if (q == 1) quarterRange(anio - 1, 4) else quarterRange(anio, q - 1)
    }
}

/**
 * Los trimestres que toca un período, del más viejo al más nuevo.
 *
 * Sirve para la evolución: un total acumulado no dice si alguien viene subiendo o bajando, y con
 * dos años de datos tampoco describe a nadie.
 *
 * @param max cuántos mostrar como mucho. Con "Todo" seleccionado serían decenas de columnas, y una
 *   tabla de treinta columnas no se lee: se muestran los últimos.
 */
fun quartersIn(period: Period, max: Int = 8, today: LocalDate = LocalDate.now()): List<Period> {
    val desde = runCatching { LocalDate.parse(period.from.take(10)) }.getOrNull() ?: return emptyList()
    val hastaCruda = runCatching { LocalDate.parse(period.to.take(10)) }.getOrNull() ?: return emptyList()
    // No se muestran trimestres futuros: con "Todo" el tope es 2999 y serían mil columnas vacías.
    val hasta = if (hastaCruda > today) today else hastaCruda
    if (desde > hasta) return emptyList()

    val todos = mutableListOf<Period>()
    var (anio, q) = quarterOf(desde)
    val (anioFin, qFin) = quarterOf(hasta)
    // Con "Todo" el inicio es 1970: se arranca en el trimestre más viejo que valga la pena, no en
    // el del epoch, o serían doscientas iteraciones para tirar casi todas.
    if (anio < anioFin - max) {
        anio = anioFin - max
        q = qFin
    }
    while (anio < anioFin || (anio == anioFin && q <= qFin)) {
        todos += quarterRange(anio, q)
        if (q == 4) { anio++; q = 1 } else q++
    }
    return todos.takeLast(max)
}
