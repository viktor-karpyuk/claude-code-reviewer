package io.acr.impl

import java.time.Duration
import java.time.Instant

/**
 * Qué clase de trabajo es un job.
 *
 * `IMPL` es el padre —la implementación entera— y los demás son sus hijos. Tenerlos en la misma
 * tabla y no en cinco permite la pregunta que de verdad se hace después de un corte: "¿qué había en
 * marcha?", sin tener que unir cinco lugares para contestarla.
 */
enum class JobKind { IMPL, TASK, PLAN, REVIEW, SPECS, AUDIT }

/**
 * En qué estado está un job.
 *
 * `INTERRUPTED` es distinto de `FAILED` y esa distinción es el corazón de todo esto: fallar es que
 * el trabajo se intentó y salió mal —hay algo para leer y decidir—; interrumpirse es que nadie lo
 * terminó porque la app se cayó o alguien la cerró. Mezclarlos haría buscar un error que no existe,
 * y peor: haría descartar trabajo que estaba bien encaminado.
 */
enum class JobState {
    QUEUED,
    RUNNING,
    PAUSED,
    DONE,
    FAILED,
    INTERRUPTED,
    CANCELLED,
    ;

    val active: Boolean get() = this == RUNNING || this == QUEUED

    companion object {
        fun fromApi(s: String?): JobState =
            entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: QUEUED
    }
}

/**
 * Una corrida concreta: un proceso, una sesión del CLI, un intento.
 *
 * Una tarea es una unidad del plan; un job es una unidad de ejecución. Hacen falta las dos porque
 * contestan preguntas distintas, y con el estado de la tarea sola no se puede contestar la única
 * que importa después de un corte: **lo que figura corriendo, ¿está vivo o es un cadáver?** La
 * tarea quedó en RUNNING en los dos casos. El job late, y un RUNNING sin latido reciente está
 * muerto — eso sí se puede afirmar sin adivinar.
 */
data class Job(
    val id: String,
    val kind: JobKind,
    val implId: String,
    val taskId: String?,
    val parentId: String?,
    val state: JobState,
    val attempt: Int,
    /**
     * La sesión del CLI, cuando la hubo.
     *
     * Es lo que permite retomar de verdad en vez de volver a empezar con más información: las
     * sesiones de Claude Code viven en disco, así que reanudar una le devuelve al modelo todo lo
     * que ya había razonado, no un resumen de lo que hizo.
     */
    val sessionId: String?,
    val pid: Long?,
    val workDir: String?,
    val createdAt: String,
    val startedAt: String?,
    val heartbeatAt: String?,
    val finishedAt: String?,
    val error: String?,
) {
    /**
     * ¿Este job dice estar corriendo pero hace rato que no late?
     *
     * El umbral es holgado a propósito. El latido es cada veinte segundos y una máquina que
     * hiberna, un disco lento o un GC largo pueden saltearse uno: dar por muerto un job vivo es
     * peor que tardar un minuto más en darlo por muerto, porque significaría matar trabajo en curso
     * y duplicar lo que ya se estaba haciendo.
     */
    fun stale(now: Instant = Instant.now(), threshold: Duration = STALE): Boolean {
        if (state != JobState.RUNNING) return false
        val latido = (heartbeatAt ?: startedAt)?.let {
            runCatching { Instant.parse(it) }.getOrNull()
        } ?: return true
        return Duration.between(latido, now) > threshold
    }

    companion object {
        val STALE: Duration = Duration.ofSeconds(120)
    }
}

/** De qué habla una entrada del contexto. */
enum class ContextKind {
    /** Un archivo que la tarea escribió o editó. */
    FILE,

    /** Un comando que corrió. */
    CMD,

    /** Algo que el modelo dijo de sí mismo: su resumen, o una decisión que tomó. */
    NOTE,

    /** Un paso que quedó cerrado. */
    STEP,

    /** Una pregunta contestada por una persona. */
    DECISION,

    /** Un intento nuevo sobre la misma tarea. */
    RESUME,

    /** El trabajo se cortó sin terminar. */
    INTERRUPT,
    ;

    companion object {
        fun fromApi(s: String?): ContextKind =
            entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: NOTE
    }
}

/** Un hecho observado durante la vida de una tarea. */
data class ContextEntry(
    val id: String,
    val taskId: String,
    val jobId: String?,
    val kind: ContextKind,
    val text: String,
    val at: String,
)

/**
 * El contexto de una tarea, listo para meter en un prompt.
 *
 * No es todo lo que pasó: es lo que le sirve al que va a retomar. Los archivos tocados van
 * completos y sin repetir —es la pregunta más importante, "¿qué hay ya modificado?"— y de los
 * comandos van los últimos, porque lo que interesa de un corte es qué se estaba intentando cuando
 * ocurrió, no cómo empezó.
 *
 * Devuelve null cuando no hay nada que contar: un bloque de contexto vacío en un prompt ocupa lugar
 * y le sugiere al modelo que hubo un intento previo que no dejó nada, que es distinto de no haber
 * habido ninguno.
 */
fun renderContext(entries: List<ContextEntry>, maxCommands: Int = 8): String? {
    if (entries.isEmpty()) return null
    val archivos = entries.filter { it.kind == ContextKind.FILE }.map { it.text }.distinct()
    val comandos = entries.filter { it.kind == ContextKind.CMD }.map { it.text }.takeLast(maxCommands)
    val notas = entries.filter { it.kind == ContextKind.NOTE }.map { it.text }
    val pasos = entries.filter { it.kind == ContextKind.STEP }.map { it.text }
    val cortes = entries.filter { it.kind == ContextKind.INTERRUPT || it.kind == ContextKind.RESUME }

    val partes = buildList {
        if (archivos.isNotEmpty()) {
            add("Archivos que ya tocaste:\n" + archivos.joinToString("\n") { "  - $it" })
        }
        if (pasos.isNotEmpty()) {
            add("Pasos ya cerrados:\n" + pasos.joinToString("\n") { "  - $it" })
        }
        if (comandos.isNotEmpty()) {
            add("Últimos comandos que corriste:\n" + comandos.joinToString("\n") { "  - $it" })
        }
        if (notas.isNotEmpty()) {
            add("Lo que fuiste dejando anotado:\n" + notas.joinToString("\n") { "  - $it" })
        }
        if (cortes.isNotEmpty()) {
            add("Historia de esta tarea:\n" + cortes.joinToString("\n") { "  - ${it.text}" })
        }
    }
    return partes.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}
