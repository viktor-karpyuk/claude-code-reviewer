package io.acr

import io.acr.claude.Git
import io.acr.claude.ModelCatalog
import io.acr.claude.ClaudeCli
import io.acr.claude.ReviewPlanner
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class AutoModeTest {

    @Test
    fun discoversModelsFromTheLocalCli() {
        val binary = ClaudeCli.resolveBinary(null)
        println("binario: $binary")
        val models = runBlocking { ModelCatalog.discover(binary) }
        println("modelos descubiertos: $models")
        assertTrue(models.isNotEmpty())
    }

    @Test
    fun inferPlanFromRealDiff() {
        val dir = File("/Users/viktor/dev/kubrik/ks-erp/kubrik-erp-be")
        if (!Git.isRepo(dir)) { println("SKIP: sin clon local"); return }
        val range = "origin/develop...origin/feature/pos-ar-fiscal"
        val files = runBlocking { Git.numstat(dir, range) }
        println("archivos en el diff: ${files.size}, líneas: ${files.sumOf { it.touched }}")
        val plan = ReviewPlanner.plan(files, null, null)
        println("PLAN AUTO -> ${plan.depth.label} / ${plan.kind.label}")
        println("motivo: ${plan.reason}")
        assertTrue(files.isNotEmpty())
    }

    @Test
    fun smallSafeDiffGoesLight() {
        val files = listOf(
            Git.FileChange("src/main/kotlin/io/acr/ui/Ext.kt", 8, 2),
            Git.FileChange("README.md", 4, 1),
        )
        val plan = ReviewPlanner.plan(files, null, null)
        println("diff chico -> ${plan.depth.label} (${plan.reason})")
        assertTrue(plan.depth == io.acr.claude.ReviewDepth.LIGHT)
    }

    @Test
    fun migrationForcesHeavyEvenWhenTiny() {
        val files = listOf(Git.FileChange("db/migration/V0460__algo.sql", 3, 0))
        val plan = ReviewPlanner.plan(files, null, null)
        println("migración de 3 líneas -> ${plan.depth.label} (${plan.reason})")
        assertTrue(plan.depth == io.acr.claude.ReviewDepth.HEAVY)
    }

    @Test
    fun detectsFrontendAndMobile() {
        val web = ReviewPlanner.plan(
            listOf(Git.FileChange("src/app/list.component.ts", 40, 5),
                   Git.FileChange("src/styles.scss", 10, 0)), null, null)
        println("web -> ${web.kind.label}")
        val mobile = ReviewPlanner.plan(
            listOf(Git.FileChange("android/app/src/main/AndroidManifest.xml", 5, 1),
                   Git.FileChange("lib/screens/home.dart", 60, 10)), null, null)
        println("mobile -> ${mobile.kind.label}")
        assertTrue(web.kind == io.acr.claude.ProjectKind.FRONTEND_WEB)
        assertTrue(mobile.kind == io.acr.claude.ProjectKind.MOBILE)
    }
}
