package io.acr

import io.acr.claude.DiffParser
import io.acr.claude.Git
import io.acr.claude.ReviewDepth
import io.acr.claude.ReviewPlanner
import io.acr.claude.ProjectKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Regresiones de los bugs que salieron en la auditoría. */
class AuditFixesTest {

    @Test
    fun riskMatchingUsesWordsNotSubstrings() {
        // Falsos positivos que antes escalaban a Opus sin motivo.
        assertTrue(!ReviewPlanner.isRisky("src/main/kotlin/AuthorService.kt"), "author != auth")
        assertTrue(!ReviewPlanner.isRisky("src/SyntaxHighlighter.kt"), "syntax != tax")
        assertTrue(!ReviewPlanner.isRisky("src/AcronymResolver.kt"), "acronym != cron")
        assertTrue(!ReviewPlanner.isRisky("frontend/yarn.lock"), "lockfile no es sensible")
        // Verdaderos positivos que tienen que seguir escalando.
        assertTrue(ReviewPlanner.isRisky("db/migration/V0460__algo.sql"))
        assertTrue(ReviewPlanner.isRisky("src/auth/TokenService.kt"))
        assertTrue(ReviewPlanner.isRisky("src/billing/InvoiceCalculator.kt"))
    }

    @Test
    fun gradleBumpIsNotMobile() {
        val plan = ReviewPlanner.plan(listOf(Git.FileChange("build.gradle.kts", 2, 2)), null, null)
        assertTrue(plan.kind != ProjectKind.MOBILE, "un bump de gradle no es una app mobile")
    }

    @Test
    fun realMobilePathsStillDetected() {
        val plan = ReviewPlanner.plan(
            listOf(Git.FileChange("android/app/src/main/AndroidManifest.xml", 4, 1)), null, null,
        )
        assertEquals(ProjectKind.MOBILE, plan.kind)
    }

    @Test
    fun renamedPathsResolveToDestination() {
        assertEquals("src/nuevo/File.kt", Git.resolveRenamed("src/{viejo => nuevo}/File.kt"))
        assertEquals("nuevo.ts", Git.resolveRenamed("viejo.ts => nuevo.ts"))
        assertEquals("normal.kt", Git.resolveRenamed("normal.kt"))
    }

    @Test
    fun binaryDiffProducesNoFakeLines() {
        val raw = "diff --git a/img.png b/img.png\nindex 111..222 100644\nBinary files a/img.png and b/img.png differ\n"
        val lines = DiffParser.parse(raw)
        val anchorable = lines.filter { it.kind == io.acr.claude.DiffLine.Kind.CONTEXT }
        assertTrue(anchorable.isEmpty(), "un binario no debe generar líneas anclables: $anchorable")
    }

    @Test
    fun depthsStillDifferInTools() {
        assertTrue(ReviewDepth.HEAVY.allowedTools().any { it.contains("git log") })
        assertTrue(ReviewDepth.LIGHT.allowedTools().none { it.contains("git log") })
    }
}
