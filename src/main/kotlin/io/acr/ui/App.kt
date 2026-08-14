package io.acr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import io.acr.AppContext
import io.acr.forge.RepoRecord
import io.acr.ui.prs.PrsPanel
import io.acr.ui.repos.RepoFormPanel
import io.acr.ui.review.ReviewPanel
import io.acr.ui.settings.SettingsPanel
import io.acr.ui.state.Selection
import io.acr.ui.state.SelectionStore
import io.acr.ui.theme.AcrTheme
import io.acr.ui.theme.ThemePref

@Composable
fun App(ctx: AppContext) {
    val selection = remember { SelectionStore() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var repos by remember { mutableStateOf<List<RepoRecord>>(emptyList()) }
    val anchoBarra = io.acr.ui.rememberPaneWidth(ctx.prefs, "sidebar", 260.dp)
    var theme by remember {
        mutableStateOf(
            runCatching { ThemePref.valueOf(ctx.prefs.get(AppContext.PREF_THEME) ?: "System") }
                .getOrDefault(ThemePref.System),
        )
    }
    var lang by remember {
        mutableStateOf(io.acr.i18n.Lang.fromCode(ctx.prefs.get(AppContext.PREF_UI_LANG)))
    }
    // Espejo del idioma para los textos que se arman fuera de la composición (snackbars que salen
    // después de una llamada de red, donde no se puede leer el CompositionLocal).
    androidx.compose.runtime.SideEffect { io.acr.i18n.uiLang = lang }
    // Cuántas reviews corren por repo, para verlo en la barra sin entrar a cada uno.
    val progressMap by ctx.engine.progress.collectAsState()
    val runningByRepo = progressMap.values.groupingBy { it.repoId }.eachCount()

    suspend fun reloadRepos() {
        repos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ctx.repos.list() }
    }

    androidx.compose.runtime.CompositionLocalProvider(io.acr.i18n.LocalLang provides lang) {
    AcrTheme(theme) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                io.acr.ui.ActivityBar(
                    items = listOf(
                        io.acr.ui.ActivityItem(
                            Icons.Default.Dashboard, io.acr.i18n.t("nav.panel"),
                            selection.current is Selection.Dashboard,
                        ) { selection.go(Selection.Dashboard) },
                        io.acr.ui.ActivityItem(
                            Icons.Default.FolderOpen, io.acr.i18n.t("nav.repos"),
                            // También queda activo al estar dentro de un repositorio o de un PR:
                            // son parte de esta sección, no otro lugar.
                            selection.current is Selection.Repos ||
                                selection.current is Selection.Repo ||
                                selection.current is Selection.Review ||
                                selection.current is Selection.RepoForm,
                        ) { selection.go(Selection.Repos) },
                        io.acr.ui.ActivityItem(
                            Icons.Default.BarChart, io.acr.i18n.t("nav.stats"),
                            selection.current is Selection.Stats,
                        ) { selection.go(Selection.Stats) },
                    ),
                    bottom = listOf(
                        io.acr.ui.ActivityItem(
                            Icons.Default.Info, io.acr.i18n.t("nav.about"),
                            selection.current is Selection.About,
                        ) { selection.go(Selection.About) },
                        io.acr.ui.ActivityItem(
                            Icons.Default.Settings, io.acr.i18n.t("nav.settings"),
                            selection.current is Selection.Settings,
                        ) { selection.go(Selection.Settings) },
                    ),
                )
                // La lista de repositorios acompaña a toda la sección de repositorios, incluida
                // su portada: entrar a la sección y entrar a un repositorio son el mismo lugar, y
                // que la lista apareciera recién al abrir uno hacía saltar el layout.
                //
                // En el panel o en estadísticas no aparece: ahí no aporta nada y se lleva 260 dp
                // de ancho útil.
                val enRepos = selection.current.let {
                    it is Selection.Repos || it is Selection.Repo ||
                        it is Selection.Review || it is Selection.RepoForm
                }
                if (enRepos) {
                    RepoSidebar(
                        repos = repos,
                        running = runningByRepo,
                        selection = selection,
                        width = anchoBarra.value,
                        onAdd = { selection.go(Selection.RepoForm(null)) },
                        onEdit = { selection.go(Selection.RepoForm(it.id)) },
                    )
                    io.acr.ui.VerticalSplitter(anchoBarra, ctx.prefs, "sidebar", min = 200.dp, max = 480.dp)
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (val sel = selection.current) {
                        is Selection.Welcome -> Welcome(hasRepos = repos.isNotEmpty())
                        is Selection.Dashboard -> io.acr.ui.dashboard.DashboardPanel(
                            ctx = ctx,
                            onOpenPr = { repoId, prId -> selection.go(Selection.Review(repoId, prId)) },
                        )
                        is Selection.About -> io.acr.ui.about.AboutPanel(ctx)
                        is Selection.Stats -> io.acr.ui.stats.StatsSection(ctx)
                        is Selection.Repos -> io.acr.ui.repos.ReposPanel(
                            ctx = ctx,
                            repos = repos,
                            running = runningByRepo,
                            onOpen = { selection.go(Selection.Repo(it.id)) },
                            onEdit = { selection.go(Selection.RepoForm(it.id)) },
                            onAdd = { selection.go(Selection.RepoForm(null)) },
                        )
                        is Selection.Settings -> SettingsPanel(
                            ctx = ctx,
                            theme = theme,
                            onTheme = { theme = it; ctx.prefs.put(AppContext.PREF_THEME, it.name) },
                            lang = lang,
                            onLang = { lang = it; ctx.prefs.put(AppContext.PREF_UI_LANG, it.code) },
                        )
                        is Selection.Repo -> {
                            val repo = repos.firstOrNull { it.id == sel.repoId }
                            if (repo == null) Welcome(hasRepos = repos.isNotEmpty())
                            else PrsPanel(
                                ctx = ctx,
                                repo = repo,
                                snackbar = snackbar,
                                onOpenReview = { prId -> selection.go(Selection.Review(repo.id, prId)) },
                            )
                        }
                        is Selection.RepoForm -> {
                            // `key` fuerza a rehacer el estado del formulario al cambiar de repo:
                            // sin eso, pasar de editar uno a otro conservaría los campos del primero.
                            androidx.compose.runtime.key(sel.repoId) {
                                RepoFormPanel(
                                    ctx = ctx,
                                    existing = sel.repoId?.let { id -> repos.firstOrNull { it.id == id } },
                                    onCancel = { selection.back() },
                                    onSaved = { id ->
                                        scope.launch { reloadRepos() }
                                        selection.go(Selection.Repo(id))
                                    },
                                    onDeleted = {
                                        scope.launch { reloadRepos() }
                                        selection.go(Selection.Welcome)
                                    },
                                )
                            }
                        }
                        is Selection.Review -> {
                            val repo = repos.firstOrNull { it.id == sel.repoId }
                            if (repo == null) Welcome(hasRepos = repos.isNotEmpty())
                            else ReviewPanel(
                                ctx = ctx,
                                repo = repo,
                                prId = sel.prId,
                                snackbar = snackbar,
                                // Vuelve a donde estabas —el panel, la lista del repo— y no
                                // siempre a la lista: abrir un PR desde el panel y aterrizar en
                                // otro lado te hace perder lo que estabas mirando.
                                onBack = { selection.back(Selection.Repo(repo.id)) },
                            )
                        }
                    }
                }
            }
        }
    }

    }

    LaunchedEffect(Unit) {
        reloadRepos()
        // Con repos conectados, lo primero útil es el panel, no una pantalla de bienvenida.
        if (selection.current is Selection.Welcome) {
            // Sin repositorios, lo único útil es agregar uno; con repositorios, el panel.
            selection.go(if (repos.isEmpty()) Selection.Repos else Selection.Dashboard)
        }
    }

    // Un solo arranque por vida de la app: el bucle sobrevive a la navegación entre pantallas.
    LaunchedEffect(Unit) { ctx.auto.start(scope) }
}

@Composable
private fun RepoSidebar(
    repos: List<RepoRecord>,
    running: Map<String, Int>,
    selection: SelectionStore,
    width: androidx.compose.ui.unit.Dp,
    onAdd: () -> Unit,
    onEdit: (RepoRecord) -> Unit,
) {
    Column(
        Modifier.width(width).fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 12.dp),
    ) {
        Text(
            "AI Code Reviewer",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            io.acr.i18n.t("app.subtitle"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()

        LazyColumn(Modifier.weight(1f)) {
            items(repos, key = { it.id }) { repo ->
                val active = (selection.current as? Selection.Repo)?.repoId == repo.id ||
                    (selection.current as? Selection.Review)?.repoId == repo.id ||
                    (selection.current as? Selection.RepoForm)?.repoId == repo.id
                RepoRow(
                    repo = repo,
                    running = running[repo.id] ?: 0,
                    active = active,
                    onClick = { selection.go(Selection.Repo(repo.id)) },
                    onEdit = { onEdit(repo) },
                )
            }
        }

    }
}

@Composable
private fun RepoRow(
    repo: RepoRecord,
    running: Int,
    active: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
) {
    val bg = if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    Column(
        Modifier.fillMaxWidth().background(bg)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                repo.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickableText(onClick),
            )
            if (running > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "● $running",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            "${repo.provider.label} · ${repo.owner}/${repo.slug}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickableText(onClick),
        )
        Text(
            "Editar",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickableText(onEdit),
        )
    }
}

@Composable
private fun Welcome(hasRepos: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("AI Code Reviewer", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasRepos) io.acr.i18n.t("welcome.pick") else io.acr.i18n.t("welcome.connect"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
