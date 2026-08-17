package io.acr.impl

import io.acr.claude.ClaudeCli
import io.acr.claude.Git
import io.acr.data.ImplRepository
import io.acr.data.PrefsRepo
import io.acr.forge.RepoRecord
import kotlinx.coroutines.async
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
    /**
     * Qué tareas están corriendo ahora. Puede ser más de una: las que no dependen entre sí y están
     * en repositorios distintos arrancan juntas.
     */
    val currentTasks: Set<Int> = emptySet(),
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

    /**
     * Modelos por defecto.
     *
     * Los alias cortos que entiende el CLI, igual que el resto de la app —`haiku`, `sonnet`,
     * `opus`—. Con `fable-5` y `opus-5` la primera implementación real murió al arrancar con
     * `unrecognized_model`: esos nombres no existen, y el error recién se ve al correr porque el
     * CLI valida el modelo del lado del servidor.
     *
     * El alias apunta siempre a la última versión de esa familia, que es lo que se quiere acá: no
     * hay que tocar esto cuando salga la que sigue.
     */
    companion object {
        const val PLAN_MODEL = "fable"
        const val CODE_MODEL = "opus"
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
        // Puede haber varias tareas corriendo a la vez, cada una con su proceso. Matar sólo una
        // dejaría a las otras escribiendo en un repositorio que ya nadie está mirando.
        running.keys.filter { it.startsWith("$implId#") }.forEach { running.remove(it)?.destroy() }
    }

    /**
     * Lee los documentos y arma el plan.
     *
     * Corre en modo lectura aunque después se vaya a escribir: planificar es mirar, y darle
     * permisos de escritura a esta etapa sería regalarlos sin motivo.
     */
    suspend fun plan(
        repos: List<RepoRecord>,
        implId: String,
        /**
         * En qué sentido revisar el plan que ya existe, cuando esto es una replanificación.
         *
         * Null es planificar de cero. Con texto, al modelo se le da el plan actual y esta guía y se
         * le pide el revisado: replanificar de cero perdería las decisiones de orden que ya estaban
         * bien y devolvería otras distintas, y entonces no habría forma de saber si la revisión
         * mejoró algo o simplemente barajó de nuevo.
         */
        guidance: String? = null,
    ): Result<Int> {
        val impl = impls.get(implId) ?: return Result.failure(IllegalStateException("No existe."))
        val principal = repos.firstOrNull()
            ?: return Result.failure(IllegalStateException("La implementación no tiene repositorios."))
        val dir = File(principal.localPath)
        if (!Git.isRepo(dir)) {
            return Result.failure(IllegalStateException("«${principal.localPath}» no es un repositorio de git."))
        }
        val binario = ClaudeCli.resolveBinary(prefs.get(io.acr.AppContext.PREF_CLAUDE_BINARY))
            ?: return Result.failure(IllegalStateException("No encuentro el ejecutable de Claude Code."))

        val docs = loadSources(impl.sources)
        if (docs.isEmpty()) {
            return Result.failure(IllegalStateException("No encontré ningún .md en lo que cargaste."))
        }

        // El plan actual, para revisarlo en vez de rehacerlo.
        val revision = guidance?.takeIf { it.isNotBlank() }?.let { guia ->
            val previas = impls.tasks(implId)
            val texto = buildString {
                impl.planSummary?.takeIf { it.isNotBlank() }?.let { appendLine(it).appendLine() }
                previas.forEach { t ->
                    appendLine("${t.seq}. ${t.title}")
                    t.detail.takeIf { d -> d.isNotBlank() }?.let { d -> appendLine("   $d") }
                    t.steps.forEach { paso -> appendLine("   - ${paso.title}") }
                }
            }
            texto to guia
        }

        impls.setStatus(implId, ImplStatus.PLANNING)
        log(
            implId,
            if (revision == null) "Leyendo ${docs.size} documento(s): ${docs.joinToString(", ") { it.name }}"
            else "Revisando el plan con lo que pediste…",
        )

        val base = Git.currentBranch(dir) ?: "develop"
        val modelo = impl.planModel ?: PLAN_MODEL
        val idioma = prefs.get(io.acr.AppContext.PREF_LANGUAGE) ?: "español"

        return runCatching {
            val res = ClaudeCli.run(
                binary = binario,
                workDir = dir,
                prompt = ImplPrompt.plan(
                    docs, impl.extraPrompt,
                    repos.map { r ->
                        val rol = impls.reposOf(implId).firstOrNull { it.repoId == r.id }?.role
                        Triple(r.name, (rol ?: RepoRole.OTHER).name, r.localPath)
                    },
                    base, idioma, revision,
                ),
                model = modelo,
                // Planificar es mirar: los permisos de escritura llegan recién al implementar.
                // `Bash(ls *)` y `Bash(find *)` porque con varios repositorios el modelo trabaja
                // fuera del directorio en el que está parado, y sin poder listarlos planificaría
                // a ciegas sobre todos menos uno.
                allowedTools = listOf("Read", "Grep", "Glob", "Bash(git *)", "Bash(ls *)", "Bash(find *)"),
                disallowedTools = ImplPrompt.DENIED_TOOLS,
                jsonSchema = ImplPrompt.PLAN_SCHEMA,
                register = { running["$implId#plan"] = it },
                onEvent = { evt -> resumir(evt)?.let { log(implId, it) } },
            )
            running.remove("$implId#plan")
            if (!res.ok) error(res.stderr.ifBlank { "El planificador no devolvió un plan." })

            val (resumen, rama, tareas) = parsePlan(res.structured ?: res.text, implId, repos)
            if (tareas.isEmpty()) error("El plan volvió sin tareas.")

            impls.savePlan(implId, resumen, rama, base, modelo, tareas)
            guidance?.takeIf { it.isNotBlank() }?.let { impls.saveReviewGuidance(implId, it) }
            log(
                implId,
                if (revision == null) "Plan listo: ${tareas.size} tareas, rama «$rama»."
                else "Plan revisado: ${tareas.size} tareas.",
            )
            tareas.size
        }.onFailure {
            running.remove("$implId#plan")
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
    /**
     * Guarda en un stash lo que haya sin commitear.
     *
     * Negarse a arrancar por un archivo a medias obligaba a ir a la terminal, y volver. `git
     * stash` no pierde nada —queda recuperable con `git stash pop`— y por eso se puede ofrecer
     * como una acción y no como una advertencia. Lo que no se hace nunca es descartar.
     */
    suspend fun stashDirty(repo: RepoRecord): Result<Boolean> = runCatching {
        val dir = File(repo.localPath)
        if (!Git.isDirty(dir)) return@runCatching false
        Git.stash(dir, "acr: antes de implementar").also {
            if (!it) error("No pude guardar los cambios de ${repo.name} en el stash.")
        }
    }

    suspend fun run(repos: List<RepoRecord>, implId: String): Result<Unit> {
        val impl = impls.get(implId) ?: return Result.failure(IllegalStateException("No existe."))
        val rama = impl.branch ?: return Result.failure(IllegalStateException("Falta planificar."))
        val binario = ClaudeCli.resolveBinary(prefs.get(io.acr.AppContext.PREF_CLAUDE_BINARY))
            ?: return Result.failure(IllegalStateException("No encuentro el ejecutable de Claude Code."))
        val porId = repos.associateBy { it.id }

        // Se chequean todos antes de tocar ninguno: encontrar el segundo repositorio sucio con el
        // primero ya modificado dejaría el trabajo partido a la mitad.
        repos.forEach { r ->
            val d = File(r.localPath)
            if (!Git.isRepo(d)) {
                val msg = "«${r.localPath}» no es un repositorio de git."
                impls.setStatus(implId, ImplStatus.FAILED, msg)
                return Result.failure(IllegalStateException(msg))
            }
            if (Git.isDirty(d)) {
                // Se nombra el repositorio y se dice la salida concreta. Antes decía "guardalos o
                // descartalos" sin decir cuál de los repositorios era el del problema.
                val msg = "${r.name} tiene cambios sin commitear. Desde la pantalla podés " +
                    "guardarlos en el stash y seguir; se recuperan con `git stash pop`."
                impls.setStatus(implId, ImplStatus.FAILED, msg)
                return Result.failure(IllegalStateException(msg))
            }
        }

        cancelled.remove(implId)
        impls.setStatus(implId, ImplStatus.RUNNING)
        // La misma rama en todos: buscar el trabajo de una implementación en tres repositorios con
        // tres nombres distintos es un problema que no hace falta tener.
        val configurados = impls.reposOf(implId)
        repos.forEach { r ->
            // De dónde parte cada uno: lo configurado, o la rama en la que esté parado el clon.
            // No todos los repositorios usan el mismo nombre.
            val base = configurados.firstOrNull { it.repoId == r.id }?.baseBranch
                ?: impl.baseBranch
                ?: Git.currentBranch(File(r.localPath))
                ?: "develop"
            log(implId, "${r.name}: «$rama» desde «$base»…")
            Git.checkoutBranch(File(r.localPath), rama, base)
        }

        val docs = loadSources(impl.sources)
        val idioma = prefs.get(io.acr.AppContext.PREF_LANGUAGE) ?: "español"
        val modelo = impl.codeModel ?: CODE_MODEL
        var costo = 0.0
        // Qué tareas ya usaron su reintento. En memoria y no en la base: es de esta corrida, y al
        // retomar una implementación frenada corresponde volver a tener la oportunidad.
        // Concurrente porque varias tareas pueden fallar a la vez y cada una consulta y marca su
        // reintento desde su propia corrutina.
        val reintentadas = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        return runCatching {
            kotlinx.coroutines.coroutineScope {
                while (true) {
                    if (implId in cancelled) {
                        impls.finish(implId, ImplStatus.STOPPED, costo)
                        log(implId, "Frenada. Lo hecho quedó commiteado en «$rama».")
                        break
                    }
                    val todas = impls.tasks(implId)

                    // Una tarea cuya dependencia falló no se intenta: construir sobre algo que no
                    // está da un error confuso y encima gasta una corrida entera.
                    val condependenciaRota = todas.filter { t ->
                        t.status == TaskStatus.PENDING && t.dependsOn.any { d ->
                            todas.firstOrNull { it.seq == d }?.status == TaskStatus.FAILED
                        }
                    }
                    if (condependenciaRota.isNotEmpty()) {
                        condependenciaRota.forEach { t ->
                            impls.failTask(t.id, "Depende de una tarea que falló.")
                            log(implId, "· ${t.seq}. ${t.title} — saltada, su dependencia falló.")
                        }
                        continue
                    }

                    val listas = ready(todas, porId, repos)
                    if (listas.isEmpty()) {
                        val pendientes = todas.count { it.status == TaskStatus.PENDING }
                        val fallidas = todas.count { it.status == TaskStatus.FAILED }
                        val bloqueadas = todas.count { it.status == TaskStatus.BLOCKED }
                        // Esperar una decisión no es haber fallado: no hay nada roto, hay algo que
                        // alguien tiene que definir. Mezclarlos haría buscar un error que no existe.
                        val estado = when {
                            bloqueadas > 0 || pendientes > 0 -> ImplStatus.AWAITING
                            fallidas > 0 -> ImplStatus.FAILED
                            else -> ImplStatus.DONE
                        }
                        impls.finish(implId, estado, costo)
                        log(
                            implId,
                            when (estado) {
                                ImplStatus.AWAITING ->
                                    "Avancé todo lo que pude. $bloqueadas tarea(s) esperan una decisión" +
                                        (if (pendientes > 0) " y $pendientes quedaron trabadas detrás de ellas." else ".")
                                ImplStatus.FAILED -> "Terminó con $fallidas tarea(s) fallida(s)."
                                else -> "Implementación completa."
                            },
                        )
                        break
                    }

                    _progress.update { m ->
                        m + (
                            implId to (m[implId] ?: ImplProgress(implId))
                                .copy(currentTasks = listas.map { it.seq }.toSet())
                            )
                    }
                    if (listas.size > 1) {
                        log(implId, "▶ en paralelo: " + listas.joinToString(", ") { "${it.seq}. ${it.title}" })
                    }

                    // Se lanzan juntas y se espera a que terminen todas antes de volver a mirar el
                    // grafo. Podría re-evaluarse en cuanto termina la primera, pero eso complica el
                    // control de la cancelación a cambio de poco: las tareas de una tanda duran
                    // parecido, y lo que destraba a las de la vuelta siguiente casi siempre es la
                    // última en terminar.
                    val resultados = listas.map { tarea ->
                        async {
                            runTask(
                                implId, tarea, todas, porId, repos, docs, impl.extraPrompt,
                                idioma, modelo, binario, reintentadas,
                            )
                        }
                    }.map { it.await() }
                    costo += resultados.sum()
                }
            }
        }.onFailure {
            running.keys.filter { it.startsWith("$implId#") }.forEach { running.remove(it) }
            impls.setStatus(implId, ImplStatus.FAILED, it.message)
        }.map { }
            .also { clear(implId) }
    }

    /**
     * Las tareas que pueden arrancar ahora mismo.
     *
     * El plan es un grafo, no una fila: la tarea 5 puede no necesitar nada de la 4, y hacerla
     * esperar igual es tiempo tirado. Lo que decide es `depends_on`, que hasta ahora existía sólo
     * para saber a quién arrastraba una falla.
     *
     * **Una sola tarea por repositorio a la vez.** Dos modelos escribiendo en el mismo árbol de
     * trabajo se pisan los archivos, y el commit que la herramienta hace al terminar una se llevaría
     * puesto lo que la otra dejó a medias. El paralelismo real aparece cuando la implementación toca
     * varios repositorios —el backend y el frontend avanzando a la vez— que es justo el caso donde
     * más se nota.
     */
    internal fun ready(
        todas: List<ImplTask>,
        porId: Map<String, RepoRecord>,
        repos: List<RepoRecord>,
    ): List<ImplTask> {
        val hechas = todas.filter { it.status == TaskStatus.DONE }.map { it.seq }.toSet()
        val existen = todas.map { it.seq }.toSet()
        val ocupados = mutableSetOf<String>()
        return todas.asSequence()
            .filter { it.status == TaskStatus.PENDING }
            .filter { t ->
                // Se ignoran las dependencias imposibles: una que apunta a una tarea que no existe,
                // y una que apunta hacia adelante. Las dos son errores del plan, y honrarlas dejaría
                // la tarea esperando para siempre a algo que nunca va a llegar —una implementación
                // trabada sin nada roto y sin nada que decir—. El orden del plan manda.
                t.dependsOn.filter { it in existen && it < t.seq }.all { it in hechas }
            }
            // En orden de plan: cuando dos pueden arrancar y comparten repositorio, va la de
            // antes. El número de tarea es lo que alguien mira para saber por dónde va.
            .sortedBy { it.seq }
            .filter { t ->
                val repo = (porId[t.repoId] ?: repos.firstOrNull())?.id ?: return@filter false
                ocupados.add(repo)
            }
            .toList()
    }

    /**
     * Corre una tarea de punta a punta y devuelve lo que costó.
     *
     * Todo lo que toca —la rama, el commit, el reintento— es de su repositorio, así que dos de
     * estas pueden correr a la vez mientras no compartan uno.
     */
    private suspend fun runTask(
        implId: String,
        tarea: ImplTask,
        todas: List<ImplTask>,
        porId: Map<String, RepoRecord>,
        repos: List<RepoRecord>,
        docs: List<SourceDoc>,
        extra: String?,
        idioma: String,
        modelo: String,
        binario: String,
        reintentadas: MutableSet<String>,
    ): Double {
        val repoTarea = porId[tarea.repoId] ?: repos.first()
        val dir = File(repoTarea.localPath)
        val clave = "$implId#${tarea.id}"

        impls.startTask(tarea.id)
        log(implId, "▶ ${tarea.seq}/${todas.size} [${repoTarea.name}] ${tarea.title}")

        val prompt = ImplPrompt.task(
            tarea, todas, docs, extra, idioma,
            // Lo ya decidido viaja en cada tarea: sin eso, la siguiente vuelve a toparse con la
            // misma duda y frena de nuevo por algo ya resuelto.
            decided = impls.questions(implId)
                .filter { !it.answer.isNullOrBlank() }
                .map { it.question to it.answer!! },
        )
        // Se guarda antes de correr: si la tarea revienta a mitad de camino, el prompt es justo lo
        // que hace falta para entender por qué.
        impls.savePrompt(tarea.id, prompt)

        val intento = runCatching {
            ClaudeCli.run(
                binary = binario,
                workDir = dir,
                prompt = prompt,
                model = modelo,
                allowedTools = ImplPrompt.WRITE_TOOLS,
                disallowedTools = ImplPrompt.DENIED_TOOLS,
                jsonSchema = ImplPrompt.TASK_SCHEMA,
                register = { running[clave] = it },
                onEvent = { evt -> resumir(evt)?.let { log(implId, it) } },
            )
        }
        running.remove(clave)
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
            return 0.0
        }

        if (!res.ok) {
            // Un reintento antes de darla por perdida. Corriendo sin nadie mirando, una caída
            // pasajera —el proceso muere, el modelo devuelve vacío— frenaría la implementación
            // entera hasta que alguien la mire, que es justo lo que no puede pasar. Uno solo: si
            // falla dos veces, el problema no es de suerte.
            if (tarea.id !in reintentadas) {
                reintentadas += tarea.id
                impls.resetTask(tarea.id)
                log(implId, "↻ ${tarea.seq}. reintentando: ${res.stderr.take(120)}")
            } else {
                impls.failTask(tarea.id, res.stderr.ifBlank { "Terminó sin resultado." }, res.costUsd)
                log(implId, "✗ ${tarea.seq}. ${res.stderr.take(160)}")
            }
            return res.costUsd ?: 0.0
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
            return res.costUsd ?: 0.0
        }

        // El commit lo hace la herramienta y no el modelo: así el mensaje es uniforme y, sobre
        // todo, no depende de que el modelo se acuerde. Sin commit por tarea, una falla en la
        // número siete se lleva puesto todo lo anterior.
        val sha = if (Git.isDirty(dir)) {
            Git.commitAll(dir, "${tarea.seq}. ${tarea.title}\n\n${res.text.take(1_500)}")
        } else {
            // Terminar sin cambios es sospechoso, pero no siempre un error: puede ser una tarea
            // que ya estaba hecha. Se registra y se sigue.
            log(implId, "  (sin cambios en el árbol)")
            null
        }

        // Los pasos se cierran con lo que reportó el modelo, al terminar y no mientras corre: no
        // hay forma de saber desde afuera en cuál está —los eventos del CLI dicen qué archivo
        // tocó, no qué paso del plan estaba haciendo—. Inventar un avance paso a paso sería
        // mostrar una barra que no mide nada.
        if (tarea.steps.isNotEmpty()) {
            val reportados = salida.steps.associateBy { it.first }
            tarea.steps.forEach { paso ->
                val r = reportados[paso.seq]
                // Un paso del que no dijo nada queda sin hacer, no hecho: dar por bueno lo que
                // nadie confirmó es exactamente lo que hace inútil una lista de pasos.
                impls.finishStep(paso.id, done = r?.second ?: false, note = r?.third)
            }
            val hechos = tarea.steps.count { p -> reportados[p.seq]?.second == true }
            if (hechos < tarea.steps.size) {
                log(implId, "  ($hechos de ${tarea.steps.size} pasos hechos)")
            }
        }

        // Qué tocó, tomado del commit recién hecho. Se guarda ahora y no se calcula al mirar:
        // preguntarle a git por cada tarea en cada apertura sería una llamada por fila, y así
        // sobrevive a que la rama se borre.
        val cambios = sha?.let { x -> runCatching { Git.commitStats(dir, x) }.getOrDefault(emptyList()) }
        impls.finishTask(
            tarea.id, sha, salida.summary.take(4_000), res.costUsd,
            diff = cambios?.takeIf { it.isNotEmpty() }?.let { fs ->
                io.acr.impl.TaskDiff(
                    filesAdded = fs.count { it.status == 'A' },
                    filesModified = fs.count { it.status != 'A' && it.status != 'D' },
                    filesDeleted = fs.count { it.status == 'D' },
                    linesAdded = fs.sumOf { it.added },
                    linesDeleted = fs.sumOf { it.deleted },
                    files = fs,
                )
            },
        )
        log(implId, "✓ ${tarea.seq}. ${tarea.title}${sha?.let { " · ${it.take(7)}" }.orEmpty()}")
        return res.costUsd ?: 0.0
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
    suspend fun planAndRun(repos: List<RepoRecord>, implId: String): Result<Unit> {
        val planificado = plan(repos, implId)
        if (planificado.isFailure) return Result.failure(planificado.exceptionOrNull() ?: Exception("falló"))
        if (implId in cancelled) return Result.success(Unit)
        return run(repos, implId)
    }

    /** Lo que devolvió una tarea. */
    private data class TaskOut(
        val blocked: Boolean,
        val summary: String,
        val question: Pregunta?,
        /** Qué dijo el modelo de cada paso: número, si lo hizo, y la nota. */
        val steps: List<Triple<Int, Boolean, String?>> = emptyList(),
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
        val pasos = (obj["steps_done"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val n = o["seq"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
            Triple(
                n,
                o["done"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                o["note"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            )
        }
        return TaskOut(
            blocked = estado == "BLOCKED" && q != null,
            summary = resumen,
            question = q,
            steps = pasos,
        )
    }

    /** Lee el plan que devolvió el modelo. */
    private fun parsePlan(
        raw: String,
        implId: String,
        repos: List<RepoRecord>,
    ): Triple<String, String, List<ImplTask>> {
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
                // El modelo devuelve el nombre; acá se resuelve al id. Si no coincide con
                // ninguno cae al primero: una tarea sin repositorio no se podría ejecutar, y con
                // un solo repositorio el nombre da igual.
                repoId = o["repo"]?.jsonPrimitive?.contentOrNull?.let { n ->
                    repos.firstOrNull { it.name.equals(n.trim(), ignoreCase = true) }?.id
                } ?: repos.firstOrNull()?.id,
                title = titulo,
                detail = o["detail"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                dependsOn = (o["depends_on"] as? JsonArray).orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() },
                size = TaskSize.fromApi(o["size"]?.jsonPrimitive?.contentOrNull),
                estimateMin = o["estimate_min"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                status = TaskStatus.PENDING,
                commitSha = null, result = null, error = null, costUsd = null,
                startedAt = null, finishedAt = null,
                // Los pasos se renumeran por posición igual que las tareas, y por la misma razón.
                // El id va vacío: lo pone la base al guardarlos, junto con el de su tarea.
                steps = (o["steps"] as? JsonArray).orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotBlank() } }
                    .mapIndexed { j, texto ->
                        ImplStep(
                            id = "", taskId = "", seq = j + 1, title = texto,
                            status = TaskStatus.PENDING, note = null,
                            createdAt = "", updatedAt = null, startedAt = null, finishedAt = null,
                        )
                    },
            )
        }
        val rama = obj["branch"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.trim('/')?.takeIf { it.isNotBlank() } ?: "implementacion"
        return Triple(obj["summary"]?.jsonPrimitive?.contentOrNull.orEmpty(), rama, tareas)
    }
}
