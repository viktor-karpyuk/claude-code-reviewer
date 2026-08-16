package io.acr.ui.settings

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.acr.AppContext
import io.acr.claude.ClaudeCli
import io.acr.ui.theme.ThemePref

@Composable
fun SettingsPanel(
    ctx: AppContext,
    theme: ThemePref,
    onTheme: (ThemePref) -> Unit,
    lang: io.acr.i18n.Lang,
    onLang: (io.acr.i18n.Lang) -> Unit,
) {
    var binary by remember { mutableStateOf(ctx.prefs.get(AppContext.PREF_CLAUDE_BINARY) ?: "") }
    var model by remember { mutableStateOf(ctx.prefs.get(AppContext.PREF_MODEL) ?: "") }
    var language by remember { mutableStateOf(ctx.prefs.get(AppContext.PREF_LANGUAGE) ?: "español") }
    var probe by remember { mutableStateOf<String?>(null) }
    var autoOn by remember { mutableStateOf(ctx.prefs.get(io.acr.claude.AutoReviewer.PREF_ENABLED) != "false") }
    var interval by remember { mutableStateOf(ctx.prefs.get(io.acr.claude.AutoReviewer.PREF_INTERVAL) ?: "10") }
    var maxPerCycle by remember { mutableStateOf(ctx.prefs.get(io.acr.claude.AutoReviewer.PREF_MAX) ?: "3") }
    var notifyOn by remember { mutableStateOf(ctx.notifier.enabled()) }
    val notifyTestMsg = io.acr.i18n.t("settings.notifyTest")
    val autoStatus by ctx.auto.status.collectAsState()
    val scope = rememberCoroutineScope()

    // Con motor, notificaciones, idioma, apariencia, automático y permisos, el contenido supera
    // el alto de la ventana y lo de abajo quedaba inalcanzable.
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(io.acr.i18n.t("settings.title"), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(10.dp))
            Text(
                "v" + io.acr.AppVersion.value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()

        Text(io.acr.i18n.t("settings.engine"), style = MaterialTheme.typography.titleSmall)
        Text(
            io.acr.i18n.t("settings.engineNote"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = binary,
            onValueChange = { binary = it; ctx.prefs.put(AppContext.PREF_CLAUDE_BINARY, it) },
            label = { Text(io.acr.i18n.t("settings.binary")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                val found = ClaudeCli.resolveBinary(binary.ifBlank { null })
                probe = found?.let { "Encontrado: $it" } ?: "No encuentro el ejecutable claude."
            }) { Text(io.acr.i18n.t("settings.detect")) }
            probe?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            }
        }

        OutlinedTextField(
            value = model,
            onValueChange = { model = it; ctx.prefs.put(AppContext.PREF_MODEL, it) },
            label = { Text(io.acr.i18n.t("settings.model")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
        )

        OutlinedTextField(
            value = language,
            onValueChange = { language = it; ctx.prefs.put(AppContext.PREF_LANGUAGE, it) },
            label = { Text(io.acr.i18n.t("settings.reviewLanguage")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
        )

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.Switch(
                checked = notifyOn,
                onCheckedChange = {
                    notifyOn = it
                    ctx.prefs.put(io.acr.notify.Notifier.PREF_ENABLED, it.toString())
                    // Se lee afuera del lambda: t() es @Composable y acá ya no hay composición.
                    if (it) ctx.notifier.notify("AI Code Reviewer", notifyTestMsg)
                },
            )
            Column(Modifier.padding(start = 10.dp)) {
                Text(io.acr.i18n.t("settings.notify"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    io.acr.i18n.t("settings.notifyNote"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
        Text(io.acr.i18n.t("settings.uiLanguage"), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            io.acr.i18n.Lang.entries.forEach { l ->
                FilterChip(
                    selected = lang == l,
                    onClick = { onLang(l) },
                    label = { Text(l.label) },
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
        Text(io.acr.i18n.t("settings.appearance"), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemePref.entries.forEach { pref ->
                FilterChip(
                    selected = theme == pref,
                    onClick = { onTheme(pref) },
                    label = { Text(pref.name) },
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
        Text(io.acr.i18n.t("settings.autoTitle"), style = MaterialTheme.typography.titleSmall)
        Text(
            io.acr.i18n.t("settings.autoNote"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.Switch(
                checked = autoOn,
                onCheckedChange = {
                    autoOn = it
                    ctx.prefs.put(io.acr.claude.AutoReviewer.PREF_ENABLED, it.toString())
                },
            )
            Text(
                if (autoOn) io.acr.i18n.t("settings.autoOn") else io.acr.i18n.t("settings.autoOff"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = interval,
                onValueChange = {
                    interval = it.filter(Char::isDigit)
                    ctx.prefs.put(io.acr.claude.AutoReviewer.PREF_INTERVAL, interval.ifBlank { "10" })
                },
                label = { Text(io.acr.i18n.t("settings.interval")) },
                singleLine = true,
                modifier = Modifier.width(220.dp),
            )
            OutlinedTextField(
                value = maxPerCycle,
                onValueChange = {
                    maxPerCycle = it.filter(Char::isDigit)
                    ctx.prefs.put(io.acr.claude.AutoReviewer.PREF_MAX, maxPerCycle.ifBlank { "3" })
                },
                label = { Text(io.acr.i18n.t("settings.maxPerCycle")) },
                supportingText = { Text(io.acr.i18n.t("settings.maxNote")) },
                singleLine = true,
                modifier = Modifier.width(260.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedButton(
                enabled = !autoStatus.running,
                onClick = { scope.launch { ctx.auto.runOnce() } },
            ) { Text(if (autoStatus.running) io.acr.i18n.t("dash.searching") else io.acr.i18n.t("settings.searchNow")) }
            Text(
                autoStatus.lastRunAt?.let { "Último barrido ${it.take(16).replace('T', ' ')}: ${autoStatus.lastMessage}" }
                    ?: io.acr.i18n.t("settings.neverRan"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
        JiraSettings(ctx)

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
        io.acr.ui.GuidelinesSection(
            repo = ctx.guidelines,
            repoId = null,
            titulo = io.acr.i18n.t("guide.globalTitle"),
            nota = io.acr.i18n.t("guide.globalNote"),
        )
        Spacer(Modifier.height(16.dp))

        Text(io.acr.i18n.t("settings.permsTitle"), style = MaterialTheme.typography.titleSmall)
        Text(
            io.acr.i18n.t("settings.permsNote"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Los sitios de Jira conectados.
 *
 * Es una lista y no un ajuste único porque los tickets de estos repositorios salen de instancias
 * distintas: los proyectos KS y POS de una, CON/FIA/FIMA/TLOG de otra, FIS de otra. Con una sola
 * configuración, dos de las tres quedaban afuera.
 *
 * Cada sitio declara qué proyectos atiende, y por ahí se rutea cada ticket. Con un solo sitio
 * conectado no hace falta declarar nada: pedir esa lista cuando no hay ambigüedad es trabajo sin
 * motivo.
 *
 * Sólo lectura: la app mira los tickets, nunca los mueve. Autentica con Basic y el email, que es
 * como funciona Jira Cloud —al revés que Bitbucket, donde sólo anda Bearer—.
 */
@Composable
private fun JiraSettings(ctx: io.acr.AppContext) {
    var version by remember { mutableStateOf(0) }
    val sitios = io.acr.ui.dbState(version, initial = emptyList<io.acr.data.JiraSite>()) {
        ctx.jiraSites.list()
    }
    var editando by remember { mutableStateOf<io.acr.data.JiraSite?>(null) }
    var creando by remember { mutableStateOf(false) }
    var estado by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(io.acr.i18n.t("jira.title"), style = MaterialTheme.typography.labelLarge)
        Text(
            io.acr.i18n.t("jira.note"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))

        sitios.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.bodySmall)
                    Text(
                        s.baseUrl + "  ·  " +
                            (s.projects.takeIf { it.isNotEmpty() }?.joinToString(", ")
                                ?: io.acr.i18n.t("jira.allProjects")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { editando = s }) { Text(io.acr.i18n.t("common.edit")) }
                TextButton(onClick = { ctx.jiraSites.delete(s.id); version++ }) {
                    Text(io.acr.i18n.t("common.delete"), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = { creando = true }) { Text(io.acr.i18n.t("jira.add")) }
        estado?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (creando || editando != null) {
            JiraSiteDialog(
                ctx = ctx,
                sitio = editando,
                onDismiss = { creando = false; editando = null },
                onSaved = { creando = false; editando = null; version++ },
                onEstado = { estado = it },
            )
        }
    }
}

/** Alta y edición de un sitio, con la prueba de conexión antes de guardar a ciegas. */
@Composable
private fun JiraSiteDialog(
    ctx: io.acr.AppContext,
    sitio: io.acr.data.JiraSite?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onEstado: (String) -> Unit,
) {
    var nombre by remember { mutableStateOf(sitio?.name.orEmpty()) }
    var url by remember { mutableStateOf(sitio?.baseUrl.orEmpty()) }
    var email by remember { mutableStateOf(sitio?.email.orEmpty()) }
    var token by remember { mutableStateOf("") }
    var proyectos by remember { mutableStateOf(sitio?.projects?.joinToString(",").orEmpty()) }
    var probando by remember { mutableStateOf(false) }
    var resultado by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(io.acr.i18n.t(if (sitio == null) "jira.add" else "jira.editSite")) },
        text = {
            Column(Modifier.width(560.dp)) {
                OutlinedTextField(
                    value = nombre, onValueChange = { nombre = it },
                    label = { Text(io.acr.i18n.t("jira.name")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text(io.acr.i18n.t("jira.url")) },
                    placeholder = { Text("https://tuempresa.atlassian.net") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = email, onValueChange = { email = it },
                        label = { Text(io.acr.i18n.t("jira.email")) },
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = token, onValueChange = { token = it },
                        // Al editar, vacío significa "dejá el que está" y no "borralo".
                        label = { Text(io.acr.i18n.t(if (sitio == null) "jira.token" else "jira.tokenKeep")) },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = proyectos, onValueChange = { proyectos = it },
                    label = { Text(io.acr.i18n.t("jira.projects")) },
                    placeholder = { Text("KS,POS") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    io.acr.i18n.t("jira.projectsNote"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                resultado?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            Row {
                // Probar antes de guardar: una credencial mal puesta se descubre acá y no en la
                // mitad de una review.
                TextButton(
                    enabled = !probando && url.isNotBlank() && email.isNotBlank() &&
                        (token.isNotBlank() || sitio?.token != null),
                    onClick = {
                        probando = true
                        scope.launch {
                            val cfg = io.acr.jira.JiraConfig(url, email, token.ifBlank { sitio?.token.orEmpty() })
                            io.acr.jira.JiraClient(cfg).check()
                                .onSuccess { resultado = io.acr.i18n.t2("jira.ok", it) }
                                .onFailure { resultado = it.message ?: "falló" }
                            probando = false
                        }
                    },
                ) { Text(io.acr.i18n.t("jira.check")) }
                TextButton(
                    enabled = url.isNotBlank() && email.isNotBlank(),
                    onClick = {
                        ctx.jiraSites.save(
                            sitio?.id,
                            nombre.ifBlank { url.removePrefix("https://").substringBefore('.') },
                            url, email, token.takeIf { it.isNotBlank() }, proyectos,
                        )
                        onEstado(io.acr.i18n.t2("jira.saved"))
                        onSaved()
                    },
                ) { Text(io.acr.i18n.t("common.save")) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(io.acr.i18n.t("common.cancel")) } },
    )
}
