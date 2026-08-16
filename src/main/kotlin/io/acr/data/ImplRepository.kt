package io.acr.data

import com.github.f4b6a3.ulid.UlidCreator
import io.acr.impl.ImplStatus
import io.acr.impl.ImplTask
import io.acr.impl.Implementation
import io.acr.impl.TaskSize
import io.acr.impl.TaskStatus
import java.time.Instant

/** Implementaciones y sus tareas. */
class ImplRepository(private val store: Store) {

    /**
     * Crea una implementación sobre uno o varios repositorios.
     *
     * @param repos en orden; el primero queda como principal para las pantallas que muestran uno
     *   solo, pero el plan abarca todos.
     */
    fun create(
        repos: List<io.acr.impl.ImplRepo>,
        title: String,
        sources: List<String>,
        extra: String?,
    ): String {
        val id = UlidCreator.getUlid().toString()
        store.transaction { conn ->
            conn.prepareStatement(
                """INSERT INTO implementation(id, repo_id, title, sources, extra_prompt, status, created_at)
                   VALUES (?,?,?,?,?,?,?)""",
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, repos.first().repoId)
                ps.setString(3, title)
                ps.setString(4, sources.joinToString("\n"))
                ps.setString(5, extra)
                ps.setString(6, ImplStatus.DRAFT.name)
                ps.setString(7, Instant.now().toString())
                ps.executeUpdate()
            }
            repos.forEach { r ->
                conn.prepareStatement(
                    "INSERT OR REPLACE INTO impl_repo(impl_id, repo_id, role) VALUES (?,?,?)",
                ).use { ps ->
                    ps.setString(1, id); ps.setString(2, r.repoId); ps.setString(3, r.role.name)
                    ps.executeUpdate()
                }
            }
        }
        return id
    }

    /** Los repositorios de una implementación, con su rol. */
    fun reposOf(implId: String): List<io.acr.impl.ImplRepo> =
        store.stmt("SELECT repo_id, role FROM impl_repo WHERE impl_id = ?") { ps ->
            ps.setString(1, implId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(io.acr.impl.ImplRepo(rs.getString(1), io.acr.impl.RepoRole.fromApi(rs.getString(2))))
                    }
                }
            }
        }

    fun list(repoId: String? = null): List<Implementation> =
        query(
            if (repoId == null) "ORDER BY created_at DESC" else "WHERE repo_id = ? ORDER BY created_at DESC",
        ) { if (repoId != null) it.setString(1, repoId) }

    fun get(id: String): Implementation? = query("WHERE id = ?") { it.setString(1, id) }.firstOrNull()

    fun setStatus(id: String, status: ImplStatus, error: String? = null) {
        store.stmt("UPDATE implementation SET status = ?, error = ? WHERE id = ?") { ps ->
            ps.setString(1, status.name)
            ps.setString(2, error)
            ps.setString(3, id)
            ps.executeUpdate()
        }
    }

    /**
     * Guarda el plan y sus tareas de una sola vez.
     *
     * En una transacción porque un plan a medias es peor que ninguno: la implementación quedaría
     * marcada como planificada con la mitad de las tareas, y al ejecutar construiría algo
     * incompleto sin que nada lo delate.
     */
    fun savePlan(
        implId: String,
        summary: String,
        branch: String,
        baseBranch: String,
        planModel: String,
        tasks: List<ImplTask>,
    ) {
        store.transaction { conn ->
            conn.prepareStatement("DELETE FROM impl_task WHERE impl_id = ?").use { ps ->
                ps.setString(1, implId); ps.executeUpdate()
            }
            conn.prepareStatement(
                """UPDATE implementation SET plan_summary = ?, branch = ?, base_branch = ?,
                       plan_model = ?, status = ?, planned_at = ? WHERE id = ?""",
            ).use { ps ->
                ps.setString(1, summary)
                ps.setString(2, branch)
                ps.setString(3, baseBranch)
                ps.setString(4, planModel)
                ps.setString(5, ImplStatus.PLANNED.name)
                ps.setString(6, Instant.now().toString())
                ps.setString(7, implId)
                ps.executeUpdate()
            }
            tasks.forEach { t ->
                conn.prepareStatement(
                    """INSERT INTO impl_task(id, impl_id, seq, title, detail, depends_on, size,
                             estimate_min, status, repo_id)
                       VALUES (?,?,?,?,?,?,?,?,?,?)""",
                ).use { ps ->
                    ps.setString(1, UlidCreator.getUlid().toString())
                    ps.setString(2, implId)
                    ps.setInt(3, t.seq)
                    ps.setString(4, t.title)
                    ps.setString(5, t.detail)
                    ps.setString(6, t.dependsOn.joinToString(","))
                    ps.setString(7, t.size?.name)
                    if (t.estimateMin == null) ps.setNull(8, java.sql.Types.INTEGER) else ps.setInt(8, t.estimateMin)
                    ps.setString(9, TaskStatus.PENDING.name)
                    ps.setString(10, t.repoId)
                    ps.executeUpdate()
                }
            }
        }
    }

    fun tasks(implId: String): List<ImplTask> =
        store.stmt(
            """SELECT id, impl_id, seq, title, detail, depends_on, size, estimate_min, status,
                      commit_sha, result, error, cost_usd, started_at, finished_at, repo_id
                 FROM impl_task WHERE impl_id = ? ORDER BY seq""",
        ) { ps ->
            ps.setString(1, implId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            ImplTask(
                                id = rs.getString(1),
                                implId = rs.getString(2),
                                repoId = rs.getString(16),
                                seq = rs.getInt(3),
                                title = rs.getString(4),
                                detail = rs.getString(5).orEmpty(),
                                dependsOn = rs.getString(6).orEmpty()
                                    .split(',').mapNotNull { it.trim().toIntOrNull() },
                                size = TaskSize.fromApi(rs.getString(7)),
                                estimateMin = rs.getObject(8)?.let { rs.getInt(8) },
                                status = runCatching { TaskStatus.valueOf(rs.getString(9)) }
                                    .getOrDefault(TaskStatus.PENDING),
                                commitSha = rs.getString(10),
                                result = rs.getString(11),
                                error = rs.getString(12),
                                costUsd = rs.getObject(13)?.let { rs.getDouble(13) },
                                startedAt = rs.getString(14),
                                finishedAt = rs.getString(15),
                            ),
                        )
                    }
                }
            }
        }

    /**
     * El avance de todas las implementaciones, en una sola consulta.
     *
     * La lista lo necesita para cada fila, y pedirlo por implementación era una consulta por fila
     * en cada recomposición. Se agrega en SQL en vez de traer todas las tareas: lo que la pantalla
     * muestra son cinco números, no las tareas.
     */
    fun progressOfAll(): Map<String, io.acr.impl.Progress> =
        store.stmt(
            """SELECT impl_id,
                      COUNT(*),
                      SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END),
                      SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END),
                      SUM(CASE WHEN status = 'RUNNING' THEN 1 ELSE 0 END),
                      SUM(COALESCE(estimate_min, 0)),
                      SUM(CASE WHEN started_at IS NOT NULL AND finished_at IS NOT NULL
                               THEN (julianday(finished_at) - julianday(started_at)) * 1440
                               ELSE 0 END),
                      SUM(CASE WHEN status IN ('PENDING','RUNNING') THEN COALESCE(estimate_min, 0) ELSE 0 END)
                 FROM impl_task GROUP BY impl_id""",
        ) { ps ->
            ps.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        val hechas = rs.getInt(3)
                        val transcurrido = rs.getDouble(7)
                        val estimadoHecho = rs.getInt(6) - rs.getInt(8)
                        // El desvío sólo con tareas terminadas y estimadas: sin eso sería 1,0 y
                        // mostraría una precisión inventada.
                        val desvio = if (hechas > 0 && estimadoHecho > 0) transcurrido / estimadoHecho else null
                        put(
                            rs.getString(1),
                            io.acr.impl.Progress(
                                total = rs.getInt(2),
                                done = hechas,
                                failed = rs.getInt(4),
                                running = rs.getInt(5),
                                estimatedMin = rs.getInt(6),
                                elapsedMin = transcurrido,
                                remainingMin = rs.getInt(8) * (desvio ?: 1.0),
                                drift = desvio,
                            ),
                        )
                    }
                }
            }
        }

    fun startTask(taskId: String) {
        store.stmt("UPDATE impl_task SET status = ?, started_at = ?, error = NULL WHERE id = ?") { ps ->
            ps.setString(1, TaskStatus.RUNNING.name)
            ps.setString(2, Instant.now().toString())
            ps.setString(3, taskId)
            ps.executeUpdate()
        }
    }

    fun finishTask(taskId: String, commitSha: String?, result: String?, costUsd: Double?) {
        store.stmt(
            """UPDATE impl_task SET status = ?, commit_sha = ?, result = ?, cost_usd = ?,
                   finished_at = ? WHERE id = ?""",
        ) { ps ->
            ps.setString(1, TaskStatus.DONE.name)
            ps.setString(2, commitSha)
            ps.setString(3, result)
            if (costUsd == null) ps.setNull(4, java.sql.Types.REAL) else ps.setDouble(4, costUsd)
            ps.setString(5, Instant.now().toString())
            ps.setString(6, taskId)
            ps.executeUpdate()
        }
    }

    fun failTask(taskId: String, error: String, costUsd: Double? = null) {
        store.stmt(
            "UPDATE impl_task SET status = ?, error = ?, cost_usd = ?, finished_at = ? WHERE id = ?",
        ) { ps ->
            ps.setString(1, TaskStatus.FAILED.name)
            ps.setString(2, error.take(2_000))
            if (costUsd == null) ps.setNull(3, java.sql.Types.REAL) else ps.setDouble(3, costUsd)
            ps.setString(4, Instant.now().toString())
            ps.setString(5, taskId)
            ps.executeUpdate()
        }
    }

    /** Vuelve a dejar pendiente una tarea, para reintentarla sin rehacer el plan. */
    fun resetTask(taskId: String) {
        store.stmt(
            """UPDATE impl_task SET status = ?, error = NULL, result = NULL, started_at = NULL,
                   finished_at = NULL WHERE id = ?""",
        ) { ps ->
            ps.setString(1, TaskStatus.PENDING.name)
            ps.setString(2, taskId)
            ps.executeUpdate()
        }
    }

    fun finish(implId: String, status: ImplStatus, costUsd: Double?) {
        store.stmt(
            "UPDATE implementation SET status = ?, cost_usd = ?, finished_at = ? WHERE id = ?",
        ) { ps ->
            ps.setString(1, status.name)
            if (costUsd == null) ps.setNull(2, java.sql.Types.REAL) else ps.setDouble(2, costUsd)
            ps.setString(3, Instant.now().toString())
            ps.setString(4, implId)
            ps.executeUpdate()
        }
    }

    fun setModels(implId: String, planModel: String?, codeModel: String?) {
        store.stmt("UPDATE implementation SET plan_model = ?, code_model = ? WHERE id = ?") { ps ->
            ps.setString(1, planModel)
            ps.setString(2, codeModel)
            ps.setString(3, implId)
            ps.executeUpdate()
        }
    }

    // --- preguntas: lo único que frena una implementación autónoma ---

    fun ask(
        implId: String,
        taskId: String?,
        kind: io.acr.impl.QuestionKind,
        question: String,
        context: String?,
        options: List<String>,
    ): String {
        val id = UlidCreator.getUlid().toString()
        store.stmt(
            """INSERT INTO impl_question(id, impl_id, task_id, kind, question, context, options, asked_at)
               VALUES (?,?,?,?,?,?,?,?)""",
        ) { ps ->
            ps.setString(1, id)
            ps.setString(2, implId)
            ps.setString(3, taskId)
            ps.setString(4, kind.name)
            ps.setString(5, question)
            ps.setString(6, context)
            ps.setString(7, options.joinToString("\n"))
            ps.setString(8, Instant.now().toString())
            ps.executeUpdate()
        }
        return id
    }

    fun questions(implId: String): List<io.acr.impl.ImplQuestion> =
        store.stmt(
            """SELECT id, impl_id, task_id, kind, question, context, options, answer, asked_at, answered_at
                 FROM impl_question WHERE impl_id = ? ORDER BY asked_at""",
        ) { ps ->
            ps.setString(1, implId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            io.acr.impl.ImplQuestion(
                                id = rs.getString(1),
                                implId = rs.getString(2),
                                taskId = rs.getString(3),
                                kind = io.acr.impl.QuestionKind.fromApi(rs.getString(4)),
                                question = rs.getString(5),
                                context = rs.getString(6),
                                options = rs.getString(7).orEmpty().lines().filter { it.isNotBlank() },
                                answer = rs.getString(8),
                                askedAt = rs.getString(9),
                                answeredAt = rs.getString(10),
                            ),
                        )
                    }
                }
            }
        }

    /**
     * Contesta y desbloquea la tarea que esperaba.
     *
     * Las dos cosas juntas: una respuesta guardada que no vuelva a poner la tarea en cola dejaría
     * la implementación esperando algo que ya se contestó, y nadie se enteraría.
     */
    fun answer(questionId: String, answer: String) {
        store.transaction { conn ->
            conn.prepareStatement(
                "UPDATE impl_question SET answer = ?, answered_at = ? WHERE id = ?",
            ).use { ps ->
                ps.setString(1, answer)
                ps.setString(2, Instant.now().toString())
                ps.setString(3, questionId)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                """UPDATE impl_task SET status = ?, error = NULL, started_at = NULL, finished_at = NULL
                    WHERE id = (SELECT task_id FROM impl_question WHERE id = ?)""",
            ).use { ps ->
                ps.setString(1, io.acr.impl.TaskStatus.PENDING.name)
                ps.setString(2, questionId)
                ps.executeUpdate()
            }
        }
    }

    fun blockTask(taskId: String, question: String) {
        store.stmt("UPDATE impl_task SET status = ?, error = ?, finished_at = ? WHERE id = ?") { ps ->
            ps.setString(1, io.acr.impl.TaskStatus.BLOCKED.name)
            ps.setString(2, question.take(1_000))
            ps.setString(3, Instant.now().toString())
            ps.setString(4, taskId)
            ps.executeUpdate()
        }
    }

    fun delete(id: String) {
        store.stmt("DELETE FROM implementation WHERE id = ?") { ps -> ps.setString(1, id); ps.executeUpdate() }
    }

    private fun query(tail: String, bind: (java.sql.PreparedStatement) -> Unit): List<Implementation> =
        store.stmt(
            """SELECT id, repo_id, title, sources, extra_prompt, branch, base_branch, status,
                      plan_summary, plan_model, code_model, error, cost_usd, created_at,
                      planned_at, finished_at
                 FROM implementation $tail""",
        ) { ps ->
            bind(ps)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Implementation(
                                id = rs.getString(1),
                                repoId = rs.getString(2),
                                title = rs.getString(3),
                                sources = rs.getString(4).orEmpty().lines().filter { it.isNotBlank() },
                                extraPrompt = rs.getString(5),
                                branch = rs.getString(6),
                                baseBranch = rs.getString(7),
                                status = runCatching { ImplStatus.valueOf(rs.getString(8)) }
                                    .getOrDefault(ImplStatus.DRAFT),
                                planSummary = rs.getString(9),
                                planModel = rs.getString(10),
                                codeModel = rs.getString(11),
                                error = rs.getString(12),
                                costUsd = rs.getObject(13)?.let { rs.getDouble(13) },
                                createdAt = rs.getString(14),
                                plannedAt = rs.getString(15),
                                finishedAt = rs.getString(16),
                            ),
                        )
                    }
                }
            }
        }
}
