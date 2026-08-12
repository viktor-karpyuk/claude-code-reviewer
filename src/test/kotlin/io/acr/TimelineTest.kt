package io.acr

import io.acr.claude.ReviewDepth
import io.acr.data.LocalNote
import io.acr.data.PublicationRecord
import io.acr.data.ReviewRecord
import io.acr.data.ReviewStatus
import io.acr.data.StoredComment
import io.acr.ui.review.EventKind
import io.acr.ui.review.buildTimeline
import io.acr.ui.review.day
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El historial como una sola línea de tiempo. Antes eran cuatro listas separadas con su propio
 * orden: reconstruir qué pasó y cuándo obligaba a saltar entre secciones comparando fechas a ojo.
 */
class TimelineTest {

    private fun review(at: String, head: String = "sha") = ReviewRecord(
        id = "rev-$at", repoId = "r", prId = 7, prTitle = "PR", headSha = head,
        status = ReviewStatus.DONE, body = "cuerpo", error = null, sessionId = null, costUsd = null,
        publishedUrl = null, createdAt = at, depth = ReviewDepth.LIGHT, projectKind = null,
        model = "haiku", auto = false, deniedTools = null,
    )

    private fun comment(id: String, autor: String, ours: Boolean, at: String) =
        StoredComment(id, autor, "texto $id", "src/A.kt", 3, false, ours, at, null)

    private fun publication(id: String, commentId: String?, at: String) =
        PublicationRecord(id, "rev", 7, commentId, "https://x/$id", "publicado $id", at)

    private fun note(at: String) =
        LocalNote("n", 7, "src/B.kt", 9, "mi nota", null, null, at)

    @Test
    fun everythingLandsInOneListNewestFirst() {
        val t = buildTimeline(
            listOf(review("2026-08-01T10:00")),
            emptyList(),
            listOf(comment("c1", "dev", false, "2026-08-03T10:00")),
            listOf(note("2026-08-02T10:00")),
        )
        assertEquals(3, t.size)
        assertEquals(
            listOf("2026-08-03T10:00", "2026-08-02T10:00", "2026-08-01T10:00"),
            t.map { it.at },
        )
    }

    @Test
    fun ourCommentsAreDistinguishedFromTheirs() {
        val t = buildTimeline(
            emptyList(), emptyList(),
            listOf(
                comment("c1", "yo", true, "2026-08-03T10:00"),
                comment("c2", "dev", false, "2026-08-03T11:00"),
            ),
            emptyList(),
        )
        assertEquals(EventKind.COMMENT_THEIRS, t.first().kind)
        assertEquals(EventKind.COMMENT_OURS, t.last().kind)
    }

    @Test
    fun aPublishedCommentIsNotCountedTwice() {
        // La publicación y el comentario del hilo son la misma cosa vista dos veces: duplicarlas
        // hacía parecer que habíamos comentado el doble.
        val t = buildTimeline(
            emptyList(),
            listOf(publication("p1", "c1", "2026-08-03T10:00")),
            listOf(comment("c1", "yo", true, "2026-08-03T10:00")),
            emptyList(),
        )
        assertEquals(1, t.size)
        assertEquals("texto c1", t.single().body)
    }

    @Test
    fun aPublicationNotYetSyncedIsStillShown() {
        // Si el hilo todavía no se sincronizó, esconderla haría desaparecer algo que sí se publicó.
        val t = buildTimeline(
            emptyList(),
            listOf(publication("p1", "c9", "2026-08-03T10:00")),
            listOf(comment("c1", "yo", true, "2026-08-03T09:00")),
            emptyList(),
        )
        assertEquals(2, t.size)
        assertTrue(t.any { it.body == "publicado p1" })
    }

    @Test
    fun theOrderIsStableForTheSameInstant() {
        // Dos eventos del mismo segundo no pueden intercambiarse entre recomposiciones.
        val entrada = listOf(
            comment("c1", "dev", false, "2026-08-03T10:00"),
            comment("c2", "otro", false, "2026-08-03T10:00"),
        )
        val a = buildTimeline(emptyList(), emptyList(), entrada, emptyList()).map { it.body }
        val b = buildTimeline(emptyList(), emptyList(), entrada.reversed(), emptyList()).map { it.body }
        assertEquals(a, b)
    }

    @Test
    fun eventsGroupByDay() {
        val t = buildTimeline(
            emptyList(), emptyList(),
            listOf(
                comment("c1", "dev", false, "2026-08-03T10:00"),
                comment("c2", "dev", false, "2026-08-03T23:59"),
                comment("c3", "dev", false, "2026-08-04T00:01"),
            ),
            emptyList(),
        )
        assertEquals(listOf("2026-08-04", "2026-08-03"), t.map { it.day() }.distinct())
    }

    @Test
    fun everyKindHasATranslation() {
        EventKind.entries.forEach { k ->
            listOf(io.acr.i18n.Lang.ES, io.acr.i18n.Lang.EN).forEach { idioma ->
                assertTrue(
                    io.acr.i18n.I18n.get(idioma, k.labelKey) != k.labelKey,
                    "falta ${k.labelKey} en $idioma",
                )
            }
        }
    }
}
