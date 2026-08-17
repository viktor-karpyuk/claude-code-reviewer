package io.acr.ui.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.acr.AppContext
import io.acr.forge.RepoRecord
import io.acr.i18n.t
import io.acr.impl.ImplRepo
import io.acr.impl.Implementation

/**
 * Alta y edición de una implementación, como pantalla.
 *
 * Era un modal de 640 de ancho y se le fueron sumando cosas —repositorios con su rol y su rama
 * base, documentos, la rama nueva, las pasadas de revisión— hasta que en una pantalla de catorce
 * pulgadas el botón de borrar un documento quedaba cortado por la mitad. Un modal tiene sentido para
 * una pregunta; esto es un formulario con seis decisiones, y cada una necesita explicarse.
 *
 * Como pantalla puede scrollear, usar el ancho que hay, y —lo que más importa— mostrar cada
 * documento encontrado en vez de la ruta de la carpeta: elegir un directorio y ver "1 elemento" es
 * un acto de fe que se paga cuando el plan sale mal.
 */
@Composable
fun ImplForm(
    ctx: AppContext,
    repos: List<RepoRecord>,
    /** null = alta. Con valor, se edita esa. */
    impl: Implementation?,
    actuales: List<ImplRepo> = emptyList(),
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
) {
    var titulo by remember(impl?.id) { mutableStateOf(impl?.title.orEmpty()) }
    var elegidos by remember(impl?.id) { mutableStateOf(actuales) }
    var rutas by remember(impl?.id) { mutableStateOf(impl?.sources.orEmpty()) }
    var extra by remember(impl?.id) { mutableStateOf(impl?.extraPrompt.orEmpty()) }
    var ramaAuto by remember(impl?.id) { mutableStateOf(impl?.branchFixed != true) }
    var rama by remember(impl?.id) { mutableStateOf(impl?.branch.orEmpty()) }
    var revMin by remember(impl?.id) { mutableStateOf(impl?.reviewMin ?: 2) }
    var revMax by remember(impl?.id) { mutableStateOf(impl?.reviewMax ?: 5) }
    var revCada by remember(impl?.id) { mutableStateOf(impl?.reviewEach ?: false) }
    var replanificar by remember(impl?.id) { mutableStateOf(false) }

    // Los documentos de verdad, no las rutas. Es la diferencia entre "elegí una carpeta" y "estos
    // son los nueve archivos que se van a leer".
    val docs = remember(rutas) { io.acr.impl.loadSources(rutas) }
    val hechas = impl?.let { i ->
        io.acr.ui.dbState(i.id, initial = 0) {
            ctx.impls.tasks(i.id).count { it.status == io.acr.impl.TaskStatus.DONE }
        }
    } ?: 0

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        io.acr.ui.Breadcrumbs(
            listOf(
                io.acr.ui.Crumb(t("nav.impls"), onBack),
                io.acr.ui.Crumb(if (impl == null) t("impl.new") else t("impl.edit")),
            ),
            Modifier.padding(bottom = 16.dp),
        )

        OutlinedTextField(
            value = titulo,
            onValueChange = { titulo = it },
            label = { Text(t("impl.name")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(18.dp))
        Seccion(t("impl.fRepos"), t("impl.fReposNote"))
        RepoPicker(
            repos = repos,
            elegidos = elegidos,
            onChange = { elegidos = it },
            // Sólo al dar de alta: en una edición los roles ya los decidió alguien, y volver a
            // sugerir pisaría esa decisión con una adivinanza.
            sugerirRol = if (impl == null) ::adivinarRol else ({ io.acr.impl.RepoRole.OTHER }),
            onAddFolder = {
                elegirCarpeta()?.let { ruta ->
                    val dir = java.io.File(ruta)
                    // Si no es un repositorio de git se le da uno. El commit por tarea es la red de
                    // seguridad del módulo entero: sin historial, que la séptima tarea falle se
                    // lleva puesto el trabajo de las seis anteriores. Inicializar no toca nada de
                    // lo que ya hay adentro.
                    kotlinx.coroutines.runBlocking { io.acr.claude.Git.init(dir) }
                    ctx.repos.createLocal(ruta)
                }
            },
        )

        // --- La rama nueva ---
        Spacer(Modifier.height(18.dp))
        Seccion(t("impl.fBranch"), t("impl.fBranchNote"))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = ramaAuto, onCheckedChange = { ramaAuto = it })
                Text(t("impl.fBranchAuto"), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = if (ramaAuto) "" else rama,
                onValueChange = { rama = it.trim().replace(' ', '-') },
                label = { Text(t("impl.fBranchName")) },
                placeholder = { Text(if (ramaAuto) t("impl.fBranchAutoHint") else "feature-x") },
                enabled = !ramaAuto,
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }

        // --- Documentos ---
        Spacer(Modifier.height(18.dp))
        Seccion(t("impl.docs"), t("impl.docsNote"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { elegirDocs(false)?.let { rutas = rutas + it } }) {
                Text(t("impl.addFiles"))
            }
            OutlinedButton(onClick = { elegirDocs(true)?.let { rutas = rutas + it } }) {
                Text(t("impl.addFolder"))
            }
        }
        if (rutas.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    rutas.forEach { r ->
                        // Cada origen con su botón propio, en su renglón, con el nombre por delante
                        // de la ruta: en una pantalla angosta lo que se corta es el final de la
                        // ruta, no la acción.
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    r.substringAfterLast('/').ifBlank { r },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                )
                                Text(
                                    r,
                                    style = MaterialTheme.typography.labelSmall
                                        .copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { rutas = rutas - r }) { Text(t("common.delete")) }
                        }
                    }

                    // Y los archivos que salen de ahí, uno por uno. Elegir una carpeta y ver sólo
                    // su ruta es un acto de fe: si adentro no había ningún .md, se sabe recién
                    // cuando el plan sale vacío.
                    Spacer(Modifier.height(10.dp))
                    Text(
                        t("impl.found", docs.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (docs.isEmpty()) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    docs.forEach { d ->
                        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                            Text(
                                "·",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(14.dp),
                            )
                            Text(
                                d.name,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            // El tamaño dice si un documento vino vacío o truncado, que es la
                            // otra forma de que el plan salga mal sin que nada falle.
                            Text(
                                t("impl.docChars", d.content.length),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // --- Revisión ---
        Spacer(Modifier.height(18.dp))
        Seccion(t("impl.fReview"), t("impl.fReviewNote"))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t("impl.fReviewMin"), style = MaterialTheme.typography.bodySmall)
            (0..5).forEach { n ->
                FilterChip(
                    selected = revMin == n,
                    onClick = { revMin = n; if (revMax < n) revMax = n },
                    label = { Text(n.toString()) },
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(t("impl.fReviewMax"), style = MaterialTheme.typography.bodySmall)
            (0..5).forEach { n ->
                FilterChip(
                    selected = revMax == n,
                    onClick = { revMax = n; if (revMin > n) revMin = n },
                    label = { Text(n.toString()) },
                )
            }
        }
        Text(
            if (revMax == 0) t("impl.fReviewOff") else t("impl.fReviewRange", revMin, revMax),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = revCada, onCheckedChange = { revCada = it }, enabled = revMax > 0)
            Column {
                Text(t("impl.fReviewEach"), style = MaterialTheme.typography.bodySmall)
                Text(
                    t("impl.fReviewEachNote"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // --- Parámetros ---
        Spacer(Modifier.height(18.dp))
        Seccion(t("impl.extra"), t("impl.extraNote"))
        OutlinedTextField(
            value = extra,
            onValueChange = { extra = it },
            placeholder = { Text(t("impl.extraPlaceholder")) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 200.dp),
            textStyle = MaterialTheme.typography.bodySmall,
        )

        if (impl != null) {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = replanificar,
                    onClick = { replanificar = !replanificar },
                    label = { Text(t("impl.replan")) },
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (replanificar) t("impl.replanOn", hechas) else t("impl.replanOff"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = titulo.isNotBlank() && elegidos.isNotEmpty() && docs.isNotEmpty() &&
                    (ramaAuto || rama.isNotBlank()),
                onClick = {
                    val laRama = if (ramaAuto) null else rama.trim()
                    val id = if (impl == null) {
                        ctx.impls.create(elegidos, titulo.trim(), rutas, extra.trim().takeIf { it.isNotBlank() })
                    } else {
                        ctx.impls.update(
                            impl.id, titulo.trim(), rutas,
                            extra.trim().takeIf { it.isNotBlank() }, elegidos,
                        )
                        // Tirar el plan pendiente es opcional y sólo toca lo que no se hizo: las
                        // tareas terminadas tienen su código commiteado y siguen existiendo.
                        if (replanificar) ctx.impls.clearPendingPlan(impl.id)
                        impl.id
                    }
                    // La rama se fija sólo si se escribió: en automático se deja como está para no
                    // pisar la que ya eligió el plan de una implementación en marcha.
                    if (!ramaAuto || impl?.branchFixed == true) ctx.impls.setBranch(id, laRama)
                    ctx.impls.setReviewPolicy(id, revMin, revMax, revCada)
                    onSaved(id)
                },
            ) { Text(t("common.save")) }
            OutlinedButton(onClick = onBack) { Text(t("common.cancel")) }
            if (docs.isEmpty() && rutas.isNotEmpty()) {
                Text(
                    t("impl.fNoDocs"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

/** Título de sección con su explicación debajo. Cada decisión del formulario dice para qué es. */
@Composable
private fun Seccion(titulo: String, nota: String) {
    Text(titulo, style = MaterialTheme.typography.titleSmall)
    Text(
        nota,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * Adivina el rol por el nombre del repositorio.
 *
 * Es una sugerencia y se puede cambiar de un click. Acertar la mayoría de las veces ahorra el
 * trabajo de clasificar a mano cinco repositorios cuyo nombre ya lo dice —`kubrik-erp-be`,
 * `pds-inspections-app`—, y equivocarse cuesta un toque.
 */
internal fun adivinarRol(nombre: String): io.acr.impl.RepoRole {
    val n = nombre.lowercase()
    return when {
        n.endsWith("-be") || n.contains("backend") || n.contains("apirest") || n.contains("api") ->
            io.acr.impl.RepoRole.BACKEND
        n.endsWith("-fe") || n.contains("frontend") || n.contains("-web") || n.contains("app") ->
            io.acr.impl.RepoRole.FRONTEND
        else -> io.acr.impl.RepoRole.OTHER
    }
}

/** Elegir una carpeta donde escribir el código. */
internal fun elegirCarpeta(): String? = elegirDocs(carpeta = true)?.firstOrNull()

/**
 * Selector nativo de archivos o carpetas.
 *
 * En macOS elegir un directorio con `FileDialog` requiere esta propiedad de sistema; sin ella el
 * diálogo deja seleccionar sólo archivos y no hay forma de apuntar a una carpeta de specs.
 */
internal fun elegirDocs(carpeta: Boolean): List<String>? {
    val previa = System.getProperty("apple.awt.fileDialogForDirectories")
    if (carpeta) System.setProperty("apple.awt.fileDialogForDirectories", "true")
    return try {
        val d = java.awt.FileDialog(null as java.awt.Frame?, if (carpeta) "Elegí la carpeta" else "Elegí los .md")
        d.isMultipleMode = !carpeta
        d.isVisible = true
        d.files?.map { it.absolutePath }?.takeIf { it.isNotEmpty() }
    } finally {
        if (previa == null) System.clearProperty("apple.awt.fileDialogForDirectories")
        else System.setProperty("apple.awt.fileDialogForDirectories", previa)
    }
}
