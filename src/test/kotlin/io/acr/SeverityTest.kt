package io.acr

import io.acr.ui.Severity
import io.acr.ui.mark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La gravedad se muestra en cuatro pantallas y cada una elegía su color por su cuenta: `major`
 * usaba el azul del tema y `minor` un gris, que en la práctica se leían igual.
 */
class SeverityTest {

    @Test
    fun readsWhatTheModelReturns() {
        // El esquema obliga al modelo a devolver estas tres, en minúsculas.
        assertEquals(Severity.BLOCKER, Severity.of("blocker"))
        assertEquals(Severity.MAJOR, Severity.of("major"))
        assertEquals(Severity.MINOR, Severity.of("minor"))
        assertEquals(Severity.MAJOR, Severity.of("MAJOR"))
        assertEquals(Severity.MAJOR, Severity.of(" major "))
    }

    @Test
    fun anUnknownValueFallsToTheLeastSevere() {
        // Ante algo inesperado, exagerar la gravedad es peor: un "blocker" inventado frena un
        // merge sin motivo.
        assertEquals(Severity.MINOR, Severity.of("critical"))
        assertEquals(Severity.MINOR, Severity.of(""))
        assertEquals(Severity.MINOR, Severity.of(null))
    }

    @Test
    fun theOrderGoesFromMostToLeastSevere() {
        // El orden se usa para elegir la gravedad más alta de un archivo: si se invierte, el
        // contador se pintaría con el color del hallazgo más leve.
        assertEquals(
            listOf(Severity.BLOCKER, Severity.MAJOR, Severity.MINOR),
            Severity.entries.toList(),
        )
        val delArchivo = listOf("minor", "blocker", "major")
        assertEquals(
            Severity.BLOCKER,
            delArchivo.minByOrNull { Severity.of(it).ordinal }!!.let { Severity.of(it) },
        )
    }

    @Test
    fun theMarkWorksWithoutColor() {
        // No todo el mundo distingue rojo de ámbar, y el color no sobrevive a una captura en
        // blanco y negro: la cantidad de puntos dice lo mismo sin depender de la vista.
        assertEquals("●●●", Severity.BLOCKER.mark())
        assertEquals("●●", Severity.MAJOR.mark())
        assertEquals("●", Severity.MINOR.mark())
        assertEquals(3, Severity.entries.map { it.mark() }.distinct().size)
    }

    @Test
    fun everyLevelHasATranslation() {
        Severity.entries.forEach { s ->
            listOf(io.acr.i18n.Lang.ES, io.acr.i18n.Lang.EN).forEach { idioma ->
                assertTrue(
                    io.acr.i18n.I18n.get(idioma, s.labelKey) != s.labelKey,
                    "falta ${s.labelKey} en $idioma",
                )
            }
        }
    }
}
