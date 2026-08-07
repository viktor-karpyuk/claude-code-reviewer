package io.acr

import io.acr.claude.ProjectKind
import io.acr.claude.ReviewDepth
import io.acr.data.Finding
import io.acr.forge.PrComment
import io.acr.forge.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * El bug: publicar una respuesta no figuraba como que contestamos.
 *
 * Al sincronizar el hilo del PR sólo se pasaban como "nuestros" los ids del comentario resumen.
 * Los hallazgos publicados inline y nuestras propias respuestas quedaban guardados con
 * `is_ours = 0`, así que el historial los mostraba como comentarios de otra persona. Y, peor: como
 * su id no figuraba como nuestro, una respuesta colgada de una contestación nuestra no se detectaba
 * jamás — la conversación se cortaba después de la primera vuelta.
 */
class OursFlagTest {

    private fun comment(id: String, author: String, parent: String? = null) = PrComment(
        commentId = id, author = author, body = "texto de $id", inlinePath = "src/A.kt",
        inlineLine = 10, deleted = false, createdOn = "2026-08-06T07:00:0${id.last()}Z",
        parentId = parent,
    )

    @Test
    fun ourFindingsAndOurRepliesAreFlaggedAsOurs() {
        val ctx = AppContext.bootstrap()
        try {
            val prId = System.nanoTime() % 100_000
            val repoId = ctx.repos.create(
                "tmp-ours-$prId", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            try {
                val reviewId = ctx.reviews.start(
                    repoId, prId, "PR", "sha", ReviewDepth.LIGHT, ProjectKind.BACKEND, "haiku", false,
                )
                ctx.reviews.finish(reviewId, "cuerpo", null, null)

                // Un hallazgo nuestro, publicado como comentario inline "c1".
                ctx.findings.replaceForReview(
                    reviewId, repoId, prId,
                    listOf(
                        Finding("", reviewId, prId, "src/A.kt", 10, "MAJOR", "t", "b", null, null),
                    ),
                )
                ctx.findings.markPublished(ctx.findings.forReview(reviewId).single().id, "c1", null)

                // El dev responde "c2" colgado de nuestro hallazgo, y nosotros contestamos "c3".
                ctx.replies.registerIfNew(repoId, prId, "c2", "Dev", "no estoy de acuerdo", "c1", "b", "src/A.kt", 10)
                val draft = ctx.replies.forPr(repoId, prId).single()
                ctx.replies.markPublished(draft.id, "c3", "https://x/c3")

                val thread = listOf(
                    comment("c1", "Viktor Karpyuk"),
                    comment("c2", "Dev", parent = "c1"),
                    comment("c3", "Viktor Karpyuk", parent = "c2"),
                    comment("c4", "Dev", parent = "c3"),
                )
                val ours = buildSet {
                    addAll(ctx.publications.forPr(repoId, prId).mapNotNull { it.commentId })
                    addAll(ctx.findings.forPr(repoId, prId).mapNotNull { it.publishedId })
                    addAll(ctx.replies.publishedIds(repoId, prId))
                }
                ctx.comments.sync(repoId, prId, thread, ours)

                val stored = ctx.comments.forPr(repoId, prId).associateBy { it.commentId }
                assertTrue(stored.getValue("c1").ours, "el hallazgo publicado no figura como nuestro")
                assertTrue(stored.getValue("c3").ours, "la respuesta publicada no figura como nuestra")
                assertFalse(stored.getValue("c2").ours)
                assertFalse(stored.getValue("c4").ours)

                // Y la segunda vuelta se detecta: c4 cuelga de nuestra respuesta c3.
                ctx.engine.detectReplies(repoId, prId)
                val pending = ctx.replies.forPr(repoId, prId).map { it.theirCommentId }.toSet()
                assertTrue("c4" in pending, "no se detectó la respuesta a nuestra contestación")
                assertEquals(setOf("c2", "c4"), pending)
            } finally {
                ctx.repos.delete(repoId)
            }
        } finally {
            ctx.close()
        }
    }
}
