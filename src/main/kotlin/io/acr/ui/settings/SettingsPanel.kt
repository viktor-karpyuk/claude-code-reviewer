package io.acr.ui.settings

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

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(io.acr.i18n.t("settings.title"), style = MaterialTheme.typography.headlineSmall)
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
        Text(io.acr.i18n.t("settings.permsTitle"), style = MaterialTheme.typography.titleSmall)
        Text(
            io.acr.i18n.t("settings.permsNote"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
