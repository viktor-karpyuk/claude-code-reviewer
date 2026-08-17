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
                    "INSERT OR REPLACE INTO impl_repo(impl_id, repo_id, role, base_branch) VALUES (?,?,?,?)",
                ).use { ps ->
                    ps.setString(1, id); ps.setString(2, r.repoId); ps.setString(3, r.role.name)
                    ps.setString(4, r.baseBranch)
                    ps.executeUpdate()
                }
            }
        }
        return id
    }

    /**
     * Cambia lo que define una implementación sin tocar lo ya hecho.
     *
     * No borra tareas ni commits: sumar un repositorio o corregir un parámetro a mitad de camino
     * es normal, y obligar a empezar de cero por eso tiraría el trabajo hecho. Lo que sí queda
     * viejo es el plan —se armó con otra información—, así que el que replanifica decide cuándo.
     */
    fun update(
        id: String,
        title: String,
        sources: List<String>,
        extra: String?,
        repos: List<io.acr.impl.ImplRepo>,
    ) {
        store.transaction { conn ->
            conn.prepareStatement(
                "UPDATE implementation SET title = ?, sources = ?, extra_prompt = ?, repo_id = ? WHERE id = ?",
            ).use { ps ->
                ps.setString(1, title)
                ps.setString(2, sources.joinToString("\n"))
                ps.setString(3, extra)
                ps.setString(4, repos.first().repoId)
                ps.setString(5, id)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM impl_repo WHERE impl_id = ?").use { ps ->
                ps.setString(1, id); ps.executeUpdate()
            }
            repos.forEach { r ->
                conn.prepareStatement(
                    "INSERT OR REPLACE INTO impl_repo(impl_id, repo_id, role, base_branch) VALUES (?,?,?,?)",
                ).use { ps ->
                    ps.setString(1, id); ps.setString(2, r.repoId); ps.setString(3, r.role.name)
                    ps.setString(4, r.baseBranch)
                    ps.executeUpdate()
                }
            }
        }
    }

    /**
     * Borra el plan para poder rehacerlo, conservando lo ya construido.
     *
     * Las tareas terminadas no se tocan: su código está commiteado y sigue existiendo. Se quitan
     * las que no llegaron a hacerse, que son las que el plan nuevo va a reemplazar.
     */
    fun clearPendingPlan(implId: String) {
        store.transaction { conn ->
            conn.prepareStatement(
                "DELETE FROM impl_task WHERE impl_id = ? AND status IN ('PENDING','FAILED','BLOCKED')",
            ).use { ps -> ps.setString(1, implId); ps.executeUpdate() }
            conn.prepareStatement(
                "UPDATE implementation SET status = ?, error = NULL WHERE id = ?",
            ).use { ps ->
                ps.setString(1, ImplStatus.DRAFT.name); ps.setString(2, implId); ps.executeUpdate()
            }
        }
    }

    /** Los repositorios de una implementación, con su rol. */
    fun reposOf(implId: String): List<io.acr.impl.ImplRepo> =
        store.stmt("SELECT repo_id, role, base_branch FROM impl_repo WHERE impl_id = ?") { ps ->
            ps.setString(1, implId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            io.acr.impl.ImplRepo(
                                rs.getString(1),
                                io.acr.impl.RepoRole.fromApi(rs.getString(2)),
                                rs.getString(3),
                            ),
                        )
                    }
                }
            }
        }

    /**
     * Cierra las implementaciones que quedaron marcadas como corriendo de una sesión anterior.
     *
     * El estado del motor es en memoria: los procesos murieron con la app y nadie va a cerrarlas.
     * Sin esto quedan diciendo "corriendo" o "planificando" para siempre, sin nada corriendo y sin
     * nada que lo delate — y peor, el botón ofrece frenarlas en vez de retomarlas.
     *
     * Quedan en STOPPED y no en FAILED: nadie las intentó y falló, se cortaron. Desde ahí el botón
     * dice "retomar", que es lo que corresponde.
     */
    fun stopOrphanedRunning(): Int =
        store.stmt(
            "UPDATE implementation SET status = ? WHERE status IN (?, ?)",
        ) { ps ->
            ps.setString(1, ImplStatus.STOPPED.name)
            ps.setString(2, ImplStatus.RUNNING.name)
            ps.setString(3, ImplStatus.PLANNING.name)
            ps.executeUpdate()
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
            // El contexto de las tareas que se van también se va: describe un trabajo que el plan
            // nuevo ya no pide, y dejarlo colgado haría que un intento futuro crea que hay avance
            // sobre algo distinto. Va primero, mientras las tareas todavía existen para poder
            // encontrarlo.
            conn.prepareStatement(
                """DELETE FROM task_context WHERE task_id IN
                     (SELECT id FROM impl_task WHERE impl_id = ?)""",
            ).use { ps -> ps.setString(1, implId); ps.executeUpdate() }
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
            val ahora = Instant.now().toString()
            tasks.forEach { t ->
                val taskId = UlidCreator.getUlid().toString()
                conn.prepareStatement(
                    """INSERT INTO impl_task(id, impl_id, seq, title, detail, depends_on, size,
                             estimate_min, status, repo_id, created_at, updated_at)
                       VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                ).use { ps ->
                    ps.setString(1, taskId)
                    ps.setString(2, implId)
                    ps.setInt(3, t.seq)
                    ps.setString(4, t.title)
                    ps.setString(5, t.detail)
                    ps.setString(6, t.dependsOn.joinToString(","))
                    ps.setString(7, t.size?.name)
                    if (t.estimateMin == null) ps.setNull(8, java.sql.Types.INTEGER) else ps.setInt(8, t.estimateMin)
                    ps.setString(9, TaskStatus.PENDING.name)
                    ps.setString(10, t.repoId)
                    ps.setString(11, ahora)
                    ps.setString(12, ahora)
                    ps.executeUpdate()
                }
                t.steps.forEach { paso ->
                    conn.prepareStatement(
                        """INSERT INTO impl_step(id, task_id, seq, title, status, created_at)
                           VALUES (?,?,?,?,?,?)""",
                    ).use { ps ->
                        ps.setString(1, UlidCreator.getUlid().toString())
                        ps.setString(2, taskId)
                        ps.setInt(3, paso.seq)
                        ps.setString(4, paso.title)
                        ps.setString(5, TaskStatus.PENDING.name)
                        ps.setString(6, ahora)
                        ps.executeUpdate()
                    }
                }
            }
        }
    }

    /**
     * Los pasos de todas las tareas de una implementación, en una sola consulta.
     *
     * Agrupados acá y no pedidos por tarea: la pantalla los muestra en una lista de hasta
     * veinticinco tareas, y una consulta por fila en cada recomposición es exactamente el problema
     * que se arregló antes con el avance.
     */
    private fun stepsOf(implId: String): Map<String, List<io.acr.impl.ImplStep>> =
        store.stmt(
            """SELECT s.id, s.task_id, s.seq, s.title, s.status, s.note,
                      s.created_at, s.updated_at, s.started_at, s.finished_at
                 FROM impl_step s JOIN impl_task t ON t.id = s.task_id
                WHERE t.impl_id = ? ORDER BY s.seq""",
        ) { ps ->
            ps.setString(1, implId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            io.acr.impl.ImplStep(
                                id = rs.getString(1),
                                taskId = rs.getString(2),
                                seq = rs.getInt(3),
                                title = rs.getString(4),
                                status = runCatching { TaskStatus.valueOf(rs.getString(5)) }
                                    .getOrDefault(TaskStatus.PENDING),
                                note = rs.getString(6),
                                createdAt = rs.getString(7).orEmpty(),
                                updatedAt = rs.getString(8),
                                startedAt = rs.getString(9),
                                finishedAt = rs.getString(10),
                            ),
                        )
                    }
                }
            }
        }.groupBy { it.taskId }

    fun tasks(implId: String): List<ImplTask> {
        val pasos = stepsOf(implId)
        return store.stmt(
            """SELECT id, impl_id, seq, title, detail, depends_on, size, estimate_min, status,
                      commit_sha, result, error, cost_usd, started_at, finished_at, repo_id,
                      files_added, files_modified, files_deleted, lines_added, lines_deleted,
                      files_detail, prompt, created_at, updated_at
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
                                diff = rs.getObject(17)?.let {
                                    io.acr.impl.TaskDiff(
                                        filesAdded = rs.getInt(17),
                                        filesModified = rs.getInt(18),
                                        filesDeleted = rs.getInt(19),
                                        linesAdded = rs.getInt(20),
                                        linesDeleted = rs.getInt(21),
                                        files = rs.getString(22).orEmpty().lines()
                                            .mapNotNull { l ->
                                                val p = l.split('|')
                                                if (p.size < 4) null
                                                else io.acr.impl.FileChange(
                                                    p[0].firstOrNull() ?: 'M', p[1],
                                                    p[2].toIntOrNull() ?: 0, p[3].toIntOrNull() ?: 0,
                                                )
                                            },
                                    )
                                },
                                prompt = rs.getString(23),
                                createdAt = rs.getString(24),
                                updatedAt = rs.getString(25),
                                steps = pasos[rs.getString(1)].orEmpty(),
                            ),
                        )
                    }
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

    /** El estado de una tarea, sin traer el resto. Para decidir si hay que reintentarla. */
    fun taskStatus(taskId: String): TaskStatus? =
        store.stmt("SELECT status FROM impl_task WHERE id = ?") { ps ->
            ps.setString(1, taskId)
            ps.executeQuery().use { rs ->
                if (rs.next()) runCatching { TaskStatus.valueOf(rs.getString(1)) }.getOrNull() else null
            }
        }

    fun startTask(taskId: String) {
        val ahora = Instant.now().toString()
        store.stmt(
            """UPDATE impl_task SET status = ?, started_at = ?, updated_at = ?, error = NULL
                 WHERE id = ?""",
        ) { ps ->
            ps.setString(1, TaskStatus.RUNNING.name)
            ps.setString(2, ahora)
            ps.setString(3, ahora)
            ps.setString(4, taskId)
            ps.executeUpdate()
        }
    }

    /**
     * Guarda el prompt con el que se le pidió el trabajo a la tarea.
     *
     * Se guarda al arrancar y no después: si la tarea revienta a mitad de camino, el prompt es
     * justamente lo que hace falta para entender por qué, y guardarlo al final significaría no
     * tenerlo nunca en el único caso donde importa.
     */
    fun savePrompt(taskId: String, prompt: String) {
        store.stmt("UPDATE impl_task SET prompt = ?, updated_at = ? WHERE id = ?") { ps ->
            ps.setString(1, prompt)
            ps.setString(2, Instant.now().toString())
            ps.setString(3, taskId)
            ps.executeUpdate()
        }
    }

    /** Marca un paso como en curso. Los pasos se tildan de a uno mientras la tarea avanza. */
    fun startStep(stepId: String) {
        val ahora = Instant.now().toString()
        store.stmt(
            """UPDATE impl_step SET status = ?, started_at = ?, updated_at = ? WHERE id = ?""",
        ) { ps ->
            ps.setString(1, TaskStatus.RUNNING.name)
            ps.setString(2, ahora)
            ps.setString(3, ahora)
            ps.setString(4, stepId)
            ps.executeUpdate()
        }
    }

    /**
     * Cierra un paso con lo que reportó el modelo.
     *
     * Un paso que no se hizo queda FAILED y no PENDING: pendiente se lee como "todavía va a
     * pasar", y cuando la tarea ya terminó eso es mentira. Lo que quedó sin hacer tiene que
     * verse como lo que es.
     */
    fun finishStep(stepId: String, done: Boolean, note: String?) {
        val ahora = Instant.now().toString()
        store.stmt(
            """UPDATE impl_step SET status = ?, note = ?, finished_at = ?, updated_at = ?,
                   started_at = COALESCE(started_at, ?) WHERE id = ?""",
        ) { ps ->
            ps.setString(1, if (done) TaskStatus.DONE.name else TaskStatus.FAILED.name)
            ps.setString(2, note?.take(1_000))
            ps.setString(3, ahora)
            ps.setString(4, ahora)
            ps.setString(5, ahora)
            ps.setString(6, stepId)
            ps.executeUpdate()
        }
    }

    /** Devuelve los pasos de una tarea a pendientes, para reintentarla desde cero. */
    fun resetSteps(taskId: String) {
        store.stmt(
            """UPDATE impl_step SET status = ?, note = NULL, started_at = NULL, finished_at = NULL,
                   updated_at = ? WHERE task_id = ?""",
        ) { ps ->
            ps.setString(1, TaskStatus.PENDING.name)
            ps.setString(2, Instant.now().toString())
            ps.setString(3, taskId)
            ps.executeUpdate()
        }
    }

    fun finishTask(
        taskId: String,
        commitSha: String?,
        result: String?,
        costUsd: Double?,
        diff: io.acr.impl.TaskDiff? = null,
    ) {
        store.stmt(
            """UPDATE impl_task SET status = ?, commit_sha = ?, result = ?, cost_usd = ?,
                   finished_at = ?, files_added = ?, files_modified = ?, files_deleted = ?,
                   lines_added = ?, lines_deleted = ?, files_detail = ?, updated_at = ?
                 WHERE id = ?""",
        ) { ps ->
            ps.setString(1, TaskStatus.DONE.name)
            ps.setString(2, commitSha)
            ps.setString(3, result)
            if (costUsd == null) ps.setNull(4, java.sql.Types.REAL) else ps.setDouble(4, costUsd)
            ps.setString(5, Instant.now().toString())
            if (diff == null) {
                (6..10).forEach { ps.setNull(it, java.sql.Types.INTEGER) }
                ps.setNull(11, java.sql.Types.VARCHAR)
            } else {
                ps.setInt(6, diff.filesAdded)
                ps.setInt(7, diff.filesModified)
                ps.setInt(8, diff.filesDeleted)
                ps.setInt(9, diff.linesAdded)
                ps.setInt(10, diff.linesDeleted)
                // Una línea por archivo. Se guarda el detalle y no sólo los totales porque lo que
                // sirve para revisar es cuáles, no cuántos.
                ps.setString(
                    11,
                    diff.files.joinToString("\n") { f -> f.status.toString() + "|" + f.path + "|" + f.added + "|" + f.deleted },
                )
            }
            ps.setString(12, Instant.now().toString())
            ps.setString(13, taskId)
            ps.executeUpdate()
        }
    }

    fun failTask(taskId: String, error: String, costUsd: Double? = null) {
        store.stmt(
            """UPDATE impl_task SET status = ?, error = ?, cost_usd = ?, finished_at = ?,
                   updated_at = ? WHERE id = ?""",
        ) { ps ->
            val ahora = Instant.now().toString()
            ps.setString(1, TaskStatus.FAILED.name)
            ps.setString(2, error.take(2_000))
            if (costUsd == null) ps.setNull(3, java.sql.Types.REAL) else ps.setDouble(3, costUsd)
            ps.setString(4, ahora)
            ps.setString(5, ahora)
            ps.setString(6, taskId)
            ps.executeUpdate()
        }
    }

    /**
     * Devuelve a pendientes todas las tareas fallidas de una implementación.
     *
     * Es lo que hace que "retomar" signifique algo. Sin esto, retomar una implementación fallida no
     * reintentaba nada —el motor sólo toma tareas pendientes, y las fallidas ya no lo eran— así que
     * terminaba al instante volviendo a mostrar el error de la vez anterior, como si nada hubiera
     * pasado. Y no había pasado nada.
     *
     * Las bloqueadas no se tocan: esperan una decisión que nadie tomó todavía, y volver a lanzarlas
     * las haría chocar contra la misma pregunta.
     *
     * Devuelve cuántas volvieron a la cola.
     */
    fun retryFailed(implId: String): Int {
        val fallidas = tasks(implId).filter { it.status == TaskStatus.FAILED }
        fallidas.forEach { resetTask(it.id) }
        return fallidas.size
    }

    /** Vuelve a dejar pendiente una tarea, para reintentarla sin rehacer el plan. */
    fun resetTask(taskId: String) {
        store.stmt(
            """UPDATE impl_task SET status = ?, error = NULL, result = NULL, started_at = NULL,
                   finished_at = NULL, updated_at = ? WHERE id = ?""",
        ) { ps ->
            ps.setString(1, TaskStatus.PENDING.name)
            ps.setString(2, Instant.now().toString())
            ps.setString(3, taskId)
            ps.executeUpdate()
        }
        resetSteps(taskId)
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
                """UPDATE impl_task SET status = ?, error = NULL, started_at = NULL,
                        finished_at = NULL, updated_at = ?
                    WHERE id = (SELECT task_id FROM impl_question WHERE id = ?)""",
            ).use { ps ->
                ps.setString(1, io.acr.impl.TaskStatus.PENDING.name)
                ps.setString(2, Instant.now().toString())
                ps.setString(3, questionId)
                ps.executeUpdate()
            }
        }
    }

    fun blockTask(taskId: String, question: String) {
        store.stmt(
            """UPDATE impl_task SET status = ?, error = ?, finished_at = ?, updated_at = ?
                 WHERE id = ?""",
        ) { ps ->
            val ahora = Instant.now().toString()
            ps.setString(1, io.acr.impl.TaskStatus.BLOCKED.name)
            ps.setString(2, question.take(1_000))
            ps.setString(3, ahora)
            ps.setString(4, ahora)
            ps.setString(5, taskId)
            ps.executeUpdate()
        }
    }

    /**
     * Guarda lo último que se pidió al revisar el plan.
     *
     * Para que la próxima revisión arranque de ahí y no en blanco: casi siempre se revisa dos veces
     * seguidas por lo mismo, y volver a escribirlo entero invita a escribir menos.
     */
    fun saveReviewGuidance(implId: String, guidance: String) {
        store.stmt("UPDATE implementation SET review_guidance = ? WHERE id = ?") { ps ->
            ps.setString(1, guidance.take(4_000))
            ps.setString(2, implId)
            ps.executeUpdate()
        }
    }

    /** Guarda el resultado de una pasada de revisión. */
    fun saveReview(
        implId: String,
        taskId: String?,
        pass: Int,
        findings: Int,
        fixed: Int,
        summary: String?,
        detail: String?,
        commitSha: String?,
        costUsd: Double?,
        kind: io.acr.impl.ReviewKind = io.acr.impl.ReviewKind.CODE,
    ) {
        store.stmt(
            """INSERT INTO impl_review(id, impl_id, task_id, pass, findings, fixed, summary,
                     detail, commit_sha, cost_usd, created_at, kind)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
        ) { ps ->
            ps.setString(1, UlidCreator.getUlid().toString())
            ps.setString(2, implId)
            ps.setString(3, taskId)
            ps.setInt(4, pass)
            ps.setInt(5, findings)
            ps.setInt(6, fixed)
            ps.setString(7, summary?.take(4_000))
            ps.setString(8, detail?.take(20_000))
            ps.setString(9, commitSha)
            if (costUsd == null) ps.setNull(10, java.sql.Types.REAL) else ps.setDouble(10, costUsd)
            ps.setString(11, Instant.now().toString())
            ps.setString(12, kind.name)
            ps.executeUpdate()
        }
    }

    fun reviews(implId: String): List<io.acr.impl.ReviewPass> =
        store.stmt(
            """SELECT id, impl_id, task_id, pass, findings, fixed, summary, detail, commit_sha,
                      cost_usd, created_at, kind
                 FROM impl_review WHERE impl_id = ? ORDER BY created_at, pass""",
        ) { ps ->
            ps.setString(1, implId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            io.acr.impl.ReviewPass(
                                id = rs.getString(1),
                                implId = rs.getString(2),
                                taskId = rs.getString(3),
                                pass = rs.getInt(4),
                                findings = rs.getInt(5),
                                fixed = rs.getInt(6),
                                summary = rs.getString(7),
                                detail = rs.getString(8),
                                commitSha = rs.getString(9),
                                costUsd = rs.getObject(10)?.let { rs.getDouble(10) },
                                createdAt = rs.getString(11).orEmpty(),
                                kind = io.acr.impl.ReviewKind.fromApi(rs.getString(12)),
                            ),
                        )
                    }
                }
            }
        }

    /**
     * Suma un documento a los que ya tiene, sin tocar el resto.
     *
     * Existe porque el análisis de las specs deja un documento nuevo y tiene que quedar entre los
     * que el planificador va a leer. Reescribir la lista entera desde afuera se prestaba a perder
     * los que ya estaban.
     */
    fun addSource(implId: String, path: String) {
        val actual = get(implId)?.sources.orEmpty()
        if (path in actual) return
        store.stmt("UPDATE implementation SET sources = ? WHERE id = ?") { ps ->
            ps.setString(1, (actual + path).joinToString("\n"))
            ps.setString(2, implId)
            ps.executeUpdate()
        }
    }

    /**
     * Fija el nombre de la rama, o lo devuelve a automático con null.
     *
     * La marca va aparte del nombre porque después de planificar los dos están llenos, y sin saber
     * cuál fue una decisión de una persona, replanificar pisaría lo elegido.
     */
    fun setBranch(implId: String, branch: String?) {
        store.stmt("UPDATE implementation SET branch = ?, branch_fixed = ? WHERE id = ?") { ps ->
            ps.setString(1, branch?.trim()?.trim('/')?.takeIf { it.isNotBlank() })
            ps.setInt(2, if (branch.isNullOrBlank()) 0 else 1)
            ps.setString(3, implId)
            ps.executeUpdate()
        }
    }

    /** Cuántas pasadas de revisión hacer, y si además revisar después de cada tarea. */
    fun setReviewPolicy(implId: String, min: Int, max: Int, each: Boolean) {
        store.stmt(
            "UPDATE implementation SET review_min = ?, review_max = ?, review_each = ? WHERE id = ?",
        ) { ps ->
            ps.setInt(1, min)
            ps.setInt(2, max)
            ps.setInt(3, if (each) 1 else 0)
            ps.setString(4, implId)
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
                      planned_at, finished_at, review_guidance, review_min, review_max,
                      review_each, branch_fixed
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
                                reviewGuidance = rs.getString(17),
                                // Los valores por defecto viven acá y no en el DDL: las
                                // implementaciones creadas antes de esta migración tienen null, y
                                // sin esto quedarían con cero pasadas sin que nadie lo haya
                                // decidido.
                                reviewMin = rs.getObject(18)?.let { rs.getInt(18) } ?: 2,
                                reviewMax = rs.getObject(19)?.let { rs.getInt(19) } ?: 5,
                                reviewEach = (rs.getObject(20)?.let { rs.getInt(20) } ?: 0) == 1,
                                branchFixed = (rs.getObject(21)?.let { rs.getInt(21) } ?: 0) == 1,
                            ),
                        )
                    }
                }
            }
        }
}
