package io.acr.ui.impl

import androidx.compose.ui.graphics.Color
import io.acr.impl.TaskStatus

/**
 * Los colores de estado, en un solo lugar.
 *
 * Estaban resueltos tres veces —el badge de la tabla, los pasos, el Gantt— y dos de ellas usaban
 * `colorScheme.primary` para "corriendo", que según el tema podía salir violeta. El color de estado
 * es la primera cosa que se lee en cualquiera de esas pantallas, y que signifique algo distinto en
 * cada una lo vuelve ruido.
 *
 * El código es fijo y no derivado del tema a propósito: son cuatro significados con una convención
 * universal detrás —rojo es que se rompió, verde es que salió bien, azul es que está pasando,
 * naranja es que alguien tiene que meterse— y un tema que los reasigne no los mejora, los rompe.
 *
 * **El naranja es específico: hace falta una persona.** No es "atención" genérico ni un fallo
 * suave. Se usa sólo cuando el trabajo está frenado esperando una decisión o una revisión humana, y
 * por eso es el único que exige que alguien haga algo.
 */
object StatusColors {

    /** Salió bien. */
    val DONE = Color(0xFF2E9E63)

    /** Está pasando ahora. */
    val RUNNING = Color(0xFF2F80ED)

    /** Se rompió. */
    val FAILED = Color(0xFFD64545)

    /** Necesita una persona: una decisión o una revisión. */
    val NEEDS_HUMAN = Color(0xFFE08A1E)

    /** Todavía no arrancó. */
    val PENDING = Color(0xFF9AA5B1)

    fun of(s: TaskStatus): Color = when (s) {
        TaskStatus.DONE -> DONE
        TaskStatus.RUNNING -> RUNNING
        TaskStatus.FAILED -> FAILED
        TaskStatus.BLOCKED -> NEEDS_HUMAN
        else -> PENDING
    }
}
