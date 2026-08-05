package io.acr

import io.acr.claude.ProjectKind
import io.acr.claude.ReviewDepth
import io.acr.claude.ReviewOutcome
import io.acr.forge.Forges
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ¿Dos reviews corren de verdad en paralelo? Corre de verdad el CLI dos veces, así que sólo con
 * ACR_E2E=1.
 */
class ParallelReviewTest {

    @Test
    fun twoReviewsRunAtTheSameTime() {
        if (System.getenv("ACR_E2E") != "1") { println("SKIP: poné ACR_E2E=1"); return }
        val ctx = AppContext.bootstrap()
        try {
            val repo = ctx.repos.list().first { it.slug == "kubrik-erp-be" }
            val prs = runBlocking { Forges.of(repo.provider).listPullRequests(repo) }.take(2)
            if (prs.size < 2) { println("SKIP: hacen falta 2 PRs"); return }
            println("lanzando en paralelo: ${prs.map { "#${it.id}" }}")

            runBlocking {
                var maxSimultaneous = 0
                val watcher = launch {
                    repeat(120) {
                        maxSimultaneous = maxOf(maxSimultaneous, ctx.engine.progress.value.size)
                        delay(500)
                    }
                }
                val started = System.currentTimeMillis()
                val results = prs.map { pr ->
                    async { ctx.engine.review(repo, pr, ReviewDepth.LIGHT, ProjectKind.BACKEND, "haiku") }
                }.awaitAll()
                val seconds = (System.currentTimeMillis() - started) / 1000
                watcher.cancel()

                results.forEachIndexed { i, r ->
                    val label = when (r) {
                        is ReviewOutcome.Ok -> "OK, US$ ${r.record.costUsd}"
                        is ReviewOutcome.Error -> "ERROR: ${r.message.take(70)}"
                    }
                    println("  PR #${prs[i].id} -> $label")
                }
                println("máximo simultáneo observado: $maxSimultaneous")
                println("tiempo total de las dos: ${seconds}s")
                assertTrue(maxSimultaneous >= 2, "no corrieron en paralelo (máx $maxSimultaneous)")
            }
        } finally {
            ctx.close()
        }
    }
}
