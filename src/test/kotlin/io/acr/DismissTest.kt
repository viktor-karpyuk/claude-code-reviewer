package io.acr

import io.acr.claude.ProjectKind
import io.acr.claude.ReviewDepth
import io.acr.data.Finding
import io.acr.forge.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Un hallazgo que se decide no publicar tiene que dejar de contar como pendiente.
 *
 * Antes sólo existía publicado o pendiente: un nitpick que uno no manda dejaba el PR figurando
 * "listo para publicar" para siempre —así quedaron tres PRs de la base real, con un `minor` sin
 * publicar cada uno— y el contador del panel nunca bajaba.
 */
class DismissTest {

    private fun conReview(block: (AppContext, String, String, Long) -> Unit) {
        val ctx = AppContext.bootstrap()
        try {
            val prId = System.nanoTime() % 100_000
            val repoId = ctx.repos.create(
                "tmp-dis-$prId", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            try {
                val reviewId = ctx.reviews.start(
                    repoId, prId, "PR", "sha", ReviewDepth.LIGHT, ProjectKind.BACKEND, "haiku", false,
                )
                ctx.reviews.finish(reviewId, "cuerpo", null, null)
                block(ctx, repoId, reviewId, prId)
            } finally {
                ctx.repos.delete(repoId)
            }
        } finally {
            ctx.close()
        }
    }

    private fun finding(reviewId: String, prId: Long, n: Int) =
        Finding("", reviewId, prId, "src/A$n.kt", n, "minor", "nitpick $n", "cuerpo", null, null)

    @Test
    fun dismissingTheLastPendingClosesTheReview() = conReview { ctx, repoId, reviewId, prId ->
        ctx.findings.replaceForReview(
            reviewId, repoId, prId, listOf(finding(reviewId, prId, 1), finding(reviewId, prId, 2)),
        )
        val hallazgos = ctx.findings.forReview(reviewId)
        ctx.findings.markPublished(hallazgos[0].id, "c1", "https://x/c1")

        // Con uno pendiente el PR sigue en la lista del panel.
        assertTrue(ctx.reviews.readyToPublish().any { it.id == reviewId })

        // Se descarta el nitpick que no se va a mandar: el PR deja de esperar algo.
        ctx.findings.dismiss(hallazgos[1].id)
        ctx.reviews.markPublishedIfComplete(reviewId, null)
        assertTrue(
            ctx.reviews.readyToPublish().none { it.id == reviewId },
            "el PR sigue contando como listo para publicar",
        )
    }

    @Test
    fun dismissedCountsAsSettledInTheProgress() = conReview { ctx, repoId, reviewId, prId ->
        ctx.findings.replaceForReview(
            reviewId, repoId, prId, listOf(finding(reviewId, prId, 1), finding(reviewId, prId, 2)),
        )
        val hallazgos = ctx.findings.forReview(reviewId)
        ctx.findings.markPublished(hallazgos[0].id, "c1", null)
        ctx.findings.dismiss(hallazgos[1].id)
        assertEquals(2 to 2, ctx.reviews.findingProgressByPr(repoId)[prId])
    }

    @Test
    fun undoingBringsItBackAsPending() = conReview { ctx, repoId, reviewId, prId ->
        ctx.findings.replaceForReview(reviewId, repoId, prId, listOf(finding(reviewId, prId, 1)))
        val f = ctx.findings.forReview(reviewId).single()
        ctx.findings.dismiss(f.id)
        assertTrue(ctx.findings.forReview(reviewId).single().settled)

        ctx.findings.restore(f.id)
        val vuelto = ctx.findings.forReview(reviewId).single()
        assertTrue(!vuelto.settled, "descartar no se pudo deshacer")
    }

    @Test
    fun aPublishFailureIsRemembered() = conReview { ctx, repoId, reviewId, prId ->
        // El error vivía en un snackbar que se iba solo: el hallazgo quedaba pendiente sin que
        // nadie supiera por qué.
        ctx.findings.replaceForReview(reviewId, repoId, prId, listOf(finding(reviewId, prId, 1)))
        val f = ctx.findings.forReview(reviewId).single()
        ctx.findings.failPublish(f.id, "HTTP 400 — línea fuera del diff")

        val guardado = ctx.findings.forReview(reviewId).single()
        assertTrue(guardado.publishError!!.contains("400"))
        assertTrue(!guardado.settled, "un fallo no puede contar como resuelto")

        // Y al publicarse de verdad, el error se limpia.
        ctx.findings.markPublished(f.id, "c1", null)
        assertEquals(null, ctx.findings.forReview(reviewId).single().publishError)
    }
}
