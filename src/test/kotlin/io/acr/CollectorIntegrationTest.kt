package io.acr

import io.acr.forge.Provider
import io.acr.forge.RepoRecord
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El recolector contra un repositorio de git de verdad.
 *
 * Los otros tests le dan salida de `git log` fabricada, que prueba el parseo pero no que el
 * comando que se arma sea el correcto, ni que el formato con caracteres de control sobreviva al
 * subproceso, ni que las rutas del repo real caigan donde se espera. Eso sólo se sabe corriéndolo.
 *
 * Usa el propio repositorio de la app: siempre está, es chico y su historia es conocida. Si no
 * estuviera —un clon sin `.git`, o el test corriendo desde otro lado— se saltea en vez de fallar:
 * es una verificación de integración, no un requisito para poder compilar.
 */
class CollectorIntegrationTest {

    private fun repoPropio(): File? =
        generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, ".git").exists() }

    private fun conRepo(dir: File, block: (AppContext, RepoRecord) -> Unit) {
        val data = java.nio.file.Files.createTempDirectory("acr-collect-it")
        val ctx = AppContext.bootstrap(data)
        try {
            val id = ctx.repos.create(
                "self", Provider.BITBUCKET, "acme", "demo", dir.absolutePath,
                null, null, null, "", false, io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            block(ctx, ctx.repos.list().single { it.id == id })
        } finally {
            ctx.close()
            data.toFile().deleteRecursively()
        }
    }

    @Test
    fun itReadsARealRepositoryEndToEnd() {
        val dir = repoPropio()
        if (dir == null) {
            println("SKIP: no encontré un repositorio de git desde ${File(".").canonicalPath}")
            return
        }
        conRepo(dir) { ctx, repo ->
            val out = runBlocking { ctx.statsCollector.collect(repo, since = "24 months ago") }

            assertEquals(null, out.error, "la recolección no debería fallar sobre un repo real")
            assertTrue(out.done)
            assertTrue(out.commits > 0, "un repositorio con historia tiene commits")
            assertEquals(out.commits, ctx.commitStats.count(repo.id), "se guardó lo que se contó")

            // Toda persona encontrada tiene al menos una identidad: sin eso no se la puede volver
            // a reconocer y cada corrida la duplicaría.
            val personas = ctx.persons.all()
            assertTrue(personas.isNotEmpty())
            assertTrue(personas.all { it.identities.isNotEmpty() })

            // Y todo commit quedó atribuido: un commit sin persona no aparece en ningún total, y
            // desaparecer en silencio es justo lo que no puede pasar.
            val vol = ctx.commitStats.volumeByPerson("1970-01-01", "2999-12-31", repo.id)
            assertEquals(
                out.commits,
                vol.values.sumOf { it.commits },
                "todos los commits tienen que estar atribuidos a alguien",
            )
            assertTrue(vol.values.sumOf { it.added } > 0, "y con volumen de código real")
            println(
                "OK: ${out.commits} commits, ${personas.size} personas, " +
                    "${vol.values.sumOf { it.added }}+/${vol.values.sumOf { it.deleted }}- de código, " +
                    "${vol.values.sumOf { it.generated }} líneas generadas excluidas",
            )
        }
    }

    @Test
    fun collectingTwiceDoesNotDuplicateAnything() {
        val dir = repoPropio() ?: return
        conRepo(dir) { ctx, repo ->
            val primera = runBlocking { ctx.statsCollector.collect(repo, since = "6 months ago") }
            val personasPrimera = ctx.persons.all().size
            val segunda = runBlocking { ctx.statsCollector.collect(repo, since = "6 months ago") }

            // Recolectar de nuevo es lo normal —hay commits nuevos— y volver a ver los viejos no
            // puede inflar nada ni inventar personas.
            assertEquals(primera.commits, segunda.commits)
            assertEquals(primera.commits, ctx.commitStats.count(repo.id))
            assertEquals(personasPrimera, ctx.persons.all().size)
        }
    }

    @Test
    fun aPathThatIsNotARepositoryFailsWithAReadableMessage() {
        val data = java.nio.file.Files.createTempDirectory("acr-collect-bad")
        val ctx = AppContext.bootstrap(data)
        try {
            val id = ctx.repos.create(
                "roto", Provider.BITBUCKET, "acme", "demo", System.getProperty("java.io.tmpdir"),
                null, null, null, "", false, io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            val out = runBlocking { ctx.statsCollector.collect(ctx.repos.list().single { it.id == id }) }
            assertTrue(out.error != null, "un repositorio sin clonar tiene que decirlo, no romper")
            assertTrue(!out.done)
            assertEquals(0, out.commits)
        } finally {
            ctx.close()
            data.toFile().deleteRecursively()
        }
    }
}
