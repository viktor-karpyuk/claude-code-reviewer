package io.acr

import io.acr.forge.Provider
import io.acr.impl.ImplStatus
import io.acr.impl.ImplTask
import io.acr.impl.QuestionKind
import io.acr.impl.TaskSize
import io.acr.impl.TaskStatus
import io.acr.impl.loadSources
import io.acr.impl.progressOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El módulo de implementaciones.
 *
 * Corre sin supervisión y escribe código, así que lo que más importa probar es dónde se frena y
 * qué pasa cuando algo sale mal: una implementación autónoma que se cuelga esperando, o que sigue
 * adelante sobre una decisión inventada, es peor que una que no arranca.
 */
class ImplTest {

    private fun conRepo(block: (AppContext, String) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("acr-impl")
        val ctx = AppContext.bootstrap(dir)
        try {
            val repoId = ctx.repos.create(
                "tmp-i-${System.nanoTime()}", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            block(ctx, repoId)
        } finally {
            ctx.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun tarea(seq: Int, titulo: String, size: TaskSize = TaskSize.M, dep: List<Int> = emptyList()) =
        ImplTask("", "", seq, titulo, "detalle", dep, size, size.minutes, TaskStatus.PENDING,
            null, null, null, null, null, null)

    // --- documentos de entrada ---

    @Test
    fun aFolderOfSpecsIsReadWhole() {
        // Las specs de algo real casi nunca son un archivo: son requerimientos, mockups y un plan
        // conviviendo en un directorio, y elegirlos de a uno sería armar a mano una lista que ya
        // existe.
        val dir = java.nio.file.Files.createTempDirectory("acr-specs").toFile()
        java.io.File(dir, "01-requerimientos.md").writeText("lo que se pide")
        java.io.File(dir, "02-mockups.md").writeText("las pantallas")
        java.io.File(dir, "sub").mkdirs()
        java.io.File(dir, "sub/03-plan.md").writeText("el plan")
        java.io.File(dir, "notas.txt").writeText("esto no es markdown")

        val docs = loadSources(listOf(dir.absolutePath))
        assertEquals(3, docs.size, "los tres .md, incluido el de la subcarpeta")
        assertTrue(docs.none { it.name.endsWith(".txt") })
        dir.deleteRecursively()
    }

    @Test
    fun anEmptyOrMissingPathIsNotAnError() {
        assertEquals(emptyList(), loadSources(listOf("/no/existe")))
        assertEquals(emptyList(), loadSources(emptyList()))
    }

    // --- avance y estimación ---

    @Test
    fun theRemainingTimeIsCorrectedWithWhatWasMeasured() {
        // Si las primeras tareas tardaron el doble de lo estimado, lo que falta también va a
        // tardar el doble. Sostener la estimación original sería sostener un número que ya se
        // sabe malo.
        val hechas = (1..2).map {
            tarea(it, "t$it").copy(
                status = TaskStatus.DONE,
                estimateMin = 10,
                startedAt = "2026-08-15T10:00:00Z",
                finishedAt = "2026-08-15T10:20:00Z",
            )
        }
        val faltan = (3..4).map { tarea(it, "t$it").copy(estimateMin = 10) }
        val p = progressOf(hechas + faltan)

        assertEquals(2, p.done)
        assertEquals(2.0, p.drift, "tardaron el doble")
        assertEquals(40.0, p.remainingMin, "y lo que falta se ajusta por eso")
    }

    @Test
    fun withNothingFinishedThereIsNoDriftToClaim() {
        // Antes de terminar una tarea el desvío sería siempre 1,0 y mostraría una precisión
        // inventada.
        val p = progressOf((1..3).map { tarea(it, "t$it") })
        assertNull(p.drift)
        assertEquals(45.0, p.remainingMin, "sin corrección, lo estimado tal cual")
    }

    @Test
    fun anEmptyPlanDoesNotDivideByZero() {
        val p = progressOf(emptyList())
        assertEquals(0, p.total)
        assertEquals(0.0, p.remainingMin)
    }

    // --- persistencia del plan ---

    @Test
    fun thePlanIsSavedWholeOrNotAtAll() = conRepo { ctx, repoId ->
        val id = ctx.impls.create(repoId, "Una feature", listOf("/x/specs"), "sin frameworks nuevos")
        ctx.impls.savePlan(id, "el enfoque", "feature-x", "develop", "fable-5", (1..3).map { tarea(it, "t$it") })

        val impl = assertNotNull(ctx.impls.get(id))
        assertEquals(ImplStatus.PLANNED, impl.status)
        assertEquals("feature-x", impl.branch)
        assertEquals(3, ctx.impls.tasks(id).size)
        assertEquals(listOf(1, 2, 3), ctx.impls.tasks(id).map { it.seq })
    }

    @Test
    fun replanningReplacesTheTasksInsteadOfPilingThemUp() = conRepo { ctx, repoId ->
        val id = ctx.impls.create(repoId, "f", listOf("/x"), null)
        ctx.impls.savePlan(id, "v1", "b", "develop", "m", (1..3).map { tarea(it, "vieja $it") })
        ctx.impls.savePlan(id, "v2", "b", "develop", "m", (1..2).map { tarea(it, "nueva $it") })

        val t = ctx.impls.tasks(id)
        assertEquals(2, t.size)
        assertTrue(t.all { it.title.startsWith("nueva") })
    }

    // --- lo único que frena ---

    @Test
    fun aBlockedTaskWaitsInsteadOfInventingTheDecision() = conRepo { ctx, repoId ->
        // Adivinar una decisión de negocio produce código que compila, pasa los tests y hace lo
        // que no era, y el error se descubre mucho después.
        val id = ctx.impls.create(repoId, "f", listOf("/x"), null)
        ctx.impls.savePlan(id, "s", "b", "develop", "m", listOf(tarea(1, "cobrar")))
        val t = ctx.impls.tasks(id).single()

        ctx.impls.ask(
            id, t.id, QuestionKind.BUSINESS,
            "¿Qué pasa si el pago se rechaza tres veces?",
            "Las specs describen el camino feliz.",
            listOf("Cancelar la orden", "Dejarla pendiente 24h"),
        )
        ctx.impls.blockTask(t.id, "¿Qué pasa si el pago se rechaza tres veces?")

        assertEquals(TaskStatus.BLOCKED, ctx.impls.tasks(id).single().status)
        val q = ctx.impls.questions(id).single()
        assertEquals(QuestionKind.BUSINESS, q.kind)
        assertEquals(2, q.options.size, "las alternativas viajan, para poder contestar eligiendo")
        assertNull(q.answer)
    }

    @Test
    fun answeringPutsTheTaskBackInTheQueue() = conRepo { ctx, repoId ->
        // Una respuesta guardada que no desbloquee dejaría la implementación esperando algo que ya
        // se contestó, y nadie se enteraría.
        val id = ctx.impls.create(repoId, "f", listOf("/x"), null)
        ctx.impls.savePlan(id, "s", "b", "develop", "m", listOf(tarea(1, "cobrar")))
        val t = ctx.impls.tasks(id).single()
        val q = ctx.impls.ask(id, t.id, QuestionKind.BUSINESS, "¿y si falla?", null, emptyList())
        ctx.impls.blockTask(t.id, "¿y si falla?")

        ctx.impls.answer(q, "Cancelar la orden")

        assertEquals(TaskStatus.PENDING, ctx.impls.tasks(id).single().status)
        assertEquals("Cancelar la orden", ctx.impls.questions(id).single().answer)
    }

    @Test
    fun aFailedTaskCanBeRetriedWithoutReplanning() = conRepo { ctx, repoId ->
        val id = ctx.impls.create(repoId, "f", listOf("/x"), null)
        ctx.impls.savePlan(id, "s", "b", "develop", "m", listOf(tarea(1, "t")))
        val t = ctx.impls.tasks(id).single()
        ctx.impls.failTask(t.id, "se cayó")
        assertEquals(TaskStatus.FAILED, ctx.impls.tasks(id).single().status)

        ctx.impls.resetTask(t.id)
        val vuelta = ctx.impls.tasks(id).single()
        assertEquals(TaskStatus.PENDING, vuelta.status)
        assertNull(vuelta.error)
        assertNull(vuelta.startedAt, "y arranca de cero, sin arrastrar la duración del intento fallido")
    }

    @Test
    fun finishedWorkKeepsItsCommit() = conRepo { ctx, repoId ->
        // Cada tarea commitea al terminar: si la séptima falla, las seis anteriores siguen ahí.
        val id = ctx.impls.create(repoId, "f", listOf("/x"), null)
        ctx.impls.savePlan(id, "s", "b", "develop", "m", listOf(tarea(1, "t")))
        val t = ctx.impls.tasks(id).single()
        ctx.impls.startTask(t.id)
        ctx.impls.finishTask(t.id, "abc1234", "hice esto", 0.5)

        val hecha = ctx.impls.tasks(id).single()
        assertEquals(TaskStatus.DONE, hecha.status)
        assertEquals("abc1234", hecha.commitSha)
        assertNotNull(hecha.actualMin, "y queda medido cuánto tardó, para corregir la estimación")
    }
}
