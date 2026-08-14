package io.acr

import io.acr.data.CommitStatRepository
import io.acr.forge.Provider
import io.acr.stats.parseGitLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Los commits de importación, que son los que más distorsionan el reparto del trabajo.
 *
 * Medido sobre la base real: 40 commits —el 1,7% del total— concentraban el **69,5% de todas las
 * líneas**, y al abrirlos eran proyectos enteros copiados al repositorio: un JSON de cien mil
 * líneas, hojas de estilo vendorizadas, librerías. Con ellos adentro el reparto decía 77,9% para
 * una persona que sin ellos tiene 65,9%, y ponía a otra en el segundo puesto por dos commits.
 *
 * Mover un árbol de archivos y escribir código son cosas distintas, y un solo número no puede ser
 * las dos.
 */
class BulkCommitTest {

    private val RS = "\u001e"
    private val US = "\u001f"

    private fun conRepo(block: (AppContext, String) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("acr-bulk")
        val ctx = AppContext.bootstrap(dir)
        try {
            val repoId = ctx.repos.create(
                "tmp-bulk-${System.nanoTime()}", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            block(ctx, repoId)
        } finally {
            ctx.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun commit(sha: String, autor: String, lineas: Int): String {
        val email = autor.lowercase().replace(" ", "") + "@x.com"
        return "$RS$sha$US$autor$US$email${US}2026-08-01T10:00:00Z\n$lineas\t0\ta.kt\n"
    }

    private fun AppContext.guardar(repoId: String, log: String) {
        val cache = mutableListOf<io.acr.stats.Person>()
        parseGitLog(log).forEach { commitStats.upsert(repoId, persons.resolve(it.author, cache), it) }
    }

    @Test
    fun anImportDoesNotDecideWhoDidTheWork() = conRepo { ctx, repoId ->
        ctx.guardar(
            repoId,
            commit("s1", "Importadora", 250_000) +
                commit("s2", "Trabajadora", 900) +
                commit("s3", "Trabajadora", 800) +
                commit("s4", "Trabajadora", 700),
        )
        val importadora = ctx.persons.all().single { it.displayName == "Importadora" }.id
        val trabajadora = ctx.persons.all().single { it.displayName == "Trabajadora" }.id

        val crudo = ctx.commitStats.volumeByPerson("2026-01-01", "2026-12-31", repoId)
        assertTrue(
            crudo.getValue(importadora).touched > crudo.getValue(trabajadora).touched * 100,
            "en crudo, una sola importación aplasta a todo lo demás",
        )

        val reparto = ctx.commitStats.volumeByPerson(
            "2026-01-01", "2026-12-31", repoId, maxLines = CommitStatRepository.BULK_COMMIT_LINES,
        )
        assertNull(reparto[importadora], "la importación sale del reparto")
        assertEquals(2400, reparto.getValue(trabajadora).touched, "y el trabajo real queda intacto")
    }

    @Test
    fun theOverviewCanSayHowMuchTheImportsWeigh() = conRepo { ctx, repoId ->
        // Sin ese número el anillo describiría unas pocas importaciones y nadie lo sabría.
        ctx.guardar(repoId, commit("s1", "A", 90_000) + commit("s2", "B", 500))
        val (cuantos, enBulk, total) = ctx.commitStats.bulkShare("2026-01-01", "2026-12-31")
        assertEquals(1, cuantos)
        assertEquals(90_000L, enBulk)
        assertEquals(90_500L, total)
        assertTrue(100.0 * enBulk / total > 99, "y se puede decir qué porcentaje se llevan")
    }

    @Test
    fun aBigButRealCommitIsNotAnImport() = conRepo { ctx, repoId ->
        // El umbral tiene que dejar pasar el trabajo grande de verdad: un refactor de casi cinco
        // mil líneas es trabajo, no una importación. Poner el corte más abajo empezaría a borrar
        // gente que sí escribió el código.
        ctx.guardar(repoId, commit("s1", "A", 4_900))
        assertEquals(0, ctx.commitStats.bulkShare("2026-01-01", "2026-12-31").first)
        assertEquals(
            1,
            ctx.commitStats.volumeByPerson(
                "2026-01-01", "2026-12-31", repoId, maxLines = CommitStatRepository.BULK_COMMIT_LINES,
            ).size,
        )
    }

    @Test
    fun withNoImportsTheWarningStaysQuiet() = conRepo { ctx, repoId ->
        // Un repositorio sano no tiene que ver una alerta que no le corresponde.
        ctx.guardar(repoId, commit("s1", "A", 100) + commit("s2", "B", 200))
        assertEquals(0, ctx.commitStats.bulkShare("2026-01-01", "2026-12-31").first)
    }

    @Test
    fun theRawTotalIsStillAvailable() = conRepo { ctx, repoId ->
        // El reparto excluye las importaciones, pero el total crudo sigue existiendo: son dos
        // preguntas distintas y las dos son legítimas.
        ctx.guardar(repoId, commit("s1", "A", 250_000) + commit("s2", "A", 100))
        val a = ctx.persons.all().single().id
        assertEquals(250_100, ctx.commitStats.volumeByPerson("2026-01-01", "2026-12-31", repoId).getValue(a).touched)
    }
}
