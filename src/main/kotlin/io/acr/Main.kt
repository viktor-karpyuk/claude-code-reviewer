package io.acr

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import io.acr.ui.App

fun main() {
    val ctx = AppContext.bootstrap()
    Runtime.getRuntime().addShutdownHook(Thread { ctx.close() })

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "AI Code Reviewer",
            icon = painterResource("icon.png"),
            state = WindowState(size = DpSize(1440.dp, 940.dp)),
        ) {
            App(ctx)
        }
    }
}
