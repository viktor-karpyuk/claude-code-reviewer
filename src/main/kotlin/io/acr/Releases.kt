package io.acr

/** Una versión publicada, con lo que cambió. */
data class ReleaseNote(val version: String, val body: String)

/**
 * Las novedades de cada versión, leídas del changelog que viaja en el paquete.
 *
 * Va empaquetado y no se descarga: la pantalla tiene que funcionar sin red, y una app que para
 * contarte qué cambió necesita internet no sirve justo cuando algo anda mal.
 */
object Releases {

    /** Dónde viven las versiones publicadas. */
    const val URL = "https://github.com/viktor-karpyuk/claude-code-reviewer/releases"

    /** El link a una versión puntual. Sirve para bajar el instalador de esa. */
    fun urlFor(version: String) = "$URL/tag/v$version"

    private val cache: List<ReleaseNote> by lazy { parse(leer()) }

    fun all(): List<ReleaseNote> = cache

    /** Lo que trajo la versión que está corriendo, si el changelog la conoce. */
    fun current(): ReleaseNote? = cache.firstOrNull { it.version == AppVersion.value }

    private fun leer(): String =
        runCatching {
            Releases::class.java.getResourceAsStream("/acr-changelog.md")
                ?.bufferedReader()?.use { it.readText() }
        }.getOrNull().orEmpty()

    /**
     * Parte el changelog por sus encabezados `## <versión>`.
     *
     * Tolerante a propósito: si el formato cambia o el archivo no está, devuelve una lista vacía y
     * la pantalla muestra el link. Que una pantalla informativa rompa la app sería absurdo.
     */
    internal fun parse(texto: String): List<ReleaseNote> {
        if (texto.isBlank()) return emptyList()
        val encabezado = Regex("^## (\\d+\\.\\d+\\.\\d+)\\s*$", RegexOption.MULTILINE)
        val marcas = encabezado.findAll(texto).toList()
        return marcas.mapIndexed { i, m ->
            val desde = m.range.last + 1
            val hasta = if (i + 1 < marcas.size) marcas[i + 1].range.first else texto.length
            ReleaseNote(m.groupValues[1], texto.substring(desde, hasta).trim())
        }
    }
}
