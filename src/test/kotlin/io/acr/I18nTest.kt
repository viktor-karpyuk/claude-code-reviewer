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

/**
 * Claves repetidas dentro de un mismo idioma.
 *
 * `mapOf` se queda con la última, en silencio. Una clave escrita dos veces no es sólo desorden: la
 * segunda gana, y así el español terminó mostrando "Status" en la columna de estado durante quién
 * sabe cuánto. El compilador no dice nada y la pantalla tampoco.
 */
class I18nDuplicateTest {

    private fun duplicadas(desde: String, hasta: String?): List<String> {
        val src = java.io.File("src/main/kotlin/io/acr/i18n/I18n.kt").readText()
        val ini = src.indexOf(desde)
        val fin = hasta?.let { src.indexOf(it) }?.takeIf { it > ini } ?: src.length
        val claves = Regex("""^\s{8}"([\w.]+)" to """, RegexOption.MULTILINE)
            .findAll(src.substring(ini, fin)).map { it.groupValues[1] }.toList()
        return claves.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
    }

    @Test
    fun noKeyIsDefinedTwiceInTheSameLanguage() {
        val es = duplicadas("private val es: Map<String, String> = mapOf(", "private val en: Map<String, String> = mapOf(")
        val en = duplicadas("private val en: Map<String, String> = mapOf(", "private val tables:")
        kotlin.test.assertTrue(es.isEmpty(), "repetidas en español: $es")
        kotlin.test.assertTrue(en.isEmpty(), "repetidas en inglés: $en")
    }
}
