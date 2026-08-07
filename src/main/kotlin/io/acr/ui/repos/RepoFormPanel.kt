package io.acr.ui.repos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.acr.AppContext
import io.acr.claude.ClaudeCli
import io.acr.claude.ModelCatalog
import io.acr.claude.ProjectKind
import io.acr.claude.ReviewDepth
import io.acr.forge.Forges
import io.acr.forge.Provider
import io.acr.forge.ReplyMode
import io.acr.forge.RepoRecord
import io.acr.forge.SkipRules
import io.acr.i18n.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Alta y edición de un repositorio, como pantalla y no como modal.
 *
 * Son quince campos repartidos en cuatro temas distintos —conexión, perfil de review, barrido
 * automático y respuestas—. En un diálogo entraban a fuerza de scroll dentro de una caja de
 * 520dp, con el botón de guardar fuera de vista y sin lugar para explicar qué hace cada cosa.
 * Acá cada tema es una tarjeta, la barra de acciones queda fija abajo y en ventanas anchas se
 * usan dos columnas en vez de desperdiciar el ancho.
 *
 * El único modal que queda es la confirmación de borrado, que es donde un modal sí corresponde:
 * interrumpe a propósito porque la acción no tiene vuelta atrás.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RepoFormPanel(
    ctx: AppContext,
    existing: RepoRecord?,
    onCancel: () -> Unit,
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
    var model by remember { mutableStateOf(existing?.defaultModel ?: ModelCatalog.AUTO) }
    var models by remember { mutableStateOf(listOf(ModelCatalog.AUTO)) }
    var modelOpen by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var autoReview by remember { mutableStateOf(existing?.autoReview ?: false) }
    val baseSkip = existing?.skipRules ?: SkipRules()
    var skipDrafts by remember { mutableStateOf(baseSkip.skipDrafts) }
    var skipTitles by remember { mutableStateOf(baseSkip.skipTitles) }
    var skipAuthors by remember { mutableStateOf(baseSkip.skipAuthors) }
    var onlyTargets by remember { mutableStateOf(baseSkip.onlyTargets) }
    var replyMode by remember { mutableStateOf(existing?.replyMode ?: ReplyMode.DRAFT) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        models = ModelCatalog.discover(ClaudeCli.resolveBinary(ctx.prefs.get(AppContext.PREF_CLAUDE_BINARY)))
    }

    val isEdit = existing != null
    val pathMissing = localPath.isNotBlank() && !File(localPath, ".git").exists()
    val valid = name.isNotBlank() && owner.isNotBlank() && slug.isNotBlank() &&
        localPath.isNotBlank() && !pathMissing

    // Borrar un repo arrastra en cascada reviews, publicaciones, hilo y notas locales.
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(t("repo.deleteTitle").format(existing.name)) },
            text = { Text(t("repo.deleteBody")) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { ctx.repos.delete(existing.id) }
                    }
                    confirmDelete = false
                    onDeleted()
                }) {
                    Text(t("repo.deleteAll"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(t("common.cancel")) } },
        )
    }

    // --- Tarjetas ------------------------------------------------------------------------

    // Los mensajes de la prueba se resuelven acá: se usan dentro de onClick y de una corrutina,
    // y `t()` es @Composable, así que no se puede llamar desde ahí.
    val msgTesting = t("repo.testing")
    val msgTestOk = t("repo.testOk")
    val msgTestFail = t("repo.testFail")

    val connection: @Composable () -> Unit = {
        FormCard(t("repo.sectionConnection"), t("repo.sectionConnectionNote")) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text(t("repo.name")) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
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
                    label = { Text(t("repo.provider")) },
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
                    label = {
                        Text(if (provider == Provider.BITBUCKET) t("repo.workspace") else t("repo.owner"))
                    },
                    singleLine = true, enabled = !isEdit, modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = slug, onValueChange = { slug = it },
                    label = { Text(t("repo.slug")) }, singleLine = true,
                    enabled = !isEdit, modifier = Modifier.weight(1f),
                )
            }
            if (isEdit) {
                Text(
                    t("repo.coordsLocked"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = localPath, onValueChange = { localPath = it },
                label = { Text(t("repo.localPath")) },
                supportingText = {
                    Text(if (pathMissing) t("repo.localPathMissing") else t("repo.localPathNote"))
                },
                singleLine = true, isError = pathMissing,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text(if (isEdit) t("repo.tokenKeep") else t("repo.token")) },
                supportingText = {
                    Text(
                        if (provider == Provider.BITBUCKET) t("repo.tokenNoteBitbucket")
                        else t("repo.tokenNoteGithub"),
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = valid && !busy,
                    onClick = {
                        busy = true
                        status = msgTesting
                        scope.launch {
                            val probe = RepoRecord(
                                id = "probe", name = name, provider = provider, owner = owner.trim(),
                                slug = slug.trim(), localPath = localPath.trim(),
                                token = token.trim().ifBlank { existing?.token },
                            )
                            status = runCatching {
                                withContext(Dispatchers.IO) { Forges.of(provider).listPullRequests(probe) }
                            }.fold(
                                { msgTestOk.format(it.size) },
                                { msgTestFail.format(it.message?.take(160) ?: "?") },
                            )
                            busy = false
                        }
                    },
                ) { Text(t("repo.test")) }
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
    }

    val profile: @Composable () -> Unit = {
        FormCard(t("repo.sectionProfile"), t("repo.sectionProfileNote")) {
            Text(t("repo.projectKind"), style = MaterialTheme.typography.labelLarge)
            Text(
                t("repo.projectKindNote"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(kind == null, { kind = null }, { Text(t("repo.auto")) })
                ProjectKind.entries.forEach { k ->
                    FilterChip(kind == k, { kind = k }, { Text(k.label) })
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(t("repo.depth"), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(depth == null, { depth = null }, { Text(t("repo.auto")) })
                ReviewDepth.entries.forEach { d ->
                    FilterChip(depth == d, { depth = d }, { Text(d.label) })
                }
            }
            Text(
                depth?.blurb ?: t("repo.depthAuto"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))
            ExposedDropdownMenuBox(modelOpen, { modelOpen = !modelOpen }) {
                OutlinedTextField(
                    value = ModelCatalog.label(model),
                    onValueChange = { model = it },
                    label = { Text(t("repo.modelLabel")) },
                    supportingText = { Text(t("repo.modelNote")) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelOpen) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(modelOpen, { modelOpen = false }) {
                    (listOf(ModelCatalog.AUTO) + models).distinct().forEach { m ->
                        DropdownMenuItem(
                            text = { Text(ModelCatalog.label(m)) },
                            onClick = { model = m; modelOpen = false },
                        )
                    }
                }
            }
        }
    }

    val automatic: @Composable () -> Unit = {
        FormCard(t("repo.sectionAuto"), null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = autoReview, onCheckedChange = { autoReview = it })
                Column(Modifier.padding(start = 10.dp)) {
                    Text(t("repo.autoReview"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        t("repo.autoReviewNote"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Las reglas de exclusión sólo tienen efecto en el barrido automático.
            if (autoReview) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(t("repo.skipTitle"), style = MaterialTheme.typography.labelLarge)
                Text(
                    t("repo.skipNote"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = skipDrafts, onCheckedChange = { skipDrafts = it })
                    Text(t("repo.skipDrafts"), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = skipTitles, onValueChange = { skipTitles = it },
                    label = { Text(t("repo.skipTitles")) },
                    supportingText = { Text(t("repo.skipTitlesNote")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = skipAuthors, onValueChange = { skipAuthors = it },
                    label = { Text(t("repo.skipAuthors")) },
                    supportingText = { Text(t("repo.skipAuthorsNote")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = onlyTargets, onValueChange = { onlyTargets = it },
                    label = { Text(t("repo.onlyTargets")) },
                    supportingText = { Text(t("repo.onlyTargetsNote")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    val replies: @Composable () -> Unit = {
        FormCard(t("repo.replyMode"), t("repo.replyModeNote")) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReplyMode.entries.forEach { m ->
                    FilterChip(replyMode == m, { replyMode = m }, { Text(t("repo.replyMode." + m.name)) })
                }
            }
            if (replyMode == ReplyMode.AUTO) {
                Text(
                    t("repo.replyModeAutoWarn"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    // --- Armado --------------------------------------------------------------------------

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp)) {
            Text(
                if (isEdit) t("repo.edit") else t("repo.connect"),
                style = MaterialTheme.typography.headlineSmall,
            )
            if (isEdit) {
                Text(
                    "${existing!!.provider.label} · ${existing.owner}/${existing.slug}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()

        BoxWithConstraints(Modifier.weight(1f)) {
            // Dos columnas sólo si sobra ancho de verdad; si no, una sola y se scrollea.
            val twoColumns = maxWidth >= 1000.dp
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (twoColumns) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            connection()
                            replies()
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            profile()
                            automatic()
                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth().widthIn(max = 720.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        connection()
                        profile()
                        automatic()
                        replies()
                    }
                }
            }
        }

        // Barra fija: en el modal el botón de guardar quedaba abajo del scroll interno.
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isEdit) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text(t("common.delete"), color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text(t("common.cancel")) }
            Button(
                enabled = valid && !busy,
                onClick = {
                    val rules = SkipRules(
                        skipDrafts, skipTitles.trim(), skipAuthors.trim(), onlyTargets.trim(),
                    )
                    val id = if (isEdit) {
                        ctx.repos.update(
                            existing!!.id, name.trim(), localPath.trim(), token.trim(),
                            kind, depth, model.trim(), autoReview, rules, replyMode,
                        )
                        existing.id
                    } else {
                        ctx.repos.create(
                            name.trim(), provider, owner.trim(), slug.trim(), localPath.trim(),
                            token.trim().ifBlank { null }, kind, depth, model.trim(), autoReview,
                            rules, replyMode,
                        )
                    }
                    onSaved(id)
                },
            ) { Text(t("common.save")) }
        }
    }
}

/** Bloque con título y, si hace falta, una línea que explica de qué se trata. */
@Composable
private fun FormCard(title: String, note: String?, body: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        body()
    }
}
