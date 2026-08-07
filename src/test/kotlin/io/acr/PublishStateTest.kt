package io.acr

import io.acr.claude.ProjectKind
import io.acr.claude.ReviewDepth
import io.acr.data.Finding
import io.acr.forge.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El bug: publicar los hallazgos uno a uno dejaba el PR figurando "lista para publicar".
 *
 * `review.published_url` sólo se escribía al publicar el comentario resumen, así que el camino
 * normal —publicar cada hallazgo anclado a su archivo y línea— nunca marcaba la review, y el PR
 * quedaba pendiente para siempre en la lista, en el panel y en el icono de la barra de menú.
 */
class PublishStateTest {

    private fun ctxWithReview(block: (AppContext, String, String, Long) -> Unit) {
        val ctx = AppContext.bootstrap()
        try {
            val prId = System.nanoTime() % 100_000
            val repoId = ctx.repos.create(
                "tmp-${prId}", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            val reviewId = ctx.reviews.start(
                repoId, prId, "PR de prueba", "abc123",
                ReviewDepth.LIGHT, ProjectKind.BACKEND, "haiku", false,
            )
            ctx.reviews.finish(reviewId, "cuerpo", "sess", 0.0)
            try {
                block(ctx, repoId, reviewId, prId)
            } finally {
                ctx.repos.delete(repoId)
            }
        } finally {
            ctx.close()
        }
    }

    private fun finding(reviewId: String, prId: Long, n: Int) = Finding(
        id = "", reviewId = reviewId, prId = prId, filePath = "src/A$n.kt", lineNo = n,
        severity = "MAJOR", title = "hallazgo $n", body = "detalle", publishedId = null,
        publishedUrl = null,
    )

    @Test
    fun publishingEveryFindingMarksTheReviewPublished() = ctxWithReview { ctx, repoId, reviewId, prId ->
        ctx.findings.replaceForReview(
            reviewId, repoId, prId, listOf(finding(reviewId, prId, 1), finding(reviewId, prId, 2)),
        )
        val stored = ctx.findings.forReview(reviewId)
        assertEquals(2, stored.size)

        // Con uno solo publicado sigue habiendo trabajo pendiente: NO se marca.
        ctx.findings.markPublished(stored[0].id, "c1", "https://x/c1")
        assertFalse(ctx.reviews.markPublishedIfComplete(reviewId, "https://x/c1"))
        assertNull(ctx.reviews.get(reviewId)?.publishedUrl, "se marcó con hallazgos sin publicar")

        // Al publicar el último, la review pasa a publicada.
        ctx.findings.markPublished(stored[1].id, "c2", "https://x/c2")
        assertTrue(ctx.reviews.markPublishedIfComplete(reviewId, "https://x/c2"))
        assertNotNull(ctx.reviews.get(reviewId)?.publishedUrl)

        // Y deja de estar en la lista de pendientes, que es lo que el usuario veía mal.
        assertTrue(ctx.reviews.readyToPublish().none { it.id == reviewId })
    }

    @Test
    fun aSecondCallDoesNotReMarkOrOverwrite() = ctxWithReview { ctx, repoId, reviewId, prId ->
        ctx.findings.replaceForReview(reviewId, repoId, prId, listOf(finding(reviewId, prId, 1)))
        val f = ctx.findings.forReview(reviewId).single()
        ctx.findings.markPublished(f.id, "c1", "https://x/c1")

        assertTrue(ctx.reviews.markPublishedIfComplete(reviewId, "https://x/c1"))
        // Publicar dos hallazgos a la vez dispara la comprobación dos veces: la segunda no debe
        // pisar la URL ya guardada ni contar como que marcó algo.
        assertFalse(ctx.reviews.markPublishedIfComplete(reviewId, "https://x/OTRA"))
        assertEquals("https://x/c1", ctx.reviews.get(reviewId)?.publishedUrl)
    }

    @Test
    fun aReviewWithoutFindingsStaysPending() = ctxWithReview { ctx, _, reviewId, _ ->
        // Sin hallazgos no hay nada inline que publicar: la review sigue esperando el comentario
        // resumen. Marcarla acá la haría desaparecer de la lista sin haberse publicado nunca.
        assertFalse(ctx.reviews.markPublishedIfComplete(reviewId, "https://x/c1"))
        assertNull(ctx.reviews.get(reviewId)?.publishedUrl)
    }

    @Test
    fun aFindingPublishedWithoutUrlStillCounts() = ctxWithReview { ctx, repoId, reviewId, prId ->
        // El proveedor a veces devuelve el comentario creado sin link. El hallazgo está publicado
        // igual, así que la review tiene que quedar publicada y no colgada como pendiente.
        ctx.findings.replaceForReview(reviewId, repoId, prId, listOf(finding(reviewId, prId, 1)))
        val f = ctx.findings.forReview(reviewId).single()
        ctx.findings.markPublished(f.id, "c1", null)

        assertTrue(ctx.reviews.markPublishedIfComplete(reviewId, null))
        assertEquals("", ctx.reviews.get(reviewId)?.publishedUrl)
        assertTrue(ctx.reviews.readyToPublish().none { it.id == reviewId })
    }

    @Test
    fun progressByPrDistinguishesPartialFromPending() = ctxWithReview { ctx, repoId, reviewId, prId ->
        ctx.findings.replaceForReview(
            reviewId, repoId, prId, listOf(finding(reviewId, prId, 1), finding(reviewId, prId, 2)),
        )
        assertEquals(2 to 0, ctx.reviews.findingProgressByPr(repoId)[prId])

        ctx.findings.markPublished(ctx.findings.forReview(reviewId).first().id, "c1", "https://x/c1")
        assertEquals(2 to 1, ctx.reviews.findingProgressByPr(repoId)[prId])
    }
}
