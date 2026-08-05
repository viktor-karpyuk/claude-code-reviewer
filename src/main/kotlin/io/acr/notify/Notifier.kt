package io.acr.notify

import io.acr.data.PrefsRepo
import java.util.concurrent.TimeUnit

/**
 * Avisos del sistema operativo.
 *
 * Sin esto el modo automático queda a medias: revisa mientras no estás mirando y no hay forma de
 * enterarse salvo abrir la app. En macOS se usa `osascript`, que da la notificación nativa sin
 * sumar dependencias ni pedir permisos de accesibilidad; en el resto se cae al tray de AWT.
 */
class Notifier(private val prefs: PrefsRepo) {

    fun enabled(): Boolean = prefs.get(PREF_ENABLED) != "false"

    fun notify(title: String, body: String) {
        if (!enabled()) return
        val ok = runCatching { macOs(title, body) }.getOrDefault(false)
        if (!ok) runCatching { tray(title, body) }
    }

    private fun macOs(title: String, body: String): Boolean {
        if (!System.getProperty("os.name").lowercase().contains("mac")) return false
        // Las comillas dobles se escapan porque el texto va dentro de un literal de AppleScript:
        // un título con comillas rompería el script y el aviso se perdería en silencio.
        val script = "display notification \"${clean(body)}\" with title \"${clean(title)}\""
        val p = ProcessBuilder("osascript", "-e", script).start()
        return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
    }

    private fun tray(title: String, body: String) {
        if (!java.awt.SystemTray.isSupported()) return
        val tray = java.awt.SystemTray.getSystemTray()
        val image = java.awt.Toolkit.getDefaultToolkit()
            .createImage(ByteArray(0))
        val icon = java.awt.TrayIcon(image, "AI Code Reviewer")
        icon.isImageAutoSize = true
        tray.add(icon)
        icon.displayMessage(title, body, java.awt.TrayIcon.MessageType.INFO)
        tray.remove(icon)
    }

    private fun clean(s: String) = s.replace("\\", "").replace("\"", "'").replace("\n", " ").take(200)

    companion object {
        const val PREF_ENABLED = "notify.enabled"
    }
}
