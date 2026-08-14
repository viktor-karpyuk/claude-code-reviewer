package io.acr

import io.acr.data.RepoGuide
import io.acr.forge.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Las tres formas de dar convenciones: subir un `.md`, importarlo del repositorio, o escribirla acá.
 *
 * La tercera cubre el caso más común de todos —la regla que el equipo tiene clara y no está escrita
 * en ningún lado— y por eso no puede exigir crear un archivo primero.
 */
class GuidelineEditTest {

    private fun conRepo(block: (AppContext, String) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("acr-guide-edit")
        val ctx = AppContext.bootstrap(dir)
        try {
            val repoId = ctx.repos.create(
                "tmp-ge-${System.nanoTime()}", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            block(ctx, repoId)
        } finally {
            ctx.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun aTypedGuidelineNeedsNoFile() = conRepo { ctx, repoId ->
        ctx.guidelines.add(repoId, "Controladores", "Los controladores no llevan lógica.", null)
        val g = ctx.guidelines.forRepo(repoId).single()
        assertEquals("Los controladores no llevan lógica.", g.content)
        assertNull(g.linkedPath, "no vino de ningún archivo, y eso es lo que la hace editable")
    }

    @Test
    fun aTypedGuidelineCanBeCorrected() = conRepo { ctx, repoId ->
        // Una convención se afina con el uso: si no se pudiera editar, corregir una coma
        // obligaría a borrarla y volver a escribirla entera.
        val id = ctx.guidelines.add(repoId, "Reglas", "primera version", null)
        ctx.guidelines.update(id, "Reglas del equipo", "segunda version, más clara")

        val g = ctx.guidelines.forRepo(repoId).single()
        assertEquals("Reglas del equipo", g.name)
        assertEquals("segunda version, más clara", g.content)
    }

    @Test
    fun anImportedGuidelineIsNotEditedFromTheApp() = conRepo { ctx, repoId ->
        // Editarla acá la dejaría distinta del archivo del que salió, y el próximo "actualizar"
        // pisaría el cambio sin avisar. Esas se editan en el archivo y se re-importan.
        val dir = java.nio.file.Files.createTempDirectory("acr-guide-src").toFile()
        val f = java.io.File(dir, "CLAUDE.md").apply { writeText("lo que dice el archivo") }
        val id = ctx.guidelines.importFromFile(repoId, RepoGuide("CLAUDE.md", f.absolutePath, f.readText()))

        ctx.guidelines.update(id, "otro nombre", "texto cambiado a mano")

        val g = ctx.guidelines.forRepo(repoId).single()
        assertEquals("lo que dice el archivo", g.content, "el texto sigue siendo el del archivo")
        assertEquals("CLAUDE.md", g.name)
        dir.deleteRecursively()
    }

    @Test
    fun typedAndImportedLiveTogether() = conRepo { ctx, repoId ->
        // Las tres vías conviven: lo del repositorio más lo que el equipo agrega a mano.
        val dir = java.nio.file.Files.createTempDirectory("acr-guide-src2").toFile()
        val f = java.io.File(dir, "CLAUDE.md").apply { writeText("del repo") }
        ctx.guidelines.importFromFile(repoId, RepoGuide("CLAUDE.md", f.absolutePath, f.readText()))
        ctx.guidelines.add(repoId, "A mano", "lo que no está escrito", null)

        val todas = ctx.guidelines.forRepo(repoId)
        assertEquals(2, todas.size)
        assertEquals(1, todas.count { it.linkedPath != null })
        assertTrue(todas.all { it.enabled }, "las dos entran al prompt")
        dir.deleteRecursively()
    }

    @Test
    fun everythingEnabledReachesThePrompt() = conRepo { ctx, repoId ->
        // Da igual de dónde salió: lo que está activo es lo que la review va a leer.
        ctx.guidelines.add(repoId, "Una", "regla uno", null)
        ctx.guidelines.add(repoId, "Otra", "regla dos", null)
        val seccion = io.acr.claude.ReviewPrompt.guidelinesSection(ctx.guidelines.forRepo(repoId))
        assertTrue(seccion.contains("regla uno") && seccion.contains("regla dos"))
    }

    @Test
    fun aDisabledOneStaysOutOfThePrompt() = conRepo { ctx, repoId ->
        val id = ctx.guidelines.add(repoId, "Apagada", "no debería llegar", null)
        ctx.guidelines.setEnabled(id, false)
        val seccion = io.acr.claude.ReviewPrompt.guidelinesSection(ctx.guidelines.forRepo(repoId))
        assertTrue(!seccion.contains("no debería llegar"))
    }
}
