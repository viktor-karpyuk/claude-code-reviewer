package io.acr.data

import com.github.f4b6a3.ulid.UlidCreator
import io.acr.impl.ContextEntry
import io.acr.impl.ContextKind
import io.acr.impl.Job
import io.acr.impl.JobKind
import io.acr.impl.JobState
import java.time.Instant

/**
 * Los jobs y el contexto de las tareas.
 *
 * Es la parte del sistema que sobrevive a que la app se muera. Todo lo que hay acá está pensado
 * para contestar dos preguntas después de un corte: **qué había en marcha** y **desde dónde se
 * puede seguir**.
 */
class JobRepository(private val store: Store) {

    // --- jobs ---------------------------------------------------------------------------

    fun start(
        kind: JobKind,
        implId: String,
        taskId: String? = null,
        parentId: String? = null,
        workDir: String? = null,
        attempt: Int = 1,
    ): String {
        val id = UlidCreator.getUlid().toString()
        val ahora = Instant.now().toString()
        store.stmt(
            """INSERT INTO job(id, kind, impl_id, task_id, parent_id, state, attempt, work_dir,
                     created_at, started_at, heartbeat_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
        ) { ps ->
            ps.setString(1, id)
            ps.setString(2, kind.name)
            ps.setString(3, implId)
            ps.setString(4, taskId)
            ps.setString(5, parentId)
            ps.setString(6, JobState.RUNNING.name)
            ps.setInt(7, attempt)
            ps.setString(8, workDir)
            ps.setString(9, ahora)
            ps.setString(10, ahora)
            ps.setString(11, ahora)
            ps.executeUpdate()
        }
        return id
    }

    /**
     * El latido.
     *
     * Es lo único que distingue un job vivo de un cadáver. Sin él, después de un corte todo lo que
     * figura corriendo es igualmente sospechoso y no hay forma de decidir qué retomar sin
     * arriesgarse a duplicar trabajo que sigue en marcha.
     */
    fun beat(jobId: String) {
        store.stmt("UPDATE job SET heartbeat_at = ? WHERE id = ?") { ps ->
            ps.setString(1, Instant.now().toString())
            ps.setString(2, jobId)
            ps.executeUpdate()
        }
    }

    /** Anota la sesión del CLI apenas se conoce: es el pasaporte para retomar. */
    fun attachSession(jobId: String, sessionId: String?, pid: Long?) {
        store.stmt("UPDATE job SET session_id = COALESCE(?, session_id), pid = ? WHERE id = ?") { ps ->
            ps.setString(1, sessionId)
            if (pid == null) ps.setNull(2, java.sql.Types.INTEGER) else ps.setLong(2, pid)
            ps.setString(3, jobId)
            ps.executeUpdate()
        }
    }

    fun finish(jobId: String, state: JobState, error: String? = null) {
        store.stmt("UPDATE job SET state = ?, finished_at = ?, error = ? WHERE id = ?") { ps ->
            ps.setString(1, state.name)
            ps.setString(2, Instant.now().toString())
            ps.setString(3, error?.take(2_000))
            ps.setString(4, jobId)
            ps.executeUpdate()
        }
    }

    fun setState(jobId: String, state: JobState) {
        store.stmt("UPDATE job SET state = ? WHERE id = ?") { ps ->
            ps.setString(1, state.name)
            ps.setString(2, jobId)
            ps.executeUpdate()
        }
    }

    fun jobsOf(implId: String): List<Job> = query("WHERE impl_id = ? ORDER BY created_at") {
        it.setString(1, implId)
    }

    /** El último job de una tarea. Es de donde sale la sesión que se puede reanudar. */
    fun lastOf(taskId: String): Job? =
        query("WHERE task_id = ? ORDER BY created_at DESC") { it.setString(1, taskId) }.firstOrNull()

    /** Todo lo que quedó marcado como corriendo, de cualquier implementación. */
    fun running(): List<Job> = query("WHERE state = ?") { it.setString(1, JobState.RUNNING.name) }

    /**
     * Cierra como interrumpido todo lo que quedó corriendo sin latir, y devuelve qué cerró.
     *
     * Corre al arrancar la app. Interrumpido y no fallido: fallar es que el trabajo se intentó y
     * salió mal —hay algo para leer y decidir—; interrumpirse es que nadie lo terminó. Mezclarlos
     * haría buscar un error que no existe y, peor, haría descartar trabajo que estaba bien
     * encaminado.
     */
    fun sweepInterrupted(): List<Job> {
        val muertos = running().filter { it.stale() }
        muertos.forEach { j ->
            finish(j.id, JobState.INTERRUPTED, "La app se cerró mientras esto corría.")
            j.taskId?.let {
                add(it, j.id, ContextKind.INTERRUPT, "El intento ${j.attempt} se cortó sin terminar.")
            }
        }
        return muertos
    }

    private fun query(tail: String, bind: (java.sql.PreparedStatement) -> Unit): List<Job> =
        store.stmt(
            """SELECT id, kind, impl_id, task_id, parent_id, state, attempt, session_id, pid,
                      work_dir, created_at, started_at, heartbeat_at, finished_at, error
                 FROM job $tail""",
        ) { ps ->
            bind(ps)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Job(
                                id = rs.getString(1),
                                kind = runCatching { JobKind.valueOf(rs.getString(2)) }
                                    .getOrDefault(JobKind.TASK),
                                implId = rs.getString(3),
                                taskId = rs.getString(4),
                                parentId = rs.getString(5),
                                state = JobState.fromApi(rs.getString(6)),
                                attempt = rs.getInt(7),
                                sessionId = rs.getString(8),
                                pid = rs.getObject(9)?.let { rs.getLong(9) },
                                workDir = rs.getString(10),
                                createdAt = rs.getString(11).orEmpty(),
                                startedAt = rs.getString(12),
                                heartbeatAt = rs.getString(13),
                                finishedAt = rs.getString(14),
                                error = rs.getString(15),
                            ),
                        )
                    }
                }
            }
        }

    // --- contexto -----------------------------------------------------------------------

    /**
     * Agrega un hecho al contexto de una tarea.
     *
     * Sólo agrega, nunca reescribe. Un blob mutable puede quedar escrito a medias cuando el proceso
     * muere y no hay forma de distinguir uno truncado de uno real; una fila se escribe entera o no
     * se escribe, y lo peor que se pierde es la última.
     *
     * Los archivos no se repiten: una tarea que edita el mismo archivo veinte veces tiene que
     * aparecer una vez en el contexto, o la lista deja de ser legible justo cuando más hace falta.
     */
    fun add(taskId: String, jobId: String?, kind: ContextKind, text: String) {
        val limpio = text.trim().take(600)
        if (limpio.isBlank()) return
        if (kind == ContextKind.FILE || kind == ContextKind.STEP) {
            val yaEsta = store.stmt(
                "SELECT 1 FROM task_context WHERE task_id = ? AND kind = ? AND text = ? LIMIT 1",
            ) { ps ->
                ps.setString(1, taskId)
                ps.setString(2, kind.name)
                ps.setString(3, limpio)
                ps.executeQuery().use { it.next() }
            }
            if (yaEsta) return
        }
        store.stmt(
            "INSERT INTO task_context(id, task_id, job_id, kind, text, at) VALUES (?,?,?,?,?,?)",
        ) { ps ->
            ps.setString(1, UlidCreator.getUlid().toString())
            ps.setString(2, taskId)
            ps.setString(3, jobId)
            ps.setString(4, kind.name)
            ps.setString(5, limpio)
            ps.setString(6, Instant.now().toString())
            ps.executeUpdate()
        }
    }

    fun contextOf(taskId: String, limit: Int = 200): List<ContextEntry> =
        store.stmt(
            """SELECT id, task_id, job_id, kind, text, at FROM task_context
                WHERE task_id = ? ORDER BY at LIMIT ?""",
        ) { ps ->
            ps.setString(1, taskId)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            ContextEntry(
                                id = rs.getString(1),
                                taskId = rs.getString(2),
                                jobId = rs.getString(3),
                                kind = ContextKind.fromApi(rs.getString(4)),
                                text = rs.getString(5).orEmpty(),
                                at = rs.getString(6).orEmpty(),
                            ),
                        )
                    }
                }
            }
        }

    /** Cuántas entradas tiene cada tarea de una implementación, para la pantalla. */
    fun contextCounts(implId: String): Map<String, Int> =
        store.stmt(
            """SELECT c.task_id, COUNT(*) FROM task_context c
                 JOIN impl_task t ON t.id = c.task_id
                WHERE t.impl_id = ? GROUP BY c.task_id""",
        ) { ps ->
            ps.setString(1, implId)
            ps.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), rs.getInt(2)) }
            }
        }

    /**
     * Borra el contexto de una tarea.
     *
     * Se usa cuando el plan se rehace: el contexto describe un trabajo que ya no es el que la tarea
     * pide, y arrastrarlo haría que el próximo intento crea que hay avance sobre algo distinto.
     */
    fun clearContext(taskId: String) {
        store.stmt("DELETE FROM task_context WHERE task_id = ?") { ps ->
            ps.setString(1, taskId)
            ps.executeUpdate()
        }
    }
}
