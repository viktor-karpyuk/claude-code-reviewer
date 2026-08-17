package io.acr

import io.acr.forge.Provider
import io.acr.impl.ImplStatus
import io.acr.impl.ImplTask
import io.acr.impl.QuestionKind
import io.acr.impl.TaskSize
import io.acr.impl.TaskStatus
import io.acr.impl.fraction
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

    private fun tarea(
        seq: Int,
        titulo: String,
        size: TaskSize = TaskSize.M,
        dep: List<Int> = emptyList(),
        repoId: String? = null,
    ) = ImplTask("", "", repoId, seq, titulo, "detalle", dep, size, size.minutes, TaskStatus.PENDING,
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
        val id = ctx.impls.create(listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.BACKEND)), "Una feature", listOf("/x/specs"), "sin frameworks nuevos")
        ctx.impls.savePlan(id, "el enfoque", "feature-x", "develop", "fable-5", (1..3).map { tarea(it, "t$it") })

        val impl = assertNotNull(ctx.impls.get(id))
        assertEquals(ImplStatus.PLANNED, impl.status)
        assertEquals("feature-x", impl.branch)
        assertEquals(3, ctx.impls.tasks(id).size)
        assertEquals(listOf(1, 2, 3), ctx.impls.tasks(id).map { it.seq })
    }

    @Test
    fun replanningReplacesTheTasksInsteadOfPilingThemUp() = conRepo { ctx, repoId ->
        val id = ctx.impls.create(listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.BACKEND)), "f", listOf("/x"), null)
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
        val id = ctx.impls.create(listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.BACKEND)), "f", listOf("/x"), null)
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
        val id = ctx.impls.create(listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.BACKEND)), "f", listOf("/x"), null)
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
        val id = ctx.impls.create(listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.BACKEND)), "f", listOf("/x"), null)
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
        val id = ctx.impls.create(listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.BACKEND)), "f", listOf("/x"), null)
        ctx.impls.savePlan(id, "s", "b", "develop", "m", listOf(tarea(1, "t")))
        val t = ctx.impls.tasks(id).single()
        ctx.impls.startTask(t.id)
        ctx.impls.finishTask(t.id, "abc1234", "hice esto", 0.5)

        val hecha = ctx.impls.tasks(id).single()
        assertEquals(TaskStatus.DONE, hecha.status)
        assertEquals("abc1234", hecha.commitSha)
        assertNotNull(hecha.actualMin, "y queda medido cuánto tardó, para corregir la estimación")
    }

    // --- varias repos en una implementación ---

    @Test
    fun anImplementationCanSpanSeveralRepositories() = conRepo { ctx, repoId ->
        // El caso que lo motiva: el contrato del backend tiene que existir antes de que el
        // frontend lo consuma, y eso sólo se puede ordenar con un plan que abarque los dos.
        val otro = ctx.repos.create(
            "fe-${System.nanoTime()}", Provider.BITBUCKET, "acme", "fe",
            System.getProperty("java.io.tmpdir"), null, null, null, "", false,
            io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
        )
        val id = ctx.impls.create(
            listOf(
                io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.BACKEND),
                io.acr.impl.ImplRepo(otro, io.acr.impl.RepoRole.FRONTEND),
            ),
            "feature cruzada", listOf("/x"), null,
        )

        val suyos = ctx.impls.reposOf(id)
        assertEquals(2, suyos.size)
        assertEquals(io.acr.impl.RepoRole.BACKEND, suyos.first { it.repoId == repoId }.role)
        assertEquals(io.acr.impl.RepoRole.FRONTEND, suyos.first { it.repoId == otro }.role)
    }

    @Test
    fun eachTaskRemembersWhichRepositoryItRunsIn() = conRepo { ctx, repoId ->
        // Sin esto, una tarea de frontend escribiría en el backend y el commit iría al lugar
        // equivocado.
        val otro = ctx.repos.create(
            "fe2-${System.nanoTime()}", Provider.BITBUCKET, "acme", "fe",
            System.getProperty("java.io.tmpdir"), null, null, null, "", false,
            io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
        )
        val id = ctx.impls.create(
            listOf(
                io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.BACKEND),
                io.acr.impl.ImplRepo(otro, io.acr.impl.RepoRole.FRONTEND),
            ),
            "f", listOf("/x"), null,
        )
        ctx.impls.savePlan(
            id, "primero el endpoint, después la pantalla", "feature-x", "develop", "m",
            listOf(
                tarea(1, "endpoint", repoId = repoId),
                tarea(2, "pantalla", dep = listOf(1), repoId = otro),
            ),
        )

        val t = ctx.impls.tasks(id)
        assertEquals(repoId, t.first { it.seq == 1 }.repoId)
        assertEquals(otro, t.first { it.seq == 2 }.repoId)
        assertEquals(listOf(1), t.first { it.seq == 2 }.dependsOn, "la pantalla espera al endpoint")
    }

    @Test
    fun anImplementationWithOneRepositoryKeepsWorkingTheSame() = conRepo { ctx, repoId ->
        val id = ctx.impls.create(
            listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.BACKEND)), "f", listOf("/x"), null,
        )
        assertEquals(1, ctx.impls.reposOf(id).size)
        assertEquals(repoId, assertNotNull(ctx.impls.get(id)).repoId, "el principal sigue siendo el mismo")
    }
}

/**
 * El nombre del modelo.
 *
 * La primera implementación real murió al arrancar con `unrecognized_model: fable-5`. El CLI
 * acepta el alias corto de cada familia —`haiku`, `sonnet`, `opus`, `fable`— o el id completo, y
 * `fable-5` no es ninguno de los dos. El error sólo aparece al correr, porque el modelo se valida
 * del lado del servidor: por eso conviene fijarlo acá.
 */
class ImplModelTest {

    @Test
    fun theDefaultModelsUseTheAliasesTheCliKnows() {
        val validos = setOf("haiku", "sonnet", "opus", "fable")
        assertTrue(
            io.acr.impl.ImplEngine.PLAN_MODEL in validos,
            "el modelo de planificación tiene que ser un alias conocido, no ${io.acr.impl.ImplEngine.PLAN_MODEL}",
        )
        assertTrue(
            io.acr.impl.ImplEngine.CODE_MODEL in validos,
            "el modelo de código tiene que ser un alias conocido, no ${io.acr.impl.ImplEngine.CODE_MODEL}",
        )
    }

    @Test
    fun planningAndCodingUseDifferentModels() {
        // Es la razón de ser de tener dos: el orden de las tareas se razona, el código se escribe.
        assertTrue(io.acr.impl.ImplEngine.PLAN_MODEL != io.acr.impl.ImplEngine.CODE_MODEL)
    }

    @Test
    fun theAliasHasNoVersionSuffix() {
        // Un alias con número —"opus-5"— apunta a nada y además envejece: el alias corto sigue a
        // la última versión de esa familia sola.
        listOf(io.acr.impl.ImplEngine.PLAN_MODEL, io.acr.impl.ImplEngine.CODE_MODEL).forEach {
            assertTrue(!it.contains("-"), "«$it» parece un id inventado")
            assertTrue(!it.any { c -> c.isDigit() }, "«$it» lleva versión pegada")
        }
    }
}

/**
 * Que el progreso se mueva mientras algo corre.
 *
 * Contar sólo las tareas terminadas deja la barra quieta durante toda una tarea —que puede ser
 * media hora— y después salta. Durante ese rato la pantalla parece colgada.
 */
class ImplProgressLiveTest {

    private val ahora = java.time.Instant.parse("2026-08-16T12:00:00Z")

    private fun tarea(
        seq: Int,
        estado: TaskStatus,
        estimado: Int,
        arrancoHaceMin: Long? = null,
    ) = ImplTask(
        "t$seq", "i", null, seq, "t$seq", "", emptyList(), null, estimado, estado,
        null, null, null, null,
        arrancoHaceMin?.let { ahora.minusSeconds(it * 60).toString() },
        if (estado == TaskStatus.DONE) ahora.toString() else null,
    )

    @Test
    fun theBarMovesWhileATaskIsRunning() {
        // Dos hechas de cuatro y la tercera a mitad de su estimación: la barra tiene que estar
        // entre 50% y 75%, no clavada en 50%.
        val tareas = listOf(
            tarea(1, TaskStatus.DONE, 10, 10),
            tarea(2, TaskStatus.DONE, 10, 10),
            tarea(3, TaskStatus.RUNNING, 10, arrancoHaceMin = 5),
            tarea(4, TaskStatus.PENDING, 10),
        )
        val f = io.acr.impl.progressOf(tareas).fraction(tareas, ahora)
        assertTrue(f > 0.5f, "se movió respecto de las dos terminadas: $f")
        assertTrue(f < 0.75f, "pero no completó una tarea que sigue corriendo: $f")
    }

    @Test
    fun aTaskThatOverrunsDoesNotFillItsShare() {
        // Sin tope, una tarea que se pasa de su estimación empujaría la barra hasta dar por
        // completo algo que todavía no terminó: la forma más fácil de que una barra mienta.
        val tareas = listOf(
            tarea(1, TaskStatus.RUNNING, 10, arrancoHaceMin = 100),
            tarea(2, TaskStatus.PENDING, 10),
        )
        val f = io.acr.impl.progressOf(tareas).fraction(tareas, ahora)
        assertTrue(f <= 0.45f, "una sola tarea corriendo no puede pasar del 45% de dos: $f")
    }

    @Test
    fun aTaskWithoutAnEstimateDoesNotFakeProgress() {
        // Sin estimación no hay contra qué medir; inventar avance sería peor que no mostrarlo.
        val tareas = listOf(
            ImplTask("t1", "i", null, 1, "t", "", emptyList(), null, null, TaskStatus.RUNNING,
                null, null, null, null, ahora.minusSeconds(600).toString(), null),
        )
        assertEquals(0f, io.acr.impl.progressOf(tareas).fraction(tareas, ahora))
    }

    @Test
    fun elapsedTimeIncludesTheTaskInFlight() {
        // El "van N min" tiene que moverse mientras corre, que es justo cuando alguien lo mira.
        val tareas = listOf(tarea(1, TaskStatus.RUNNING, 10, arrancoHaceMin = 7))
        assertTrue(io.acr.impl.progressOf(tareas).elapsedMin > 0.0)
    }

    @Test
    fun aFinishedPlanIsExactlyComplete() {
        val tareas = listOf(tarea(1, TaskStatus.DONE, 10, 10), tarea(2, TaskStatus.DONE, 10, 10))
        assertEquals(1f, io.acr.impl.progressOf(tareas).fraction(tareas, ahora))
    }

    @Test
    fun anEmptyPlanIsNotDividedByZero() {
        assertEquals(0f, io.acr.impl.progressOf(emptyList()).fraction(emptyList(), ahora))
    }
}

/**
 * Qué tocó cada tarea, y poder ajustar la implementación sin perder lo hecho.
 */
class ImplDetailTest {

    private fun conRepo(block: (AppContext, String) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("acr-impl-d")
        val ctx = AppContext.bootstrap(dir)
        try {
            val repoId = ctx.repos.create(
                "tmp-d-${System.nanoTime()}", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            block(ctx, repoId)
        } finally {
            ctx.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun t(seq: Int) = ImplTask(
        "", "", null, seq, "t$seq", "", emptyList(), TaskSize.M, 15, TaskStatus.PENDING,
        null, null, null, null, null, null,
    )

    @Test
    fun whatATaskTouchedSurvivesTheBranch() = conRepo { ctx, repoId ->
        // Se guarda al commitear y no se le pregunta a git al mirar: así sigue estando aunque la
        // rama se borre, y no cuesta una llamada por fila cada vez que se abre la pantalla.
        val id = ctx.impls.create(
            listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.BACKEND)), "f", listOf("/x"), null,
        )
        ctx.impls.savePlan(id, "s", "b", "develop", "m", listOf(t(1)))
        val tarea = ctx.impls.tasks(id).single()
        ctx.impls.startTask(tarea.id)
        ctx.impls.finishTask(
            tarea.id, "abc1234", "hecho", 0.4,
            diff = io.acr.impl.TaskDiff(
                filesAdded = 2, filesModified = 1, filesDeleted = 0,
                linesAdded = 120, linesDeleted = 8,
                files = listOf(
                    io.acr.impl.FileChange('A', "src/Nuevo.kt", 100, 0),
                    io.acr.impl.FileChange('A', "src/Otro.kt", 15, 0),
                    io.acr.impl.FileChange('M', "src/Viejo.kt", 5, 8),
                ),
            ),
        )

        val d = assertNotNull(ctx.impls.tasks(id).single().diff)
        assertEquals(2, d.filesAdded)
        assertEquals(1, d.filesModified)
        assertEquals(3, d.filesTouched)
        assertEquals(128, d.linesTouched)
        assertEquals(3, d.files.size, "y los archivos uno por uno, que es lo que sirve para revisar")
        assertEquals("src/Nuevo.kt", d.files.first().path)
    }

    @Test
    fun theEffortOnlyCountsWhatActuallyLanded() = conRepo { ctx, repoId ->
        // Una tarea fallida no dejó código: sumar su esfuerzo diría que se produjo algo que no está.
        val id = ctx.impls.create(
            listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.BACKEND)), "f", listOf("/x"), null,
        )
        ctx.impls.savePlan(id, "s", "b", "develop", "m", listOf(t(1), t(2)))
        val ts = ctx.impls.tasks(id)
        ctx.impls.startTask(ts[0].id)
        ctx.impls.finishTask(
            ts[0].id, "sha", "ok", 1.0,
            diff = io.acr.impl.TaskDiff(1, 0, 0, 50, 0, listOf(io.acr.impl.FileChange('A', "a.kt", 50, 0))),
        )
        ctx.impls.failTask(ts[1].id, "se cayó", 0.9)

        val e = io.acr.impl.effortOf(ctx.impls.tasks(id))
        assertEquals(1, e.filesAdded)
        assertEquals(50, e.linesAdded)
        assertEquals(1.0, e.costUsd, "el consumo de la fallida no cuenta como esfuerzo entregado")
    }

    @Test
    fun addingARepositoryLaterDoesNotThrowAwayTheWork() = conRepo { ctx, repoId ->
        // Abrir un repositorio nuevo a mitad de camino es normal y no puede obligar a empezar de
        // cero: lo ya construido tiene su código commiteado.
        val otro = ctx.repos.create(
            "fe3-${System.nanoTime()}", Provider.BITBUCKET, "acme", "fe",
            System.getProperty("java.io.tmpdir"), null, null, null, "", false,
            io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
        )
        val id = ctx.impls.create(
            listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.BACKEND)), "f", listOf("/x"), null,
        )
        ctx.impls.savePlan(id, "s", "b", "develop", "m", listOf(t(1), t(2)))
        val ts = ctx.impls.tasks(id)
        ctx.impls.startTask(ts[0].id)
        ctx.impls.finishTask(ts[0].id, "sha1", "ok", 0.5)

        ctx.impls.update(
            id, "f con frontend", listOf("/x", "/y"), "sin librerías nuevas",
            listOf(
                io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.BACKEND),
                io.acr.impl.ImplRepo(otro, io.acr.impl.RepoRole.FRONTEND),
            ),
        )

        val impl = assertNotNull(ctx.impls.get(id))
        assertEquals("f con frontend", impl.title)
        assertEquals(listOf("/x", "/y"), impl.sources)
        assertEquals(2, ctx.impls.reposOf(id).size)
        assertEquals(2, ctx.impls.tasks(id).size, "las tareas siguen ahí")
        assertEquals(TaskStatus.DONE, ctx.impls.tasks(id).first { it.seq == 1 }.status)
    }

    @Test
    fun replanningKeepsWhatWasBuiltAndDropsWhatWasNot() = conRepo { ctx, repoId ->
        val id = ctx.impls.create(
            listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.BACKEND)), "f", listOf("/x"), null,
        )
        ctx.impls.savePlan(id, "s", "b", "develop", "m", listOf(t(1), t(2), t(3)))
        val ts = ctx.impls.tasks(id)
        ctx.impls.startTask(ts[0].id)
        ctx.impls.finishTask(ts[0].id, "sha1", "ok", 0.5)
        ctx.impls.failTask(ts[1].id, "se cayó")

        ctx.impls.clearPendingPlan(id)

        val quedan = ctx.impls.tasks(id)
        assertEquals(1, quedan.size, "sólo la terminada: su código está commiteado")
        assertEquals(TaskStatus.DONE, quedan.single().status)
        assertEquals(io.acr.impl.ImplStatus.DRAFT, assertNotNull(ctx.impls.get(id)).status)
    }
}

/**
 * El detalle de cada tarea del plan.
 *
 * El planificador escribe qué hay que hacer en cada tarea y eso se guardaba desde el principio sin
 * mostrarse en ninguna pantalla: era lo único que se podía leer de un plan que todavía no corrió,
 * que es justo cuando uno quiere ver qué se propone antes de dejarlo andar.
 */
class ImplTaskDetailTest {

    private fun conRepo(block: (AppContext, String) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("acr-impl-td")
        val ctx = AppContext.bootstrap(dir)
        try {
            val repoId = ctx.repos.create(
                "tmp-td-${System.nanoTime()}", Provider.BITBUCKET, "acme", "demo",
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
    fun theDetailOfEachTaskSurvivesTheRoundTrip() = conRepo { ctx, repoId ->
        val id = ctx.impls.create(
            listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.BACKEND)), "f", listOf("/x"), null,
        )
        ctx.impls.savePlan(
            id, "s", "b", "develop", "m",
            listOf(
                ImplTask("", "", null, 1, "Crear el endpoint",
                    "POST /ausencias con validación de fechas solapadas.",
                    emptyList(), TaskSize.M, 15, TaskStatus.PENDING,
                    null, null, null, null, null, null),
                ImplTask("", "", null, 2, "Pantalla de alta", "Formulario con el calendario.",
                    listOf(1), TaskSize.L, 35, TaskStatus.PENDING,
                    null, null, null, null, null, null),
            ),
        )

        val ts = ctx.impls.tasks(id)
        assertEquals(
            "POST /ausencias con validación de fechas solapadas.",
            ts.first { it.seq == 1 }.detail,
            "el detalle del plan tiene que llegar a la pantalla",
        )
        assertEquals(listOf(1), ts.first { it.seq == 2 }.dependsOn, "y de qué depende, que explica el orden")
    }

    @Test
    fun aTaskWithoutDetailIsStillReadable() = conRepo { ctx, repoId ->
        // Si el planificador no escribió detalle, la fila igual se abre y muestra lo demás.
        val id = ctx.impls.create(
            listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.BACKEND)), "f", listOf("/x"), null,
        )
        ctx.impls.savePlan(
            id, "s", "b", "develop", "m",
            listOf(ImplTask("", "", null, 1, "Algo", "", emptyList(), null, null, TaskStatus.PENDING,
                null, null, null, null, null, null)),
        )
        assertEquals("", ctx.impls.tasks(id).single().detail)
    }
}

/**
 * Que el estado de cada tarea quede persistido en cada cambio.
 *
 * La pantalla lee de la base, así que lo que no se guarde no existe: una tarea que arranca, se
 * completa o se bloquea sin escribir su fila deja la pantalla mostrando la foto anterior, y la
 * implementación corriendo sin que nadie pueda saber en qué anda.
 */
class ImplPersistenceTest {

    private fun conRepo(block: (AppContext, String) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("acr-impl-p")
        val ctx = AppContext.bootstrap(dir)
        try {
            val repoId = ctx.repos.create(
                "tmp-p-${System.nanoTime()}", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            block(ctx, repoId)
        } finally {
            ctx.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun tarea(seq: Int) = ImplTask(
        "", "", null, seq, "t$seq", "detalle $seq", emptyList(), TaskSize.M, 15,
        TaskStatus.PENDING, null, null, null, null, null, null,
    )

    @Test
    fun everyTransitionIsWrittenDown() = conRepo { ctx, repoId ->
        val id = ctx.impls.create(
            listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.BACKEND)), "f", listOf("/x"), null,
        )
        ctx.impls.savePlan(id, "s", "b", "develop", "m", listOf(tarea(1)))
        val t = ctx.impls.tasks(id).single()

        // Cada paso se relee de la base, no de memoria: es lo que ve la pantalla.
        assertEquals(TaskStatus.PENDING, ctx.impls.tasks(id).single().status)

        ctx.impls.startTask(t.id)
        val corriendo = ctx.impls.tasks(id).single()
        assertEquals(TaskStatus.RUNNING, corriendo.status)
        assertNotNull(corriendo.startedAt, "sin arranque guardado no se puede medir cuánto lleva")

        ctx.impls.finishTask(t.id, "sha1", "listo", 0.7)
        val hecha = ctx.impls.tasks(id).single()
        assertEquals(TaskStatus.DONE, hecha.status)
        assertEquals("sha1", hecha.commitSha)
        assertEquals("listo", hecha.result)
        assertEquals(0.7, hecha.costUsd)
        assertNotNull(hecha.finishedAt)
    }

    @Test
    fun theTaskNumberIsItsOwnField() = conRepo { ctx, repoId ->
        // Se referencia por número —"la 3 depende de la 1"— así que tiene que ser un dato y no
        // parte del título.
        val id = ctx.impls.create(
            listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.BACKEND)), "f", listOf("/x"), null,
        )
        ctx.impls.savePlan(id, "s", "b", "develop", "m", listOf(tarea(1), tarea(2), tarea(3)))
        val ts = ctx.impls.tasks(id)
        assertEquals(listOf(1, 2, 3), ts.map { it.seq })
        assertTrue(ts.none { it.title.startsWith("1.") }, "el número no está pegado al título")
    }

    @Test
    fun blockingAndAnsweringLeaveTheirTrace() = conRepo { ctx, repoId ->
        val id = ctx.impls.create(
            listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.BACKEND)), "f", listOf("/x"), null,
        )
        ctx.impls.savePlan(id, "s", "b", "develop", "m", listOf(tarea(1)))
        val t = ctx.impls.tasks(id).single()

        ctx.impls.startTask(t.id)
        ctx.impls.blockTask(t.id, "¿qué pasa si falla?")
        assertEquals(TaskStatus.BLOCKED, ctx.impls.tasks(id).single().status)

        val q = ctx.impls.ask(id, t.id, io.acr.impl.QuestionKind.BUSINESS, "¿qué pasa si falla?", null, emptyList())
        ctx.impls.answer(q, "cancelar")
        assertEquals(TaskStatus.PENDING, ctx.impls.tasks(id).single().status, "contestar la devuelve a la cola")
    }

    @Test
    fun aRetriedTaskForgetsTheFailedAttempt() = conRepo { ctx, repoId ->
        // Si arrastrara la duración del intento fallido, el tiempo real de la tarea mentiría.
        val id = ctx.impls.create(
            listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.BACKEND)), "f", listOf("/x"), null,
        )
        ctx.impls.savePlan(id, "s", "b", "develop", "m", listOf(tarea(1)))
        val t = ctx.impls.tasks(id).single()
        ctx.impls.startTask(t.id)
        ctx.impls.failTask(t.id, "se cayó")
        ctx.impls.resetTask(t.id)

        val vuelta = ctx.impls.tasks(id).single()
        assertNull(vuelta.startedAt)
        assertNull(vuelta.finishedAt)
        assertNull(vuelta.actualMin)
    }
}

/**
 * Los pasos, el prompt y las fechas de una tarea.
 *
 * Todo esto existe para contestar preguntas después de que algo pasó —qué quedó sin hacer, qué se
 * le pidió, cuándo—, así que lo único que importa es que sobreviva a cerrar la pantalla. Un dato
 * que sólo vive en memoria no contesta nada: para cuando alguien pregunta, ya no está.
 */
class ImplStepsTest {

    private fun conRepo(block: (AppContext, String) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("acr-steps")
        val ctx = AppContext.bootstrap(dir)
        try {
            val repoId = ctx.repos.create(
                "tmp-s-${System.nanoTime()}", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            block(ctx, repoId)
        } finally {
            ctx.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun paso(seq: Int, titulo: String) = io.acr.impl.ImplStep(
        id = "", taskId = "", seq = seq, title = titulo, status = TaskStatus.PENDING,
        note = null, createdAt = "", updatedAt = null, startedAt = null, finishedAt = null,
    )

    private fun tareaCon(seq: Int, pasos: List<io.acr.impl.ImplStep>) = ImplTask(
        "", "", null, seq, "tarea $seq", "detalle", emptyList(), TaskSize.M, 20,
        TaskStatus.PENDING, null, null, null, null, null, null, steps = pasos,
    )

    @Test
    fun theStepsOfATaskSurviveBeingSaved() = conRepo { ctx, repoId ->
        val id = ctx.impls.create(listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.OTHER, null)), "con pasos", listOf("/tmp/x.md"), null)
        ctx.impls.savePlan(
            id, "resumen", "rama", "develop", "fable",
            listOf(tareaCon(1, listOf(paso(1, "tocar el repositorio"), paso(2, "correr los tests")))),
        )

        val t = ctx.impls.tasks(id).single()
        assertEquals(2, t.steps.size, "los pasos se guardan con la tarea")
        assertEquals(listOf(1, 2), t.steps.map { it.seq }, "y en orden")
        assertEquals("tocar el repositorio", t.steps.first().title)
        assertTrue(t.steps.all { it.status == TaskStatus.PENDING })
    }

    @Test
    fun aStepNobodyReportedIsNotTakenAsDone() = conRepo { ctx, repoId ->
        // El punto entero de una lista de pasos es saber qué quedó sin hacer. Dar por bueno lo que
        // nadie confirmó la convierte en decoración.
        val id = ctx.impls.create(listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.OTHER, null)), "parcial", listOf("/tmp/x.md"), null)
        ctx.impls.savePlan(
            id, "r", "rama", "develop", "fable",
            listOf(tareaCon(1, listOf(paso(1, "uno"), paso(2, "dos")))),
        )
        val t = ctx.impls.tasks(id).single()

        ctx.impls.finishStep(t.steps[0].id, done = true, note = "listo")
        ctx.impls.finishStep(t.steps[1].id, done = false, note = "no hacía falta")

        val leidos = ctx.impls.tasks(id).single().steps
        assertEquals(TaskStatus.DONE, leidos[0].status)
        assertEquals("listo", leidos[0].note)
        assertEquals(TaskStatus.FAILED, leidos[1].status, "sin hacer no es pendiente: la tarea ya terminó")
        assertEquals("no hacía falta", leidos[1].note)
        assertTrue(leidos.all { it.finishedAt != null }, "se cierran los dos, hechos o no")
    }

    @Test
    fun retryingATaskAlsoClearsItsSteps() = conRepo { ctx, repoId ->
        // Si los pasos quedaran tildados de la corrida anterior, el segundo intento arrancaría
        // mostrando trabajo que todavía no se hizo de nuevo.
        val id = ctx.impls.create(listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.OTHER, null)), "reintento", listOf("/tmp/x.md"), null)
        ctx.impls.savePlan(id, "r", "rama", "develop", "fable", listOf(tareaCon(1, listOf(paso(1, "uno")))))
        val t = ctx.impls.tasks(id).single()
        ctx.impls.finishStep(t.steps[0].id, done = true, note = "hecho")

        ctx.impls.resetTask(t.id)

        val paso = ctx.impls.tasks(id).single().steps.single()
        assertEquals(TaskStatus.PENDING, paso.status)
        assertEquals(null, paso.note, "la nota del intento anterior no se arrastra")
        assertEquals(null, paso.finishedAt)
    }

    @Test
    fun thePromptIsSavedBeforeTheTaskRuns() = conRepo { ctx, repoId ->
        // Se guarda al arrancar y no al terminar: si la tarea revienta a mitad de camino, el
        // prompt es justo lo que hace falta para entender por qué, y guardarlo al final
        // significaría no tenerlo nunca en el único caso donde importa.
        val id = ctx.impls.create(listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.OTHER, null)), "prompt", listOf("/tmp/x.md"), null)
        ctx.impls.savePlan(id, "r", "rama", "develop", "fable", listOf(tareaCon(1, emptyList())))
        val t = ctx.impls.tasks(id).single()

        ctx.impls.savePrompt(t.id, "hacé esto y aquello")
        ctx.impls.failTask(t.id, "explotó")

        val leida = ctx.impls.tasks(id).single()
        assertEquals("hacé esto y aquello", leida.prompt, "el prompt sobrevive a la tarea fallida")
    }

    @Test
    fun theFourDatesAnswerFourDifferentQuestions() = conRepo { ctx, repoId ->
        val id = ctx.impls.create(listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.OTHER, null)), "fechas", listOf("/tmp/x.md"), null)
        ctx.impls.savePlan(id, "r", "rama", "develop", "fable", listOf(tareaCon(1, emptyList())))

        val creada = ctx.impls.tasks(id).single()
        assertTrue(creada.createdAt != null, "una tarea planificada ya tiene fecha de creación")
        assertEquals(null, creada.startedAt, "pero todavía no arrancó")

        ctx.impls.startTask(creada.id)
        Thread.sleep(5)
        ctx.impls.finishTask(creada.id, "abc123", "hecho", 0.1)

        val fin = ctx.impls.tasks(id).single()
        assertTrue(fin.startedAt != null && fin.finishedAt != null)
        assertTrue(
            fin.updatedAt != null && fin.updatedAt!! >= fin.createdAt!!,
            "modificada avanza con la tarea; creada se queda donde estaba",
        )
    }

    @Test
    fun theReviewGuidanceIsRememberedForNextTime() = conRepo { ctx, repoId ->
        // Casi siempre se revisa dos veces seguidas por lo mismo. Volver a escribirlo entero
        // invita a escribir menos.
        val id = ctx.impls.create(listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.OTHER, null)), "revisión", listOf("/tmp/x.md"), null)
        assertEquals(null, ctx.impls.get(id)!!.reviewGuidance)

        ctx.impls.saveReviewGuidance(id, "separá la tarea 4")
        assertEquals("separá la tarea 4", ctx.impls.get(id)!!.reviewGuidance)
    }

    @Test
    fun theReviewPromptSaysWhatItLooksAt() {
        // Está a la vista en la pantalla para que nadie escriba una guía que repita lo que ya
        // está: pedir "tareas más chicas" cuando el prompt ya lo dice gasta una corrida en nada.
        val reglas = io.acr.impl.ImplPrompt.REVIEW_RULES
        assertTrue(reglas.contains("orden", ignoreCase = true))
        assertTrue(reglas.contains("tamaño", ignoreCase = true))
        assertTrue(reglas.contains("paso", ignoreCase = true))
        assertTrue(
            reglas.contains("Mantené lo que está bien"),
            "revisar no es rehacer: si se pierde eso, no hay forma de saber si la revisión mejoró algo",
        )
    }
}

/**
 * La traducción del SQL, sin necesidad de un servidor.
 *
 * Los tests contra motores reales ([io.acr.DbEngineIT]) son los que valen, pero sólo corren donde
 * hay un PostgreSQL o un MySQL escuchando. Estos fijan las reglas en cualquier máquina: son las
 * decisiones del traductor, y si alguna cambia sin querer, esto lo dice antes que un usuario.
 */
class DialectTest {

    private val pkDe = mapOf(
        "impl_repo" to listOf("impl_id", "repo_id"),
        "pref" to listOf("k"),
    )

    private fun pg(sql: String) =
        io.acr.data.Dialect.translate(sql, io.acr.data.DbEngine.POSTGRES, emptySet()) { pkDe[it].orEmpty() }

    private fun my(sql: String, indexadas: Set<String> = emptySet()) =
        io.acr.data.Dialect.translate(sql, io.acr.data.DbEngine.MYSQL, indexadas) { pkDe[it].orEmpty() }

    @Test
    fun sqliteIsLeftExactlyAsItWas() {
        // El motor de fábrica no paga nada por esto. Un traductor que "mejora" el SQL del caso
        // normal es un riesgo puro: no arregla nada y puede romper todo.
        val sql = "INSERT OR REPLACE INTO pref(k, v) VALUES (?,?)"
        assertEquals(sql, io.acr.data.Dialect.translate(sql, io.acr.data.DbEngine.SQLITE, emptySet()) { emptyList() })
    }

    @Test
    fun theUpsertDeclaresWhatItCollidesWith() {
        // PostgreSQL exige saber sobre qué columna choca; SQLite no lo dice. Se resuelve contra la
        // clave real de la tabla en vez de adivinarla.
        val r = pg("INSERT OR REPLACE INTO impl_repo(impl_id, repo_id, role, base_branch) VALUES (?,?,?,?)")
        assertTrue(r.contains("ON CONFLICT (impl_id, repo_id)"), r)
        assertTrue(r.contains("role = EXCLUDED.role"), r)
        assertTrue(!r.contains("impl_id = EXCLUDED.impl_id"), "la clave no se actualiza a sí misma: $r")
    }

    @Test
    fun anUpsertWithoutAKnownKeyDegradesToAnInsert() {
        // Inventar una clave sería peor: fallar con "duplicate key" es un error que se entiende;
        // escribir sobre la fila equivocada, no.
        val r = pg("INSERT OR REPLACE INTO tabla_rara(a, b) VALUES (?,?)")
        assertTrue(r.startsWith("INSERT INTO tabla_rara"), r)
        assertTrue(!r.contains("ON CONFLICT"), r)
    }

    @Test
    fun ignoringADuplicateMeansDoingNothing() {
        assertTrue(pg("INSERT OR IGNORE INTO pref(k, v) VALUES (?,?)").endsWith("ON CONFLICT DO NOTHING"))
        assertTrue(my("INSERT OR IGNORE INTO pref(k, v) VALUES (?,?)").startsWith("INSERT IGNORE INTO"))
    }

    @Test
    fun datesEndUpComparableOnBothSides() {
        // El error que esto evita es "operator does not exist: date = text": la mitad de la
        // comparación quedaba como fecha y la otra como texto, y PostgreSQL las rechaza.
        val r = pg("SELECT COUNT(*) FROM review WHERE date(created_at)=date('now')")
        assertTrue(r.contains("to_char"), r)
        assertTrue(!r.contains("date(created_at)"), r)
        assertTrue(r.contains("to_char(now(), 'YYYY-MM-DD')"), r)
    }

    @Test
    fun groupingByPeriodKeepsMeaningTheSameThing() {
        assertTrue(pg("SELECT strftime('%Y-%m', created_at) FROM review").contains("'YYYY-MM'"))
        // En MySQL %M es el nombre del mes, no los minutos: traducirlo mal daría "August" donde
        // debería ir "08" y el agrupamiento sería otro sin fallar.
        assertTrue(my("SELECT strftime('%Y-%m', created_at) FROM review").contains("date_format"))
    }

    @Test
    fun subtractingTwoInstantsStillGivesDays() {
        val r = pg("SELECT julianday(closed_on) - julianday(created_on) FROM pr_stat")
        assertTrue(r.contains("EXTRACT(EPOCH"), r)
        assertTrue(r.contains("86400"), "en días, no en segundos: $r")
    }

    @Test
    fun concatenationDoesNotSilentlyBecomeALogicalOr() {
        // `||` en MySQL es un OR y devolvería 0 sin error, que es el peor resultado posible: sin
        // falla visible y con los datos mal.
        val r = my("SELECT repo_id||'#'||pr_id FROM review")
        assertTrue(r.contains("concat(repo_id, '#', pr_id)"), r)
    }

    @Test
    fun indexedTextGetsALengthAndTheRestStaysLong() {
        // MySQL no puede indexar un TEXT sin decirle cuántos caracteres; y una columna que guarda
        // el cuerpo de una review no puede quedar en 255.
        val ddl = "CREATE TABLE x (\n  id TEXT PRIMARY KEY,\n  body TEXT\n)"
        val r = my(ddl, indexadas = setOf("id"))
        assertTrue(r.contains("id VARCHAR(255)"), r)
        assertTrue(r.contains("body LONGTEXT"), r)
    }

    @Test
    fun bytesAreBytesInEachEngine() {
        assertTrue(pg("CREATE TABLE x (token_cipher BLOB)").contains("BYTEA"))
        assertTrue(my("CREATE TABLE x (token_cipher BLOB)").contains("LONGBLOB"))
    }

    @Test
    fun theReservedWordOfTheSchemaIsQuoted() {
        // `key` es la única columna del esquema que MySQL reserva.
        assertTrue(my("SELECT key FROM jira_issue").contains("`key`"))
    }

    @Test
    fun tablesAreCopiedAfterTheOnesTheyDependOn() {
        // Copiar una fila hija antes que su madre la rechaza la clave foránea. La alternativa
        // —apagar la verificación— pide permisos que en una base ajena no se tienen.
        val orden = io.acr.data.DbPorter().order(io.acr.data.Store.MIGRATIONS)
        assertTrue(orden.contains("repo") && orden.contains("review"))
        assertTrue(
            orden.indexOf("repo") < orden.indexOf("review"),
            "repo va antes que review, que la referencia",
        )
        assertTrue(
            orden.indexOf("impl_task") < orden.indexOf("impl_step"),
            "y una tarea antes que sus pasos",
        )
    }
}

/**
 * El plan como grafo y no como fila.
 *
 * `depends_on` existía sólo para saber a quién arrastraba una falla; ahora decide qué puede
 * arrancar. Lo que se prueba acá es el criterio, que es donde están las decisiones: qué se puede
 * lanzar junto, qué tiene que esperar, y qué pasa con una dependencia que el plan escribió mal.
 */
class ImplSchedulerTest {

    private fun repo(id: String) = io.acr.forge.RepoRecord(
        id = id, name = id, provider = Provider.BITBUCKET, owner = "acme", slug = id,
        localPath = "/tmp/$id", token = null,
    )

    private fun tarea(
        seq: Int,
        repoId: String,
        dep: List<Int> = emptyList(),
        estado: TaskStatus = TaskStatus.PENDING,
    ) = ImplTask(
        "t$seq", "i", repoId, seq, "tarea $seq", "", dep, TaskSize.M, 10, estado,
        null, null, null, null, null, null,
    )

    private val motor = io.acr.impl.ImplEngine(
        io.acr.data.ImplRepository(io.acr.data.Store(java.nio.file.Files.createTempDirectory("acr-sch").resolve("x.db"))),
        io.acr.data.PrefsRepo(io.acr.data.Store(java.nio.file.Files.createTempDirectory("acr-sch2").resolve("y.db"))),
    )

    private fun listas(todas: List<ImplTask>, repos: List<io.acr.forge.RepoRecord>) =
        motor.ready(todas, repos.associateBy { it.id }, repos).map { it.seq }

    @Test
    fun independentTasksInDifferentReposStartTogether() {
        // Es el caso que hace valer todo esto: el backend y el frontend avanzando a la vez en vez
        // de uno esperando al otro sin necesitarlo.
        val be = repo("be")
        val fe = repo("fe")
        val todas = listOf(tarea(1, be.id), tarea(2, fe.id))
        assertEquals(listOf(1, 2), listas(todas, listOf(be, fe)))
    }

    @Test
    fun twoTasksInTheSameRepoNeverRunAtOnce() {
        // Dos modelos escribiendo en el mismo árbol se pisan los archivos, y el commit de una se
        // llevaría puesto lo que la otra dejó a medias.
        val be = repo("be")
        val todas = listOf(tarea(1, be.id), tarea(2, be.id))
        assertEquals(listOf(1), listas(todas, listOf(be)), "sólo la primera")
    }

    @Test
    fun aTaskWaitsForWhatItDependsOn() {
        val be = repo("be")
        val fe = repo("fe")
        // La 2 necesita la 1: aunque esté en otro repositorio, no arranca.
        val todas = listOf(tarea(1, be.id), tarea(2, fe.id, dep = listOf(1)))
        assertEquals(listOf(1), listas(todas, listOf(be, fe)))

        // Y en cuanto la 1 está, la 2 se destraba sola.
        val despues = listOf(tarea(1, be.id, estado = TaskStatus.DONE), tarea(2, fe.id, dep = listOf(1)))
        assertEquals(listOf(2), listas(despues, listOf(be, fe)))
    }

    @Test
    fun oneFinishedTaskCanUnlockAChain() {
        // X habilita Y, que habilita Z. Cada vuelta destraba la siguiente sin que nadie la empuje.
        val a = repo("a")
        val b = repo("b")
        val c = repo("c")
        val plan = listOf(tarea(1, a.id), tarea(2, b.id, dep = listOf(1)), tarea(3, c.id, dep = listOf(2)))
        val repos = listOf(a, b, c)
        assertEquals(listOf(1), listas(plan, repos))

        val conUna = listOf(plan[0].copy(status = TaskStatus.DONE), plan[1], plan[2])
        assertEquals(listOf(2), listas(conUna, repos))

        val conDos = listOf(conUna[0], conUna[1].copy(status = TaskStatus.DONE), plan[2])
        assertEquals(listOf(3), listas(conDos, repos))
    }

    @Test
    fun anImpossibleDependencyDoesNotFreezeEverything() {
        // Una dependencia hacia adelante, o hacia una tarea que no existe, es un error del plan.
        // Honrarla dejaría la tarea esperando para siempre a algo que nunca va a llegar: una
        // implementación trabada, sin nada roto y sin nada que decir.
        val a = repo("a")
        val haciaAdelante = listOf(tarea(1, a.id, dep = listOf(2)), tarea(2, a.id))
        assertEquals(listOf(1), listas(haciaAdelante, listOf(a)))

        val inexistente = listOf(tarea(1, a.id, dep = listOf(99)))
        assertEquals(listOf(1), listas(inexistente, listOf(a)))
    }

    @Test
    fun whatIsAlreadyRunningOrDoneIsNotPickedAgain() {
        val a = repo("a")
        val b = repo("b")
        val todas = listOf(
            tarea(1, a.id, estado = TaskStatus.RUNNING),
            tarea(2, b.id, estado = TaskStatus.DONE),
        )
        assertEquals(emptyList(), listas(todas, listOf(a, b)))
    }

    @Test
    fun aBlockedDependencyHoldsItsDependentsWithoutFailingThem() {
        // Esperar una decisión no es haber fallado. Lo que quedó detrás sigue pendiente, y por eso
        // la implementación termina en "esperando" y no en "falló".
        val a = repo("a")
        val b = repo("b")
        val todas = listOf(
            tarea(1, a.id, estado = TaskStatus.BLOCKED),
            tarea(2, b.id, dep = listOf(1)),
        )
        assertEquals(emptyList(), listas(todas, listOf(a, b)))
    }
}

/**
 * Las pasadas de revisión: la política, lo que se guarda, y la rama elegida a mano.
 *
 * Lo que importa acá es que el rango signifique algo. Un número fijo de pasadas o corre de más
 * —cinco corridas pagas sobre código limpio para que digan "no encontré nada"— o de menos, y en
 * ninguno de los dos casos el número dice nada sobre el código.
 */
class ImplReviewTest {

    private fun conRepo(block: (AppContext, String) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("acr-rev")
        val ctx = AppContext.bootstrap(dir)
        try {
            val repoId = ctx.repos.create(
                "tmp-r-${System.nanoTime()}", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            block(ctx, repoId)
        } finally {
            ctx.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun nueva(ctx: AppContext, repoId: String) = ctx.impls.create(
        listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.OTHER, null)),
        "con revisión", listOf("/tmp/x.md"), null,
    )

    @Test
    fun anImplementationCreatedBeforeThisStillGetsPasses() = conRepo { ctx, repoId ->
        // Los valores por defecto se resuelven al leer y no en el DDL: las implementaciones
        // anteriores a la migración tienen null, y sin esto quedarían en cero pasadas sin que
        // nadie lo haya decidido.
        val impl = ctx.impls.get(nueva(ctx, repoId))!!
        assertEquals(2, impl.reviewMin)
        assertEquals(5, impl.reviewMax)
        assertTrue(!impl.reviewEach)
    }

    @Test
    fun thePolicyIsRemembered() = conRepo { ctx, repoId ->
        val id = nueva(ctx, repoId)
        ctx.impls.setReviewPolicy(id, 3, 4, true)
        val impl = ctx.impls.get(id)!!
        assertEquals(3, impl.reviewMin)
        assertEquals(4, impl.reviewMax)
        assertTrue(impl.reviewEach)
    }

    @Test
    fun eachPassIsSavedWithWhatItFoundAndWhatItFixed() = conRepo { ctx, repoId ->
        // Hallazgos y arreglos son números distintos y confundirlos arruina los dos: una pasada
        // que encuentra ocho y arregla dos no hizo el mismo trabajo que una que encuentra dos.
        val id = nueva(ctx, repoId)
        ctx.impls.saveReview(id, null, 1, findings = 8, fixed = 2, summary = "varias cosas", detail = "…", commitSha = "abc", costUsd = 0.5)
        ctx.impls.saveReview(id, null, 2, findings = 0, fixed = 0, summary = "nada", detail = null, commitSha = null, costUsd = 0.2)

        val pasadas = ctx.impls.reviews(id)
        assertEquals(2, pasadas.size)
        assertEquals(8, pasadas.first().findings)
        assertEquals(2, pasadas.first().fixed)
        assertEquals(0, pasadas.last().findings, "una pasada limpia también se guarda: es la que corta la serie")
    }

    @Test
    fun theBranchNameSurvivesAReplan() = conRepo { ctx, repoId ->
        // Sin la marca no se puede distinguir la rama que eligió una persona de la que propuso el
        // modelo —después de planificar las dos están llenas— y replanificar pisaría la elegida.
        val id = nueva(ctx, repoId)
        assertTrue(!ctx.impls.get(id)!!.branchFixed, "por defecto la elige el modelo")

        ctx.impls.setBranch(id, "feature-mia")
        val fijada = ctx.impls.get(id)!!
        assertEquals("feature-mia", fijada.branch)
        assertTrue(fijada.branchFixed)

        // Y volver a automático la libera.
        ctx.impls.setBranch(id, null)
        assertTrue(!ctx.impls.get(id)!!.branchFixed)
        assertEquals(null, ctx.impls.get(id)!!.branch)
    }

    @Test
    fun theReviewPromptLooksForTheFiveThingsAndAcceptsFindingNothing() {
        val p = io.acr.impl.ImplPrompt.review(
            1, 3, "develop..HEAD", emptyList(), null, emptyList(), "español",
        )
        listOf("Bugs", "Performance", "Diseño", "Arquitectura", "Tests").forEach {
            assertTrue(p.contains(it, ignoreCase = true), "busca $it")
        }
        assertTrue(
            p.contains("Si no encontrás nada, decilo"),
            "poder contestar 'nada' es lo que hace que el rango funcione: sin eso, la pasada " +
                "inventa un hallazgo menor para justificarse y la serie nunca se corta",
        )
        assertTrue(p.contains("Arreglalo"), "una lista que nadie va a leer es trabajo tirado")
    }

    @Test
    fun aLaterPassKnowsWhatTheEarlierOnesFound() {
        // Sin esto, la pasada 3 vuelve a reportar lo que la 1 ya arregló y el número de hallazgos
        // deja de significar algo.
        val p = io.acr.impl.ImplPrompt.review(
            3, 5, "develop..HEAD", emptyList(), null,
            listOf("[HIGH/BUG] null en el parser", "[LOW/DESIGN] nombre confuso"), "español",
        )
        assertTrue(p.contains("null en el parser"))
        assertTrue(p.contains("No lo repitas"))
    }
}

/**
 * Las dos pasadas de análisis que no miran código: los documentos y el plan.
 *
 * Las dos existen por la misma razón: un plan no puede ser mejor que las specs de las que sale, y
 * el que planificó es mal juez de su propio plan. Lo que se prueba acá es el criterio de cada
 * prompt, que es donde están las decisiones.
 */
class ImplAnalysisTest {

    private fun conRepo(block: (AppContext, String) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("acr-an")
        val ctx = AppContext.bootstrap(dir)
        try {
            val repoId = ctx.repos.create(
                "tmp-a-${System.nanoTime()}", Provider.BITBUCKET, "acme", "demo",
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
    fun theSpecsPassNeverRewritesTheOriginals() {
        // Una spec es un acuerdo entre personas, no un borrador de esta app: reescribirla en el
        // lugar borraría lo que alguien redactó y acordó, y dejaría sin forma de saber qué cambió.
        val p = io.acr.impl.ImplPrompt.improveSpecs(
            listOf(io.acr.impl.SourceDoc("req.md", "lo que se pide")),
            listOf(Triple("be", "BACKEND", "/tmp/be")), null, "español",
        )
        assertTrue(p.contains("no escribas ni modifiques archivos", ignoreCase = true), p.take(400))
        assertTrue(p.contains("además"), "el documento nuevo se lee además de los originales, no en su lugar")
    }

    @Test
    fun whatCannotBeDeducedBecomesAQuestionInsteadOfAnInvention() {
        // Es el punto entero de la pasada: si inventa la respuesta de negocio que falta, produce
        // exactamente el problema que venía a evitar, y encima con más autoridad.
        val p = io.acr.impl.ImplPrompt.improveSpecs(
            listOf(io.acr.impl.SourceDoc("req.md", "x")), listOf(Triple("be", "BACKEND", "/tmp")),
            null, "español",
        )
        assertTrue(p.contains("preguntas abiertas"))
        assertTrue(p.contains("si no está y") || p.contains("no se deduce"))
    }

    @Test
    fun theAuditGoesFromTheDocumentsToThePlanAndNotTheOtherWay() {
        // Es la decisión que hace que la auditoría sirva: yendo del plan a los documentos, lo que
        // falta no aparece nunca, porque no hay ninguna tarea que lo mencione.
        val p = io.acr.impl.ImplPrompt.auditPlan(
            listOf(io.acr.impl.SourceDoc("req.md", "x")), "1. tarea",
            listOf(Triple("be", "BACKEND", "/tmp")), "español",
        )
        assertTrue(p.contains("de los documentos al plan"))
        assertTrue(p.contains("requisito por requisito"))
        listOf("MISSING", "EXTRA", "WRONG_ORDER", "CONTRADICTS", "VAGUE").forEach {
            assertTrue(p.contains(it), "clasifica $it")
        }
    }

    @Test
    fun anEmptyAuditIsAValidAnswer() {
        val p = io.acr.impl.ImplPrompt.auditPlan(
            emptyList(), "1. tarea", listOf(Triple("be", "BACKEND", "/tmp")), "español",
        )
        assertTrue(p.contains("Si el plan está bien, decilo"))
    }

    @Test
    fun eachKindOfPassIsStoredAsWhatItIs() = conRepo { ctx, repoId ->
        // Van a la misma tabla porque son lo mismo —una pasada de análisis con su resultado— pero
        // contestan preguntas distintas, y la pantalla tiene que poder decir cuál es cuál.
        val id = ctx.impls.create(
            listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.OTHER, null)),
            "análisis", listOf("/tmp/x.md"), null,
        )
        ctx.impls.saveReview(id, null, 1, 3, 2, "docs", "…", null, 0.1, io.acr.impl.ReviewKind.SPECS)
        ctx.impls.saveReview(id, null, 1, 1, 0, "plan", "…", null, 0.1, io.acr.impl.ReviewKind.PLAN)
        ctx.impls.saveReview(id, null, 1, 0, 0, "código", null, null, 0.1)

        val tipos = ctx.impls.reviews(id).map { it.kind }
        assertTrue(io.acr.impl.ReviewKind.SPECS in tipos)
        assertTrue(io.acr.impl.ReviewKind.PLAN in tipos)
        assertTrue(io.acr.impl.ReviewKind.CODE in tipos, "las viejas, sin tipo guardado, son de código")
    }

    @Test
    fun theNewDocumentJoinsTheOnesThePlannerReads() = conRepo { ctx, repoId ->
        // Si el documento de aclaraciones no entra en las fuentes, el análisis fue decorativo: el
        // plan se sigue armando con las specs incompletas.
        val id = ctx.impls.create(
            listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.OTHER, null)),
            "docs", listOf("/tmp/a.md"), null,
        )
        ctx.impls.addSource(id, "/tmp/00-aclaraciones.md")
        assertEquals(listOf("/tmp/a.md", "/tmp/00-aclaraciones.md"), ctx.impls.get(id)!!.sources)

        // Y no se duplica si se corre dos veces.
        ctx.impls.addSource(id, "/tmp/00-aclaraciones.md")
        assertEquals(2, ctx.impls.get(id)!!.sources.size)
    }
}

/**
 * El Gantt: dónde cae cada tarea en el tiempo.
 *
 * Lo único que importa acá es que el diagrama y el motor cuenten la misma historia. Un Gantt que
 * muestra un orden distinto del que va a pasar no es una previsión, es una ilustración.
 */
class GanttLayoutTest {

    private fun tarea(
        seq: Int,
        repoId: String,
        dep: List<Int> = emptyList(),
        estimado: Int = 10,
        real: String? = null,
    ) = ImplTask(
        "t$seq", "i", repoId, seq, "tarea $seq", "", dep, TaskSize.M, estimado,
        if (real != null) TaskStatus.DONE else TaskStatus.PENDING,
        null, null, null, null,
        real?.let { "2026-01-01T00:00:00Z" }, real,
    )

    private fun carriles(vararg r: String) = r.withIndex().associate { (i, x) -> x as String? to i }

    @Test
    fun independentTasksStartAtTheSameMoment() {
        // Es lo que hace que el diagrama valga: dos barras que arrancan juntas son dos tareas que
        // van a correr juntas.
        val barras = io.acr.ui.impl.layout(
            listOf(tarea(1, "be"), tarea(2, "fe")), carriles("be", "fe"),
        )
        assertEquals(0.0, barras[0].startMin)
        assertEquals(0.0, barras[1].startMin)
    }

    @Test
    fun aDependentTaskStartsWhenItsDependencyEnds() {
        val barras = io.acr.ui.impl.layout(
            listOf(tarea(1, "be", estimado = 30), tarea(2, "fe", dep = listOf(1))),
            carriles("be", "fe"),
        )
        assertEquals(30.0, barras[1].startMin)
    }

    @Test
    fun twoTasksInTheSameRepoAreDrawnOneAfterTheOther() {
        // Aunque no dependan entre sí. El repositorio es el límite real del paralelismo, y
        // dibujarlas superpuestas prometería algo que el motor no va a hacer.
        val barras = io.acr.ui.impl.layout(
            listOf(tarea(1, "be", estimado = 20), tarea(2, "be", estimado = 10)),
            carriles("be"),
        )
        assertEquals(0.0, barras[0].startMin)
        assertEquals(20.0, barras[1].startMin, "la segunda espera a que se libere el repositorio")
    }

    @Test
    fun whatAlreadyRanUsesWhatItActuallyTook() {
        // Para lo hecho, lo que tardó; para lo que falta, la estimación. Mezclarlos es la lectura
        // que sirve: lo que pasó, y desde ahí lo que se espera.
        val hecha = tarea(1, "be", estimado = 10, real = "2026-01-01T00:40:00Z")
        val barras = io.acr.ui.impl.layout(listOf(hecha, tarea(2, "be")), carriles("be"))
        assertEquals(40.0, barras[0].durationMin, "cuarenta minutos reales, no diez estimados")
        assertEquals(40.0, barras[1].startMin)
    }

    @Test
    fun anImpossibleDependencyDoesNotPushATaskIntoTheFuture() {
        // El mismo criterio que usa el motor: una dependencia hacia adelante es un error de plan y
        // se ignora. Si el diagrama la respetara, mostraría una tarea esperando algo que el motor
        // no va a esperar.
        val barras = io.acr.ui.impl.layout(
            listOf(tarea(1, "be", dep = listOf(2)), tarea(2, "be")),
            carriles("be"),
        )
        assertEquals(0.0, barras[0].startMin)
    }
}

/**
 * Desarmar las enumeraciones de una descripción.
 *
 * Los modelos las escriben en línea y como párrafo corrido se leen como una sola oración larga
 * donde los números son ruido. Lo delicado no es partir: es no partir donde no hay lista.
 */
class TextLayoutTest {

    private fun bloques(s: String) = io.acr.impl.splitBlocks(s)

    @Test
    fun anInlineEnumerationBecomesOneItemPerLine() {
        val b = bloques("Hay que 1) migrar la tabla, 2) exponer el endpoint, 3) cablear la pantalla")
        assertEquals(4, b.size, "la frase que introduce más los tres items")
        assertEquals("Hay que", b[0].text)
        assertEquals(null, b[0].marker)
        assertEquals("1)", b[1].marker)
        assertEquals("migrar la tabla", b[1].text)
        assertEquals("cablear la pantalla", b[3].text)
    }

    @Test
    fun theMarkerIsKeptAsItWasWritten() {
        // Si el plan numeró, el número es parte del contenido: alguien lo va a usar para referirse
        // a un item. Normalizarlo todo a un bullet perdería esa referencia.
        assertEquals(listOf("a)", "b)"), bloques("Dos formas: a) una, b) otra").drop(1).map { it.marker })
    }

    @Test
    fun aSingleMarkerIsNotAList() {
        // "1)" solo es una aclaración, no una enumeración. Partir ahí inventaría una estructura
        // que el texto no tiene.
        val b = bloques("El caso 1) es el único que importa acá")
        assertEquals(1, b.size)
        assertEquals(null, b.single().marker)
    }

    @Test
    fun versionNumbersAndDecimalsAreNotItems() {
        // El error que esto evita: `v1.2` y `Art. 5.` convertidos en items de una lista que nadie
        // escribió, con el texto partido justo en el medio de una idea.
        val b = bloques("Migrar de v1.2 a v2.0 sin romper el contrato 3.1 del acuerdo")
        assertEquals(1, b.size, "nada de esto es una lista: ${b.map { it.text }}")
    }

    @Test
    fun existingLineBreaksWin() {
        // Si alguien ya separó, esa separación gana sobre cualquier heurística: es información
        // real sobre cómo quiso que se leyera.
        val b = bloques("- primero\n- segundo\n- tercero")
        assertEquals(3, b.size)
        assertTrue(b.all { it.marker == "-" })
        assertEquals("primero", b.first().text)
    }

    @Test
    fun plainProseIsLeftAlone() {
        val texto = "Esto es un párrafo común, sin ninguna lista adentro."
        assertEquals(listOf(texto), bloques(texto).map { it.text })
    }

    @Test
    fun aReferenceInParenthesesIsNotAListItem() {
        // Salió de mirar descripciones de verdad: los planes están llenos de "(mockup 01)" y
        // "(tarea 13)". El número va precedido y seguido de espacio, igual que un item, así que
        // sin mirar el paréntesis el texto se partía justo en el medio de una idea.
        val real = "En timelogbook-v4: rediseñar pages/management/absences/absences.component " +
            "(mockup 01) con tarjetas de saldo por tipo, usando GET /absences/me/balances, y crear " +
            "absence-request-dialog/ (mockup 02) con preview en vivo (tarea 13), medio día por extremo."
        val b = bloques(real)
        assertEquals(1, b.size, "ninguna de estas es una lista: ${b.map { it.text }}")
    }

    @Test
    fun theIntroSentenceIsNotThrownAway() {
        // Sin ella los items quedan sin decir de qué son.
        val b = bloques("Pasos del import: 1) leer, 2) validar")
        assertEquals("Pasos del import", b.first().text, "se le saca el dos puntos, no la frase")
    }
}

/**
 * Retomar una implementación que falló.
 *
 * El motor sólo toma tareas pendientes. Una tarea fallida ya no lo es, así que sin volver a
 * encolarla "retomar" no hacía nada: terminaba al instante y volvía a mostrar el error de la vez
 * anterior, que además ya no describía nada actual.
 */
class ImplResumeTest {

    private fun conRepo(block: (AppContext, String) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("acr-res")
        val ctx = AppContext.bootstrap(dir)
        try {
            val repoId = ctx.repos.create(
                "tmp-res-${System.nanoTime()}", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            block(ctx, repoId)
        } finally {
            ctx.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun tarea(seq: Int) = ImplTask(
        "", "", null, seq, "tarea $seq", "detalle", emptyList(), TaskSize.M, 10,
        TaskStatus.PENDING, null, null, null, null, null, null,
        steps = listOf(
            io.acr.impl.ImplStep("", "", 1, "un paso", TaskStatus.PENDING, null, "", null, null, null),
        ),
    )

    @Test
    fun resumingPutsTheFailedTasksBackInTheQueue() = conRepo { ctx, repoId ->
        val id = ctx.impls.create(
            listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.OTHER, null)),
            "retomar", listOf("/tmp/x.md"), null,
        )
        ctx.impls.savePlan(id, "r", "rama", "develop", "fable", listOf(tarea(1), tarea(2), tarea(3)))
        val tareas = ctx.impls.tasks(id)
        ctx.impls.finishTask(tareas[0].id, "abc", "hecha", 0.1)
        ctx.impls.failTask(tareas[1].id, "explotó")
        ctx.impls.blockTask(tareas[2].id, "¿qué hacemos acá?")

        assertEquals(1, ctx.impls.retryFailed(id), "sólo la fallida vuelve a la cola")

        val despues = ctx.impls.tasks(id).associateBy { it.seq }
        assertEquals(TaskStatus.DONE, despues[1]!!.status, "lo hecho no se toca")
        assertEquals(TaskStatus.PENDING, despues[2]!!.status)
        assertEquals(
            TaskStatus.BLOCKED, despues[3]!!.status,
            "la bloqueada espera una decisión que nadie tomó: relanzarla la haría chocar contra la misma pregunta",
        )
    }

    @Test
    fun theOldErrorDoesNotSurviveTheRetry() = conRepo { ctx, repoId ->
        // Es la mitad del problema: aunque se reintente, el texto del error viejo colgado de la
        // tarea sigue leyéndose como si fuera de ahora.
        val id = ctx.impls.create(
            listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.OTHER, null)),
            "error viejo", listOf("/tmp/x.md"), null,
        )
        ctx.impls.savePlan(id, "r", "rama", "develop", "fable", listOf(tarea(1)))
        val t = ctx.impls.tasks(id).single()
        ctx.impls.failTask(t.id, "unrecognized_model")

        ctx.impls.retryFailed(id)

        val vuelta = ctx.impls.tasks(id).single()
        assertEquals(null, vuelta.error)
        assertEquals(null, vuelta.finishedAt, "ni la hora en que falló")
        assertEquals(TaskStatus.PENDING, vuelta.steps.single().status, "los pasos también arrancan de cero")
    }
}
