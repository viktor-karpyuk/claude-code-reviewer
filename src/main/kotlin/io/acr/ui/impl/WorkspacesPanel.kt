package io.acr.ui.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.acr.AppContext
import io.acr.i18n.t
import io.acr.impl.Workspace
import kotlinx.coroutines.launch

/**
 * Los talleres: qué hay en disco y si se puede tirar.
 *
 * Un workspace puede tener el único ejemplar del trabajo de una tarde, así que esta pantalla existe
 * sobre todo para contestar una pregunta: **¿esto ya está a salvo en el clon?** Se contesta comparando
 * el sha de la rama a los dos lados, no mirando fechas ni tamaños — dos números iguales son una
 * prueba, "terminó hace rato" no lo es.
 *
 * El botón de borrar se ofrece sólo cuando la comparación dio bien. El de devolver está siempre,
 * porque es la acción que hace que borrar sea seguro.
 */
@Composable
fun WorkspacesPanel(ctx: AppContext) {
    var version by remember { mutableStateOf(0) }
    var trabajando by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val impls = io.acr.ui.dbState(version, initial = emptyList<io.acr.impl.Implementation>()) {
        ctx.impls.list()
    }
    val talleres = io.acr.ui.dbState(version, impls, initial = emptyList<Workspace>()) {
        kotlinx.coroutines.runBlocking {
            impls.mapNotNull { impl ->
                val repos = ctx.impls.reposOf(impl.id)
                    .mapNotNull { r -> ctx.repos.get(r.repoId) }
                val w = ctx.workspaces.inspect(impl.id, impl.title, repos, impl.branch)
                w.takeIf { it.exists }
            }
        }
    }
    // Carpetas que no corresponden a ninguna implementación: quedan si alguien borra una
    // implementación mientras su taller existía. Se muestran aparte porque no se puede verificar
    // nada de ellas — no hay rama ni clon con el que comparar.
    val huerfanos = io.acr.ui.dbState(version, impls, initial = emptyList<java.io.File>()) {
        ctx.workspaces.orphans(impls.map { it.id }.toSet())
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("ws.title"), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(10.dp))
            Text(
                t("ws.note"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // El total, que es la pregunta que trae a alguien acá cuando el disco aprieta. Y el
            // recuento de lo que todavía no se devolvió, que es lo único que pide una decisión.
            val total = talleres.sumOf { it.sizeBytes }
            val sinDevolver = talleres.count { !it.safeToDelete }
            if (talleres.isNotEmpty()) {
                Text(
                    t("ws.total", mb(total), talleres.size) +
                        (if (sinDevolver > 0) "  ·  " + t("ws.pending", sinDevolver) else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (sinDevolver > 0) StatusColors.NEEDS_HUMAN
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                ctx.workspaces.rootDir().absolutePath,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))

        if (talleres.isEmpty() && huerfanos.isEmpty()) {
            Text(
                t("ws.none"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Primero lo que pide una decisión. Un taller con trabajo sin devolver entre diez que están
        // limpios se pierde de vista, y es el único que importa.
        talleres.sortedBy { it.safeToDelete }.forEach { w ->
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(w.implTitle, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            t("ws.size", mb(w.sizeBytes)) + "  ·  " + t("ws.repos", w.repos.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (w.safeToDelete) t("ws.safe") else t("ws.unsynced"),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (w.safeToDelete) StatusColors.DONE else StatusColors.NEEDS_HUMAN,
                    )
                    if (trabajando == w.implId) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                    }
                }

                // Repositorio por repositorio, con los dos shas. Es la prueba de que el trabajo está
                // del otro lado; sin verlos, "se puede borrar" es una afirmación que hay que creer.
                w.repos.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(
                            if (r.synced) "✓" else "!",
                            color = if (r.synced) StatusColors.DONE else StatusColors.NEEDS_HUMAN,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(18.dp),
                        )
                        Text(
                            r.name,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(160.dp),
                            maxLines = 1,
                        )
                        Text(
                            (r.head?.take(7) ?: "—") + " → " + (r.originHead?.take(7) ?: "—"),
                            style = MaterialTheme.typography.labelSmall
                                .copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(160.dp),
                        )
                        Text(
                            mb(r.sizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val impl = impls.firstOrNull { it.id == w.implId }
                    io.acr.ui.InfoTip(t("ws.syncTip"), t("ws.syncTipOut")) {
                        TextButton(
                            enabled = trabajando == null && impl?.branch != null,
                            onClick = {
                                trabajando = w.implId
                                scope.launch {
                                    val repos = ctx.impls.reposOf(w.implId).mapNotNull { ctx.repos.get(it.repoId) }
                                    ctx.workspaces.syncBack(w.implId, repos, impl!!.branch!!)
                                    trabajando = null
                                    version++
                                }
                            },
                        ) { Text(t("ws.sync")) }
                    }
                    io.acr.ui.InfoTip(t("ws.deleteTip"), t("ws.deleteTipOut")) {
                        TextButton(
                            // Sólo si los shas coinciden. El botón no se ofrece cuando borrar
                            // significaría perder algo: una confirmación no alcanza para una acción
                            // que no tiene vuelta.
                            enabled = trabajando == null && w.safeToDelete,
                            onClick = {
                                trabajando = w.implId
                                scope.launch {
                                    val repos = ctx.impls.reposOf(w.implId).mapNotNull { ctx.repos.get(it.repoId) }
                                    ctx.workspaces.delete(w.implId, repos, impl?.branch)
                                    trabajando = null
                                    version++
                                }
                            },
                        ) { Text(t("ws.delete")) }
                    }
                    TextButton(onClick = {
                        runCatching { java.awt.Desktop.getDesktop().open(java.io.File(w.path)) }
                    }) { Text(t("impl.openRepo")) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (huerfanos.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(
                t("ws.orphans", huerfanos.size),
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.NEEDS_HUMAN,
            )
            Text(
                t("ws.orphansNote"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            huerfanos.forEach { d ->
                var confirmando by remember(d.path) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        d.name,
                        style = MaterialTheme.typography.labelSmall
                            .copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    TextButton(onClick = {
                        runCatching { java.awt.Desktop.getDesktop().open(d) }
                    }) { Text(t("impl.openRepo")) }
                    // Se puede borrar, pero a ciegas y diciéndolo. Sin implementación no hay rama ni
                    // clon con el que comparar, así que la app no puede afirmar que no se pierde
                    // nada. Dejarlo sin salida sería peor: la carpeta quedaría para siempre.
                    TextButton(onClick = {
                        if (!confirmando) {
                            confirmando = true
                        } else {
                            runCatching { d.deleteRecursively() }
                            version++
                        }
                    }) {
                        Text(if (confirmando) t("ws.orphanConfirm") else t("ws.delete"))
                    }
                    if (confirmando) {
                        Text(
                            t("ws.orphanWarn"),
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.FAILED,
                        )
                    }
                }
            }
        }
    }
}

/** En megas: los bytes de un repositorio clonado no se leen. */
private fun mb(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024L * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}
