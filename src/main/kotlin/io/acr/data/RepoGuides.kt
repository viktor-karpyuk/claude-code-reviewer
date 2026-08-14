package io.acr.data

import java.io.File
import java.security.MessageDigest

/** Un documento de convenciones encontrado dentro del repositorio, listo para importar. */
data class RepoGuide(
    val name: String,
    val path: String,
    val content: String,
) {
    val chars: Int get() = content.length
}

/**
 * Huella del contenido importado.
 *
 * Sirve para una sola pregunta: ¿el archivo cambió desde que se importó? Comparar el texto entero
 * también funcionaría, pero obliga a leer y guardar dos copias completas para responder que sí o
 * que no. SHA-256 sobre los bytes en UTF-8 alcanza y no depende del sistema de archivos: la fecha
 * de modificación cambia con un `git checkout` aunque el contenido sea idéntico, y ahí el aviso
 * sería ruido.
 */
fun guideHash(content: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * Busca documentos de convenciones dentro del clon local del repositorio.
 *
 * Busca `CLAUDE.md` en la raíz y en los subdirectorios de primer y segundo nivel, que es donde
 * viven de verdad: en un repo modular hay uno en la raíz y uno por proyecto —el caso real tiene
 * `CLAUDE.md` arriba y otro en el subproyecto de frontend, con reglas distintas—. No recorre todo
 * el árbol porque en un monorepo eso son decenas de miles de directorios para encontrar tres
 * archivos, y porque más abajo del segundo nivel un CLAUDE.md ya es de un módulo puntual y no un
 * criterio general de revisión.
 *
 * Devuelve la lista vacía si la ruta no existe o no se puede leer: un repositorio recién agregado
 * puede no estar clonado todavía, y eso no es un error que valga interrumpir a nadie.
 */
fun findRepoGuides(localPath: String, maxDepth: Int = 2): List<RepoGuide> {
    val raiz = runCatching { File(localPath) }.getOrNull() ?: return emptyList()
    if (!raiz.isDirectory) return emptyList()

    val encontrados = mutableListOf<RepoGuide>()

    fun revisar(dir: File, profundidad: Int) {
        if (profundidad > maxDepth) return
        val hijos = dir.listFiles() ?: return
        hijos.filter { it.isFile && it.name.equals("CLAUDE.md", ignoreCase = true) }
            .forEach { f ->
                val texto = runCatching { f.readText() }.getOrNull()
                if (!texto.isNullOrBlank()) {
                    // El nombre lleva la ruta relativa cuando no está en la raíz: con tres
                    // archivos llamados "CLAUDE.md" en la lista, el nombre solo no distingue nada.
                    val rel = f.relativeTo(raiz).path
                    encontrados += RepoGuide(name = rel, path = f.absolutePath, content = texto)
                }
            }
        hijos.filter { it.isDirectory && !it.isHidden && it.name !in SALTAR }
            .forEach { revisar(it, profundidad + 1) }
    }

    runCatching { revisar(raiz, 0) }
    return encontrados.sortedBy { it.name.count { c -> c == File.separatorChar } }
}

/**
 * Directorios que nunca contienen convenciones del equipo y sí muchísimos archivos. Recorrerlos
 * es tiempo puro: `node_modules` solo ya son decenas de miles de carpetas.
 */
private val SALTAR = setOf(
    "node_modules", "build", "target", "dist", "out", ".git", ".gradle", ".idea", "vendor",
)

/**
 * Estado de una guía importada frente a su archivo de origen.
 *
 * No se re-lee el archivo para la review —eso lo decidió la migración v34 y sigue vigente: si el
 * contenido saliera del disco, cambiar de rama cambiaría las reglas de revisión sin aviso—. Esto
 * es sólo para mostrar.
 */
enum class LinkState {
    /** No vino de un archivo: se subió a mano. */
    NOT_LINKED,

    /** El archivo sigue igual que cuando se importó. */
    IN_SYNC,

    /** El archivo cambió: la guía que se usa quedó vieja. */
    STALE,

    /** El archivo ya no está donde estaba. Se sigue usando lo guardado. */
    MISSING,
}

/**
 * Compara una guía importada contra su archivo, sin tocar la base.
 *
 * @param leer inyectable para poder probar los cuatro estados sin fabricar archivos.
 */
fun linkStateOf(
    linkedPath: String?,
    linkedHash: String?,
    leer: (String) -> String? = { p -> runCatching { File(p).takeIf { it.isFile }?.readText() }.getOrNull() },
): LinkState {
    if (linkedPath.isNullOrBlank() || linkedHash.isNullOrBlank()) return LinkState.NOT_LINKED
    val actual = leer(linkedPath) ?: return LinkState.MISSING
    return if (guideHash(actual) == linkedHash) LinkState.IN_SYNC else LinkState.STALE
}
