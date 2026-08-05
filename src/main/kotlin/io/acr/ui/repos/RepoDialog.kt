package io.acr.ui.repos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.acr.AppContext
import io.acr.forge.Forges
import io.acr.forge.Provider
import io.acr.forge.RepoRecord
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RepoDialog(
    ctx: AppContext,
    existing: RepoRecord?,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var provider by remember { mutableStateOf(existing?.provider ?: Provider.BITBUCKET) }
    var owner by remember { mutableStateOf(existing?.owner ?: "") }
    var slug by remember { mutableStateOf(existing?.slug ?: "") }
    var localPath by remember { mutableStateOf(existing?.localPath ?: "") }
    var token by remember { mutableStateOf("") }
    var providerOpen by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(existing?.projectKind) }
    var depth by remember { mutableStateOf(existing?.defaultDepth) }
    var model by remember { mutableStateOf(existing?.defaultModel ?: io.acr.claude.ModelCatalog.AUTO) }
    var models by remember { mutableStateOf(listOf(io.acr.claude.ModelCatalog.AUTO)) }
    var modelOpen by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var autoReview by remember { mutableStateOf(existing?.autoReview ?: false) }
    val baseSkip = existing?.skipRules ?: io.acr.forge.SkipRules()
    var skipDrafts by remember { mutableStateOf(baseSkip.skipDrafts) }
    var skipTitles by remember { mutableStateOf(baseSkip.skipTitles) }
    var skipAuthors by remember { mutableStateOf(baseSkip.skipAuthors) }
    var onlyTargets by remember { mutableStateOf(baseSkip.onlyTargets) }
    var replyMode by remember { mutableStateOf(existing?.replyMode ?: io.acr.forge.ReplyMode.DRAFT) }
    var busy by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        models = io.acr.claude.ModelCatalog.discover(
            io.acr.claude.ClaudeCli.resolveBinary(ctx.prefs.get(AppContext.PREF_CLAUDE_BINARY)),
        )
    }

    val isEdit = existing != null
    val pathIsRepo = localPath.isNotBlank() && File(localPath, ".git").exists()
    val valid = name.isNotBlank() && owner.isNotBlank() && slug.isNotBlank() && pathIsRepo

    // Borrar un repo arrastra en cascada reviews, publicaciones, hilo y notas locales. Antes se
    // hacía con un solo click, sin confirmación y sin vuelta atrás.
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Eliminar «${existing.name}»?") },
            text = {
                Text(
                    "Se borra el repositorio y, en cascada, todo su historial: reviews, " +
                        "publicaciones, el hilo de comentarios sincronizado y tus notas locales. " +
                        "No se puede deshacer.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            ctx.repos.delete(existing.id)
                        }
                    }
                    confirmDelete = false
                    onDeleted()
                }) {
                    Text("Eliminar todo", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Editar repositorio" else "Conectar repositorio") },
        text = {
            Column(
                Modifier.width(520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )

                ExposedDropdownMenuBox(
                    expanded = providerOpen,
                    onExpandedChange = { if (!isEdit) providerOpen = !providerOpen },
                ) {
                    OutlinedTextField(
                        value = provider.label,
                        onValueChange = {},
                        readOnly = true,
                        enabled = !isEdit,
                        label = { Text("Proveedor") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerOpen) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(providerOpen, { providerOpen = false }) {
                        Provider.entries.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.label) },
                                onClick = { provider = p; providerOpen = false },
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = owner, onValueChange = { owner = it },
                        label = { Text(if (provider == Provider.BITBUCKET) "Workspace" else "Owner") },
                        singleLine = true, enabled = !isEdit, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = slug, onValueChange = { slug = it },
                        label = { Text("Repositorio") }, singleLine = true,
                        enabled = !isEdit, modifier = Modifier.weight(1f),
                    )
                }

                OutlinedTextField(
                    value = localPath, onValueChange = { localPath = it },
                    label = { Text("Ruta del working copy local") },
                    supportingText = { Text("La review corre acá: debe ser un clon git con remote origin.") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = localPath.isNotBlank() && !File(localPath, ".git").exists(),
                )

                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text(if (isEdit) "Token (vacío = conservar el guardado)" else "API token") },
                    supportingText = {
                        Text(
                            if (provider == Provider.BITBUCKET)
                                "Atlassian API token con scope read:pullrequest:bitbucket. Se guarda cifrado con AES-256-GCM."
                            else "PAT con permiso de lectura de PRs. Se guarda cifrado con AES-256-GCM.",
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )

                Text("Tipo de proyecto", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Define en qué se enfoca la review.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    androidx.compose.material3.FilterChip(
                        selected = kind == null,
                        onClick = { kind = null },
                        label = { Text("Auto") },
                    )
                    io.acr.claude.ProjectKind.entries.forEach { k ->
                        androidx.compose.material3.FilterChip(
                            selected = kind == k,
                            onClick = { kind = k },
                            label = { Text(k.label) },
                        )
                    }
                }

                Text("Profundidad por defecto", style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    androidx.compose.material3.FilterChip(
                        selected = depth == null,
                        onClick = { depth = null },
                        label = { Text("Auto") },
                    )
                    io.acr.claude.ReviewDepth.entries.forEach { d ->
                        androidx.compose.material3.FilterChip(
                            selected = depth == d,
                            onClick = { depth = d },
                            label = { Text(d.label) },
                        )
                    }
                }
                Text(
                    depth?.blurb ?: "Se infiere del diff: tamaño del cambio y archivos sensibles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("Modelo", style = MaterialTheme.typography.labelLarge)
                ExposedDropdownMenuBox(modelOpen, { modelOpen = !modelOpen }) {
                    OutlinedTextField(
                        value = io.acr.claude.ModelCatalog.label(model),
                        onValueChange = { model = it },
                        label = { Text("Modelo de Claude") },
                        supportingText = {
                            Text("Descubiertos del CLI local. Podés escribir cualquier otro a mano.")
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelOpen) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(modelOpen, { modelOpen = false }) {
                        (listOf(io.acr.claude.ModelCatalog.AUTO) + models)
                            .distinct()
                            .forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(io.acr.claude.ModelCatalog.label(m)) },
                                    onClick = { model = m; modelOpen = false },
                                )
                            }
                    }
                }

                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(checked = autoReview, onCheckedChange = { autoReview = it })
                    Column(Modifier.padding(start = 10.dp)) {
                        Text("Revisar los PRs nuevos automáticamente", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Cuando aparezca un PR (o un commit nuevo en uno existente) la app lo " +
                                "revisa sola y deja la review lista. Nunca publica: eso lo hacés vos.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Reglas de exclusión: sólo tienen efecto en el barrido automático.
                if (autoReview) {
                    Text(io.acr.i18n.t("repo.skipTitle"), style = MaterialTheme.typography.labelLarge)
                    Text(
                        io.acr.i18n.t("repo.skipNote"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = skipDrafts,
                            onCheckedChange = { skipDrafts = it },
                        )
                        Text(io.acr.i18n.t("repo.skipDrafts"), style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(
                        value = skipTitles,
                        onValueChange = { skipTitles = it },
                        label = { Text(io.acr.i18n.t("repo.skipTitles")) },
                        supportingText = { Text(io.acr.i18n.t("repo.skipTitlesNote")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = skipAuthors,
                        onValueChange = { skipAuthors = it },
                        label = { Text(io.acr.i18n.t("repo.skipAuthors")) },
                        supportingText = { Text(io.acr.i18n.t("repo.skipAuthorsNote")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = onlyTargets,
                        onValueChange = { onlyTargets = it },
                        label = { Text(io.acr.i18n.t("repo.onlyTargets")) },
                        supportingText = { Text(io.acr.i18n.t("repo.onlyTargetsNote")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Text(io.acr.i18n.t("repo.replyMode"), style = MaterialTheme.typography.labelLarge)
                Text(
                    io.acr.i18n.t("repo.replyModeNote"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    io.acr.forge.ReplyMode.entries.forEach { m ->
                        androidx.compose.material3.FilterChip(
                            selected = replyMode == m,
                            onClick = { replyMode = m },
                            label = { Text(io.acr.i18n.t("repo.replyMode." + m.name)) },
                        )
                    }
                }
                if (replyMode == io.acr.forge.ReplyMode.AUTO) {
                    Text(
                        io.acr.i18n.t("repo.replyModeAutoWarn"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !busy,
                onClick = {
                    val id = if (isEdit) {
                        ctx.repos.update(existing!!.id, name.trim(), localPath.trim(), token.trim(), kind, depth, model.trim(), autoReview,
                            io.acr.forge.SkipRules(skipDrafts, skipTitles.trim(), skipAuthors.trim(), onlyTargets.trim()), replyMode)
                        existing.id
                    } else {
                        ctx.repos.create(
                            name.trim(), provider, owner.trim(), slug.trim(),
                            localPath.trim(), token.trim().ifBlank { null }, kind, depth, model.trim(), autoReview,
                            io.acr.forge.SkipRules(skipDrafts, skipTitles.trim(), skipAuthors.trim(), onlyTargets.trim()), replyMode,
                        )
                    }
                    onSaved(id)
                },
            ) { Text("Guardar") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isEdit) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Eliminar", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(
                    enabled = valid && !busy,
                    onClick = {
                        busy = true
                        status = "Probando…"
                        scope.launch {
                            val probe = RepoRecord(
                                id = "probe", name = name, provider = provider, owner = owner.trim(),
                                slug = slug.trim(), localPath = localPath.trim(),
                                token = token.trim().ifBlank { existing?.token },
                            )
                            status = runCatching { Forges.of(provider).listPullRequests(probe) }
                                .fold(
                                    { "Conecta. ${it.size} PR abiertos." },
                                    { "Falló: ${it.message?.take(160)}" },
                                )
                            busy = false
                        }
                    },
                ) { Text("Probar") }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}
