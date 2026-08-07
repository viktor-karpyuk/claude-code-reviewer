package io.acr

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import io.acr.ui.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.compose.runtime.Composable
private fun CloseDialog(
    onBackground: (remember: Boolean) -> Unit,
    onQuit: (remember: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var rememberChoice by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(io.acr.i18n.t("close.title")) },
        text = {
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.Text(io.acr.i18n.t("close.body"))
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = rememberChoice,
                        onCheckedChange = { rememberChoice = it },
                    )
                    androidx.compose.material3.Text(io.acr.i18n.t("close.remember"))
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(onClick = { onBackground(rememberChoice) }) {
                androidx.compose.material3.Text(io.acr.i18n.t("close.background"))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { onQuit(rememberChoice) }) {
                androidx.compose.material3.Text(io.acr.i18n.t("close.quit"))
            }
        },
    )
}

fun main() {
    val ctx = AppContext.bootstrap()
    Runtime.getRuntime().addShutdownHook(Thread { ctx.close() })

    application {
        // La ventana se oculta al cerrarla en vez de terminar la app: con icono en la barra de
        // menú, cerrar la ventana significa "sacala de encima", no "apagá el barrido automático".
        var windowVisible by remember { mutableStateOf(true) }
        // Contador y no booleano: si la ventana YA estaba visible pero detrás de otras, poner
        // `visible = true` no cambia el estado y no pasa nada. Cada pedido incrementa esto, así
        // el efecto se dispara siempre y la trae al frente.
        var bringToFront by remember { mutableStateOf(0) }

        fun showWindow() {
            windowVisible = true
            bringToFront++
        }

        val progress by ctx.engine.progress.collectAsState()
        var ready by remember { mutableStateOf(0) }
        var replies by remember { mutableStateOf(0) }
        var autoOn by remember { mutableStateOf(ctx.auto.enabled()) }
        var askOnClose by remember { mutableStateOf(false) }
        // El menú del tray no está dentro del provider de idioma de App(), así que se resuelve
        // contra la preferencia directamente.
        val uiLang = io.acr.i18n.Lang.fromCode(ctx.prefs.get(AppContext.PREF_UI_LANG))
        val windowState = androidx.compose.ui.window.rememberWindowState(size = DpSize(1440.dp, 940.dp))
        // Preferencia guardada: "background" o "quit". Vacío = preguntar.
        var closeChoice by remember { mutableStateOf(ctx.prefs.get(AppContext.PREF_CLOSE_ACTION).orEmpty()) }

        // Los contadores del menú se releen cada tanto: es la única forma de que el icono sirva
        // sin abrir la ventana, que es justamente para lo que uno lo mira.
        LaunchedEffect(Unit) {
            while (true) {
                withContext(Dispatchers.IO) {
                    ready = ctx.reviews.readyToPublish().size
                    replies = ctx.replies.openOnes().size
                }
                delay(30_000)
            }
        }

        Tray(
            icon = painterResource("icon.png"),
            tooltip = "AI Code Reviewer",
            onAction = { showWindow() },
            menu = {
                Item(
                    if (progress.isEmpty()) io.acr.i18n.I18n.get(uiLang, "tray.idle")
                    else "${progress.size} " + io.acr.i18n.I18n.get(uiLang, "prs.reviewingCount"),
                    enabled = false,
                    onClick = {},
                )
                Item("$ready " + io.acr.i18n.I18n.get(uiLang, "prs.readyToPublish"), onClick = { showWindow() })
                Item("$replies " + io.acr.i18n.I18n.get(uiLang, "tray.replied"), onClick = { showWindow() })
                Separator()
                Item(io.acr.i18n.I18n.get(uiLang, "tray.openPanel"), onClick = { showWindow() })
                Item(io.acr.i18n.I18n.get(uiLang, "dash.findNew"), onClick = { ctx.appScope.launch { ctx.auto.runOnce() } })
                Item(
                    if (autoOn) io.acr.i18n.I18n.get(uiLang, "tray.pause")
                    else io.acr.i18n.I18n.get(uiLang, "tray.resume"),
                    onClick = {
                        autoOn = !autoOn
                        ctx.prefs.put(io.acr.claude.AutoReviewer.PREF_ENABLED, autoOn.toString())
                    },
                )
                Separator()
                Item(io.acr.i18n.I18n.get(uiLang, "tray.quit"), onClick = ::exitApplication)
            },
        )

        Window(
            onCloseRequest = {
                when (closeChoice) {
                    "quit" -> exitApplication()
                    "background" -> windowVisible = false
                    // Sin preferencia se pregunta: cerrar la ventana y apagar el barrido
                    // automático no son lo mismo, y adivinar cuál quiso es peor que preguntar.
                    else -> askOnClose = true
                }
            },
            visible = windowVisible,
            title = "AI Code Reviewer",
            icon = painterResource("icon.png"),
            state = windowState,
        ) {
            LaunchedEffect(bringToFront, windowVisible) {
                if (!windowVisible) return@LaunchedEffect
                // Des-minimizar primero: toFront() sobre una ventana minimizada no hace nada.
                if (windowState.isMinimized) windowState.isMinimized = false
                window.toFront()
                window.requestFocus()
                // En macOS una app en segundo plano no se trae al frente sola aunque la ventana
                // sí lo haga; hay que pedir que la aplicación pase a primer plano.
                runCatching {
                    val d = java.awt.Desktop.getDesktop()
                    if (d.isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND)) {
                        d.requestForeground(true)
                    }
                }
            }

            App(ctx)

            if (askOnClose) {
                CloseDialog(
                    onBackground = { remember_ ->
                        if (remember_) ctx.prefs.put(AppContext.PREF_CLOSE_ACTION, "background")
                        closeChoice = if (remember_) "background" else closeChoice
                        askOnClose = false
                        windowVisible = false
                    },
                    onQuit = { remember_ ->
                        if (remember_) ctx.prefs.put(AppContext.PREF_CLOSE_ACTION, "quit")
                        exitApplication()
                    },
                    onDismiss = { askOnClose = false },
                )
            }
        }
    }
}
