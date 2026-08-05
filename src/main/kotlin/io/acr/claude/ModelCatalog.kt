package io.acr.claude

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Qué modelos ofrecer para elegir, y cómo nombrarlos.
 *
 * Claude Code no tiene ningún comando que enumere lo que muestra `/model`: `claude models` se
 * interpreta como un prompt y un `--model` inválido tampoco devuelve la lista de válidos. Así que
 * todo se descubre en runtime de fuentes autoritativas en la máquina del usuario —los alias que
 * documenta `--help`, el modelo configurado en `~/.claude/settings.json`, y los ids canónicos que
 * el propio binario del CLI conoce— en vez de clavar una lista que envejecería mal.
 */
object ModelCatalog {

    const val AUTO = ""

    /** Sólo se usa si `--help` cambia de forma y no se puede parsear nada. */
    private val FALLBACK = listOf("fable", "opus", "sonnet", "haiku")

    private val FAMILIES = listOf("opus", "sonnet", "haiku", "fable")

    @Volatile
    private var cachedAliases: List<String>? = null

    /** familia -> versión más alta conocida, p. ej. "opus" -> "5", "haiku" -> "4.5". */
    @Volatile
    private var cachedVersions: Map<String, String> = emptyMap()

    /**
     * @Volatile da visibilidad pero no exclusión: dos paneles abriendo el selector a la vez
     * disparaban dos escaneos del bundle de 259MB y dos subprocesos `--help` en paralelo.
     */
    private val discoverLock = kotlinx.coroutines.sync.Mutex()

    suspend fun discover(binary: String?): List<String> = withContext(Dispatchers.IO) {
        cachedAliases?.let { return@withContext it }
        discoverLock.lock()
        try {
        cachedAliases?.let { return@withContext it }
        val found = LinkedHashSet<String>()

        if (binary != null) {
            runCatching {
                val p = ProcessBuilder(binary, "--help").redirectErrorStream(true).start()
                val help = p.inputStream.bufferedReader().readText()
                p.waitFor(15, TimeUnit.SECONDS)
                found += parseHelp(help)
            }
            runCatching { cachedVersions = scanVersions(resolveRealBinary(binary)) }
        }
        runCatching {
            val settings = File(System.getProperty("user.home"), ".claude/settings.json")
            if (settings.isFile) {
                Regex(""""model"\s*:\s*"([^"]+)"""").find(settings.readText())
                    ?.groupValues?.get(1)?.let { found += it }
            }
        }
        // Las familias que el binario conoce se ofrecen aunque `--help` sólo las mencione de ejemplo.
        found += cachedVersions.keys
        if (found.isEmpty()) found += FALLBACK
        found.toList().also { cachedAliases = it }
        } finally {
            discoverLock.unlock()
        }
    }

    /**
     * El launcher suele ser un stub; el bundle real vive en ~/.claude/local/share. Se sigue el
     * symlink y, si no, se busca la versión instalada más nueva.
     */
    private fun resolveRealBinary(binary: String): File {
        val f = File(binary)
        val canonical = runCatching { f.canonicalFile }.getOrDefault(f)
        if (canonical.length() > 10_000_000) return canonical
        val versions = File(System.getProperty("user.home"), ".local/share/claude/versions")
        return versions.listFiles()?.maxByOrNull { it.lastModified() } ?: canonical
    }

    /**
     * Extrae los ids canónicos (`claude-opus-5`, `claude-haiku-4-5`) del bundle del CLI y se queda
     * con la versión más alta por familia. Es un archivo grande, así que se lee en bloques y una
     * sola vez por sesión.
     */
    internal fun scanVersions(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val re = Regex("claude-(opus|sonnet|haiku|fable)-(\\d+(?:-\\d+)?)(?![\\d-])")
        val best = mutableMapOf<String, List<Int>>()
        file.inputStream().buffered(1 shl 20).reader(Charsets.ISO_8859_1).use { reader ->
            val buf = CharArray(1 shl 20)
            var carry = ""
            while (true) {
                val n = reader.read(buf)
                if (n <= 0) break
                val chunk = carry + String(buf, 0, n)
                re.findAll(chunk).forEach { m ->
                    val family = m.groupValues[1]
                    val parts = m.groupValues[2].split('-').mapNotNull { it.toIntOrNull() }
                    // Los ids con fecha (claude-opus-4-20250514) no son una versión: se descartan.
                    if (parts.isEmpty() || parts.any { it > 1000 }) return@forEach
                    val current = best[family]
                    if (current == null || compareVersions(parts, current) > 0) best[family] = parts
                }
                // Solapar el final por si un id quedó cortado entre bloques.
                carry = chunk.takeLast(40)
            }
        }
        return best.mapValues { (_, v) -> v.joinToString(".") }
    }

    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /**
     * El bloque de `--model` documenta los alias entre comillas simples, por ejemplo:
     * "Provide an alias for the latest model (e.g. 'fable', 'opus', or 'sonnet') or a model's
     * full name (e.g. 'claude-fable-5')".
     */
    internal fun parseHelp(help: String): List<String> {
        val block = help.lineSequence()
            .dropWhile { !it.contains("--model") }
            .takeWhile { !it.trimStart().startsWith("--mcp") || it.contains("--model") }
            .joinToString(" ")
        if (block.isBlank()) return emptyList()
        return Regex("'([a-z0-9][a-z0-9.\\-]{1,40})'")
            .findAll(block)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }

    /**
     * Nombre para mostrar. Un alias pelado no dice de qué generación es, así que se le agrega la
     * versión que conoce el CLI: "opus" -> "Opus 5", "haiku" -> "Haiku 4.5", "opus[1m]" ->
     * "Opus 5 (1M contexto)".
     */
    fun label(model: String): String {
        if (model == AUTO) return "Auto (según nivel)"

        val suffix = Regex("\\[(\\w+)]$").find(model)?.groupValues?.get(1)
        val base = model.removeSuffix(suffix?.let { "[$it]" } ?: "")
        val extra = when (suffix?.lowercase()) {
            null -> ""
            "1m" -> " (1M contexto)"
            else -> " ($suffix)"
        }

        // Id canónico completo: claude-haiku-4-5 -> Haiku 4.5
        Regex("^claude-(\\w+)-(\\d+(?:-\\d+)?)$").find(base)?.let { m ->
            return m.groupValues[1].replaceFirstChar(Char::uppercase) +
                " " + m.groupValues[2].replace('-', '.') + extra
        }

        val family = base.lowercase()
        if (family in FAMILIES) {
            val pretty = family.replaceFirstChar(Char::uppercase)
            val version = cachedVersions[family]
            return if (version != null) "$pretty $version$extra" else pretty + extra
        }
        return model
    }

    fun forceRefresh() {
        cachedAliases = null
        cachedVersions = emptyMap()
    }
}
