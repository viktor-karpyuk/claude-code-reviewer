package io.acr.ui.repos

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.acr.AppContext
import io.acr.forge.RepoRecord
import io.acr.i18n.t

/**
 * La sección de repositorios: todos a la vista, con su estado y desde dónde agregar uno nuevo.
 *
 * Antes los repositorios sólo existían como una lista angosta pegada al costado, presente en toda
 * la app, y agregar uno era un ítem del menú principal. Dos cosas mal: la lista ocupaba lugar
 * también cuando estabas mirando el panel o las estadísticas, donde no sirve de nada; y "agregar
 * repositorio" quedaba al mismo nivel que las secciones, cuando es una acción de esta sección y no
 * un lugar al que ir.
 *
 * Acá cada repositorio es una tarjeta con lo que uno quiere saber de un vistazo —cuántos PRs tiene
 * abiertos, si hay reviews corriendo, cuándo fue la última— y desde acá se entra a su lista de
 * pull requests.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ReposPanel(
    ctx: AppContext,
    repos: List<RepoRecord>,
    running: Map<String, Int>,
    onOpen: (RepoRecord) -> Unit,
    onEdit: (RepoRecord) -> Unit,
    onAdd: () -> Unit,
) {
    // La foto del día, una vez por apertura de la sección y fuera del hilo de UI. Antes se
    // guardaba adentro de la lectura de cada tarjeta, así que escribía en cada recomposición.
    androidx.compose.runtime.LaunchedEffect(repos) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            repos.forEach { r -> runCatching { ctx.health.snapshot(r.id, ctx.health.current(r.id)) } }
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t("repos.title"), style = MaterialTheme.typography.titleMedium)
                Text(
                    t("repos.note"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // La acción principal de esta sección, acá y no en el menú: agregar un repositorio es
            // algo que se hace estando en repositorios, no un destino al que ir.
            Button(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.height(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("repos.add"))
            }
        }

        Spacer(Modifier.height(16.dp))

        if (repos.isEmpty()) {
            Text(
                t("repos.empty"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repos.forEach { repo ->
                RepoCard(
                    ctx = ctx,
                    repo = repo,
                    running = running[repo.id] ?: 0,
                    onOpen = { onOpen(repo) },
                    onEdit = { onEdit(repo) },
                )
            }
        }
    }
}

/**
 * Una tarjeta por repositorio, ordenada por lo que exige acción.
 *
 * Lo primero es la **deuda de revisión**: respuestas sin contestar, hallazgos publicados que nadie
 * verificó y reviews terminadas sin publicar. Son trabajo empezado que espera algo nuestro, y son
 * la única razón por la que uno abre esta pantalla. Van juntas y sumadas porque separadas cada una
 * parece chica: en esta instalación son 69 respuestas y 51 hallazgos repartidos de a poco entre
 * cinco repositorios, y así nadie los ve.
 *
 * Después el tamaño del trabajo —PRs abiertos, el más viejo— y sólo al final la densidad de
 * hallazgos y el costo por PR, que describen al repositorio y no urgen nada.
 */
@Composable
private fun RepoCard(
    ctx: AppContext,
    repo: RepoRecord,
    running: Int,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
) {
    // Leer y escribir estaban juntos acá, y eso hacía un INSERT por tarjeta cada vez que la
    // pantalla se recomponía: siete repositorios, siete escrituras, por cada vuelta de layout.
    // La foto del día se toma una vez por apertura de la sección, no por dibujo de una tarjeta.
    val salud = io.acr.ui.dbState(repo.id, initial = null as io.acr.data.RepoHealth?) {
        ctx.health.current(repo.id)
    }
    val historia = io.acr.ui.dbState(repo.id, salud, initial = emptyList<io.acr.data.RepoSnapshot>()) {
        ctx.health.history(repo.id)
    }

    // Alto fijo y no según el contenido: un repositorio sin deuda y sin historia todavía ocupa
    // menos que uno con las dos cosas, y con alturas distintas la grilla queda escalonada y cuesta
    // comparar de un vistazo, que es justo para lo que sirve verlos todos juntos.
    Column(
        Modifier.width(330.dp)
            .height(310.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(repo.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (running > 0) {
                CircularProgressIndicator(Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)
            }
        }
        Text(
            // Una carpeta local no tiene owner ni slug: los suyos existen sólo porque la tabla los
            // pedía, y mostrarlos haría parecer que hay un repositorio remoto detrás.
            if (repo.localOnly) io.acr.i18n.t("impl.localRepo") + " · " + repo.localPath
            else "${repo.owner}/${repo.slug}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val h = salud ?: return@Column
        Spacer(Modifier.height(12.dp))

        // --- Deuda: lo que espera algo nuestro ---
        if (h.debt > 0) {
            Text(
                t("repos.debt", h.debt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
            io.acr.ui.stats.BarRow(
                segments = listOfNotNull(
                    h.pendingReplies.takeIf { it > 0 }
                        ?.let { io.acr.ui.stats.Segment(it.toDouble(), io.acr.ui.stats.ChartColors.blocker, "") },
                    h.unverified.takeIf { it > 0 }
                        ?.let { io.acr.ui.stats.Segment(it.toDouble(), io.acr.ui.stats.ChartColors.major, "") },
                    h.unpublished.takeIf { it > 0 }
                        ?.let { io.acr.ui.stats.Segment(it.toDouble(), io.acr.ui.stats.ChartColors.neutral, "") },
                ),
                max = h.debt.toDouble(),
                height = 8.dp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(
                    h.pendingReplies.takeIf { it > 0 }?.let { t("repos.debtReplies", it) },
                    h.unverified.takeIf { it > 0 }?.let { t("repos.debtUnverified", it) },
                    h.unpublished.takeIf { it > 0 }?.let { t("repos.debtUnpublished", it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Con el alto fijo, una tercera línea acá empujaría las acciones fuera de la
                // tarjeta. Dos alcanzan para las tres clases de deuda.
                maxLines = 2,
            )
        } else {
            Text(
                t("repos.noDebt"),
                style = MaterialTheme.typography.labelMedium,
                color = ChartColorsOk(),
            )
        }

        Spacer(Modifier.height(12.dp))
        Row {
            Dato(t("repos.openPrs"), h.openPrs.toString())
            Spacer(Modifier.width(16.dp))
            Dato(t("repos.reviewed"), h.reviewedPrs.toString())
            if (h.reviewedPrs > 0) {
                Spacer(Modifier.width(16.dp))
                // Densidad y no total: un repositorio con más PRs revisados acumula más hallazgos
                // sin que eso diga nada de su código.
                Dato(t("repos.perPr"), "%.1f".format(h.findingsPerPr))
                Spacer(Modifier.width(16.dp))
                Dato(t("repos.costPerPr"), "$" + "%.0f".format(h.costPerPr))
            }
        }

        // La tendencia de la deuda: es lo que dice si esto se está acumulando o se está drenando,
        // que un número solo no puede contestar. Aparece recién con dos días guardados.
        if (historia.size > 1) {
            Spacer(Modifier.height(10.dp))
            Text(
                t("repos.trend", historia.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            io.acr.ui.stats.MiniBars(
                values = historia.map { (it.pendingReplies + it.unverified + it.unpublished).toDouble() },
                labels = emptyList(),
                height = 26.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            h.oldestPrDays?.let { t("repos.oldest", it) } ?: t("repos.neverReviewed"),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            color = if ((h.oldestPrDays ?: 0) > 14) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Empuja las acciones al pie: con el alto fijo, los botones de todas las tarjetas quedan
        // en la misma línea y el ojo no tiene que buscarlos tarjeta por tarjeta.
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onOpen) { Text(t("repos.openPrsAction")) }
            TextButton(onClick = onEdit) { Text(t("common.edit")) }
        }
    }
}

/** El verde de "no hay nada pendiente". No sale del tema porque el primario se usa para navegar. */
@Composable
private fun ChartColorsOk() = androidx.compose.ui.graphics.Color(0xFF2E7D5B)

@Composable
private fun Dato(etiqueta: String, valor: String, destacado: Boolean = false) {
    Column {
        Text(
            valor,
            style = MaterialTheme.typography.titleSmall,
            color = if (destacado) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            etiqueta,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
