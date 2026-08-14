package io.acr.claude

import io.acr.data.ReviewRecord

/**
 * Qué parte del PR mira esta corrida.
 *
 * Hasta la 37.0.0 todas las reviews miraban la rama entera. En la base real eso significó que el
 * PR #149 —23 commits— se revisara 25 veces de punta a punta y costara US$ 225, el 60% de todo el
 * consumo: en la pasada 25 se volvió a leer y razonar sobre archivos que ya se habían aprobado 24
 * veces.
 */
sealed interface ReviewScope {

    /** La rama completa contra su destino. Es lo correcto la primera vez, y el respaldo siempre. */
    data object Full : ReviewScope

    /**
     * Sólo lo que llegó después de la última review, arrastrando lo que quedó abierto.
     *
     * @param sinceSha el commit que ya se revisó.
     * @param previousReviewId de dónde salen los hallazgos que se arrastran.
     */
    data class Incremental(val sinceSha: String, val previousReviewId: String) : ReviewScope
}

/** Por qué se eligió el alcance. Se muestra en el feed: una review que mira menos tiene que decirlo. */
enum class ScopeReason(val labelKey: String) {
    FIRST_REVIEW("scope.first"),
    FORCED("scope.forced"),
    SAME_COMMIT("scope.sameCommit"),
    REWRITTEN_HISTORY("scope.rewritten"),
    NEW_COMMITS("scope.newCommits"),
}

data class ScopeDecision(val scope: ReviewScope, val reason: ScopeReason)

/**
 * Decide si esta corrida puede mirar sólo lo nuevo.
 *
 * Se aísla de git y de la base para poder probar los cinco caminos sin fabricar repositorios: lo
 * único que necesita del mundo es responder si un commit es ancestro de otro.
 *
 * @param isAncestor `git merge-base --is-ancestor a b`. Si el PR se rebasó o se forzó el push, el
 *   commit que revisamos ya no está en la historia del actual: el rango `viejo..nuevo` daría
 *   basura o vacío, y la review miraría cualquier cosa menos el cambio. Ahí se vuelve a completa.
 * @param forceFull el usuario pidió explícitamente la pasada entera.
 */
fun decideScope(
    previous: ReviewRecord?,
    headSha: String,
    forceFull: Boolean,
    isAncestor: (String, String) -> Boolean,
): ScopeDecision {
    if (forceFull) return ScopeDecision(ReviewScope.Full, ScopeReason.FORCED)
    if (previous == null) return ScopeDecision(ReviewScope.Full, ScopeReason.FIRST_REVIEW)

    val since = previous.headSha
    // Repetir el mismo commit no tiene "lo nuevo" que mirar. Se llega acá sólo si el usuario
    // insistió después del aviso, y lo que quiere entonces es una mirada fresca y entera.
    if (since == headSha) return ScopeDecision(ReviewScope.Full, ScopeReason.SAME_COMMIT)
    if (since.isBlank() || headSha.isBlank()) return ScopeDecision(ReviewScope.Full, ScopeReason.FIRST_REVIEW)

    // Ante la duda —el comando falla, el objeto no está en el clon— se elige mirar de más. Una
    // review completa de sobra cuesta plata; una incremental sobre una historia reescrita mira el
    // código equivocado y devuelve un resultado en el que no se puede confiar.
    val encadena = runCatching { isAncestor(since, headSha) }.getOrDefault(false)
    if (!encadena) return ScopeDecision(ReviewScope.Full, ScopeReason.REWRITTEN_HISTORY)

    return ScopeDecision(ReviewScope.Incremental(since, previous.id), ScopeReason.NEW_COMMITS)
}

/** Veredicto sobre un hallazgo anterior a la luz de los commits nuevos. */
enum class CarryVerdict {
    /** Sigue en pie: se arrastra a esta review. */
    STILL_OPEN,

    /** Los commits nuevos lo corrigieron. */
    FIXED,

    /** El código que lo motivaba ya no existe o cambió de forma: dejó de aplicar. */
    OBSOLETE,
}
