package io.acr.impl

import io.acr.claude.ClaudeCli
import io.acr.claude.Git
import io.acr.data.ImplRepository
import io.acr.data.PrefsRepo
import io.acr.forge.RepoRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Qué está pasando ahora mismo en una implementación, para la pantalla. */
data class ImplProgress(
    val implId: String,
    val lines: List<String> = emptyList(),
    val currentTask: Int? = null,
)

/**
 * Construye una feature entera a partir de sus documentos.
 *
 * Dos modelos y no uno, a propósito. El orden de las tareas es la decisión que más cuesta
 * deshacer —si la tercera necesita algo que recién aparece en la novena, la implementación se
 * traba a la mitad con medio trabajo hecho— así que planificar va al modelo que mejor razona.
 * Escribir el código es un trabajo distinto, más largo y más repetitivo, y ahí conviene el que
 * mejor programa.
 *
 * **Es lo primero de esta app que escribe.** Todo lo demás corre Claude en sólo lectura. Por eso
 * cada implementación trabaja en su propia rama, nunca en la de trabajo, y cada tarea que termina
 * bien queda commiteada: si la séptima falla, las seis anteriores siguen ahí y se retoma desde
 * ahí en vez de perder todo.
 */
class ImplEngine(
    private val impls: ImplRepository,
    private val prefs: PrefsRepo,
) {

    private val _progress = MutableStateFlow<Map<String, ImplProgress>>(emptyMap())
    val progress: StateFlow<Map<String, ImplProgress>> = _progress

    private val running = ConcurrentHashMap<String, Process>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    /** Modelos por defecto. Se pueden cambiar por implementación. */
    companion object {
        const val PLAN_MODEL = "fable-5"
        const val CODE_MODEL = "opus-5"
    }

    private fun log(implId: String, linea: String) {
        _progress.update { m ->
            val actual = m[implId] ?: ImplProgress(implId)
            m + (implId to actual.copy(lines = (actual.lines + linea).takeLast(400)))
        }
    }

    private fun clear(implId: String) {
        _progress.update { it - implId }
    }

    fun cancel(implId: String) {
        cancelled += implId
        running[implId]?.destroy()
    }

    /**
     * Lee los documentos y arma el plan.
     *
     * Corre en modo lectura aunque después se vaya a escribir: planificar es mirar, y darle
     * permisos de escritura a esta etapa sería regalarlos sin motivo.
     */
    suspend fun plan(repo: RepoRecord, implId: String): Result<Int> {
        val impl = impls.get(implId) ?: return Result.failure(IllegalStateException("No existe."))
        val dir = File(repo.localPath)
        if (!Git.isRepo(dir)) {
            return Result.failure(IllegalStateException("«${repo.localPath}» no es un repositorio de git."))
        }
        val binario = ClaudeCli.resolveBinary(prefs.get(io.acr.AppContext.PREF_CLAUDE_BINARY))
            ?: return Result.failure(IllegalStateException("No encuentro el ejecutable de Claude Code."))

        val docs = loadSources(impl.sources)
        if (docs.isEmpty()) {
            return Result.failure(IllegalStateException("No encontré ningún .md en lo que cargaste."))
        }

        impls.setStatus(implId, ImplStatus.PLANNING)
        log(implId, "Leyendo ${docs.size} documento(s): ${docs.joinToString(", ") { it.name }}")

        val base = Git.currentBranch(dir) ?: "develop"
        val modelo = impl.planModel ?: PLAN_MODEL
        val idioma = prefs.get(io.acr.AppContext.PREF_LANGUAGE) ?: "español"

        return runCatching {
            val res = ClaudeCli.run(
                binary = binario,
                workDir = dir,
                prompt = ImplPrompt.plan(docs, impl.extraPrompt, repo.name, base, idioma),
                model = modelo,
                // Planificar es mirar: los permisos de escritura llegan recién al implementar.
                allowedTools = listOf("Read", "Grep", "Glob", "Bash(git *)"),
                disallowedTools = ImplPrompt.DENIED_TOOLS,
                jsonSchema = ImplPrompt.PLAN_SCHEMA,
                register = { running[implId] = it },
                onEvent = { evt -> resumir(evt)?.let { log(implId, it) } },
            )
            running.remove(implId)
            if (!res.ok) error(res.stderr.ifBlank { "El planificador no devolvió un plan." })

            val (resumen, rama, tareas) = parsePlan(res.structured ?: res.text, implId)
            if (tareas.isEmpty()) error("El plan volvió sin tareas.")

            impls.savePlan(implId, resumen, rama, base, modelo, tareas)
            log(implId, "Plan listo: ${tareas.size} tareas, rama «$rama».")
            tareas.size
        }.onFailure {
            running.remove(implId)
            impls.setStatus(implId, ImplStatus.FAILED, it.message)
        }
    }

    /**
     * Ejecuta el plan, tarea por tarea, sin parar a preguntar.
     *
     * Crea la rama antes de tocar nada. Si ya existe se reusa: retomar una implementación frenada
     * es el caso normal, no una excepción, y crear `feature-x-2` dejaría el trabajo partido en dos
     * ramas que después hay que unir a mano.
     */
    suspend fun run(repo: RepoRecord, implId: String): Result<Unit> {
        val impl = impls.get(implId) ?: return Result.failure(IllegalStateException("No existe."))
        val rama = impl.branch ?: return Result.failure(IllegalStateException("Falta planificar."))
        val dir = File(repo.localPath)
        val binario = ClaudeCli.resolveBinary(prefs.get(io.acr.AppContext.PREF_CLAUDE_BINARY))
            ?: return Result.failure(IllegalStateException("No encuentro el ejecutable de Claude Code."))

        // Sin esto se escribiría sobre lo que el usuario tenga a medias, y el commit de la primera
        // tarea se llevaría cambios ajenos adentro.
        if (Git.isDirty(dir)) {
            val msg = "El repositorio tiene cambios sin commitear. Guardalos o descartalos antes."
            impls.setStatus(implId, ImplStatus.FAILED, msg)
            return Result.failure(IllegalStateException(msg))
        }

        cancelled.remove(implId)
        impls.setStatus(implId, ImplStatus.RUNNING)
        log(implId, "Rama «$rama»…")
        Git.checkoutBranch(dir, rama, impl.baseBranch ?: "develop")

        val docs = loadSources(impl.sources)
        val idioma = prefs.get(io.acr.AppContext.PREF_LANGUAGE) ?: "español"
        val modelo = impl.codeModel ?: CODE_MODEL
        var costo = 0.0
        // Qué tareas ya usaron su reintento. En memoria y no en la base: es de esta corrida, y al
        // retomar una implementación frenada corresponde volver a tener la oportunidad.
        val reintentadas = mutableSetOf<String>()

        return runCatching {
            while (true) {
                if (implId in cancelled) {
                    impls.finish(implId, ImplStatus.STOPPED, costo)
                    log(implId, "Frenada. Lo hecho quedó commiteado en «$rama».")
                    break
                }
                val todas = impls.tasks(implId)
                val tarea = todas.firstOrNull { it.status == TaskStatus.PENDING }
                if (tarea == null) {
                    val fallidas = todas.count { it.status == TaskStatus.FAILED }
                    val bloqueadas = todas.count { it.status == TaskStatus.BLOCKED }
                    // Esperar una decisión no es haber fallado: no hay nada roto, hay algo que
                    // alguien tiene que definir. Mezclarlos haría buscar un error que no existe.
                    val estado = when {
                        bloqueadas > 0 -> ImplStatus.AWAITING
                        fallidas > 0 -> ImplStatus.FAILED
                        else -> ImplStatus.DONE
                    }
                    impls.finish(implId, estado, costo)
                    log(
                        implId,
                        when (estado) {
                            ImplStatus.AWAITING -> "Avancé todo lo que pude. $bloqueadas tarea(s) esperan una decisión."
                            ImplStatus.FAILED -> "Terminó con $fallidas tarea(s) fallida(s)."
                            else -> "Implementación completa."
                        },
                    )
                    break
                }

                // Una tarea cuya dependencia falló no se intenta: construir sobre algo que no
                // está da un error confuso y encima gasta una corrida entera.
                val bloqueada = tarea.dependsOn.any { d ->
                    todas.firstOrNull { it.seq == d }?.status == TaskStatus.FAILED
                }
                if (bloqueada) {
                    impls.failTask(tarea.id, "Depende de una tarea que falló.")
                    log(implId, "· ${tarea.seq}. ${tarea.title} — saltada, su dependencia falló.")
                    continue
                }

                _progress.update { m ->
                    m + (implId to (m[implId] ?: ImplProgress(implId)).copy(currentTask = tarea.seq))
                }
                impls.startTask(tarea.id)
                log(implId, "▶ ${tarea.seq}/${todas.size} ${tarea.title}")

                val intento = runCatching {
                    ClaudeCli.run(
                        binary = binario,
                        workDir = dir,
                        prompt = ImplPrompt.task(
                            tarea, todas, docs, impl.extraPrompt, idioma,
                            // Lo ya decidido viaja en cada tarea: sin eso, la siguiente vuelve a
                            // toparse con la misma duda y frena de nuevo por algo ya resuelto.
                            decided = impls.questions(implId)
                                .filter { !it.answer.isNullOrBlank() }
                                .map { it.question to it.answer!! },
                        ),
                        model = modelo,
                        allowedTools = ImplPrompt.WRITE_TOOLS,
                        disallowedTools = ImplPrompt.DENIED_TOOLS,
                        jsonSchema = ImplPrompt.TASK_SCHEMA,
                        register = { running[implId] = it },
                        onEvent = { evt -> resumir(evt)?.let { log(implId, it) } },
                    )
                }
                running.remove(implId)
                val res = intento.getOrNull()
                if (res == null) {
                    val e = intento.exceptionOrNull()
                    if (tarea.id !in reintentadas) {
                        reintentadas += tarea.id
                        impls.resetTask(tarea.id)
                        log(implId, "↻ ${tarea.seq}. reintentando: ${e?.message.orEmpty().take(120)}")
                    } else {
                        impls.failTask(tarea.id, e?.message ?: "El subproceso falló.")
                        log(implId, "✗ ${tarea.seq}. ${e?.message.orEmpty()}")
                    }
                    continue
                }
                costo += res.costUsd ?: 0.0

                if (!res.ok) {
                    // Un reintento antes de darla por perdida. Corriendo sin nadie mirando, una
                    // caída pasajera —el proceso muere, el modelo devuelve vacío— frenaría la
                    // implementación entera hasta que alguien la mire, que es justo lo que no
                    // puede pasar. Uno solo: si falla dos veces, el problema no es de suerte.
                    val yaReintentada = tarea.id in reintentadas
                    if (!yaReintentada) {
                        reintentadas += tarea.id
                        impls.resetTask(tarea.id)
                        log(implId, "↻ ${tarea.seq}. reintentando: ${res.stderr.take(120)}")
                    } else {
                        impls.failTask(tarea.id, res.stderr.ifBlank { "Terminó sin resultado." }, res.costUsd)
                        log(implId, "✗ ${tarea.seq}. ${res.stderr.take(160)}")
                    }
                    continue
                }

                // ¿Se topó con algo que no puede decidir solo? Eso no es un fallo: es la única
                // interrupción legítima de una implementación autónoma.
                val salida = parseTask(res.structured ?: res.text)
                if (salida.blocked) {
                    val p = salida.question
                    impls.ask(
                        implId, tarea.id,
                        io.acr.impl.QuestionKind.fromApi(p?.kind),
                        p?.question ?: salida.summary,
                        p?.context,
                        p?.options.orEmpty(),
                    )
                    impls.blockTask(tarea.id, p?.question ?: salida.summary)
                    log(implId, "⏸ ${tarea.seq}. espera una decisión: ${(p?.question ?: "").take(140)}")
                    // Lo que haya quedado a medias no se commitea: la tarea no terminó.
                    continue
                }

                // El commit lo hace la herramienta y no el modelo: así el mensaje es uniforme y,
                // sobre todo, no depende de que el modelo se acuerde. Sin commit por tarea, una
                // falla en la número siete se lleva puesto todo lo anterior.
                val sha = if (Git.isDirty(dir)) {
                    Git.commitAll(dir, "${tarea.seq}. ${tarea.title}\n\n${res.text.take(1_500)}")
                } else {
                    // Terminar sin cambios es sospechoso, pero no siempre un error: puede ser una
                    // tarea que ya estaba hecha. Se registra y se sigue.
                    log(implId, "  (sin cambios en el árbol)")
                    null
                }
                impls.finishTask(tarea.id, sha, salida.summary.take(4_000), res.costUsd)
                log(implId, "✓ ${tarea.seq}. ${tarea.title}${sha?.let { " · ${it.take(7)}" }.orEmpty()}")
            }
        }.onFailure {
            running.remove(implId)
            impls.setStatus(implId, ImplStatus.FAILED, it.message)
        }.map { }
            .also { clear(implId) }
    }

    /**
     * Una línea de log por evento, o null para lo que no aporta.
     *
     * Filtrar acá y no mostrar todo: una implementación larga emite miles de eventos y un feed que
     * scrollea solo a esa velocidad no se lee, sólo hace ruido. Lo que importa es qué archivo se
     * tocó y qué comando corrió.
     */
    private fun resumir(evt: io.acr.claude.ClaudeEvent): String? = when (evt) {
        is io.acr.claude.ClaudeEvent.ToolUse ->
            "  · " + evt.tool + evt.detail.takeIf { it.isNotBlank() }?.let { ": ${it.take(110)}" }.orEmpty()
        is io.acr.claude.ClaudeEvent.Thinking -> evt.text.trim().takeIf { it.isNotBlank() }?.take(160)
        is io.acr.claude.ClaudeEvent.Failed -> "Error: " + evt.message.take(200)
        // El arranque de sesión no aporta nada acá: el modelo ya se muestra en la pantalla.
        is io.acr.claude.ClaudeEvent.Started -> null
    }

    /**
     * De los documentos al código, sin parar.
     *
     * Es como se usa el módulo: se cargan las specs y se va. Que el plan quede a la espera de una
     * aprobación tendría sentido si alguien fuera a mirarlo, y el punto de esto es justamente que
     * no haga falta. El plan igual queda guardado y visible mientras corre, así que se puede mirar
     * —y frenar— sin haber tenido que autorizarlo antes.
     */
    suspend fun planAndRun(repo: RepoRecord, implId: String): Result<Unit> {
        val planificado = plan(repo, implId)
        if (planificado.isFailure) return Result.failure(planificado.exceptionOrNull() ?: Exception("falló"))
        if (implId in cancelled) return Result.success(Unit)
        return run(repo, implId)
    }

    /** Lo que devolvió una tarea. */
    private data class TaskOut(
        val blocked: Boolean,
        val summary: String,
        val question: Pregunta?,
    )

    private data class Pregunta(
        val kind: String?,
        val question: String,
        val context: String?,
        val options: List<String>,
    )

    /**
     * Lee la salida de una tarea.
     *
     * Ante un JSON que no se entiende se asume terminada y no bloqueada: dar por bloqueada una
     * tarea que en realidad terminó dejaría la implementación esperando una decisión que nadie
     * pidió, y eso frena todo por un error de parseo.
     */
    private fun parseTask(raw: String): TaskOut {
        val limpio = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = runCatching {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                .parseToJsonElement(limpio).jsonObject
        }.getOrNull() ?: return TaskOut(false, raw.take(4_000), null)

        val estado = obj["status"]?.jsonPrimitive?.contentOrNull?.uppercase()
        val resumen = obj["summary"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { raw.take(2_000) }
        val q = (obj["question"] as? JsonObject)?.let { o ->
            val texto = o["question"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (texto == null) null
            else Pregunta(
                kind = o["kind"]?.jsonPrimitive?.contentOrNull,
                question = texto,
                context = o["context"]?.jsonPrimitive?.contentOrNull,
                options = (o["options"] as? JsonArray).orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf { s -> s.isNotBlank() } },
            )
        }
        // Bloqueada sólo si además dice cuál es la duda: un BLOCKED sin pregunta no se puede
        // contestar y dejaría la implementación trabada sin salida.
        return TaskOut(blocked = estado == "BLOCKED" && q != null, summary = resumen, question = q)
    }

    /** Lee el plan que devolvió el modelo. */
    private fun parsePlan(raw: String, implId: String): Triple<String, String, List<ImplTask>> {
        val limpio = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
        val obj = json.parseToJsonElement(limpio).jsonObject
        val tareas = (obj["tasks"] as? JsonArray).orEmpty().mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val titulo = o["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
            ImplTask(
                id = "", implId = implId,
                // Se renumera por posición: un plan con seq repetidos o salteados haría que el
                // orden dependa de un campo que el modelo puede errar.
                seq = i + 1,
                title = titulo,
                detail = o["detail"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                dependsOn = (o["depends_on"] as? JsonArray).orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() },
                size = TaskSize.fromApi(o["size"]?.jsonPrimitive?.contentOrNull),
                estimateMin = o["estimate_min"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                status = TaskStatus.PENDING,
                commitSha = null, result = null, error = null, costUsd = null,
                startedAt = null, finishedAt = null,
            )
        }
        val rama = obj["branch"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.trim('/')?.takeIf { it.isNotBlank() } ?: "implementacion"
        return Triple(obj["summary"]?.jsonPrimitive?.contentOrNull.orEmpty(), rama, tareas)
    }
}
