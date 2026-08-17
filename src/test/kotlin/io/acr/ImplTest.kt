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
