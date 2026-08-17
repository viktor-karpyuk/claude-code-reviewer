package io.acr

import io.acr.data.DbEngine
import io.acr.data.DbSettings
import io.acr.data.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El esquema entero, contra un PostgreSQL y un MySQL de verdad.
 *
 * Un traductor de SQL probado sólo contra sí mismo no prueba nada: los errores que importan son los
 * que el motor rechaza, y para verlos hay que hablarle a un motor. Si no hay ninguno escuchando, el
 * test se saltea en vez de fallar —no todas las máquinas tienen un servidor levantado— pero cuando
 * lo hay, corre completo.
 */
class DbEngineIT {

    private fun vivo(host: String, port: Int): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 400) }
        true
    }.getOrDefault(false)

    /**
     * Deja la base vacía antes de cada corrida.
     *
     * El contenedor sobrevive entre corridas, así que sin esto la segunda choca contra las filas
     * de la primera y el error parece del traductor cuando es del test.
     */
    private fun limpiar(s: DbSettings) {
        java.sql.DriverManager.getConnection(
            s.jdbcUrl(java.nio.file.Path.of("/tmp/x.db")), s.user, s.password,
        ).use { c ->
            c.createStatement().use { st ->
                when (s.engine) {
                    DbEngine.POSTGRES -> st.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;")
                    DbEngine.MYSQL -> {
                        st.execute("DROP DATABASE IF EXISTS " + s.database)
                        st.execute("CREATE DATABASE " + s.database)
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun probar(s: DbSettings) {
        limpiar(s)
        val tmp = java.nio.file.Files.createTempDirectory("acr-eng")
        try {
            // Base limpia: el esquema entero se aplica de cero, que es lo que hay que probar.
            Store(tmp.resolve("x.db"), s).use { st ->
                val (aplicada, conocidas) = st.schemaVersion()
                assertEquals(conocidas, aplicada, "todas las migraciones corren en ${s.engine.label}")

                // Y las escrituras reales: un esquema que se crea pero no acepta un insert no
                // sirve de nada.
                val repos = io.acr.data.RepoRepository(st, io.acr.crypto.Secrets(tmp.resolve("master.key")))
                val id = repos.create(
                    "eng-${System.nanoTime()}", io.acr.forge.Provider.BITBUCKET, "acme", "demo",
                    tmp.toString(), null, null, null, "", false,
                    io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
                )
                assertTrue(repos.list().any { r -> r.id == id })

                // El upsert, que es lo que más cambia entre motores: dos veces la misma clave
                // tiene que dejar una fila, no dos ni un error.
                val prefs = io.acr.data.PrefsRepo(st)
                prefs.put("k1", "uno")
                prefs.put("k1", "dos")
                assertEquals("dos", prefs.get("k1"), "el upsert reemplaza en ${s.engine.label}")

                // Las consultas que usan funciones propias de SQLite —agrupar por período, restar
                // dos fechas, concatenar— son las que de verdad se traducen. Que el esquema se
                // cree no dice nada sobre ellas: hay que correrlas.
                val reviews = io.acr.data.ReviewRepository(st)
                assertTrue(reviews.statsByPeriod("month", 6).size >= 0, "agrupar por mes")
                assertTrue(reviews.statsByPeriod("week", 6).size >= 0, "agrupar por semana")
                reviews.currentPeriods()

                val prStats = io.acr.data.PrStatRepository(st)
                prStats.mergedDurationsByAuthor("2020-01-01", "2030-01-01")
                prStats.listByAuthor("nadie", "2020-01-01", "2030-01-01")

                val impls = io.acr.data.ImplRepository(st)
                impls.progressOfAll()

                // Dos veces el mismo día: es un upsert con clave compuesta, la forma que más
                // difiere entre motores.
                val salud = io.acr.data.RepoHealth(1, 2, 3, 4, 5, 6, 0.5, 7L, 8)
                io.acr.data.RepoHealthRepository(st).let { h ->
                    h.snapshot(id, salud)
                    h.snapshot(id, salud.copy(openPrs = 9))
                }
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun theWholeSchemaRunsOnPostgres() {
        if (!vivo("localhost", 55432)) return
        probar(
            DbSettings(
                engine = DbEngine.POSTGRES, host = "localhost", port = 55432,
                database = "acr", user = "acr", password = "acr",
            ),
        )
    }

    @Test
    fun theWholeSchemaRunsOnMysql() {
        if (!vivo("localhost", 33306)) return
        probar(
            DbSettings(
                engine = DbEngine.MYSQL, host = "localhost", port = 33306,
                database = "acr", user = "acr", password = "acr",
                params = "allowPublicKeyRetrieval=true&useSSL=false",
            ),
        )
    }

    /**
     * La mudanza de verdad: datos en SQLite, copiados a PostgreSQL, contados de los dos lados.
     *
     * Es el único test que dice algo sobre la portación. Contar filas en la base de origen y
     * confiar en que llegaron es exactamente el error que la portación tiene que no cometer.
     */
    @Test
    fun dataMovesFromSqliteToPostgres() {
        if (!vivo("localhost", 55432)) return
        val destino = DbSettings(
            engine = DbEngine.POSTGRES, host = "localhost", port = 55432,
            database = "acr", user = "acr", password = "acr",
        )
        limpiar(destino)

        val dir = java.nio.file.Files.createTempDirectory("acr-port")
        try {
            // Una base SQLite con algo adentro, incluido un token cifrado —bytes— y una review,
            // que es la tabla con más columnas y la que más importa no perder.
            val ctx = AppContext.bootstrap(dir)
            val repoId = ctx.repos.create(
                "port-demo", io.acr.forge.Provider.BITBUCKET, "acme", "demo",
                dir.toString(), "un-token-secreto", null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            ctx.prefs.put("algo", "guardado")
            val implId = ctx.impls.create(
                listOf(io.acr.impl.ImplRepo(repoId, io.acr.impl.RepoRole.OTHER, null)),
                "una implementación", listOf("/tmp/x.md"), null,
            )
            ctx.impls.savePlan(
                implId, "resumen", "rama", "develop", "fable",
                listOf(
                    io.acr.impl.ImplTask(
                        "", "", repoId, 1, "tarea", "detalle", emptyList(),
                        io.acr.impl.TaskSize.M, 20, io.acr.impl.TaskStatus.PENDING,
                        null, null, null, null, null, null,
                        steps = listOf(
                            io.acr.impl.ImplStep(
                                "", "", 1, "un paso", io.acr.impl.TaskStatus.PENDING,
                                null, "", null, null, null,
                            ),
                        ),
                    ),
                ),
            )
            ctx.close()

            val porter = io.acr.data.DbPorter()
            val res = porter.migrate(dir, DbSettings(), destino, io.acr.crypto.Secrets(dir.resolve("master.key")))
            val malas = res.filter { !it.ok }
            assertTrue(malas.isEmpty(), "todas las tablas cuadran: " + malas.joinToString { it.table + " " + it.error })
            assertTrue(res.sumOf { it.source } > 0, "algo se copió de verdad")

            // Y la app abre el destino y encuentra lo suyo, incluido el token descifrable: si los
            // bytes se hubieran copiado mal, esto es lo que lo delata.
            val movida = AppContext.bootstrap(dir)
            try {
                assertEquals(1, movida.repos.list().size)
                assertEquals("guardado", movida.prefs.get("algo"))
                assertEquals(
                    "un-token-secreto", movida.repos.list().first().token,
                    "el token cifrado sobrevive: si los bytes se copiaran mal, esto es lo que lo delata",
                )
                val tareas = movida.impls.tasks(implId)
                assertEquals(1, tareas.size)
                assertEquals(1, tareas.single().steps.size, "los pasos también viajan")
            } finally {
                movida.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
