package io.acr.data

/**
 * Traduce el SQL de la app al dialecto del motor elegido.
 *
 * Toda la app está escrita en SQLite y va a seguir estándolo: es el motor de fábrica, el que corre
 * sin instalar nada, y el que usa el 99% de la gente. Reescribir cinco mil líneas de SQL a un
 * subconjunto común de tres motores habría empeorado el caso normal para servir al raro.
 *
 * Así que la traducción pasa acá, en el borde, y es chica a propósito: las diferencias que de
 * verdad importan son un puñado —los tipos del DDL, el upsert, dos funciones de fecha— y están
 * enumeradas abajo. Lo que no está enumerado no se traduce, y eso es deliberado: un traductor que
 * intenta entender SQL arbitrario se equivoca en silencio, y una consulta mal traducida devuelve
 * datos mal en vez de fallar.
 */
object Dialect {

    /**
     * Columnas que el esquema indexa o usa como clave.
     *
     * MySQL no puede indexar un `TEXT` sin decirle cuántos caracteres, así que estas van a
     * `VARCHAR` y el resto a `LONGTEXT`. Se calcula del propio DDL en vez de mantenerse a mano:
     * una lista escrita a mano se desactualiza en el primer índice nuevo, y el síntoma sería un
     * error de creación de tabla en la máquina de otro.
     */
    fun indexedColumns(ddl: List<String>): Set<String> {
        val cols = mutableSetOf<String>()
        val texto = ddl.joinToString("\n")
        Regex("""CREATE\s+(?:UNIQUE\s+)?INDEX\s+(?:IF NOT EXISTS\s+)?\w+\s+ON\s+\w+\s*\(([^)]*)\)""", RegexOption.IGNORE_CASE)
            .findAll(texto).forEach { m ->
                m.groupValues[1].split(',').forEach { c ->
                    c.trim().substringBefore(' ').takeIf { it.isNotBlank() }?.let { cols += it.lowercase() }
                }
            }
        // Claves y unicidad declaradas en la propia tabla, en línea o al final.
        texto.lines().forEach { l ->
            val linea = l.trim()
            Regex("""^(\w+)\s+(TEXT|INTEGER|REAL|BLOB)\b(.*)$""", RegexOption.IGNORE_CASE)
                .find(linea)?.let { m ->
                    if (Regex("PRIMARY KEY|UNIQUE|REFERENCES", RegexOption.IGNORE_CASE).containsMatchIn(m.groupValues[3])) {
                        cols += m.groupValues[1].lowercase()
                    }
                }
            Regex("""^(?:PRIMARY KEY|UNIQUE)\s*\(([^)]*)\)""", RegexOption.IGNORE_CASE)
                .find(linea)?.let { m ->
                    m.groupValues[1].split(',').forEach { c ->
                        c.trim().takeIf { it.isNotBlank() }?.let { cols += it.lowercase() }
                    }
                }
        }
        return cols
    }

    /**
     * Traduce una sentencia.
     *
     * [primaryKeys] devuelve las columnas de la clave primaria de una tabla; hace falta para el
     * upsert, porque `INSERT OR REPLACE` de SQLite no dice sobre qué choca y PostgreSQL sí lo
     * exige. Se resuelve contra el esquema real en vez de mantenerse una tabla a mano.
     */
    fun translate(
        sql: String,
        engine: DbEngine,
        indexed: Set<String>,
        primaryKeys: (String) -> List<String>,
    ): String {
        if (engine == DbEngine.SQLITE) return sql
        var s = sql
        s = upsert(s, engine, primaryKeys)
        s = ddl(s, engine, indexed)
        s = fechas(s, engine)
        if (engine == DbEngine.MYSQL) s = mysqlIdentificadores(s)
        if (engine == DbEngine.POSTGRES) s = postgres(s)
        return s
    }

    // --- upsert ---------------------------------------------------------------------------

    private val OR_REPLACE = Regex(
        """INSERT\s+OR\s+REPLACE\s+INTO\s+(\w+)\s*\(([^)]*)\)""",
        RegexOption.IGNORE_CASE,
    )
    private val OR_IGNORE = Regex("""INSERT\s+OR\s+IGNORE\s+INTO""", RegexOption.IGNORE_CASE)

    private fun upsert(sql: String, engine: DbEngine, primaryKeys: (String) -> List<String>): String {
        OR_REPLACE.find(sql)?.let { m ->
            val tabla = m.groupValues[1]
            val columnas = m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotBlank() }
            val pk = primaryKeys(tabla).map { it.lowercase() }
            val actualizables = columnas.filter { it.lowercase() !in pk }
            val cuerpo = OR_REPLACE.replace(sql) { "INSERT INTO $tabla(${it.groupValues[2]})" }
                .trimEnd().trimEnd(';')
            return when {
                // Sin clave conocida o sin nada que actualizar no hay conflicto que declarar, y el
                // upsert se degrada a un insert común. Preferible a inventar una clave: fallar con
                // "duplicate key" es un error que se entiende; escribir sobre la fila equivocada no.
                actualizables.isEmpty() -> cuerpo
                engine == DbEngine.MYSQL ->
                    cuerpo + " ON DUPLICATE KEY UPDATE " +
                        actualizables.joinToString(", ") { c -> "$c = VALUES($c)" }
                pk.isEmpty() -> cuerpo
                else ->
                    cuerpo + " ON CONFLICT (${pk.joinToString(", ")}) DO UPDATE SET " +
                        actualizables.joinToString(", ") { "$it = EXCLUDED.$it" }
            }
        }
        if (OR_IGNORE.containsMatchIn(sql)) {
            return when (engine) {
                DbEngine.MYSQL -> OR_IGNORE.replace(sql, "INSERT IGNORE INTO")
                else -> OR_IGNORE.replace(sql, "INSERT INTO").trimEnd().trimEnd(';') + " ON CONFLICT DO NOTHING"
            }
        }
        return sql
    }

    // --- DDL ------------------------------------------------------------------------------

    private fun ddl(sql: String, engine: DbEngine, indexed: Set<String>): String {
        if (!Regex("""^\s*(CREATE TABLE|ALTER TABLE)""", RegexOption.IGNORE_CASE).containsMatchIn(sql)) return sql
        var s = sql
        s = Regex("""\bBLOB\b""", RegexOption.IGNORE_CASE)
            .replace(s, if (engine == DbEngine.POSTGRES) "BYTEA" else "LONGBLOB")
        s = Regex("""\bREAL\b""", RegexOption.IGNORE_CASE)
            .replace(s, if (engine == DbEngine.POSTGRES) "DOUBLE PRECISION" else "DOUBLE")
        if (engine == DbEngine.MYSQL) {
            // Un TEXT indexado necesita largo; el resto puede ser largo de verdad. 255 caracteres
            // entran en el límite de clave de InnoDB incluso en un índice de tres columnas, y
            // ninguna de las columnas indexadas del esquema —ids, shas, claves, rutas— se acerca.
            s = Regex("""(\w+)(\s+)TEXT\b""", RegexOption.IGNORE_CASE).replace(s) { m ->
                val col = m.groupValues[1].lowercase()
                if (col in indexed) "${m.groupValues[1]}${m.groupValues[2]}VARCHAR(255)"
                else "${m.groupValues[1]}${m.groupValues[2]}LONGTEXT"
            }
            // Un LONGTEXT no puede tener DEFAULT en MySQL. Los defaults del esquema son cadenas
            // cortas de estado, así que la columna se vuelve VARCHAR en vez de perder el default.
            s = Regex("""(\w+)(\s+)LONGTEXT(\s+[^,\n]*DEFAULT)""", RegexOption.IGNORE_CASE)
                .replace(s) { m -> "${m.groupValues[1]}${m.groupValues[2]}VARCHAR(255)${m.groupValues[3]}" }
        }
        return s
    }

    // --- fechas ---------------------------------------------------------------------------

    /**
     * Las dos funciones de fecha de SQLite que la app usa.
     *
     * `strftime` para agrupar por período y `julianday` para restar dos instantes en días. Las
     * fechas se guardan como texto ISO-8601, así que en los otros motores hay que convertir antes
     * de operar.
     */
    private fun fechas(sql: String, engine: DbEngine): String {
        var s = sql
        val ahora = Regex("""'now'""", RegexOption.IGNORE_CASE)
        if (engine == DbEngine.POSTGRES) {
            s = Regex("""strftime\(\s*'([^']*)'\s*,\s*([^)]+)\)""", RegexOption.IGNORE_CASE).replace(s) { m ->
                val patron = pgPatron(m.groupValues[1])
                val arg = m.groupValues[2].trim()
                if (ahora.matches(arg)) "to_char(now(), '$patron')"
                else "to_char(($arg)::timestamp, '$patron')"
            }
            s = Regex("""julianday\(\s*([^)]+)\)""", RegexOption.IGNORE_CASE).replace(s) { m ->
                val arg = m.groupValues[1].trim()
                if (ahora.matches(arg)) "(EXTRACT(EPOCH FROM now()) / 86400.0)"
                else "(EXTRACT(EPOCH FROM ($arg)::timestamp) / 86400.0)"
            }
            // `date(x)` de SQLite recorta el día de un timestamp de texto y devuelve texto. En
            // PostgreSQL convierte a tipo `date`, y comparar eso contra el texto que devuelve el
            // resto de la traducción falla con "operator does not exist: date = text". Se traduce
            // a texto para que los dos lados de la comparación sean lo mismo.
            s = Regex("""\bdate\(\s*([^()]+?)\s*\)""", RegexOption.IGNORE_CASE).replace(s) { m ->
                val arg = m.groupValues[1].trim()
                if (ahora.matches(arg)) "to_char(now(), 'YYYY-MM-DD')"
                else "to_char(($arg)::timestamp, 'YYYY-MM-DD')"
            }
        }
        if (engine == DbEngine.MYSQL) {
            s = Regex("""strftime\(\s*'([^']*)'\s*,\s*([^)]+)\)""", RegexOption.IGNORE_CASE).replace(s) { m ->
                val patron = myPatron(m.groupValues[1])
                val arg = m.groupValues[2].trim()
                if (ahora.matches(arg)) "date_format(now(), '$patron')"
                else "date_format($arg, '$patron')"
            }
            s = Regex("""julianday\(\s*([^)]+)\)""", RegexOption.IGNORE_CASE).replace(s) { m ->
                val arg = m.groupValues[1].trim()
                if (ahora.matches(arg)) "(unix_timestamp(now()) / 86400.0)"
                else "(unix_timestamp($arg) / 86400.0)"
            }
            // Igual que en PostgreSQL: `date(x)` devuelve un tipo DATE y el resto de la
            // traducción devuelve texto. Se lleva todo a texto.
            s = Regex("""\bdate\(\s*([^()]+?)\s*\)""", RegexOption.IGNORE_CASE).replace(s) { m ->
                val arg = m.groupValues[1].trim()
                if (ahora.matches(arg)) "date_format(now(), '%Y-%m-%d')"
                else "date_format($arg, '%Y-%m-%d')"
            }
            // El `||` de SQLite concatena; en MySQL es un OR lógico y devolvería 0 en silencio,
            // que es el peor resultado posible: sin error y con los datos mal.
            s = concatenar(s)
        }
        return s
    }

    /**
     * `a || b` → `concat(a, b)`.
     *
     * Se hace sobre expresiones simples, que es lo único que el esquema usa: `repo_id||'#'||pr_id`.
     * Si apareciera un `||` en una expresión que este reemplazo no sabe leer, es preferible que
     * quede tal cual y falle a que se traduzca mal.
     */
    private fun concatenar(sql: String): String =
        Regex("""([\w.]+|'[^']*')\s*\|\|\s*([\w.]+|'[^']*')(\s*\|\|\s*([\w.]+|'[^']*'))*""")
            .replace(sql) { m ->
                val partes = m.value.split("||").map { it.trim() }
                "concat(" + partes.joinToString(", ") + ")"
            }

    /** Los patrones de `strftime` que el esquema usa, en la forma de PostgreSQL. */
    private fun pgPatron(p: String): String = p
        .replace("%Y", "YYYY").replace("%m", "MM").replace("%d", "DD")
        .replace("%W", "IW").replace("%H", "HH24").replace("%M", "MI").replace("%S", "SS")

    private fun myPatron(p: String): String = p
        .replace("%W", "%u") // en SQLite %W es el número de semana; en MySQL, el nombre del día
        .replace("%M", "%i") // y %M es el nombre del mes, no los minutos

    // --- detalles por motor ----------------------------------------------------------------

    /** `key` es palabra reservada en MySQL. Es la única del esquema, y va entre comillas. */
    private fun mysqlIdentificadores(sql: String): String =
        Regex("""(?<![\w.`'])key(?![\w`'])""", RegexOption.IGNORE_CASE).replace(sql, "`key`")

    /**
     * Detalles de PostgreSQL.
     *
     * `INTEGER` con valores 0/1 usados como booleano funciona igual, así que no se toca. Lo que sí
     * cambia es la comparación de un entero contra `true`, que PostgreSQL rechaza por tipos.
     */
    private fun postgres(sql: String): String = sql
        .replace(Regex("""\bAUTOINCREMENT\b""", RegexOption.IGNORE_CASE), "")
}
