package io.acr

import io.acr.ui.prs.Urgency
import io.acr.ui.prs.ageInDays
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hace cuántos días está abierto el PR. Se cuenta desde que se creó —no desde el último commit—
 * porque lo que se quiere saber es cuánto lleva esperando, y un commit nuevo no reinicia esa
 * espera.
 */
class PrAgeTest {

    private val hoy = LocalDate.of(2026, 8, 8)

    @Test
    fun countsFromTheDayItWasOpened() {
        assertEquals(5L, ageInDays("2026-08-03 19:24", hoy))
        assertEquals(1L, ageInDays("2026-08-07 23:59", hoy))
        assertEquals(0L, ageInDays("2026-08-08 00:01", hoy))
    }

    @Test
    fun acceptsTheFormatsWeStore() {
        // La lista guarda "2026-08-03 19:24"; la API manda ISO con zona. Los dos tienen que andar.
        assertEquals(5L, ageInDays("2026-08-03 19:24", hoy))
        assertEquals(5L, ageInDays("2026-08-03T19:24:11.000+00:00", hoy))
        assertEquals(5L, ageInDays("2026-08-03", hoy))
    }

    @Test
    fun withoutADateItShowsNothing() {
        // Un PR cacheado antes de que se guardara la fecha de creación viene vacío: mostrar "hace
        // 0 días" sería inventar que se abrió hoy.
        assertNull(ageInDays("", hoy))
        assertNull(ageInDays(null, hoy))
        assertNull(ageInDays("basura", hoy))
        assertNull(ageInDays("2026-13-99", hoy))
    }

    @Test
    fun aFutureDateDoesNotGoNegative() {
        // Relojes desfasados entre el servidor y esta máquina: "hace -1 días" no significa nada.
        assertEquals(0L, ageInDays("2026-08-20 10:00", hoy))
    }

    @Test
    fun urgencyGrowsWithTheDays() {
        // Un umbral único metía en la misma bolsa un PR de 8 días y uno de 1290; los dos casos
        // existen en la instalación real.
        assertEquals(Urgency.FRESCO, Urgency.fromDays(0))
        assertEquals(Urgency.FRESCO, Urgency.fromDays(2))
        assertEquals(Urgency.ATENCION, Urgency.fromDays(3))
        assertEquals(Urgency.ATENCION, Urgency.fromDays(6))
        assertEquals(Urgency.ALTO, Urgency.fromDays(7))
        assertEquals(Urgency.ALTO, Urgency.fromDays(13))
        assertEquals(Urgency.CRITICO, Urgency.fromDays(14))
        assertEquals(Urgency.CRITICO, Urgency.fromDays(89))
        assertEquals(Urgency.ABANDONADO, Urgency.fromDays(90))
        // Los dos PRs de talos, abiertos en 2023.
        assertEquals(Urgency.ABANDONADO, Urgency.fromDays(1290))
    }

    @Test
    fun urgencyNeverGoesBackwards() {
        // El nivel tiene que ser monótono: que un PR más viejo apure menos sería absurdo, y es el
        // error fácil de cometer al agregar un escalón nuevo en el medio.
        var previo = Urgency.fromDays(0)!!
        (0L..400L).forEach { d ->
            val actual = Urgency.fromDays(d)!!
            assertTrue(actual.ordinal >= previo.ordinal, "en el día $d la urgencia bajó")
            previo = actual
        }
    }

    @Test
    fun withoutADateThereIsNoUrgency() {
        // No se inventa urgencia para un PR cuya fecha no conocemos.
        assertNull(Urgency.fromDays(null))
        assertNull(Urgency.fromDays(ageInDays("", hoy)))
    }

    @Test
    fun itSpansMonthsAndYears() {
        assertEquals(365L, ageInDays("2025-08-08 00:00", hoy))
        // Caso real de la base: un PR de talos abierto en 2023.
        assertEquals(1064L, ageInDays("2023-09-09 12:00", hoy))
    }
}
