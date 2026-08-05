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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
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
import io.acr.ui.repos.RepoDialog
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
    var theme by remember {
        mutableStateOf(
            runCatching { ThemePref.valueOf(ctx.prefs.get(AppContext.PREF_THEME) ?: "System") }
                .getOrDefault(ThemePref.System),
        )
    }
    var lang by remember {
        mutableStateOf(io.acr.i18n.Lang.fromCode(ctx.prefs.get(AppContext.PREF_UI_LANG)))
    }
    var editing by remember { mutableStateOf<RepoRecord?>(null) }
    // Cuántas reviews corren por repo, para verlo en la barra sin entrar a cada uno.
    val progressMap by ctx.engine.progress.collectAsState()
    val runningByRepo = progressMap.values.groupingBy { it.repoId }.eachCount()
    var dialogOpen by remember { mutableStateOf(false) }

    suspend fun reloadRepos() {
        repos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ctx.repos.list() }
    }

    androidx.compose.runtime.CompositionLocalProvider(io.acr.i18n.LocalLang provides lang) {
    AcrTheme(theme) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                RepoSidebar(
                    repos = repos,
                    running = runningByRepo,
                    selection = selection,
                    onAdd = { editing = null; dialogOpen = true },
                    onEdit = { editing = it; dialogOpen = true },
                )
                VerticalDivider(Modifier.fillMaxHeight())
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (val sel = selection.current) {
                        is Selection.Welcome -> Welcome(hasRepos = repos.isNotEmpty())
                        is Selection.Dashboard -> io.acr.ui.dashboard.DashboardPanel(
                            ctx = ctx,
                            onOpenPr = { repoId, prId -> selection.go(Selection.Review(repoId, prId)) },
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
                        is Selection.Review -> {
                            val repo = repos.firstOrNull { it.id == sel.repoId }
                            if (repo == null) Welcome(hasRepos = repos.isNotEmpty())
                            else ReviewPanel(
                                ctx = ctx,
                                repo = repo,
                                prId = sel.prId,
                                snackbar = snackbar,
                                onBack = { selection.go(Selection.Repo(repo.id)) },
                            )
                        }
                    }
                }
            }

            if (dialogOpen) {
                RepoDialog(
                    ctx = ctx,
                    existing = editing,
                    onDismiss = { dialogOpen = false },
                    onSaved = { id ->
                        dialogOpen = false
                        scope.launch { reloadRepos() }
                        selection.go(Selection.Repo(id))
                    },
                    onDeleted = {
                        dialogOpen = false
                        scope.launch { reloadRepos() }
                        selection.go(Selection.Welcome)
                    },
                )
            }
        }
    }

    }

    LaunchedEffect(Unit) {
        reloadRepos()
        // Con repos conectados, lo primero útil es el panel, no una pantalla de bienvenida.
        if (repos.isNotEmpty() && selection.current is Selection.Welcome) {
            selection.go(Selection.Dashboard)
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
    onAdd: () -> Unit,
    onEdit: (RepoRecord) -> Unit,
) {
    Column(
        Modifier.width(260.dp).fillMaxHeight()
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
                    (selection.current as? Selection.Review)?.repoId == repo.id
                RepoRow(
                    repo = repo,
                    running = running[repo.id] ?: 0,
                    active = active,
                    onClick = { selection.go(Selection.Repo(repo.id)) },
                    onEdit = { onEdit(repo) },
                )
            }
        }

        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            NavigationRailItem(
                selected = selection.current is Selection.Dashboard,
                onClick = { selection.go(Selection.Dashboard) },
                icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                label = { Text(io.acr.i18n.t("nav.panel")) },
            )
            NavigationRailItem(
                selected = false,
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                label = { Text(io.acr.i18n.t("nav.repo")) },
            )
            NavigationRailItem(
                selected = selection.current is Selection.Settings,
                onClick = { selection.go(Selection.Settings) },
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                label = { Text(io.acr.i18n.t("nav.settings")) },
            )
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
