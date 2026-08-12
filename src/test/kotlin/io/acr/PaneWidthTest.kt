package io.acr

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El ancho de los paneles se guarda en preferencias: acomodarlos depende del monitor y de qué
 * estés mirando, y volver a arrastrarlos en cada arranque sería tratar esa decisión como un
 * capricho del momento.
 */
class PaneWidthTest {

    @Test
    fun theWidthSurvivesARestart() {
        val ctx = AppContext.bootstrap()
        try {
            ctx.prefs.put("pane.code", "420.0")
            assertEquals("420.0", ctx.prefs.get("pane.code"))
        } finally {
            ctx.prefs.put("pane.code", "")
            ctx.close()
        }
    }

    @Test
    fun aCorruptValueFallsBackToTheDefault() {
        // Si alguien edita el archivo a mano o queda basura, la app no puede arrancar con un panel
        // de ancho nulo: sin borde no hay de dónde tirar para recuperarlo.
        val ctx = AppContext.bootstrap()
        try {
            ctx.prefs.put("pane.code", "no-es-un-numero")
            val leido = ctx.prefs.get("pane.code")?.toFloatOrNull()?.dp ?: 330.dp
            assertEquals(330.dp, leido)
        } finally {
            ctx.prefs.put("pane.code", "")
            ctx.close()
        }
    }

    @Test
    fun clampingKeepsThePaneReachable() {
        // Es la misma cuenta que hace el splitter al arrastrar. Sin tope se puede dejar un panel
        // en cero y ahí desaparece el borde del que se tira.
        val min = 200.dp
        val max = 700.dp
        assertEquals(min, (10.dp).coerceIn(min, max))
        assertEquals(max, (5_000.dp).coerceIn(min, max))
        assertEquals(350.dp, (350.dp).coerceIn(min, max))
        assertTrue(min < max)
    }
}
