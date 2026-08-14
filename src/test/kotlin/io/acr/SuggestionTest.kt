package io.acr

import io.acr.claude.ProjectKind
import io.acr.claude.ReviewDepth
import io.acr.data.Finding
import io.acr.forge.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cada hallazgo puede traer cómo se resolvería. Señalar sin proponer deja todo el trabajo de
 * pensar la solución del otro lado, y muchas veces quien encontró el problema ya sabe el arreglo.
 */
class SuggestionTest {

    private fun conReview(block: (AppContext, String, String, Long) -> Unit) {
        val ctx = AppContext.bootstrap()
        try {
            val prId = System.nanoTime() % 100_000
            val repoId = ctx.repos.create(
                "tmp-sug-$prId", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            try {
                val reviewId = ctx.reviews.start(
                    repoId, prId, "PR", "sha", ReviewDepth.LIGHT, ProjectKind.BACKEND, "haiku", false,
                )
                ctx.reviews.finish(reviewId, "cuerpo", null, null)
                block(ctx, repoId, reviewId, prId)
            } finally {
                ctx.repos.delete(repoId)
            }
        } finally {
            ctx.close()
        }
    }

    @Test
    fun theSuggestionSurvivesTheRoundTrip() = conReview { ctx, repoId, reviewId, prId ->
        val propuesta = "```kotlin\nval x = requireNotNull(y) { \"y es obligatorio\" }\n```"
        ctx.findings.replaceForReview(
            reviewId, repoId, prId,
            listOf(
                Finding("", reviewId, prId, "src/A.kt", 3, "major", "t", "b", null, null,
                    suggestion = propuesta),
            ),
        )
        assertEquals(propuesta, ctx.findings.forReview(reviewId).single().suggestion)
    }

    @Test
    fun aFindingWithoutASuggestionIsValid() {
        // El prompt pide explícitamente dejarlo vacío cuando no se puede sostener una propuesta:
        // una sugerencia inventada es peor que ninguna.
        conReview { ctx, repoId, reviewId, prId ->
            ctx.findings.replaceForReview(
                reviewId, repoId, prId,
                listOf(Finding("", reviewId, prId, "src/A.kt", 3, "minor", "t", "b", null, null)),
            )
            assertNull(ctx.findings.forReview(reviewId).single().suggestion)
        }
    }

    @Test
    fun theSchemaAllowsButDoesNotRequireIt() {
        // Si `suggestion` fuera obligatorio, el CLI reintentaría hasta que el modelo invente una.
        val esquema = io.acr.claude.ReviewPrompt.SCHEMA
        assertTrue(esquema.contains("\"suggestion\""), "el esquema no la contempla")
        // Se afirma lo que importa —que no esté entre las obligatorias— y no la lista completa:
        // fijarla entera hacía fallar este test al agregar un campo nuevo que no tiene nada que
        // ver con las sugerencias.
        val obligatorias = Regex("\"required\":\\[([^]]*)]").find(esquema)?.groupValues?.get(1).orEmpty()
        assertTrue(obligatorias.isNotBlank(), "el esquema declara campos obligatorios")
        assertTrue(!obligatorias.contains("suggestion"), "suggestion no puede ser obligatoria")
        assertTrue(esquema.contains("[\"string\",\"null\"]"), "tiene que poder venir nula")
    }

    @Test
    fun thePromptTellsItNotToInventOne() {
        val prompt = io.acr.claude.ReviewPrompt.build(
            io.acr.forge.PullRequest(
                id = 1, title = "PR", author = "dev", sourceBranch = "f", targetBranch = "main",
                headSha = "sha", commentCount = 0, updatedOn = "", url = "",
            ),
            language = "español",
            depth = ReviewDepth.INTERMEDIATE,
            kind = ProjectKind.BACKEND,
        )
        assertTrue(prompt.contains("suggestion"), "el prompt no pide la sugerencia")
        assertTrue(prompt.contains("null"), "el prompt no dice cuándo dejarla vacía")
    }

    @Test
    fun bothLanguagesHaveTheLabel() {
        listOf(io.acr.i18n.Lang.ES, io.acr.i18n.Lang.EN).forEach {
            assertTrue(
                io.acr.i18n.I18n.get(it, "finding.suggestion") != "finding.suggestion",
                "falta la etiqueta en $it",
            )
        }
    }
}
