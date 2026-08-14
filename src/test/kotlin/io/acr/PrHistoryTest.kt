package io.acr

import io.acr.claude.ProjectKind
import io.acr.claude.ReviewDepth
import io.acr.data.percentiles
import io.acr.forge.PrState
import io.acr.forge.Provider
import io.acr.forge.PullRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El histórico de pull requests: cuántos abrió cada uno y cuánto tardaron en cerrarse.
 *
 * Es lo que el proveedor sólo devuelve si se lo pide —148 cerrados contra 4 abiertos en uno de
 * estos repositorios— y por eso se trae bajo confirmación y se guarda aparte del caché de los
 * abiertos, que se reemplaza en cada sincronización.
 */
class PrHistoryTest {

    private fun conRepo(block: (AppContext, String) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("acr-prhist")
        val ctx = AppContext.bootstrap(dir)
        try {
            val repoId = ctx.repos.create(
                "tmp-ph-${System.nanoTime()}", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            block(ctx, repoId)
        } finally {
            ctx.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun pr(
        id: Long,
        autor: String,
        creado: String,
        estado: PrState = PrState.OPEN,
        actualizado: String = creado,
    ) = PullRequest(
        id = id, title = "PR $id", author = autor, sourceBranch = "f", targetBranch = "d",
        headSha = "sha$id", commentCount = 0, updatedOn = actualizado, url = "",
        createdOn = creado, state = estado,
    )

    // --- percentiles ---

    @Test
    fun theMedianDescribesTheTypicalCaseAndTheAverageDoesNot() {
        // Cuatro PRs de un par de días y uno olvidado tres meses: el promedio da 20 días y no
        // describe a ninguno. La mediana dice 2, que es lo que pasa de verdad.
        val dias = listOf(1.0, 2.0, 2.0, 3.0, 92.0)
        val (mediana, p90) = assertNotNull(percentiles(dias))
        assertEquals(2.0, mediana)
        assertEquals(92.0, p90, "el percentil 90 es el que muestra la cola fea")
        assertTrue(dias.average() > 19, "el promedio sería 20, engañoso")
    }

    @Test
    fun aSingleValueIsItsOwnMedian() {
        assertEquals(5.0 to 5.0, percentiles(listOf(5.0)))
    }

    @Test
    fun theWorstCaseShowsUpEvenWithFewPrs() {
        // Truncar el índice hacía que el percentil 90 de cinco valores devolviera el cuarto, así
        // que el peor PR nunca aparecía. Con equipos chicos eso pasa siempre, y es justo el
        // número que uno mira para saber cuán mal se pone la cosa.
        assertEquals(50.0, percentiles(listOf(1.0, 1.0, 1.0, 1.0, 50.0))?.second)
        assertEquals(9.0, percentiles(listOf(1.0, 2.0, 9.0))?.second)
        assertEquals(4.0, percentiles(listOf(1.0, 2.0, 3.0, 4.0))?.second)
    }

    @Test
    fun nothingToMeasureIsNotZero() {
        // Devolver 0 diría "se cierran el mismo día", que es lo contrario de "no sé".
        assertNull(percentiles(emptyList()))
    }

    // --- persistencia ---

    @Test
    fun prsAreCountedByAuthorAndOutcome() = conRepo { ctx, repoId ->
        listOf(
            pr(1, "Ana Gómez", "2026-08-01", PrState.MERGED, "2026-08-03"),
            pr(2, "Ana Gómez", "2026-08-02", PrState.MERGED, "2026-08-04"),
            pr(3, "Ana Gómez", "2026-08-05", PrState.OPEN),
            pr(4, "Beto Pérez", "2026-08-01", PrState.DECLINED, "2026-08-02"),
        ).forEach { ctx.prStats.upsert(repoId, it) }

        val m = ctx.prStats.openedByAuthor("2026-01-01", "2026-12-31")
        assertEquals(3, m.getValue("Ana Gómez").opened)
        assertEquals(2, m.getValue("Ana Gómez").merged)
        assertEquals(0, m.getValue("Ana Gómez").declined)
        assertEquals(1, m.getValue("Beto Pérez").declined)
    }

    @Test
    fun anOpenPrHasNoCloseDate() = conRepo { ctx, repoId ->
        // En un PR abierto `updated_on` es el último commit. Tomarlo como cierre daría tiempos de
        // ciclo de PRs que siguen vivos, y bajaría la mediana con datos que no existen.
        ctx.prStats.upsert(repoId, pr(1, "Ana Gómez", "2026-08-01", PrState.OPEN, "2026-08-10"))
        assertTrue(ctx.prStats.mergedDurationsByAuthor("2026-01-01", "2026-12-31").isEmpty())
    }

    @Test
    fun onlyMergedOnesCountForCycleTime() = conRepo { ctx, repoId ->
        // Un PR rechazado se cerró, pero su duración no dice cuánto tarda el trabajo en entrar.
        ctx.prStats.upsert(repoId, pr(1, "Ana Gómez", "2026-08-01", PrState.MERGED, "2026-08-03"))
        ctx.prStats.upsert(repoId, pr(2, "Ana Gómez", "2026-08-01", PrState.DECLINED, "2026-08-20"))

        val d = ctx.prStats.mergedDurationsByAuthor("2026-01-01", "2026-12-31").getValue("Ana Gómez")
        assertEquals(1, d.size)
        assertEquals(2.0, d.single())
    }

    @Test
    fun aPrThatGetsMergedUpdatesInsteadOfDuplicating() = conRepo { ctx, repoId ->
        // El mismo PR se trae varias veces: abierto primero, mergeado después.
        ctx.prStats.upsert(repoId, pr(1, "Ana Gómez", "2026-08-01", PrState.OPEN, "2026-08-01"))
        ctx.prStats.upsert(repoId, pr(1, "Ana Gómez", "2026-08-01", PrState.MERGED, "2026-08-05"))

        assertEquals(1, ctx.prStats.count(repoId))
        assertEquals(1, ctx.prStats.openedByAuthor("2026-01-01", "2026-12-31").getValue("Ana Gómez").merged)
        assertEquals(4.0, ctx.prStats.mergedDurationsByAuthor("2026-01-01", "2026-12-31").getValue("Ana Gómez").single())
    }

    @Test
    fun clockSkewDoesNotDragTheMedianDown() = conRepo { ctx, repoId ->
        // Un cierre anterior a la apertura sale de relojes desfasados; contarlo como duración
        // negativa bajaría la mediana sin que se note.
        ctx.prStats.upsert(repoId, pr(1, "Ana Gómez", "2026-08-05", PrState.MERGED, "2026-08-01"))
        assertTrue(ctx.prStats.mergedDurationsByAuthor("2026-01-01", "2026-12-31")["Ana Gómez"].isNullOrEmpty())
    }

    // --- el agujero que esto viene a tapar ---

    @Test
    fun theHistoryFillsInWhoOwnedTheReviewedPrs() = conRepo { ctx, repoId ->
        // Es el motivo principal de traer el histórico: la review no guardaba el autor, y sin él
        // los hallazgos no se le pueden atribuir a nadie. En la base real eran 7 de 12 PRs.
        val id = ctx.reviews.start(
            repoId, 42, "t", "sha", ReviewDepth.LIGHT, ProjectKind.BACKEND, "m", false,
            prAuthor = null,
        )
        ctx.reviews.finish(id, "cuerpo", null, 1.0)
        assertNull(ctx.reviews.get(id)?.prAuthor)

        ctx.prStats.upsert(repoId, pr(42, "Ana Gómez", "2026-08-01", PrState.MERGED, "2026-08-03"))
        val rellenadas = ctx.prStats.backfillReviewAuthors()

        assertEquals(1, rellenadas)
        assertEquals("Ana Gómez", ctx.reviews.get(id)?.prAuthor)
    }

    @Test
    fun theBackfillDoesNotOverwriteWhatWasAlreadyKnown() = conRepo { ctx, repoId ->
        // Lo que se guardó al correr la review es de primera mano; el histórico es una
        // reconstrucción. Si difieren, gana el dato original.
        val id = ctx.reviews.start(
            repoId, 42, "t", "sha", ReviewDepth.LIGHT, ProjectKind.BACKEND, "m", false,
            prAuthor = "Quien Corresponde",
        )
        ctx.reviews.finish(id, "cuerpo", null, 1.0)
        ctx.prStats.upsert(repoId, pr(42, "Otra Persona", "2026-08-01"))

        assertEquals(0, ctx.prStats.backfillReviewAuthors())
        assertEquals("Quien Corresponde", ctx.reviews.get(id)?.prAuthor)
    }

    @Test
    fun aPeriodOnlyCountsWhatWasOpenedInIt() = conRepo { ctx, repoId ->
        ctx.prStats.upsert(repoId, pr(1, "Ana Gómez", "2025-03-01", PrState.MERGED, "2025-03-02"))
        ctx.prStats.upsert(repoId, pr(2, "Ana Gómez", "2026-08-01", PrState.MERGED, "2026-08-02"))

        assertEquals(1, ctx.prStats.openedByAuthor("2026-07-01", "2026-09-30").getValue("Ana Gómez").opened)
    }
}

/**
 * El detalle que hay detrás de cada número de la ficha.
 *
 * Un agregado que no se puede abrir sólo sirve para tener una impresión, que es justo lo que este
 * módulo no debería producir.
 */
class PersonDetailTest {

    private fun conRepo(block: (AppContext, String) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("acr-detail")
        val ctx = AppContext.bootstrap(dir)
        try {
            val repoId = ctx.repos.create(
                "tmp-d-${System.nanoTime()}", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            block(ctx, repoId)
        } finally {
            ctx.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun pr(id: Long, autor: String, creado: String, estado: PrState, cerrado: String? = null) =
        PullRequest(
            id = id, title = "PR $id", author = autor, sourceBranch = "f", targetBranch = "d",
            headSha = "sha$id", commentCount = 0, updatedOn = cerrado ?: creado, url = "",
            createdOn = creado, state = estado,
        )

    @Test
    fun theListBehindTheNumberIsComplete() = conRepo { ctx, repoId ->
        // La lista entera y no un top: el PR que explica la cola del percentil 90 puede ser
        // cualquiera, y esconderlo dejaría el número sin la fila que lo justifica.
        ctx.prStats.upsert(repoId, pr(1, "Ana Gómez", "2026-08-01", PrState.MERGED, "2026-08-02"))
        ctx.prStats.upsert(repoId, pr(2, "Ana Gómez", "2026-08-01", PrState.MERGED, "2026-08-21"))
        ctx.prStats.upsert(repoId, pr(3, "Ana Gómez", "2026-08-03", PrState.OPEN))
        ctx.prStats.upsert(repoId, pr(4, "Beto Pérez", "2026-08-01", PrState.MERGED, "2026-08-02"))

        val filas = ctx.prStats.listByAuthor("Ana Gómez", "2026-01-01", "2026-12-31")
        assertEquals(3, filas.size, "los tres de Ana, no los de Beto")
        assertEquals(listOf(3L, 2L, 1L), filas.map { it.prId }, "el más reciente primero")
        assertNull(filas.first { it.prId == 3L }.days, "uno abierto todavía no tiene duración")
        assertEquals(20.0, filas.first { it.prId == 2L }.days, "y el que tardó veinte está a la vista")
    }

    @Test
    fun theFindingsBehindTheSeverityAreListedWithTheirVerdict() = conRepo { ctx, repoId ->
        val id = ctx.reviews.start(
            repoId, 7, "t", "sha", ReviewDepth.LIGHT, ProjectKind.BACKEND, "m", false,
            prAuthor = "Ana Gómez",
        )
        ctx.findings.replaceForReview(
            id, repoId, 7,
            listOf(
                io.acr.data.Finding("", "", 7, "a.kt", 1, "minor", "menor", "x", null, null),
                io.acr.data.Finding("", "", 7, "b.kt", 2, "blocker", "grave", "y", null, null),
            ),
        )
        ctx.reviews.finish(id, "cuerpo", null, 1.0)
        val grave = ctx.findings.forReview(id).first { it.title == "grave" }
        ctx.findings.setResolution(grave.id, io.acr.data.Resolution.UNRESOLVED, "sigue")

        val filas = ctx.reviewStats.findingsFor("Ana Gómez", "1970-01-01", "2999-12-31")
        assertEquals(2, filas.size)
        assertEquals("blocker", filas.first().severity, "lo más grave primero, que es lo que se mira")
        assertEquals("UNRESOLVED", filas.first().resolution)
        assertEquals(7L, filas.first().prId, "con el PR donde está, para poder ir a verlo")
    }

    @Test
    fun theDetailOfSomeoneElseIsNotMixedIn() = conRepo { ctx, repoId ->
        ctx.prStats.upsert(repoId, pr(1, "Ana Gómez", "2026-08-01", PrState.MERGED, "2026-08-02"))
        assertTrue(ctx.prStats.listByAuthor("Beto Pérez", "2026-01-01", "2026-12-31").isEmpty())
    }
}
