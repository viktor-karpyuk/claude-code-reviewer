package io.acr.claude

/**
 * Distinguir un problema pasajero de uno que no se va a arreglar esperando.
 *
 * Es la diferencia entre una implementación que sobrevive a un bache de diez minutos del servidor y
 * una que se cae entera porque justo en ese momento nadie estaba mirando. Un `529 Overloaded` no
 * dice nada sobre el trabajo: dice que en ese instante había demasiada gente. Reintentar lo arregla.
 *
 * Y al revés: `unrecognized_model` reintentado cien veces falla cien veces. Tratarlo como pasajero
 * convertiría un error de configuración de un segundo en una espera infinita, que es peor que el
 * error — porque el error se lee y se arregla, y la espera parece que algo está pasando.
 */
object Transient {

    /** Lo que se arregla solo si se espera. */
    private val PASAJERO = listOf(
        "529", "overloaded", "rate limit", "rate_limit", "429",
        "too many requests", "timeout", "timed out", "connection reset",
        "connection refused", "econnreset", "socket hang up", "temporarily unavailable",
        "service unavailable", "503", "502", "504", "bad gateway", "internal server error",
    )

    /**
     * Lo que no. Va primero: `unrecognized_model` en un mensaje que también dice "error" no es un
     * bache del servidor, y clasificarlo como pasajero dejaría la tarea reintentando para siempre.
     */
    private val PERMANENTE = listOf(
        "unrecognized_model", "invalid_request", "authentication", "unauthorized", "401",
        "403", "permission denied", "not found", "404", "invalid api key",
        "schema", "no encuentro el ejecutable",
    )

    fun isTransient(mensaje: String?): Boolean {
        val m = mensaje?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        if (PERMANENTE.any { it in m }) return false
        return PASAJERO.any { it in m }
    }

    /**
     * Cuánto esperar antes del intento número [intento], en milisegundos.
     *
     * Arranca en cinco segundos y se queda ahí el primer minuto: la mayoría de los baches duran
     * eso, y esperar más sería tener la máquina parada de gusto. Después se estira, porque a partir
     * del minuto el problema ya no es un bache y machacar cada cinco segundos no lo apura — sólo
     * suma carga al servidor que está justamente sobrecargado.
     *
     * No hay tope de intentos: la salida es cancelar, que es una decisión de una persona. Un tope
     * fijo haría que la implementación se dé por vencida justo cuando el servidor estaba volviendo.
     */
    fun waitMs(intento: Int): Long = when {
        intento <= 12 -> 5_000
        intento <= 20 -> 15_000
        else -> 30_000
    }
}
