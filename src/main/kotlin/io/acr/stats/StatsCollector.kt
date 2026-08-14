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

        val args = buildList {
            add("git"); add("log"); add("--all"); add("--no-merges")
            add("--format=$GIT_LOG_FORMAT"); add("--numstat")
            since?.let { add("--since=$it") }
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
        commits.markRun(repo.id, Git.currentHead(dir), n)
        CollectProgress(repo.name, n, cache.size, done = true)
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
