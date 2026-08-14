package io.acr

import io.acr.claude.ProjectKind
import io.acr.claude.ReviewDepth
import io.acr.claude.ReviewPrompt
import io.acr.data.Guideline
import io.acr.forge.Provider
import io.acr.forge.PullRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Convenciones propias del equipo.
 *
 * Sin ellas la review marca como problema lo que es una decisión tomada —cómo se nombran las
 * interfaces, dónde va la lógica—, y ese ruido hace que se deje de leer lo que dice.
 */
class GuidelineTest {

    private fun doc(nombre: String, texto: String, repoId: String? = null) = Guideline(
        id = nombre, repoId = repoId, name = nombre, content = texto,
        enabled = true, source = null, createdAt = "2026-08-13T00:00:00Z",
    )

    @Test
    fun theSectionSaysTheyAreDecisionsNotSuggestions() {
        val texto = ReviewPrompt.guidelinesSection(listOf(doc("convenciones.md", "Las interfaces no llevan I.")))
        assertTrue(texto.contains("Las interfaces no llevan I."))
        // Lo importante no es incluirlas sino decir cómo tratarlas.
        assertTrue(texto.contains("NO las reportes como problemas"))
        assertTrue(texto.contains("CONTRADICE"))
    }

    @Test
    fun aRepoRuleOverridesAGeneralOne() {
        val texto = ReviewPrompt.guidelinesSection(
            listOf(doc("general.md", "regla general"), doc("repo.md", "regla del repo", repoId = "r1")),
        )
        assertTrue(texto.indexOf("regla general") < texto.indexOf("regla del repo"))
        assertTrue(texto.contains("manda la del repositorio"))
        // Y cada una dice a qué alcance pertenece.
        assertTrue(texto.contains("todos los repositorios"))
        assertTrue(texto.contains("este repositorio"))
    }

    @Test
    fun withoutDocumentsItAddsNothing() {
        // Un bloque vacío gastaría contexto y sugeriría que hay reglas donde no las hay.
        assertEquals("", ReviewPrompt.guidelinesSection(emptyList()))
    }

    @Test
    fun anEnormousDocumentIsTrimmedAndSaidSo() {
        // El contexto no es gratis: una guía de cien páginas empujaría afuera el diff, que es lo
        // que hay que revisar. Se recorta, pero callarlo sería peor.
        val gigante = doc("arquitectura.md", "x".repeat(ReviewPrompt.MAX_GUIDELINES_CHARS * 2))
        val texto = ReviewPrompt.guidelinesSection(listOf(gigante))
        assertTrue(texto.length < ReviewPrompt.MAX_GUIDELINES_CHARS * 2)
        assertTrue(texto.contains("recortaron"), "recortó sin avisar")
    }

    @Test
    fun theyReachTheReviewPrompt() {
        val prompt = ReviewPrompt.build(
            PullRequest(
                id = 1, title = "PR", author = "dev", sourceBranch = "f", targetBranch = "main",
                headSha = "sha", commentCount = 0, updatedOn = "", url = "",
            ),
            language = "español",
            depth = ReviewDepth.INTERMEDIATE,
            kind = ProjectKind.BACKEND,
            guidelines = listOf(doc("convenciones.md", "Los servicios terminan en Service.")),
        )
        assertTrue(prompt.contains("Los servicios terminan en Service."))
    }

    @Test
    fun globalsAndRepoOnesComeTogetherInTheRightOrder() {
        val ctx = AppContext.bootstrap()
        try {
            val repoId = ctx.repos.create(
                "tmp-guide", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            try {
                ctx.guidelines.add(null, "general.md", "regla general", null)
                ctx.guidelines.add(repoId, "repo.md", "regla del repo", null)
                val docs = ctx.guidelines.forRepo(repoId)
                assertEquals(listOf("general.md", "repo.md"), docs.map { it.name })

                // Desactivada deja de contar, sin borrarse.
                ctx.guidelines.setEnabled(docs.first().id, false)
                assertEquals(listOf("repo.md"), ctx.guidelines.forRepo(repoId).map { it.name })
                assertEquals(2, ctx.guidelines.forRepo(repoId, onlyEnabled = false).size)
            } finally {
                ctx.repos.delete(repoId)
                ctx.guidelines.globals().forEach { ctx.guidelines.delete(it.id) }
            }
        } finally { ctx.close() }
    }
}
