package io.acr.ui.review

import io.acr.data.Finding
import io.acr.data.ReviewRecord
import io.acr.forge.PrState
import io.acr.forge.PullRequest

/**
 * Una cosa que falta —o que ya está— para poder mergear, con cuánto pesa.
 *
 * El porcentaje sale de sumar esto, no de una estimación: un número que no se puede desarmar en
 * "esto sí, esto no" es decorativo, y peor todavía si de él depende mergear.
 */
data class ReadinessItem(
    val key: String,
    val weight: Int,
    val done: Boolean,
    /** Con qué se completa, cuando el nombre solo no alcanza: "3 de 5", un archivo, etc. */
    val detail: String = "",
)

/**
 * Cuán listo está el PR para mergearse, de 0 a 100, y por qué.
 *
 * Los pesos dicen qué importa más: un comentario publicado sin resolver pesa el triple que uno
 * que ni se publicó, porque el primero es una objeción viva y el segundo una decisión que todavía
 * no tomaste. La pasada final pesa como un bloqueante entero: es lo último que mira el código
 * completo antes de mergear.
 */
data class Readiness(
    val percent: Int,
    val items: List<ReadinessItem>,
) {
    /** Lo que falta, en orden de peso: es lo que se muestra debajo del número. */
    val missing: List<ReadinessItem> get() = items.filter { !it.done }.sortedByDescending { it.weight }

    val ready: Boolean get() = percent == 100
}

/**
 * @param finalPassDone si la pasada final corrió sobre el commit actual.
 * @param finalPassBlockers cuántos bloqueantes encontró esa pasada.
 */
fun mergeReadiness(
    pr: PullRequest?,
    review: ReviewRecord?,
    threads: List<ConversationThread>,
    findings: List<Finding>,
    finalPassDone: Boolean,
    finalPassBlockers: Int,
): Readiness {
    // Sin PR o sin review no hay nada que medir: 0 no es "casi listo", es "no hay información".
    if (pr == null || review == null) {
        return Readiness(0, listOf(ReadinessItem("ready.noReview", 1, false)))
    }
    if (pr.state != PrState.OPEN) {
        return Readiness(100, listOf(ReadinessItem("ready.notOpen", 1, true)))
    }

    val items = mutableListOf<ReadinessItem>()

    // Cada comentario publicado que sigue vivo. Pesa por unidad: diez objeciones abiertas no
    // pueden valer lo mismo que una.
    val publicados = findings.filter {
        it.publishedId != null && it.dismissedAt == null && it.closedAt == null
    }
    publicados.forEach { f ->
        items += ReadinessItem(
            key = "ready.finding",
            weight = 3,
            // Descartado con motivo también cierra: no va a cambiar nunca en este diff, y tenerlo
            // como pendiente para siempre hace que la lista no signifique nada.
            done = f.resolution?.closed == true,
            detail = f.filePath.substringAfterLast('/') + (f.lineNo?.let { ":$it" } ?: ""),
        )
    }

    // Comentarios que ni se publicaron: falta una decisión tuya, no del autor.
    findings.filter { it.publishedId == null && it.dismissedAt == null && it.closedAt == null }
        .forEach { f ->
            items += ReadinessItem(
                key = "ready.unpublished",
                weight = 1,
                done = false,
                detail = f.filePath.substringAfterLast('/'),
            )
        }

    // Respuestas suyas esperando que contestes.
    val sinContestar = threads.count { it.state == ThreadState.NEEDS_ANSWER || it.state == ThreadState.DRAFT_READY }
    if (sinContestar > 0) {
        items += ReadinessItem("ready.replies", 2 * sinContestar, false, sinContestar.toString())
    }

    // Que haya código nuevo después de los comentarios: sin eso nadie corrigió nada.
    if (publicados.isNotEmpty()) {
        items += ReadinessItem(
            key = "ready.newCommits",
            weight = 3,
            done = review.headSha.isBlank() || review.headSha != pr.headSha,
        )
    }

    // La pasada final, sobre el commit actual. Un bloqueante encontrado ahí no se compensa con
    // nada: cuenta como no hecha y además se muestra aparte.
    items += ReadinessItem(
        key = if (finalPassBlockers > 0) "ready.finalPassBlockers" else "ready.finalPass",
        weight = 4,
        done = finalPassDone && finalPassBlockers == 0,
        detail = if (finalPassBlockers > 0) finalPassBlockers.toString() else "",
    )

    val total = items.sumOf { it.weight }
    val hecho = items.filter { it.done }.sumOf { it.weight }
    // Se redondea hacia abajo salvo cuando está todo: 99,6% no puede mostrarse como 100.
    val pct = if (total == 0) 100 else if (hecho == total) 100 else (hecho * 100) / total
    return Readiness(pct, items)
}
