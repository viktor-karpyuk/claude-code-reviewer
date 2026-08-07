package io.acr

/**
 * Versión de la app, leída del recurso que genera Gradle desde `version` del build.
 *
 * No se escribe a mano en ningún lado: si el número viviera también en el código, tarde o
 * temprano diría una cosa distinta que el instalador.
 */
object AppVersion {

    val value: String by lazy {
        runCatching {
            AppVersion::class.java.getResourceAsStream("/acr-version.properties")?.use { input ->
                java.util.Properties().apply { load(input) }.getProperty("version")
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "desconocida"
    }
}
