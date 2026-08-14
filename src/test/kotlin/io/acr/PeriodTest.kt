package io.acr

import io.acr.stats.Period
import io.acr.stats.PeriodPreset
import io.acr.stats.quarterOf
import io.acr.stats.quarterRange
import io.acr.stats.quartersIn
import io.acr.stats.resolvePreset
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Los períodos con los que se miran las estadísticas.
 *
 * Un total acumulado de dos años no describe a nadie: lo que sirve es ver si alguien viene subiendo
 * o bajando, y comparar un trimestre contra el mismo del año anterior.
 */
class PeriodTest {

    private val hoy = LocalDate.of(2026, 8, 14) // Q3

    @Test
    fun quartersFollowTheCalendarYear() {
        assertEquals(2026 to 1, quarterOf(LocalDate.of(2026, 1, 1)))
        assertEquals(2026 to 1, quarterOf(LocalDate.of(2026, 3, 31)))
        assertEquals(2026 to 2, quarterOf(LocalDate.of(2026, 4, 1)))
        assertEquals(2026 to 3, quarterOf(LocalDate.of(2026, 8, 14)))
        assertEquals(2026 to 4, quarterOf(LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun aQuarterCoversItsThreeMonthsExactly() {
        assertEquals("2026-01-01", quarterRange(2026, 1).from)
        assertEquals("2026-03-31", quarterRange(2026, 1).to)
        assertEquals("2026-04-01", quarterRange(2026, 2).from)
        assertEquals("2026-06-30", quarterRange(2026, 2).to)
        // Diciembre tiene 31 y el trimestre no puede desbordar al año siguiente.
        assertEquals("2026-12-31", quarterRange(2026, 4).to)
    }

    @Test
    fun theQuarterBeforeQ1IsQ4OfThePreviousYear() {
        val p = resolvePreset(PeriodPreset.PREVIOUS_QUARTER, LocalDate.of(2026, 2, 10))
        assertEquals("2025-10-01", p.from)
        assertEquals("2025-12-31", p.to)
    }

    @Test
    fun thePresetsResolveToTheExpectedRanges() {
        assertEquals("2026-07-01", resolvePreset(PeriodPreset.CURRENT_QUARTER, hoy).from)
        assertEquals("2026-04-01", resolvePreset(PeriodPreset.PREVIOUS_QUARTER, hoy).from)
        assertEquals("2026-05-16", resolvePreset(PeriodPreset.LAST_90, hoy).from)
        // "Todo" tiene que dejar entrar cualquier fecha real sin consultar la base.
        assertTrue(resolvePreset(PeriodPreset.ALL, hoy).from < "2000-01-01")
    }

    @Test
    fun anUnfinishedQuarterIsMarkedAsSuch() {
        // Sin esto, el trimestre en curso se lee como una caída cuando sólo está a mitad.
        assertTrue(resolvePreset(PeriodPreset.CURRENT_QUARTER, hoy).partial)
        assertTrue(!resolvePreset(PeriodPreset.PREVIOUS_QUARTER, hoy).partial)
    }

    @Test
    fun theEvolutionListsTheQuartersItTouches() {
        val medioAnio = Period("x", "2026-01-01", "2026-08-14")
        assertEquals(listOf("Q1 2026", "Q2 2026", "Q3 2026"), quartersIn(medioAnio, today = hoy).map { it.label })
    }

    @Test
    fun futureQuartersAreNotShown() {
        // "Todo" llega hasta 2999: sin acotar serían mil columnas vacías.
        val labels = quartersIn(resolvePreset(PeriodPreset.ALL, hoy), today = hoy).map { it.label }
        assertTrue(labels.isNotEmpty())
        assertEquals("Q3 2026", labels.last(), "el último trimestre es el actual, no uno futuro")
        assertTrue(labels.none { it.contains("2027") })
    }

    @Test
    fun theEvolutionDoesNotGrowIntoAnUnreadableTable() {
        // Una tabla de treinta columnas no se lee; se muestran los últimos.
        val labels = quartersIn(resolvePreset(PeriodPreset.ALL, hoy), max = 4, today = hoy).map { it.label }
        assertEquals(4, labels.size)
        assertEquals(listOf("Q4 2025", "Q1 2026", "Q2 2026", "Q3 2026"), labels)
    }

    @Test
    fun aSingleQuarterPeriodYieldsThatQuarter() {
        val q2 = resolvePreset(PeriodPreset.PREVIOUS_QUARTER, hoy)
        assertEquals(listOf("Q2 2026"), quartersIn(q2, today = hoy).map { it.label })
    }

    @Test
    fun aBackwardsOrBrokenRangeYieldsNothingInsteadOfLooping() {
        // Un rango elegido al revés no puede colgar la pantalla.
        assertEquals(emptyList(), quartersIn(Period("x", "2026-08-01", "2026-01-01"), today = hoy))
        assertEquals(emptyList(), quartersIn(Period("x", "no es fecha", "2026-01-01"), today = hoy))
    }
}
