package io.acr

import io.acr.claude.ClaudeCli
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El bug del PR #151: el modelo contestó "no puedo acceder al diff, los permisos están en modo
 * don't ask" sin haber intentado un solo comando, con `permission_denials` vacío. Esa disculpa se
 * guardó como review terminada, publicable y contada como trabajo hecho.
 *
 * La señal objetiva es cuántas herramientas usó el subproceso: cero significa que no abrió el diff
 * ni leyó un archivo, así que lo que devolvió no puede describir el PR. Acá se verifica que el
 * contador refleja el stream real del CLI, con un binario falso que emite los mismos eventos.
 */
class ToolUseGuardTest {

    private fun fakeCli(lines: List<String>): String {
        val script = File.createTempFile("fake-claude", ".sh")
        script.writeText(
            buildString {
                appendLine("#!/bin/sh")
                // El prompt llega por stdin: si no se consume, el proceso padre se bloquea.
                appendLine("cat > /dev/null")
                lines.forEach { appendLine("cat <<'ACREOF'\n$it\nACREOF") }
            },
        )
        script.setExecutable(true)
        script.deleteOnExit()
        return script.absolutePath
    }

    private fun run(lines: List<String>) = runBlocking {
        ClaudeCli.run(
            binary = fakeCli(lines),
            workDir = File(System.getProperty("java.io.tmpdir")),
            prompt = "revisá esto",
            model = null,
            allowedTools = emptyList(),
            disallowedTools = emptyList(),
        )
    }

    private fun result(text: String) =
        """{"type":"result","is_error":false,"result":"$text","session_id":"s1","permission_denials":[]}"""

    private fun toolUse(cmd: String) =
        """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"$cmd"}}]}}"""

    @Test
    fun aRefusalWithoutASingleToolCallIsDetectable() {
        val r = run(
            listOf(
                """{"type":"system","subtype":"init","session_id":"s1","model":"haiku"}""",
                """{"type":"assistant","message":{"content":[{"type":"text","text":"No se pudo completar la revisión: se requiere acceso a git diff."}]}}""",
                result("No se pudo completar la revision."),
            ),
        )
        // El CLI reporta éxito y sin permisos denegados: exactamente lo que pasó en el PR #151.
        assertTrue(r.ok, "el CLI reportó la corrida como buena")
        assertTrue(r.permissionDenials.isEmpty(), "no hubo nada denegado")
        // Y sin embargo no tocó una sola herramienta, que es lo que la delata.
        assertEquals(0, r.toolUses)
    }

    @Test
    fun aRealReviewCountsItsToolCalls() {
        val r = run(
            listOf(
                """{"type":"system","subtype":"init","session_id":"s1","model":"opus"}""",
                toolUse("git diff --stat origin/develop...origin/KS-600"),
                """{"type":"assistant","message":{"content":[{"type":"text","text":"Leyendo el archivo"}]}}""",
                toolUse("git diff origin/develop...origin/KS-600"),
                result("review de verdad"),
            ),
        )
        assertTrue(r.ok)
        assertEquals(2, r.toolUses)
    }
}
