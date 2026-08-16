package io.acr

import io.acr.impl.FileChange
import io.acr.impl.FileKind
import io.acr.impl.analyze
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lo que se puede afirmar de una tarea mirando qué archivos tocó.
 *
 * No son adornos: cada señal responde algo concreto que uno se pregunta al revisar código escrito
 * sin supervisión. "Tocó 12 archivos" no se puede juzgar; "300 líneas de código y ningún test" sí.
 */
class TaskAnalysisTest {

    @Test
    fun aTestFileIsATestEvenIfItLooksLikeSomethingElse() {
        // El orden de clasificación importa: un .sql dentro de test/ es un test, no una migración.
        assertEquals(FileKind.TEST, FileKind.of("src/test/kotlin/FooTest.kt"))
        assertEquals(FileKind.TEST, FileKind.of("src/test/resources/datos.sql"))
        assertEquals(FileKind.TEST, FileKind.of("web/src/app/foo.spec.ts"))
        assertEquals(FileKind.MIGRATION, FileKind.of("app/db/migration/V0306__algo.sql"))
        assertEquals(FileKind.CONFIG, FileKind.of("build.gradle.kts"))
        assertEquals(FileKind.CODE, FileKind.of("src/main/kotlin/Foo.kt"))
    }

    @Test
    fun aTaskWithoutTestsSaysSo() {
        // Trescientas líneas de código sin un test es una cosa y con tests es otra: el total solo
        // no distingue, y es de lo primero que uno mira al revisar.
        val a = analyze(listOf(FileChange('A', "src/main/Foo.kt", 300, 0)))!!
        assertTrue(!a.touchedTests)

        val b = analyze(
            listOf(
                FileChange('A', "src/main/Foo.kt", 300, 0),
                FileChange('A', "src/test/FooTest.kt", 80, 0),
            ),
        )!!
        assertTrue(b.touchedTests)
    }

    @Test
    fun concentrationTellsTwoVeryDifferentChangesApart() {
        // 400 líneas en un archivo es un archivo para leer entero; 40 en diez es una
        // refactorización dispersa. El total es el mismo.
        val concentrado = analyze(
            listOf(FileChange('M', "Grande.kt", 400, 0)) +
                (1..9).map { FileChange('M', "chico$it.kt", 3, 0) },
        )!!
        assertTrue(concentrado.concentration > 0.9)
        assertEquals("Grande.kt", concentrado.biggestFile)

        val disperso = analyze((1..10).map { FileChange('M', "f$it.kt", 40, 0) })!!
        assertTrue(disperso.concentration < 0.2)
    }

    @Test
    fun migrationsAndConfigAreFlaggedApart() {
        // Un error ahí no lo atrapa revisar la lógica, que es lo que uno hace por defecto.
        val a = analyze(
            listOf(
                FileChange('A', "db/migration/V1__x.sql", 20, 0),
                FileChange('M', "build.gradle.kts", 3, 1),
                FileChange('M', "src/Foo.kt", 10, 2),
            ),
        )!!
        assertEquals(2, a.sensitive.size)
        assertTrue(a.sensitive.any { it.contains("migration") })
    }

    @Test
    fun aDocsOnlyTaskIsNotAnImplementation() {
        // Puede estar bien, pero conviene que se note en vez de contarse como si hubiera
        // implementado algo.
        val a = analyze(listOf(FileChange('M', "README.md", 10, 2)))!!
        assertTrue(a.onlyDocs)
        assertTrue(!analyze(listOf(FileChange('M', "src/Foo.kt", 1, 0)))!!.onlyDocs)
    }

    @Test
    fun nothingTouchedIsNothingToAnalyze() {
        // Null y no un análisis en cero: no es lo mismo "no tocó nada" que "tocó cosas neutras".
        assertNull(analyze(emptyList()))
    }

    @Test
    fun theCompositionAddsUpToWhatWasTouched() {
        val a = analyze(
            listOf(
                FileChange('A', "src/Foo.kt", 100, 0),
                FileChange('A', "src/test/FooTest.kt", 50, 0),
                FileChange('M', "README.md", 5, 5),
            ),
        )!!
        assertEquals(160, a.byKind.values.sumOf { it.second })
        assertEquals(1 to 100, a.byKind[FileKind.CODE])
    }
}
