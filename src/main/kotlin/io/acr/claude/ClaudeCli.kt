package io.acr.claude

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

/** A progress event surfaced while a review runs. */
sealed interface ClaudeEvent {
    data class Started(val sessionId: String, val model: String) : ClaudeEvent
    data class Thinking(val text: String) : ClaudeEvent
    data class ToolUse(val tool: String, val detail: String) : ClaudeEvent
    data class Failed(val message: String) : ClaudeEvent
}

data class ClaudeResult(
    val ok: Boolean,
    val text: String,
    /** JSON validado contra el esquema pedido, cuando se pasó uno. */
    val structured: String?,
    val sessionId: String?,
    val costUsd: Double?,
    /** Tokens del evento result. Con suscripción es la métrica que importa, no el costo. */
    val tokensIn: Long,
    val tokensOut: Long,
    val tokensCacheRead: Long,
    val tokensCacheWrite: Long,
    val permissionDenials: List<String>,
    val stderr: String,
)

/**
 * Drives the local Claude Code CLI as a subprocess.
 *
 * This is deliberately the CLI and not the Anthropic API: the CLI runs on the user's existing
 * Claude Code subscription login, so the app never needs — and never stores — an API key. The
 * Agent SDK was the other candidate but it authenticates only with an API key and has no JVM
 * binding, which rules it out for both reasons.
 */
object ClaudeCli {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * A GUI-launched JVM does not get the user's interactive shell PATH, so `claude` is usually
     * not on it. Probe the known install locations before falling back to a login shell lookup.
     */
    fun resolveBinary(explicit: String?): String? {
        if (!explicit.isNullOrBlank() && File(explicit).canExecute()) return explicit
        val home = System.getProperty("user.home")
        val candidates = listOf(
            "$home/.local/bin/claude",
            "/opt/homebrew/bin/claude",
            "/usr/local/bin/claude",
            "$home/.claude/local/claude",
        )
        candidates.firstOrNull { File(it).canExecute() }?.let { return it }
        return runCatching {
            val p = ProcessBuilder("/bin/zsh", "-lc", "command -v claude").start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(5, TimeUnit.SECONDS)
            out.ifBlank { null }
        }.getOrNull()
    }

    /**
     * Runs one headless review turn. Streams progress through [onEvent] and returns the final
     * result. [register] hands the live process back to the caller so a run can be cancelled.
     */
    suspend fun run(
        binary: String,
        workDir: File,
        prompt: String,
        model: String?,
        allowedTools: List<String>,
        disallowedTools: List<String>,
        jsonSchema: String? = null,
        timeoutMinutes: Long = 20,
        register: (Process) -> Unit = {},
        onEvent: (ClaudeEvent) -> Unit = {},
    ): ClaudeResult = withContext(Dispatchers.IO) {
        val cmd = buildList {
            add(binary)
            add("-p")
            add("--output-format"); add("stream-json")
            add("--verbose")
            // dontAsk denies anything outside the allow-list instead of blocking on a prompt that
            // nobody can answer in a headless run.
            add("--permission-mode"); add("dontAsk")
            // Cada patrón va como argumento propio, NO unidos por coma: el CLI separa la lista por
            // comas *y espacios*, así que "Bash(git diff *)" en una sola cadena se parte en
            // "Bash(git" y "diff *)" y el permiso nunca matchea. Se ve como que la review no
            // encontró nada, cuando en realidad no pudo leer el diff.
            if (allowedTools.isNotEmpty()) { add("--allowedTools"); addAll(allowedTools) }
            if (disallowedTools.isNotEmpty()) { add("--disallowedTools"); addAll(disallowedTools) }
            if (!model.isNullOrBlank()) { add("--model"); add(model) }
            // El CLI valida contra el esquema y reintenta solo si el modelo se desvía.
            if (!jsonSchema.isNullOrBlank()) { add("--json-schema"); add(jsonSchema) }
        }

        val process = ProcessBuilder(cmd)
            .directory(workDir)
            .redirectErrorStream(false)
            .start()
        register(process)

        // El prompt va por stdin porque supera cualquier largo seguro de argv. Se escribe en un
        // hilo aparte a propósito: si se escribiera acá, en línea, el padre quedaría bloqueado
        // llenando el pipe de stdin mientras el hijo se bloquea llenando el de stdout que todavía
        // nadie lee — deadlock clásico, y un PR con hilo largo alcanza para provocarlo.
        // Si la escritura falla, el CLI corre con un prompt vacío o parcial y devuelve una review
        // sin sentido: hay que enterarse, no tragarlo.
        val stdinError = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val stdinPump = Thread {
            runCatching { process.outputStream.bufferedWriter().use { it.write(prompt) } }
                .onFailure { stdinError.set(it.message ?: it::class.java.simpleName) }
        }.apply { isDaemon = true; start() }

        // StringBuffer y no StringBuilder: lo escribe el hilo de stderr y lo lee el principal.
        val stderr = StringBuffer()
        val stderrPump = Thread {
            runCatching { process.errorStream.bufferedReader().forEachLine { stderr.append(it).append('\n') } }
        }.apply { isDaemon = true; start() }

        var sessionId: String? = null
        var cost: Double? = null
        var tIn = 0L
        var tOut = 0L
        var tCacheRead = 0L
        var tCacheWrite = 0L
        var finalText = ""
        var structured: String? = null
        var isError = false
        val denials = mutableListOf<String>()

        process.inputStream.bufferedReader().forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val evt = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEachLine
            when (evt["type"]?.jsonPrimitive?.contentOrNull) {
                "system" -> if (evt["subtype"]?.jsonPrimitive?.contentOrNull == "init") {
                    sessionId = evt["session_id"]?.jsonPrimitive?.contentOrNull
                    onEvent(ClaudeEvent.Started(sessionId ?: "?", evt["model"]?.jsonPrimitive?.contentOrNull ?: "?"))
                }
                "assistant" -> describeAssistant(evt).forEach(onEvent)
                "result" -> {
                    isError = evt["is_error"]?.jsonPrimitive?.contentOrNull == "true"
                    finalText = evt["result"]?.jsonPrimitive?.contentOrNull ?: finalText
                    structured = evt["structured_output"]
                        ?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.toString()
                    (evt["usage"] as? JsonObject)?.let { u ->
                        fun num(k: String) =
                            (u[k] as? kotlinx.serialization.json.JsonPrimitive)
                                ?.contentOrNull?.toLongOrNull() ?: 0L
                        tIn = num("input_tokens")
                        tOut = num("output_tokens")
                        tCacheRead = num("cache_read_input_tokens")
                        tCacheWrite = num("cache_creation_input_tokens")
                    }
                    cost = evt["total_cost_usd"]?.jsonPrimitive?.doubleOrNull
                    sessionId = evt["session_id"]?.jsonPrimitive?.contentOrNull ?: sessionId
                    evt["permission_denials"]?.jsonArray?.forEach { d ->
                        denials += d.jsonObject["tool_name"]?.jsonPrimitive?.contentOrNull ?: d.toString()
                    }
                }
            }
        }

        // Sin tope, un proceso colgado bloquea la review para siempre y sólo lo salva un cancel
        // manual. Con tope, la review falla y el estado queda consistente.
        val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            onEvent(ClaudeEvent.Failed("La review superó el límite de $timeoutMinutes minutos."))
        }
        val exit = if (finished) process.exitValue() else -1
        stdinPump.join(1_000)
        stderrPump.join(2_000)

        stdinError.get()?.let { onEvent(ClaudeEvent.Failed("No pude enviarle el prompt al CLI: $it")) }

        ClaudeResult(
            ok = finished && exit == 0 && !isError && finalText.isNotBlank() && stdinError.get() == null,
            text = finalText,
            structured = structured,
            sessionId = sessionId,
            costUsd = cost,
            tokensIn = tIn,
            tokensOut = tOut,
            tokensCacheRead = tCacheRead,
            tokensCacheWrite = tCacheWrite,
            permissionDenials = denials,
            stderr = listOfNotNull(stdinError.get()?.let { "stdin: $it" }, stderr.toString().trim())
                .filter { it.isNotBlank() }.joinToString("\n"),
        )
    }

    /** Turns an assistant event into human-readable progress lines. */
    private fun describeAssistant(evt: JsonObject): List<ClaudeEvent> {
        // Claude Code has shipped both {"content":[...]} and {"message":{"content":[...]}};
        // accept either so a CLI upgrade does not silently blank the progress feed.
        val content = (evt["message"] as? JsonObject)?.get("content")?.jsonArray
            ?: evt["content"]?.jsonArray
            ?: return emptyList()
        return content.mapNotNull { node ->
            val obj = node as? JsonObject ?: return@mapNotNull null
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> obj["text"]?.jsonPrimitive?.contentOrNull
                    ?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { ClaudeEvent.Thinking(it.lineSequence().first().take(160)) }
                "tool_use" -> {
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "tool"
                    val input = obj["input"] as? JsonObject
                    val detail = input?.get("command")?.jsonPrimitive?.contentOrNull
                        ?: input?.get("file_path")?.jsonPrimitive?.contentOrNull
                        ?: input?.get("pattern")?.jsonPrimitive?.contentOrNull
                        ?: ""
                    ClaudeEvent.ToolUse(name, detail.take(120))
                }
                else -> null
            }
        }
    }
}
