package io.acr.impl

/**
 * Una duración en minutos, escrita para leerse de un vistazo.
 *
 * Hasta la hora van minutos, que es como se piensa una tarea. A partir de ahí, `HH:MM`: "185 min"
 * obliga a dividir por sesenta para saber si son tres horas o cinco, y ese cálculo se hace mal
 * justo cuando la cifra importa —cuando algo se está yendo de tiempo—.
 *
 * Se redondea al minuto y no se muestran segundos: la precisión de estas estimaciones no llega ni
 * al minuto, y mostrar más dígitos de los que el número tiene sugiere una exactitud que no existe.
 */
fun minutosLegibles(min: Double?): String {
    if (min == null) return "—"
    val total = kotlin.math.round(min).toLong().coerceAtLeast(0)
    if (total < 60) return "$total′"
    return "%d:%02d".format(total / 60, total % 60)
}

fun minutosLegibles(min: Int?): String = minutosLegibles(min?.toDouble())
