package io.acr

import io.acr.claude.ReviewDepth
import io.acr.data.Finding
import io.acr.data.LocalNote
import io.acr.data.ReplyDraft
import io.acr.data.ReplyStatus
import io.acr.data.ReviewRecord
import io.acr.data.Resolution
import io.acr.data.ReviewStatus
import io.acr.forge.PullRequest
import io.acr.ui.review.mergeBlocker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Mergear es la única acción de la app que cambia el repositorio y no tiene vuelta atrás. Estas
 * son las condiciones, y existen como test porque un error acá no se deshace desde la app.
 */
class MergeGateTest {

    private val pr = PullRequest(
        id = 7, title = "PR", author = "dev", sourceBranch = "feat", targetBranch = "main",
        headSha = "nuevo", commentCount = 0, updatedOn = "", url = "",
    )

    private fun review(head: String = "viejo") = ReviewRecord(
        id = "rev", repoId = "r", prId = 7, prTitle = "PR", headSha = head,
        status = ReviewStatus.DONE, body = "b", error = null, sessionId = null, costUsd = null,
        publishedUrl = null, createdAt = "2026-08-07T00:00:00Z", depth = ReviewDepth.LIGHT,
        projectKind = null, model = "haiku", auto = false, deniedTools = null,
    )

    private fun finding(
        publicado: Boolean = false,
        descartado: Boolean = false,
        resolucion: Resolution? = if (publicado) Resolution.RESOLVED else null,
    ) = Finding(
        "f", "rev", 7, "a.kt", 1, "major", "t", "b",
        publishedId = if (publicado) "c1" else null, publishedUrl = null,
        dismissedAt = if (descartado) "2026-08-07T00:00:00Z" else null,
        resolution = resolucion,
    )

    private fun note(publicada: Boolean) = LocalNote(
        "n", 7, "a.kt", 2, "nota", if (publicada) "c2" else null, null, "2026-08-07T00:00:00Z",
    )

    private fun reply(estado: ReplyStatus) = ReplyDraft(
        "d", "r", 7, "tc", "dev", "objeción", null, null, null, null, null, estado,
        null, null, null, "2026-08-07T00:00:00Z",
    )

    @Test
    fun everythingResolvedAndNewCommitsAllowsMerge() {
        assertNull(
            mergeBlocker(
                pr, review(), listOf(finding(publicado = true)), listOf(note(true)),
                listOf(reply(ReplyStatus.PUBLISHED)),
            ),
        )
    }

    @Test
    fun aDismissedFindingCountsAsResolved() {
        // Descartar un nitpick a propósito es resolverlo: si no, un PR terminado no se podría
        // mergear nunca por un comentario que uno decidió no mandar.
        assertNull(
            mergeBlocker(pr, review(), listOf(finding(descartado = true)), emptyList(), emptyList()),
        )
    }

    @Test
    fun anythingPendingBlocksIt() {
        assertEquals(
            "merge.pendingFindings",
            mergeBlocker(pr, review(), listOf(finding()), emptyList(), emptyList()),
        )
        assertEquals(
            "merge.pendingNotes",
            mergeBlocker(pr, review(), emptyList(), listOf(note(false)), emptyList()),
        )
        assertEquals(
            "merge.pendingReplies",
            mergeBlocker(pr, review(), emptyList(), emptyList(), listOf(reply(ReplyStatus.DRAFTED))),
        )
    }

    @Test
    fun aReviewThatFoundNothingCanBeMergedRightAway() {
        // Exigir commits nuevos sólo tiene sentido si pedimos algún cambio. Con cero comentarios
        // publicados no hay nada que corregir, y pedir un commit dejaba ese PR sin poder
        // mergearse nunca. Es el caso real de talos-apirest #1517.
        assertNull(
            mergeBlocker(
                pr, review(head = "nuevo"), emptyList(), emptyList(), emptyList(),
            ),
            "un PR sin observaciones quedó bloqueado para siempre",
        )
    }

    @Test
    fun withoutNewCommitsItDoesNotMerge() {
        // El head del PR es el mismo que se revisó: nadie corrigió nada, así que mergear sería
        // aprobar sin verificar. Es la condición que el usuario pidió: "y el código actualizado".
        assertEquals(
            "merge.noNewCommits",
            mergeBlocker(
                pr, review(head = "nuevo"), listOf(finding(publicado = true)), emptyList(), emptyList(),
            ),
        )
    }

    @Test
    fun theListAndThePrScreenApplyTheSameRule() {
        // La lista trabaja con conteos y la pantalla del PR con las listas completas. Si las dos
        // reglas divergen, la que se relaje de más habilita un merge que no se puede deshacer.
        val casos = listOf(
            Triple(listOf(finding()), emptyList<LocalNote>(), emptyList<ReplyDraft>()),
            Triple(emptyList(), listOf(note(false)), emptyList()),
            Triple(emptyList(), emptyList(), listOf(reply(ReplyStatus.DRAFTED))),
            Triple(listOf(finding(publicado = true)), listOf(note(true)), listOf(reply(ReplyStatus.PUBLISHED))),
        )
        casos.forEach { (f, n, r) ->
            val porListas = mergeBlocker(pr, review(), f, n, r)
            val porConteos = mergeBlocker(
                prHeadSha = pr.headSha,
                reviewHeadSha = review().headSha,
                hayReview = true,
                hallazgosPendientes = f.count { !it.settled },
                notasPendientes = n.count { it.publishedId == null },
                respuestasPendientes = r.count { it.status != ReplyStatus.PUBLISHED },
                comentariosPublicados = f.count {
                    it.publishedId != null && it.dismissedAt == null && it.closedAt == null
                },
                sinVerificar = f.count {
                    it.publishedId != null && it.dismissedAt == null && it.closedAt == null &&
                        it.resolution == null
                },
                noResueltos = f.count {
                    it.publishedId != null && it.dismissedAt == null && it.closedAt == null &&
                        it.resolution != null && it.resolution != Resolution.RESOLVED
                },
            )
            assertEquals(porListas, porConteos, "las dos reglas no coinciden para $f / $n / $r")
        }
    }

    @Test
    fun aPublishedCommentThatNobodyFixedBlocksTheMerge() {
        // Publicado no es resuelto. Sin esta condición, mergear era confiar en que alguien lo
        // arregló porque lo dijo.
        assertEquals(
            "merge.notResolved",
            mergeBlocker(
                pr, review(),
                listOf(finding(publicado = true, resolucion = Resolution.UNRESOLVED)),
                emptyList(), emptyList(),
            ),
        )
        // A medias tampoco alcanza.
        assertEquals(
            "merge.notResolved",
            mergeBlocker(
                pr, review(),
                listOf(finding(publicado = true, resolucion = Resolution.PARTIAL)),
                emptyList(), emptyList(),
            ),
        )
    }

    @Test
    fun withoutVerifyingItDoesNotMerge() {
        // Publicado y sin veredicto: no se sabe si se arregló, así que no se mergea.
        assertEquals(
            "merge.notVerified",
            mergeBlocker(
                pr, review(),
                listOf(finding(publicado = true, resolucion = null)),
                emptyList(), emptyList(),
            ),
        )
    }

    @Test
    fun aDismissedOneNeedsNoVerification() {
        // Lo que se descartó no se publicó, así que no hay nada que verificar contra el código.
        assertNull(
            mergeBlocker(
                pr, review(),
                listOf(finding(descartado = true, resolucion = null)),
                emptyList(), emptyList(),
            ),
        )
    }

    @Test
    fun aThreadClosedInConversationDoesNotBlockTheMerge() {
        // Cerrado hablando: no espera cambios, así que no puede frenar el merge por "sin verificar".
        val cerrado = finding(publicado = true, resolucion = null)
            .copy(closedAt = "2026-08-09T00:00:00Z")
        assertNull(mergeBlocker(pr, review(), listOf(cerrado), emptyList(), emptyList()))
    }

    @Test
    fun withoutPrOrReviewItDoesNotMerge() {
        assertEquals("merge.noPr", mergeBlocker(null, review(), emptyList(), emptyList(), emptyList()))
        assertEquals("merge.noReview", mergeBlocker(pr, null, emptyList(), emptyList(), emptyList()))
    }
}
