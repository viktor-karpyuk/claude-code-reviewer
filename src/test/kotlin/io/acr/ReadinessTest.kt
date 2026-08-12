package io.acr

import io.acr.claude.ReviewDepth
import io.acr.data.Finding
import io.acr.data.Resolution
import io.acr.data.ReviewRecord
import io.acr.data.ReviewStatus
import io.acr.forge.PrState
import io.acr.forge.PullRequest
import io.acr.ui.review.ConversationThread
import io.acr.ui.review.ThreadState
import io.acr.ui.review.mergeReadiness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * El porcentaje de "listo para mergear".
 *
 * Sale de sumar cosas concretas, no de una estimación: un número que no se puede desarmar en
 * "esto sí, esto no" es decorativo, y peor si de él depende mergear.
 */
class ReadinessTest {

    private fun pr(head: String = "nuevo", estado: PrState = PrState.OPEN) = PullRequest(
        id = 7, title = "PR", author = "dev", sourceBranch = "feat", targetBranch = "main",
        headSha = head, commentCount = 0, updatedOn = "", url = "", state = estado,
    )

    private fun review(head: String = "viejo") = ReviewRecord(
        id = "rev", repoId = "r", prId = 7, prTitle = "PR", headSha = head,
        status = ReviewStatus.DONE, body = "b", error = null, sessionId = null, costUsd = null,
        publishedUrl = null, createdAt = "2026-08-11T00:00:00Z", depth = ReviewDepth.LIGHT,
        projectKind = null, model = "haiku", auto = false, deniedTools = null,
    )

    private fun finding(id: String, resolucion: Resolution? = null, publicado: Boolean = true) = Finding(
        id, "rev", 7, "src/A.kt", 1, "major", "t", "b",
        publishedId = if (publicado) "c$id" else null, publishedUrl = null,
        resolution = resolucion,
    )

    private fun hilo(estado: ThreadState) = ConversationThread(
        findingId = "h", filePath = "src/A.kt", lineNo = 1, title = "t", severity = "major",
        question = "q", entries = emptyList(), draft = null, resolution = null,
        resolutionNote = null, state = estado,
    )

    @Test
    fun everythingDoneIsAHundred() {
        val r = mergeReadiness(
            pr(), review(), emptyList(),
            listOf(finding("1", Resolution.RESOLVED)),
            finalPassDone = true, finalPassBlockers = 0,
        )
        assertEquals(100, r.percent)
        assertTrue(r.ready)
        assertTrue(r.missing.isEmpty())
    }

    @Test
    fun theMissingPartIsAlwaysExplained() {
        // Un porcentaje sin el detalle de qué falta no sirve para decidir nada.
        val r = mergeReadiness(
            pr(), review(), emptyList(),
            listOf(finding("1", Resolution.RESOLVED)),
            finalPassDone = false, finalPassBlockers = 0,
        )
        assertFalse(r.ready)
        assertEquals(listOf("ready.finalPass"), r.missing.map { it.key })
        // 3 de 3 comentarios + 3 de commits nuevos, sobre 10 con la pasada final: 60%.
        assertEquals(60, r.percent)
    }

    @Test
    fun anUnresolvedCommentWeighsMoreThanAnUnpublishedOne() {
        // El publicado es una objeción viva; el otro es una decisión que todavía no tomaste.
        val conVivo = mergeReadiness(
            pr(), review(), emptyList(), listOf(finding("1")),
            finalPassDone = true, finalPassBlockers = 0,
        )
        val conSinPublicar = mergeReadiness(
            pr(), review(), emptyList(), listOf(finding("1", publicado = false)),
            finalPassDone = true, finalPassBlockers = 0,
        )
        assertTrue(
            conVivo.percent < conSinPublicar.percent,
            "una objeción viva tiene que restar más que un comentario sin publicar",
        )
    }

    @Test
    fun tenOpenObjectionsAreWorseThanOne() {
        val una = mergeReadiness(
            pr(), review(), emptyList(), listOf(finding("1")),
            finalPassDone = true, finalPassBlockers = 0,
        )
        val diez = mergeReadiness(
            pr(), review(), emptyList(), (1..10).map { finding("$it") },
            finalPassDone = true, finalPassBlockers = 0,
        )
        assertTrue(diez.percent < una.percent, "diez objeciones abiertas no pueden valer como una")
    }

    @Test
    fun blockersFoundInTheFinalPassCountAsNotDone() {
        val r = mergeReadiness(
            pr(), review(), emptyList(), listOf(finding("1", Resolution.RESOLVED)),
            finalPassDone = true, finalPassBlockers = 2,
        )
        assertFalse(r.ready)
        assertEquals("ready.finalPassBlockers", r.missing.single().key)
        assertEquals("2", r.missing.single().detail)
    }

    @Test
    fun pendingRepliesLowerIt() {
        val sinRespuestas = mergeReadiness(
            pr(), review(), emptyList(), listOf(finding("1", Resolution.RESOLVED)),
            finalPassDone = true, finalPassBlockers = 0,
        )
        val conRespuestas = mergeReadiness(
            pr(), review(), listOf(hilo(ThreadState.NEEDS_ANSWER)),
            listOf(finding("1", Resolution.RESOLVED)),
            finalPassDone = true, finalPassBlockers = 0,
        )
        assertEquals(100, sinRespuestas.percent)
        assertTrue(conRespuestas.percent < 100)
        assertTrue(conRespuestas.missing.any { it.key == "ready.replies" })
    }

    @Test
    fun withoutAReviewItIsZeroAndNotAlmostReady() {
        // Cero acá no significa "muy lejos": significa que no hay información para opinar.
        assertEquals(0, mergeReadiness(pr(), null, emptyList(), emptyList(), false, 0).percent)
        assertEquals(0, mergeReadiness(null, review(), emptyList(), emptyList(), true, 0).percent)
    }

    @Test
    fun aClosedPrIsNotPending() {
        val r = mergeReadiness(
            pr(estado = PrState.MERGED), review(), emptyList(), listOf(finding("1")),
            finalPassDone = false, finalPassBlockers = 3,
        )
        assertEquals(100, r.percent)
    }

    @Test
    fun ninetyNinePointSixIsNotShownAsAHundred() {
        // Con muchos ítems, el redondeo podría llegar a 100 con algo todavía pendiente: mostrar
        // 100 cuando falta algo sería exactamente la mentira que rompe la confianza en el número.
        val muchos = (1..200).map { finding("$it", Resolution.RESOLVED) } + finding("x")
        val r = mergeReadiness(
            pr(), review(), emptyList(), muchos, finalPassDone = true, finalPassBlockers = 0,
        )
        assertTrue(r.percent < 100, "mostró 100% con un comentario sin resolver")
        assertFalse(r.ready)
    }
}
