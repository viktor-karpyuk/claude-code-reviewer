package io.acr.stats

import io.acr.claude.Git
import io.acr.data.CommitStatRepository
import io.acr.data.PersonRepository
import io.acr.forge.RepoRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Cómo viene saliendo la recolección, para mostrarla mientras corre. */
data class CollectProgress(
    val repoName: String,
    val commits: Int = 0,
    val people: Int = 0,
    val done: Boolean = false,
    val error: String? = null,
    /** Si sólo se leyó lo nuevo. Se muestra: "12 commits" significa otra cosa que "12 commits nuevos". */
    val incremental: Boolean = false,
)

/**
 * Recorre el historial de un repositorio y guarda un registro por commit.
 *
 * Se recolecta y se guarda en vez de calcular al vuelo porque un `git log` de años sobre siete
 * repositorios tarda demasiado para hacerlo cada vez que se abre una pantalla. Lo que no se guarda
 * son los totales por persona: esos se consultan, así una fusión de identidades se refleja sola.
 */
class StatsCollector(
    private val people: PersonRepository,
    private val commits: CommitStatRepository,
) {

    /**
     * Trae el historial de [repo] y lo guarda.
     *
     * @param since límite hacia atrás en formato de git —"2024-01-01", "12 months ago"—. Null trae
     *   todo, que la primera vez son miles de commits y por eso se confirma antes.
     * @param onProgress se llama cada tanto; un historial largo no puede parecer colgado.
     */
    suspend fun collect(
        repo: RepoRecord,
        since: String? = null,
        onProgress: (CollectProgress) -> Unit = {},
    ): CollectProgress = withContext(Dispatchers.IO) {
        val dir = File(repo.localPath)
        if (!Git.isRepo(dir)) {
            return@withContext CollectProgress(repo.name, error = "«${repo.localPath}» no es un repositorio de git.")
        }

        // El clon se actualiza antes de contar: sin esto las métricas describen un repo viejo, y
        // el número que falta es justamente el del trabajo más reciente.
        Git.fetch(dir)

        // Sólo lo que llegó desde la última corrida, cuando se puede. Releer todo cada vez son
        // 2.283 commits sobre siete repositorios en esta instalación, y crece.
        val desdeSha = commits.lastRun(repo.id)?.second?.takeIf { sha ->
            // Si ese commit ya no está —la rama se reescribió, o se recreó el clon— continuar
            // desde ahí dejaría un agujero silencioso en el medio del historial. Ahí se relee todo.
            Git.exists(dir, sha)
        }

        val args = buildList {
            add("git"); add("log"); add("--all"); add("--no-merges")
            add("--format=$GIT_LOG_FORMAT"); add("--numstat")
            if (desdeSha != null) {
                // `--not <sha>` y no `<sha>..HEAD`: con `--all` interesa todo lo que no era
                // alcanzable antes, incluidas ramas que no son la principal.
                add("--not"); add(desdeSha)
            } else {
                since?.let { add("--since=$it") }
            }
        }
        val salida = runCatching { Git.runRaw(dir, args) }.getOrElse {
            return@withContext CollectProgress(repo.name, error = it.message ?: "git log falló")
        }
        if (!salida.ok) {
            return@withContext CollectProgress(repo.name, error = salida.output.take(300))
        }

        val stats = parseGitLog(salida.output, repo.generatedPatterns())
        // Se resuelven las identidades con un cache en memoria: sin esto habría una consulta por
        // commit y son miles.
        val cache = people.all().toMutableList()
        var n = 0
        stats.forEach { c ->
            val personId = people.resolve(c.author, cache).takeIf { it.isNotBlank() }
            commits.upsert(repo.id, personId, c)
            n++
            if (n % 200 == 0) onProgress(CollectProgress(repo.name, n, cache.size))
        }
        // Se anota la punta actual aunque no haya commits nuevos: es lo que permite que la
        // próxima corrida siga siendo incremental en vez de volver a leer todo.
        commits.markRun(repo.id, Git.currentHead(dir), n)
        CollectProgress(repo.name, n, cache.size, done = true, incremental = desdeSha != null)
    }
}

/**
 * Trae el histórico de pull requests de un repositorio y lo guarda.
 *
 * Aparte de la recolección de git y bajo confirmación explícita: son cientos por repositorio
 * —148 contra 4 abiertos en uno de esta instalación— y el proveedor los devuelve paginados. No se
 * descarga solo.
 *
 * Lo que trae desbloquea dos cosas: contar cuántos PRs abrió cada uno y cuánto tardaron en
 * cerrarse, y rellenar de quién era cada PR revisado, que es el agujero que dejaba a las métricas
 * de review calculándose sobre 7 de 12.
 */
class PrHistoryCollector(private val prs: io.acr.data.PrStatRepository) {

    suspend fun collect(
        repo: RepoRecord,
        maxPages: Int = 10,
        onProgress: (CollectProgress) -> Unit = {},
    ): CollectProgress {
        val forge = io.acr.forge.Forges.of(repo.provider)
        var guardados = 0
        // Uno por estado y no los tres juntos: si un pedido falla —Bitbucket devuelve 401 en
        // cerca del 40% de las llamadas, al azar— se pierde ese estado y no la corrida entera.
        for (estado in io.acr.forge.PrState.entries) {
            val intento = runCatching {
                forge.searchPullRequests(repo, setOf(estado), maxPages = maxPages)
            }
            val traidos = intento.getOrNull()
            if (traidos == null) {
                onProgress(
                    CollectProgress(
                        repo.name, guardados,
                        error = intento.exceptionOrNull()?.message?.take(200),
                    ),
                )
            } else {
                for (pr in traidos) {
                    prs.upsert(repo.id, pr)
                    guardados++
                }
                onProgress(CollectProgress(repo.name, guardados))
            }
        }
        val rellenadas = prs.backfillReviewAuthors()
        return CollectProgress(repo.name, guardados, people = rellenadas, done = true)
    }
}

/**
 * Cuenta los commits que llegaron después de que revisamos, para los PRs que tuvieron hallazgos
 * publicados.
 *
 * La condición de los hallazgos publicados es la que le da sentido al número: los commits que
 * llegan después de una review que no encontró nada son desarrollo normal, no corrección.
 * Contarlos como retrabajo diría que alguien arregló algo que nadie le señaló.
 */
class ReworkCollector(private val prs: io.acr.data.PrStatRepository) {

    suspend fun collect(
        repos: List<RepoRecord>,
        onProgress: (CollectProgress) -> Unit = {},
    ): CollectProgress {
        val porId = repos.associateBy { it.id }
        val objetivos = prs.pendingRework()
        var medidos = 0
        for (o in objetivos) {
            val repo = porId[o.repoId] ?: continue
            val dir = File(repo.localPath)
            if (!Git.isRepo(dir)) continue
            prs.setRework(o.repoId, o.prId, contar(dir, o))
            medidos++
            if (medidos % 10 == 0) onProgress(CollectProgress(repo.name, medidos))
        }
        return CollectProgress(repos.firstOrNull()?.name.orEmpty(), medidos, done = true)
    }

    /**
     * Cuántos commits hay entre el que revisamos y la punta de la rama.
     *
     * Devuelve null —y no cero— cuando no se puede saber: si el commit revisado ya no está en la
     * rama, es porque hubo un rebase que reescribió la historia. Ahí "0 correcciones" sería una
     * afirmación falsa, y contar todos los commits de la rama también.
     */
    private suspend fun contar(dir: File, o: io.acr.data.ReworkTarget): Int? {
        if (o.reviewedSha.isBlank()) return null
        // Si el commit revisado no es ancestro de la punta, la historia se reescribió.
        if (!Git.isAncestor(dir, o.reviewedSha, "origin/${o.sourceBranch}")) return null
        val res = Git.runRaw(
            dir,
            listOf("git", "rev-list", "--count", "${o.reviewedSha}..origin/${o.sourceBranch}"),
        )
        return if (!res.ok) null else res.output.trim().toIntOrNull()
    }
}

/**
 * Los patrones de archivo generado que aplican a este repositorio.
 *
 * Hoy son los mismos para todos. Queda como punto de extensión porque la lista correcta depende
 * del repo —un proyecto Angular y uno Java no generan las mismas carpetas— y ese ajuste es
 * exactamente el que hace que el número de líneas signifique algo.
 */
fun RepoRecord.generatedPatterns(): List<String> = DEFAULT_GENERATED_PATTERNS
