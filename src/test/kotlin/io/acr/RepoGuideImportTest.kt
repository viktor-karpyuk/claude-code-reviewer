package io.acr

import io.acr.data.LinkState
import io.acr.data.RepoGuide
import io.acr.data.findRepoGuides
import io.acr.data.guideHash
import io.acr.data.linkStateOf
import io.acr.forge.Provider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Importar las convenciones que el repositorio ya trae escritas.
 *
 * El módulo de guías se shipeó con cero documentos cargados mientras el repo real tenía un
 * CLAUDE.md completo a dos directorios de distancia. Pedirle a alguien que lo busque a mano es
 * trabajo inventado.
 *
 * La regla que más importa probar es la que NO se implementó: el contenido no se re-lee del disco
 * en cada review. Eso lo decidió la migración v34 y sigue vigente —si saliera del archivo, un
 * `git checkout` cambiaría las reglas de revisión sin que nadie se entere—. Lo que sí se hace es
 * avisar cuando el archivo cambió.
 */
class RepoGuideImportTest {

    private fun tempRepo(): File =
        java.nio.file.Files.createTempDirectory("acr-guides").toFile().also { it.deleteOnExit() }

    private fun conRepo(block: (AppContext, String) -> Unit) {
        val ctx = AppContext.bootstrap()
        try {
            val repoId = ctx.repos.create(
                "tmp-guide-${System.nanoTime()}", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            try { block(ctx, repoId) } finally { ctx.repos.delete(repoId) }
        } finally { ctx.close() }
    }

    // --- descubrimiento ---

    @Test
    fun itFindsTheClaudeMdAtTheRoot() {
        val dir = tempRepo()
        File(dir, "CLAUDE.md").writeText("# Convenciones\n\nNo uses var.")
        val hallados = findRepoGuides(dir.absolutePath)
        assertEquals(1, hallados.size)
        assertEquals("CLAUDE.md", hallados.single().name)
        assertTrue(hallados.single().content.contains("No uses var"))
    }

    @Test
    fun itFindsTheOnesInSubprojects() {
        // El caso real: uno arriba con las reglas generales y otro por proyecto con las suyas.
        val dir = tempRepo()
        File(dir, "CLAUDE.md").writeText("raiz")
        File(dir, "frontend").mkdirs()
        File(dir, "frontend/CLAUDE.md").writeText("frontend")
        val hallados = findRepoGuides(dir.absolutePath)
        assertEquals(2, hallados.size)
        // La raíz primero: es el marco general, y leerla después de la específica la contradice.
        assertEquals("CLAUDE.md", hallados.first().name)
        assertTrue(hallados.any { it.name.endsWith("CLAUDE.md") && it.name.contains("frontend") })
    }

    @Test
    fun itDoesNotWalkIntoNodeModules() {
        // Sin esto, un monorepo con node_modules son decenas de miles de directorios recorridos
        // para encontrar tres archivos.
        val dir = tempRepo()
        File(dir, "node_modules/paquete").mkdirs()
        File(dir, "node_modules/paquete/CLAUDE.md").writeText("de una dependencia, no nuestro")
        assertEquals(emptyList(), findRepoGuides(dir.absolutePath))
    }

    @Test
    fun anEmptyFileIsNotAGuideline() {
        val dir = tempRepo()
        File(dir, "CLAUDE.md").writeText("   \n  ")
        assertEquals(emptyList(), findRepoGuides(dir.absolutePath))
    }

    @Test
    fun aMissingCloneIsNotAnError() {
        // Un repositorio recién agregado puede no estar clonado todavía. Eso no justifica
        // interrumpir a nadie con un error.
        assertEquals(emptyList(), findRepoGuides("/no/existe/esta/ruta"))
        assertEquals(emptyList(), findRepoGuides(""))
    }

    // --- estado del vínculo ---

    @Test
    fun anUntouchedFileReadsAsInSync() {
        val texto = "# Reglas"
        assertEquals(
            LinkState.IN_SYNC,
            linkStateOf("/x/CLAUDE.md", guideHash(texto), leer = { texto }),
        )
    }

    @Test
    fun anEditedFileReadsAsStale() {
        // Este es el punto de la mejora: la guía sigue siendo la importada, pero hay que decirlo.
        assertEquals(
            LinkState.STALE,
            linkStateOf("/x/CLAUDE.md", guideHash("viejo"), leer = { "nuevo" }),
        )
    }

    @Test
    fun aVanishedFileDoesNotDiscardTheSavedCopy() {
        assertEquals(
            LinkState.MISSING,
            linkStateOf("/x/CLAUDE.md", guideHash("algo"), leer = { null }),
        )
    }

    @Test
    fun anUploadedFileHasNoLink() {
        assertEquals(LinkState.NOT_LINKED, linkStateOf(null, null))
        assertEquals(LinkState.NOT_LINKED, linkStateOf("/x/CLAUDE.md", null))
    }

    // --- persistencia ---

    @Test
    fun importingStoresTheTextAndRemembersWhereItCameFrom() = conRepo { ctx, repoId ->
        val dir = tempRepo()
        val f = File(dir, "CLAUDE.md").apply { writeText("# Reglas\n\nControllers finos.") }

        ctx.guidelines.importFromFile(repoId, findRepoGuides(dir.absolutePath).single())

        val g = assertNotNull(ctx.guidelines.forRepo(repoId).firstOrNull())
        // El texto queda en la base, no una ruta: es lo que decidió la v34.
        assertTrue(g.content.contains("Controllers finos"))
        assertEquals(f.absolutePath, g.linkedPath)
        assertEquals(guideHash(f.readText()), g.linkedHash)
        assertEquals(LinkState.IN_SYNC, linkStateOf(g.linkedPath, g.linkedHash))
    }

    @Test
    fun reimportingTheSameFileUpdatesInsteadOfDuplicating() = conRepo { ctx, repoId ->
        // Dos filas del mismo archivo mandarían el criterio dos veces al prompt y gastarían dos
        // veces del tope de contexto para decir lo mismo.
        val dir = tempRepo()
        val f = File(dir, "CLAUDE.md").apply { writeText("primera version") }
        ctx.guidelines.importFromFile(repoId, findRepoGuides(dir.absolutePath).single())

        f.writeText("segunda version")
        ctx.guidelines.importFromFile(repoId, findRepoGuides(dir.absolutePath).single())

        val todas = ctx.guidelines.forRepo(repoId)
        assertEquals(1, todas.size)
        assertEquals("segunda version", todas.single().content)
        assertEquals(LinkState.IN_SYNC, linkStateOf(todas.single().linkedPath, todas.single().linkedHash))
    }

    @Test
    fun updatingTheTextDoesNotSilentlyTurnTheGuidelineBackOn() = conRepo { ctx, repoId ->
        // Si alguien la apagó a propósito, que el archivo cambie no es motivo para reactivarla.
        val dir = tempRepo()
        val f = File(dir, "CLAUDE.md").apply { writeText("v1") }
        val id = ctx.guidelines.importFromFile(repoId, findRepoGuides(dir.absolutePath).single())
        ctx.guidelines.setEnabled(id, false)

        f.writeText("v2")
        ctx.guidelines.importFromFile(repoId, findRepoGuides(dir.absolutePath).single())

        val g = ctx.guidelines.forRepo(repoId, onlyEnabled = false).single()
        assertEquals("v2", g.content)
        assertTrue(!g.enabled, "una guía apagada no se reactiva sola al actualizarse")
    }

    @Test
    fun anEditedFileIsReportedAsStaleFromTheDatabase() = conRepo { ctx, repoId ->
        val dir = tempRepo()
        val f = File(dir, "CLAUDE.md").apply { writeText("original") }
        ctx.guidelines.importFromFile(repoId, findRepoGuides(dir.absolutePath).single())

        f.writeText("editado después de importar")

        val g = ctx.guidelines.forRepo(repoId).single()
        // Lo que se usa para revisar sigue siendo lo importado…
        assertEquals("original", g.content)
        // …pero la pantalla tiene que poder decir que quedó viejo.
        assertEquals(LinkState.STALE, linkStateOf(g.linkedPath, g.linkedHash))
    }

    @Test
    fun aHandUploadedGuidelineIsUntouchedByAllThis() = conRepo { ctx, repoId ->
        ctx.guidelines.add(repoId, "manual.md", "texto a mano", null)
        val g = ctx.guidelines.forRepo(repoId).single()
        assertEquals(LinkState.NOT_LINKED, linkStateOf(g.linkedPath, g.linkedHash))
    }

    @Test
    fun globalAndRepoImportsDoNotCollide() = conRepo { ctx, repoId ->
        // El mismo archivo puede importarse como global y como del repo: son dos decisiones
        // distintas y ninguna debería pisar a la otra.
        val dir = tempRepo()
        File(dir, "CLAUDE.md").writeText("compartido")
        val guia = findRepoGuides(dir.absolutePath).single()

        val idGlobal = ctx.guidelines.importFromFile(null, guia)
        val idRepo = ctx.guidelines.importFromFile(repoId, guia)
        try {
            assertTrue(idGlobal != idRepo)
            assertEquals(1, ctx.guidelines.globals().count { it.linkedPath == guia.path })
            assertEquals(1, ctx.guidelines.forRepo(repoId).count { it.repoId == repoId })
        } finally {
            ctx.guidelines.delete(idGlobal)
        }
    }

    @Test
    fun theSizeCapStillGovernsWhatReachesThePrompt() {
        // Importar puede traer un documento grande —el CLAUDE.md real ronda los 13.000
        // caracteres— y el tope de contexto sigue siendo el que manda.
        val gigante = RepoGuide("CLAUDE.md", "/x", "a".repeat(io.acr.claude.ReviewPrompt.MAX_GUIDELINES_CHARS * 2))
        val armado = io.acr.claude.ReviewPrompt.guidelinesSection(
            listOf(
                io.acr.data.Guideline(
                    id = "1", repoId = null, name = gigante.name, content = gigante.content,
                    enabled = true, source = null, createdAt = "2026-01-01T00:00:00Z",
                ),
            ),
        )
        assertTrue(
            armado.length <= io.acr.claude.ReviewPrompt.MAX_GUIDELINES_CHARS + 2_000,
            "la sección de guías creció más allá del tope: ${armado.length}",
        )
    }
}
