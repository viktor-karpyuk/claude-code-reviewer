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
    /**
     * Hay algo nuevo que juzgar: código, o una respuesta.
     *
     * `becauseOfReplies` distingue por qué. Una respuesta no cambia el código, así que la
     * verificación tiene que mirar lo que dice la persona y no un diff que no existe.
     */
    data class Needed(
        val sinceSha: String,
        val pending: Int,
        val becauseOfReplies: Boolean = false,
    ) : VerificationNeed

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
    /**
     * Cuántos de nuestros comentarios publicados tienen una respuesta que todavía no se juzgó.
     *
     * Es la señal que faltaba. Una respuesta es una persona diciendo qué pasó con el hallazgo —"ya
     * está", "no aplica", "lo hago en otro PR"— y es información más fuerte que un commit: el commit
     * hay que interpretarlo, la respuesta lo dice. Sin mirarla, un PR contestado entero se quedaba
     * en "sin verificar" para siempre, porque la regla sólo esperaba código nuevo.
     */
    repliesPending: Int = 0,
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

    val sinCodigoNuevo = pr.headSha.isBlank() || pr.headSha == review.headSha
    val yaVerificadoAca = review.resolutionHead == pr.headSha

    // Una respuesta manda sobre las dos reglas de código. Alguien se tomó el trabajo de contestar:
    // eso es exactamente lo que hay que leer, y no depende de que además haya commiteado.
    if (repliesPending > 0 && (sinCodigoNuevo || yaVerificadoAca)) {
        return VerificationNeed.Needed(
            sinceSha = review.headSha,
            pending = pendientes,
            becauseOfReplies = true,
        )
    }

    // Sin commits nuevos no hay nada que juzgar: es el mismo código que ya se revisó.
    if (sinCodigoNuevo) return VerificationNeed.NotNeeded("verify.skip.noNewCommits")
    // Ya se verificó contra ESTE commit exacto: repetirlo daría lo mismo y cuesta otra corrida.
    if (yaVerificadoAca) return VerificationNeed.NotNeeded("verify.skip.alreadyVerified")
    return VerificationNeed.Needed(
        sinceSha = review.headSha,
        pending = pendientes,
        becauseOfReplies = repliesPending > 0,
    )
}
