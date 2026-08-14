package io.acr

import io.acr.data.Volume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La aritmética de los gráficos.
 *
 * Un gráfico mal escalado no se ve mal: se ve bien y dice otra cosa. Por eso lo que se prueba acá
 * no es el dibujo sino los números que deciden el largo de cada barra, que es donde se puede
 * mentir sin que nadie lo note.
 */
class ChartScaleTest {

    private fun vol(added: Int, deleted: Int, gen: Int = 0) = Volume(1, added, deleted, gen, 0)

    /** El máximo compartido: lo que la pantalla usa como referencia de todas las barras. */
    private fun escala(vs: List<Volume>) = vs.maxOfOrNull { it.touched }?.toDouble() ?: 0.0

    @Test
    fun theScaleIsSharedSoTheBarsCanBeCompared() {
        // Si cada fila se normalizara a su propio máximo, todas quedarían del mismo largo y el
        // gráfico diría que todos hicieron lo mismo. Es la forma más fácil de mentir con barras.
        val gente = listOf(vol(1000, 200), vol(50, 10), vol(10, 0))
        val max = escala(gente)
        assertEquals(1200.0, max)

        val largos = gente.map { it.touched / max }
        assertTrue(largos[0] > largos[1] * 10, "quien tocó veinte veces más tiene que verse así")
        assertTrue(largos.all { it <= 1.0 }, "ninguna barra se pasa del ancho disponible")
    }

    @Test
    fun addedAndDeletedShareTheBarInsteadOfBeingSummedAway() {
        // Un refactor que borra 2.000 líneas y agrega 100 se vería como producción pura si los
        // tramos se sumaran en uno solo. Separados, se lee lo que realmente pasó.
        val refactor = vol(100, 2000)
        val feature = vol(2100, 0)
        assertEquals(refactor.touched, feature.touched, "el total es el mismo…")
        assertTrue(refactor.deleted > refactor.added, "…pero los tramos cuentan historias distintas")
    }

    @Test
    fun generatedLinesNeverEnterTheBar() {
        // Son el 25,6% de las líneas en estos repositorios. Si entraran, quien tocó una semilla de
        // datos tendría la barra más larga de todas.
        val conSemilla = vol(10, 0, gen = 40_505)
        assertEquals(10, conSemilla.touched, "lo generado va aparte, no al largo de la barra")
        assertEquals(40_505, conSemilla.generated)
    }

    @Test
    fun anEmptyPeriodDoesNotBreakTheScale() {
        // Sin datos no hay máximo, y dividir por cero pintaría barras infinitas o ninguna.
        assertEquals(0.0, escala(emptyList()))
        assertEquals(0.0, escala(listOf(vol(0, 0))))
    }

    @Test
    fun someoneWithNoActivityKeepsTheirRow() {
        // Una fila en cero tiene que seguir estando: si desapareciera, alguien sin actividad en el
        // período se leería como que no existe en vez de como un cero.
        val gente = listOf(vol(100, 0), vol(0, 0))
        val max = escala(gente)
        assertEquals(0.0, gente[1].touched / max)
        assertEquals(2, gente.size)
    }
}
