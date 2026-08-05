package io.acr.claude

import io.acr.data.PrefsRepo
import io.acr.data.ReviewRecord
import io.acr.data.ReviewRepository
import io.acr.data.ReviewStatus
import io.acr.forge.PullRequest
import io.acr.forge.RepoRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Progreso de una review en curso.
 *
 * Lleva la identidad del repo y del PR además del progreso: el panel de un PR ya sabe de cuál
 * habla, pero el dashboard muestra varias a la vez y necesita etiquetarlas.
 */
data class RunProgress(
    val prId: Long,
    val lines: List<String> = emptyList(),
    val sessionId: String? = null,
    val repoId: String = "",
    val repoName: String = "",
    val prTitle: String = "",
    val depth: String = "",
    val model: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val auto: Boolean = false,
)

sealed interface ReviewOutcome {
    data class Ok(val record: ReviewRecord) : ReviewOutcome
    data class Error(val message: String) : ReviewOutcome
}

/**
 * Orchestrates one PR review: refresh the local clone, run Claude Code headless in it, persist
 * the result. Several reviews can run at once; each is an independent subprocess and session.
 */
class ReviewEngine(
    private val reviews: ReviewRepository,
    private val publications: io.acr.data.PublicationRepository,
    private val comments: io.acr.data.PrCommentRepository,
    private val findings: io.acr.data.FindingRepository,
    private val replies: io.acr.data.ReplyRepository,
    private val prefs: PrefsRepo,
    private val notifier: io.acr.notify.Notifier? = null,
) {

    /**
     * Detecta respuestas a comentarios nuestros y las registra como pendientes de contestar.
     * "Nuestro" es cualquier comentario cuyo id quedó guardado al publicar: la review general,
     * un hallazgo inline o una nota propia.
     */
    fun detectReplies(repoId: String, prId: Long): Int {
        val thread = comments.forPr(repoId, prId)
        if (thread.isEmpty()) return 0
        val ourIds = buildSet {
            publications.forPr(repoId, prId).forEach { it.commentId?.let(::add) }
            findings.forPr(repoId, prId).forEach { it.publishedId?.let(::add) }
            addAll(thread.filter { it.ours }.map { it.commentId })
        }
        if (ourIds.isEmpty()) return 0

        val byId = thread.associateBy { it.commentId }
        var registered = 0
        thread.forEach { c ->
            val parent = c.parentId ?: return@forEach
            if (parent !in ourIds) return@forEach
            if (c.ours) return@forEach // una respuesta nuestra no se contesta a sí misma
            val ours = byId[parent]
            val added = replies.registerIfNew(
                repoId = repoId,
                prId = prId,
                theirCommentId = c.commentId,
                theirAuthor = c.author,
                theirBody = c.body,
                ourCommentId = parent,
                ourBody = ours?.body,
                filePath = c.inlinePath ?: ours?.inlinePath,
                lineNo = c.inlineLine ?: ours?.inlineLine,
            )
            if (added) registered++
        }
        return registered
    }

    /** Analiza una respuesta y redacta la contestación. No publica nada. */
    suspend fun draftReply(
        repo: RepoRecord,
        pr: PullRequest,
        draft: io.acr.data.ReplyDraft,
    ): Result<String> {
        val workDir = File(repo.localPath)
        if (!Git.isRepo(workDir)) {
            return Result.failure(IllegalStateException("«${repo.localPath}» no es un working copy de git."))
        }
        val binary = ClaudeCli.resolveBinary(prefs.get(io.acr.AppContext.PREF_CLAUDE_BINARY))
            ?: return Result.failure(IllegalStateException("No encuentro el ejecutable de Claude Code."))

        val range = "origin/${pr.targetBranch}...origin/${pr.sourceBranch}"
        Git.fetch(workDir, pr.targetBranch, pr.sourceBranch)
        val prompt = ReviewPrompt.replyPrompt(
            language = prefs.get(io.acr.AppContext.PREF_LANGUAGE) ?: "español",
            prTitle = pr.title,
            range = range,
            ourComment = draft.ourBody,
            theirAuthor = draft.theirAuthor,
            theirBody = draft.theirBody,
            filePath = draft.filePath,
            lineNo = draft.lineNo,
        )
        return runCatching {
            val result = ClaudeCli.run(
                binary = binary,
                workDir = workDir,
                prompt = prompt,
                // Intermedia: hay que poder abrir el archivo para verificar lo que plantean.
                model = repo.defaultModel.ifBlank { ReviewDepth.INTERMEDIATE.defaultModel },
                allowedTools = ReviewDepth.INTERMEDIATE.allowedTools(),
                disallowedTools = ReviewPrompt.DISALLOWED_TOOLS,
            )
            if (!result.ok || result.text.isBlank()) {
                error(result.stderr.ifBlank { "Claude Code no devolvió una respuesta." })
            }
            replies.saveDraft(draft.id, result.text.trim(), result.costUsd)
            result.text.trim()
        }.onFailure { replies.fail(draft.id, it.message ?: it::class.java.simpleName) }
    }

    /** Publica la contestación colgada del comentario al que responde. */
    suspend fun publishReply(
        repo: RepoRecord,
        prId: Long,
        draft: io.acr.data.ReplyDraft,
        body: String,
    ): Result<String> = runCatching {
        val parent = draft.theirCommentId
        val posted = io.acr.forge.Forges.of(repo.provider).postReply(repo, prId, parent, body)
        replies.markPublished(draft.id, posted.id, posted.url)
        syncComments(repo, prId)
        posted.url
    }

    /** Publica un hallazgo como comentario inline, anclado a su archivo y línea. */
    suspend fun publishFinding(
        repo: RepoRecord,
        prId: Long,
        finding: io.acr.data.Finding,
        headSha: String,
    ): Result<String> = runCatching {
        val body = "**${finding.title}**\n\n${finding.body}"
        val posted = io.acr.forge.Forges.of(repo.provider)
            .postInlineComment(repo, prId, body, finding.filePath, finding.lineNo, headSha)
        findings.markPublished(finding.id, posted.id, posted.url)
        syncComments(repo, prId)
        posted.url
    }

    /**
     * Refreshes the stored copy of the PR thread. Best-effort: the review is still worth running
     * if the forge is unreachable, it just loses the "don't repeat what was said" context.
     */
    suspend fun syncComments(repo: RepoRecord, prId: Long): Result<Int> = runCatching {
        val fetched = io.acr.forge.Forges.of(repo.provider).listComments(repo, prId)
        val ours = publications.forPr(repo.id, prId).mapNotNull { it.commentId }.toSet()
        comments.sync(repo.id, prId, fetched, ours)
        val newReplies = detectReplies(repo.id, prId)
        if (newReplies > 0) {
            notifier?.notify(
                "Te respondieron · ${repo.name} #$prId",
                "$newReplies respuesta(s) nueva(s) a nuestros comentarios.",
            )
        }
        fetched.size
    }

    /**
     * Publishes a review and records the publication as its own event. The stored body is the
     * exact text sent to the forge, so the history says what was published, not what the draft
     * happens to say now.
     */
    suspend fun publish(repo: RepoRecord, prId: Long, reviewId: String, body: String): Result<String> =
        runCatching {
            val posted = io.acr.forge.Forges.of(repo.provider).postComment(repo, prId, body)
            publications.record(reviewId, repo.id, prId, posted.id, posted.url, body)
            reviews.markPublished(reviewId, posted.url)
            syncComments(repo, prId)
            posted.url
        }

    /** Publica una nota local como comentario inline y la marca publicada. */
    suspend fun publishNote(
        repo: RepoRecord,
        prId: Long,
        note: io.acr.data.LocalNote,
        headSha: String,
        notes: io.acr.data.LocalNoteRepository,
    ): Result<String> = runCatching {
        val posted = io.acr.forge.Forges.of(repo.provider)
            .postInlineComment(repo, prId, note.body, note.filePath, note.lineNo, headSha)
        notes.markPublished(note.id, posted.id, posted.url)
        syncComments(repo, prId)
        posted.url
    }

    private val _progress = MutableStateFlow<Map<Long, RunProgress>>(emptyMap())
    val progress: StateFlow<Map<Long, RunProgress>> = _progress

    private val running = ConcurrentHashMap<Long, Process>()

    /**
     * Cancelaciones pedidas antes de que exista el proceso. Entre que arranca `review()` y que
     * `ClaudeCli.run` registra el subproceso pasan el fetch, el planning y el sync de comentarios;
     * un cancel en esa ventana no encontraba nada que matar y se perdía en silencio.
     */
    private val cancelRequested = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /**
     * Reviews en vuelo, marcadas desde el instante en que arranca `review()`.
     *
     * `running` sólo se puebla cuando el subproceso ya existe, y hasta ahí pasan el fetch, el
     * planning y el sync de comentarios: bajo rate limiting eso es más de un minuto. En esa
     * ventana, dos disparos —el barrido automático y un click manual— veían "no hay nada
     * corriendo" y pagaban dos veces la misma review.
     */
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun key(repoId: String, prId: Long) = "$repoId:$prId"

    fun isRunning(prId: Long): Boolean = running.containsKey(prId)

    fun isInFlight(repoId: String, prId: Long): Boolean = inFlight.contains(key(repoId, prId))

    fun cancel(prId: Long) {
        cancelRequested.add(prId)
        running.remove(prId)?.destroy()
    }

    /** [depth] y [kind] en null significan automático: los infiere [ReviewPlanner] del diff. */
    suspend fun review(
        repo: RepoRecord,
        pr: PullRequest,
        depth: ReviewDepth? = repo.defaultDepth,
        kind: ProjectKind? = repo.projectKind,
        model: String = repo.defaultModel,
        auto: Boolean = false,
    ): ReviewOutcome {
        val claim = key(repo.id, pr.id)
        if (!inFlight.add(claim)) {
            return ReviewOutcome.Error("Ya hay una review en curso para el PR #${pr.id}.")
        }
        try {
        val workDir = File(repo.localPath)
        if (!Git.isRepo(workDir)) {
            return ReviewOutcome.Error(
                "[${repo.name} · ${repo.owner}/${repo.slug}] «${repo.localPath}» no es un working copy de git.",
            )
        }
        val binary = ClaudeCli.resolveBinary(prefs.get(io.acr.AppContext.PREF_CLAUDE_BINARY))
            ?: return ReviewOutcome.Error(
                "No encuentro el ejecutable de Claude Code. Configurá su ruta en Ajustes.",
            )

        emit(pr.id) {
            it.copy(
                lines = listOf("Actualizando el clon local…"),
                repoId = repo.id,
                repoName = repo.name,
                prTitle = pr.title,
                auto = auto,
            )
        }

        val fetch = Git.fetch(workDir, pr.targetBranch, pr.sourceBranch)
        if (!fetch.ok) {
            val msg = buildString {
                append("[${repo.name} · ${repo.owner}/${repo.slug}] git fetch falló en ${repo.localPath}\n\n")
                append(fetch.output.take(600))
                // Error muy frecuente desde que Atlassian dio de baja las app passwords: el clon
                // sigue con un origin HTTPS que ya no puede autenticar.
                if (fetch.output.contains("CHANGE-3222") || fetch.output.contains("App passwords")) {
                    append(
                        "\n\nEl remote de este clon usa HTTPS con app password, que Bitbucket dio " +
                            "de baja. Pasalo a SSH:\n" +
                            "  git -C ${repo.localPath} remote set-url origin " +
                            "git@bitbucket.org:${repo.owner}/${repo.slug}.git",
                    )
                }
            }
            // Se registra igual para que el fallo quede en el historial, con el perfil pedido.
            val id = reviews.start(
                repo.id, pr.id, pr.title, pr.headSha,
                depth ?: ReviewDepth.INTERMEDIATE, kind ?: ProjectKind.GENERIC, "", auto,
            )
            reviews.fail(id, msg)
            clear(pr.id)
            return ReviewOutcome.Error(msg)
        }

        // El plan necesita el diff, así que va después del fetch.
        val range = "origin/${pr.targetBranch}...origin/${pr.sourceBranch}"
        val plan = ReviewPlanner.plan(Git.numstat(workDir, range), depth, kind)
        val resolvedModel = model.ifBlank { plan.depth.defaultModel }
        emit(pr.id) {
            it.copy(
                lines = it.lines + "${plan.reason} · modelo $resolvedModel",
                depth = plan.depth.label,
                model = resolvedModel,
            )
        }

        val reviewId = reviews.start(
            repo.id, pr.id, pr.title, pr.headSha, plan.depth, plan.kind, resolvedModel, auto,
        )
        // Todo lo que sigue va bajo guarda: cualquier excepción no atrapada (una consulta a la
        // base, el parseo, el guardado de hallazgos) dejaba la review en RUNNING para siempre,
        // sin error visible y perdiendo el resultado de una corrida ya pagada.
        return try {

            emit(pr.id) { it.copy(lines = it.lines + "Trayendo el hilo de comentarios del PR…") }
            syncComments(repo, pr.id).onFailure {
                emit(pr.id) { p -> p.copy(lines = p.lines + "No pude leer el hilo (${it.message?.take(80)}); sigo sin ese contexto.") }
            }
            val existing = comments.forPr(repo.id, pr.id)
            if (existing.isNotEmpty()) {
                emit(pr.id) { it.copy(lines = it.lines + "${existing.size} comentarios previos: no se van a repetir.") }
            }

            val language = prefs.get(io.acr.AppContext.PREF_LANGUAGE) ?: "español"
            val prompt = ReviewPrompt.build(pr, language, plan.depth, plan.kind, existing)

            var proc: Process? = null
            val result = try {
                ClaudeCli.run(
                    binary = binary,
                    workDir = workDir,
                    prompt = prompt,
                    model = resolvedModel,
                    allowedTools = plan.depth.allowedTools(),
                    disallowedTools = ReviewPrompt.DISALLOWED_TOOLS,
                    jsonSchema = ReviewPrompt.SCHEMA,
                    register = { p ->
                        proc = p
                        running[pr.id] = p
                        // Si el cancel llegó antes de que el proceso existiera, se aplica ahora.
                        if (cancelRequested.remove(pr.id)) {
                            running.remove(pr.id, p)
                            p.destroy()
                        }
                    },
                    onEvent = { evt -> emit(pr.id) { cur -> cur.copy(lines = cur.lines + render(evt), sessionId = sessionOf(evt) ?: cur.sessionId) } },
                )
            } catch (e: Exception) {
                reviews.fail(reviewId, e.message ?: e::class.java.simpleName)
                clear(pr.id)
                return ReviewOutcome.Error(e.message ?: "El subproceso de Claude Code falló.")
            } finally {
                // remove(key, value): si otra corrida del mismo PR ya registró su proceso, no se lo borra.
                proc?.let { running.remove(pr.id, it) }
                cancelRequested.remove(pr.id)
            }

            clear(pr.id)

            if (!result.ok) {
                // A cancelled run kills the process, so an empty result here is expected rather than
                // an error worth showing as a failure.
                val cancelled = result.text.isBlank() && result.stderr.isBlank()
                val msg = when {
                    cancelled -> "Review cancelada."
                    result.stderr.isNotBlank() -> result.stderr.take(500)
                    else -> "Claude Code terminó sin devolver una review."
                }
                reviews.fail(reviewId, msg, if (cancelled) ReviewStatus.CANCELLED else ReviewStatus.FAILED)
                return ReviewOutcome.Error(msg)
            }

            // Un permiso denegado no rompe la corrida, pero la deja parcialmente ciega: si no se
            // avisa, una review que no pudo leer el diff se guarda como exitosa. Fue exactamente lo
            // que pasó cuando los patrones con espacios llegaban partidos al CLI.
            if (result.permissionDenials.isNotEmpty()) {
                emit(pr.id) { p ->
                    p.copy(lines = p.lines + "AVISO: se denegaron ${result.permissionDenials.size} herramienta(s): ${result.permissionDenials.distinct().joinToString(", ")}")
                }
            }

            val parsed = parseFindings(result.structured ?: result.text, repo.id, pr.id, reviewId)
            findings.replaceForReview(reviewId, repo.id, pr.id, parsed.second)
            val warning = if (result.permissionDenials.isEmpty()) "" else
                "\n\n> ⚠️ Esta review corrió con ${result.permissionDenials.size} herramienta(s) denegada(s) " +
                    "(${result.permissionDenials.distinct().joinToString(", ")}), así que puede estar incompleta."
            reviews.finish(
                reviewId,
                renderMarkdown(parsed.first, parsed.second) + warning,
                result.sessionId,
                result.costUsd,
            )
            return reviews.get(reviewId)?.let { ReviewOutcome.Ok(it) }
                ?: ReviewOutcome.Error("No pude releer la review recién guardada.")
        } catch (e: Throwable) {
            clear(pr.id)
            val msg = e.message ?: e::class.java.simpleName
            runCatching { reviews.fail(reviewId, msg) }
            ReviewOutcome.Error(msg)
        }
        } finally {
            inFlight.remove(claim)
        }
    }

    /**
     * El CLI valida contra el esquema, pero igual se parsea defensivamente: si algún día devuelve
     * el JSON envuelto en un bloque de código, no queremos perder la review entera.
     */
    private fun parseFindings(
        raw: String,
        repoId: String,
        prId: Long,
        reviewId: String,
    ): Pair<String, List<io.acr.data.Finding>> {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = runCatching {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                .parseToJsonElement(cleaned).jsonObject
        }.getOrNull() ?: return "" to emptyList()

        val summary = obj["summary"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val list = (obj["findings"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val file = o["file"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            io.acr.data.Finding(
                id = "", reviewId = reviewId, prId = prId,
                filePath = file,
                lineNo = o["line"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                severity = o["severity"]?.jsonPrimitive?.contentOrNull ?: "minor",
                title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                body = o["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                publishedId = null,
                publishedUrl = null,
            )
        }
        return summary to list
    }

    /**
     * Corrige o descarta anclas imposibles antes de guardar.
     *
     * El esquema garantiza los tipos, no que la ruta exista ni que la línea esté dentro del
     * archivo. Un ancla inventada publica un comentario en el lugar equivocado del PR —o en un
     * archivo que el PR no toca—, que es peor que no publicarlo. Se normalizan rutas absolutas,
     * se descartan archivos fuera del diff y se degrada a "archivo entero" una línea fuera de rango.
     */
    private suspend fun validateAnchors(
        list: List<io.acr.data.Finding>,
        workDir: File,
        range: String,
        sourceBranch: String,
    ): List<io.acr.data.Finding> {
        if (list.isEmpty()) return list
        val changed = Git.numstat(workDir, range).map { it.path }.toSet()
        if (changed.isEmpty()) return list
        val root = workDir.canonicalPath.trimEnd('/') + "/"
        val lineCounts = mutableMapOf<String, Int>()

        return list.mapNotNull { f ->
            val relative = f.filePath.removePrefix(root).removePrefix("./").trim()
            val path = changed.firstOrNull { it == relative }
                ?: changed.firstOrNull { it.endsWith("/$relative") }
                ?: return@mapNotNull null

            val line = f.lineNo
            val ok = if (line == null) true else {
                val total = lineCounts.getOrPut(path) { 0 }
                    .takeIf { it > 0 }
                    ?: Git.fileLineCount(workDir, sourceBranch, path).also { lineCounts[path] = it }
                total <= 0 || line in 1..total
            }
            f.copy(filePath = path, lineNo = if (ok) line else null)
        }
    }

    /** Markdown del comentario general, derivado de los mismos hallazgos que se anclan al código. */
    private fun renderMarkdown(summary: String, list: List<io.acr.data.Finding>): String {
        if (list.isEmpty()) {
            return "### Code review\n\n" +
                (summary.ifBlank { "Sin observaciones. Revisé bugs y cumplimiento de CLAUDE.md." })
        }
        val sb = StringBuilder("### Code review\n\n")
        if (summary.isNotBlank()) sb.append(summary).append("\n\n")
        sb.append("Encontré ${list.size} ").append(if (list.size == 1) "problema" else "problemas").append(":\n\n")
        list.forEachIndexed { i, f ->
            val anchor = f.filePath + (f.lineNo?.let { ":$it" } ?: "")
            sb.append("${i + 1}. **${f.title}** _(${f.severity})_\n\n")
            sb.append("`$anchor`\n\n")
            sb.append(f.body).append("\n\n")
        }
        return sb.toString().trimEnd()
    }

    private fun sessionOf(evt: ClaudeEvent): String? =
        (evt as? ClaudeEvent.Started)?.sessionId

    private fun render(evt: ClaudeEvent): String = when (evt) {
        is ClaudeEvent.Started -> "Sesión ${evt.sessionId.take(8)} · modelo ${evt.model}"
        is ClaudeEvent.Thinking -> evt.text
        is ClaudeEvent.ToolUse -> if (evt.detail.isBlank()) "· ${evt.tool}" else "· ${evt.tool}: ${evt.detail}"
        is ClaudeEvent.Failed -> "Error: ${evt.message}"
    }

    // update{} hace CAS con reintento. Con el read-modify-write anterior, dos reviews en paralelo
    // leían el mismo snapshot y la segunda escritura borraba la línea de progreso de la primera.
    private fun emit(prId: Long, mutate: (RunProgress) -> RunProgress) {
        _progress.update { cur ->
            cur + (prId to mutate(cur[prId] ?: RunProgress(prId)))
        }
    }

    private fun clear(prId: Long) {
        _progress.update { it - prId }
    }
}
