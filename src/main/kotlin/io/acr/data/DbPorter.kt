package io.acr.data

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Cómo le fue a una tabla. */
data class TableResult(
    val table: String,
    val source: Int,
    val target: Int,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null && source == target
}

/** Qué está pasando ahora, para la pantalla. */
data class PortProgress(
    val table: String = "",
    val tablesDone: Int = 0,
    val tables: Int = 0,
    val rows: Int = 0,
    val results: List<TableResult> = emptyList(),
    val finished: Boolean = false,
    val error: String? = null,
) {
    val fraction: Float get() = if (tables == 0) 0f else tablesDone.toFloat() / tables
}

/**
 * Copia los datos de una base a otra.
 *
 * Existe porque elegir motor sin poder mudarse no es una elección: quien arrancó con SQLite —o sea,
 * todos— tendría que empezar de cero para pasar a PostgreSQL, y empezar de cero significa perder
 * las reviews, las estadísticas y los hallazgos de meses. Eso convierte la opción en una que nadie
 * usa.
 *
 * Tres decisiones que hacen que esto sea seguro:
 *
 * 1. **Se copia a una base vacía.** Si el destino ya tiene filas, no se mezcla: mezclar dos bases
 *    con las mismas claves primarias termina en filas pisadas y en un resultado que nadie puede
 *    verificar. Se avisa y se pide vaciarla explícitamente.
 * 2. **Se cuenta todo, tabla por tabla, y se compara.** Una copia que dice "listo" sin haber
 *    contado no vale nada: los errores de este tipo son silenciosos por naturaleza —una tabla que
 *    quedó a medias parece completa hasta que alguien busca algo viejo—.
 * 3. **La app no se muda hasta que la copia cerró.** El archivo de configuración se escribe al
 *    final y sólo si todas las tablas coinciden. Si algo falla, la app sigue usando la base de
 *    siempre, intacta.
 *
 * Lo que NO hace: borrar la base de origen. Queda donde estaba, y es la red de seguridad.
 */
class DbPorter {

    /**
     * Las tablas en un orden donde ninguna llega antes que aquella de la que depende.
     *
     * Hace falta porque las claves foráneas del esquema rechazan una fila hija cuya madre todavía
     * no está. La alternativa —apagar la verificación de claves durante la copia— exige permisos de
     * superusuario que en una base administrada por otro no se tienen, así que el orden se calcula
     * y listo.
     */
    fun order(ddl: List<String>): List<String> {
        val deps = mutableMapOf<String, MutableSet<String>>()
        val texto = ddl.joinToString("\n")
        Regex("""CREATE TABLE (?:IF NOT EXISTS )?(\w+)\s*\((.*?)\n\s*\)""", setOf(RegexOption.DOT_MATCHES_ALL))
            .findAll(texto).forEach { m ->
                val tabla = m.groupValues[1].lowercase()
                val propias = deps.getOrPut(tabla) { mutableSetOf() }
                Regex("""REFERENCES\s+(\w+)""", RegexOption.IGNORE_CASE).findAll(m.groupValues[2])
                    .forEach { r ->
                        val madre = r.groupValues[1].lowercase()
                        // Una tabla que se referencia a sí misma no espera a nadie; tratarla como
                        // dependencia dejaría el orden sin solución.
                        if (madre != tabla) propias += madre
                    }
            }
        val listas = mutableListOf<String>()
        val pendientes = deps.keys.toMutableSet()
        while (pendientes.isNotEmpty()) {
            val siguientes = pendientes.filter { t -> deps[t].orEmpty().all { it in listas || it !in pendientes } }
            // Un ciclo dejaría esto sin avanzar. No debería pasar con este esquema, pero si pasara
            // es mejor copiar en un orden cualquiera y que falle una clave foránea —error visible—
            // que quedarse girando para siempre.
            if (siguientes.isEmpty()) {
                listas += pendientes.sorted()
                break
            }
            listas += siguientes.sorted()
            pendientes -= siguientes.toSet()
        }
        return listas
    }

    /** Abre una conexión cruda al destino, sin migrar ni traducir. */
    private fun open(s: DbSettings, sqlitePath: Path): Connection =
        DriverManager.getConnection(
            s.jdbcUrl(sqlitePath),
            s.user.takeIf { s.isServer },
            s.password.takeIf { s.isServer },
        )

    /**
     * Cuántas filas tiene cada tabla de una base. Vacío para las que no existen.
     *
     * Es lo que se muestra antes de empezar: nadie debería lanzar una copia sin ver primero qué se
     * va a copiar.
     */
    fun counts(conn: Connection, tables: List<String>): Map<String, Int> =
        tables.associateWith { t ->
            runCatching {
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM $t").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                }
            }.getOrDefault(0)
        }

    /**
     * Copia de [from] a [to] y devuelve el resultado por tabla.
     *
     * [onProgress] se llama con cada avance. No suspende ni salta de hilo: quien llama decide dónde
     * corre esto.
     */
    fun copy(
        from: Connection,
        to: Connection,
        tables: List<String>,
        onProgress: (PortProgress) -> Unit = {},
    ): List<TableResult> {
        val resultados = mutableListOf<TableResult>()
        val previo = to.autoCommit
        to.autoCommit = false
        try {
            tables.forEachIndexed { i, tabla ->
                onProgress(PortProgress(tabla, i, tables.size, 0, resultados.toList()))
                resultados += runCatching { copyTable(from, to, tabla, i, tables.size, onProgress) }
                    .getOrElse { e -> TableResult(tabla, -1, -1, e.message ?: e::class.simpleName) }
            }
            // Un solo commit al final: si algo se rompió en la tabla doce, no queda un destino con
            // once tablas copiadas que parece una base válida.
            if (resultados.all { it.ok }) to.commit() else to.rollback()
        } catch (e: Throwable) {
            runCatching { to.rollback() }
            throw e
        } finally {
            runCatching { to.autoCommit = previo }
        }
        return resultados
    }

    private fun copyTable(
        from: Connection,
        to: Connection,
        tabla: String,
        indice: Int,
        total: Int,
        onProgress: (PortProgress) -> Unit,
    ): TableResult {
        // Las columnas se leen del destino y no del origen: si el destino tiene una columna que el
        // origen no —porque se migró con una versión más nueva— copiar por nombre del origen sería
        // igual de correcto, pero al revés no: una columna del origen que el destino no conoce
        // rompería el insert, y es mejor enterarse acá que a mitad de la copia.
        val columnas = mutableListOf<String>()
        val tipos = mutableMapOf<String, Int>()
        to.metaData.getColumns(null, null, tabla, null).use { rs ->
            while (rs.next()) {
                val c = rs.getString("COLUMN_NAME")
                columnas += c
                tipos[c.lowercase()] = rs.getInt("DATA_TYPE")
            }
        }
        if (columnas.isEmpty()) return TableResult(tabla, 0, 0, "la tabla no existe en el destino")

        val enOrigen = runCatching {
            from.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM $tabla").use { if (it.next()) it.getInt(1) else 0 }
            }
        }.getOrDefault(0)
        if (enOrigen == 0) return TableResult(tabla, 0, 0)

        val lista = columnas.joinToString(", ")
        val huecos = columnas.joinToString(", ") { "?" }
        var copiadas = 0
        from.createStatement().use { st ->
            st.executeQuery("SELECT $lista FROM $tabla").use { rs ->
                to.prepareStatement("INSERT INTO $tabla($lista) VALUES ($huecos)").use { ps ->
                    while (rs.next()) {
                        columnas.forEachIndexed { j, c ->
                            val v = rs.getObject(j + 1)
                            when {
                                v == null -> ps.setNull(j + 1, tipos[c.lowercase()] ?: java.sql.Types.VARCHAR)
                                // Los bytes van explícitos: por setObject, el driver de PostgreSQL
                                // los manda como large object y la columna bytea los rechaza.
                                v is ByteArray -> ps.setBytes(j + 1, v)
                                else -> ps.setObject(j + 1, v)
                            }
                        }
                        ps.addBatch()
                        copiadas++
                        if (copiadas % 500 == 0) {
                            ps.executeBatch()
                            onProgress(PortProgress(tabla, indice, total, copiadas))
                        }
                    }
                    ps.executeBatch()
                }
            }
        }

        val enDestino = to.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM $tabla").use { if (it.next()) it.getInt(1) else 0 }
        }
        return TableResult(tabla, enOrigen, enDestino)
    }

    /**
     * La mudanza completa: copia, verifica y —sólo si todo cerró— deja la app apuntando al destino.
     *
     * Devuelve el detalle por tabla para poder mostrarlo. Si algo falló, la configuración no se
     * toca: la app sigue abriendo la base de siempre, que quedó intacta.
     */
    fun migrate(
        dataDir: Path,
        current: DbSettings,
        target: DbSettings,
        secrets: io.acr.crypto.Secrets?,
        onProgress: (PortProgress) -> Unit = {},
    ): List<TableResult> {
        val sqlitePath = dataDir.resolve("acr.db")
        val tablas = order(Store.MIGRATIONS)

        // El destino se migra primero, con el mismo camino que usa la app al arrancar. Así el
        // esquema es exactamente el mismo y no una versión escrita a mano que se desactualiza.
        Store(sqlitePath, target).use { }

        open(current, sqlitePath).use { origen ->
            open(target, sqlitePath).use { destino ->
                val yaHay = counts(destino, tablas).filterValues { it > 0 }
                if (yaHay.isNotEmpty()) {
                    val detalle = yaHay.entries.sortedByDescending { it.value }
                        .joinToString(", ") { "${it.key} (${it.value})" }
                    error(
                        "La base de destino ya tiene datos: $detalle. Vaciala antes de copiar: " +
                            "mezclar dos bases con las mismas claves deja filas pisadas y un " +
                            "resultado que no se puede verificar.",
                    )
                }
                val resultados = copy(origen, destino, tablas, onProgress)
                val fallidas = resultados.filter { !it.ok }
                if (fallidas.isNotEmpty()) {
                    onProgress(
                        PortProgress(
                            tables = tablas.size, tablesDone = tablas.size, results = resultados,
                            finished = true,
                            error = fallidas.joinToString("; ") {
                                it.error?.let { e -> "${it.table}: $e" } ?: "${it.table}: ${it.source} → ${it.target}"
                            },
                        ),
                    )
                    return resultados
                }
                DbSettings.save(dataDir, target, secrets)
                onProgress(
                    PortProgress(
                        tables = tablas.size, tablesDone = tablas.size,
                        results = resultados, finished = true,
                    ),
                )
                return resultados
            }
        }
    }

    /** Prueba una conexión y devuelve qué salió mal, o null si anduvo. */
    fun test(settings: DbSettings, sqlitePath: Path): String? = runCatching {
        open(settings, sqlitePath).use { c ->
            c.createStatement().use { it.executeQuery("SELECT 1").use { rs -> rs.next() } }
        }
        null
    }.getOrElse { it.message ?: it::class.simpleName }
}
