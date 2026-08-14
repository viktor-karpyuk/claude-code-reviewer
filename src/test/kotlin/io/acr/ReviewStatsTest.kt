package io.acr

import io.acr.claude.ProjectKind
import io.acr.claude.ReviewDepth
import io.acr.data.Finding
import io.acr.data.Resolution
import io.acr.forge.Provider
import io.acr.stats.GitAuthor
import io.acr.stats.Person
import io.acr.stats.personForDisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Las métricas que salen de nuestras reviews.
 *
 * Son de cobertura parcial —sólo existen para los pull requests que la app revisó o sincronizó— y
 * lo que más importa probar es que la pantalla pueda decir sobre cuánto está calculando. Un número
 * cierto presentado como si cubriera todo es una forma de mentir.
 */
class ReviewStatsTest {

    private fun conRepo(block: (AppContext, String) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("acr-rstats")
        val ctx = AppContext.bootstrap(dir)
        try {
            val repoId = ctx.repos.create(
                "tmp-rs-${System.nanoTime()}", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            block(ctx, repoId)
        } finally {
            ctx.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun AppContext.reviewDe(
        repoId: String,
        prId: Long,
        autor: String?,
        hallazgos: List<Finding> = emptyList(),
    ): String {
        val id = reviews.start(
            repoId, prId, "t", "sha$prId", ReviewDepth.LIGHT, ProjectKind.BACKEND, "m", false,
            prAuthor = autor,
        )
        if (hallazgos.isNotEmpty()) findings.replaceForReview(id, repoId, prId, hallazgos)
        reviews.finish(id, "cuerpo", null, 1.0)
        return id
    }

    /** Un comentario en el hilo del PR. `nuestro` = lo publicó la app en nuestro nombre. */
    private fun comentar(
        ctx: AppContext,
        repoId: String,
        prId: Long,
        id: String,
        autor: String,
        nuestro: Boolean = false,
        cuando: String = "2026-08-01T10:00:00Z",
    ) {
        ctx.comments.sync(
            repoId, prId,
            listOf(io.acr.forge.PrComment(id, autor, "texto", null, null, false, cuando, null)),
            if (nuestro) setOf(id) else emptySet(),
        )
    }

    private fun hallazgo(prId: Long, sev: String, titulo: String = "x") =
        Finding("", "", prId, "a.kt", 1, sev, titulo, "detalle", null, null)

    // --- atribución por nombre del proveedor ---

    @Test
    fun theProviderNameMatchesThePersonFromGit() {
        // En estos repositorios el proveedor dice "Tomás Rivero" y git registra "Tomas Rivero".
        // Normalizan igual, así que enganchan sin configurar nada.
        val personas = io.acr.stats.groupAuthors(
            listOf(
                GitAuthor("Tomas Rivero", "ttomasrivero@gmail.com"),
                GitAuthor("Mateo Andres Perano", "mateo@kubriksoftware.com"),
            ),
        )
        assertNotNull(personForDisplayName("Tomás Rivero", personas))
        assertNotNull(personForDisplayName("Mateo Andrés Perano", personas))
    }

    @Test
    fun someoneWhoOnlyCommentsIsNotForcedIntoTheClosestPerson() {
        // Un nombre del proveedor que no está en git suele ser alguien que comenta pero no
        // commitea —un product owner, un QA—. Meterlo en la persona más parecida le adjudicaría
        // trabajo que no hizo.
        val personas = io.acr.stats.groupAuthors(listOf(GitAuthor("Tomas Rivero", "t@x.com")))
        assertNull(personForDisplayName("Ana Gómez", personas))
        assertNull(personForDisplayName("", personas))
    }

    // --- cobertura ---

    @Test
    fun theCoverageSaysHowMuchIsMissing() = conRepo { ctx, repoId ->
        // Con autor y sin autor: la pantalla tiene que poder decir "3 de 5", no mostrar 3 como si
        // fueran todos.
        ctx.reviewDe(repoId, 1, "Ana Gómez")
        ctx.reviewDe(repoId, 2, "Ana Gómez")
        ctx.reviewDe(repoId, 3, "Beto Pérez")
        ctx.reviewDe(repoId, 4, null)
        ctx.reviewDe(repoId, 5, null)

        val (total, conAutor) = ctx.reviewStats.coverage("1970-01-01", "2999-12-31")
        assertEquals(5, total)
        assertEquals(3, conAutor)
    }

    @Test
    fun aReviewWithoutAuthorDoesNotInventOne() = conRepo { ctx, repoId ->
        // Las reviews viejas no guardaban el autor. Atribuirlas a alguien sería peor que dejarlas
        // afuera: le sumaría hallazgos a quien no le corresponden.
        ctx.reviewDe(repoId, 1, null, listOf(hallazgo(1, "blocker")))
        assertTrue(ctx.reviewStats.findingsByPrAuthor("1970-01-01", "2999-12-31").isEmpty())
    }

    // --- hallazgos recibidos ---

    @Test
    fun findingsAreGroupedBySeverityForThePrAuthor() = conRepo { ctx, repoId ->
        ctx.reviewDe(
            repoId, 1, "Ana Gómez",
            listOf(
                hallazgo(1, "blocker", "a"), hallazgo(1, "major", "b"),
                hallazgo(1, "major", "c"), hallazgo(1, "minor", "d"),
            ),
        )
        val s = assertNotNull(ctx.reviewStats.findingsByPrAuthor("1970-01-01", "2999-12-31")["Ana Gómez"])
        assertEquals(1, s.blocker)
        assertEquals(2, s.major)
        assertEquals(1, s.minor)
        assertEquals(4, s.total)
        assertEquals(1, s.prs)
    }

    @Test
    fun aDismissedFindingIsNotSomethingSomeoneReceived() = conRepo { ctx, repoId ->
        // Descartar es decidir que no había nada que señalar. Contarlo igual le cargaría a alguien
        // un problema que nosotros mismos dijimos que no era.
        val id = ctx.reviewDe(repoId, 1, "Ana Gómez", listOf(hallazgo(1, "major", "a"), hallazgo(1, "major", "b")))
        ctx.findings.dismiss(ctx.findings.forReview(id).first { it.title == "b" }.id)

        assertEquals(1, ctx.reviewStats.findingsByPrAuthor("1970-01-01", "2999-12-31").getValue("Ana Gómez").major)
    }

    @Test
    fun theOnesNotFixedFirstTimeAreCountedApart() = conRepo { ctx, repoId ->
        // Es la señal de retrabajo, y va aparte del total: alto no significa que alguien trabaje
        // peor, puede ser un revisor exigente o un área difícil.
        val id = ctx.reviewDe(
            repoId, 1, "Ana Gómez",
            listOf(hallazgo(1, "major", "a"), hallazgo(1, "major", "b"), hallazgo(1, "minor", "c")),
        )
        val fs = ctx.findings.forReview(id).associateBy { it.title }
        ctx.findings.setResolution(fs.getValue("a").id, Resolution.RESOLVED, "ok")
        ctx.findings.setResolution(fs.getValue("b").id, Resolution.UNRESOLVED, "sigue")
        ctx.findings.setResolution(fs.getValue("c").id, Resolution.PARTIAL, "a medias")

        val s = ctx.reviewStats.findingsByPrAuthor("1970-01-01", "2999-12-31").getValue("Ana Gómez")
        assertEquals(2, s.notFixedFirstTime, "parcial y sin resolver cuentan; resuelto no")
        assertEquals(3, s.total, "y el total no cambia por eso")
    }

    @Test
    fun eachPersonKeepsTheirOwn() = conRepo { ctx, repoId ->
        ctx.reviewDe(repoId, 1, "Ana Gómez", listOf(hallazgo(1, "blocker")))
        ctx.reviewDe(repoId, 2, "Beto Pérez", listOf(hallazgo(2, "minor"), hallazgo(2, "minor")))

        val m = ctx.reviewStats.findingsByPrAuthor("1970-01-01", "2999-12-31")
        assertEquals(1, m.getValue("Ana Gómez").blocker)
        assertEquals(0, m.getValue("Ana Gómez").minor)
        assertEquals(2, m.getValue("Beto Pérez").minor)
    }

    // --- participación ---

    @Test
    fun commentsAreCountedPerAuthorAndPerPr() = conRepo { ctx, repoId ->
        comentar(ctx, repoId, 1, "c1", "Ana Gómez")
        comentar(ctx, repoId, 1, "c2", "Ana Gómez")
        comentar(ctx, repoId, 2, "c3", "Ana Gómez")
        comentar(ctx, repoId, 2, "c4", "Beto Pérez")

        val m = ctx.reviewStats.commentsByAuthor("1970-01-01", "2999-12-31")
        assertEquals(3, m.getValue("Ana Gómez").comments)
        assertEquals(2, m.getValue("Ana Gómez").prs, "tres comentarios pero en dos PRs")
        assertEquals(1, m.getValue("Beto Pérez").comments)
    }

    @Test
    fun whatTheAppPublishedIsNotSomeonesParticipation() = conRepo { ctx, repoId ->
        // La app publica en nuestro nombre: son comentarios nuestros, pero no son una persona
        // participando en una revisión.
        comentar(ctx, repoId, 1, "c1", "Ana Gómez", nuestro = true)
        comentar(ctx, repoId, 1, "c2", "Ana Gómez")

        assertEquals(1, ctx.reviewStats.commentsByAuthor("1970-01-01", "2999-12-31").getValue("Ana Gómez").comments)
        assertEquals(
            2,
            ctx.reviewStats.commentsByAuthor("1970-01-01", "2999-12-31", excludeOurs = false)
                .getValue("Ana Gómez").comments,
        )
    }

    @Test
    fun aPeriodOnlyCountsWhatHappenedInIt() = conRepo { ctx, repoId ->
        comentar(ctx, repoId, 1, "viejo", "Ana Gómez", cuando = "2025-01-01T10:00:00Z")
        comentar(ctx, repoId, 1, "nuevo", "Ana Gómez", cuando = "2026-08-01T10:00:00Z")

        val q3 = ctx.reviewStats.commentsByAuthor("2026-07-01", "2026-09-30")
        assertEquals(1, q3.getValue("Ana Gómez").comments)
    }
}
