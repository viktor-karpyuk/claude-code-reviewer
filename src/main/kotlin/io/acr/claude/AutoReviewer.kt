package io.acr.claude

import io.acr.data.PrefsRepo
import io.acr.data.RepoRepository
import io.acr.data.ReviewRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Revisa sola los PRs nuevos de los repositorios marcados como automáticos.
 *
 * Deja la review lista y **nunca publica**: publicar sigue siendo un acto explícito del usuario.
 * Ese límite es deliberado — el valor de la app es que alguien decida qué se dice en el PR; lo
 * que se automatiza es el trabajo previo, no la firma.
 */
class AutoReviewer(
    private val repos: RepoRepository,
    private val reviews: ReviewRepository,
    private val prefs: PrefsRepo,
    private val engine: ReviewEngine,
    private val notifier: io.acr.notify.Notifier,
    private val replies: io.acr.data.ReplyRepository,
) {

    /**
     * Procesa las respuestas que dejó el desarrollador, según el modo de cada repositorio.
     *
     * DRAFT prepara y avisa; AUTO además publica. La diferencia importa: en AUTO el texto queda
     * público sin que nadie lo lea antes, así que es opt-in por repositorio y nunca el default.
     */
    private suspend fun processReplies(repo: io.acr.forge.RepoRecord): Int {
        if (repo.replyMode == io.acr.forge.ReplyMode.OFF) return 0
        val pending = replies.forPr2(repo.id).filter {
            it.status == io.acr.data.ReplyStatus.PENDING && it.body.isNullOrBlank()
        }
        if (pending.isEmpty()) return 0

        var done = 0
        val prs = runCatching { io.acr.forge.Forges.of(repo.provider).listPullRequests(repo) }
            .getOrDefault(emptyList())
        for (d in pending.take(maxPerCycle())) {
            val pr = prs.firstOrNull { it.id == d.prId } ?: continue
            val drafted = engine.draftReply(repo, pr, d).getOrNull() ?: continue
            done++
            if (repo.replyMode == io.acr.forge.ReplyMode.AUTO) {
                val fresh = replies.get(d.id) ?: continue
                engine.publishReply(repo, d.prId, fresh, drafted)
                    .onSuccess {
                        notifier.notify(
                            "Respuesta publicada · ${repo.name} #${d.prId}",
                            "Le contesté a ${d.theirAuthor} automáticamente.",
                        )
                    }
                    .onFailure {
                        notifier.notify(
                            "No pude publicar la respuesta · ${repo.name} #${d.prId}",
                            it.message?.take(120) ?: "",
                        )
                    }
            } else {
                notifier.notify(
                    "Respuesta lista para revisar · ${repo.name} #${d.prId}",
                    "${d.theirAuthor} respondió; preparé una contestación esperando tu confirmación.",
                )
            }
        }
        return done
    }

    data class Status(
        val running: Boolean = false,
        val lastRunAt: String? = null,
        val lastMessage: String? = null,
        val reviewedTotal: Int = 0,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    /** Un solo bucle por vida de la app: dos bucles serían dos veces el gasto. */
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Un ciclo a la vez. El bucle periódico y el botón "Buscar ahora" pueden dispararse a la vez,
     * y dos ciclos concurrentes revisarían el mismo PR dos veces: `existsForHead` no alcanza,
     * porque ninguno de los dos habría terminado de guardar cuando el otro consulta.
     */
    private val cycleLock = kotlinx.coroutines.sync.Mutex()

    fun enabled(): Boolean = prefs.get(PREF_ENABLED) != "false"
    fun intervalMinutes(): Long = prefs.get(PREF_INTERVAL)?.toLongOrNull()?.coerceAtLeast(1) ?: 10
    fun maxPerCycle(): Int = prefs.get(PREF_MAX)?.toIntOrNull()?.coerceAtLeast(1) ?: 3

    /** Arranca el bucle. Idempotente por scope: se llama una vez al iniciar la app. */
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            // Un respiro inicial para no pelear con la carga de la primera pantalla.
            delay(15_000)
            while (isActive) {
                if (enabled()) runCatching { runOnce() }
                delay(intervalMinutes() * 60_000)
            }
        }
    }

    /**
     * Un ciclo: busca PRs sin review para su commit actual y los revisa, hasta [maxPerCycle].
     * Secuencial a propósito: varias reviews en paralelo multiplican el gasto sin que nadie lo
     * esté mirando.
     */
    suspend fun runOnce(): Int {
        // tryLock y no lock: si ya hay un ciclo corriendo se descarta este pedido en vez de
        // encolarlo, que es lo correcto para un barrido periódico.
        if (!cycleLock.tryLock()) return 0
        _status.update { it.copy(running = true) }
        var done = 0
        var crashed: String? = null
        val notes = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        try {
            val targets = repos.list().filter { it.autoReview }
            if (targets.isEmpty()) {
                notes += "ningún repositorio en automático"
                return 0
            }
            // Las respuestas se procesan en TODOS los repos, no sólo en los de review automática:
            // que el hilo siga vivo no depende de cómo se originó la review.
            repos.list().forEach { r ->
                runCatching { processReplies(r) }
                    .onSuccess { if (it > 0) notes += "${r.name}: $it respuesta(s) procesada(s)" }
            }

            for (repo in targets) {
                if (done >= maxPerCycle()) break
                val listed = runCatching { io.acr.forge.Forges.of(repo.provider).listPullRequests(repo) }
                if (listed.isFailure) {
                    notes += "${repo.name}: no pude listar PRs (${listed.exceptionOrNull()?.message?.take(60)})"
                    continue
                }
                val prs = listed.getOrDefault(emptyList())
                for (pr in prs) {
                    if (done >= maxPerCycle()) {
                        notes += "corte por límite de $done por ciclo"
                        break
                    }
                    if (pr.headSha.isBlank()) continue
                    // Las reglas sólo aplican al barrido: pedir una review a mano siempre corre.
                    val why = repo.skipRules.skipReason(pr)
                    if (why != null) {
                        skipped += "${repo.name} #${pr.id}: $why"
                        continue
                    }
                    // Idempotencia por commit: si ya hay una review de ese head —aunque haya
                    // fallado— no se repite. Reintentar es decisión del usuario.
                    if (reviews.existsForHead(repo.id, pr.id, pr.headSha)) continue
                    if (engine.isRunning(pr.id)) continue

                    // Se cuenta el INTENTO, no el éxito: una review que falla después de correr
                    // el CLI ya costó plata, y contar sólo éxitos dejaba el tope sin efecto
                    // justo cuando algo anda mal y más conviene frenar.
                    done++
                    when (val out = engine.review(repo, pr, auto = true)) {
                        is ReviewOutcome.Ok -> {
                            notes += "${repo.name} #${pr.id}: lista para publicar"
                            // El aviso es el punto de la revisión automática: si no, hay que
                            // acordarse de abrir la app para enterarse.
                            notifier.notify(
                                "Review lista · ${repo.name} #${pr.id}",
                                pr.title,
                            )
                        }
                        is ReviewOutcome.Error -> notes += "${repo.name} #${pr.id}: ${out.message.take(60)}"
                    }
                }
            }
        } catch (e: Throwable) {
            // Sin esto, una excepción antes de la primera nota se mostraba como "sin PRs nuevos":
            // un fallo permanente parecía un ciclo normal y vacío.
            crashed = e.message ?: e::class.java.simpleName
            throw e
        } finally {
            cycleLock.unlock()
            _status.update {
                it.copy(
                    running = false,
                    lastRunAt = Instant.now().toString(),
                    lastMessage = crashed?.let { "error: $it" }
                        ?: buildList {
                            addAll(notes.take(3))
                            if (skipped.isNotEmpty()) add("${skipped.size} salteados (${skipped.first()})")
                        }.joinToString(" · ").ifBlank { "sin PRs nuevos" },
                    reviewedTotal = it.reviewedTotal + done,
                )
            }
        }
        return done
    }

    companion object {
        const val PREF_ENABLED = "auto.enabled"
        const val PREF_INTERVAL = "auto.interval.minutes"
        const val PREF_MAX = "auto.max.per.cycle"
    }
}
