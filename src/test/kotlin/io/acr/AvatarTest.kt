package io.acr

import io.acr.ui.colorDePersona
import io.acr.ui.iniciales
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Los círculos con iniciales: con varias personas pronunciándose sobre un PR, una lista de nombres
 * ocupa toda la fila.
 */
class AvatarTest {

    @Test
    fun initialsComeFromFirstAndLastName() {
        assertEquals("VK", iniciales("Viktor Karpyuk"))
        assertEquals("TR", iniciales("Tomás Rivero"))
        // Nombres de tres partes: primera y última, no las dos primeras.
        assertEquals("SR", iniciales("Santiago Del Percio Rodriguez"))
        assertEquals("MP", iniciales("Mateo Andrés Perano"))
    }

    @Test
    fun oneWordAndEmptyNamesDoNotBreakIt() {
        assertEquals("BR", iniciales("braian"))
        assertEquals("?", iniciales(""))
        assertEquals("?", iniciales("   "))
        // Espacios de más no generan iniciales vacías.
        assertEquals("VK", iniciales("  Viktor   Karpyuk  "))
    }

    @Test
    fun theColourIsStableForTheSamePerson() {
        // Si cambiara entre aperturas, el círculo dejaría de servir para reconocer a alguien de un
        // vistazo, que es lo único que hace.
        assertEquals(colorDePersona("Viktor Karpyuk"), colorDePersona("Viktor Karpyuk"))
        assertEquals(colorDePersona("Tomás Rivero"), colorDePersona("Tomás Rivero"))
    }

    private val equipo = listOf(
        "Viktor Karpyuk", "Tomás Rivero", "Braian Chavez",
        "Mateo Andrés Perano", "Santiago Del Percio Rodriguez",
    )

    @Test
    fun theRealTeamGetsFiveDifferentColours() {
        // Con la paleta de ocho y `hashCode()` caían en tres colores: los nombres del equipo
        // comparten prefijos y longitud, que es justo lo que ese hash agrupa.
        val colores = equipo.map { colorDePersona(it) }.distinct()
        assertEquals(equipo.size, colores.size, "hay personas compartiendo color")
    }

    @Test
    fun theInitialsAreTheRealIdentifier() {
        // El color es una ayuda; lo que distingue son las iniciales. Aunque dos compartan color,
        // tienen que leerse distinto.
        assertEquals(equipo.size, equipo.map { iniciales(it) }.distinct().size)
    }

    @Test
    fun accentsDoNotChangeTheInitial() {
        // "Tomás" y "Tomas" son la misma persona escrita distinto; la inicial tiene que coincidir.
        assertEquals(iniciales("Tomas Rivero"), iniciales("Tomás Rivero"))
    }
}
