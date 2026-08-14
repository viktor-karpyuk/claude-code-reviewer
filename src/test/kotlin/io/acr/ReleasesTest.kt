package io.acr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Las novedades salen del changelog empaquetado, no de la red: la pantalla tiene que funcionar sin
 * internet, que es justo cuando uno mira qué cambió porque algo anda mal.
 */
class ReleasesTest {

    @Test
    fun itSplitsTheChangelogByVersion() {
        val texto = """
            # Changelog

            ## 3.1.0

            - algo nuevo
            - otra cosa

            ## 3.0.0

            - lo primero
        """.trimIndent()
        val notas = Releases.parse(texto)
        assertEquals(listOf("3.1.0", "3.0.0"), notas.map { it.version })
        assertTrue(notas.first().body.contains("algo nuevo"))
        assertTrue(notas.first().body.contains("otra cosa"))
        // El cuerpo de una versión no se lleva el de la siguiente.
        assertTrue(!notas.first().body.contains("lo primero"))
    }

    @Test
    fun headingsThatAreNotVersionsAreIgnored() {
        // El changelog tiene encabezados de sección además de versiones; tomarlos como versión
        // llenaría la pantalla de entradas falsas.
        val texto = "## Reglas\n\ntexto\n\n## 1.0.0\n\n- primera\n"
        assertEquals(listOf("1.0.0"), Releases.parse(texto).map { it.version })
    }

    @Test
    fun aMissingOrBrokenChangelogDoesNotBreakTheScreen() {
        // Que una pantalla informativa rompa la app sería absurdo.
        assertEquals(emptyList(), Releases.parse(""))
        assertEquals(emptyList(), Releases.parse("sin encabezados de versión"))
    }

    @Test
    fun theLinksPointAtTheRightPlace() {
        assertTrue(Releases.URL.endsWith("/releases"))
        assertEquals(Releases.URL + "/tag/v12.3.4", Releases.urlFor("12.3.4"))
    }

    @Test
    fun theBundledChangelogKnowsTheRunningVersion() {
        // Si el build empaquetó el changelog, la versión que corre tiene que estar ahí: si no,
        // significa que se olvidaron de anotar el cambio.
        val notas = Releases.all()
        if (notas.isEmpty()) {
            println("SKIP: este build no trae el changelog")
            return
        }
        assertTrue(
            notas.any { it.version == AppVersion.value },
            "la versión ${AppVersion.value} no está en el changelog",
        )
        assertEquals(AppVersion.value, notas.first().version, "el changelog no arranca por la última")
    }
}
