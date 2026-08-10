package io.acr.claude

import io.acr.data.Finding
import io.acr.data.ReviewRecord
import io.acr.data.ReviewStatus
import io.acr.forge.PrState
import io.acr.forge.PullRequest

/**
 * Si hace falta verificar que los comentarios fueron atendidos, y por qué no cuando no hace falta.
 *
 * El barrido corre cada pocos minutos y cada verificación cuesta una corrida del modelo, así que
 * la pregunta importante no es cuándo verificar sino cuándo NO: sin esto, o se repetiría en cada
 * vuelta quemando plata sobre un PR que no cambió, o no se repetiría nunca.
 */
sealed interface VerificationNeed {
    /** Hay commits nuevos y comentarios que todavía no se juzgaron contra ellos. */
    data class Needed(val sinceSha: String, val pending: Int) : VerificationNeed

    /** No hace falta, y el motivo es explícito para poder mostrarlo o registrarlo. */
    data class NotNeeded(val reasonKey: String) : VerificationNeed
}

/**
 * @param verifiedHead contra qué commit se verificó por última vez, si se verificó.
 */
fun verificationNeed(
    pr: PullRequest,
    review: ReviewRecord?,
    findings: List<Finding>,
): VerificationNeed {
    // Un PR cerrado o mergeado ya no se está revisando.
    if (pr.state != PrState.OPEN) return VerificationNeed.NotNeeded("verify.skip.notOpen")
    if (review == null || review.status != ReviewStatus.DONE) {
        return VerificationNeed.NotNeeded("verify.skip.noReview")
    }
    // Publicados y todavía sin resolver: lo único que tiene sentido volver a mirar. Lo descartado
    // nunca se publicó, y lo ya marcado corregido no se re-juzga salvo que cambie el código.
    val pendientes = findings.count {
        it.publishedId != null && it.dismissedAt == null &&
            it.resolution != io.acr.data.Resolution.RESOLVED
    }
    if (pendientes == 0) return VerificationNeed.NotNeeded("verify.skip.nothingPending")

    // Sin commits nuevos no hay nada que juzgar: es el mismo código que ya se revisó.
    if (pr.headSha.isBlank() || pr.headSha == review.headSha) {
        return VerificationNeed.NotNeeded("verify.skip.noNewCommits")
    }
    // Ya se verificó contra ESTE commit exacto: repetirlo daría lo mismo y cuesta otra corrida.
    if (review.resolutionHead == pr.headSha) {
        return VerificationNeed.NotNeeded("verify.skip.alreadyVerified")
    }
    return VerificationNeed.Needed(sinceSha = review.headSha, pending = pendientes)
}
