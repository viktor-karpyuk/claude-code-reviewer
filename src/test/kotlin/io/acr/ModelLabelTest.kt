package io.acr

import io.acr.claude.ClaudeCli
import io.acr.claude.ModelCatalog
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class ModelLabelTest {

    @Test
    fun labelsIncludeTheVersionDiscoveredFromTheCli() {
        val binary = ClaudeCli.resolveBinary(null)
        val models = runBlocking { ModelCatalog.discover(binary) }
        println("modelos: $models")
        models.forEach { println("  '$it' -> '${ModelCatalog.label(it)}'") }
        println("  '' -> '${ModelCatalog.label("")}'")

        // Lo pedido: que no diga sólo "Opus" sino la generación.
        val opus = ModelCatalog.label("opus")
        assertTrue(Regex("Opus \\d").containsMatchIn(opus), "esperaba versión en el label, dio '$opus'")
        val haiku = ModelCatalog.label("haiku")
        assertTrue(Regex("Haiku \\d").containsMatchIn(haiku), "dio '$haiku'")
        // Los ids completos también se muestran lindos.
        assertTrue(ModelCatalog.label("claude-haiku-4-5") == "Haiku 4.5")
        // Y la variante de contexto no se pierde.
        assertTrue(ModelCatalog.label("opus[1m]").contains("1M"), ModelCatalog.label("opus[1m]"))
    }
}
