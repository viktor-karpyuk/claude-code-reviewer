package io.acr

import io.acr.claude.ProjectKind
import io.acr.claude.ReviewDepth
import io.acr.forge.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El trabajo que quedó a medias al cerrar la app.
 *
 * No se puede retomar de verdad —el subproceso de Claude Code murió con su contexto— así que lo
 * que se guarda son los parámetros para volver a lanzarla. Antes se marcaba fallida y ahí moría:
 * había 16 así en la base real.
 */
class PendingJobTest {

    private fun conRepo(block: (AppContext, String, Long) -> Unit) {
        val ctx = AppContext.bootstrap()
        try {
            val prId = System.nanoTime() % 100_000
            val repoId = ctx.repos.create(
                "tmp-job-$prId", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            try { block(ctx, repoId, prId) } finally { ctx.repos.delete(repoId) }
        } finally { ctx.close() }
    }

    @Test
    fun theParametersSurviveTheRestart() = conRepo { ctx, repoId, prId ->
        ctx.jobs.enqueue(repoId, prId, ReviewDepth.HEAVY, ProjectKind.BACKEND, "opus", auto = true)
        val job = ctx.jobs.pending().single { it.prId == prId }
        assertEquals(ReviewDepth.HEAVY, job.depth)
        assertEquals(ProjectKind.BACKEND, job.kind)
        assertEquals("opus", job.model)
        assertTrue(job.auto)
    }

    @Test
    fun autoDetectedProfileStaysAuto() = conRepo { ctx, repoId, prId ->
        // Profundidad y tipo en null significan "inferilo del diff". Guardarlos como un valor
        // concreto congelaría una decisión que se toma en cada corrida.
        ctx.jobs.enqueue(repoId, prId, null, null, "", auto = false)
        val job = ctx.jobs.pending().single { it.prId == prId }
        assertEquals(null, job.depth)
        assertEquals(null, job.kind)
    }

    @Test
    fun enqueueingTwiceCountsAsARetryAndDoesNotDuplicate() = conRepo { ctx, repoId, prId ->
        ctx.jobs.enqueue(repoId, prId, null, null, "", false)
        ctx.jobs.enqueue(repoId, prId, null, null, "", false)
        val encolados = ctx.jobs.pending().filter { it.prId == prId }
        assertEquals(1, encolados.size, "se duplicó el trabajo")
        assertEquals(1, encolados.single().attempts)
    }

    @Test
    fun itGivesUpAfterThreeAttempts() = conRepo { ctx, repoId, prId ->
        // Una review que revienta siempre —un binario roto, un repo que ya no está— reintentada
        // en cada arranque sería un bucle que gasta plata y nunca termina.
        repeat(4) { ctx.jobs.enqueue(repoId, prId, null, null, "", false) }
        assertTrue(ctx.jobs.pending().none { it.prId == prId }, "sigue reintentando")
        assertTrue(ctx.jobs.givenUp() >= 1, "no quedó registro de que se abandonó")
    }

    @Test
    fun removingClearsIt() = conRepo { ctx, repoId, prId ->
        ctx.jobs.enqueue(repoId, prId, null, null, "", false)
        ctx.jobs.remove(repoId, prId)
        assertTrue(ctx.jobs.pending().none { it.prId == prId })
    }

    @Test
    fun deletingTheRepoTakesItsJobs() {
        // La cascada evita trabajo huérfano apuntando a un repositorio que ya no existe.
        val ctx = AppContext.bootstrap()
        try {
            val prId = System.nanoTime() % 100_000
            val repoId = ctx.repos.create(
                "tmp-cas-$prId", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            ctx.jobs.enqueue(repoId, prId, null, null, "", false)
            ctx.repos.delete(repoId)
            assertTrue(ctx.jobs.pending().none { it.repoId == repoId })
        } finally { ctx.close() }
    }
}
