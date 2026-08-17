package io.acr.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import io.acr.data.DbEngine
import io.acr.data.DbPorter
import io.acr.data.DbSettings
import io.acr.data.PortProgress
import io.acr.i18n.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A qué base se conecta la app, y cómo mudarse a otra.
 *
 * SQLite es el de fábrica y va a seguir siéndolo: un archivo, sin instalar nada. Esto existe para
 * quien necesita otra cosa —una base compartida por un equipo, una que ya tiene backup, una que su
 * organización exige— y hasta ahora tenía que elegir entre eso y usar la app.
 *
 * La pantalla tiene un orden deliberado: probar la conexión, después copiar, y recién ahí mudarse.
 * Cambiar la configuración sin copiar los datos deja la app mirando una base vacía, que se ve
 * exactamente igual que haberlos perdido.
 */
@Composable
fun DatabasePanel(ctx: AppContext) {
    val actual = remember { DbSettings.load(ctx.dataDir, ctx.secrets) }
    var motor by remember { mutableStateOf(actual.engine) }
    var host by remember { mutableStateOf(actual.host) }
    var puerto by remember { mutableStateOf(actual.port.toString()) }
    var base by remember { mutableStateOf(actual.database) }
    var usuario by remember { mutableStateOf(actual.user) }
    var clave by remember { mutableStateOf(actual.password) }
    var params by remember { mutableStateOf(actual.params) }

    var probando by remember { mutableStateOf(false) }
    var resultadoPrueba by remember { mutableStateOf<String?>(null) }
    var pruebaOk by remember { mutableStateOf(false) }
    var avance by remember { mutableStateOf<PortProgress?>(null) }
    var copiando by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun elegido() = DbSettings(
        engine = motor,
        host = host.trim(),
        port = puerto.trim().toIntOrNull() ?: motor.defaultPort,
        database = base.trim(),
        user = usuario.trim(),
        password = clave,
        params = params.trim(),
    )

    val cambio = elegido() != actual
    val falta = elegido().missing()
    // Los textos que se usan adentro de un onClick se resuelven acá: `t` es composable y no se
    // puede llamar desde un lambda que corre después.
    val textoOk = t("db.testOk")

    Column(Modifier.fillMaxWidth()) {
        Text(t("db.title"), style = MaterialTheme.typography.titleMedium)
        Text(
            t("db.note"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        // La app está corriendo sobre la base de fábrica porque la configurada no respondió. Va
        // arriba de todo y en rojo: sin esto, la pantalla se ve igual que si los datos se hubieran
        // perdido, y alguien podría empezar a cargar cosas de nuevo sobre la base equivocada.
        ctx.dbFallback?.let { err ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        t("db.fallback"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(err, style = MaterialTheme.typography.bodySmall)
                    Text(
                        t("db.fallbackNote"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Dónde está parada la app ahora. Es lo primero porque es la pregunta que trae a alguien
        // a esta pantalla, y porque después de una mudanza es lo que confirma que salió bien.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    t("db.current"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(actual.describe(), style = MaterialTheme.typography.bodyMedium)
                val (aplicada, conocidas) = ctx.store.schemaVersion()
                Text(
                    t("db.schema", aplicada, conocidas) +
                        (if (!actual.isServer) "  ·  " + ctx.store.path else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DbEngine.entries.forEach { e ->
                AssistChip(
                    onClick = {
                        motor = e
                        if (e != DbEngine.SQLITE && puerto.toIntOrNull() != e.defaultPort) {
                            puerto = e.defaultPort.toString()
                        }
                        resultadoPrueba = null; pruebaOk = false
                    },
                    label = { Text(e.label) },
                    colors = if (motor == e) {
                        androidx.compose.material3.AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        )
                    } else {
                        androidx.compose.material3.AssistChipDefaults.assistChipColors()
                    },
                )
            }
        }

        if (motor == DbEngine.SQLITE) {
            Spacer(Modifier.height(10.dp))
            Text(
                t("db.sqliteNote"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = host, onValueChange = { host = it; pruebaOk = false },
                    label = { Text(t("db.host")) }, singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = puerto, onValueChange = { puerto = it.filter(Char::isDigit); pruebaOk = false },
                    label = { Text(t("db.port")) }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = base, onValueChange = { base = it; pruebaOk = false },
                    label = { Text(t("db.database")) }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = usuario, onValueChange = { usuario = it; pruebaOk = false },
                    label = { Text(t("db.user")) }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = clave, onValueChange = { clave = it; pruebaOk = false },
                    label = { Text(t("db.password")) }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = params, onValueChange = { params = it; pruebaOk = false },
                label = { Text(t("db.params")) },
                placeholder = { Text("sslmode=require") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                enabled = motor != DbEngine.SQLITE && falta == null && !probando && !copiando,
                onClick = {
                    probando = true
                    resultadoPrueba = null
                    scope.launch {
                        val err = withContext(Dispatchers.IO) {
                            DbPorter().test(elegido(), ctx.dataDir.resolve("acr.db"))
                        }
                        pruebaOk = err == null
                        resultadoPrueba = err ?: textoOk
                        probando = false
                    }
                },
            ) { Text(t("db.test")) }
            if (probando) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)

            // Copiar y mudarse son un solo botón a propósito: mudarse sin copiar deja la app
            // mirando una base vacía, que se ve exactamente igual que haber perdido todo.
            Button(
                enabled = cambio && falta == null && !copiando && (motor == DbEngine.SQLITE || pruebaOk),
                onClick = {
                    copiando = true
                    avance = PortProgress()
                    scope.launch {
                        val res = withContext(Dispatchers.IO) {
                            runCatching {
                                DbPorter().migrate(ctx.dataDir, actual, elegido(), ctx.secrets) { p ->
                                    avance = p
                                }
                            }.getOrElse { e ->
                                avance = PortProgress(finished = true, error = e.message ?: e::class.simpleName)
                                emptyList()
                            }
                        }
                        copiando = false
                        if (res.isNotEmpty() && res.all { it.ok }) {
                            avance = avance?.copy(finished = true, results = res)
                        }
                    }
                },
            ) { Text(t("db.moveHere")) }
            if (copiando) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)

            if (falta != null) {
                Text(
                    t("db.missing", falta),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        resultadoPrueba?.let { r ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (pruebaOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                if (!pruebaOk) {
                    TextButton(onClick = {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(java.awt.datatransfer.StringSelection(r), null)
                    }) { Text(t("common.copyError")) }
                }
            }
        }

        // El detalle de la copia, tabla por tabla. Decir "listo" sin mostrar los números sería
        // pedir que alguien confíe en el momento en que menos corresponde.
        avance?.let { p ->
            Spacer(Modifier.height(14.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        if (p.finished) t("db.portDone") else t("db.porting", p.table),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (!p.finished) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { p.fraction },
                            modifier = Modifier.fillMaxWidth().height(5.dp),
                        )
                        Text(
                            t("db.portProgress", p.tablesDone, p.tables, p.rows),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    p.error?.let { e ->
                        Spacer(Modifier.height(6.dp))
                        Text(e, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Text(
                            t("db.portFailedNote"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Sólo las tablas con filas: veintiséis renglones en cero esconden los cinco
                    // que importan.
                    val conDatos = p.results.filter { it.source > 0 || !it.ok }
                    if (conDatos.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        conDatos.forEach { r ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    if (r.ok) "✓" else "✗",
                                    color = if (r.ok) io.acr.ui.stats.ChartColors.added else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.width(20.dp),
                                )
                                Text(
                                    r.table,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    r.error ?: t("db.rows", r.target),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (p.finished && p.error == null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            t("db.restart"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            t("db.sourceKept"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
