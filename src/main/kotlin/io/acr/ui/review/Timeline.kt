package io.acr.ui.review

import io.acr.data.LocalNote
import io.acr.data.PublicationRecord
import io.acr.data.ReviewRecord
import io.acr.data.StoredComment

/** Qué tipo de cosa pasó. Define el ícono y el color de la fila. */
enum class EventKind(val labelKey: String) {
    REVIEW("hist.review"),
    COMMENT_OURS("hist.ourComment"),
    COMMENT_THEIRS("hist.theirComment"),
    NOTE("hist.note"),
}

/** Una cosa que pasó en el PR, con cuándo y quién. */
data class TimelineEvent(
    val at: String,
    val kind: EventKind,
    val author: String,
    val title: String,
    val body: String,
    val anchor: String? = null,
    val url: String? = null,
)

/**
 * El historial del PR como una sola línea de tiempo.
 *
 * Antes eran cuatro listas separadas —reviews, publicaciones, hilo y notas—, cada una con su
 * propio orden. Para reconstruir qué pasó y en qué orden había que ir saltando entre secciones y
 * comparando fechas a ojo, que es exactamente lo que un historial tendría que evitar.
 *
 * Las publicaciones no se muestran aparte cuando el comentario ya está en el hilo: son la misma
 * cosa vista dos veces, y duplicarlas hacía parecer que habíamos comentado el doble.
 */
fun buildTimeline(
    reviews: List<ReviewRecord>,
    publications: List<PublicationRecord>,
    thread: List<StoredComment>,
    notes: List<LocalNote>,
): List<TimelineEvent> {
    val enElHilo = thread.map { it.commentId }.toSet()
    val eventos = mutableListOf<TimelineEvent>()

    reviews.forEach { r ->
        eventos += TimelineEvent(
            at = r.createdAt,
            kind = EventKind.REVIEW,
            author = if (r.auto) "auto" else "",
            title = listOfNotNull(
                r.status.name.lowercase(),
                r.depth?.label,
                r.model?.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            body = (r.body ?: r.error).orEmpty(),
            anchor = "commit " + r.headSha.take(12),
        )
    }

    thread.forEach { c ->
        eventos += TimelineEvent(
            at = c.createdOn,
            kind = if (c.ours) EventKind.COMMENT_OURS else EventKind.COMMENT_THEIRS,
            author = c.author,
            title = "",
            body = c.body,
            anchor = c.inlinePath?.let { p ->
                p.substringAfterLast('/') + (c.inlineLine?.let { ":$it" } ?: "")
            },
        )
    }

    // Sólo las que todavía no aparecen en el hilo sincronizado.
    publications.filter { it.commentId == null || it.commentId !in enElHilo }.forEach { p ->
        eventos += TimelineEvent(
            at = p.publishedAt,
            kind = EventKind.COMMENT_OURS,
            author = "",
            title = "",
            body = p.body,
            url = p.url,
        )
    }

    notes.forEach { n ->
        eventos += TimelineEvent(
            at = n.createdAt,
            kind = EventKind.NOTE,
            author = "",
            title = "",
            body = n.body,
            anchor = n.filePath.substringAfterLast('/') + (n.lineNo?.let { ":$it" } ?: ""),
            url = n.publishedUrl,
        )
    }

    // Lo más nuevo arriba, y con un desempate estable: dos comentarios del mismo segundo no
    // pueden intercambiarse entre recomposiciones.
    return eventos.sortedWith(
        compareByDescending<TimelineEvent> { it.at }.thenBy { it.kind.ordinal }.thenBy { it.body },
    )
}

/** El día de un evento, para agrupar. Las fechas vienen en ISO o en "YYYY-MM-DD HH:mm". */
fun TimelineEvent.day(): String = at.take(10)
