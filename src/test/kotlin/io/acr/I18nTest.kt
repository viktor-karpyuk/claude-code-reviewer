package io.acr

import io.acr.i18n.I18n
import io.acr.i18n.Lang
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class I18nTest {

    @Test
    fun englishCoversTheSpanishBase() {
        val missing = I18n.missing(Lang.EN)
        println("claves sin traducir al inglés: ${missing.size}")
        missing.take(20).forEach { println("   $it") }
        assertTrue(missing.isEmpty(), "faltan traducciones: $missing")
    }

    @Test
    fun fallsBackInsteadOfBreaking() {
        // Una clave inexistente devuelve la clave, no una excepción ni vacío.
        assertEquals("no.existe", I18n.get(Lang.EN, "no.existe"))
        // Y una traducida devuelve el idioma pedido.
        assertEquals("Dashboard", I18n.get(Lang.EN, "nav.panel"))
        assertEquals("Panel", I18n.get(Lang.ES, "nav.panel"))
    }

    @Test
    fun langCodeRoundTrip() {
        assertEquals(Lang.EN, Lang.fromCode("en"))
        assertEquals(Lang.ES, Lang.fromCode("es"))
        // Un código desconocido o nulo cae al idioma base en vez de romper.
        assertEquals(Lang.ES, Lang.fromCode("xx"))
        assertEquals(Lang.ES, Lang.fromCode(null))
    }
}
