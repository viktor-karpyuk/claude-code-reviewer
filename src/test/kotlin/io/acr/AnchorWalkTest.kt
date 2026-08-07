package io.acr

import io.acr.claude.Git
import io.acr.data.Finding
import io.acr.data.LocalNote
import io.acr.ui.code.Anchor
import io.acr.ui.code.buildAnchors
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * El recorrido de la vista de código: se avanza de observación en observación y los archivos van
 * cambiando solos. Para que eso tenga sentido el orden tiene que ser el de lectura —archivo del
 * diff, después línea—, no el orden en que la review devolvió los hallazgos.
 */
class AnchorWalkTest {

    private val files = listOf(
        Git.FileChange("src/a.kt", 10, 0),
        Git.FileChange("src/b.kt", 5, 1),
        Git.FileChange("src/c.kt", 1, 1),
    )

    private fun f(id: String, path: String, line: Int?) =
        Finding(id, "rev", 1, path, line, "MAJOR", "hallazgo $id", "cuerpo", null, null)

    private fun n(id: String, path: String, line: Int?) =
        LocalNote(id, 1, path, line, "nota $id\nsegunda línea", null, null, "2026-08-06T00:00:00Z")

    @Test
    fun walksInDiffOrderNotInReviewOrder() {
        // La review los devolvió mezclados; el recorrido tiene que ir a.kt, b.kt, c.kt.
        val findings = listOf(f("3", "src/c.kt", 4), f("1", "src/a.kt", 80), f("2", "src/b.kt", 12))
        val orden = buildAnchors(files, findings, emptyList()).map { it.filePath to it.lineNo }
        assertEquals(listOf("src/a.kt" to 80, "src/b.kt" to 12, "src/c.kt" to 4), orden)
    }

    @Test
    fun withinAFileItGoesTopDown() {
        val findings = listOf(f("x", "src/a.kt", 200), f("y", "src/a.kt", 12), f("z", "src/a.kt", 55))
        assertEquals(listOf(12, 55, 200), buildAnchors(files, findings, emptyList()).map { it.lineNo })
    }

    @Test
    fun findingsAndNotesShareTheWalk() {
        // Para recorrerlas da igual quién las escribió: van intercaladas por línea.
        val anchors = buildAnchors(
            files,
            listOf(f("h1", "src/a.kt", 30)),
            listOf(n("n1", "src/a.kt", 10), n("n2", "src/b.kt", 2)),
        )
        assertEquals(
            listOf(
                Anchor.Kind.NOTA to 10,
                Anchor.Kind.HALLAZGO to 30,
                Anchor.Kind.NOTA to 2,
            ),
            anchors.map { it.kind to it.lineNo },
        )
    }

    @Test
    fun anEntryWithoutALineGoesLastWithinItsFile() {
        // Un hallazgo de archivo entero no tiene adónde saltar: se lee al final de ese archivo.
        val findings = listOf(f("sin", "src/a.kt", null), f("con", "src/a.kt", 99))
        assertEquals(listOf(99, null), buildAnchors(files, findings, emptyList()).map { it.lineNo })
    }

    @Test
    fun aFileNoLongerInTheDiffDoesNotBreakTheOrder() {
        // El PR pudo cambiar desde que corrió la review: ese hallazgo va al final, no primero.
        val findings = listOf(f("viejo", "src/borrado.kt", 3), f("vigente", "src/b.kt", 7))
        val orden = buildAnchors(files, findings, emptyList()).map { it.filePath }
        assertEquals(listOf("src/b.kt", "src/borrado.kt"), orden)
    }

    @Test
    fun theOrderIsStable() {
        // Dos observaciones en la misma línea no pueden intercambiarse entre recomposiciones: el
        // "3 de 7" señalaría cosas distintas cada vez.
        val findings = listOf(f("b", "src/a.kt", 5), f("a", "src/a.kt", 5))
        assertEquals(listOf("a", "b"), buildAnchors(files, findings, emptyList()).map { it.id })
        assertEquals(listOf("a", "b"), buildAnchors(files, findings.reversed(), emptyList()).map { it.id })
    }
}
