package io.acr

import io.acr.claude.DiffLine
import io.acr.claude.DiffParser
import io.acr.claude.Git
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffParserTest {

    private val dir = File("/Users/viktor/dev/kubrik/ks-erp/kubrik-erp-be")
    private val range = "origin/develop...origin/feature/pos-ar-fiscal"

    @Test
    fun parsesSyntheticHunkNumbering() {
        val raw = """
            diff --git a/x.kt b/x.kt
            index 111..222 100644
            --- a/x.kt
            +++ b/x.kt
            @@ -10,4 +10,5 @@
             contexto uno
            -viejo
            +nuevo a
            +nuevo b
             contexto dos
        """.trimIndent()
        val lines = DiffParser.parse(raw)
        val ctx1 = lines.first { it.text == "contexto uno" }
        assertEquals(10, ctx1.oldNo)
        assertEquals(10, ctx1.newNo)
        val nuevoA = lines.first { it.text == "nuevo a" }
        assertEquals(11, nuevoA.newNo)
        assertEquals(null, nuevoA.oldNo)
        val viejo = lines.first { it.text == "viejo" }
        assertEquals(11, viejo.oldNo)
        // Tras -1 +2, el contexto siguiente va 12 en viejo y 13 en nuevo.
        val ctx2 = lines.first { it.text == "contexto dos" }
        assertEquals(12, ctx2.oldNo)
        assertEquals(13, ctx2.newNo)
        println("numeración sintética OK")
    }

    /**
     * La prueba que de verdad importa: que el número de línea que calculamos coincida con el
     * archivo real en la rama del PR. Si esto falla, una nota se publica en la línea equivocada.
     */
    @Test
    fun lineNumbersMatchTheRealFile() {
        if (!Git.isRepo(dir)) { println("SKIP: sin clon local"); return }
        val files = runBlocking { Git.numstat(dir, range) }
            .filter { it.path.endsWith(".java") || it.path.endsWith(".kt") }
        if (files.isEmpty()) { println("SKIP: sin archivos de código"); return }

        var checked = 0
        files.take(3).forEach { f ->
            val lines = DiffParser.parse(runBlocking { Git.diffFile(dir, range, f.path) })
            val real = ProcessBuilder("git", "show", "origin/feature/pos-ar-fiscal:${f.path}")
                .directory(dir).redirectErrorStream(true).start()
                .inputStream.bufferedReader().readLines()
            if (real.isEmpty()) return@forEach

            // Tomamos líneas de contexto y agregadas: ambas existen en el lado nuevo.
            lines.filter {
                (it.kind == DiffLine.Kind.CONTEXT || it.kind == DiffLine.Kind.ADDED) &&
                    it.newNo != null && it.text.isNotBlank()
            }.take(8).forEach { dl ->
                val actual = real.getOrNull(dl.newNo!! - 1)
                assertEquals(dl.text, actual, "desalineado en ${f.path}:${dl.newNo}")
                checked++
            }
            println("${f.path.substringAfterLast('/')}: ${lines.size} líneas de diff, numeración verificada")
        }
        println("líneas verificadas contra el archivo real: $checked")
        assertTrue(checked > 0)
    }
}
