package io.acr.jira

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/** Dónde vive el Jira y con qué credenciales se entra. */
data class JiraConfig(val baseUrl: String, val email: String, val token: String) {
    val configured: Boolean get() = baseUrl.isNotBlank() && email.isNotBlank() && token.isNotBlank()
}

/**
 * Lee tickets de Jira Cloud.
 *
 * **Basic con `email:token`, no Bearer.** Es al revés que Bitbucket, donde Basic falla el 100% de
 * las veces y sólo anda Bearer: son dos productos de la misma empresa con dos esquemas distintos,
 * y confundirlos da un 401 que parece una credencial vencida.
 *
 * Sólo lectura. La app no toca los tickets: mueve el estado quien trabaja, no la herramienta que
 * mira.
 */
class JiraClient(private val config: JiraConfig) {

    private fun auth(): String =
        "Basic " + Base64.getEncoder()
            .encodeToString("${config.email}:${config.token}".toByteArray(Charsets.UTF_8))

    private fun base(): String = config.baseUrl.trim().trimEnd('/')

    /**
     * Trae un ticket, o null si no está.
     *
     * Null y no una excepción para el 404: una rama puede mencionar una clave que ya no existe, o
     * de otro proyecto, y eso no es un error que valga interrumpir la pantalla.
     */
    suspend fun issue(key: String): Result<JiraIssue?> = withContext(Dispatchers.IO) {
        if (!config.configured) {
            return@withContext Result.failure(IllegalStateException("Jira no está configurado."))
        }
        runCatching {
            val url = "${base()}/rest/api/3/issue/$key" +
                "?fields=summary,description,issuetype,status,assignee"
            val req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", auth())
                .header("Accept", "application/json")
                .timeout(java.time.Duration.ofSeconds(20))
                .GET().build()
            val res = io.acr.forge.httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            when (res.statusCode()) {
                200 -> parse(key, res.body())
                404 -> null
                401, 403 -> error(
                    "Jira rechazó las credenciales (${res.statusCode()}). En Jira Cloud el token " +
                        "va como Basic con tu email, no como Bearer.",
                )
                else -> error("Jira contestó ${res.statusCode()}: ${res.body().take(200)}")
            }
        }
    }

    private fun parse(key: String, body: String): JiraIssue {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
        val fields = json.parseToJsonElement(body).jsonObject["fields"]?.jsonObject
        fun str(vararg path: String): String? {
            var actual: JsonObject? = fields
            path.dropLast(1).forEach { actual = actual?.get(it) as? JsonObject }
            return actual?.get(path.last())?.jsonPrimitive?.contentOrNull
        }
        return JiraIssue(
            key = key,
            summary = str("summary").orEmpty(),
            description = descripcion(fields?.get("description")),
            type = str("issuetype", "name").orEmpty(),
            status = str("status", "name").orEmpty(),
            assignee = str("assignee", "displayName"),
            url = "${base()}/browse/$key",
        )
    }

    /**
     * La descripción, que en Jira Cloud viene en formato de documento y no como texto.
     *
     * Se aplana a texto plano recorriendo los nodos: lo que se necesita es lo que pide el ticket,
     * y para eso el formato no aporta nada. Renderizar el documento entero sería trabajo para
     * mostrar negritas que a nadie le cambian la decisión.
     */
    private fun descripcion(nodo: kotlinx.serialization.json.JsonElement?): String {
        if (nodo == null) return ""
        val sb = StringBuilder()
        fun recorrer(e: kotlinx.serialization.json.JsonElement) {
            when (e) {
                is JsonObject -> {
                    val tipo = e["type"]?.jsonPrimitive?.contentOrNull
                    if (tipo == "text") sb.append(e["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    // Los saltos se conservan porque una lista de requisitos pegada en un párrafo
                    // deja de leerse como una lista.
                    if (tipo == "hardBreak") sb.append('\n')
                    (e["content"] as? JsonArray)?.forEach { recorrer(it) }
                    if (tipo == "paragraph" || tipo == "listItem" || tipo == "heading") sb.append('\n')
                }
                is JsonArray -> e.forEach { recorrer(it) }
                else -> Unit
            }
        }
        // La API vieja devolvía un string plano; la nueva un documento. Se soportan las dos.
        runCatching {
            if (nodo is kotlinx.serialization.json.JsonPrimitive) sb.append(nodo.contentOrNull.orEmpty())
            else recorrer(nodo)
        }
        return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** Prueba las credenciales sin depender de que exista un ticket concreto. */
    suspend fun check(): Result<String> = withContext(Dispatchers.IO) {
        if (!config.configured) {
            return@withContext Result.failure(IllegalStateException("Faltan datos de Jira."))
        }
        runCatching {
            val req = HttpRequest.newBuilder(URI.create("${base()}/rest/api/3/myself"))
                .header("Authorization", auth())
                .header("Accept", "application/json")
                .timeout(java.time.Duration.ofSeconds(20))
                .GET().build()
            val res = io.acr.forge.httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) {
                error("Jira contestó ${res.statusCode()}. Revisá la URL, el email y el token.")
            }
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            json.parseToJsonElement(res.body()).jsonObject["displayName"]
                ?.jsonPrimitive?.contentOrNull ?: "ok"
        }
    }
}
