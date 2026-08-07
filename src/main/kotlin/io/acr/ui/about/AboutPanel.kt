package io.acr.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.acr.AppContext
import io.acr.AppVersion
import io.acr.claude.ClaudeCli
import io.acr.i18n.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Información de la app: versión, entorno, dónde viven los datos y qué está conectado.
 *
 * Todo es seleccionable y hay un botón que copia el bloque entero: cuando algo falla, lo que
 * hace falta es pegar este resumen en algún lado, no ir campo por campo.
 */
@Composable
fun AboutPanel(ctx: AppContext) {
    val clipboard = LocalClipboardManager.current
    var info by remember { mutableStateOf<List<Pair<String, List<Pair<String, String>>>>>(emptyList()) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        info = withContext(Dispatchers.IO) { collect(ctx) }
    }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_500)
            copied = false
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI Code Reviewer", style = MaterialTheme.typography.headlineSmall)
                Text(
                    t("app.subtitle") + " · v" + AppVersion.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(asText(info)))
                copied = true
            }) { Text(if (copied) t("common.copied") else t("about.copy")) }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()

        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                info.forEach { (section, rows) ->
                    item {
                        Spacer(Modifier.height(10.dp))
                        Text(section, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                    }
                    items(rows) { (k, v) -> InfoRow(k, v) }
                }
                item {
                    Spacer(Modifier.height(14.dp))
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(12.dp),
                    ) {
                        Text(t("about.postureTitle"), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(t("about.posture"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(240.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(1f),
        )
    }
}

private fun asText(info: List<Pair<String, List<Pair<String, String>>>>): String = buildString {
    appendLine("AI Code Reviewer v${AppVersion.value}")
    info.forEach { (section, rows) ->
        appendLine()
        appendLine("== $section")
        rows.forEach { (k, v) -> appendLine("$k: $v") }
    }
}

/** Se arma fuera del hilo de UI: consulta la base y ejecuta el CLI para pedirle su versión. */
private fun collect(ctx: AppContext): List<Pair<String, List<Pair<String, String>>>> {
    val binary = ClaudeCli.resolveBinary(ctx.prefs.get(AppContext.PREF_CLAUDE_BINARY))
    val cliVersion = binary?.let { runVersion(it) } ?: "no encontrado"
    val (applied, known) = ctx.store.schemaVersion()
    val dbFile = File(ctx.store.path)
    val repos = ctx.repos.list()
    val usage = ctx.reviews.usage()
    val totals = ctx.reviews.totals()

    return listOf(
        "Aplicación" to listOf(
            "Versión" to AppVersion.value,
            "Idioma" to io.acr.i18n.Lang.fromCode(ctx.prefs.get(AppContext.PREF_UI_LANG)).label,
            "Licencia" to "Apache License 2.0",
        ),
        "Motor" to listOf(
            "Claude Code" to cliVersion,
            "Ejecutable" to (binary ?: "—"),
            "Autenticación" to "sesión de Claude Code (sin API key)",
            "Modelo por defecto" to (ctx.prefs.get(AppContext.PREF_MODEL)?.takeIf { it.isNotBlank() }
                ?: "según el nivel de profundidad"),
            "Idioma de las reviews" to (ctx.prefs.get(AppContext.PREF_LANGUAGE) ?: "español"),
        ),
        "Entorno" to listOf(
            "Sistema" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})",
            "Java" to System.getProperty("java.version"),
            "Usuario" to System.getProperty("user.name"),
        ),
        "Datos" to listOf(
            "Base" to dbFile.absolutePath,
            "Tamaño" to "${dbFile.length() / 1024} KB",
            "Esquema" to "v$applied (este build conoce $known)",
            "Clave maestra" to File(System.getProperty("user.home"), ".acr/master.key").absolutePath,
        ),
        "Uso" to listOf(
            "Repositorios" to "${repos.size} (${repos.count { it.autoReview }} en automático)",
            "PRs revisados" to totals.prsReviewed.toString(),
            "Reviews" to "${totals.reviews} terminadas · ${totals.failed} fallidas",
            "Publicadas" to totals.published.toString(),
            "Origen" to "${totals.auto} automáticas · ${totals.manual} manuales",
            "Consumo" to "~US$ %.2f equivalente API · %,d tokens".format(usage.costUsd, usage.tokens),
        ),
        "Repositorios" to repos.map {
            it.name to "${it.owner}/${it.slug} · ${if (it.autoReview) "auto" else "manual"} · respuestas ${it.replyMode.name}"
        },
    )
}

/** `claude --version` con tope: si el binario está roto no puede colgar la pantalla. */
private fun runVersion(binary: String): String = runCatching {
    val p = ProcessBuilder(binary, "--version").redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly()
    out.ifBlank { "sin respuesta" }
}.getOrElse { "error: ${it.message}" }
