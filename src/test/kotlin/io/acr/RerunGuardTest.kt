package io.acr

import io.acr.claude.ProjectKind
import io.acr.claude.ReviewDepth
import io.acr.data.Finding
import io.acr.data.ReviewStatus
import io.acr.forge.Provider
import io.acr.ui.review.Ago
import io.acr.ui.review.agoFrom
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * El aviso antes de repetir una review sobre un commit ya revisado.
 *
 * Sale de la base real: 11 corridas repitieron un commit cuya review ya había terminado bien —a
 * 57, 127 y 139 horas de distancia, o sea alguien que volvió al PR días después y no se acordaba.
 * Otras 10 repetían una review caída, y ahí repetir es lo correcto: por eso el filtro por DONE es
 * la regla que más importa probar.
 */
class RerunGuardTest {

    private fun conRepo(block: (AppContext, String, Long) -> Unit) {
        val ctx = AppContext.bootstrap()
        try {
            val prId = System.nanoTime() % 100_000
            val repoId = ctx.repos.create(
                "tmp-rerun-$prId", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            try { block(ctx, repoId, prId) } finally { ctx.repos.delete(repoId) }
        } finally { ctx.close() }
    }

    private fun AppContext.review(repoId: String, prId: Long, sha: String): String =
        reviews.start(repoId, prId, "titulo", sha, ReviewDepth.LIGHT, ProjectKind.BACKEND, "opus", auto = false)

    @Test
    fun aFinishedReviewOfTheSameCommitIsFound() = conRepo { ctx, repoId, prId ->
        val id = ctx.review(repoId, prId, "abc123")
        ctx.reviews.finish(id, "cuerpo", null, costUsd = 4.49)
        ctx.findings.replaceForReview(
            id, repoId, prId,
            listOf(
                Finding("", id, prId, "a.kt", 1, "major", "uno", "x", null, null),
                Finding("", id, prId, "b.kt", 2, "minor", "dos", "y", null, null),
            ),
        )

        val previa = assertNotNull(ctx.reviews.doneForHead(repoId, prId, "abc123"))
        assertEquals(2, previa.findings)
        assertEquals(4.49, previa.costUsd)
    }

    @Test
    fun aFailedReviewIsNotAReasonToWarn() = conRepo { ctx, repoId, prId ->
        // Es la mitad de los casos reales: la app se cerró a mitad de la review. Volver a correr
        // el mismo commit es exactamente lo que hay que hacer, y preguntar ahí sería estorbar.
        val id = ctx.review(repoId, prId, "def456")
        ctx.reviews.fail(id, "Interrumpida: la app se cerró mientras corría.")
        assertNull(ctx.reviews.doneForHead(repoId, prId, "def456"))
    }

    @Test
    fun aCancelledReviewIsNotAReasonToWarn() = conRepo { ctx, repoId, prId ->
        val id = ctx.review(repoId, prId, "aaa111")
        ctx.reviews.fail(id, "cancelada", ReviewStatus.CANCELLED)
        assertNull(ctx.reviews.doneForHead(repoId, prId, "aaa111"))
    }

    @Test
    fun anotherCommitIsNotTheSameCommit() = conRepo { ctx, repoId, prId ->
        // Lo único que justifica el aviso es que no haya cambiado nada. Con código nuevo, la
        // review vuelve a correr sin preguntar.
        val id = ctx.review(repoId, prId, "viejo")
        ctx.reviews.finish(id, "cuerpo", null, costUsd = 1.0)
        assertNull(ctx.reviews.doneForHead(repoId, prId, "nuevo"))
    }

    @Test
    fun theMostRecentFinishedReviewWins() = conRepo { ctx, repoId, prId ->
        val vieja = ctx.review(repoId, prId, "sha9")
        ctx.reviews.finish(vieja, "vieja", null, costUsd = 1.0)
        Thread.sleep(5)
        val nueva = ctx.review(repoId, prId, "sha9")
        ctx.reviews.finish(nueva, "nueva", null, costUsd = 2.0)

        assertEquals(2.0, ctx.reviews.doneForHead(repoId, prId, "sha9")?.costUsd)
    }

    @Test
    fun aMissingCostIsNotZeroCost() = conRepo { ctx, repoId, prId ->
        // Una review vieja puede no tener el costo guardado. Mostrar "US$ 0,00" diría que salió
        // gratis, que es distinto de no saber.
        val id = ctx.review(repoId, prId, "sincosto")
        ctx.reviews.finish(id, "cuerpo", null, costUsd = null)
        assertNull(ctx.reviews.doneForHead(repoId, prId, "sincosto")?.costUsd)
    }

    // --- la antigüedad que se muestra en el diálogo ---

    @Test
    fun recentThingsAreCountedInHours() {
        val ahora = Instant.parse("2026-08-13T12:00:00Z")
        assertEquals(Ago(6, Ago.Unit.HOURS), agoFrom("2026-08-13T06:00:00Z", ahora))
        assertEquals(Ago(47, Ago.Unit.HOURS), agoFrom("2026-08-11T13:00:00Z", ahora))
    }

    @Test
    fun oldThingsAreCountedInDays() {
        // A las 139 horas —uno de los casos reales— nadie piensa en horas.
        val ahora = Instant.parse("2026-08-13T12:00:00Z")
        assertEquals(Ago(2, Ago.Unit.DAYS), agoFrom("2026-08-11T12:00:00Z", ahora))
        assertEquals(Ago(5, Ago.Unit.DAYS), agoFrom("2026-08-08T05:00:00Z", ahora))
    }

    @Test
    fun aClockSkewDoesNotProduceNegativeTime() {
        // Pasa de verdad cuando el reloj del servidor va adelante: "hace -3 horas" no se lee.
        val ahora = Instant.parse("2026-08-13T12:00:00Z")
        assertEquals(Ago(0, Ago.Unit.HOURS), agoFrom("2026-08-13T15:00:00Z", ahora))
    }

    @Test
    fun anUnreadableDateSaysNothing() {
        assertNull(agoFrom(null, Instant.parse("2026-08-13T12:00:00Z")))
        assertNull(agoFrom("no es una fecha", Instant.parse("2026-08-13T12:00:00Z")))
    }
}
