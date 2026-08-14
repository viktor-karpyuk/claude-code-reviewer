package io.acr

import io.acr.claude.ProjectKind
import io.acr.claude.ReviewDepth
import io.acr.data.ReviewStatus
import io.acr.forge.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Retomar la review que quedó a medias al cerrar la app.
 *
 * La secuencia completa importa porque es donde estaba el error: al arrancar, la review que quedó
 * en RUNNING se encola como trabajo pendiente **y** se marca fallida con su mismo `head_sha`. Si
 * el guard que decide si hace falta reanudar pregunta "¿existe alguna review de este commit?", se
 * encuentra a sí misma, contesta que sí, y descarta el trabajo — mientras la pantalla dice "se
 * reanuda al abrir".
 *
 * Se descubrió mirando la base real: cinco reviews interrumpidas, cero reanudadas.
 */
class ResumeAfterCloseTest {

    private fun conRepo(block: (AppContext, String, Long) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("acr-resume")
        val ctx = AppContext.bootstrap(dir)
        try {
            val prId = System.nanoTime() % 100_000
            val repoId = ctx.repos.create(
                "tmp-res-$prId", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            block(ctx, repoId, prId)
        } finally {
            ctx.close()
            dir.toFile().deleteRecursively()
        }
    }

    /** Lo que hace el arranque con una review que quedó corriendo. */
    private fun simularArranque(ctx: AppContext) {
        ctx.reviews.orphanedRunning().forEach { r ->
            ctx.jobs.enqueue(r.repoId, r.prId, r.depth, r.projectKind, r.model.orEmpty(), r.auto)
        }
        ctx.reviews.failOrphanedRunning()
    }

    @Test
    fun anInterruptedReviewLeavesWorkToPickUp() = conRepo { ctx, repoId, prId ->
        ctx.reviews.start(repoId, prId, "t", "shaX", ReviewDepth.HEAVY, ProjectKind.BACKEND, "opus", auto = false)

        simularArranque(ctx)

        val job = assertNotNull(ctx.jobs.pending().firstOrNull { it.prId == prId })
        assertEquals(ReviewDepth.HEAVY, job.depth, "con los parámetros con los que corría")
        assertEquals("opus", job.model)
        assertEquals(
            ReviewStatus.FAILED,
            ctx.reviews.latestFor(repoId, prId)?.status,
            "y la fila vieja no queda corriendo para siempre",
        )
    }

    @Test
    fun theFailedRowDoesNotMakeTheResumeThinkTheWorkIsDone() = conRepo { ctx, repoId, prId ->
        // El corazón del bug. Tras el arranque hay una review FAILED con el mismo sha que el job
        // pendiente; el guard tiene que mirar sólo las TERMINADAS.
        ctx.reviews.start(repoId, prId, "t", "shaX", ReviewDepth.LIGHT, ProjectKind.BACKEND, "m", auto = false)
        simularArranque(ctx)

        assertTrue(
            ctx.reviews.existsForHead(repoId, prId, "shaX"),
            "existe una review de ese sha: la que acaba de fallar",
        )
        assertNull(
            ctx.reviews.doneForHead(repoId, prId, "shaX"),
            "pero ninguna terminada, así que el trabajo sigue pendiente de verdad",
        )
    }

    @Test
    fun workAlreadyDoneByHandIsNotRepeated() = conRepo { ctx, repoId, prId ->
        // El caso legítimo que el guard sí tiene que atrapar: si mientras tanto alguien corrió la
        // review a mano y terminó bien, reanudar sería pagarla dos veces.
        ctx.reviews.start(repoId, prId, "t", "shaX", ReviewDepth.LIGHT, ProjectKind.BACKEND, "m", auto = false)
        simularArranque(ctx)

        val nueva = ctx.reviews.start(repoId, prId, "t", "shaX", ReviewDepth.LIGHT, ProjectKind.BACKEND, "m", false)
        ctx.reviews.finish(nueva, "cuerpo", null, 2.0)

        assertNotNull(ctx.reviews.doneForHead(repoId, prId, "shaX"), "ahora sí hay una terminada")
    }

    @Test
    fun newCommitsMeanTheOldWorkIsNotWhatIsNeeded() = conRepo { ctx, repoId, prId ->
        // Si el PR avanzó mientras la app estaba cerrada, la review pendiente era de un commit
        // viejo. El guard mira el sha actual, así que no encuentra nada terminado y se corre.
        ctx.reviews.start(repoId, prId, "t", "shaViejo", ReviewDepth.LIGHT, ProjectKind.BACKEND, "m", false)
        simularArranque(ctx)

        assertNull(ctx.reviews.doneForHead(repoId, prId, "shaNuevo"))
    }

    @Test
    fun severalInterruptedReviewsAreAllRemembered() = conRepo { ctx, repoId, prId ->
        // Pasó de verdad: tres reviews cortadas el mismo día por reinstalar la app.
        listOf(prId, prId + 1, prId + 2).forEach {
            ctx.reviews.start(repoId, it, "t", "sha$it", ReviewDepth.LIGHT, ProjectKind.BACKEND, "m", false)
        }

        simularArranque(ctx)

        assertEquals(3, ctx.jobs.pending().count { it.prId >= prId })
        assertEquals(0, ctx.reviews.orphanedRunning().size, "ninguna queda marcada como corriendo")
    }

    @Test
    fun aFinishedReviewIsNeverTreatedAsInterrupted() = conRepo { ctx, repoId, prId ->
        val id = ctx.reviews.start(repoId, prId, "t", "shaX", ReviewDepth.LIGHT, ProjectKind.BACKEND, "m", false)
        ctx.reviews.finish(id, "cuerpo", null, 1.0)

        simularArranque(ctx)

        assertTrue(ctx.jobs.pending().none { it.prId == prId }, "no hay nada que reanudar")
        assertEquals(ReviewStatus.DONE, ctx.reviews.get(id)?.status, "y no se le toca el estado")
    }
}
