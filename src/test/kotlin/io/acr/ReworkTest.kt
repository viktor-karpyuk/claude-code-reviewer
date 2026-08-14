package io.acr

import io.acr.claude.ProjectKind
import io.acr.claude.ReviewDepth
import io.acr.data.Finding
import io.acr.forge.PrState
import io.acr.forge.Provider
import io.acr.forge.PullRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Retrabajo: los commits que llegaron después de que revisamos.
 *
 * La medida sólo significa algo si el PR tuvo hallazgos **publicados**. Los commits que llegan
 * después de una review que no encontró nada son desarrollo normal, y contarlos como corrección
 * diría que alguien arregló algo que nadie le señaló.
 *
 * Y alto no quiere decir que alguien trabaje peor: puede ser un revisor exigente, un requerimiento
 * mal definido o un área difícil. Es una señal para preguntar.
 */
class ReworkTest {

    private fun conRepo(block: (AppContext, String) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("acr-rework")
        val ctx = AppContext.bootstrap(dir)
        try {
            val repoId = ctx.repos.create(
                "tmp-rw-${System.nanoTime()}", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            block(ctx, repoId)
        } finally {
            ctx.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun pr(id: Long, autor: String, rama: String = "feature/x") = PullRequest(
        id = id, title = "PR $id", author = autor, sourceBranch = rama, targetBranch = "develop",
        headSha = "sha$id", commentCount = 0, updatedOn = "2026-08-02", url = "",
        createdOn = "2026-08-01", state = PrState.MERGED,
    )

    /** Una review terminada, con o sin hallazgos publicados. */
    private fun AppContext.reviewCon(repoId: String, prId: Long, publicado: Boolean): String {
        val id = reviews.start(
            repoId, prId, "t", "shaRevisado", ReviewDepth.LIGHT, ProjectKind.BACKEND, "m", false,
            prAuthor = "Ana Gómez",
        )
        findings.replaceForReview(
            id, repoId, prId,
            listOf(Finding("", "", prId, "a.kt", 1, "major", "algo", "x", null, null)),
        )
        if (publicado) {
            findings.markPublished(findings.forReview(id).single().id, "c1", "http://x/1")
        }
        reviews.finish(id, "cuerpo", null, 1.0)
        return id
    }

    @Test
    fun onlyPrsWithPublishedFindingsAreMeasured() = conRepo { ctx, repoId ->
        // Es la condición que le da sentido al número.
        ctx.prStats.upsert(repoId, pr(1, "Ana Gómez"))
        ctx.prStats.upsert(repoId, pr(2, "Ana Gómez"))
        ctx.reviewCon(repoId, 1, publicado = true)
        ctx.reviewCon(repoId, 2, publicado = false)

        val objetivos = ctx.prStats.pendingRework()
        assertEquals(listOf(1L), objetivos.map { it.prId })
        assertEquals("feature/x", objetivos.single().sourceBranch)
        assertEquals("shaRevisado", objetivos.single().reviewedSha)
    }

    @Test
    fun aPrWithoutAKnownBranchCannotBeMeasured() = conRepo { ctx, repoId ->
        // Sin la rama no se le puede preguntar a git qué vino después; sólo se sabría la fecha, y
        // la fecha no distingue un commit de esa rama de cualquier otro del repositorio.
        ctx.prStats.upsert(repoId, pr(1, "Ana Gómez", rama = ""))
        ctx.reviewCon(repoId, 1, publicado = true)
        // Rama vacía se guarda como cadena vacía, no null, así que igual aparece: lo que importa
        // es que el conteo devuelva "no sé" y no un cero inventado.
        ctx.prStats.setRework(repoId, 1, null)
        assertTrue(ctx.prStats.reworkByAuthor("2026-01-01", "2026-12-31").isEmpty())
    }

    @Test
    fun notMeasuredIsDifferentFromNoFixes() = conRepo { ctx, repoId ->
        // Cero es una afirmación —"no hubo correcciones"— y null es "no se pudo saber". Mezclarlos
        // haría que un rebase se lea como un PR impecable.
        ctx.prStats.upsert(repoId, pr(1, "Ana Gómez"))
        ctx.prStats.upsert(repoId, pr(2, "Ana Gómez"))
        ctx.prStats.setRework(repoId, 1, 0)
        ctx.prStats.setRework(repoId, 2, null)

        val rw = assertNotNull(ctx.prStats.reworkByAuthor("2026-01-01", "2026-12-31")["Ana Gómez"])
        assertEquals(0, rw.prsWithFixes)
        assertEquals(1, rw.measured, "sólo uno se pudo medir, y eso se dice")
    }

    @Test
    fun theNumberCarriesHowManyPrsItWasMeasuredOver() = conRepo { ctx, repoId ->
        // "3 PRs con correcciones" significa cosas muy distintas si se midieron 4 o 40.
        (1L..4L).forEach { ctx.prStats.upsert(repoId, pr(it, "Ana Gómez")) }
        ctx.prStats.setRework(repoId, 1, 3)
        ctx.prStats.setRework(repoId, 2, 1)
        ctx.prStats.setRework(repoId, 3, 0)
        ctx.prStats.setRework(repoId, 4, 2)

        val rw = ctx.prStats.reworkByAuthor("2026-01-01", "2026-12-31").getValue("Ana Gómez")
        assertEquals(3, rw.prsWithFixes)
        assertEquals(4, rw.measured)
        assertEquals(6, rw.commits, "y el total de commits de corrección")
    }

    @Test
    fun resyncingThePrDoesNotDiscardTheMeasurement() = conRepo { ctx, repoId ->
        // Traer de nuevo el histórico es normal. Si el upsert pisara el retrabajo con null, habría
        // que recalcular todo —una llamada a git por PR— cada vez que se sincroniza.
        ctx.prStats.upsert(repoId, pr(1, "Ana Gómez"))
        ctx.prStats.setRework(repoId, 1, 5)
        ctx.prStats.upsert(repoId, pr(1, "Ana Gómez"))

        assertEquals(5, ctx.prStats.reworkByAuthor("2026-01-01", "2026-12-31").getValue("Ana Gómez").commits)
    }

    @Test
    fun aPeriodOnlyCountsWhatWasOpenedInIt() = conRepo { ctx, repoId ->
        ctx.prStats.upsert(repoId, pr(1, "Ana Gómez"))
        ctx.prStats.setRework(repoId, 1, 2)
        assertNull(ctx.prStats.reworkByAuthor("2025-01-01", "2025-12-31")["Ana Gómez"])
    }
}
