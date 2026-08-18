package io.acr.impl

import io.acr.claude.Git
import io.acr.forge.RepoRecord
import java.io.File

/** Un repositorio dentro de un workspace: de dónde salió y dónde está la copia. */
data class WorkspaceRepo(
    val repoId: String,
    val name: String,
    /** El clon del usuario: de acá se clonó y acá vuelve el trabajo. */
    val origin: String,
    /** La copia donde se trabaja. */
    val path: String,
    val sizeBytes: Long,
    /** El sha de la rama de la implementación en la copia, si ya hay algo. */
    val head: String?,
    /** El mismo sha del otro lado. Iguales significa que el trabajo ya está a salvo. */
    val originHead: String?,
) {
    /**
     * ¿Todo lo de la copia ya está en el clon del usuario?
     *
     * Es la única pregunta que autoriza a borrar. Los dos shas iguales significan que la rama de
     * allá tiene exactamente lo mismo que la de acá; con `head` en null no hay nada que perder.
     */
    val synced: Boolean get() = head == null || head == originHead
}

/** El workspace de una implementación. */
data class Workspace(
    val implId: String,
    val implTitle: String,
    val path: String,
    val repos: List<WorkspaceRepo>,
    val exists: Boolean,
) {
    val sizeBytes: Long get() = repos.sumOf { it.sizeBytes }

    /** Se puede borrar cuando todo lo que tenía adentro ya está del otro lado. */
    val safeToDelete: Boolean get() = repos.all { it.synced }
}

/**
 * El taller aparte donde trabaja una implementación.
 *
 * Antes cada tarea escribía directamente en el clon del usuario, y eso traía tres problemas de
 * distinta gravedad: el árbol de trabajo de alguien se llenaba de cambios a mitad de camino; dos
 * implementaciones sobre el mismo repositorio se peleaban por esa única copia; y cualquier cosa que
 * el usuario tuviera sin commitear bloqueaba el arranque o quedaba mezclada con lo generado.
 *
 * Un workspace por implementación resuelve los tres. **Uno por implementación y no uno por tarea**:
 * dos tareas del mismo repositorio siguen sin poder correr a la vez —comparten el árbol— pero dos
 * implementaciones distintas ya no se estorban, que es donde estaba el choque real.
 *
 * El clon es `--local`: enlaza los objetos en vez de copiarlos, así que un repositorio de dos gigas
 * se clona en un segundo. Sin eso, esto sería inviable.
 *
 * **El trabajo no vive acá.** Al terminar se empuja cada rama al clon del usuario y recién entonces
 * se borra el workspace, después de comparar los dos shas. El orden importa: borrar primero y
 * verificar después es como se pierde el trabajo de una tarde.
 */
class Workspaces(private val root: File) {

    fun rootDir(): File = root

    fun dirOf(implId: String): File = File(root, implId)

    fun repoDir(implId: String, repoName: String): File = File(dirOf(implId), repoName)

    /**
     * Prepara el workspace: clona lo que falte y deja cada repositorio en la rama.
     *
     * Idempotente. Retomar una implementación es el caso normal, no la excepción: si el clon ya está
     * se reusa, y con él todo lo que las tareas anteriores dejaron commiteado.
     */
    suspend fun prepare(
        implId: String,
        repos: List<RepoRecord>,
        rama: String,
        baseDe: (String) -> String,
        log: (String) -> Unit = {},
    ): Result<Unit> = runCatching {
        dirOf(implId).mkdirs()
        repos.forEach { r ->
            val destino = repoDir(implId, r.name)
            if (!Git.isRepo(destino)) {
                log("Clonando ${r.name} al workspace…")
                val res = Git.cloneLocal(File(r.localPath), destino)
                if (!res.ok) error("No pude clonar ${r.name} al workspace: ${res.output.take(300)}")
            }
            // La rama, desde la base que corresponda. En un clon nuevo la base existe porque vino
            // con todo el historial del original.
            val base = baseDe(r.id)
            if (Git.currentBranch(destino) != rama) {
                Git.checkoutBranch(destino, rama, base)
                val quedo = Git.currentBranch(destino)
                if (quedo != rama) {
                    error("El workspace de ${r.name} no pudo pasar a «$rama» (quedó en «${quedo ?: "?"}»).")
                }
            }
        }
    }

    /**
     * Devuelve el trabajo al clon del usuario y dice qué pasó con cada repositorio.
     *
     * Empuja a una ruta local, no al remoto: subir es una decisión de una persona y esto no la toma.
     */
    suspend fun syncBack(
        implId: String,
        repos: List<RepoRecord>,
        rama: String,
        log: (String) -> Unit = {},
    ): List<Pair<String, String?>> = repos.map { r ->
        val ws = repoDir(implId, r.name)
        if (!Git.isRepo(ws)) return@map r.name to null
        if (!Git.hasBranch(ws, rama)) return@map r.name to null
        val res = Git.pushToLocal(ws, File(r.localPath), rama)
        val ok = res.ok || Git.branchHead(ws, rama) == Git.branchHead(File(r.localPath), rama)
        if (ok) {
            log("${r.name}: «$rama» quedó en el clon.")
            r.name to null
        } else {
            log("${r.name}: no pude devolver «$rama». ${res.output.take(200)}")
            r.name to res.output.take(300)
        }
    }

    /**
     * Borra el workspace, sólo si todo lo que tiene adentro ya está del otro lado.
     *
     * La verificación no es una formalidad: es lo único que separa "limpiar" de "perder una tarde de
     * trabajo". Si algo no cuadra, no se borra y se dice por qué.
     */
    suspend fun delete(
        implId: String,
        repos: List<RepoRecord>,
        rama: String?,
        force: Boolean = false,
    ): Result<Unit> = runCatching {
        val dir = dirOf(implId)
        if (!dir.exists()) return@runCatching
        if (!force && rama != null) {
            repos.forEach { r ->
                val ws = repoDir(implId, r.name)
                if (!Git.isRepo(ws)) return@forEach
                val aca = Git.branchHead(ws, rama)
                if (aca != null && aca != Git.branchHead(File(r.localPath), rama)) {
                    error(
                        "${r.name} tiene commits en el workspace que no están en el clon. " +
                            "Devolvelos antes de borrar.",
                    )
                }
            }
        }
        if (!dir.deleteRecursively()) error("No pude borrar $dir.")
    }

    /** Lo que hay en disco, para la pantalla de administración. */
    suspend fun inspect(
        implId: String,
        implTitle: String,
        repos: List<RepoRecord>,
        rama: String?,
    ): Workspace {
        val dir = dirOf(implId)
        val detalle = repos.map { r ->
            val ws = repoDir(implId, r.name)
            WorkspaceRepo(
                repoId = r.id,
                name = r.name,
                origin = r.localPath,
                path = ws.absolutePath,
                sizeBytes = if (ws.exists()) tamano(ws) else 0,
                head = rama?.takeIf { Git.isRepo(ws) }?.let { Git.branchHead(ws, it) },
                originHead = rama?.let { Git.branchHead(File(r.localPath), it) },
            )
        }
        return Workspace(implId, implTitle, dir.absolutePath, detalle, dir.exists())
    }

    /** Carpetas de workspace que no corresponden a ninguna implementación viva. */
    fun orphans(implIds: Set<String>): List<File> =
        root.listFiles()?.filter { it.isDirectory && it.name !in implIds }.orEmpty()

    /**
     * Cuánto ocupa, contando sólo archivos.
     *
     * Con `--local` la mayoría de los objetos son enlaces al repositorio original, así que este
     * número sobreestima el espacio real. Se muestra igual porque la pregunta que contesta —"¿esto
     * está creciendo?"— se responde bien con un número consistente, aunque sea alto.
     */
    private fun tamano(dir: File): Long =
        runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0)
}
