package io.acr

import io.acr.stats.GitAuthor
import io.acr.stats.Identity
import io.acr.stats.IdentityKind
import io.acr.stats.isGenerated
import io.acr.stats.matchesGlob
import io.acr.stats.parseGitLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Leer el historial de git y separar el código del ruido generado.
 *
 * El número de líneas es la métrica más fácil de malinterpretar que tiene el módulo, así que lo
 * mínimo es que esté bien calculada. Medido sobre `kubrik-erp-be` a 90 días, los archivos
 * generados son el 25,6% de las líneas: contarlos como trabajo haría que quien tocó una semilla
 * de datos aparezca arriba de quien escribió el sistema.
 */
class CommitCollectorTest {

    private val RS = "\u001e"
    private val US = "\u001f"

    private fun commit(sha: String, nombre: String, email: String, fecha: String, vararg archivos: String) =
        "$RS$sha$US$nombre$US$email$US$fecha\n" + archivos.joinToString("\n") + "\n"

    // --- glob ---

    @Test
    fun doubleStarCrossesDirectoriesAndSingleDoesNot() {
        assertTrue(matchesGlob("src/db/seed/data.sql", "**/seed/**"))
        assertTrue(matchesGlob("seed/data.sql", "**/seed/**"), "también en la raíz")
        assertTrue(!matchesGlob("src/seedling/x.kt", "**/seed/**"))

        // Con barra en el patrón se ve la diferencia entre uno y dos asteriscos: `src/*.js` es
        // sólo lo que está justo ahí, `src/**/*.js` baja por todo el árbol.
        assertTrue(matchesGlob("src/a.js", "src/*.js"))
        assertTrue(!matchesGlob("src/b/c.js", "src/*.js"), "un asterisco solo no cruza barras")
        assertTrue(matchesGlob("src/b/c.js", "src/**/*.js"))
    }

    @Test
    fun anExtensionPatternMatchesTheFileAnywhere() {
        // Uno escribe "*.png" esperando que tome cualquier png, no sólo los de la raíz.
        assertTrue(isGenerated("src/assets/logo.png"))
        assertTrue(isGenerated("package-lock.json"))
        assertTrue(isGenerated("frontend/package-lock.json"))
    }

    @Test
    fun schemaMigrationsAreRealWork() {
        // La distinción que importa es entre migración y semilla, no la extensión: son 14.615
        // líneas de cambio de esquema que hay que revisar como cualquier otro código.
        assertTrue(!isGenerated("ks-erp-app/src/main/resources/db/migration/V0306__algo.sql"))
        assertTrue(isGenerated("ks-erp-app/src/main/resources/db/seed/demo.sql"))
    }

    @Test
    fun sourceCodeIsNeverGenerated() {
        assertTrue(!isGenerated("src/main/kotlin/io/acr/App.kt"))
        assertTrue(!isGenerated("src/app/features/wms/wms-home.component.ts"))
    }

    // --- parseo del log ---

    @Test
    fun itReadsTheAuthorAndTheVolume() {
        val log = commit(
            "abc123", "Mateo Andrés Perano", "mateo@kubriksoftware.com", "2026-08-01T10:00:00-03:00",
            "10\t5\tsrc/App.kt",
            "3\t0\tsrc/Otro.kt",
        )
        val c = parseGitLog(log).single()
        assertEquals("abc123", c.sha)
        assertEquals(GitAuthor("Mateo Andrés Perano", "mateo@kubriksoftware.com"), c.author)
        assertEquals(2, c.files)
        assertEquals(13, c.added)
        assertEquals(5, c.deleted)
    }

    @Test
    fun generatedLinesAreCountedApartFromTheStart() {
        // Separadas desde el origen y no sumadas: una vez juntas no hay forma de partirlas, y un
        // package-lock regenerado pesaría más que un mes de trabajo.
        val log = commit(
            "abc", "X", "x@y.com", "2026-08-01T10:00:00Z",
            "20\t4\tsrc/App.kt",
            "5000\t3000\tpackage-lock.json",
            "40505\t0\tdb/seed/demo.sql",
        )
        val c = parseGitLog(log).single()
        assertEquals(20, c.added)
        assertEquals(4, c.deleted)
        assertEquals(45505, c.generatedAdded)
        assertEquals(3000, c.generatedDeleted)
        assertEquals(3, c.files, "el archivo generado se tocó igual, sólo no cuenta como código")
    }

    @Test
    fun aNameWithSeparatorsDoesNotBreakTheParsing() {
        // Los campos se separan con caracteres de control justamente porque un nombre puede tener
        // comas y pipes, y un email cualquier cosa.
        val log = commit("abc", "Pérez, Ana | QA", "ana@x.com", "2026-08-01T10:00:00Z", "1\t1\ta.kt")
        assertEquals("Pérez, Ana | QA", parseGitLog(log).single().author.name)
    }

    @Test
    fun binaryFilesCountAsTouchedButNotAsLines() {
        // git emite "-" en vez de números para binarios.
        val log = commit("abc", "X", "x@y.com", "2026-08-01T10:00:00Z", "-\t-\tdocs/manual.pdf", "2\t1\ta.kt")
        val c = parseGitLog(log).single()
        assertEquals(2, c.files)
        assertEquals(2, c.added)
    }

    @Test
    fun aRenameIsCountedWhereTheFileEndedUp() {
        val log = commit("abc", "X", "x@y.com", "2026-08-01T10:00:00Z", "0\t0\tsrc/{viejo => nuevo}/a.kt")
        assertEquals(1, parseGitLog(log).single().files)
    }

    @Test
    fun severalCommitsAreReadInOnePass() {
        // Una sola invocación de git y no una por commit: en estos repositorios son miles, y mil
        // procesos tardan minutos en vez de segundos.
        val log = commit("a1", "X", "x@y.com", "2026-08-01T10:00:00Z", "1\t0\ta.kt") +
            commit("a2", "Y", "y@y.com", "2026-08-02T10:00:00Z", "2\t0\tb.kt") +
            commit("a3", "X", "x@y.com", "2026-08-03T10:00:00Z", "3\t0\tc.kt")
        val cs = parseGitLog(log)
        assertEquals(listOf("a1", "a2", "a3"), cs.map { it.sha })
        assertEquals(listOf(1, 2, 3), cs.map { it.added })
    }

    @Test
    fun aCommitWithoutFilesIsStillACommit() {
        // Un commit vacío o que sólo toca permisos: existe y tiene autor.
        val c = parseGitLog(commit("abc", "X", "x@y.com", "2026-08-01T10:00:00Z")).single()
        assertEquals(0, c.files)
        assertEquals(0, c.touched)
    }

    @Test
    fun emptyOutputIsNotAnError() {
        // Un repositorio recién clonado, o un `--since` que no alcanza ningún commit.
        assertEquals(emptyList(), parseGitLog(""))
        assertEquals(emptyList(), parseGitLog("\n\n"))
    }

    // --- persistencia y fusión ---

    /**
     * Cada test con su base propia.
     *
     * Las personas y sus identidades son globales, no de un repositorio: borrar el repo al
     * terminar no las limpia, y una identidad que quedó de otro test hace que la heurística
     * encuentre a alguien que este test nunca creó. Se vio de verdad acá — dos tests pasaban
     * solos y fallaban juntos.
     */
    private fun conRepo(block: (AppContext, String) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("acr-stats-test")
        val ctx = AppContext.bootstrap(dir)
        try {
            val repoId = ctx.repos.create(
                "tmp-stats-${System.nanoTime()}", io.acr.forge.Provider.BITBUCKET, "acme", "demo",
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
    fun mergingTwoPeopleMovesTheirCommitsToo() = conRepo { ctx, repoId ->
        // Es el caso de Viktor: dos cuentas, 223 y 26 commits. Si al fusionar los commits
        // quedaran apuntando a la persona borrada, desaparecerían de todos los totales.
        val cache = mutableListOf<io.acr.stats.Person>()
        val p1 = ctx.persons.resolve(GitAuthor("Viktor K", "viktor@kubriksoftware.com"), cache)
        val p2 = ctx.persons.resolve(GitAuthor("Viktor Karpyuk", "viktor.karpyuk@kubriksoftware.com"), cache)
        assertTrue(p1 != p2, "la heurística no puede unirlos sola, y está bien que no lo intente")

        val log = commit("s1", "Viktor K", "viktor@kubriksoftware.com", "2026-08-01T10:00:00Z", "10\t0\ta.kt") +
            commit("s2", "Viktor Karpyuk", "viktor.karpyuk@kubriksoftware.com", "2026-08-02T10:00:00Z", "5\t0\tb.kt")
        parseGitLog(log).forEach { c ->
            ctx.commitStats.upsert(repoId, ctx.persons.resolve(c.author, cache), c)
        }

        ctx.persons.merge(p1, p2)

        val vol = ctx.commitStats.volumeByPerson("2026-01-01", "2026-12-31", repoId)
        assertEquals(null, vol[p2], "la persona absorbida ya no tiene commits propios")
        assertEquals(2, assertNotNull(vol[p1]).commits)
        assertEquals(15, vol.getValue(p1).added)
    }

    @Test
    fun aMergeMarksTheIdentitiesAsReviewed() = conRepo { ctx, repoId ->
        val cache = mutableListOf<io.acr.stats.Person>()
        val p1 = ctx.persons.resolve(GitAuthor("Viktor K", "viktor@x.com"), cache)
        val p2 = ctx.persons.resolve(GitAuthor("Viktor Karpyuk", "viktor.karpyuk@x.com"), cache)
        assertTrue(ctx.persons.hasUnconfirmed(), "recién descubiertas, nadie las miró todavía")

        ctx.persons.merge(p1, p2)
        ctx.persons.confirmAll()

        assertTrue(!ctx.persons.hasUnconfirmed(), "confirmar es lo que apaga el aviso de provisional")
    }

    @Test
    fun splittingAnIdentityTakesItsCommitsWithIt() = conRepo { ctx, repoId ->
        // Deshacer una fusión equivocada. Sin mover los commits, separar sería cosmético: los
        // números seguirían contando igual que antes.
        val cache = mutableListOf<io.acr.stats.Person>()
        val p = ctx.persons.resolve(GitAuthor("Ana Gómez", "ana@x.com"), cache)
        ctx.persons.addIdentity(p, Identity(IdentityKind.GIT_EMAIL, "otra@x.com"))
        parseGitLog(
            commit("s1", "Ana Gómez", "ana@x.com", "2026-08-01T10:00:00Z", "10\t0\ta.kt") +
                commit("s2", "Ana Gómez", "otra@x.com", "2026-08-02T10:00:00Z", "7\t0\tb.kt"),
        ).forEach { ctx.commitStats.upsert(repoId, p, it) }

        val idOtra = ctx.persons.identityIds(p).first { it.second.value == "otra@x.com" }.first
        val nuevo = assertNotNull(ctx.persons.split(idOtra, "Otra Persona"))

        val vol = ctx.commitStats.volumeByPerson("2026-01-01", "2026-12-31", repoId)
        assertEquals(10, vol.getValue(p).added)
        assertEquals(7, vol.getValue(nuevo).added, "los commits de esa identidad se van con ella")
    }

    @Test
    fun storedIdentitiesConvergeNoMatterTheOrderOfTheCommits() = conRepo { ctx, repoId ->
        // El mismo caso real que en la resolución en memoria, pero contra la base: acá el
        // `UNIQUE(kind,value)` hacía que el alias corto se descartara en silencio en vez de unir,
        // así que el equipo daba números distintos según qué repositorio se leyera primero.
        val cache = mutableListOf<io.acr.stats.Person>()
        val a = ctx.persons.resolve(GitAuthor("Lautaro Eloy Islas", "lautaro@empresa.com"), cache)
        ctx.persons.resolve(GitAuthor("Lautaro", "islaslautaroeloy@gmail.com"), cache)
        val c = ctx.persons.resolve(GitAuthor("Lautaro", "lautaro@empresa.com"), cache)

        assertEquals(1, ctx.persons.all().size, "las tres formas son una sola persona")
        assertEquals(a, c, "el alias corto no abre una persona nueva")
        assertEquals(
            setOf("lautaro@empresa.com", "islaslautaroeloy@gmail.com"),
            ctx.persons.all().single().emails(),
            "encontrar la identidad tomada es la prueba de que son la misma, no un conflicto",
        )
    }

    @Test
    fun aMergeDuringCollectionDoesNotStrandCommits() = conRepo { ctx, repoId ->
        // Si la fusión ocurre a mitad de la recolección, los commits ya guardados con el id viejo
        // tienen que seguir contando: si quedaran apuntando a una persona borrada, desaparecerían.
        val cache = mutableListOf<io.acr.stats.Person>()
        val log = commit("s1", "Lautaro Eloy Islas", "lautaro@empresa.com", "2026-08-01T10:00:00Z", "10\t0\ta.kt") +
            commit("s2", "Lautaro", "islaslautaroeloy@gmail.com", "2026-08-02T10:00:00Z", "5\t0\tb.kt") +
            commit("s3", "Lautaro", "lautaro@empresa.com", "2026-08-03T10:00:00Z", "3\t0\tc.kt")
        parseGitLog(log).forEach { ctx.commitStats.upsert(repoId, ctx.persons.resolve(it.author, cache), it) }

        val vol = ctx.commitStats.volumeByPerson("2026-01-01", "2026-12-31", repoId)
        assertEquals(1, vol.size, "una persona")
        assertEquals(3, vol.values.single().commits, "y sus tres commits, ninguno perdido")
        assertEquals(18, vol.values.single().added)
    }

    @Test
    fun reprocessingTheSameCommitDoesNotDoubleCount() = conRepo { ctx, repoId ->
        // Recolectar dos veces es normal: la segunda corrida vuelve a ver los commits viejos.
        val cache = mutableListOf<io.acr.stats.Person>()
        val c = parseGitLog(commit("s1", "X", "x@y.com", "2026-08-01T10:00:00Z", "10\t0\ta.kt")).single()
        val p = ctx.persons.resolve(c.author, cache)
        ctx.commitStats.upsert(repoId, p, c)
        ctx.commitStats.upsert(repoId, p, c)

        assertEquals(1, ctx.commitStats.count(repoId))
        assertEquals(10, ctx.commitStats.volumeByPerson("2026-01-01", "2026-12-31", repoId).getValue(p).added)
    }

    @Test
    fun theVolumeIsAlwaysAvailableBrokenDown() = conRepo { ctx, repoId ->
        // Nunca un número solo: agregado, borrado, neto y cuánto se excluyó por generado.
        val cache = mutableListOf<io.acr.stats.Person>()
        val c = parseGitLog(
            commit("s1", "X", "x@y.com", "2026-08-01T10:00:00Z", "100\t40\ta.kt", "900\t0\tpackage-lock.json"),
        ).single()
        ctx.commitStats.upsert(repoId, ctx.persons.resolve(c.author, cache), c)

        val v = ctx.commitStats.volumeByPerson("2026-01-01", "2026-12-31", repoId).values.single()
        assertEquals(100, v.added)
        assertEquals(40, v.deleted)
        assertEquals(60, v.net)
        assertEquals(140, v.touched)
        assertEquals(900, v.generated, "lo excluido se puede decir, no se esconde")
    }

    @Test
    fun aPeriodOnlyCountsWhatHappenedInIt() = conRepo { ctx, repoId ->
        val cache = mutableListOf<io.acr.stats.Person>()
        val log = commit("viejo", "X", "x@y.com", "2025-01-15T10:00:00Z", "50\t0\ta.kt") +
            commit("nuevo", "X", "x@y.com", "2026-08-01T10:00:00Z", "7\t0\tb.kt")
        parseGitLog(log).forEach { ctx.commitStats.upsert(repoId, ctx.persons.resolve(it.author, cache), it) }

        val q3 = ctx.commitStats.volumeByPerson("2026-07-01", "2026-09-30", repoId).values.single()
        assertEquals(1, q3.commits)
        assertEquals(7, q3.added)
    }
}
