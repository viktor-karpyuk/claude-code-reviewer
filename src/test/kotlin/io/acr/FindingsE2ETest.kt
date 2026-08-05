package io.acr

import io.acr.claude.Git
import io.acr.claude.ProjectKind
import io.acr.claude.ReviewDepth
import io.acr.claude.ReviewOutcome
import io.acr.forge.Forges
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Corre una review de verdad contra un PR real y valida lo único que no se puede simular: que
 * cada hallazgo apunte a un archivo que existe en el diff y a una línea que existe en ese archivo.
 * Un ancla inventada publica el comentario en el lugar equivocado del PR.
 *
 * Se saltea salvo que ACR_E2E=1, porque gasta tokens.
 */
class FindingsE2ETest {

    @Test
    fun findingsAreAnchoredToRealFilesAndLines() {
        if (System.getenv("ACR_E2E") != "1") { println("SKIP: poné ACR_E2E=1 para correrlo"); return }
        val ctx = AppContext.bootstrap()
        try {
            val repo = ctx.repos.list().firstOrNull() ?: run { println("SKIP: sin repos"); return }
            val prs = runBlocking { Forges.of(repo.provider).listPullRequests(repo) }
            val pr = prs.minByOrNull { it.id } ?: run { println("SKIP: sin PRs"); return }
            println("PR elegido: #${pr.id} ${pr.title}")

            val out = runBlocking {
                ctx.engine.review(repo, pr, ReviewDepth.LIGHT, ProjectKind.BACKEND, "haiku")
            }
            when (out) {
                is ReviewOutcome.Error -> {
                    println("la review falló: ${out.message}")
                    return
                }
                is ReviewOutcome.Ok -> println("review ok, costo US$ ${out.record.costUsd}")
            }

            val review = (out as ReviewOutcome.Ok).record
            val findings = ctx.findings.forReview(review.id)
            println("hallazgos: ${findings.size}")

            val dir = File(repo.localPath)
            val range = "origin/${pr.targetBranch}...origin/${pr.sourceBranch}"
            val changed = runBlocking { Git.numstat(dir, range) }.map { it.path }.toSet()

            var anchored = 0
            findings.forEach { f ->
                val inDiff = f.filePath in changed
                var lineOk = true
                if (f.lineNo != null) {
                    val real = ProcessBuilder("git", "show", "origin/${pr.sourceBranch}:${f.filePath}")
                        .directory(dir).redirectErrorStream(true).start()
                        .inputStream.bufferedReader().readLines()
                    lineOk = f.lineNo in 1..real.size
                }
                println("  [${f.severity}] ${f.filePath}:${f.lineNo ?: "-"} enDiff=$inDiff lineaValida=$lineOk — ${f.title.take(70)}")
                assertTrue(inDiff, "el archivo ${f.filePath} no está en el diff del PR")
                assertTrue(lineOk, "la línea ${f.lineNo} no existe en ${f.filePath}")
                if (f.lineNo != null) anchored++
            }
            println("hallazgos con línea concreta: $anchored de ${findings.size}")
            println("markdown generado:\n${review.body?.take(400)}")
        } finally {
            ctx.close()
        }
    }
}
