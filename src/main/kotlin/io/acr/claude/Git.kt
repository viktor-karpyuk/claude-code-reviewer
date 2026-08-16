package io.acr.claude

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

object Git {

    data class Result(val ok: Boolean, val output: String)

    fun isRepo(dir: File): Boolean = dir.isDirectory && File(dir, ".git").exists()

    /**
     * Brings the two branches the review needs into the local clone. Without this the diff range
     * resolves against stale refs and the review silently describes an older version of the PR.
     */
    suspend fun fetch(dir: File, vararg branches: String): Result = withContext(Dispatchers.IO) {
        run(dir, listOf("git", "fetch", "origin") + branches.filter { it.isNotBlank() })
    }

    suspend fun headOf(dir: File, branch: String): String? = withContext(Dispatchers.IO) {
        run(dir, listOf("git", "rev-parse", "origin/$branch")).takeIf { it.ok }?.output?.trim()
    }

    /**
     * ¿[maybeAncestor] está en la historia de [descendant]?
     *
     * Es la pregunta que decide si una review incremental es posible: si el commit que revisamos
     * sigue siendo ancestro del actual, `viejo..nuevo` son exactamente los commits que llegaron
     * después. Si el autor rebasó o forzó el push, ese commit ya no está en la historia y el rango
     * daría vacío o basura: la review miraría cualquier cosa menos el cambio.
     *
     * `--is-ancestor` contesta por código de salida: 0 sí, 1 no, otro para errores. Un objeto que
     * no está en el clon es error, no un "no", pero acá los dos llevan al mismo lado —mirar todo—
     * así que alcanza con distinguir el 0.
     */
    suspend fun isAncestor(dir: File, maybeAncestor: String, descendant: String): Boolean =
        withContext(Dispatchers.IO) {
            if (maybeAncestor.isBlank() || descendant.isBlank()) return@withContext false
            run(dir, listOf("git", "merge-base", "--is-ancestor", maybeAncestor, descendant)).ok
        }

    /** Un archivo del diff con sus líneas agregadas y borradas. */
    data class FileChange(val path: String, val added: Int, val deleted: Int) {
        val touched: Int get() = added + deleted
    }

    /**
     * `--numstat` da archivos y líneas en una sola llamada. Los binarios vienen con "-" en vez
     * de números y se cuentan como 0 líneas: no aportan a la complejidad de la review.
     */
    suspend fun numstat(dir: File, range: String): List<FileChange> = withContext(Dispatchers.IO) {
        val res = run(dir, listOf("git", "diff", "--numstat", range))
        if (!res.ok) return@withContext emptyList()
        res.output.lineSequence().mapNotNull { line ->
            val parts = line.trim().split('\t')
            if (parts.size < 3) return@mapNotNull null
            FileChange(
                // En renombres git emite "viejo => nuevo" o "{a => b}/f.kt"; interesa el destino,
                // que es el unico que existe en la rama del PR y sirve como pathspec.
                path = resolveRenamed(parts[2]),
                added = parts[0].toIntOrNull() ?: 0,
                deleted = parts[1].toIntOrNull() ?: 0,
            )
        }.toList()
    }

    /** Un commit del PR. */
    data class Commit(
        val sha: String,
        val author: String,
        val date: String,
        val subject: String,
        val body: String,
    )

    /**
     * Commits que el PR agrega sobre la rama destino. Se usa `..` y no `...`: interesa lo que
     * trae la rama, no el merge-base completo.
     */
    suspend fun commits(dir: File, target: String, source: String): List<Commit> =
        withContext(Dispatchers.IO) {
            // %x1f separa campos y %x1e registros: cualquier separador imprimible podría aparecer
            // dentro de un asunto o cuerpo de commit y romper el parseo.
            val fmt = "%H%x1f%an%x1f%ad%x1f%s%x1f%b%x1e"
            val res = run(
                dir,
                listOf("git", "log", "--date=short", "--format=$fmt", "origin/$target..origin/$source"),
            )
            if (!res.ok) return@withContext emptyList()
            res.output.split('\u001e')
                .map { it.trim('\n', '\r', ' ') }
                .filter { it.isNotBlank() }
                .mapNotNull { rec ->
                    val f = rec.split('\u001f')
                    if (f.size < 4) return@mapNotNull null
                    Commit(f[0], f[1], f[2], f[3], f.getOrElse(4) { "" }.trim())
                }
        }

    /** Archivos tocados por un commit puntual. */
    suspend fun commitFiles(dir: File, sha: String): List<FileChange> = withContext(Dispatchers.IO) {
        val res = run(dir, listOf("git", "show", "--numstat", "--format=", sha))
        if (!res.ok) return@withContext emptyList()
        res.output.lineSequence().mapNotNull { line ->
            val parts = line.trim().split('\t')
            if (parts.size < 3) return@mapNotNull null
            FileChange(resolveRenamed(parts[2]), parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0)
        }.toList()
    }

    /** Diff de un archivo dentro de un commit. */
    suspend fun commitDiff(dir: File, sha: String, path: String): String = withContext(Dispatchers.IO) {
        run(dir, listOf("git", "show", "--unified=5", "--format=", sha, "--", path)).output
    }

    internal fun resolveRenamed(raw: String): String {
        if (" => " !in raw) return raw
        val brace = Regex("[{]([^{}]*) => ([^{}]*)[}]").find(raw)
        if (brace != null) {
            return raw.replaceRange(brace.range, brace.groupValues[2]).replace("//", "/")
        }
        return raw.substringAfter(" => ")
    }

    /** Cantidad de líneas del archivo en una rama; 0 si no se puede determinar. */
    suspend fun fileLineCount(dir: File, branch: String, path: String): Int =
        withContext(Dispatchers.IO) {
            val res = run(dir, listOf("git", "show", "origin/$branch:$path"))
            if (!res.ok) 0 else res.output.count { it == '\n' } + 1
        }

    /** Diff unificado de un solo archivo, con 5 líneas de contexto para que se lea como en Bitbucket. */
    suspend fun diffFile(dir: File, range: String, path: String): String = withContext(Dispatchers.IO) {
        run(dir, listOf("git", "diff", "--unified=5", range, "--", path)).output
    }

    /**
     * Corre un comando de git y devuelve su salida cruda.
     *
     * Lo usa la recolección de estadísticas, que necesita un `git log` con un formato propio y no
     * encaja en ninguno de los helpers de arriba. Sigue siendo sólo lectura.
     */
    suspend fun runRaw(dir: File, cmd: List<String>): Result = withContext(Dispatchers.IO) {
        run(dir, cmd)
    }

    /**
     * ¿Ese objeto está en el clon?
     *
     * Se pregunta antes de continuar desde un commit anotado en una corrida anterior: si la rama
     * se reescribió o el clon se rehizo, ese commit ya no existe y seguir desde ahí dejaría un
     * agujero en el medio del historial sin que nadie se entere.
     */
    suspend fun exists(dir: File, sha: String): Boolean = withContext(Dispatchers.IO) {
        if (sha.isBlank()) return@withContext false
        run(dir, listOf("git", "cat-file", "-e", "$sha^{commit}")).ok
    }

    /** La rama en la que está parado el clon. */
    suspend fun currentBranch(dir: File): String? = withContext(Dispatchers.IO) {
        run(dir, listOf("git", "rev-parse", "--abbrev-ref", "HEAD"))
            .takeIf { it.ok }?.output?.trim()?.takeIf { it.isNotBlank() && it != "HEAD" }
    }

    /** ¿Hay cambios sin commitear? Escribir encima de trabajo ajeno se los llevaría en el commit. */
    suspend fun isDirty(dir: File): Boolean = withContext(Dispatchers.IO) {
        run(dir, listOf("git", "status", "--porcelain")).output.isNotBlank()
    }

    /**
     * Se para en [branch], creándola desde [base] si no existe.
     *
     * Si ya existe se reusa en vez de crear una variante: retomar una implementación frenada es el
     * caso normal, y una `feature-x-2` dejaría el trabajo partido en dos ramas que después hay que
     * unir a mano.
     */
    suspend fun checkoutBranch(dir: File, branch: String, base: String): Result =
        withContext(Dispatchers.IO) {
            val existe = run(dir, listOf("git", "rev-parse", "--verify", branch)).ok
            if (existe) run(dir, listOf("git", "checkout", branch))
            else run(dir, listOf("git", "checkout", "-b", branch, base))
        }

    /**
     * Commitea todo lo que haya, y devuelve el sha.
     *
     * Lo hace la herramienta y no el modelo: así el mensaje es uniforme y, sobre todo, no depende
     * de que el modelo se acuerde de hacerlo. Sin un commit por tarea, una falla en la séptima se
     * lleva puesto todo lo anterior.
     */
    suspend fun commitAll(dir: File, message: String): String? = withContext(Dispatchers.IO) {
        run(dir, listOf("git", "add", "-A"))
        val res = run(dir, listOf("git", "commit", "-m", message))
        if (!res.ok) return@withContext null
        run(dir, listOf("git", "rev-parse", "HEAD")).takeIf { it.ok }?.output?.trim()
    }

    /**
     * Qué archivos tocó un commit, con su estado y sus líneas.
     *
     * Dos llamadas y no una: `--numstat` da las líneas y `--name-status` la letra —A, M, D—, y git
     * no las mezcla en una salida que se pueda parsear sin ambigüedad. Se hace una sola vez, al
     * commitear, no cada vez que alguien mira la pantalla.
     */
    suspend fun commitStats(dir: File, sha: String): List<io.acr.impl.FileChange> =
        withContext(Dispatchers.IO) {
            val nums = run(dir, listOf("git", "show", "--numstat", "--format=", sha))
            if (!nums.ok) return@withContext emptyList()
            val estados = run(dir, listOf("git", "show", "--name-status", "--format=", sha))
                .takeIf { it.ok }?.output.orEmpty()
                .lineSequence().mapNotNull { l ->
                    val p = l.trim().split('\t')
                    if (p.size < 2) null else resolveRenamed(p.last()) to p[0].firstOrNull()
                }.toMap()

            nums.output.lineSequence().mapNotNull { linea ->
                val p = linea.trim().split('\t')
                if (p.size < 3) return@mapNotNull null
                val ruta = resolveRenamed(p[2])
                io.acr.impl.FileChange(
                    // Si no se encontró la letra se asume modificado: es el caso más común y el
                    // menos afirmativo de los tres.
                    status = estados[ruta] ?: 'M',
                    path = ruta,
                    added = p[0].toIntOrNull() ?: 0,
                    deleted = p[1].toIntOrNull() ?: 0,
                )
            }.toList()
        }

    /**
     * Guarda lo que haya sin commitear, incluidos los archivos nuevos.
     *
     * `-u` para que los archivos sin seguimiento también entren: si quedaran afuera, el primer
     * commit de la implementación se los llevaría adentro, que es justo lo que se quiere evitar.
     * Nunca descarta: lo guardado se recupera con `git stash pop`.
     */
    suspend fun stash(dir: File, message: String): Boolean = withContext(Dispatchers.IO) {
        run(dir, listOf("git", "stash", "push", "-u", "-m", message)).ok
    }

    /** Las ramas locales del clon, para elegir de cuál partir. */
    suspend fun branches(dir: File): List<String> = withContext(Dispatchers.IO) {
        val res = run(dir, listOf("git", "for-each-ref", "--format=%(refname:short)", "refs/heads"))
        if (!res.ok) emptyList()
        else res.output.lines().map { it.trim() }.filter { it.isNotBlank() }.sorted()
    }

    /**
     * Los commits que una rama agrega sobre su base.
     *
     * Es lo que construyó una implementación: sin esto hay que ir a la terminal para ver qué se
     * hizo, que es exactamente el viaje que la herramienta debería ahorrar.
     */
    suspend fun commitsBetween(dir: File, base: String, branch: String): List<Commit> =
        withContext(Dispatchers.IO) {
            val fmt = "%H%x1f%an%x1f%ad%x1f%s%x1f%b%x1e"
            val res = run(dir, listOf("git", "log", "--date=short", "--format=$fmt", "$base..$branch"))
            if (!res.ok) return@withContext emptyList()
            res.output.split('\u001e')
                .map { it.trim('\n', '\r', ' ') }
                .filter { it.isNotBlank() }
                .mapNotNull { rec ->
                    val f = rec.split('\u001f')
                    if (f.size < 4) return@mapNotNull null
                    Commit(f[0], f[1], f[2], f[3], f.getOrElse(4) { "" }.trim())
                }
        }

    /** El commit en el que está parado el clon. Sirve para anotar hasta dónde se procesó. */
    suspend fun currentHead(dir: File): String? = withContext(Dispatchers.IO) {
        run(dir, listOf("git", "rev-parse", "HEAD")).takeIf { it.ok }?.output?.trim()
    }

    private fun run(dir: File, cmd: List<String>): Result {
        return runCatching {
            val pb = ProcessBuilder(withGlobalFlags(cmd)).directory(dir).redirectErrorStream(true)
            // Sin esto, un `git fetch` que necesite credenciales o confirmar una host key SSH se
            // queda esperando input para siempre. Con esto falla rapido y con mensaje.
            pb.environment()["GIT_TERMINAL_PROMPT"] = "0"
            pb.environment()["GIT_ASKPASS"] = "true"
            // Sólo se impone BatchMode si el usuario no configuró su propio SSH. putIfAbsent
            // no alcanzaba: mira la variable de entorno, pero lo habitual es configurarlo con
            // `git config core.sshCommand`, que GIT_SSH_COMMAND pisaría — perdiendo su bastion
            // o su identity file y fallando con un error de conexión que no delata la causa.
            if (System.getenv("GIT_SSH_COMMAND") == null && !hasCustomSshCommand(dir)) {
                pb.environment()["GIT_SSH_COMMAND"] =
                    "ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new"
            }
            val p = pb.start()

            // Drenar en un hilo aparte: readText() solo vuelve al llegar a EOF, asi que si se
            // leyera aca el waitFor con timeout nunca se alcanzaria y el cuelgue seria infinito.
            val out = StringBuffer()
            val pump = Thread {
                runCatching { p.inputStream.bufferedReader().forEachLine { out.append(it).append(NL) } }
            }.apply { isDaemon = true; start() }

            val finished = p.waitFor(120, TimeUnit.SECONDS)
            if (!finished) p.destroyForcibly()
            pump.join(2_000)
            Result(finished && p.exitValue() == 0, out.toString().trim())
        }.getOrElse { Result(false, it.message ?: "git failed") }
    }

    private const val NL = '\n'

    /** ¿El repo (o la config global) define su propio comando SSH? */
    private fun hasCustomSshCommand(dir: File): Boolean = runCatching {
        val p = ProcessBuilder("git", "config", "--get", "core.sshCommand")
            .directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(5, TimeUnit.SECONDS)
        out.isNotBlank()
    }.getOrDefault(false)

    /**
     * core.quotePath=false evita que git devuelva rutas con acentos escapadas en octal y entre
     * comillas, que despues no matchean como pathspec y dejaban el visor de ese archivo vacio.
     */
    private fun withGlobalFlags(cmd: List<String>): List<String> =
        if (cmd.firstOrNull() == "git") listOf("git", "-c", "core.quotePath=false") + cmd.drop(1) else cmd
}
