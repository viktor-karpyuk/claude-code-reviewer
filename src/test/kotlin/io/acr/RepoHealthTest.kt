package io.acr

import io.acr.claude.ProjectKind
import io.acr.claude.ReviewDepth
import io.acr.data.Finding
import io.acr.forge.Provider
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La salud de un repositorio: qué espera algo nuestro y cómo viene evolucionando.
 *
 * Las tres piezas de deuda van sumadas porque separadas cada una parece chica. En la base real son
 * 69 respuestas sin contestar y 51 hallazgos publicados que nadie verificó, repartidos de a poco
 * entre cinco repositorios: así nadie los ve.
 */
class RepoHealthTest {

    private fun conRepo(block: (AppContext, String) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("acr-health")
        val ctx = AppContext.bootstrap(dir)
        try {
            val repoId = ctx.repos.create(
                "tmp-h-${System.nanoTime()}", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            block(ctx, repoId)
        } finally {
            ctx.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun AppContext.reviewCon(repoId: String, prId: Long, publicar: Boolean, hallazgos: Int): String {
        val id = reviews.start(repoId, prId, "t", "sha$prId", ReviewDepth.LIGHT, ProjectKind.BACKEND, "m", false)
        findings.replaceForReview(
            id, repoId, prId,
            (1..hallazgos).map { Finding("", "", prId, "a.kt", it, "major", "h$it", "x", null, null) },
        )
        findings.forReview(id).forEach { findings.markPublished(it.id, "c${it.lineNo}", "http://x") }
        reviews.finish(id, "cuerpo", null, 3.0)
        if (publicar) reviews.markPublished(id, "http://x/pub")
        return id
    }

    @Test
    fun theThreeKindsOfDebtAddUpIntoOneNumber() = conRepo { ctx, repoId ->
        ctx.reviewCon(repoId, 1, publicar = false, hallazgos = 3)
        val h = ctx.health.current(repoId)
        assertEquals(3, h.unverified, "publicados y sin veredicto")
        assertEquals(1, h.unpublished, "la review terminó y nunca se publicó")
        assertEquals(4, h.debt, "y se suman en el número que decide a qué repo entrar")
    }

    @Test
    fun aVerifiedFindingStopsBeingDebt() = conRepo { ctx, repoId ->
        val id = ctx.reviewCon(repoId, 1, publicar = true, hallazgos = 2)
        ctx.findings.forReview(id).forEach {
            ctx.findings.setResolution(it.id, io.acr.data.Resolution.RESOLVED, "ok")
        }
        assertEquals(0, ctx.health.current(repoId).debt, "verificado y publicado: no espera nada")
    }

    @Test
    fun densityIsPerReviewedPrNotATotal() = conRepo { ctx, repoId ->
        // Un repositorio con más PRs revisados acumula más hallazgos sin que eso diga nada de su
        // código: por eso la tarjeta muestra la densidad y no el total.
        ctx.reviewCon(repoId, 1, publicar = true, hallazgos = 10)
        ctx.reviewCon(repoId, 2, publicar = true, hallazgos = 10)
        val h = ctx.health.current(repoId)
        assertEquals(20, h.openFindings)
        assertEquals(2, h.reviewedPrs)
        assertEquals(10.0, h.findingsPerPr)
        assertEquals(3.0, h.costPerPr, "y el costo también va por PR")
    }

    @Test
    fun withNothingReviewedNothingIsDividedByZero() = conRepo { ctx, repoId ->
        val h = ctx.health.current(repoId)
        assertEquals(0.0, h.findingsPerPr)
        assertEquals(0.0, h.costPerPr)
        assertEquals(0, h.debt)
        assertNull(h.oldestPrDays, "sin PRs abiertos no hay antigüedad que mostrar")
    }

    @Test
    fun theHistoryKeepsOneRowPerDay() = conRepo { ctx, repoId ->
        // Dentro de un día la deuda sube y baja con cada acción; lo que interesa es dónde quedó.
        // Guardar cada variación llenaría la tabla para dibujar la misma línea.
        val hoy = LocalDate.of(2026, 8, 14)
        ctx.health.snapshot(repoId, ctx.health.current(repoId), hoy)
        ctx.reviewCon(repoId, 1, publicar = false, hallazgos = 5)
        ctx.health.snapshot(repoId, ctx.health.current(repoId), hoy)

        val historia = ctx.health.history(repoId)
        assertEquals(1, historia.size, "un día, una fila")
        assertEquals(6, historia.single().let { it.pendingReplies + it.unverified + it.unpublished })
    }

    @Test
    fun theHistoryComesOldestFirstSoItCanBeDrawn() = conRepo { ctx, repoId ->
        val base = LocalDate.of(2026, 8, 10)
        repeat(4) { ctx.health.snapshot(repoId, ctx.health.current(repoId), base.plusDays(it.toLong())) }
        val dias = ctx.health.history(repoId).map { it.day }
        assertEquals(dias.sorted(), dias, "del más viejo al más nuevo, como se lee un gráfico")
    }
}
