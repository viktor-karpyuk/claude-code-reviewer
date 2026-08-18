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
 * El clon es `--local`: enlaza los objetos en vez de copiarlos. Medido sobre un repositorio real de
 * 1,7 GB: cuatro segundos, y de disco propio ocupa 36 MB —los archivos versionados— porque los 670 MB
 * de historial quedan enlazados. Sin eso, esto sería inviable.
 *
 * **Lo que el taller no se lleva es lo que no está versionado**: `target/`, `node_modules`, cachés de
 * compilación. Eso es bueno para el disco y tiene un costo real que conviene saber: la primera
 * compilación adentro del taller arranca en frío, así que una tarea que compila puede tardar más de
 * lo que tardaría en el clon del usuario. La estimación del plan no lo sabe.
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
     * Dónde vive de verdad este repositorio para esta implementación, ahora.
     *
     * Una sola función para toda la app —el motor y las pantallas— porque si el motor commitea en el
     * taller y la pantalla lee el clon, la pantalla muestra cero commits y cero diffs mientras el
     * trabajo avanza. Es la misma clase de error que "el tablero decía que todo había salido bien":
     * dos fuentes para una sola verdad.
     *
     * Mira el disco y no sólo la marca: cuando la implementación termina, el taller se borra y todo
     * pasa a estar en el clon. Preguntando por la marca, la pantalla seguiría buscando en una
     * carpeta que ya no existe.
     */
    fun dirFor(implId: String, useWorkspace: Boolean, repoName: String, localPath: String): File {
        if (useWorkspace) {
            val ws = repoDir(implId, repoName)
            if (ws.isDirectory) return ws
        }
        return File(localPath)
    }

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
            // El caso más común y el más fácil de arreglar: git no deja pisar la rama que el
            // destino tiene abierta. Decirlo con el nombre y la salida ahorra buscar el error.
            val abierta = Git.currentBranch(File(r.localPath)) == rama
            val detalle = if (abierta) {
                "el clon está parado en «$rama»: pasalo a otra rama y volvé a devolver."
            } else {
                res.output.take(300)
            }
            log("${r.name}: no pude devolver «$rama». $detalle")
            r.name to detalle
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
     * Cuánto se recupera borrando esto.
     *
     * Sumar todos los archivos daría un número enorme y falso: con `--local`, git **enlaza** los
     * objetos al repositorio original en vez de copiarlos, así que la mayoría de lo que se ve
     * adentro no ocupa disco propio. Medido de un caso real: `du` informa 708 MB para un clon cuyo
     * costo verdadero es 36 MB — casi veinte veces más. Con ese número a la vista, alguien borra un
     * taller para recuperar espacio que en realidad nunca gastó, y se lleva puesto el trabajo.
     *
     * Así que se suman sólo los archivos con un único enlace: exactamente lo que desaparecería.
     */
    private fun tamano(dir: File): Long = runCatching {
        dir.walkTopDown().filter { it.isFile }.sumOf { f ->
            val enlaces = runCatching {
                java.nio.file.Files.getAttribute(f.toPath(), "unix:nlink") as? Int
            }.getOrNull() ?: 1
            if (enlaces > 1) 0L else f.length()
        }
    }.getOrDefault(0)
}
