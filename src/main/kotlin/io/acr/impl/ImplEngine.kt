package io.acr.impl

import io.acr.claude.ClaudeCli
import io.acr.claude.Git
import io.acr.data.ImplRepository
import io.acr.data.PrefsRepo
import io.acr.forge.RepoRecord
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    /**
     * Los jobs y el contexto: lo que sobrevive a que la app se muera.
     *
     * El motor guarda su estado en memoria —qué corre, qué se canceló— y eso desaparece con el
     * proceso. Todo lo que hace falta para retomar pasa por acá.
     */
    private val jobs: io.acr.data.JobRepository,
    /**
     * El taller aparte. Null hace que todo trabaje en el clon del usuario, como antes.
     *
     * Es un parámetro y no un singleton para que los tests puedan correr el motor sin tocar el disco
     * del usuario, que es exactamente el problema que los workspaces vienen a resolver.
     */
    private val workspaces: Workspaces? = null,
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

    /**
     * Late mientras el bloque corre.
     *
     * Es lo único que distingue después un job vivo de un cadáver. Cada veinte segundos: más
     * seguido serían escrituras por nada —una tarea dura minutos— y más espaciado alargaría el
     * tiempo que un job muerto sigue pareciendo vivo, que es justo la ventana en la que alguien
     * puede lanzar un segundo intento sobre trabajo que todavía está en marcha.
     */
    private suspend fun <T> latiendo(jobId: String, block: suspend () -> T): T =
        kotlinx.coroutines.coroutineScope {
            val pulso = launch {
                while (true) {
                    delay(20_000)
                    runCatching { jobs.beat(jobId) }
                }
            }
            try {
                block()
            } finally {
                pulso.cancel()
            }
        }

    /**
     * Traduce un evento del CLI a un hecho del contexto, o null si no aporta.
     *
     * Se guardan hechos observados y no el resumen del modelo porque el resumen sólo llega al
     * final, y en el único caso que importa —el proceso se murió a la mitad— no llega nunca. Qué
     * archivo tocó y qué comando corrió sí se sabe mientras pasa.
     */
    private fun aContexto(evt: io.acr.claude.ClaudeEvent): Pair<ContextKind, String>? =
        (evt as? io.acr.claude.ClaudeEvent.ToolUse)?.let { u ->
            val detalle = u.detail.trim().takeIf { it.isNotBlank() } ?: return null
            when (u.tool) {
                "Write", "Edit", "NotebookEdit" -> ContextKind.FILE to detalle.take(200)
                "Bash" -> ContextKind.CMD to detalle.take(200)
                else -> null
            }
        }

    private fun log(implId: String, linea: String) {
        _progress.update { m ->
            val actual = m[implId] ?: ImplProgress(implId)
            m + (implId to actual.copy(lines = (actual.lines + linea).takeLast(400)))
        }
    }

    /**
     * Igual, pero además lo guarda en el registro del job.
     *
     * Para los jobs sin tarea —analizar, auditar, planificar— este registro es todo su rastro: el
     * feed vive en memoria y desaparece al cerrar la app, y sin nada escrito un análisis que se
     * cortó y uno que nunca se lanzó se ven igual.
     */
    private fun log(implId: String, jobId: String, linea: String) {
        log(implId, linea)
        runCatching { jobs.logLine(jobId, linea) }
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
         * Null es planificar de cero. Con texto —aunque sea vacío— al modelo se le da el plan
         * actual y se le pide el revisado: replanificar de cero perdería las decisiones de orden que
         * ya estaban bien y devolvería otras distintas, y entonces no habría forma de saber si la
         * revisión mejoró algo o simplemente barajó de nuevo.
         *
         * La guía puede venir vacía. El prompt de revisión ya dice qué mirar —orden, tamaño,
         * pasos—, así que obligar a escribir algo era pedir que alguien redacte lo que el sistema
         * ya sabe pedir.
         */
        guidance: String? = null,
        /** True cuando esto es una replanificación, aunque no haya guía escrita. */
        revising: Boolean = guidance != null,
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
        val revision = if (!revising) null else run {
            // Vacío es vacío: el prompt de revisión ya sabe qué mirar, y rellenar con una frase
            // inventada sería ponerle palabras a alguien que no dijo nada. El modelo las leería
            // como una instrucción más.
            val guia = guidance.orEmpty()
            val previas = impls.tasks(implId)
            val intocables = previas.filter {
                it.status == TaskStatus.DONE || it.status == TaskStatus.RUNNING
            }
            val texto = buildString {
                impl.planSummary?.takeIf { it.isNotBlank() }?.let { appendLine(it).appendLine() }
                previas.forEach { t ->
                    val marca = when (t.status) {
                        TaskStatus.DONE -> " [YA HECHA — no la repitas]"
                        TaskStatus.RUNNING -> " [SE ESTÁ HACIENDO AHORA — no la repitas]"
                        else -> ""
                    }
                    appendLine("${t.seq}. ${t.title}$marca")
                    t.detail.takeIf { d -> d.isNotBlank() }?.let { d -> appendLine("   $d") }
                    t.steps.forEach { paso -> appendLine("   - ${paso.title}") }
                }
                if (intocables.isNotEmpty()) {
                    appendLine()
                    appendLine(
                        "Las tareas ${intocables.joinToString(", ") { "#${it.seq}" }} quedan como " +
                            "están: su código ya está escrito o se está escribiendo ahora. Planificá " +
                            "SÓLO lo que falta, y numerá desde 1 — la herramienta las renumera " +
                            "a continuación de las que quedan.",
                    )
                }
            }
            texto to guia
        }

        // Planificar también es un job. Sin él, una app que se cierra mientras planifica deja la
        // implementación en PLANNING para siempre, sin nada corriendo y sin nada que lo delate: no
        // hay tarea que mirar, porque el plan es justo lo que todavía no existe.
        val jobPlan = jobs.start(JobKind.PLAN, implId, workDir = principal.localPath)
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
            val res = latiendo(jobPlan) { ClaudeCli.run(
                binary = binario,
                workDir = dondeMirar(implId, impl.useWorkspace, principal),
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
                kind = if (revising) "replan" else "plan",
                register = { running["$implId#plan"] = it },
                onEvent = { evt ->
                    resumir(evt)?.let { log(implId, it) }
                    (evt as? io.acr.claude.ClaudeEvent.Started)?.let {
                        jobs.attachSession(jobPlan, it.sessionId, null)
                    }
                },
            ) }
            running.remove("$implId#plan")
            if (!res.ok) error(res.stderr.ifBlank { "El planificador no devolvió un plan." })

            val (resumen, rama, tareas) = parsePlan(res.structured ?: res.text, implId, repos)
            if (tareas.isEmpty()) error("El plan volvió sin tareas.")

            // Lo que el plan necesita y no está declarado. Se guarda aunque el plan haya salido
            // bien: casi siempre la respuesta no es "el plan está mal" sino "falta declarar un
            // repositorio", y eso se arregla en dos minutos si alguien se entera.
            val faltantes = runCatching {
                parseMissing(
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                        .parseToJsonElement(
                            (res.structured ?: res.text).trim()
                                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim(),
                        ).jsonObject,
                )
            }.getOrDefault(emptyList())
            impls.setMissingRepos(implId, faltantes)
            faltantes.forEach { m ->
                log(implId, "⚠ Falta declarar «${m.name}»${m.path?.let { " ($it)" }.orEmpty()}: ${m.evidence}")
            }

            // Si alguien escribió el nombre de la rama, manda sobre el que propone el modelo. Es
            // una decisión de una persona sobre algo que después va a buscar a mano en git.
            val ramaFinal = if (impl.branchFixed) impl.branch ?: rama else rama
            impls.savePlan(implId, resumen, ramaFinal, base, modelo, tareas, revision = revising)
            guidance?.takeIf { it.isNotBlank() }?.let { impls.saveReviewGuidance(implId, it) }
            log(
                implId,
                if (revision == null) "Plan listo: ${tareas.size} tareas, rama «$ramaFinal»."
                else "Plan revisado: ${tareas.size} tareas.",
            )
            jobs.finish(jobPlan, JobState.DONE)
            tareas.size
        }.onFailure {
            running.remove("$implId#plan")
            jobs.finish(jobPlan, JobState.FAILED, it.message)
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
        // Dónde se trabaja cada repositorio: el workspace si la implementación lo usa, el clon del
        // usuario si no. Todo lo demás —checkout, commits, diffs, revisión— usa esta función y no la
        // ruta del repositorio, así que hay un solo lugar donde esto se decide.
        val enWorkspace = impl.useWorkspace && workspaces != null
        fun dirDe(r: RepoRecord): File =
            if (enWorkspace) workspaces!!.repoDir(implId, r.name) else File(r.localPath)
        // Nota: acá se usa `repoDir` y no `dirFor` a propósito. `prepare` todavía no corrió, así que
        // la carpeta puede no existir y `dirFor` caería al clon — que es justo lo que no queremos.

        // Con workspace, lo que el usuario tenga sin commitear en su clon no importa: no se toca su
        // árbol de trabajo. Eso elimina de raíz el bloqueo que hasta ahora impedía arrancar.
        //
        // Sin workspace se chequean todos antes de tocar ninguno: encontrar el segundo repositorio
        // sucio con el primero ya modificado dejaría el trabajo partido a la mitad.
        if (!enWorkspace) repos.forEach { r ->
            val d = File(r.localPath)
            if (!Git.isRepo(d)) {
                val msg = "«${r.localPath}» no es un repositorio de git."
                impls.setStatus(implId, ImplStatus.FAILED, msg)
                return Result.failure(IllegalStateException(msg))
            }
            if (Git.isDirty(d)) {
                // Salvo que la suciedad sea nuestra.
                //
                // Estar parado en la rama de esta implementación significa que esos cambios los
                // dejó una corrida anterior de esto mismo: la rama la creamos nosotros. Negarse ahí
                // haría imposible retomar después de un corte, que es exactamente el caso para el
                // que existe todo el mecanismo de contexto — el árbol queda sucio *porque* la tarea
                // no llegó a commitear.
                val nuestra = Git.currentBranch(d) == rama
                if (!nuestra) {
                    // Se nombra el repositorio y se dice la salida concreta. Antes decía "guardalos
                    // o descartalos" sin decir cuál de los repositorios era el del problema.
                    val msg = "${r.name} tiene cambios sin commitear en «${Git.currentBranch(d)}», " +
                        "que no son de esta implementación. Guardalos en el stash desde la pantalla " +
                        "y volvé a intentar; se recuperan con `git stash pop`."
                    // Al feed además del estado: rechazar en el primer instante dejaba la pantalla
                    // igual que antes de apretar —el mismo error, sin nada nuevo— y eso se lee como
                    // un botón que no hace nada.
                    log(implId, "No puedo arrancar. $msg")
                    impls.setStatus(implId, ImplStatus.FAILED, msg)
                    return Result.failure(IllegalStateException(msg))
                }
                log(implId, "${r.name}: retomando sobre los cambios que quedaron de la corrida anterior.")
            }
        }

        cancelled.remove(implId)
        // El taller, antes de tocar nada. Idempotente: retomar es el caso normal, así que si el clon
        // del workspace ya está se reusa con todo lo que las tareas anteriores dejaron commiteado.
        if (enWorkspace) {
            val configurados0 = impls.reposOf(implId)
            val prep = workspaces!!.prepare(
                implId, repos, rama,
                baseDe = { id ->
                    configurados0.firstOrNull { it.repoId == id }?.baseBranch
                        ?: impl.baseBranch ?: "develop"
                },
                log = { log(implId, it) },
            )
            if (prep.isFailure) {
                val msg = prep.exceptionOrNull()?.message ?: "No pude preparar el workspace."
                impls.setStatus(implId, ImplStatus.FAILED, msg)
                log(implId, msg)
                return Result.failure(IllegalStateException(msg))
            }
        }
        // El job de la implementación: el padre del que cuelgan los de cada tarea. Es lo que se
        // arranca y se pausa cuando se arranca y se pausa la implementación, y lo que después
        // permite preguntar "¿qué había en marcha?" sin recorrer tarea por tarea.
        val jobImpl = jobs.start(JobKind.IMPL, implId, workDir = repos.firstOrNull()?.localPath)
        // Retomar tiene que reintentar lo que falló. El motor sólo toma tareas pendientes, así que
        // sin esto una implementación fallida se retomaba, no hacía nada, y volvía a terminar
        // mostrando el mismo error —que además ya no describía nada actual—.
        val reencoladas = impls.retryFailed(implId)
        // Y el feed arranca limpio: las líneas de la corrida anterior arriba de las nuevas hacen
        // imposible saber si el "✗" que se está leyendo es de ahora o de hace media hora.
        clear(implId)
        impls.setStatus(implId, ImplStatus.RUNNING)
        if (reencoladas > 0) log(implId, "Reintentando $reencoladas tarea(s) que habían fallado.")
        // La misma rama en todos: buscar el trabajo de una implementación en tres repositorios con
        // tres nombres distintos es un problema que no hace falta tener.
        val configurados = impls.reposOf(implId)
        val baseDe = mutableMapOf<String, String>()
        if (enWorkspace) {
            // Con workspace la rama ya la dejó `prepare`; acá sólo se anota de dónde partió cada
            // uno, que es lo que la revisión final necesita para armar el rango del diff.
            repos.forEach { r ->
                baseDe[r.id] = configurados.firstOrNull { it.repoId == r.id }?.baseBranch
                    ?: impl.baseBranch ?: "develop"
            }
        }
        if (!enWorkspace) repos.forEach { r ->
            // De dónde parte cada uno: lo configurado, o la rama en la que esté parado el clon.
            // No todos los repositorios usan el mismo nombre.
            val base = configurados.firstOrNull { it.repoId == r.id }?.baseBranch
                ?: impl.baseBranch
                ?: Git.currentBranch(File(r.localPath))
                ?: "develop"
            baseDe[r.id] = base
            // Una carpeta recién inicializada no tiene ningún commit, así que no hay de dónde
            // partir: `git checkout -b x base` falla porque `base` no existe todavía. Se queda
            // donde está y el primer commit de la primera tarea crea la rama.
            val virgen = Git.currentHead(File(r.localPath)) == null
            if (virgen) {
                log(implId, "${r.name}: repositorio sin historial, el primer commit abre la rama.")
            } else {
                log(implId, "${r.name}: «$rama» desde «$base»…")
                Git.checkoutBranch(File(r.localPath), rama, base)
                // Y se verifica que haya quedado ahí. `git checkout` falla en silencio —una base que
                // no existe, un archivo que se pisaría— y el resultado se descartaba: la
                // implementación seguía como si nada, escribiendo y commiteando sobre la rama vieja.
                val quedo = Git.currentBranch(File(r.localPath))
                if (quedo != rama) {
                    val msg = "${r.name} no pudo pasar a «$rama» (quedó en «${quedo ?: "?"}»). " +
                        "Sin la rama correcta, el trabajo se commitearía sobre la de siempre."
                    log(implId, msg)
                    jobs.finish(jobImpl, JobState.FAILED, msg)
                    impls.setStatus(implId, ImplStatus.FAILED, msg)
                    return Result.failure(IllegalStateException(msg))
                }
            }
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
        // Con qué terminó de verdad. El job cerraba siempre en DONE, así que una implementación
        // que terminó fallida o esperando una decisión dejaba un job diciendo que salió todo bien
        // — y la pantalla de trabajos, que es donde uno mira cuando algo no cierra, mentía.
        var estadoFinal: ImplStatus? = null

        return runCatching {
            kotlinx.coroutines.coroutineScope {
                while (true) {
                    if (implId in cancelled) {
                        impls.finish(implId, ImplStatus.STOPPED, costo)
                        estadoFinal = ImplStatus.STOPPED
                        log(implId, "Frenada. Lo hecho quedó commiteado en «$rama».")
                        break
                    }
                    // El job de la implementación también tiene que latir. Sin esto, una
                    // implementación que corre dos horas aparece "sin latido" a los dos minutos y
                    // la pantalla de trabajos la muestra como muerta mientras está trabajando: la
                    // única señal que sirve para distinguir vivos de cadáveres pasa a mentir.
                    runCatching { jobs.beat(jobImpl) }
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

                    val listas = ready(todas, porId, repos, impl.maxParallel)
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
                        // Las pasadas de revisión, sobre lo que quedó. Sólo si terminó bien: no
                        // tiene sentido buscar bugs finos en una rama a la que le falta la mitad,
                        // y sería pagar por hallazgos que la tarea faltante iba a cambiar igual.
                        if (estado == ImplStatus.DONE && impl.reviewMax > 0 && implId !in cancelled) {
                            val tocados = todas.mapNotNull { t -> porId[t.repoId] }.distinctBy { it.id }
                            tocados.forEach { r ->
                                costo += audit(
                                    implId, r, dirDe(r), docs, binario, modelo, idioma,
                                    impl.reviewMin.coerceAtLeast(1), impl.reviewMax,
                                    baseRef = baseDe[r.id] ?: "HEAD",
                                )
                            }
                        }
                        // El trabajo vuelve al clon del usuario y el taller se cierra.
                        //
                        // En este orden y no al revés: empujar, verificar que los dos shas coincidan
                        // y sólo entonces borrar. Borrar primero y verificar después es como se
                        // pierde el trabajo de una tarde, y con un workspace por implementación el
                        // trabajo de una tarde es exactamente lo que hay adentro.
                        //
                        // Sólo cuando terminó de verdad. Una implementación que quedó esperando una
                        // decisión o con tareas fallidas se va a retomar, y para eso necesita su
                        // taller con todo lo que hay commiteado.
                        if (enWorkspace && estado == ImplStatus.DONE) {
                            val problemas = workspaces!!.syncBack(implId, repos, rama) { log(implId, it) }
                                .mapNotNull { (nombre, error) -> error?.let { "$nombre: $it" } }
                            if (problemas.isEmpty()) {
                                workspaces.delete(implId, repos, rama)
                                    .onSuccess { log(implId, "Workspace borrado: el código quedó en las ramas.") }
                                    .onFailure { log(implId, "No pude borrar el workspace: ${it.message}") }
                            } else {
                                // Se conserva a propósito: es el único lugar donde está ese trabajo.
                                log(
                                    implId,
                                    "El workspace se conserva porque no pude devolver todo: " +
                                        problemas.joinToString("; "),
                                )
                            }
                        }
                        impls.finish(implId, estado, costo)
                        estadoFinal = estado
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
                                dirDe(porId[tarea.repoId] ?: repos.first()), jobImpl,
                                revisar = if (!impl.reviewEach) {
                                    null
                                } else {
                                    Triple(
                                        1,
                                        // Por tarea alcanza con menos: el alcance es un commit, no
                                        // una rama entera, y hacer cinco pasadas sobre un cambio
                                        // chico es pagar de más por buscar donde ya no hay.
                                        impl.reviewMax.coerceAtMost(2),
                                        baseDe[porId[tarea.repoId]?.id ?: ""] ?: "HEAD",
                                    )
                                },
                            )
                        }
                    }.map { it.await() }
                    costo += resultados.sum()
                }
            }
        }.onFailure {
            running.keys.filter { it.startsWith("$implId#") }.forEach { running.remove(it) }
            jobs.finish(jobImpl, JobState.FAILED, it.message)
            impls.setStatus(implId, ImplStatus.FAILED, it.message)
        }.onSuccess {
            jobs.finish(
                jobImpl,
                when (estadoFinal) {
                    ImplStatus.DONE -> JobState.DONE
                    ImplStatus.FAILED -> JobState.FAILED
                    ImplStatus.STOPPED -> JobState.PAUSED
                    // Esperando una decisión no es ni terminado ni roto: es pausado, y es
                    // exactamente lo que hace falta ver para saber que algo está trabado.
                    ImplStatus.AWAITING -> JobState.PAUSED
                    else -> JobState.DONE
                },
            )
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
        /**
         * Tope de tareas a la vez, encima del límite físico. Null es sin tope.
         *
         * El límite por repositorio existe porque dos modelos en el mismo árbol se pisan; este
         * existe por otra razón: seis procesos a la vez cuestan seis veces y ocupan una máquina que
         * alguien está usando. Son dos cosas distintas y por eso se aplican las dos.
         */
        maxParalelo: Int? = null,
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
            // Primero lo urgente, después el orden del plan. Una corrección escrita a mano
            // mientras esto corre existe justamente para atenderse antes que lo que quedaba: si
            // tuviera que esperar su turno al final de la fila, llegaría cuando ya no sirve.
            .sortedWith(compareByDescending<ImplTask> { it.priority }.thenBy { it.seq })
            .filter { t ->
                val repo = (porId[t.repoId] ?: repos.firstOrNull())?.id ?: return@filter false
                ocupados.add(repo)
            }
            // El tope se aplica al final, sobre lo que ya pasó el filtro del repositorio: cortar
            // antes dejaría afuera tareas de repositorios distintos por culpa de una que se
            // descarta igual.
            .let { if (maxParalelo == null || maxParalelo <= 0) it else it.take(maxParalelo) }
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
        /** Dónde trabajar: el clon del usuario, o su copia en el workspace. */
        workDir: File,
        /** El job de la implementación, del que este cuelga. */
        jobPadre: String?,
        /** Si hay que revisar apenas termina esta tarea, y con cuántas pasadas. */
        revisar: Triple<Int, Int, String>? = null,
    ): Double {
        val repoTarea = porId[tarea.repoId] ?: repos.first()
        // El directorio llega desde afuera: puede ser el clon del usuario o el del workspace, y esa
        // decisión se toma una sola vez arriba en vez de repetirse en cada función que necesita una
        // ruta —que es como una de ellas se queda vieja y escribe en el lugar equivocado—.
        val dir = workDir
        val clave = "$implId#${tarea.id}"

        // Lo que quedó de intentos anteriores. Un intento que se cortó dejó archivos modificados y
        // decisiones tomadas: arrancar de cero sobre eso produce trabajo duplicado y, peor, código
        // que pisa lo que ya estaba bien.
        val previos = jobs.contextOf(tarea.id)
        val anterior = jobs.lastOf(tarea.id)
        // Sólo se reanuda una sesión que se cortó, no una que terminó: la que llegó al final ya
        // dijo lo suyo, y reanudarla la haría continuar un trabajo que para ella está hecho.
        val sesion = anterior?.takeIf { it.state == JobState.INTERRUPTED }?.sessionId
        val intentoNro = (anterior?.attempt ?: 0) + 1

        val jobId = jobs.start(
            JobKind.TASK, implId, tarea.id, parentId = jobPadre, workDir = repoTarea.localPath,
            attempt = intentoNro,
        )
        if (intentoNro > 1) {
            jobs.add(
                tarea.id, jobId, ContextKind.RESUME,
                if (sesion != null) "Intento $intentoNro, reanudando la sesión anterior."
                else "Intento $intentoNro, desde el contexto acumulado.",
            )
        }

        impls.startTask(tarea.id)
        log(
            implId,
            "▶ ${tarea.seq}/${todas.size} [${repoTarea.name}] ${tarea.title}" +
                if (sesion != null) "  (retomando)" else "",
        )

        val prompt = ImplPrompt.task(
            tarea, todas, docs, extra, idioma,
            // Lo ya decidido viaja en cada tarea: sin eso, la siguiente vuelve a toparse con la
            // misma duda y frena de nuevo por algo ya resuelto.
            decided = impls.questions(implId)
                .filter { !it.answer.isNullOrBlank() }
                .map { it.question to it.answer!! },
            // Y lo que quedó del intento anterior, cuando lo hubo. Con la sesión reanudada esto es
            // redundante pero inofensivo; sin ella es lo único que evita empezar de cero.
            context = renderContext(previos),
            dirty = if (previos.isEmpty()) null else runCatching { Git.dirtyFiles(dir) }.getOrNull(),
        )
        // Se guarda antes de correr: si la tarea revienta a mitad de camino, el prompt es justo lo
        // que hace falta para entender por qué.
        impls.savePrompt(tarea.id, prompt)

        // Reintentar mientras el problema sea del servidor y no del trabajo.
        //
        // Un `529 Overloaded` no dice nada sobre la tarea: dice que en ese instante había demasiada
        // gente. Reintentarlo lo arregla, y una implementación autónoma que se cae por un bache de
        // diez minutos justo cuando nadie mira es la peor forma de fallar que tiene esto.
        //
        // La espera arranca en cinco segundos y se estira sólo si el bache dura: pasado el minuto el
        // problema ya no es un bache, y machacar cada cinco segundos no lo apura — suma carga al
        // servidor que está justamente sobrecargado. No hay tope de intentos porque la salida es
        // cancelar, que es una decisión de una persona; un tope fijo haría que la tarea se dé por
        // vencida justo cuando el servidor estaba volviendo.
        var esperas = 0
        val intento = runCatching {
            latiendo(jobId) {
                var salida: io.acr.claude.ClaudeResult
                while (true) {
                    salida = ClaudeCli.run(
                    binary = binario,
                    workDir = dir,
                    prompt = prompt,
                    model = modelo,
                    allowedTools = ImplPrompt.WRITE_TOOLS,
                    disallowedTools = ImplPrompt.DENIED_TOOLS,
                    jsonSchema = ImplPrompt.TASK_SCHEMA,
                    kind = "task",
                    resumeSession = sesion,
                    register = { p ->
                        running[clave] = p
                        // El pid se anota apenas existe: si la app muere, es lo que permite saber
                        // después si aquel proceso sigue vivo en el sistema.
                        jobs.attachSession(jobId, null, runCatching { p.pid() }.getOrNull())
                    },
                    onEvent = { evt ->
                        resumir(evt)?.let { log(implId, it) }
                        // La sesión se anota en cuanto el CLI la anuncia, que es en el primer
                        // evento. Guardarla al final significaría no tenerla nunca justo cuando
                        // hace falta: cuando el proceso no llegó al final.
                        (evt as? io.acr.claude.ClaudeEvent.Started)?.let {
                            jobs.attachSession(jobId, it.sessionId, null)
                        }
                        aContexto(evt)?.let { (k, texto) -> jobs.add(tarea.id, jobId, k, texto) }
                    },
                    )
                    val pasajero = !salida.ok &&
                        io.acr.claude.Transient.isTransient(salida.stderr + " " + salida.text)
                    if (!pasajero || implId in cancelled) break
                    esperas++
                    val espera = io.acr.claude.Transient.waitMs(esperas)
                    log(
                        implId,
                        "⏳ ${tarea.seq}. el servidor está sobrecargado; reintento en " +
                            "${espera / 1000}s (van $esperas). " + salida.stderr.take(80),
                    )
                    jobs.add(
                        tarea.id, jobId, ContextKind.NOTE,
                        "Espera $esperas por el servidor: ${salida.stderr.take(120)}",
                    )
                    delay(espera)
                }
                salida
            }
        }
        running.remove(clave)
        val res = intento.getOrNull()
        if (res == null) {
            val e = intento.exceptionOrNull()
            // Cancelada por una persona no es lo mismo que rota: la sesión sigue siendo válida y
            // el próximo arranque la reanuda. Marcarla fallida tiraría esa posibilidad.
            val cancelada = implId in cancelled
            jobs.finish(
                jobId,
                if (cancelada) JobState.INTERRUPTED else JobState.FAILED,
                e?.message,
            )
            if (cancelada) {
                jobs.add(tarea.id, jobId, ContextKind.INTERRUPT, "Frenada a mano durante el intento $intentoNro.")
                impls.resetTask(tarea.id)
                return 0.0
            }
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
            jobs.finish(jobId, JobState.FAILED, res.stderr.take(500))
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
            // Bloqueada es un final legítimo del job, no un fallo. Y la sesión se conserva: cuando
            // alguien conteste, el próximo intento la reanuda y el modelo no vuelve a leer todo.
            jobs.finish(jobId, JobState.PAUSED)
            jobs.add(tarea.id, jobId, ContextKind.NOTE, "Frenó a preguntar: ${(p?.question ?: "").take(300)}")
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
            null
        }

        // Decir que terminó sin haber cambiado nada no es terminar.
        //
        // Salió de un caso real: siete tareas dieron DONE sin un solo commit, y los archivos habían
        // ido a parar a un repositorio vecino —`../timelog-ms`, que los documentos mencionaban—
        // donde no hay rama creada ni nadie commitea. Quedaron sueltos encima de develop, invisibles
        // para la herramienta, y el tablero decía que todo había salido bien.
        //
        // Un árbol limpio después de una tarea de código significa una de dos cosas, y las dos son
        // un problema: o no hizo nada, o lo hizo en otro lado. Darlo por bueno es lo que hace que el
        // problema no se vea.
        if (sha == null) {
            val afuera = jobs.contextOf(tarea.id)
                .filter { it.kind == ContextKind.FILE }
                .map { it.text }
                .filter { ruta -> ruta.startsWith("../") || ruta.startsWith("/") && !ruta.startsWith(repoTarea.localPath) }
                .distinct()
                .take(6)
            val motivo = if (afuera.isEmpty()) {
                "Dijo que terminó pero no cambió nada en ${repoTarea.name}."
            } else {
                "Escribió fuera de ${repoTarea.name}, donde no hay rama ni commit: " +
                    afuera.joinToString(", ") + ". Esos cambios quedaron sueltos."
            }
            impls.failTask(tarea.id, motivo, res.costUsd)
            jobs.finish(jobId, JobState.FAILED, motivo)
            jobs.add(tarea.id, jobId, ContextKind.NOTE, motivo)
            log(implId, "✗ ${tarea.seq}. $motivo")
            return res.costUsd ?: 0.0
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
                if (r?.second == true) jobs.add(tarea.id, jobId, ContextKind.STEP, paso.title)
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
        jobs.finish(jobId, JobState.DONE)
        jobs.add(tarea.id, jobId, ContextKind.NOTE, salida.summary.take(400))
        sha?.let { jobs.add(tarea.id, jobId, ContextKind.NOTE, "Commiteado en ${it.take(7)}.") }
        log(implId, "✓ ${tarea.seq}. ${tarea.title}${sha?.let { " · ${it.take(7)}" }.orEmpty()}")

        // Revisar acá, apenas terminó, y no sólo al final: un bug que sobrevive cinco tareas ya
        // tiene código encima que depende de él, y arreglarlo pasa de ser un cambio de una línea a
        // ser una discusión. Cuesta una pasada más por tarea, y por eso es opcional.
        var extraCosto = 0.0
        if (revisar != null && sha != null) {
            val (min, max, base) = revisar
            extraCosto = audit(
                implId, repoTarea, dir, docs, binario, modelo, idioma, min, max,
                tarea = tarea, baseRef = base,
            )
        }
        return (res.costUsd ?: 0.0) + extraCosto
    }

    /**
     * Lee un pedido escrito a mano y lo convierte en una tarea del plan.
     *
     * Sin esto, lo que uno escribe en la consola entra como una tarea con el texto crudo por
     * descripción: sin pasos, sin tamaño, sin saber en qué repositorio va ni a qué se refiere. El
     * modelo que después la ejecuta tiene que adivinar todo eso mientras escribe código, que es el
     * peor momento para adivinar.
     *
     * La importancia la decide la lectura y no quien escribió: el que pide una corrección siempre la
     * siente urgente, y si todo es urgente la prioridad deja de ordenar nada.
     *
     * Si la lectura falla —el modelo no está, el servidor se cayó— **el pedido entra igual**, crudo.
     * Perder una instrucción porque no se pudo interpretar sería el peor de los dos mundos: la
     * persona ya la escribió y se fue.
     */
    suspend fun addRequest(
        repos: List<RepoRecord>,
        implId: String,
        pedido: String,
        repoSugerido: String?,
    ): Result<String> {
        val impl = impls.get(implId) ?: return Result.failure(IllegalStateException("No existe."))
        val principal = repos.firstOrNull()
            ?: return Result.failure(IllegalStateException("La implementación no tiene repositorios."))
        val limpio = pedido.trim()
        if (limpio.isBlank()) return Result.failure(IllegalStateException("Pedido vacío."))

        val binario = ClaudeCli.resolveBinary(prefs.get(io.acr.AppContext.PREF_CLAUDE_BINARY))
        val tareas = impls.tasks(implId)
        val idioma = prefs.get(io.acr.AppContext.PREF_LANGUAGE) ?: "español"

        // El respaldo, por si la lectura no se puede hacer: el pedido crudo, urgente, en el
        // repositorio que la pantalla sugirió.
        fun crudo(): String = impls.addUserTask(
            implId = implId,
            repoId = repoSugerido ?: principal.id,
            title = limpio.lineSequence().first().take(120),
            detail = limpio,
            urgent = true,
        )

        if (binario == null) return Result.success(crudo())

        val jobId = jobs.start(JobKind.PLAN, implId, workDir = principal.localPath)
        log(implId, jobId, "Leyendo el pedido: ${limpio.take(120)}")

        return runCatching {
            val res = latiendo(jobId) {
                ClaudeCli.run(
                    binary = binario,
                    workDir = dondeMirar(implId, impl.useWorkspace, principal),
                    prompt = ImplPrompt.request(
                        limpio, tareas, describir(repos, implId), loadSources(impl.sources), idioma,
                    ),
                    model = impl.planModel ?: PLAN_MODEL,
                    allowedTools = listOf("Read", "Grep", "Glob", "Bash(git *)", "Bash(ls *)", "Bash(find *)"),
                    disallowedTools = ImplPrompt.DENIED_TOOLS,
                    jsonSchema = ImplPrompt.REQUEST_SCHEMA,
                    kind = "request",
                    register = { running["$implId#req"] = it },
                    onEvent = { evt -> resumir(evt)?.let { log(implId, jobId, it) } },
                )
            }
            running.remove("$implId#req")
            if (!res.ok) error(res.stderr.ifBlank { "La lectura del pedido no devolvió nada." })

            val leido = parseRequest(res.structured ?: res.text)
                ?: error("La lectura del pedido volvió sin tarea.")
            val repoId = repos.firstOrNull { it.name.equals(leido.repo, ignoreCase = true) }?.id
                ?: repoSugerido ?: principal.id
            val id = impls.addUserTask(
                implId = implId,
                repoId = repoId,
                title = leido.title,
                // El pedido textual primero y la interpretación después: es lo que permite ver si
                // le entendió. Sin el original, una lectura equivocada se ejecuta y nadie lo nota.
                detail = "Lo que se pidió:\n$limpio\n\n${leido.detail}",
                urgent = leido.priority > 0,
                estimateMin = leido.estimateMin,
            )
            impls.setTaskPriority(id, leido.priority)
            leido.steps.takeIf { it.isNotEmpty() }?.let { impls.setTaskSteps(id, it) }
            jobs.finish(jobId, JobState.DONE)
            log(
                implId, jobId,
                "✓ ${leido.importance}: ${leido.title}" +
                    leido.why?.let { " — $it" }.orEmpty() +
                    leido.fixes.takeIf { it.isNotEmpty() }
                        ?.let { "  (corrige " + it.joinToString(", ") { n -> "#$n" } + ")" }.orEmpty(),
            )
            id
        }.recoverCatching {
            running.remove("$implId#req")
            jobs.finish(jobId, JobState.FAILED, it.message)
            log(implId, jobId, "No pude interpretar el pedido; lo agrego tal cual: ${it.message.orEmpty().take(150)}")
            crudo()
        }
    }

    /** Lo que devolvió la lectura de un pedido. */
    private data class Pedido(
        val title: String,
        val detail: String,
        val repo: String?,
        val steps: List<String>,
        val estimateMin: Int?,
        val importance: String,
        val why: String?,
        val fixes: List<Int>,
    ) {
        /**
         * De importancia a lugar en la cola.
         *
         * Cuatro niveles y no un número libre: un número invita a comparar dos pedidos que nadie
         * comparó, y termina en una escala donde todo vale 90.
         */
        val priority: Int
            get() = when (importance.uppercase()) {
                "CRITICAL" -> 300
                "HIGH" -> 200
                "NORMAL" -> 0
                else -> -100
            }
    }

    private fun parseRequest(raw: String): Pedido? {
        val limpio = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = runCatching {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                .parseToJsonElement(limpio).jsonObject
        }.getOrNull() ?: return null
        fun texto(k: String) = obj[k]?.jsonPrimitive?.contentOrNull?.trim()
        val titulo = texto("title")?.takeIf { it.isNotBlank() } ?: return null
        return Pedido(
            title = titulo.take(200),
            detail = texto("detail").orEmpty(),
            repo = texto("repo"),
            steps = (obj["steps"] as? JsonArray).orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotBlank() } },
            estimateMin = texto("estimate_min")?.toIntOrNull(),
            importance = texto("importance") ?: "NORMAL",
            why = texto("why"),
            fixes = (obj["fixes"] as? JsonArray).orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() },
        )
    }

    /**
     * Analiza los documentos y deja uno nuevo con lo que falta y lo que es ambiguo.
     *
     * Un plan no puede ser mejor que las specs de las que sale. Lo que las specs no dicen, el
     * planificador lo inventa —bien, con seguridad, sin marcarlo— y el hueco aparece recién cuando
     * el código está escrito y hace otra cosa.
     *
     * **No toca los documentos originales.** Escribe uno nuevo al lado y lo suma a los que el
     * planificador va a leer. Una spec es un acuerdo entre personas, no un borrador de esta app:
     * reescribirla en el lugar borraría lo que alguien redactó y acordó, y dejaría sin forma de
     * saber qué se cambió.
     */
    suspend fun improveSpecs(repos: List<RepoRecord>, implId: String): Result<String> {
        val impl = impls.get(implId) ?: return Result.failure(IllegalStateException("No existe."))
        val principal = repos.firstOrNull()
            ?: return Result.failure(IllegalStateException("La implementación no tiene repositorios."))
        val binario = ClaudeCli.resolveBinary(prefs.get(io.acr.AppContext.PREF_CLAUDE_BINARY))
            ?: return Result.failure(IllegalStateException("No encuentro el ejecutable de Claude Code."))
        val docs = loadSources(impl.sources)
        if (docs.isEmpty()) return Result.failure(IllegalStateException("No hay documentos que analizar."))

        val idioma = prefs.get(io.acr.AppContext.PREF_LANGUAGE) ?: "español"
        // Con job, como todo lo que corre: sin él, un análisis que se corta con la app no deja
        // rastro —no hay tarea que mirar— y desde afuera es idéntico a uno que nunca se lanzó.
        val jobSpecs = jobs.start(JobKind.SPECS, implId, workDir = principal.localPath)
        // Uno por línea y con su tamaño: "analizando 9 documentos" no deja ver que el que
        // importaba entró vacío, y ese es el modo de fallar más callado que tiene esto.
        log(implId, jobSpecs, "Leyendo ${docs.size} documento(s):")
        docs.forEach { d -> log(implId, jobSpecs, "  · ${d.name} (${d.content.length} caracteres)") }
        log(implId, jobSpecs, "Analizando qué falta, qué es ambiguo y qué se contradice…")

        return runCatching {
            val res = latiendo(jobSpecs) { ClaudeCli.run(
                binary = binario,
                workDir = dondeMirar(implId, impl.useWorkspace, principal),
                prompt = ImplPrompt.improveSpecs(docs, describir(repos, implId), impl.extraPrompt, idioma),
                model = impl.planModel ?: PLAN_MODEL,
                allowedTools = listOf("Read", "Grep", "Glob", "Bash(git *)", "Bash(ls *)", "Bash(find *)"),
                disallowedTools = ImplPrompt.DENIED_TOOLS,
                jsonSchema = ImplPrompt.SPECS_SCHEMA,
                kind = "specs",
                register = { running["$implId#specs"] = it },
                onEvent = { evt ->
                    resumir(evt)?.let { log(implId, jobSpecs, it) }
                    (evt as? io.acr.claude.ClaudeEvent.Started)?.let {
                        jobs.attachSession(jobSpecs, it.sessionId, null)
                    }
                },
            ) }
            running.remove("$implId#specs")
            if (!res.ok) error(res.stderr.ifBlank { "El análisis no devolvió nada." })

            val salida = parseSpecs(res.structured ?: res.text)
            if (salida.documento.isBlank()) error("El análisis volvió sin documento.")

            // Al lado de las specs, no adentro de la app: es un documento del proyecto y tiene que
            // poder leerse, versionarse y discutirse como los otros. Si esa carpeta no se puede
            // escribir, cae al directorio de datos antes que perderse.
            val destino = destinoDoc(impl.sources, impl.title)
            destino.parentFile?.mkdirs()
            destino.writeText(salida.documento)
            impls.addSource(implId, destino.absolutePath)

            val abiertas = salida.issues.count { !it.second }
            impls.saveReview(
                implId, null, 1, salida.issues.size, salida.issues.size - abiertas,
                salida.resumen, salida.issues.joinToString("\n") { it.first },
                null, res.costUsd, io.acr.impl.ReviewKind.SPECS,
            )
            jobs.finish(jobSpecs, JobState.DONE)
            log(
                implId, jobSpecs,
                "✓ Análisis terminado. Documento agregado: ${destino.name} · " +
                    "${salida.issues.size} cosa(s) encontradas" +
                    (if (abiertas > 0) ", $abiertas quedan como pregunta." else "."),
            )
            destino.absolutePath
        }.onFailure {
            running.remove("$implId#specs")
            jobs.finish(jobSpecs, JobState.FAILED, it.message)
            log(implId, jobSpecs, "✗ El análisis no pudo terminar: ${it.message.orEmpty().take(300)}")
        }
    }

    /**
     * Audita el plan contra los documentos: qué quedó afuera, qué sobra, qué se contradice.
     *
     * Planificar y verificar el plan son trabajos distintos, y el que planificó es mal juez: para
     * él el plan cubre todo, porque lo armó pensando eso. Esta pasada va de los documentos al plan
     * y no al revés, que es el único orden en el que se ve lo que falta —yendo del plan a los
     * documentos, lo que no está no aparece nunca, porque no hay tarea que lo mencione—.
     *
     * No cambia el plan. Deja el resultado escrito para poder usarlo como guía de la revisión, que
     * es una decisión aparte: replanificar tira las tareas y eso no puede pasar solo.
     */
    suspend fun auditPlan(repos: List<RepoRecord>, implId: String): Result<Int> {
        val impl = impls.get(implId) ?: return Result.failure(IllegalStateException("No existe."))
        val principal = repos.firstOrNull()
            ?: return Result.failure(IllegalStateException("La implementación no tiene repositorios."))
        val binario = ClaudeCli.resolveBinary(prefs.get(io.acr.AppContext.PREF_CLAUDE_BINARY))
            ?: return Result.failure(IllegalStateException("No encuentro el ejecutable de Claude Code."))
        val tareas = impls.tasks(implId)
        if (tareas.isEmpty()) return Result.failure(IllegalStateException("Todavía no hay plan que auditar."))

        val docs = loadSources(impl.sources)
        val idioma = prefs.get(io.acr.AppContext.PREF_LANGUAGE) ?: "español"
        val plan = buildString {
            impl.planSummary?.takeIf { it.isNotBlank() }?.let { appendLine(it).appendLine() }
            tareas.forEach { t ->
                appendLine("${t.seq}. ${t.title}")
                t.detail.takeIf { it.isNotBlank() }?.let { appendLine("   $it") }
                t.steps.forEach { p -> appendLine("   - ${p.title}") }
            }
        }
        log(implId, "Leyendo ${docs.size} documento(s):")
        docs.forEach { d -> log(implId, "  · ${d.name} (${d.content.length} caracteres)") }
        log(implId, "Auditando las ${tareas.size} tareas contra lo que piden los documentos…")

        return runCatching {
            val res = ClaudeCli.run(
                binary = binario,
                // Auditar contra los documentos exige ver el código como está ahora: en el taller
                // si lo hay, porque ahí está lo que las tareas escribieron.
                workDir = dondeMirar(implId, impl.useWorkspace, principal),
                prompt = ImplPrompt.auditPlan(docs, plan, describir(repos, implId), idioma),
                model = impl.planModel ?: PLAN_MODEL,
                allowedTools = listOf("Read", "Grep", "Glob", "Bash(git *)", "Bash(ls *)", "Bash(find *)"),
                disallowedTools = ImplPrompt.DENIED_TOOLS,
                jsonSchema = ImplPrompt.AUDIT_SCHEMA,
                kind = "audit",
                register = { running["$implId#audit"] = it },
                onEvent = { evt -> resumir(evt)?.let { log(implId, it) } },
            )
            running.remove("$implId#audit")
            if (!res.ok) error(res.stderr.ifBlank { "La auditoría no devolvió nada." })

            val salida = parseAudit(res.structured ?: res.text)
            impls.saveReview(
                implId, null, 1, salida.issues.size, salida.cubiertos,
                salida.resumen, salida.issues.joinToString("\n"),
                null, res.costUsd, io.acr.impl.ReviewKind.PLAN,
            )
            // Lo encontrado queda como guía de revisión: es exactamente lo que hay que corregir, y
            // volver a escribirlo a mano para replanificar sería copiar lo que la app ya sabe.
            if (salida.issues.isNotEmpty()) {
                impls.saveReviewGuidance(
                    implId,
                    "La auditoría del plan contra los documentos encontró esto. Corregilo:\n" +
                        salida.issues.joinToString("\n") { "- $it" },
                )
            }
            log(
                implId,
                if (salida.issues.isEmpty()) "El plan cubre los documentos."
                else "${salida.issues.size} cosa(s) para corregir en el plan.",
            )
            salida.issues.size
        }.onFailure {
            running.remove("$implId#audit")
            log(implId, "Error: ${it.message.orEmpty().take(300)}")
        }
    }

    /**
     * Nombre, rol y ruta de cada repositorio, como los espera el prompt.
     *
     * La ruta es la del taller cuando existe, no la del clon. Importa más de lo que parece: quien
     * planifica, audita o analiza tiene que mirar **el código como está ahora**, con lo que las
     * tareas ya escribieron. Mirando el clon, una replanificación no ve nada de lo hecho y vuelve a
     * proponer trabajo que ya está.
     */
    private fun describir(repos: List<RepoRecord>, implId: String): List<Triple<String, String, String>> {
        val cfg = impls.reposOf(implId)
        val usa = impls.get(implId)?.useWorkspace == true
        return repos.map { r ->
            Triple(
                r.name,
                (cfg.firstOrNull { it.repoId == r.id }?.role ?: RepoRole.OTHER).name,
                dondeMirar(implId, usa, r).absolutePath,
            )
        }
    }

    /** Dónde está hoy el código de un repositorio para esta implementación. */
    private fun dondeMirar(implId: String, usaWorkspace: Boolean, r: RepoRecord): File =
        workspaces?.dirFor(implId, usaWorkspace, r.name, r.localPath) ?: File(r.localPath)

    /**
     * Dónde dejar el documento de aclaraciones.
     *
     * Al lado de las specs: es un documento del proyecto y tiene que poder leerse, versionarse y
     * discutirse como los otros. Si esa carpeta no existe o no se puede escribir, va al directorio
     * de datos de la app antes que perderse.
     */
    private fun destinoDoc(sources: List<String>, titulo: String): File {
        val base = sources.map { File(it) }
            .firstOrNull { it.exists() }
            ?.let { if (it.isDirectory) it else it.parentFile }
        val nombre = "00-aclaraciones-" +
            titulo.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "impl" } +
            ".md"
        val destino = base?.let { File(it, nombre) }
        return if (destino != null && (base.canWrite())) {
            destino
        } else {
            File(System.getProperty("user.home"), ".acr/docs/$nombre")
        }
    }

    private data class SpecsOut(
        val resumen: String,
        val documento: String,
        /** Cada cosa encontrada y si el documento nuevo la contesta. */
        val issues: List<Pair<String, Boolean>>,
    )

    private fun parseSpecs(raw: String): SpecsOut {
        val limpio = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = runCatching {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                .parseToJsonElement(limpio).jsonObject
        }.getOrNull() ?: return SpecsOut(raw.take(500), raw, emptyList())
        fun texto(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
        val items = (obj["issues"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return SpecsOut(
            resumen = texto(obj, "summary"),
            documento = texto(obj, "document"),
            issues = items.map { o ->
                val resuelto = o["resolved"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                val donde = texto(o, "where").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                ("[" + texto(o, "kind") + "]" + donde + " " + texto(o, "what") +
                    (if (resuelto) "" else "  → queda como pregunta")) to resuelto
            },
        )
    }

    private data class AuditOut(val resumen: String, val cubiertos: Int, val issues: List<String>)

    private fun parseAudit(raw: String): AuditOut {
        val limpio = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = runCatching {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                .parseToJsonElement(limpio).jsonObject
        }.getOrNull() ?: return AuditOut(raw.take(1_000), 0, emptyList())
        fun texto(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
        val items = (obj["issues"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return AuditOut(
            resumen = texto(obj, "summary"),
            cubiertos = obj["covered"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            issues = items.map { o ->
                val tarea = texto(o, "task").takeIf { it.isNotBlank() }?.let { " (tarea $it)" }.orEmpty()
                val req = texto(o, "requirement").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                val arreglo = texto(o, "fix").takeIf { it.isNotBlank() }?.let { " → $it" }.orEmpty()
                "[" + texto(o, "kind") + "]$tarea$req " + texto(o, "what") + arreglo
            },
        )
    }

    /**
     * Pasadas de revisión sobre el código ya escrito.
     *
     * Implementar y revisar son trabajos distintos, y el modelo que acaba de escribir algo es el
     * peor juez de ese algo: ya decidió que estaba bien. Una pasada aparte, mirando el diff con
     * otra intención —romperlo, no terminarlo— encuentra lo que la primera no podía ver.
     *
     * Cuántas pasadas no es un número fijo sino un rango: se corre el mínimo siempre y se sigue
     * mientras la anterior haya encontrado algo, hasta el máximo. Cinco pasadas sobre código limpio
     * son cinco corridas pagas para que digan "no encontré nada"; dos sobre código con problemas se
     * quedan cortas. Que el modelo pueda contestar "no encontré nada" es lo que hace que el rango
     * funcione, y por eso el prompt lo dice explícitamente.
     *
     * Devuelve lo que costó.
     */
    private suspend fun audit(
        implId: String,
        repo: RepoRecord,
        /** Dónde está el árbol a revisar. */
        workDir: File,
        docs: List<SourceDoc>,
        binario: String,
        modelo: String,
        idioma: String,
        min: Int,
        max: Int,
        /** La tarea que se acaba de hacer, cuando la revisión es por tarea. Null al final. */
        tarea: ImplTask? = null,
        baseRef: String,
    ): Double {
        var costo = 0.0
        val previos = mutableListOf<String>()
        var pasada = 0
        while (pasada < max) {
            if (implId in cancelled) break
            pasada++
            val alcance = tarea?.let {
                "Lo que cambió en la última tarea: `git show --stat HEAD` y `git show HEAD`. " +
                    "La tarea era: ${it.title}"
            }
            val etiqueta = tarea?.let { "${it.seq}." }.orEmpty()
            log(implId, "🔍 $etiqueta revisión, pasada $pasada de hasta $max…")

            // Con el número de tarea adentro: dos tareas que terminan a la vez lanzan su revisión
            // a la vez, y con una clave compartida la segunda pisaba a la primera — cancelar
            // mataba un solo proceso y el otro seguía escribiendo.
            val clave = "$implId#rev${tarea?.id.orEmpty()}$pasada"
            val res = runCatching {
                ClaudeCli.run(
                    binary = binario,
                    workDir = workDir,
                    prompt = ImplPrompt.review(
                        pasada, max, "$baseRef..HEAD", docs, alcance, previos.toList(), idioma,
                    ),
                    model = modelo,
                    allowedTools = ImplPrompt.WRITE_TOOLS,
                    disallowedTools = ImplPrompt.DENIED_TOOLS,
                    jsonSchema = ImplPrompt.REVIEW_SCHEMA,
                    kind = "code-review",
                    register = { running[clave] = it },
                    onEvent = { evt -> resumir(evt)?.let { log(implId, it) } },
                )
            }.getOrNull()
            running.remove(clave)
            if (res == null || !res.ok) {
                // Una revisión que no corre no invalida lo implementado: se anota y se sigue. Dar
                // por fallada la implementación entera porque una pasada de control se cayó sería
                // tirar el trabajo bueno por el control.
                log(implId, "  la pasada $pasada no pudo correr; sigo.")
                break
            }
            costo += res.costUsd ?: 0.0

            val salida = parseReview(res.structured ?: res.text)
            val sha = if (Git.isDirty(workDir)) {
                Git.commitAll(
                    workDir,
                    "revisión $pasada: ${salida.arreglados} arreglo(s)\n\n${salida.resumen.take(1_500)}",
                )
            } else {
                null
            }
            impls.saveReview(
                implId, tarea?.id, pasada, salida.hallazgos.size, salida.arreglados,
                salida.resumen, salida.detalle, sha, res.costUsd,
            )
            previos += salida.hallazgos.map { it.take(160) }
            log(
                implId,
                if (salida.hallazgos.isEmpty()) "  pasada $pasada: nada."
                else "  pasada $pasada: ${salida.hallazgos.size} hallazgo(s), ${salida.arreglados} arreglado(s)" +
                    sha?.let { " · ${it.take(7)}" }.orEmpty(),
            )

            // El mínimo se corre siempre; a partir de ahí sólo se sigue si la anterior encontró
            // algo. Una pasada limpia después del mínimo es la señal de que no hay más para
            // sacar, y seguir buscando invita a inventar hallazgos para justificar la corrida.
            if (pasada >= min && salida.hallazgos.isEmpty()) break
        }
        return costo
    }

    /** Lo que devolvió una pasada de revisión. */
    private data class RevisionOut(
        val resumen: String,
        val detalle: String,
        val hallazgos: List<String>,
        val arreglados: Int,
    )

    private fun parseReview(raw: String): RevisionOut {
        val limpio = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = runCatching {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                .parseToJsonElement(limpio).jsonObject
        }.getOrNull() ?: return RevisionOut(raw.take(2_000), raw.take(8_000), emptyList(), 0)

        val items = (obj["findings"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun texto(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
        val lineas = items.map { o ->
            val tipo = texto(o, "kind").ifBlank { "?" }
            val sev = texto(o, "severity").ifBlank { "?" }
            val arch = texto(o, "file").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            val hecho = o["fixed"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val porQueNo = texto(o, "why_not").takeIf { !hecho && it.isNotBlank() }
                ?.let { " — sin arreglar: $it" }.orEmpty()
            "[$sev/$tipo]$arch ${texto(o, "what")}$porQueNo"
        }
        return RevisionOut(
            resumen = obj["summary"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { raw.take(1_000) },
            detalle = lineas.joinToString("\n"),
            hallazgos = lineas,
            arreglados = items.count {
                it["fixed"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
            },
        )
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

    /** Lo que el plan necesita y nadie declaró, tal como lo dedujo el modelo. */
    private fun parseMissing(obj: JsonObject): List<MissingRepo> =
        (obj["missing_repos"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .mapNotNull { o ->
                val nombre = o["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                MissingRepo(
                    name = nombre,
                    path = o["path"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() },
                    evidence = o["evidence"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    neededFor = o["needed_for"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
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
