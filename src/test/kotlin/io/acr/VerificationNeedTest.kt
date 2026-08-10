package io.acr

import io.acr.claude.ReviewDepth
import io.acr.claude.VerificationNeed
import io.acr.claude.verificationNeed
import io.acr.data.Finding
import io.acr.data.Resolution
import io.acr.data.ReviewRecord
import io.acr.data.ReviewStatus
import io.acr.forge.PrState
import io.acr.forge.PullRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Cuándo hace falta volver a verificar, y —sobre todo— cuándo no.
 *
 * El barrido corre cada pocos minutos y cada verificación cuesta una corrida del modelo. Sin una
 * regla explícita, o se repite en cada vuelta sobre un PR que no cambió, o no se repite nunca.
 */
class VerificationNeedTest {

    private fun pr(head: String = "nuevo", estado: PrState = PrState.OPEN) = PullRequest(
        id = 7, title = "PR", author = "dev", sourceBranch = "feat", targetBranch = "main",
        headSha = head, commentCount = 0, updatedOn = "", url = "", state = estado,
    )

    private fun review(head: String = "viejo", verificadoEn: String? = null) = ReviewRecord(
        id = "rev", repoId = "r", prId = 7, prTitle = "PR", headSha = head,
        status = ReviewStatus.DONE, body = "b", error = null, sessionId = null, costUsd = null,
        publishedUrl = null, createdAt = "2026-08-09T00:00:00Z", depth = ReviewDepth.LIGHT,
        projectKind = null, model = "haiku", auto = false, deniedTools = null,
        resolutionHead = verificadoEn,
    )

    private fun finding(
        publicado: Boolean = true,
        descartado: Boolean = false,
        resolucion: Resolution? = null,
    ) = Finding(
        "f", "rev", 7, "a.kt", 1, "major", "t", "b",
        publishedId = if (publicado) "c1" else null, publishedUrl = null,
        dismissedAt = if (descartado) "2026-08-09T00:00:00Z" else null,
        resolution = resolucion,
    )

    @Test
    fun newCommitsOverPendingCommentsNeedChecking() {
        val need = verificationNeed(pr(), review(), listOf(finding()))
        assertIs<VerificationNeed.Needed>(need)
        assertEquals("viejo", need.sinceSha)
        assertEquals(1, need.pending)
    }

    @Test
    fun withoutNewCommitsThereIsNothingToJudge() {
        // Mismo código que ya se revisó.
        assertEquals(
            "verify.skip.noNewCommits",
            (verificationNeed(pr(head = "viejo"), review(head = "viejo"), listOf(finding()))
                as VerificationNeed.NotNeeded).reasonKey,
        )
    }

    @Test
    fun itIsNotRepeatedForTheSameCommit() {
        // Ya se verificó contra este commit exacto: repetirlo daría lo mismo y cuesta otra corrida.
        // Sin esta regla el barrido lo re-verificaría cada pocos minutos, para siempre.
        assertEquals(
            "verify.skip.alreadyVerified",
            (verificationNeed(pr(head = "nuevo"), review(verificadoEn = "nuevo"), listOf(finding()))
                as VerificationNeed.NotNeeded).reasonKey,
        )
        // Pero si llega OTRO commit, vuelve a hacer falta.
        assertIs<VerificationNeed.Needed>(
            verificationNeed(pr(head = "mas-nuevo"), review(verificadoEn = "nuevo"), listOf(finding())),
        )
    }

    @Test
    fun nothingPendingNeedsNoChecking() {
        // Ya corregido: no se re-juzga salvo que cambie el código.
        assertEquals(
            "verify.skip.nothingPending",
            (verificationNeed(pr(), review(), listOf(finding(resolucion = Resolution.RESOLVED)))
                as VerificationNeed.NotNeeded).reasonKey,
        )
        // Descartado: nunca se publicó, no hay nada que buscar en el diff.
        assertEquals(
            "verify.skip.nothingPending",
            (verificationNeed(pr(), review(), listOf(finding(publicado = false, descartado = true)))
                as VerificationNeed.NotNeeded).reasonKey,
        )
        // Sin publicar: todavía no es una pregunta hecha al autor.
        assertEquals(
            "verify.skip.nothingPending",
            (verificationNeed(pr(), review(), listOf(finding(publicado = false)))
                as VerificationNeed.NotNeeded).reasonKey,
        )
    }

    @Test
    fun aPartialVerdictIsRevisited() {
        // "A medias" sigue siendo una pregunta abierta: con commits nuevos hay que volver a mirar.
        assertIs<VerificationNeed.Needed>(
            verificationNeed(pr(), review(), listOf(finding(resolucion = Resolution.PARTIAL))),
        )
        assertIs<VerificationNeed.Needed>(
            verificationNeed(pr(), review(), listOf(finding(resolucion = Resolution.UNRESOLVED))),
        )
    }

    @Test
    fun aClosedPrIsNotBeingReviewedAnymore() {
        assertEquals(
            "verify.skip.notOpen",
            (verificationNeed(pr(estado = PrState.MERGED), review(), listOf(finding()))
                as VerificationNeed.NotNeeded).reasonKey,
        )
    }

    @Test
    fun withoutAFinishedReviewThereIsNothingToVerifyAgainst() {
        assertEquals(
            "verify.skip.noReview",
            (verificationNeed(pr(), null, emptyList()) as VerificationNeed.NotNeeded).reasonKey,
        )
    }

    @Test
    fun everyNotNeededReasonHasATranslation() {
        // Los motivos se muestran; una clave sin traducir se vería como "verify.skip.x".
        val claves = listOf(
            "verify.skip.notOpen", "verify.skip.noReview", "verify.skip.nothingPending",
            "verify.skip.noNewCommits", "verify.skip.alreadyVerified",
        )
        claves.forEach {
            assertTrue(
                io.acr.i18n.I18n.get(io.acr.i18n.Lang.ES, it) != it,
                "falta la traducción de $it",
            )
        }
    }
}
