package io.acr

import io.acr.claude.AutoReviewer
import io.acr.claude.ReviewEngine
import io.acr.crypto.Secrets
import io.acr.data.ApprovalRepository
import io.acr.data.GuidelineRepository
import io.acr.data.PendingJobRepository
import io.acr.data.FindingRepository
import io.acr.data.LocalNoteRepository
import io.acr.data.PrCommentRepository
import io.acr.data.PrefsRepo
import io.acr.data.PublicationRepository
import io.acr.data.ReplyRepository
import io.acr.data.RepoRepository
import io.acr.data.ReviewRepository
import io.acr.data.Store
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.nio.file.Path

/**
 * Composition root. Everything the UI needs hangs off one instance created in [Main] and closed
 * by a shutdown hook — same shape as mongo-explorer v3, no DI container.
 */
class AppContext private constructor(
    val store: Store,
    val repos: RepoRepository,
    val reviews: ReviewRepository,
    val publications: PublicationRepository,
    val comments: PrCommentRepository,
    val notes: LocalNoteRepository,
    val findings: FindingRepository,
    val approvals: ApprovalRepository,
    val jobs: PendingJobRepository,
    val guidelines: GuidelineRepository,
    val replies: ReplyRepository,
    val seenPrs: io.acr.data.SeenPrRepository,
    val prCache: io.acr.data.PrCacheRepository,
    val prLoader: io.acr.forge.PrLoader,
    val prefs: PrefsRepo,
    val engine: ReviewEngine,
    val auto: AutoReviewer,
    val notifier: io.acr.notify.Notifier,
    val persons: io.acr.data.PersonRepository,
    val commitStats: io.acr.data.CommitStatRepository,
    val statsCollector: io.acr.stats.StatsCollector,
    val reviewStats: io.acr.data.ReviewStatsRepository,
    val prStats: io.acr.data.PrStatRepository,
    val prHistory: io.acr.stats.PrHistoryCollector,
    val rework: io.acr.stats.ReworkCollector,
    val health: io.acr.data.RepoHealthRepository,
    val jira: io.acr.data.JiraRepository,
    val jiraSites: io.acr.data.JiraSiteRepository,
    val impls: io.acr.data.ImplRepository,
    val jobs2: io.acr.data.JobRepository,
    val usage: io.acr.data.UsageRepository,
    val workspaces: io.acr.impl.Workspaces,
    val implEngine: io.acr.impl.ImplEngine,
    /** Dónde viven la base y la clave. La pantalla de la base lo necesita para poder mudarse. */
    val dataDir: Path,
    val secrets: Secrets,
    /**
     * Por qué no se pudo abrir la base configurada, si es que no se pudo.
     *
     * Cuando esto tiene texto, la app está corriendo sobre la base de fábrica y no sobre la que
     * alguien eligió. Sin avisarlo, la pantalla se ve igual que si los datos se hubieran perdido.
     */
    val dbFallback: String? = null,
) : AutoCloseable {

    /** ¿Hay al menos un sitio de Jira conectado y usable? */
    fun jiraConfigured(): Boolean = jiraSites.list().any { it.config().configured }

    /**
     * El cliente que corresponde a esa clave de ticket.
     *
     * Null cuando ningún sitio la reclama: pedirle un `FIS-1` al Jira de otro cliente puede
     * devolver un ticket que existe y no tiene nada que ver, y eso es peor que no traer nada.
     */
    fun jiraClientFor(key: String): io.acr.jira.JiraClient? =
        jiraSites.siteFor(key)?.config()?.takeIf { it.configured }?.let { io.acr.jira.JiraClient(it) }

    /**
     * Scope de vida de la app. Las reviews se lanzan acá y NO en el scope de la pantalla: con el
     * scope del composable, navegar a otro PR cancelaba la corrutina, el subproceso de Claude
     * quedaba corriendo igual (ya facturado) y el resultado se descartaba marcando la review
     * como fallida.
     */
    val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )

    override fun close() {
        appScope.cancel()
        store.close()
    }

    companion object {
        const val PREF_CLAUDE_BINARY = "claude.binary"
        const val PREF_MODEL = "claude.model"
        const val PREF_LANGUAGE = "review.language"
        const val PREF_THEME = "ui.theme"
        const val PREF_UI_LANG = "ui.lang"
        const val PREF_CLOSE_ACTION = "ui.closeAction"

        /**
         * Tope semanal en dólares, declarado a mano.
         *
         * La app no puede leer el límite real de la cuenta —el CLI no lo expone— así que el tope lo
         * pone quien lo conoce. Sin uno declarado no se dibuja ninguna barra: una barra sin
         * referencia sugiere un límite que la app no sabe.
         */
        const val PREF_WEEKLY_BUDGET = "usage.weeklyBudget"
        const val PREF_PR_SORT = "ui.prSort"
        const val PREF_FOLLOWUP_DAYS = "followup.days"
        const val PREF_DASH_COLLAPSED = "ui.dashCollapsed"
        const val PREF_MERGE_STRATEGY = "merge.strategy"
        const val PREF_JIRA_URL = "jira.url"
        const val PREF_JIRA_EMAIL = "jira.email"
        const val PREF_JIRA_TOKEN = "jira.token"

        /**
         * @param dataDir dónde viven la base y la clave. Configurable para que los tests NO
         *   escriban sobre los datos reales del usuario: ya pasó que una corrida le cambiara la
         *   configuración de un repo y que otra fallara por contención con la app abierta.
         */
        fun bootstrap(dataDir: Path = resolveDataDir()): AppContext {
            val dir = dataDir.also { Files.createDirectories(it) }
            // Los secretos primero: la contraseña de la base está cifrada con la clave maestra, así
            // que hay que poder descifrarla antes de saber a qué base conectarse.
            val secrets = Secrets(keyPathFor(dir))
            // Si la base configurada no responde, se abre la de fábrica igual.
            //
            // Un servidor caído no puede dejar la app sin arrancar: la pantalla donde se arregla la
            // conexión está adentro de la app. Se guarda el error para poder mostrarlo, porque una
            // app que arranca con datos vacíos y sin decir por qué se ve exactamente igual que una
            // que los perdió.
            val configurada = io.acr.data.DbSettings.load(dir, secrets)
            var fallo: String? = null
            val store = runCatching { Store(dir.resolve("acr.db"), configurada) }.getOrElse { e ->
                if (!configurada.isServer) throw e
                fallo = "${configurada.describe()}: ${e.message ?: e::class.simpleName}"
                Store(dir.resolve("acr.db"))
            }
            val repos = RepoRepository(store, secrets)
            val reviews = ReviewRepository(store)
            val publications = PublicationRepository(store)
            val comments = PrCommentRepository(store)
            val notes = LocalNoteRepository(store)
            val findings = FindingRepository(store)
            val approvals = ApprovalRepository(store)
            val jobs = PendingJobRepository(store)
            val guidelines = GuidelineRepository(store)
            val persons = io.acr.data.PersonRepository(store)
            val commitStats = io.acr.data.CommitStatRepository(store)
            val reviewStats = io.acr.data.ReviewStatsRepository(store)
            val prStats = io.acr.data.PrStatRepository(store)
            val prHistory = io.acr.stats.PrHistoryCollector(prStats)
            val rework = io.acr.stats.ReworkCollector(prStats)
            val health = io.acr.data.RepoHealthRepository(store)
            val jira = io.acr.data.JiraRepository(store)
            val jiraSites = io.acr.data.JiraSiteRepository(store, secrets)
            val impls = io.acr.data.ImplRepository(store)
            val jobsRepo = io.acr.data.JobRepository(store)
            val usageRepo = io.acr.data.UsageRepository(store)
            // Desde el único lugar por el que pasan todas las corridas del CLI. Pedirle a cada
            // llamador que registre es como se termina con un registro parcial, que es peor que
            // ninguno: parece autoritativo y las decisiones que se toman mirándolo salen mal.
            io.acr.claude.ClaudeCli.onUsage = { e -> runCatching { usageRepo.record(e) } }
            val statsCollector = io.acr.stats.StatsCollector(persons, commitStats)
            val replies = ReplyRepository(store)
            val seenPrs = io.acr.data.SeenPrRepository(store)
            val prCache = io.acr.data.PrCacheRepository(store)
            val prLoader = io.acr.forge.PrLoader(prCache)
            val prefs = PrefsRepo(store)
            // El taller aparte, en el directorio de datos y no en /tmp: un workspace puede tener el
            // único ejemplar de una tarde de trabajo, y /tmp lo borra el sistema cuando quiere.
            val workspaces = io.acr.impl.Workspaces(dir.resolve("workspaces").toFile())
            val implEngine = io.acr.impl.ImplEngine(impls, prefs, jobsRepo, workspaces)
            // Lo que quedó marcado como corriendo de una sesión anterior no puede seguir figurando
            // vivo: el estado del motor es en memoria y esos procesos murieron con la app. Se
            // cierran como interrumpidos —no como fallidos— y su contexto queda intacto para que
            // el próximo intento siga desde ahí en vez de empezar de nuevo.
            val interrumpidos = runCatching { jobsRepo.sweepInterrupted() }.getOrDefault(emptyList())
            // Sólo lo que no llegó a terminar. Una tarea que alcanzó a completarse y cuyo job quedó
            // abierto porque la app murió en el medio ya tiene su commit hecho: devolverla a
            // pendiente la haría rehacer trabajo que está bien.
            val hechas = interrumpidos.mapNotNull { it.taskId }.distinct().filter { id ->
                impls.taskStatus(id) != io.acr.impl.TaskStatus.DONE
            }
            hechas.forEach { impls.resetTask(it) }
            // Los talleres de lo que ya terminó y ya está devuelto. Devolver puede fallar por algo
            // pasajero, y entonces el taller queda —bien— sin borrar; si después alguien devuelve el
            // trabajo a mano, nadie vuelve a limpiar y la carpeta ocupa disco para siempre.
            runCatching {
                kotlinx.coroutines.runBlocking {
                    workspaces.prune(
                        impls.list()
                            .filter { it.status == io.acr.impl.ImplStatus.DONE }
                            .map { i ->
                                Triple(
                                    i.id,
                                    impls.reposOf(i.id).mapNotNull { repos.get(it.repoId) },
                                    i.branch,
                                )
                            },
                    )
                }
            }
            // Y las implementaciones que quedaron diciendo que corrían. Sin esto la pantalla ofrece
            // frenarlas en vez de retomarlas, y el avance se queda congelado para siempre.
            runCatching { impls.stopOrphanedRunning() }
            // Ninguna review de una corrida anterior puede seguir viva: el estado del motor es
            // en memoria. Sin esto quedan como "corriendo" para siempre en el panel.
            //
            // Antes de cerrarlas se anotan sus parámetros: el subproceso murió con su contexto y
            // no se puede retomar, pero sí volver a lanzarlas al abrir, que es lo que uno espera
            // cuando cierra la app con trabajo en curso.
            reviews.orphanedRunning().forEach { r ->
                jobs.enqueue(r.repoId, r.prId, r.depth, r.projectKind, r.model.orEmpty(), r.auto)
            }
            reviews.failOrphanedRunning()
            val notifier = io.acr.notify.Notifier(prefs)
            val engine = ReviewEngine(
                reviews, publications, comments, findings, replies, approvals, guidelines, prefs,
                notifier,
                // Si Jira no está configurado esto devuelve vacío y la review corre igual que antes.
                jiraIssues = { repoId, prId -> jira.issuesOf(repoId, prId) },
            )
            val auto = AutoReviewer(repos, reviews, prefs, engine, notifier, replies, seenPrs, prLoader, findings, approvals, jobs, comments)
            return AppContext(store, repos, reviews, publications, comments, notes, findings, approvals, jobs, guidelines, replies, seenPrs, prCache, prLoader, prefs, engine, auto, notifier, persons, commitStats, statsCollector, reviewStats, prStats, prHistory, rework, health, jira, jiraSites, impls, jobsRepo, usageRepo, workspaces, implEngine, dir, secrets, fallo)
        }

        /** La propiedad `acr.dataDir` gana sobre la ubicación estándar; la usan los tests. */
        private fun resolveDataDir(): Path =
            System.getProperty("acr.dataDir")?.let { Path.of(it) } ?: defaultDataDir()

        /**
         * La clave vive fuera de la base en la instalación real (copiar el .db no alcanza para
         * leer los tokens), pero en una carpeta de test va adentro, para que sea descartable.
         */
        private fun keyPathFor(dir: Path): Path =
            if (System.getProperty("acr.dataDir") != null) dir.resolve("master.key")
            else Path.of(System.getProperty("user.home"), ".acr", "master.key")

        private fun defaultDataDir(): Path {
            val home = System.getProperty("user.home")
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.contains("mac") -> Path.of(home, "Library", "Application Support", "AICodeReviewer")
                os.contains("win") -> Path.of(System.getenv("APPDATA") ?: home, "AICodeReviewer")
                else -> Path.of(home, ".local", "share", "ai-code-reviewer")
            }
        }
    }
}
