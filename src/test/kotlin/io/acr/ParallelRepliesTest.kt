package io.acr

import io.acr.forge.Provider
import io.acr.forge.PullRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Varias respuestas se pueden lanzar juntas, pero cada una abre un subproceso de Claude Code que
 * lee el repo: si se largan diez a la vez compiten por CPU y por el límite de la cuenta. El motor
 * las encola de a tres.
 *
 * Acá se comprueba de verdad: un CLI falso anota cuándo entra y cuándo sale, y del orden de esas
 * marcas sale cuántas estuvieron corriendo a la vez.
 */
class ParallelRepliesTest {

    private fun gitRepo(): File {
        val dir = File.createTempFile("acr-repo", "").let { it.delete(); it.mkdirs(); it }
        ProcessBuilder("git", "init", "-q").directory(dir).start().waitFor()
        dir.deleteOnExit()
        return dir
    }

    /** Anota S al entrar y E al salir; entre medio duerme para que los solapamientos se noten. */
    private fun fakeCli(log: File): String {
        val script = File.createTempFile("fake-claude", ".sh")
        script.writeText(
            """
            #!/bin/sh
            cat > /dev/null
            printf 'S\n' >> "${log.absolutePath}"
            sleep 0.4
            printf 'E\n' >> "${log.absolutePath}"
            printf '%s\n' '{"type":"result","is_error":false,"result":"contestación","session_id":"s","permission_denials":[]}'
            """.trimIndent(),
        )
        script.setExecutable(true)
        script.deleteOnExit()
        return script.absolutePath
    }

    @Test
    fun sixRepliesRunThreeAtATime() {
        val ctx = AppContext.bootstrap()
        try {
            val log = File.createTempFile("acr-slots", ".log").apply { writeText(""); deleteOnExit() }
            ctx.prefs.put(AppContext.PREF_CLAUDE_BINARY, fakeCli(log))
            val dir = gitRepo()
            val prId = System.nanoTime() % 100_000
            val repoId = ctx.repos.create(
                "tmp-par-$prId", Provider.BITBUCKET, "acme", "demo", dir.absolutePath,
                null, null, null, "", false, io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            try {
                val repo = ctx.repos.get(repoId)!!
                repeat(6) { i ->
                    ctx.replies.registerIfNew(
                        repoId, prId, "their-$i", "Dev", "objeción $i", "our-$i", "hallazgo", "src/A.kt", i,
                    )
                }
                val drafts = ctx.replies.forPr(repoId, prId)
                assertEquals(6, drafts.size)

                val pr = PullRequest(
                    id = prId, title = "PR", author = "Dev", sourceBranch = "feat",
                    targetBranch = "main", headSha = "sha", commentCount = 0,
                    updatedOn = "2026-08-06T07:00:00Z", url = "",
                )

                runBlocking {
                    drafts.map { d -> async { ctx.engine.draftReply(repo, pr, d) } }.awaitAll()
                }

                // Las 6 se redactaron.
                assertEquals(6, ctx.replies.forPr(repoId, prId).count { !it.body.isNullOrBlank() })

                // Y nunca hubo más de 3 subprocesos vivos a la vez.
                var live = 0
                var peak = 0
                log.readLines().filter { it.isNotBlank() }.forEach {
                    if (it == "S") { live++; peak = maxOf(peak, live) } else live--
                }
                println("máximo simultáneo: $peak")
                assertTrue(peak in 1..3, "corrieron $peak a la vez; el tope es 3")
                // Y sí hubo paralelismo real: de a una, el pico sería 1.
                assertTrue(peak > 1, "no hubo paralelismo: pico $peak")
            } finally {
                ctx.repos.delete(repoId)
                ctx.prefs.put(AppContext.PREF_CLAUDE_BINARY, "")
            }
        } finally {
            ctx.close()
        }
    }
}
