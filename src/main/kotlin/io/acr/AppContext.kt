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
            val store = Store(dir.resolve("acr.db"))
            val secrets = Secrets(keyPathFor(dir))
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
            val statsCollector = io.acr.stats.StatsCollector(persons, commitStats)
            val replies = ReplyRepository(store)
            val seenPrs = io.acr.data.SeenPrRepository(store)
            val prCache = io.acr.data.PrCacheRepository(store)
            val prLoader = io.acr.forge.PrLoader(prCache)
            val prefs = PrefsRepo(store)
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
            val auto = AutoReviewer(repos, reviews, prefs, engine, notifier, replies, seenPrs, prLoader, findings, approvals, jobs)
            return AppContext(store, repos, reviews, publications, comments, notes, findings, approvals, jobs, guidelines, replies, seenPrs, prCache, prLoader, prefs, engine, auto, notifier, persons, commitStats, statsCollector, reviewStats, prStats, prHistory, rework, health, jira, jiraSites)
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
