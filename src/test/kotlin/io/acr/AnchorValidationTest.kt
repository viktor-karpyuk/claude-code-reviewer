package io.acr

import io.acr.claude.Git
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** El helper que sostiene la validación de anclas. */
class AnchorValidationTest {

    private val dir = File("/Users/viktor/dev/kubrik/ks-erp/kubrik-erp-be")

    @Test
    fun countsLinesOfARealFileOnThePrBranch() {
        if (!Git.isRepo(dir)) { println("SKIP"); return }
        val files = runBlocking { Git.numstat(dir, "origin/develop...origin/KS-600") }
        val target = files.firstOrNull { it.path.endsWith(".java") } ?: run { println("SKIP"); return }
        val n = runBlocking { Git.fileLineCount(dir, "KS-600", target.path) }
        println("${target.path.substringAfterLast('/')}: $n líneas")
        assertTrue(n > 0, "esperaba contar líneas de un archivo real")

        // Contraste con la realidad: git show del mismo archivo.
        val real = ProcessBuilder("git", "show", "origin/KS-600:${target.path}")
            .directory(dir).redirectErrorStream(true).start()
            .inputStream.bufferedReader().readLines().size
        println("git show dice: $real")
        assertTrue(kotlin.math.abs(n - real) <= 1, "conteo $n vs real $real")
    }

    @Test
    fun unknownFileCountsZeroInsteadOfThrowing() {
        if (!Git.isRepo(dir)) { println("SKIP"); return }
        val n = runBlocking { Git.fileLineCount(dir, "KS-600", "no/existe/archivo.kt") }
        assertEquals(0, n)
        println("archivo inexistente -> $n (sin excepción)")
    }
}
