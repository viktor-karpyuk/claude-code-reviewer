package io.acr.data

import java.nio.file.Files
import java.nio.file.Path

/** Los motores que la app sabe usar. */
enum class DbEngine {
    /** El de fábrica: un archivo, sin instalar nada, sin configurar nada. */
    SQLITE,
    POSTGRES,
    MYSQL,
    ;

    val label: String
        get() = when (this) {
            SQLITE -> "SQLite"
            POSTGRES -> "PostgreSQL"
            MYSQL -> "MySQL"
        }

    /** El puerto que casi siempre es. Se ofrece como valor inicial, no se impone. */
    val defaultPort: Int
        get() = when (this) {
            SQLITE -> 0
            POSTGRES -> 5432
            MYSQL -> 3306
        }
}

/**
 * A qué base se conecta la app.
 *
 * Vive en un archivo y no en la base, por razones obvias: es lo que hay que leer para saber a qué
 * base conectarse. Va en JSON plano al lado de la clave maestra, en el directorio de datos.
 *
 * La contraseña se guarda cifrada con la misma clave maestra que los tokens de los proveedores.
 * No es un secreto menor —da acceso a toda la base— y dejarla en claro en un archivo de
 * configuración sería peor que lo que ya se hace con todo lo demás.
 */
data class DbSettings(
    val engine: DbEngine = DbEngine.SQLITE,
    val host: String = "localhost",
    val port: Int = 5432,
    val database: String = "acr",
    val user: String = "",
    val password: String = "",
    /**
     * Parámetros extra de la URL, para casos que no se pueden anticipar: `sslmode=require`,
     * `serverTimezone=UTC`, el pooler de un proveedor. Sin esto, un detalle de conexión que
     * falta convierte "elegí tu base" en "elegí tu base si es como la mía".
     */
    val params: String = "",
) {
    val isServer: Boolean get() = engine != DbEngine.SQLITE

    /** La URL JDBC. [sqlitePath] sólo se usa cuando el motor es el de fábrica. */
    fun jdbcUrl(sqlitePath: Path): String = when (engine) {
        DbEngine.SQLITE -> "jdbc:sqlite:$sqlitePath"
        DbEngine.POSTGRES -> "jdbc:postgresql://$host:$port/$database" + query()
        DbEngine.MYSQL -> "jdbc:mysql://$host:$port/$database" + query()
    }

    private fun query(): String = params.trim().trim('?', '&').takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()

    /** Cómo se describe en una pantalla, sin la contraseña. */
    fun describe(): String =
        if (!isServer) engine.label else "${engine.label} · $user@$host:$port/$database"

    /** Lo que falta para poder conectar, o null si está completo. */
    fun missing(): String? = when {
        !isServer -> null
        host.isBlank() -> "host"
        port !in 1..65535 -> "puerto"
        database.isBlank() -> "base"
        user.isBlank() -> "usuario"
        else -> null
    }

    companion object {
        const val FILE = "db.json"

        /**
         * Lee la configuración del directorio de datos.
         *
         * Ante un archivo ilegible se vuelve a SQLite en vez de fallar: la app tiene que poder
         * abrir siempre. Quedarse sin arrancar por un JSON roto dejaría sin acceso justo a la
         * pantalla donde se arregla.
         */
        fun load(dir: Path, secrets: io.acr.crypto.Secrets?): DbSettings {
            val f = dir.resolve(FILE)
            if (!Files.exists(f)) return DbSettings()
            return runCatching {
                val o = kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(f))
                    .let { it as kotlinx.serialization.json.JsonObject }
                fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                val motor = runCatching { DbEngine.valueOf(str("engine")) }.getOrDefault(DbEngine.SQLITE)
                DbSettings(
                    engine = motor,
                    host = str("host").ifBlank { "localhost" },
                    port = str("port").toIntOrNull() ?: motor.defaultPort,
                    database = str("database").ifBlank { "acr" },
                    user = str("user"),
                    // Si la clave maestra cambió, la contraseña descifra a basura. Se prefiere
                    // vacía: pedirla de nuevo es molesto, conectar con basura da un error que no
                    // se entiende.
                    password = str("password").takeIf { it.isNotBlank() }?.let { cifrada ->
                        runCatching {
                            secrets?.decrypt(java.util.Base64.getDecoder().decode(cifrada)).orEmpty()
                        }.getOrDefault("")
                    }.orEmpty(),
                    params = str("params"),
                )
            }.getOrDefault(DbSettings())
        }

        fun save(dir: Path, settings: DbSettings, secrets: io.acr.crypto.Secrets?) {
            val cifrada = settings.password.takeIf { it.isNotBlank() && secrets != null }
                ?.let { java.util.Base64.getEncoder().encodeToString(secrets!!.encrypt(it)) }
                .orEmpty()
            val json = kotlinx.serialization.json.JsonObject(
                mapOf(
                    "engine" to kotlinx.serialization.json.JsonPrimitive(settings.engine.name),
                    "host" to kotlinx.serialization.json.JsonPrimitive(settings.host),
                    "port" to kotlinx.serialization.json.JsonPrimitive(settings.port.toString()),
                    "database" to kotlinx.serialization.json.JsonPrimitive(settings.database),
                    "user" to kotlinx.serialization.json.JsonPrimitive(settings.user),
                    "password" to kotlinx.serialization.json.JsonPrimitive(cifrada),
                    "params" to kotlinx.serialization.json.JsonPrimitive(settings.params),
                ),
            )
            Files.createDirectories(dir)
            // Se escribe a un temporal y se mueve: si la app muere a mitad de la escritura, el
            // archivo viejo sigue entero. Un db.json truncado deja la app sin saber a dónde ir.
            val tmp = Files.createTempFile(dir, ".db", ".tmp")
            Files.writeString(tmp, json.toString())
            Files.move(
                tmp, dir.resolve(FILE),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }
    }
}
