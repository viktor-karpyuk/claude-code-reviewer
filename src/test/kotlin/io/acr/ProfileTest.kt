package io.acr

import io.acr.claude.ProjectKind
import io.acr.claude.ReviewDepth
import io.acr.claude.ReviewPrompt
import io.acr.forge.PullRequest
import kotlin.test.Test
import kotlin.test.assertTrue

class ProfileTest {

    private val pr = PullRequest(
        id = 149, title = "Feature/pos ar fiscal", author = "Braian Chavez",
        sourceBranch = "feature/pos-ar-fiscal", targetBranch = "develop",
        headSha = "47a3cb62", commentCount = 9, updatedOn = "2026-08-04", url = "",
    )

    @Test
    fun depthsProduceDifferentPromptsAndTools() {
        ReviewDepth.entries.forEach { d ->
            val p = ReviewPrompt.build(pr, "español", d, ProjectKind.BACKEND)
            println("${d.label.padEnd(12)} modelo=${d.defaultModel.padEnd(7)} " +
                "herramientas=${d.allowedTools().size} prompt=${p.length} chars")
        }
        // La profunda es la única con acceso al historial: es lo que la hace profunda.
        assertTrue(ReviewDepth.HEAVY.allowedTools().any { it.contains("git log") })
        assertTrue(ReviewDepth.LIGHT.allowedTools().none { it.contains("git log") })
        assertTrue(ReviewDepth.LIGHT.allowedTools().none { it.contains("blame") })
    }

    @Test
    fun projectKindChangesFocus() {
        ProjectKind.entries.forEach { k ->
            val p = ReviewPrompt.build(pr, "español", ReviewDepth.INTERMEDIATE, k)
            val marker = when (k) {
                ProjectKind.BACKEND -> "multi-tenant"
                ProjectKind.FRONTEND_WEB -> "Accesibilidad"
                ProjectKind.MOBILE -> "Offline"
                ProjectKind.FULLSTACK -> "costura"
                ProjectKind.GENERIC -> null
            }
            val ok = marker == null || p.contains(marker)
            println("${k.label.padEnd(16)} prompt=${p.length} chars  foco-inyectado=$ok")
            assertTrue(ok, "el foco de ${k.label} no se inyectó")
        }
        // GENERIC no agrega sección: debe ser el prompt más corto.
        val generic = ReviewPrompt.build(pr, "español", ReviewDepth.INTERMEDIATE, ProjectKind.GENERIC).length
        val backend = ReviewPrompt.build(pr, "español", ReviewDepth.INTERMEDIATE, ProjectKind.BACKEND).length
        assertTrue(generic < backend)
    }

    @Test
    fun migrationV3AppliesAndRepoKeepsProfile() {
        val ctx = AppContext.bootstrap()
        try {
            val repo = ctx.repos.list().firstOrNull() ?: run { println("SKIP: sin repos"); return }
            println("repo ${repo.name}: tipo=${repo.projectKind?.label ?: "Auto"} profundidad=${repo.defaultDepth?.label ?: "Auto"}")
            ctx.repos.update(repo.id, repo.name, repo.localPath, "", ProjectKind.BACKEND, ReviewDepth.HEAVY, "", repo.autoReview, repo.skipRules, repo.replyMode)
            val after = ctx.repos.get(repo.id)!!
            // El test corre contra la base real del usuario: hay que dejarla como estaba.
            ctx.repos.update(
                repo.id, repo.name, repo.localPath, "",
                repo.projectKind, repo.defaultDepth, repo.defaultModel, repo.autoReview, repo.skipRules, repo.replyMode,
            )
            println("tras update: tipo=${after.projectKind?.label ?: "Auto"} profundidad=${after.defaultDepth?.label ?: "Auto"}")
            println("token conservado: ${!after.token.isNullOrBlank()}")
            assertTrue(after.projectKind == ProjectKind.BACKEND)
            assertTrue(after.defaultDepth == ReviewDepth.HEAVY)
            assertTrue(!after.token.isNullOrBlank(), "el update con token vacío no debe borrar el guardado")
        } finally {
            ctx.close()
        }
    }
}
