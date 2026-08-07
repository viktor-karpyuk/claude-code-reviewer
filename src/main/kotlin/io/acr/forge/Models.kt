package io.acr.forge

enum class Provider(val label: String) {
    BITBUCKET("Bitbucket Cloud"),
    GITHUB("GitHub"),
}

/** A connected repository: remote coordinates plus the local working copy used for reviews. */
data class RepoRecord(
    val id: String,
    val name: String,
    val provider: Provider,
    val owner: String,
    val slug: String,
    val localPath: String,
    val token: String?,
    /** null = automático: se infiere del diff en cada review. */
    val projectKind: io.acr.claude.ProjectKind? = null,
    val defaultDepth: io.acr.claude.ReviewDepth? = null,
    /** Vacío = automático: el modelo que implica el nivel de profundidad. */
    val defaultModel: String = "",
    /** Si está activo, la app revisa sola los PRs nuevos de este repo (nunca publica). */
    val autoReview: Boolean = false,
    val skipRules: SkipRules = SkipRules(),
    val replyMode: ReplyMode = ReplyMode.DRAFT,
)

/**
 * Qué hacer cuando alguien responde a un comentario nuestro.
 *
 * El default es [DRAFT] a propósito: contestar una objeción técnica es de las cosas donde más
 * conviene que haya una persona antes de que el texto quede público. [AUTO] existe porque a veces
 * la respuesta es puramente factual y esperar sólo agrega demora, pero hay que elegirlo.
 */
enum class ReplyMode(val label: String) {
    OFF("Sólo detectar"),
    DRAFT("Preparar y avisarme"),
    AUTO("Contestar automáticamente"),
    ;

    companion object {
        fun fromName(n: String?): ReplyMode = entries.firstOrNull { it.name == n } ?: DRAFT
    }
}

/**
 * Qué excluir del barrido automático. Sólo aplica al automático: pedir una review a mano siempre
 * se respeta, porque ahí hay alguien decidiendo.
 */
data class SkipRules(
    val skipDrafts: Boolean = true,
    /** Patrones de título, separados por coma. Sin distinguir mayúsculas. */
    val skipTitles: String = "DO NOT MERGE,WIP",
    val skipAuthors: String = "",
    /** Si tiene valor, sólo se revisan PRs hacia estas ramas. */
    val onlyTargets: String = "",
) {
    /** Motivo por el que se saltea, o null si hay que revisarlo. */
    fun skipReason(pr: PullRequest): String? {
        if (skipDrafts && pr.isDraft) return "es borrador"
        list(skipTitles).firstOrNull { pr.title.contains(it, ignoreCase = true) }
            ?.let { return "el título contiene «$it»" }
        list(skipAuthors).firstOrNull { pr.author.equals(it, ignoreCase = true) }
            ?.let { return "autor excluido ($it)" }
        val targets = list(onlyTargets)
        if (targets.isNotEmpty() && targets.none { it.equals(pr.targetBranch, ignoreCase = true) }) {
            return "va a ${pr.targetBranch}, fuera de las ramas elegidas"
        }
        return null
    }

    private fun list(raw: String) = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

data class PullRequest(
    val id: Long,
    val title: String,
    val author: String,
    val sourceBranch: String,
    val targetBranch: String,
    val headSha: String,
    val commentCount: Int,
    val updatedOn: String,
    val url: String,
    /** GitHub lo marca explícito; Bitbucket no tiene borradores nativos y se detecta por título. */
    val isDraft: Boolean = false,
    /**
     * Cuándo se abrió el PR. Distinto de [updatedOn] y necesario aparte: para decidir qué revisar
     * primero importa hace cuánto espera, no cuándo fue el último commit. El proveedor los
     * devuelve ordenados por actividad reciente, que es justo el orden inverso.
     */
    val createdOn: String = "",
    /** OPEN, MERGED o DECLINED. Por defecto abierto: es lo único que se lista sin pedirlo. */
    val state: PrState = PrState.OPEN,
)

/**
 * Estado del pull request.
 *
 * Los históricos —mergeados y cerrados— no se traen nunca solos: en `kubrik-erp-be` son más de
 * 148 contra 4 abiertos, así que cargarlos por defecto sería pagar cientos de resultados para
 * mostrar los cuatro que importan. Se piden cuando el usuario los pide.
 */
enum class PrState(val api: String, val labelKey: String) {
    OPEN("OPEN", "prs.state.open"),
    MERGED("MERGED", "prs.state.merged"),
    DECLINED("DECLINED", "prs.state.declined"),
    ;

    companion object {
        fun fromApi(value: String?): PrState =
            entries.firstOrNull { it.api.equals(value, ignoreCase = true) }
            // SUPERSEDED de Bitbucket y cualquier otro cierre caen en DECLINED: para revisar, lo
            // único que cambia es que ya no está abierto.
                ?: if (value.isNullOrBlank()) OPEN else DECLINED
    }
}

/** Result of publishing a review back to the PR. */
data class PostedComment(val id: String, val url: String)

/** One comment already on the PR, ours or somebody else's. */
data class PrComment(
    val commentId: String,
    val author: String,
    val body: String,
    val inlinePath: String?,
    val inlineLine: Int?,
    val deleted: Boolean,
    val createdOn: String,
    /** Comentario del que cuelga esta respuesta, si es una respuesta. */
    val parentId: String? = null,
)
