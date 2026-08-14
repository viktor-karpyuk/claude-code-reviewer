package io.acr

import io.acr.forge.Provider
import io.acr.stats.GitAuthor
import io.acr.stats.parseGitLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Quien ya no está en el equipo.
 *
 * La spec dejó esto como pregunta abierta —archivar y sacar de los agregados, o seguir mostrando a
 * la persona en los períodos en que sí trabajó—. La respuesta resultó ser las dos cosas: se oculta
 * de los reportes pero no se borra nada, porque en un trimestre viejo esa persona sí estuvo y sacar
 * su trabajo del histórico haría bajar los totales del equipo sin que nadie entienda por qué.
 */
class ArchivedPeopleTest {

    private val RS = "\u001e"
    private val US = "\u001f"

    private fun conCtx(block: (AppContext) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("acr-arch")
        val ctx = AppContext.bootstrap(dir)
        try { block(ctx) } finally { ctx.close(); dir.toFile().deleteRecursively() }
    }

    @Test
    fun archivingHidesThePersonWithoutLosingTheirData() = conCtx { ctx ->
        val cache = mutableListOf<io.acr.stats.Person>()
        val id = ctx.persons.resolve(GitAuthor("Quien Se Fue", "sefue@x.com"), cache)

        ctx.persons.setArchived(id, true)

        val p = ctx.persons.all().single { it.id == id }
        assertTrue(p.archived, "queda marcada")
        assertTrue(p.identities.isNotEmpty(), "y sus identidades siguen ahí")
        // Lo que se filtra es la vista, no el dato.
        assertEquals(0, ctx.persons.all().count { !it.archived })
    }

    @Test
    fun itCanBeUndone() = conCtx { ctx ->
        // Alguien puede volver, o el archivado puede ser un error. Si no se pudiera deshacer,
        // nadie se animaría a usarlo.
        val cache = mutableListOf<io.acr.stats.Person>()
        val id = ctx.persons.resolve(GitAuthor("Volvio", "volvio@x.com"), cache)
        ctx.persons.setArchived(id, true)
        ctx.persons.setArchived(id, false)
        assertTrue(!ctx.persons.all().single { it.id == id }.archived)
    }

    @Test
    fun theWorkOfSomeoneWhoLeftStillExistsInOldQuarters() = conCtx { ctx ->
        // El punto de archivar en vez de borrar: la persona desaparece de la tabla, pero lo que
        // hizo sigue existiendo y el total de ese período no cambia.
        val repoId = ctx.repos.create(
            "tmp-arch", Provider.BITBUCKET, "acme", "demo", System.getProperty("java.io.tmpdir"),
            null, null, null, "", false, io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
        )
        val cache = mutableListOf<io.acr.stats.Person>()
        val log = "${RS}sha1${US}Quien Se Fue${US}sefue@x.com${US}2025-03-01T10:00:00Z\n10\t0\ta.kt\n"
        val c = parseGitLog(log).single()
        val id = ctx.persons.resolve(c.author, cache)
        ctx.commitStats.upsert(repoId, id, c)

        ctx.persons.setArchived(id, true)

        val vol = ctx.commitStats.volumeByPerson("2025-01-01", "2025-12-31", repoId)
        assertEquals(10, vol.getValue(id).added, "el dato histórico no se toca")
    }

    @Test
    fun aBotIsHiddenForADifferentReasonAndBothCanBeTrue() = conCtx { ctx ->
        // Son marcas independientes: un bot que dejó de correr está las dos cosas, y desmarcar una
        // no debería devolverlo a los reportes por la otra.
        val cache = mutableListOf<io.acr.stats.Person>()
        val id = ctx.persons.resolve(GitAuthor("dependabot", "bot@x.com"), cache)
        ctx.persons.setBot(id, true)
        ctx.persons.setArchived(id, true)

        val p = ctx.persons.all().single { it.id == id }
        assertTrue(p.isBot && p.archived)

        ctx.persons.setArchived(id, false)
        assertTrue(ctx.persons.all().single { it.id == id }.isBot, "sigue siendo un bot")
    }
}
