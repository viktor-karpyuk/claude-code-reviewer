package io.acr.stats

/**
 * Un commit con su volumen de cambio, separando el código del ruido generado.
 *
 * Las líneas van partidas en dos desde el origen y no sumadas: mezclarlas hace que un `package-
 * lock.json` regenerado pese más que un mes de trabajo, y una vez sumadas no hay forma de
 * separarlas después.
 */
data class CommitStat(
    val sha: String,
    val author: GitAuthor,
    val authoredAt: String,
    val files: Int,
    val added: Int,
    val deleted: Int,
    val generatedAdded: Int,
    val generatedDeleted: Int,
) {
    val touched: Int get() = added + deleted
}

/**
 * Qué archivos no cuentan como código escrito por una persona.
 *
 * Medido sobre `kubrik-erp-be` a 90 días: de 502.044 líneas, estos patrones excluyen 128.275
 * —el 25,6%—. Lo que infla no son las migraciones sino los datos sembrados de `db`, donde un solo archivo aporta
 * 40.505 líneas, y un prototipo de importación con 38.000 líneas de `.json` y `.csv`.
 *
 * Las migraciones de esquema NO se excluyen: son 14.615 líneas de cambio real y hay que
 * revisarlo. La distinción que importa es entre migración y semilla, no la extensión del archivo.
 */
val DEFAULT_GENERATED_PATTERNS = listOf(
    "**/seed/**",
    "**/fixtures/**",
    "**/testdata/**",
    "**/__snapshots__/**",
    "**/dist/**",
    "**/build/**",
    "**/node_modules/**",
    "**/vendor/**",
    "package-lock.json",
    "yarn.lock",
    "pnpm-lock.yaml",
    "*.min.js",
    "*.min.css",
    "*.png", "*.jpg", "*.jpeg", "*.gif", "*.svg", "*.ico", "*.pdf",
    "*.woff", "*.woff2", "*.ttf", "*.zip", "*.jar",
)

/**
 * ¿La ruta cae en alguno de los patrones?
 *
 * Glob mínimo y propio en vez de `PathMatcher` de Java: el de Java resuelve contra el sistema de
 * archivos y acá las rutas son las que devuelve git, que no existen en disco cuando el commit es
 * viejo o el archivo se borró.
 *
 * `**` cruza directorios, `*` no cruza `/`. Un patrón sin barra —`*.png`— se prueba también contra
 * el nombre del archivo solo, que es como uno espera que funcione al escribirlo.
 */
fun matchesGlob(path: String, pattern: String): Boolean {
    val regex = globToRegex(pattern)
    if (regex.matches(path)) return true
    return !pattern.contains('/') && regex.matches(path.substringAfterLast('/'))
}

private val cacheGlob = mutableMapOf<String, Regex>()

private fun globToRegex(pattern: String): Regex = cacheGlob.getOrPut(pattern) {
    val sb = StringBuilder("^")
    var i = 0
    while (i < pattern.length) {
        when (val c = pattern[i]) {
            '*' ->
                if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                    // Doble asterisco con barra cruza cero o más directorios, así que el
                    // patrón de una carpeta anidada toma también la que está en la raíz.
                    if (i + 2 < pattern.length && pattern[i + 2] == '/') {
                        sb.append("(?:.*/)?"); i += 2
                    } else {
                        sb.append(".*"); i++
                    }
                } else {
                    sb.append("[^/]*")
                }
            '?' -> sb.append("[^/]")
            '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' ->
                sb.append('\\').append(c)
            else -> sb.append(c)
        }
        i++
    }
    sb.append('$')
    Regex(sb.toString())
}

fun isGenerated(path: String, patterns: List<String> = DEFAULT_GENERATED_PATTERNS): Boolean =
    patterns.any { matchesGlob(path, it) }

/**
 * Parsea la salida de `git log --numstat` con el formato que usa [GIT_LOG_FORMAT].
 *
 * Se hace en una sola pasada de git y no un comando por commit: en estos repositorios son miles,
 * y mil invocaciones de git tardan minutos en vez de segundos.
 *
 * Los binarios vienen con `-` en vez de números; cuentan como cero líneas pero sí como archivo
 * tocado, que es lo que realmente pasó.
 */
fun parseGitLog(salida: String, patterns: List<String> = DEFAULT_GENERATED_PATTERNS): List<CommitStat> {
    val commits = mutableListOf<CommitStat>()
    var sha: String? = null
    var nombre = ""
    var email = ""
    var fecha = ""
    var files = 0
    var add = 0
    var del = 0
    var gadd = 0
    var gdel = 0

    fun cerrar() {
        sha?.let {
            commits += CommitStat(it, GitAuthor(nombre, email), fecha, files, add, del, gadd, gdel)
        }
        sha = null; files = 0; add = 0; del = 0; gadd = 0; gdel = 0
    }

    salida.lineSequence().forEach { linea ->
        if (linea.startsWith(COMMIT_MARK)) {
            cerrar()
            val partes = linea.removePrefix(COMMIT_MARK).split(UNIT_SEP)
            if (partes.size >= 4) {
                sha = partes[0]; nombre = partes[1]; email = partes[2]; fecha = partes[3]
            }
            return@forEach
        }
        if (linea.isBlank() || sha == null) return@forEach
        val campos = linea.split('\t')
        if (campos.size < 3) return@forEach
        val ruta = campos[2].trim().let {
            // Un rename viene como "viejo => nuevo" o "dir/{a => b}/f": interesa dónde quedó.
            if (it.contains("=>")) it.substringAfterLast("=>").trim().replace("}", "").replace("{", "")
            else it
        }
        val a = campos[0].toIntOrNull() ?: 0
        val d = campos[1].toIntOrNull() ?: 0
        files++
        if (isGenerated(ruta, patterns)) { gadd += a; gdel += d } else { add += a; del += d }
    }
    cerrar()
    return commits
}

/**
 * Marca de inicio de commit —RS (0x1e)— y separador de campos —US (0x1f)—.
 *
 * Caracteres de control ASCII y no una coma o un pipe porque los campos son texto libre: un nombre
 * puede tener comas y un mensaje de commit cualquier cosa. Estos dos no aparecen en texto escrito.
 */
const val COMMIT_MARK = "\u001e"
const val UNIT_SEP = '\u001f'

/** El formato que espera [parseGitLog]. */
const val GIT_LOG_FORMAT = "%x1e%H%x1f%an%x1f%ae%x1f%aI"
