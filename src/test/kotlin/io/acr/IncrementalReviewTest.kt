package io.acr

import io.acr.claude.ProjectKind
import io.acr.claude.ReviewDepth
import io.acr.claude.ReviewScope
import io.acr.claude.ScopeReason
import io.acr.claude.decideScope
import io.acr.data.Finding
import io.acr.data.Resolution
import io.acr.data.ReviewRecord
import io.acr.data.ReviewStatus
import io.acr.forge.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Revisar sólo lo que llegó después de la review anterior.
 *
 * Hasta la 37.0.0 cada corrida miraba la rama entera: el PR #149, de 23 commits, se revisó 25
 * veces de punta a punta por US$ 225 —el 60% de todo el consumo—, releyendo archivos ya aprobados
 * 24 veces.
 *
 * Lo peligroso de esta mejora no es que ahorre poco: es que mire de menos y nadie se entere. Por
 * eso lo que más se prueba acá son los casos en los que hay que volver a mirar todo, y la regla
 * de que ningún hallazgo abierto puede desaparecer en el camino.
 */
class IncrementalReviewTest {

    private fun review(id: String, sha: String, status: ReviewStatus = ReviewStatus.DONE) = ReviewRecord(
        id = id, repoId = "r", prId = 1, prTitle = "t", headSha = sha, status = status,
        body = null, error = null, sessionId = null, costUsd = null, publishedUrl = null,
        createdAt = "2026-08-01T00:00:00Z", depth = null, projectKind = null, model = null,
        auto = false, deniedTools = null,
    )

    /** Cadena lineal: todo es ancestro de lo que vino después. */
    private val encadena: (String, String) -> Boolean = { _, _ -> true }
    private val noEncadena: (String, String) -> Boolean = { _, _ -> false }

    // --- cuándo se puede continuar y cuándo no ---

    @Test
    fun withNewCommitsOnTopItReviewsOnlyTheNewOnes() {
        val d = decideScope(review("rev1", "aaa"), headSha = "bbb", forceFull = false, isAncestor = encadena)
        assertEquals(ScopeReason.NEW_COMMITS, d.reason)
        assertEquals(ReviewScope.Incremental("aaa", "rev1"), d.scope)
    }

    @Test
    fun theFirstReviewOfAPrLooksAtEverything() {
        val d = decideScope(null, headSha = "aaa", forceFull = false, isAncestor = encadena)
        assertEquals(ScopeReason.FIRST_REVIEW, d.reason)
        assertEquals(ReviewScope.Full, d.scope)
    }

    @Test
    fun aRebaseForcesAFullReview() {
        // El caso que más importa: si el autor rebasó o forzó el push, el commit que revisamos ya
        // no está en la historia del actual. El rango `viejo..nuevo` daría vacío o basura, y la
        // review pasaría por buena mirando cualquier cosa menos el cambio.
        val d = decideScope(review("rev1", "aaa"), headSha = "zzz", forceFull = false, isAncestor = noEncadena)
        assertEquals(ScopeReason.REWRITTEN_HISTORY, d.reason)
        assertEquals(ReviewScope.Full, d.scope)
    }

    @Test
    fun ifGitCannotAnswerItLooksAtEverything() {
        // Ante la duda, mirar de más. Una review completa de sobra cuesta plata; una incremental
        // sobre una historia que no se pudo verificar devuelve algo en lo que no se puede confiar.
        val explota: (String, String) -> Boolean = { _, _ -> error("git no está") }
        val d = decideScope(review("rev1", "aaa"), headSha = "bbb", forceFull = false, isAncestor = explota)
        assertEquals(ScopeReason.REWRITTEN_HISTORY, d.reason)
        assertEquals(ReviewScope.Full, d.scope)
    }

    @Test
    fun theUserCanAlwaysAskForTheWholeBranch() {
        val d = decideScope(review("rev1", "aaa"), headSha = "bbb", forceFull = true, isAncestor = encadena)
        assertEquals(ScopeReason.FORCED, d.reason)
        assertEquals(ReviewScope.Full, d.scope)
    }

    @Test
    fun repeatingTheSameCommitIsAFreshLookNotAnIncrement() {
        // Se llega acá sólo si el usuario insistió después del aviso de la 36.0.0. Lo que quiere
        // entonces es una mirada entera, no un diff vacío contra sí mismo.
        val d = decideScope(review("rev1", "aaa"), headSha = "aaa", forceFull = false, isAncestor = encadena)
        assertEquals(ScopeReason.SAME_COMMIT, d.reason)
        assertEquals(ReviewScope.Full, d.scope)
    }

    @Test
    fun aBlankShaNeverBecomesARange() {
        // Un PR cacheado sin head, o una review vieja sin sha guardado: "..bbb" no es un rango.
        assertEquals(
            ReviewScope.Full,
            decideScope(review("rev1", ""), headSha = "bbb", forceFull = false, isAncestor = encadena).scope,
        )
        assertEquals(
            ReviewScope.Full,
            decideScope(review("rev1", "aaa"), headSha = "", forceFull = false, isAncestor = encadena).scope,
        )
    }

    // --- qué se arrastra ---

    private fun conRepo(block: (AppContext, String, Long) -> Unit) {
        val ctx = AppContext.bootstrap()
        try {
            val prId = System.nanoTime() % 100_000
            val repoId = ctx.repos.create(
                "tmp-inc-$prId", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            try { block(ctx, repoId, prId) } finally { ctx.repos.delete(repoId) }
        } finally { ctx.close() }
    }

    private fun AppContext.reviewCon(repoId: String, prId: Long, sha: String, hallazgos: List<Finding>): String {
        val id = reviews.start(repoId, prId, "t", sha, ReviewDepth.LIGHT, ProjectKind.BACKEND, "m", false)
        findings.replaceForReview(id, repoId, prId, hallazgos)
        reviews.finish(id, "cuerpo", null, 1.0)
        return id
    }

    private fun hallazgo(prId: Long, titulo: String, archivo: String = "a.kt") =
        Finding("", "", prId, archivo, 10, "major", titulo, "detalle", null, null)

    @Test
    fun onlyOpenFindingsAreHandedToTheNextReview() = conRepo { ctx, repoId, prId ->
        val id = ctx.reviewCon(
            repoId, prId, "aaa",
            listOf(hallazgo(prId, "abierto"), hallazgo(prId, "descartado"), hallazgo(prId, "cerrado"), hallazgo(prId, "resuelto")),
        )
        val todos = ctx.findings.forReview(id).associateBy { it.title }
        ctx.findings.dismiss(todos.getValue("descartado").id)
        ctx.findings.close(todos.getValue("cerrado").id, true)
        ctx.findings.setResolution(todos.getValue("resuelto").id, Resolution.RESOLVED, "ya está")

        // Descartado, cerrado y verificado como resuelto son decisiones tomadas: volver a
        // preguntar por ellas gasta contexto para reabrir algo que alguien ya cerró.
        val paraArrastrar = ctx.findings.openForCarry(id)
        assertEquals(listOf("abierto"), paraArrastrar.map { it.title })
    }

    @Test
    fun aCarriedFindingKeepsItsIdentityAndItsPublication() = conRepo { ctx, repoId, prId ->
        val vieja = ctx.reviewCon(repoId, prId, "aaa", listOf(hallazgo(prId, "sigue roto")))
        val f = ctx.findings.forReview(vieja).single()
        ctx.findings.markPublished(f.id, "comment-123", "https://bitbucket.org/x/1#c123")

        val nueva = ctx.reviews.start(repoId, prId, "t", "bbb", ReviewDepth.LIGHT, ProjectKind.BACKEND, "m", false)
        ctx.findings.carryForward(f.id, nueva, newLine = 42)

        // Se mueve la MISMA fila: si se copiara, el hallazgo aparecería dos veces y los
        // contadores lo contarían dos veces. Era justo lo que venía pasando —81 de 87 hallazgos
        // "pendientes" eran residuo de reviews superadas.
        assertTrue(ctx.findings.forReview(vieja).isEmpty())
        val movido = ctx.findings.forReview(nueva).single()
        assertEquals(f.id, movido.id)
        assertEquals("comment-123", movido.publishedId, "un hallazgo ya publicado no se republica")
        assertEquals(42, movido.lineNo, "si el código se movió, el ancla se corrige")
    }

    @Test
    fun aCarriedFindingWithoutANewLineKeepsTheOldAnchor() = conRepo { ctx, repoId, prId ->
        // Un hallazgo publicado está anclado a un comentario en una línea concreta. Reescribir esa
        // línea con un null la borraría y el enlace al código dejaría de llevar a ningún lado.
        val vieja = ctx.reviewCon(repoId, prId, "aaa", listOf(hallazgo(prId, "sin mover")))
        val f = ctx.findings.forReview(vieja).single()
        val nueva = ctx.reviews.start(repoId, prId, "t", "bbb", ReviewDepth.LIGHT, ProjectKind.BACKEND, "m", false)

        ctx.findings.carryForward(f.id, nueva, newLine = null)

        assertEquals(10, ctx.findings.forReview(nueva).single().lineNo)
    }

    @Test
    fun theChainIsRecordedSoTheScopeCanBeReadLater() = conRepo { ctx, repoId, prId ->
        val vieja = ctx.reviewCon(repoId, prId, "aaa", emptyList())
        val nueva = ctx.reviews.start(
            repoId, prId, "t", "bbb", ReviewDepth.LIGHT, ProjectKind.BACKEND, "m", false,
            previousReviewId = vieja, sinceSha = "aaa",
        )
        val r = assertNotNull(ctx.reviews.get(nueva))
        assertEquals(vieja, r.previousReviewId)
        assertEquals("aaa", r.sinceSha)
        assertTrue(r.incremental, "una review que miró sólo una parte tiene que poder decirlo")
    }

    @Test
    fun aFullReviewIsNotMarkedAsIncremental() = conRepo { ctx, repoId, prId ->
        val id = ctx.reviews.start(repoId, prId, "t", "aaa", ReviewDepth.LIGHT, ProjectKind.BACKEND, "m", false)
        val r = assertNotNull(ctx.reviews.get(id))
        assertNull(r.sinceSha)
        assertTrue(!r.incremental)
    }

    // --- el dictamen del modelo sobre lo anterior ---

    @Test
    fun aFindingTheModelForgotIsCarriedForwardAnyway() = conRepo { ctx, repoId, prId ->
        // La regla más importante de toda la mejora. Si el modelo devuelve la lista incompleta,
        // dejar atrás lo que no dictaminó haría desaparecer de la pantalla un problema que nadie
        // resolvió ni decidió descartar. Un hallazgo que se pierde en silencio es exactamente lo
        // que una herramienta de revisión no puede hacer.
        val vieja = ctx.reviewCon(repoId, prId, "aaa", listOf(hallazgo(prId, "uno"), hallazgo(prId, "olvidado")))
        val previos = ctx.findings.openForCarry(vieja)
        val nueva = ctx.reviews.start(repoId, prId, "t", "bbb", ReviewDepth.LIGHT, ProjectKind.BACKEND, "m", false)

        val soloUno = previos.first { it.title == "uno" }
        val stats = ctx.engine.applyCarried(
            previos,
            listOf(io.acr.claude.ReviewEngine.Carried(soloUno.id, io.acr.claude.CarryVerdict.FIXED, null, "corregido")),
            nueva,
        )

        assertEquals(1, stats.fixed)
        assertEquals(1, stats.stillOpen, "el que el modelo ignoró se arrastra abierto, no se pierde")
        assertEquals(listOf("olvidado"), ctx.findings.forReview(nueva).map { it.title })
    }

    @Test
    fun aFixedFindingStaysBehindMarkedResolved() = conRepo { ctx, repoId, prId ->
        val vieja = ctx.reviewCon(repoId, prId, "aaa", listOf(hallazgo(prId, "corregido")))
        val previos = ctx.findings.openForCarry(vieja)
        val nueva = ctx.reviews.start(repoId, prId, "t", "bbb", ReviewDepth.LIGHT, ProjectKind.BACKEND, "m", false)

        ctx.engine.applyCarried(
            previos,
            listOf(io.acr.claude.ReviewEngine.Carried(previos.single().id, io.acr.claude.CarryVerdict.FIXED, null, "el commit ccc lo arregla")),
            nueva,
        )

        // Trabajo terminado: moverlo a la review nueva lo mostraría como pendiente.
        assertTrue(ctx.findings.forReview(nueva).isEmpty())
        val f = ctx.findings.forReview(vieja).single()
        assertEquals(Resolution.RESOLVED, f.resolution)
        assertEquals("el commit ccc lo arregla", f.resolutionNote, "el veredicto va con su evidencia")
    }

    @Test
    fun anObsoleteFindingIsClosedNotLeftDangling() = conRepo { ctx, repoId, prId ->
        val vieja = ctx.reviewCon(repoId, prId, "aaa", listOf(hallazgo(prId, "ya no aplica")))
        val previos = ctx.findings.openForCarry(vieja)
        val nueva = ctx.reviews.start(repoId, prId, "t", "bbb", ReviewDepth.LIGHT, ProjectKind.BACKEND, "m", false)

        ctx.engine.applyCarried(
            previos,
            listOf(io.acr.claude.ReviewEngine.Carried(previos.single().id, io.acr.claude.CarryVerdict.OBSOLETE, null, "el archivo se borró")),
            nueva,
        )

        val f = ctx.findings.forReview(vieja).single()
        assertNotNull(f.closedAt, "algo que dejó de aplicar no puede quedar esperando respuesta")
        assertTrue(ctx.findings.openForCarry(vieja).isEmpty())
    }

    @Test
    fun anInventedIdCannotTouchAnything() = conRepo { ctx, repoId, prId ->
        // Un id de otro PR —o inventado— movería o cerraría un hallazgo que nadie miró.
        val json = """{"summary":"x","findings":[],"carried":[
            {"id":"ID-QUE-NO-EXISTE","verdict":"FIXED","evidence":"nada"}]}"""
        assertEquals(emptyList(), ctx.engine.parseCarried(json, setOf("ID-REAL")))
    }

    @Test
    fun anUnknownVerdictLeavesTheFindingOpen() = conRepo { ctx, repoId, prId ->
        // Dejar algo abierto de más se corrige mirándolo; cerrar algo que sigue roto se descubre
        // en producción.
        val json = """{"summary":"x","findings":[],"carried":[
            {"id":"A","verdict":"SE_ARREGLA_SOLO","evidence":"?"}]}"""
        assertEquals(io.acr.claude.CarryVerdict.STILL_OPEN, ctx.engine.parseCarried(json, setOf("A")).single().verdict)
    }

    @Test
    fun aBrokenAnswerDoesNotTakeTheReviewDown() = conRepo { ctx, repoId, prId ->
        // Si el JSON no se entiende, no hay dictámenes: todo se arrastra abierto por el camino
        // por defecto. El resto de la review sigue siendo válido.
        assertEquals(emptyList(), ctx.engine.parseCarried("no es json", setOf("A")))
        assertEquals(emptyList(), ctx.engine.parseCarried("""{"summary":"x","findings":[]}""", setOf("A")))
    }

    @Test
    fun aDuplicatedVerdictIsCountedOnce() = conRepo { ctx, repoId, prId ->
        val json = """{"summary":"x","findings":[],"carried":[
            {"id":"A","verdict":"FIXED","evidence":"1"},
            {"id":"A","verdict":"OBSOLETE","evidence":"2"}]}"""
        assertEquals(1, ctx.engine.parseCarried(json, setOf("A")).size)
    }

    @Test
    fun theModelCanMoveTheAnchorOfAFindingThatIsStillOpen() = conRepo { ctx, repoId, prId ->
        val json = """{"summary":"x","findings":[],"carried":[
            {"id":"A","verdict":"STILL_OPEN","line":88,"evidence":"se movió"}]}"""
        assertEquals(88, ctx.engine.parseCarried(json, setOf("A")).single().line)
    }

    @Test
    fun theLatestDoneReviewIsTheOneToContinueFrom() = conRepo { ctx, repoId, prId ->
        val buena = ctx.reviewCon(repoId, prId, "aaa", emptyList())
        Thread.sleep(5)
        val fallada = ctx.reviews.start(repoId, prId, "t", "bbb", ReviewDepth.LIGHT, ProjectKind.BACKEND, "m", false)
        ctx.reviews.fail(fallada, "se cerró la app")

        // Continuar desde una que falló daría por revisados unos commits que nadie llegó a mirar,
        // y el agujero quedaría escondido en el medio de la rama para siempre.
        assertEquals(buena, ctx.reviews.latestDoneFor(repoId, prId)?.id)
    }
}
