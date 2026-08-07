package io.acr

import io.acr.claude.ProjectKind
import io.acr.claude.ReviewDepth
import io.acr.data.Finding
import io.acr.forge.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Las dos vistas de un PR —Review y Código— tienen que hablar de la misma review.
 *
 * Antes no: la de Review tomaba la última DONE y la de Código la última de cualquier estado. Con
 * una corrida fallida encima —el caso del PR #151, que estaba así en la base real— la vista de
 * código mostraba cero hallazgos mientras la de Review mostraba los de la última buena, y publicar
 * desde una no se reflejaba en la otra.
 */
class VigentReviewTest {

    private fun conRepo(block: (AppContext, String, Long) -> Unit) {
        val ctx = AppContext.bootstrap()
        try {
            val prId = System.nanoTime() % 100_000
            val repoId = ctx.repos.create(
                "tmp-vig-$prId", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            try {
                block(ctx, repoId, prId)
            } finally {
                ctx.repos.delete(repoId)
            }
        } finally {
            ctx.close()
        }
    }

    private fun finding(reviewId: String, prId: Long, n: Int) = Finding(
        "", reviewId, prId, "src/A$n.kt", n, "major", "hallazgo $n", "cuerpo", null, null,
    )

    @Test
    fun aFailedRerunDoesNotHideTheFindingsOfTheLastGoodReview() = conRepo { ctx, repoId, prId ->
        val buena = ctx.reviews.start(
            repoId, prId, "PR", "sha1", ReviewDepth.HEAVY, ProjectKind.BACKEND, "opus", false,
        )
        ctx.reviews.finish(buena, "cuerpo", null, null)
        ctx.findings.replaceForReview(
            buena, repoId, prId, listOf(finding(buena, prId, 1), finding(buena, prId, 2)),
        )

        // Después se reintenta y falla: es exactamente el estado del PR #151.
        val fallida = ctx.reviews.start(
            repoId, prId, "PR", "sha1", ReviewDepth.HEAVY, ProjectKind.BACKEND, "opus", false,
        )
        ctx.reviews.fail(fallida, "se cortó")

        // La vista de Review toma esta.
        val vigente = ctx.reviews.latestUsableFor(repoId, prId)
        assertEquals(buena, vigente?.id)

        // Y la de Código tiene que tomar la misma: antes devolvía vacío.
        val enCodigo = ctx.findings.forLatestReview(repoId, prId)
        assertEquals(2, enCodigo.size, "la vista de código se quedó sin hallazgos")
        assertTrue(enCodigo.all { it.reviewId == buena })
        assertEquals(
            ctx.findings.forReview(vigente!!.id).map { it.id }.toSet(),
            enCodigo.map { it.id }.toSet(),
            "las dos vistas muestran hallazgos distintos",
        )
    }

    @Test
    fun publishingFromOneViewIsVisibleInTheOther() = conRepo { ctx, repoId, prId ->
        val buena = ctx.reviews.start(
            repoId, prId, "PR", "sha1", ReviewDepth.LIGHT, ProjectKind.BACKEND, "haiku", false,
        )
        ctx.reviews.finish(buena, "cuerpo", null, null)
        ctx.findings.replaceForReview(buena, repoId, prId, listOf(finding(buena, prId, 1)))
        ctx.reviews.fail(
            ctx.reviews.start(repoId, prId, "PR", "sha1", ReviewDepth.LIGHT, ProjectKind.BACKEND, "haiku", false),
            "se cortó",
        )

        // Se publica desde la vista de código…
        val desdeCodigo = ctx.findings.forLatestReview(repoId, prId).single()
        ctx.findings.markPublished(desdeCodigo.id, "c1", "https://x/c1")

        // …y la vista de Review lo ve publicado, no pendiente.
        val vigente = ctx.reviews.latestUsableFor(repoId, prId)!!
        val desdeReview = ctx.findings.forReview(vigente.id).single()
        assertEquals("c1", desdeReview.publishedId)
    }

    @Test
    fun theNewestDoneStillWinsWhenThereIsNoFailure() = conRepo { ctx, repoId, prId ->
        // Sin corridas fallidas de por medio, el comportamiento de siempre no cambia: manda la
        // última DONE y no la vieja.
        val vieja = ctx.reviews.start(
            repoId, prId, "PR", "sha1", ReviewDepth.LIGHT, ProjectKind.BACKEND, "haiku", false,
        )
        ctx.reviews.finish(vieja, "vieja", null, null)
        ctx.findings.replaceForReview(vieja, repoId, prId, listOf(finding(vieja, prId, 9)))

        val nueva = ctx.reviews.start(
            repoId, prId, "PR", "sha2", ReviewDepth.LIGHT, ProjectKind.BACKEND, "haiku", false,
        )
        ctx.reviews.finish(nueva, "nueva", null, null)
        ctx.findings.replaceForReview(nueva, repoId, prId, listOf(finding(nueva, prId, 1)))

        assertEquals(nueva, ctx.reviews.latestUsableFor(repoId, prId)?.id)
        assertEquals(listOf(nueva), ctx.findings.forLatestReview(repoId, prId).map { it.reviewId })
    }
}
