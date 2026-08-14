package io.acr.data

import com.github.f4b6a3.ulid.UlidCreator
import io.acr.crypto.Secrets
import io.acr.forge.Provider
import io.acr.forge.RepoRecord
import java.time.Instant

class RepoRepository(private val store: Store, private val secrets: Secrets) {

    fun list(): List<RepoRecord> =
        store.stmt(
            """SELECT id, name, provider, owner, slug, local_path, token_cipher, project_kind,
                      default_depth, default_model, auto_review, skip_drafts, skip_titles,
                      skip_authors, only_targets, reply_mode
               FROM repo ORDER BY name""",
        ) { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(map(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getBytes(7),
                        rs.getString(8), rs.getString(9), rs.getString(10), rs.getInt(11) == 1,
                        rs.getInt(12) == 1, rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(16)))
                }
            }
        }

    fun get(id: String): RepoRecord? =
        store.stmt(
            """SELECT id, name, provider, owner, slug, local_path, token_cipher, project_kind,
                      default_depth, default_model, auto_review, skip_drafts, skip_titles,
                      skip_authors, only_targets, reply_mode
               FROM repo WHERE id = ?""",
        ) { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (rs.next()) map(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getString(6), rs.getBytes(7),
                    rs.getString(8), rs.getString(9), rs.getString(10), rs.getInt(11) == 1,
                    rs.getInt(12) == 1, rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(16)) else null
            }
        }

    fun create(
        name: String,
        provider: Provider,
        owner: String,
        slug: String,
        localPath: String,
        token: String?,
        projectKind: io.acr.claude.ProjectKind?,
        defaultDepth: io.acr.claude.ReviewDepth?,
        defaultModel: String,
        autoReview: Boolean,
        skip: io.acr.forge.SkipRules,
        replyMode: io.acr.forge.ReplyMode,
    ): String {
        val id = UlidCreator.getUlid().toString()
        store.stmt(
            """INSERT INTO repo(id, name, provider, owner, slug, local_path, token_cipher, created_at,
                                  project_kind, default_depth, default_model, auto_review,
                                  skip_drafts, skip_titles, skip_authors, only_targets, reply_mode)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        ) { ps ->
            ps.setString(1, id)
            ps.setString(2, name)
            ps.setString(3, provider.name)
            ps.setString(4, owner)
            ps.setString(5, slug)
            ps.setString(6, localPath)
            if (token.isNullOrBlank()) ps.setNull(7, java.sql.Types.BLOB) else ps.setBytes(7, secrets.encrypt(token))
            ps.setString(8, Instant.now().toString())
            ps.setString(9, projectKind?.name ?: AUTO)
            ps.setString(10, defaultDepth?.name ?: AUTO)
            ps.setString(11, defaultModel)
            ps.setInt(12, if (autoReview) 1 else 0)
            ps.setInt(13, if (skip.skipDrafts) 1 else 0)
            ps.setString(14, skip.skipTitles)
            ps.setString(15, skip.skipAuthors)
            ps.setString(16, skip.onlyTargets)
            ps.setString(17, replyMode.name)
            ps.executeUpdate()
        }
        return id
    }

    fun update(
        id: String,
        name: String,
        localPath: String,
        token: String?,
        projectKind: io.acr.claude.ProjectKind?,
        defaultDepth: io.acr.claude.ReviewDepth?,
        defaultModel: String,
        autoReview: Boolean,
        skip: io.acr.forge.SkipRules,
        replyMode: io.acr.forge.ReplyMode,
    ) {
        // A blank token means "leave the stored one alone" — the UI never echoes it back.
        val sql = if (token.isNullOrBlank()) {
            "UPDATE repo SET name = ?, local_path = ?, project_kind = ?, default_depth = ?, default_model = ?, auto_review = ?, skip_drafts = ?, skip_titles = ?, skip_authors = ?, only_targets = ?, reply_mode = ? WHERE id = ?"
        } else {
            "UPDATE repo SET name = ?, local_path = ?, project_kind = ?, default_depth = ?, default_model = ?, auto_review = ?, skip_drafts = ?, skip_titles = ?, skip_authors = ?, only_targets = ?, reply_mode = ?, token_cipher = ? WHERE id = ?"
        }
        store.stmt(sql) { ps ->
            ps.setString(1, name)
            ps.setString(2, localPath)
            ps.setString(3, projectKind?.name ?: AUTO)
            ps.setString(4, defaultDepth?.name ?: AUTO)
            ps.setString(5, defaultModel)
            ps.setInt(6, if (autoReview) 1 else 0)
            ps.setInt(7, if (skip.skipDrafts) 1 else 0)
            ps.setString(8, skip.skipTitles)
            ps.setString(9, skip.skipAuthors)
            ps.setString(10, skip.onlyTargets)
            ps.setString(11, replyMode.name)
            if (token.isNullOrBlank()) {
                ps.setString(12, id)
            } else {
                ps.setBytes(12, secrets.encrypt(token))
                ps.setString(13, id)
            }
            ps.executeUpdate()
        }
    }

    fun delete(id: String) {
        store.stmt("DELETE FROM repo WHERE id = ?") { ps ->
            ps.setString(1, id)
            ps.executeUpdate()
        }
    }

    private fun map(
        id: String, name: String, provider: String, owner: String,
        slug: String, localPath: String, cipher: ByteArray?,
        projectKind: String?, defaultDepth: String?, defaultModel: String?, autoReview: Boolean,
        skipDrafts: Boolean, skipTitles: String?, skipAuthors: String?, onlyTargets: String?,
        replyMode: String?,
    ) = RepoRecord(
        id = id,
        name = name,
        provider = Provider.valueOf(provider),
        owner = owner,
        slug = slug,
        localPath = localPath,
        token = cipher?.let { runCatching { secrets.decrypt(it) }.getOrNull() },
        // 'AUTO' o un valor desconocido se leen como null: modo automático.
        projectKind = projectKind?.takeIf { it != AUTO }
            ?.let { runCatching { io.acr.claude.ProjectKind.valueOf(it) }.getOrNull() },
        defaultDepth = defaultDepth?.takeIf { it != AUTO }
            ?.let { runCatching { io.acr.claude.ReviewDepth.valueOf(it) }.getOrNull() },
        defaultModel = defaultModel?.takeIf { it != AUTO } ?: "",
        autoReview = autoReview,
        skipRules = io.acr.forge.SkipRules(
            skipDrafts = skipDrafts,
            skipTitles = skipTitles ?: "",
            skipAuthors = skipAuthors ?: "",
            onlyTargets = onlyTargets ?: "",
        ),
        replyMode = io.acr.forge.ReplyMode.fromName(replyMode),
    )

    private companion object { const val AUTO = "AUTO" }
}

enum class ReviewStatus { RUNNING, DONE, FAILED, CANCELLED }

data class ReviewRecord(
    val id: String,
    val repoId: String,
    val prId: Long,
    val prTitle: String,
    val headSha: String,
    val status: ReviewStatus,
    val body: String?,
    val error: String?,
    val sessionId: String?,
    val costUsd: Double?,
    val publishedUrl: String?,
    val createdAt: String,
    val depth: io.acr.claude.ReviewDepth?,
    val projectKind: io.acr.claude.ProjectKind?,
    val model: String?,
    val auto: Boolean,
    /** Diagnóstico interno: NO se publica, sólo se muestra en la app. */
    val deniedTools: String?,
    /** Veredicto global de la última verificación de resolución, si se corrió. */
    val resolutionSummary: String? = null,
    val resolutionAt: String? = null,
    /** El commit contra el que se verificó. Si el PR avanzó, la verificación quedó vieja. */
    val resolutionHead: String? = null,
    /** Commit sobre el que corrió la pasada final, su resumen y cuántos bloqueantes encontró. */
    val finalPassHead: String? = null,
    val finalPassSummary: String? = null,
    val finalPassBlockers: Int = 0,
)

/** Lo mínimo para decidir si vale la pena repetir una review: cuándo fue, qué encontró, qué costó. */
data class PriorReview(
    val id: String,
    val createdAt: String,
    val costUsd: Double?,
    val findings: Int,
)

class ReviewRepository(private val store: Store) {

    fun start(
        repoId: String,
        prId: Long,
        prTitle: String,
        headSha: String,
        depth: io.acr.claude.ReviewDepth,
        projectKind: io.acr.claude.ProjectKind,
        model: String,
        auto: Boolean,
    ): String {
        val id = UlidCreator.getUlid().toString()
        store.stmt(
            """INSERT INTO review(id, repo_id, pr_id, pr_title, head_sha, status, created_at,
                                 depth, project_kind, model, trigger_kind)
               VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
        ) { ps ->
            ps.setString(1, id)
            ps.setString(2, repoId)
            ps.setLong(3, prId)
            ps.setString(4, prTitle)
            ps.setString(5, headSha)
            ps.setString(6, ReviewStatus.RUNNING.name)
            ps.setString(7, Instant.now().toString())
            ps.setString(8, depth.name)
            ps.setString(9, projectKind.name)
            ps.setString(10, model)
            ps.setString(11, if (auto) "AUTO" else "MANUAL")
            ps.executeUpdate()
        }
        return id
    }

    fun finish(
        id: String,
        body: String,
        sessionId: String?,
        costUsd: Double?,
        tokensIn: Long = 0,
        tokensOut: Long = 0,
        cacheRead: Long = 0,
        cacheWrite: Long = 0,
        deniedTools: String? = null,
    ) {
        store.stmt(
            """UPDATE review SET status = ?, body = ?, session_id = ?, cost_usd = ?, finished_at = ?,
                   tokens_in = ?, tokens_out = ?, tokens_cache_read = ?, tokens_cache_write = ?,
                   denied_tools = ?
               WHERE id = ?""",
        ) { ps ->
            ps.setString(1, ReviewStatus.DONE.name)
            ps.setString(2, body)
            ps.setString(3, sessionId)
            if (costUsd == null) ps.setNull(4, java.sql.Types.REAL) else ps.setDouble(4, costUsd)
            ps.setString(5, Instant.now().toString())
            ps.setLong(6, tokensIn)
            ps.setLong(7, tokensOut)
            ps.setLong(8, cacheRead)
            ps.setLong(9, cacheWrite)
            ps.setString(10, deniedTools)
            ps.setString(11, id)
            ps.executeUpdate()
        }
    }

    fun fail(id: String, error: String, status: ReviewStatus = ReviewStatus.FAILED) {
        store.stmt(
            "UPDATE review SET status = ?, error = ?, finished_at = ? WHERE id = ?",
        ) { ps ->
            ps.setString(1, status.name)
            ps.setString(2, error)
            ps.setString(3, Instant.now().toString())
            ps.setString(4, id)
            ps.executeUpdate()
        }
    }

    /** Resumen de la verificación: el veredicto global sobre si el PR quedó listo. */
    fun setResolutionSummary(id: String, summary: String, head: String) {
        store.stmt(
            "UPDATE review SET resolution_summary = ?, resolution_at = ?, resolution_head = ? WHERE id = ?",
        ) { ps ->
            ps.setString(1, summary.take(4_000))
            ps.setString(2, Instant.now().toString())
            ps.setString(3, head)
            ps.setString(4, id)
            ps.executeUpdate()
        }
    }

    /** Resultado de la pasada final, anclado al commit sobre el que corrió. */
    fun setFinalPass(id: String, head: String, summary: String, blockers: Int) {
        store.stmt(
            """UPDATE review SET final_pass_head = ?, final_pass_summary = ?, final_pass_blockers = ?
               WHERE id = ?""",
        ) { ps ->
            ps.setString(1, head)
            ps.setString(2, summary.take(4_000))
            ps.setInt(3, blockers)
            ps.setString(4, id)
            ps.executeUpdate()
        }
    }

    fun markPublished(id: String, url: String) {
        store.stmt("UPDATE review SET published_url = ? WHERE id = ?") { ps ->
            ps.setString(1, url)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    /**
     * Marca la review publicada si ya no le queda ningún hallazgo sin publicar.
     *
     * Publicar los hallazgos uno a uno —el camino normal, porque son comentarios anclados a
     * archivo y línea— no tocaba `published_url`, que sólo escribía el comentario resumen. El PR
     * quedaba figurando "listo para publicar" aunque estuviera todo comentado en el PR.
     *
     * Va en un solo UPDATE condicional en vez de leer-y-decidir: publicar dos hallazgos a la vez
     * dispararía dos veces esta comprobación y con dos pasos podrían pisarse.
     *
     * @return true si esta llamada fue la que la marcó.
     */
    fun markPublishedIfComplete(reviewId: String, url: String?): Boolean =
        store.stmt(
            """UPDATE review
                  SET published_url = COALESCE(?, '')
                WHERE id = ?
                  AND published_url IS NULL
                  AND EXISTS (SELECT 1 FROM finding f WHERE f.review_id = review.id)
                  AND NOT EXISTS (
                      SELECT 1 FROM finding f
                       WHERE f.review_id = review.id
                         AND f.published_id IS NULL AND f.dismissed_at IS NULL AND f.closed_at IS NULL
                  )""",
        ) { ps ->
            ps.setString(1, url?.takeIf { it.isNotBlank() })
            ps.setString(2, reviewId)
            ps.executeUpdate() > 0
        }

    /** Por PR: sin verificar, sin resolver, y cuántos se publicaron en total. */
    data class ResolutionCounts(val sinVerificar: Int, val noResueltos: Int, val publicados: Int)

    /**
     * Por PR: cuántos comentarios publicados están sin verificar y cuántos dieron "no resuelto".
     *
     * La lista necesita los mismos números que la pantalla del PR para decidir si se puede
     * mergear, pero sin cargar los hallazgos fila por fila.
     */
    fun resolutionCountsByPr(repoId: String): Map<Long, ResolutionCounts> =
        store.stmt(
            """SELECT f.pr_id,
                      SUM(CASE WHEN f.resolution IS NULL THEN 1 ELSE 0 END),
                      SUM(CASE WHEN f.resolution IS NOT NULL AND f.resolution <> 'RESOLVED'
                               THEN 1 ELSE 0 END),
                      COUNT(*)
                 FROM finding f
                WHERE f.repo_id = ? AND f.published_id IS NOT NULL AND f.dismissed_at IS NULL AND f.closed_at IS NULL
                  AND f.review_id = (
                      SELECT id FROM review r
                       WHERE r.repo_id = f.repo_id AND r.pr_id = f.pr_id AND r.status = 'DONE'
                       ORDER BY r.created_at DESC LIMIT 1
                  )
                GROUP BY f.pr_id""",
        ) { ps ->
            ps.setString(1, repoId)
            ps.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        put(rs.getLong(1), ResolutionCounts(rs.getInt(2), rs.getInt(3), rs.getInt(4)))
                    }
                }
            }
        }

    /**
     * Por PR: cuántos hallazgos tiene la última review y cuántos ya se publicaron. Sirve para
     * distinguir "sin publicar" de "publicada a medias", que antes se veían igual.
     */
    fun findingProgressByPr(repoId: String): Map<Long, Pair<Int, Int>> =
        store.stmt(
            """SELECT f.pr_id, COUNT(*),
                      SUM(CASE WHEN f.published_id IS NOT NULL OR f.dismissed_at IS NOT NULL OR f.closed_at IS NOT NULL
                               THEN 1 ELSE 0 END)
                 FROM finding f
                WHERE f.repo_id = ?
                  AND f.review_id = (
                      SELECT id FROM review r
                       WHERE r.repo_id = f.repo_id AND r.pr_id = f.pr_id AND r.status = 'DONE'
                       ORDER BY r.created_at DESC LIMIT 1
                  )
                GROUP BY f.pr_id""",
        ) { ps ->
            ps.setString(1, repoId)
            ps.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) put(rs.getLong(1), rs.getInt(2) to rs.getInt(3))
                }
            }
        }

    fun updateBody(id: String, body: String) {
        store.stmt("UPDATE review SET body = ? WHERE id = ?") { ps ->
            ps.setString(1, body)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    fun get(id: String): ReviewRecord? = query("WHERE id = ?") { it.setString(1, id) }.firstOrNull()

    /**
     * La última review con contenido. Si la corrida más nueva falló o se canceló, sigue habiendo
     * una review buena de antes y es la que el usuario quiere ver: mostrar el error y esconder el
     * contenido hacía que un PR marcado "listo para publicar" apareciera vacío.
     */
    fun latestUsableFor(repoId: String, prId: Long): ReviewRecord? =
        query("WHERE repo_id = ? AND pr_id = ? AND status = 'DONE' ORDER BY created_at DESC LIMIT 1") {
            it.setString(1, repoId)
            it.setLong(2, prId)
        }.firstOrNull() ?: latestFor(repoId, prId)

    fun latestFor(repoId: String, prId: Long): ReviewRecord? =
        query("WHERE repo_id = ? AND pr_id = ? ORDER BY created_at DESC LIMIT 1") {
            it.setString(1, repoId)
            it.setLong(2, prId)
        }.firstOrNull()

    /**
     * ¿Ya existe una review para ese commit exacto? El modo automático la usa para no repetir
     * trabajo — y, si la anterior falló o se canceló, para NO reintentar en bucle quemando plata.
     * Reintentar es decisión del usuario, con el botón.
     */
    fun existsForHead(repoId: String, prId: Long, headSha: String): Boolean =
        store.stmt("SELECT 1 FROM review WHERE repo_id = ? AND pr_id = ? AND head_sha = ? LIMIT 1") { ps ->
            ps.setString(1, repoId)
            ps.setLong(2, prId)
            ps.setString(3, headSha)
            ps.executeQuery().use { it.next() }
        }

    /**
     * La última review **terminada bien** de ese commit exacto, si existe.
     *
     * Sirve para avisar antes de repetir trabajo ya hecho: un commit sin cambios revisado dos
     * veces da el mismo resultado, cuesta lo mismo y hace esperar los mismos siete minutos.
     *
     * El filtro por DONE no es un detalle: de las 21 repeticiones que había en la base, la mitad
     * repetía una review que se había caído —casi siempre porque se cerró la app— y ahí repetir
     * es exactamente lo correcto. Avisar en ese caso sería estorbar justo cuando el usuario está
     * haciendo lo que hay que hacer.
     */
    fun doneForHead(repoId: String, prId: Long, headSha: String): PriorReview? =
        store.stmt(
            """SELECT r.id, r.created_at, r.cost_usd,
                      (SELECT COUNT(*) FROM finding f WHERE f.review_id = r.id)
                 FROM review r
                WHERE r.repo_id = ? AND r.pr_id = ? AND r.head_sha = ? AND r.status = ?
                ORDER BY r.created_at DESC LIMIT 1""",
        ) { ps ->
            ps.setString(1, repoId)
            ps.setLong(2, prId)
            ps.setString(3, headSha)
            ps.setString(4, ReviewStatus.DONE.name)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null
                else PriorReview(
                    id = rs.getString(1),
                    createdAt = rs.getString(2),
                    costUsd = rs.getDouble(3).takeIf { !rs.wasNull() },
                    findings = rs.getInt(4),
                )
            }
        }

    /**
     * Cierra las reviews que quedaron en RUNNING de una ejecución anterior.
     *
     * El estado "corriendo" vive en memoria del motor: si la app se cierra —o se mata— en medio
     * de una review, la fila queda en RUNNING para siempre y el panel la muestra como activa
     * aunque no haya ningún proceso detrás. Al arrancar, ninguna puede seguir viva.
     */
    /** Las que quedaron corriendo, con sus parámetros, para poder volver a lanzarlas. */
    fun orphanedRunning(): List<ReviewRecord> = query("WHERE status = 'RUNNING'") {}

    fun failOrphanedRunning(): Int =
        store.stmt(
            """UPDATE review SET status = 'FAILED',
                   error = COALESCE(error, 'Interrumpida: la app se cerró mientras corría. Se reanuda al abrir.'),
                   finished_at = ?
               WHERE status = 'RUNNING'""",
        ) { ps ->
            ps.setString(1, Instant.now().toString())
            ps.executeUpdate()
        }

    /**
     * Reviews terminadas que todavía esperan algo: lo accionable del dashboard.
     *
     * "Espera algo" no es sólo "sin publicar". Una review cuyos hallazgos están todos resueltos
     * —publicados o descartados a propósito— ya no tiene nada pendiente aunque nunca se haya
     * mandado el comentario resumen; contarla ahí dejaba PRs terminados en la lista para siempre.
     */
    fun readyToPublish(limit: Int = 50): List<ReviewRecord> =
        query(
            """WHERE status = 'DONE' AND published_url IS NULL
               AND created_at = (
                   SELECT MAX(created_at) FROM review r2
                   WHERE r2.repo_id = review.repo_id AND r2.pr_id = review.pr_id
                     AND r2.status = 'DONE'
               )
               AND (
                   NOT EXISTS (SELECT 1 FROM finding f WHERE f.review_id = review.id)
                   OR EXISTS (
                       SELECT 1 FROM finding f
                        WHERE f.review_id = review.id
                          AND f.published_id IS NULL AND f.dismissed_at IS NULL AND f.closed_at IS NULL
                   )
               )
               ORDER BY created_at DESC LIMIT $limit""",
        ) {}

    /**
     * PRs donde la pelota está del otro lado: publicamos, no queda ninguna respuesta por
     * contestar, y todavía no está todo verificado como corregido.
     *
     * Es la contraparte de "te respondieron". Sin separarlas, un PR donde ya contestaste todo
     * seguía apareciendo como si te tocara mover algo, cuando lo único que falta es que el otro
     * conteste o corrija.
     */
    fun awaitingThem(limit: Int = 50): List<ReviewRecord> =
        query(
            """WHERE status = 'DONE'
               AND created_at = (
                   SELECT MAX(created_at) FROM review r2
                   WHERE r2.repo_id = review.repo_id AND r2.pr_id = review.pr_id
                     AND r2.status = 'DONE'
               )
               -- Hay algo publicado esperando que lo corrijan o lo contesten…
               AND EXISTS (
                   SELECT 1 FROM finding f
                    WHERE f.review_id = review.id AND f.published_id IS NOT NULL
                      AND f.dismissed_at IS NULL AND f.closed_at IS NULL
                      AND (f.resolution IS NULL OR f.resolution <> 'RESOLVED')
               )
               -- …y nada esperando de nuestro lado.
               AND NOT EXISTS (
                   SELECT 1 FROM reply_draft d
                    WHERE d.repo_id = review.repo_id AND d.pr_id = review.pr_id
                      AND d.status <> 'PUBLISHED'
               )
               AND NOT EXISTS (
                   SELECT 1 FROM finding f
                    WHERE f.review_id = review.id AND f.published_id IS NULL
                      AND f.dismissed_at IS NULL AND f.closed_at IS NULL
               )
               ORDER BY created_at DESC LIMIT $limit""",
        ) {}

    /** Reviews ya publicadas en su PR. */
    fun published(limit: Int = 50): List<ReviewRecord> =
        query("WHERE published_url IS NOT NULL ORDER BY created_at DESC LIMIT $limit") {}

    /** Últimas reviews de todos los repos, para la actividad reciente. */
    fun recent(limit: Int = 30): List<ReviewRecord> =
        query("ORDER BY created_at DESC LIMIT $limit") {}

    data class PeriodStat(val period: String, val reviews: Int, val prs: Int, val cost: Double)

    /**
     * Reviews agrupadas por período. `prs` cuenta PRs distintos, que no es lo mismo que reviews:
     * re-revisar el mismo PR tras nuevos commits suma corridas pero no PRs.
     */
    fun statsByPeriod(unit: String, limit: Int = 12): List<PeriodStat> {
        val fmt = if (unit == "week") "%Y-S%W" else "%Y-%m"
        return store.stmt(
            """SELECT strftime('$fmt', created_at) AS period,
                      COUNT(*) AS reviews,
                      COUNT(DISTINCT repo_id || '#' || pr_id) AS prs,
                      COALESCE(SUM(cost_usd), 0) AS cost
               FROM review
               WHERE status = 'DONE'
               GROUP BY period ORDER BY period DESC LIMIT ?""",
        ) { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(PeriodStat(rs.getString(1) ?: "?", rs.getInt(2), rs.getInt(3), rs.getDouble(4)))
                    }
                }
            }
        }
    }

    data class Current(
        val todayReviews: Int, val todayPrs: Int,
        val weekReviews: Int, val weekPrs: Int,
        val monthReviews: Int, val monthPrs: Int,
        val today: String, val week: String, val month: String,
    )

    /**
     * Lo hecho en el período en curso: hoy, esta semana, este mes.
     *
     * Las comparaciones se hacen todas contra 'now' sin convertir zona horaria, para que el
     * corte del día sea el mismo criterio con el que se guardó el timestamp y no aparezca una
     * review "de mañana" por un desfase de husos.
     */
    fun currentPeriods(): Current = store.stmt(
        """SELECT
              COUNT(CASE WHEN date(created_at)=date('now') THEN 1 END),
              COUNT(DISTINCT CASE WHEN date(created_at)=date('now') THEN repo_id||'#'||pr_id END),
              COUNT(CASE WHEN strftime('%Y-%W',created_at)=strftime('%Y-%W','now') THEN 1 END),
              COUNT(DISTINCT CASE WHEN strftime('%Y-%W',created_at)=strftime('%Y-%W','now') THEN repo_id||'#'||pr_id END),
              COUNT(CASE WHEN strftime('%Y-%m',created_at)=strftime('%Y-%m','now') THEN 1 END),
              COUNT(DISTINCT CASE WHEN strftime('%Y-%m',created_at)=strftime('%Y-%m','now') THEN repo_id||'#'||pr_id END),
              date('now'), strftime('%W','now'), strftime('%Y-%m','now')
           FROM review WHERE status = 'DONE'""",
    ) { ps ->
        ps.executeQuery().use { rs ->
            if (rs.next()) Current(
                rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                rs.getString(7) ?: "", rs.getString(8) ?: "", rs.getString(9) ?: "",
            ) else Current(0, 0, 0, 0, 0, 0, "", "", "")
        }
    }

    data class Totals(
        val prsReviewed: Int,
        val reviews: Int,
        val published: Int,
        val auto: Int,
        val manual: Int,
        val failed: Int,
    )

    fun totals(): Totals = store.stmt(
        """SELECT COUNT(DISTINCT CASE WHEN status='DONE' THEN repo_id || '#' || pr_id END),
                  SUM(CASE WHEN status='DONE' THEN 1 ELSE 0 END),
                  SUM(CASE WHEN published_url IS NOT NULL THEN 1 ELSE 0 END),
                  SUM(CASE WHEN trigger_kind='AUTO' THEN 1 ELSE 0 END),
                  SUM(CASE WHEN trigger_kind!='AUTO' THEN 1 ELSE 0 END),
                  SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END)
           FROM review""",
    ) { ps ->
        ps.executeQuery().use { rs ->
            if (rs.next()) Totals(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6))
            else Totals(0, 0, 0, 0, 0, 0)
        }
    }

    data class Usage(val costUsd: Double, val tokens: Long)

    /**
     * Costo equivalente y tokens consumidos. El costo es lo que el CLI reporta como
     * `total_cost_usd`: con suscripción no es un cargo, es cuánto habría salido a precio de API.
     */
    fun usage(): Usage =
        store.stmt(
            """SELECT COALESCE(SUM(cost_usd), 0),
                      COALESCE(SUM(COALESCE(tokens_in,0) + COALESCE(tokens_out,0) +
                                   COALESCE(tokens_cache_read,0) + COALESCE(tokens_cache_write,0)), 0)
               FROM review""",
        ) { ps ->
            ps.executeQuery().use { if (it.next()) Usage(it.getDouble(1), it.getLong(2)) else Usage(0.0, 0) }
        }

    fun historyFor(repoId: String): List<ReviewRecord> =
        query("WHERE repo_id = ? ORDER BY created_at DESC LIMIT 100") { it.setString(1, repoId) }

    /**
     * El historial de UN PR. Antes se traían las últimas 100 del repo y se filtraba en memoria,
     * así que en un repo activo las reviews viejas de ese PR caían fuera del corte y el historial
     * mostraba menos de las que existían, sin avisar.
     */
    fun historyForPr(repoId: String, prId: Long): List<ReviewRecord> =
        query("WHERE repo_id = ? AND pr_id = ? ORDER BY created_at DESC LIMIT 100") {
            it.setString(1, repoId)
            it.setLong(2, prId)
        }

    private fun query(tail: String, bind: (java.sql.PreparedStatement) -> Unit): List<ReviewRecord> =
        store.stmt(
            """SELECT id, repo_id, pr_id, pr_title, head_sha, status, body, error,
                      session_id, cost_usd, published_url, created_at, depth, project_kind, model,
                      trigger_kind, denied_tools, resolution_summary, resolution_at,
                      resolution_head, final_pass_head, final_pass_summary, final_pass_blockers
               FROM review $tail""",
        ) { ps ->
            bind(ps)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        ReviewRecord(
                            id = rs.getString(1),
                            repoId = rs.getString(2),
                            prId = rs.getLong(3),
                            prTitle = rs.getString(4),
                            headSha = rs.getString(5),
                            status = ReviewStatus.valueOf(rs.getString(6)),
                            body = rs.getString(7),
                            error = rs.getString(8),
                            sessionId = rs.getString(9),
                            costUsd = rs.getObject(10)?.let { rs.getDouble(10) },
                            publishedUrl = rs.getString(11),
                            createdAt = rs.getString(12),
                            depth = rs.getString(13)?.let {
                                runCatching { io.acr.claude.ReviewDepth.valueOf(it) }.getOrNull()
                            },
                            projectKind = rs.getString(14)?.let {
                                runCatching { io.acr.claude.ProjectKind.valueOf(it) }.getOrNull()
                            },
                            model = rs.getString(15),
                            auto = rs.getString(16) == "AUTO",
                            deniedTools = rs.getString(17),
                            resolutionSummary = rs.getString(18),
                            resolutionAt = rs.getString(19),
                            resolutionHead = rs.getString(20),
                            finalPassHead = rs.getString(21),
                            finalPassSummary = rs.getString(22),
                            finalPassBlockers = rs.getInt(23),
                        ),
                    )
                }
            }
        }
}

data class PublicationRecord(
    val id: String,
    val reviewId: String,
    val prId: Long,
    val commentId: String?,
    val url: String?,
    val body: String,
    val publishedAt: String,
)

/**
 * Immutable log of every review we published. Re-publishing appends a row instead of
 * overwriting, so the record of what was actually said, and when, survives edits.
 */
class PublicationRepository(private val store: Store) {

    fun record(
        reviewId: String,
        repoId: String,
        prId: Long,
        commentId: String?,
        url: String?,
        body: String,
    ): String {
        val id = UlidCreator.getUlid().toString()
        store.stmt(
            """INSERT INTO publication(id, review_id, repo_id, pr_id, comment_id, url, body, published_at)
               VALUES (?,?,?,?,?,?,?,?)""",
        ) { ps ->
            ps.setString(1, id)
            ps.setString(2, reviewId)
            ps.setString(3, repoId)
            ps.setLong(4, prId)
            ps.setString(5, commentId)
            ps.setString(6, url)
            ps.setString(7, body)
            ps.setString(8, Instant.now().toString())
            ps.executeUpdate()
        }
        return id
    }

    fun forPr(repoId: String, prId: Long): List<PublicationRecord> =
        store.stmt(
            """SELECT id, review_id, pr_id, comment_id, url, body, published_at
               FROM publication WHERE repo_id = ? AND pr_id = ? ORDER BY published_at DESC""",
        ) { ps ->
            ps.setString(1, repoId)
            ps.setLong(2, prId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        PublicationRecord(
                            rs.getString(1), rs.getString(2), rs.getLong(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        ),
                    )
                }
            }
        }

    fun countFor(repoId: String): Int =
        store.stmt("SELECT COUNT(*) FROM publication WHERE repo_id = ?") { ps ->
            ps.setString(1, repoId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
}

data class StoredComment(
    val commentId: String,
    val author: String,
    val body: String,
    val inlinePath: String?,
    val inlineLine: Int?,
    val deleted: Boolean,
    val ours: Boolean,
    val createdOn: String,
    val parentId: String?,
)

/** Snapshot of a PR's comment thread, refreshed on demand. */
class PrCommentRepository(private val store: Store) {

    /** Upserts by (repo, pr, commentId): a re-sync updates bodies and flips deleted flags. */
    fun sync(repoId: String, prId: Long, comments: List<io.acr.forge.PrComment>, ourCommentIds: Set<String>) {
        val now = Instant.now().toString()
        store.transaction { conn ->
            comments.forEach { c ->
                conn.prepareStatement(
                    """INSERT INTO pr_comment(id, repo_id, pr_id, comment_id, author, body,
                                              inline_path, inline_line, is_deleted, is_ours, created_on, synced_at,
                                              parent_id)
                       VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                       ON CONFLICT(repo_id, pr_id, comment_id) DO UPDATE SET
                           body = excluded.body,
                           is_deleted = excluded.is_deleted,
                           is_ours = MAX(pr_comment.is_ours, excluded.is_ours),
                           synced_at = excluded.synced_at""",
                ).use { ps ->
                    ps.setString(1, UlidCreator.getUlid().toString())
                    ps.setString(2, repoId)
                    ps.setLong(3, prId)
                    ps.setString(4, c.commentId)
                    ps.setString(5, c.author)
                    ps.setString(6, c.body)
                    ps.setString(7, c.inlinePath)
                    if (c.inlineLine == null) ps.setNull(8, java.sql.Types.INTEGER) else ps.setInt(8, c.inlineLine)
                    ps.setInt(9, if (c.deleted) 1 else 0)
                    ps.setInt(10, if (ourCommentIds.contains(c.commentId)) 1 else 0)
                    ps.setString(11, c.createdOn)
                    ps.setString(12, now)
                    ps.setString(13, c.parentId)
                    ps.executeUpdate()
                }
            }
        }
    }

    /**
     * Cómo nos nombra el proveedor: el autor más frecuente entre los comentarios que sabemos
     * nuestros.
     *
     * Hace falta porque las acciones sobre el PR —aprobar, pedir cambios— se guardan con un
     * nombre, y usar "nosotros" creaba una persona fantasma: la misma aprobación aparecía dos
     * veces, una como "nosotros" y otra como "Viktor Karpyuk" cuando el sync la traía de la API.
     *
     * Null si todavía no publicamos nada en ningún PR.
     */
    fun ourDisplayName(): String? =
        store.stmt(
            """SELECT author FROM pr_comment WHERE is_ours = 1
               GROUP BY author ORDER BY COUNT(*) DESC LIMIT 1""",
        ) { ps ->
            ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    fun forPr(repoId: String, prId: Long, includeDeleted: Boolean = false): List<StoredComment> {
        val filter = if (includeDeleted) "" else " AND is_deleted = 0"
        return store.stmt(
            """SELECT comment_id, author, body, inline_path, inline_line, is_deleted, is_ours,
                      created_on, parent_id
               FROM pr_comment WHERE repo_id = ? AND pr_id = ?$filter ORDER BY created_on""",
        ) { ps ->
            ps.setString(1, repoId)
            ps.setLong(2, prId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        StoredComment(
                            commentId = rs.getString(1),
                            author = rs.getString(2),
                            body = rs.getString(3),
                            inlinePath = rs.getString(4),
                            inlineLine = rs.getObject(5)?.let { rs.getInt(5) },
                            deleted = rs.getInt(6) == 1,
                            ours = rs.getInt(7) == 1,
                            createdOn = rs.getString(8),
                            parentId = rs.getString(9),
                        ),
                    )
                }
            }
        }
    }
}

data class LocalNote(
    val id: String,
    val prId: Long,
    val filePath: String,
    val lineNo: Int?,
    val body: String,
    val publishedId: String?,
    val publishedUrl: String?,
    val createdAt: String,
)

/** Comentarios propios anclados a archivo:línea, antes de mandarlos al PR. */
class LocalNoteRepository(private val store: Store) {

    fun add(repoId: String, prId: Long, filePath: String, lineNo: Int?, body: String): String {
        val id = UlidCreator.getUlid().toString()
        store.stmt(
            """INSERT INTO local_note(id, repo_id, pr_id, file_path, line_no, side, body, created_at)
               VALUES (?,?,?,?,?,'NEW',?,?)""",
        ) { ps ->
            ps.setString(1, id)
            ps.setString(2, repoId)
            ps.setLong(3, prId)
            ps.setString(4, filePath)
            if (lineNo == null) ps.setNull(5, java.sql.Types.INTEGER) else ps.setInt(5, lineNo)
            ps.setString(6, body)
            ps.setString(7, Instant.now().toString())
            ps.executeUpdate()
        }
        return id
    }

    fun update(id: String, body: String) {
        store.stmt("UPDATE local_note SET body = ? WHERE id = ?") { ps ->
            ps.setString(1, body)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    fun delete(id: String) {
        store.stmt("DELETE FROM local_note WHERE id = ?") { ps ->
            ps.setString(1, id)
            ps.executeUpdate()
        }
    }

    fun markPublished(id: String, commentId: String, url: String?) {
        store.stmt("UPDATE local_note SET published_id = ?, published_url = ? WHERE id = ?") { ps ->
            ps.setString(1, commentId)
            ps.setString(2, url)
            ps.setString(3, id)
            ps.executeUpdate()
        }
    }

    /** Cuántas notas propias quedan sin publicar, por PR. Para la lista, sin una consulta por fila. */
    fun unpublishedCountsByPr(repoId: String): Map<Long, Int> =
        store.stmt(
            """SELECT pr_id, COUNT(*) FROM local_note
               WHERE repo_id = ? AND published_id IS NULL GROUP BY pr_id""",
        ) { ps ->
            ps.setString(1, repoId)
            ps.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getLong(1), rs.getInt(2)) } }
        }

    fun forPr(repoId: String, prId: Long): List<LocalNote> =
        store.stmt(
            """SELECT id, pr_id, file_path, line_no, body, published_id, created_at, published_url
               FROM local_note WHERE repo_id = ? AND pr_id = ? ORDER BY file_path, line_no, created_at""",
        ) { ps ->
            ps.setString(1, repoId)
            ps.setLong(2, prId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        LocalNote(
                            id = rs.getString(1),
                            prId = rs.getLong(2),
                            filePath = rs.getString(3),
                            lineNo = rs.getObject(4)?.let { rs.getInt(4) },
                            body = rs.getString(5),
                            publishedId = rs.getString(6),
                            createdAt = rs.getString(7),
                            publishedUrl = rs.getString(8),
                        ),
                    )
                }
            }
        }
}

data class Finding(
    val id: String,
    val reviewId: String,
    val prId: Long,
    val filePath: String,
    val lineNo: Int?,
    val severity: String,
    val title: String,
    val body: String,
    val publishedId: String?,
    val publishedUrl: String?,
    /** Cuándo se decidió no publicarlo. Descartado cuenta como resuelto, igual que publicado. */
    val dismissedAt: String? = null,
    /** Último error al intentar publicarlo. Se guarda para que no se pierda con la pantalla. */
    val publishError: String? = null,
    /** Veredicto de la verificación: si lo señalado se corrigió. Null = todavía no se analizó. */
    val resolution: Resolution? = null,
    /** La evidencia: qué cambió y dónde. Es lo que permite discutir el veredicto en vez de creerlo. */
    val resolutionNote: String? = null,
    /** Cuándo se mandó el último recordatorio por falta de respuesta. */
    val followedUpAt: String? = null,
    /** Cerrado en la conversación: se habló y no espera ningún cambio en el código. */
    val closedAt: String? = null,
    /** Cómo debería resolverse, si la review pudo proponer algo que sostenga. */
    val suggestion: String? = null,
) {
    /** Ya no espera nada: se publicó o se descartó a propósito. */
    val settled: Boolean get() = publishedId != null || dismissedAt != null || closedAt != null

    /** Cerrado del todo: descartado, cerrado en la conversación, o verificado como corregido. */
    val closed: Boolean get() = dismissedAt != null || closedAt != null ||
        (publishedId != null && resolution == Resolution.RESOLVED)
}

/** Qué pasó con un hallazgo después de publicarlo. */
enum class Resolution { RESOLVED, PARTIAL, UNRESOLVED }

/** Hallazgos de una review, cada uno anclado a su archivo y, cuando aplica, a su línea. */
class FindingRepository(private val store: Store) {

    fun replaceForReview(reviewId: String, repoId: String, prId: Long, findings: List<Finding>) {
        store.transaction { conn ->
            conn.prepareStatement("DELETE FROM finding WHERE review_id = ?").use { ps ->
                ps.setString(1, reviewId)
                ps.executeUpdate()
            }
            findings.forEach { f ->
                conn.prepareStatement(
                    """INSERT INTO finding(id, review_id, repo_id, pr_id, file_path, line_no,
                                           severity, title, body, created_at, suggestion)
                       VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                ).use { ps ->
                    ps.setString(1, UlidCreator.getUlid().toString())
                    ps.setString(2, reviewId)
                    ps.setString(3, repoId)
                    ps.setLong(4, prId)
                    ps.setString(5, f.filePath)
                    if (f.lineNo == null) ps.setNull(6, java.sql.Types.INTEGER) else ps.setInt(6, f.lineNo)
                    ps.setString(7, f.severity)
                    ps.setString(8, f.title)
                    ps.setString(9, f.body)
                    ps.setString(10, Instant.now().toString())
                    ps.setString(11, f.suggestion)
                    ps.executeUpdate()
                }
            }
        }
    }

    /** Por qué no se pudo publicar. Se guarda para que el hallazgo lo diga y se pueda reintentar. */
    fun failPublish(id: String, error: String) {
        store.stmt("UPDATE finding SET publish_error = ? WHERE id = ?") { ps ->
            ps.setString(1, error.take(500))
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    /** Cierra el hilo sin esperar cambios: se habló y quedó saldado. Se puede reabrir. */
    fun close(id: String, closed: Boolean) {
        store.stmt("UPDATE finding SET closed_at = ? WHERE id = ?") { ps ->
            ps.setString(1, if (closed) Instant.now().toString() else null)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    /** Deja registrado que se mandó un recordatorio, para no volver a insistir al día siguiente. */
    fun markFollowedUp(id: String) {
        store.stmt("UPDATE finding SET followed_up_at = ? WHERE id = ?") { ps ->
            ps.setString(1, Instant.now().toString())
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    /** Guarda el veredicto de la verificación y su evidencia. */
    fun setResolution(id: String, resolution: Resolution, note: String?) {
        store.stmt("UPDATE finding SET resolution = ?, resolution_note = ? WHERE id = ?") { ps ->
            ps.setString(1, resolution.name)
            ps.setString(2, note?.take(1_000))
            ps.setString(3, id)
            ps.executeUpdate()
        }
    }

    /** Descartar: se decidió no publicarlo. Deja de contar como pendiente sin borrar nada. */
    fun dismiss(id: String) {
        store.stmt("UPDATE finding SET dismissed_at = ? WHERE id = ?") { ps ->
            ps.setString(1, Instant.now().toString())
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    fun restore(id: String) {
        store.stmt("UPDATE finding SET dismissed_at = NULL WHERE id = ?") { ps ->
            ps.setString(1, id)
            ps.executeUpdate()
        }
    }

    fun markPublished(id: String, commentId: String, url: String?) {
        store.stmt(
            "UPDATE finding SET published_id = ?, published_url = ?, publish_error = NULL WHERE id = ?",
        ) { ps ->
            ps.setString(1, commentId)
            ps.setString(2, url)
            ps.setString(3, id)
            ps.executeUpdate()
        }
    }

    fun forReview(reviewId: String): List<Finding> = query("WHERE review_id = ?") { it.setString(1, reviewId) }

    fun forPr(repoId: String, prId: Long): List<Finding> =
        query("WHERE repo_id = ? AND pr_id = ?") { it.setString(1, repoId); it.setLong(2, prId) }

    /**
     * Sólo los hallazgos de la review vigente del PR.
     *
     * `forPr` devuelve los de TODAS las reviews, y como cada corrida crea un review_id nuevo, los
     * viejos nunca se borran. Al superponerlos sobre el diff actual quedaban anclados a números
     * de línea de un diff anterior —es decir, señalando el código equivocado— y encima seguían
     * siendo publicables como si fueran vigentes.
     *
     * "Vigente" es la última DONE, con la última corrida como respaldo: exactamente el mismo
     * criterio que [ReviewRepository.latestUsableFor], y tiene que seguir siéndolo. Antes acá se
     * tomaba la última de cualquier estado: si la corrida más nueva fallaba, la vista de código
     * mostraba cero hallazgos mientras la de Review mostraba los de la última buena, y publicar
     * desde una no se reflejaba en la otra.
     */
    fun forLatestReview(repoId: String, prId: Long): List<Finding> =
        query(
            """WHERE repo_id = ? AND pr_id = ? AND review_id = COALESCE(
                   (SELECT id FROM review WHERE repo_id = ? AND pr_id = ? AND status = 'DONE'
                     ORDER BY created_at DESC LIMIT 1),
                   (SELECT id FROM review WHERE repo_id = ? AND pr_id = ?
                     ORDER BY created_at DESC LIMIT 1)
               )""",
        ) {
            it.setString(1, repoId); it.setLong(2, prId)
            it.setString(3, repoId); it.setLong(4, prId)
            it.setString(5, repoId); it.setLong(6, prId)
        }

    private fun query(tail: String, bind: (java.sql.PreparedStatement) -> Unit): List<Finding> =
        store.stmt(
            """SELECT id, review_id, pr_id, file_path, line_no, severity, title, body, published_id,
                      published_url, dismissed_at, publish_error, resolution, resolution_note,
                      followed_up_at, closed_at, suggestion
               FROM finding $tail ORDER BY file_path, line_no""",
        ) { ps ->
            bind(ps)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        Finding(
                            id = rs.getString(1),
                            reviewId = rs.getString(2),
                            prId = rs.getLong(3),
                            filePath = rs.getString(4),
                            lineNo = rs.getObject(5)?.let { rs.getInt(5) },
                            severity = rs.getString(6),
                            title = rs.getString(7),
                            body = rs.getString(8),
                            publishedId = rs.getString(9),
                            publishedUrl = rs.getString(10),
                            dismissedAt = rs.getString(11),
                            publishError = rs.getString(12),
                            resolution = rs.getString(13)?.let {
                                runCatching { Resolution.valueOf(it) }.getOrNull()
                            },
                            resolutionNote = rs.getString(14),
                            followedUpAt = rs.getString(15),
                            closedAt = rs.getString(16),
                            suggestion = rs.getString(17),
                        ),
                    )
                }
            }
        }
}

enum class ReplyStatus { PENDING, DRAFTED, FAILED, PUBLISHED }

data class ReplyDraft(
    val id: String,
    val repoId: String,
    val prId: Long,
    val theirCommentId: String,
    val theirAuthor: String,
    val theirBody: String,
    val ourCommentId: String?,
    val ourBody: String?,
    val filePath: String?,
    val lineNo: Int?,
    val body: String?,
    val status: ReplyStatus,
    val error: String?,
    val publishedId: String?,
    val publishedUrl: String?,
    val createdAt: String,
    /** Cerrada sin contestar: no toda respuesta pide una contestación. */
    val dismissedAt: String? = null,
) {
    /** Ya no espera nada nuestro: se contestó o se dio por cerrada. */
    val settled: Boolean get() = status == ReplyStatus.PUBLISHED || dismissedAt != null
}

/**
 * Respuestas que alguien dejó a un comentario nuestro, y la contestación que preparamos.
 *
 * Se guarda como borrador y nunca se publica sola: la respuesta a una objeción técnica es
 * exactamente el momento en que conviene que haya una persona decidiendo qué se dice.
 */
class ReplyRepository(private val store: Store) {

    /** Registra respuestas nuevas. Ignora las ya conocidas por la clave única. */
    fun registerIfNew(
        repoId: String,
        prId: Long,
        theirCommentId: String,
        theirAuthor: String,
        theirBody: String,
        ourCommentId: String?,
        ourBody: String?,
        filePath: String?,
        lineNo: Int?,
    ): Boolean = store.stmt(
        """INSERT OR IGNORE INTO reply_draft(id, repo_id, pr_id, their_comment_id, their_author,
                their_body, our_comment_id, our_body, file_path, line_no, status, created_at)
           VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
    ) { ps ->
        ps.setString(1, UlidCreator.getUlid().toString())
        ps.setString(2, repoId)
        ps.setLong(3, prId)
        ps.setString(4, theirCommentId)
        ps.setString(5, theirAuthor)
        ps.setString(6, theirBody)
        ps.setString(7, ourCommentId)
        ps.setString(8, ourBody)
        ps.setString(9, filePath)
        if (lineNo == null) ps.setNull(10, java.sql.Types.INTEGER) else ps.setInt(10, lineNo)
        ps.setString(11, ReplyStatus.PENDING.name)
        ps.setString(12, Instant.now().toString())
        ps.executeUpdate() > 0
    }

    fun saveDraft(id: String, body: String, costUsd: Double?) {
        store.stmt("UPDATE reply_draft SET body = ?, status = ?, error = NULL, cost_usd = ? WHERE id = ?") { ps ->
            ps.setString(1, body)
            ps.setString(2, ReplyStatus.DRAFTED.name)
            if (costUsd == null) ps.setNull(3, java.sql.Types.REAL) else ps.setDouble(3, costUsd)
            ps.setString(4, id)
            ps.executeUpdate()
        }
    }

    fun fail(id: String, error: String) {
        store.stmt("UPDATE reply_draft SET status = ?, error = ? WHERE id = ?") { ps ->
            ps.setString(1, ReplyStatus.FAILED.name)
            ps.setString(2, error)
            ps.setString(3, id)
            ps.executeUpdate()
        }
    }

    fun markPublished(id: String, commentId: String, url: String?) {
        store.stmt(
            "UPDATE reply_draft SET status = ?, published_id = ?, published_url = ? WHERE id = ?",
        ) { ps ->
            ps.setString(1, ReplyStatus.PUBLISHED.name)
            ps.setString(2, commentId)
            ps.setString(3, url)
            ps.setString(4, id)
            ps.executeUpdate()
        }
    }

    /** Da por cerrada una respuesta sin contestarla. Reversible. */
    fun dismiss(id: String, cerrada: Boolean) {
        store.stmt("UPDATE reply_draft SET dismissed_at = ? WHERE id = ?") { ps ->
            ps.setString(1, if (cerrada) Instant.now().toString() else null)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    /** Cierra todas las que quedan de un PR. Con 24 pendientes, una por una no es una opción. */
    fun dismissAllForPr(repoId: String, prId: Long): Int =
        store.stmt(
            """UPDATE reply_draft SET dismissed_at = ?
               WHERE repo_id = ? AND pr_id = ? AND status != 'PUBLISHED' AND dismissed_at IS NULL""",
        ) { ps ->
            ps.setString(1, Instant.now().toString())
            ps.setString(2, repoId)
            ps.setLong(3, prId)
            ps.executeUpdate()
        }

    fun updateBody(id: String, body: String) {
        store.stmt("UPDATE reply_draft SET body = ? WHERE id = ?") { ps ->
            ps.setString(1, body)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    fun forPr(repoId: String, prId: Long): List<ReplyDraft> =
        query("WHERE repo_id = ? AND pr_id = ?") { it.setString(1, repoId); it.setLong(2, prId) }

    /** Todo lo que espera atención: respuestas sin contestar o con borrador sin publicar. */
    fun openOnes(): List<ReplyDraft> =
        query(
            """WHERE status IN ('PENDING','DRAFTED','FAILED') AND dismissed_at IS NULL
               ORDER BY created_at DESC LIMIT 50""",
        ) {}

    fun get(id: String): ReplyDraft? = query("WHERE id = ?") { it.setString(1, id) }.firstOrNull()

    /** Cuántas respuestas esperan contestación, por PR. Alimenta el estado de la lista. */
    /** Todas las respuestas de un repo, sin importar el PR. */
    fun forPr2(repoId: String): List<ReplyDraft> = query("WHERE repo_id = ?") { it.setString(1, repoId) }

    fun openCountsByPr(repoId: String): Map<Long, Int> =
        store.stmt(
            """SELECT pr_id, COUNT(*) FROM reply_draft
               WHERE repo_id = ? AND status != 'PUBLISHED' AND dismissed_at IS NULL
               GROUP BY pr_id""",
        ) { ps ->
            ps.setString(1, repoId)
            ps.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(rs.getLong(1), rs.getInt(2)) }
            }
        }

    /** Ids de los comentarios que publicamos como respuesta, para marcarlos como nuestros. */
    fun publishedIds(repoId: String, prId: Long): Set<String> =
        store.stmt(
            """SELECT published_id FROM reply_draft
               WHERE repo_id = ? AND pr_id = ? AND published_id IS NOT NULL""",
        ) { ps ->
            ps.setString(1, repoId)
            ps.setLong(2, prId)
            ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }

    /** PRs donde ya contestamos todo, para distinguir "cerrado" de "sin respuestas". */
    fun answeredPrs(repoId: String): Set<Long> =
        store.stmt(
            "SELECT DISTINCT pr_id FROM reply_draft WHERE repo_id = ? AND status = 'PUBLISHED'",
        ) { ps ->
            ps.setString(1, repoId)
            ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getLong(1)) } }
        }

    private fun query(tail: String, bind: (java.sql.PreparedStatement) -> Unit): List<ReplyDraft> =
        store.stmt(
            """SELECT id, repo_id, pr_id, their_comment_id, their_author, their_body,
                      our_comment_id, our_body, file_path, line_no, body, status, error,
                      published_url, created_at, published_id, dismissed_at
               FROM reply_draft $tail""",
        ) { ps ->
            bind(ps)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        ReplyDraft(
                            id = rs.getString(1),
                            repoId = rs.getString(2),
                            prId = rs.getLong(3),
                            theirCommentId = rs.getString(4),
                            theirAuthor = rs.getString(5),
                            theirBody = rs.getString(6),
                            ourCommentId = rs.getString(7),
                            ourBody = rs.getString(8),
                            filePath = rs.getString(9),
                            lineNo = rs.getObject(10)?.let { rs.getInt(10) },
                            body = rs.getString(11),
                            status = runCatching { ReplyStatus.valueOf(rs.getString(12)) }
                                .getOrDefault(ReplyStatus.PENDING),
                            error = rs.getString(13),
                            publishedUrl = rs.getString(14),
                            createdAt = rs.getString(15),
                            publishedId = rs.getString(16),
                            dismissedAt = rs.getString(17),
                        ),
                    )
                }
            }
        }
}

/** PRs ya conocidos, para avisar sólo cuando aparece uno nuevo. */
class SeenPrRepository(private val store: Store) {

    fun isFirstSweep(repoId: String): Boolean =
        store.stmt("SELECT 1 FROM seen_pr WHERE repo_id = ? LIMIT 1") { ps ->
            ps.setString(1, repoId)
            ps.executeQuery().use { !it.next() }
        }

    /** Registra y devuelve los que no estaban. */
    fun registerNew(repoId: String, prs: List<io.acr.forge.PullRequest>): List<io.acr.forge.PullRequest> {
        val known = store.stmt("SELECT pr_id FROM seen_pr WHERE repo_id = ?") { ps ->
            ps.setString(1, repoId)
            ps.executeQuery().use { rs -> buildSet<Long> { while (rs.next()) add(rs.getLong(1)) } }
        }
        val fresh = prs.filter { it.id !in known }
        if (fresh.isEmpty()) return emptyList()
        store.transaction { conn ->
            fresh.forEach { pr ->
                conn.prepareStatement(
                    "INSERT OR IGNORE INTO seen_pr(repo_id, pr_id, title, author, first_seen_at) VALUES (?,?,?,?,?)",
                ).use { ps ->
                    ps.setString(1, repoId)
                    ps.setLong(2, pr.id)
                    ps.setString(3, pr.title)
                    ps.setString(4, pr.author)
                    ps.setString(5, Instant.now().toString())
                    ps.executeUpdate()
                }
            }
        }
        return fresh
    }
}

/**
 * Última lista de PRs conocida por repo, con su ETag.
 *
 * Sirve para dos cosas: mostrar la lista al instante mientras se revalida, y ahorrarse la
 * descarga cuando el proveedor contesta 304.
 */
class PrCacheRepository(private val store: Store) {

    data class Cached(val prs: List<io.acr.forge.PullRequest>, val etag: String?, val fetchedAt: String?)

    fun get(repoId: String): Cached {
        val prs = store.stmt(
            """SELECT pr_id, title, author, source, target, head_sha, comments, updated_on, url,
                      is_draft, created_on
               FROM pr_cache WHERE repo_id = ? ORDER BY pr_id DESC""",
        ) { ps ->
            ps.setString(1, repoId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        io.acr.forge.PullRequest(
                            id = rs.getLong(1), title = rs.getString(2), author = rs.getString(3),
                            sourceBranch = rs.getString(4), targetBranch = rs.getString(5),
                            headSha = rs.getString(6), commentCount = rs.getInt(7),
                            updatedOn = rs.getString(8), url = rs.getString(9),
                            isDraft = rs.getInt(10) == 1,
                            createdOn = rs.getString(11) ?: "",
                        ),
                    )
                }
            }
        }
        val meta = store.stmt("SELECT etag, fetched_at FROM pr_cache_meta WHERE repo_id = ?") { ps ->
            ps.setString(1, repoId)
            ps.executeQuery().use { if (it.next()) it.getString(1) to it.getString(2) else null to null }
        }
        return Cached(prs, meta.first, meta.second)
    }

    /** Reemplaza la lista entera: un PR que se cerró tiene que desaparecer, no quedar pegado. */
    fun put(repoId: String, prs: List<io.acr.forge.PullRequest>, etag: String?) {
        store.transaction { conn ->
            conn.prepareStatement("DELETE FROM pr_cache WHERE repo_id = ?").use { ps ->
                ps.setString(1, repoId); ps.executeUpdate()
            }
            prs.forEach { pr ->
                conn.prepareStatement(
                    """INSERT INTO pr_cache(repo_id, pr_id, title, author, source, target, head_sha,
                                             comments, updated_on, url, is_draft, created_on)
                       VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                ).use { ps ->
                    ps.setString(1, repoId); ps.setLong(2, pr.id); ps.setString(3, pr.title)
                    ps.setString(4, pr.author); ps.setString(5, pr.sourceBranch)
                    ps.setString(6, pr.targetBranch); ps.setString(7, pr.headSha)
                    ps.setInt(8, pr.commentCount); ps.setString(9, pr.updatedOn)
                    ps.setString(10, pr.url); ps.setInt(11, if (pr.isDraft) 1 else 0)
                    ps.setString(12, pr.createdOn)
                    ps.executeUpdate()
                }
            }
            conn.prepareStatement(
                """INSERT INTO pr_cache_meta(repo_id, etag, fetched_at) VALUES (?,?,?)
                   ON CONFLICT(repo_id) DO UPDATE SET etag = excluded.etag, fetched_at = excluded.fetched_at""",
            ).use { ps ->
                ps.setString(1, repoId); ps.setString(2, etag)
                ps.setString(3, Instant.now().toString()); ps.executeUpdate()
            }
        }
    }

    fun touch(repoId: String) {
        store.stmt("UPDATE pr_cache_meta SET fetched_at = ? WHERE repo_id = ?") { ps ->
            ps.setString(1, Instant.now().toString()); ps.setString(2, repoId); ps.executeUpdate()
        }
    }
}

class PrefsRepo(private val store: Store) {
    fun get(key: String): String? =
        store.stmt("SELECT v FROM pref WHERE k = ?") { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    fun put(key: String, value: String) {
        store.stmt(
            "INSERT INTO pref(k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v",
        ) { ps ->
            ps.setString(1, key)
            ps.setString(2, value)
            ps.executeUpdate()
        }
    }
}

/**
 * Qué opinó cada persona sobre un PR. Excluyentes, como los modela el proveedor.
 *
 * [NONE] es participar sin pronunciarse: figura en el PR pero todavía no aprobó ni pidió cambios.
 * Existe para poder mostrarlo —un círculo gris dice "está, no opinó" mejor que una ausencia— pero
 * **no cuenta como pronunciamiento** en ninguna regla.
 */
enum class ReviewStance { APPROVED, CHANGES_REQUESTED, NONE }

/** Quién se pronunció sobre cada pull request, hasta donde sabemos. */
data class PrApproval(
    val prId: Long,
    val approvedBy: String,
    val byUs: Boolean,
    val approvedAt: String,
    val stance: ReviewStance = ReviewStance.APPROVED,
)

/**
 * Aprobaciones conocidas.
 *
 * "Hasta donde sabemos" no es una salvedad menor: el listado de PRs no trae las aprobaciones, así
 * que sólo se registran las que hicimos nosotros y las que se ven al abrir un PR. Un PR aprobado
 * por otro que nunca abrimos no figura acá, y eso es preferible a pagar una llamada por fila.
 */
class ApprovalRepository(private val store: Store) {

    fun record(
        repoId: String,
        prId: Long,
        by: String,
        byUs: Boolean,
        stance: ReviewStance = ReviewStance.APPROVED,
    ) {
        store.stmt(
            """INSERT INTO pr_approval(repo_id, pr_id, approved_by, by_us, approved_at, state)
               VALUES (?,?,?,?,?,?)
               ON CONFLICT(repo_id, pr_id, approved_by) DO UPDATE SET
                   by_us = MAX(pr_approval.by_us, excluded.by_us),
                   state = excluded.state,
                   approved_at = excluded.approved_at""",
        ) { ps ->
            ps.setString(1, repoId)
            ps.setLong(2, prId)
            ps.setString(3, by)
            ps.setInt(4, if (byUs) 1 else 0)
            ps.setString(5, Instant.now().toString())
            ps.setString(6, stance.name)
            ps.executeUpdate()
        }
    }

    /** Borra lo que opinamos: al retirar una aprobación o un pedido de cambios. */
    fun clearOurs(repoId: String, prId: Long) {
        store.stmt("DELETE FROM pr_approval WHERE repo_id = ? AND pr_id = ? AND by_us = 1") { ps ->
            ps.setString(1, repoId); ps.setLong(2, prId); ps.executeUpdate()
        }
    }

    /** Se borran las que ya no están: alguien puede retirar su aprobación. */
    fun sync(
        repoId: String,
        prId: Long,
        aprobadores: List<String>,
        pidieronCambios: List<String> = emptyList(),
        sinPronunciarse: List<String> = emptyList(),
    ) {
        store.transaction { conn ->
            conn.prepareStatement(
                "DELETE FROM pr_approval WHERE repo_id = ? AND pr_id = ? AND by_us = 0",
            ).use { ps ->
                ps.setString(1, repoId); ps.setLong(2, prId); ps.executeUpdate()
            }
            val todos = aprobadores.map { it to ReviewStance.APPROVED } +
                pidieronCambios.map { it to ReviewStance.CHANGES_REQUESTED } +
                sinPronunciarse.map { it to ReviewStance.NONE }
            todos.forEach { (quien, postura) ->
                conn.prepareStatement(
                    """INSERT INTO pr_approval(repo_id, pr_id, approved_by, by_us, approved_at, state)
                       VALUES (?,?,?,0,?,?) ON CONFLICT DO NOTHING""",
                ).use { ps ->
                    ps.setString(1, repoId); ps.setLong(2, prId)
                    ps.setString(3, quien); ps.setString(4, Instant.now().toString())
                    ps.setString(5, postura.name)
                    ps.executeUpdate()
                }
            }
        }
    }

    /** Por PR: quiénes aprobaron. Una consulta para toda la lista, no una por fila. */
    fun byPr(repoId: String): Map<Long, List<PrApproval>> =
        store.stmt(
            """SELECT pr_id, approved_by, by_us, approved_at, state FROM pr_approval
               WHERE repo_id = ? ORDER BY approved_at""",
        ) { ps ->
            ps.setString(1, repoId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            PrApproval(
                                rs.getLong(1), rs.getString(2), rs.getInt(3) == 1, rs.getString(4),
                                runCatching { ReviewStance.valueOf(rs.getString(5)) }
                                    .getOrDefault(ReviewStance.APPROVED),
                            ),
                        )
                    }
                }
            }.groupBy { it.prId }
        }

    fun forPr(repoId: String, prId: Long): List<PrApproval> =
        byPr(repoId)[prId].orEmpty()

    /**
     * Sólo quienes se pronunciaron. Es lo que miran las reglas —saltear la revisión, el badge de
     * la lista—: contar a quien todavía no opinó como si hubiera aprobado sería exactamente al
     * revés de lo que significa.
     */
    fun statedByPr(repoId: String): Map<Long, List<PrApproval>> =
        byPr(repoId).mapValues { (_, v) -> v.filter { it.stance != ReviewStance.NONE } }
            .filterValues { it.isNotEmpty() }
}

/** Una review que quedó a medias y hay que volver a correr. */
data class PendingJob(
    val id: String,
    val repoId: String,
    val prId: Long,
    val depth: io.acr.claude.ReviewDepth?,
    val kind: io.acr.claude.ProjectKind?,
    val model: String,
    val auto: Boolean,
    val attempts: Int,
)

/**
 * Trabajo pendiente de retomar tras cerrar la app.
 *
 * No es una cola de tareas general: es exactamente el conjunto de reviews que estaban corriendo
 * cuando el proceso murió. El subproceso de Claude Code se fue con su contexto, así que lo que se
 * guarda son los parámetros para volver a lanzarla, no un estado a medio camino.
 */
class PendingJobRepository(private val store: Store) {

    fun enqueue(
        repoId: String,
        prId: Long,
        depth: io.acr.claude.ReviewDepth?,
        kind: io.acr.claude.ProjectKind?,
        model: String,
        auto: Boolean,
    ) {
        store.stmt(
            """INSERT INTO pending_job(id, repo_id, pr_id, depth, kind, model, auto, created_at)
               VALUES (?,?,?,?,?,?,?,?)
               ON CONFLICT(repo_id, pr_id) DO UPDATE SET attempts = pending_job.attempts + 1""",
        ) { ps ->
            ps.setString(1, UlidCreator.getUlid().toString())
            ps.setString(2, repoId)
            ps.setLong(3, prId)
            ps.setString(4, depth?.name)
            ps.setString(5, kind?.name)
            ps.setString(6, model)
            ps.setInt(7, if (auto) 1 else 0)
            ps.setString(8, Instant.now().toString())
            ps.executeUpdate()
        }
    }

    fun remove(repoId: String, prId: Long) {
        store.stmt("DELETE FROM pending_job WHERE repo_id = ? AND pr_id = ?") { ps ->
            ps.setString(1, repoId); ps.setLong(2, prId); ps.executeUpdate()
        }
    }

    /**
     * Los que todavía vale la pena reintentar.
     *
     * Con un tope de intentos: si una review revienta siempre —un repo que ya no está, un binario
     * roto— reintentarla en cada arranque sería un bucle que gasta plata y nunca termina.
     */
    fun pending(maxAttempts: Int = 3): List<PendingJob> =
        store.stmt(
            """SELECT id, repo_id, pr_id, depth, kind, model, auto, attempts
               FROM pending_job WHERE attempts < ? ORDER BY created_at""",
        ) { ps ->
            ps.setInt(1, maxAttempts)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        PendingJob(
                            id = rs.getString(1),
                            repoId = rs.getString(2),
                            prId = rs.getLong(3),
                            depth = rs.getString(4)?.let {
                                runCatching { io.acr.claude.ReviewDepth.valueOf(it) }.getOrNull()
                            },
                            kind = rs.getString(5)?.let {
                                runCatching { io.acr.claude.ProjectKind.valueOf(it) }.getOrNull()
                            },
                            model = rs.getString(6).orEmpty(),
                            auto = rs.getInt(7) == 1,
                            attempts = rs.getInt(8),
                        ),
                    )
                }
            }
        }

    /** Los que agotaron los intentos, para poder decirlo en vez de que desaparezcan. */
    fun givenUp(maxAttempts: Int = 3): Int =
        store.stmt("SELECT COUNT(*) FROM pending_job WHERE attempts >= ?") { ps ->
            ps.setInt(1, maxAttempts)
            ps.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
        }
}

/** Un documento de convenciones o de arquitectura que la review tiene que respetar. */
data class Guideline(
    val id: String,
    val repoId: String?,
    val name: String,
    val content: String,
    val enabled: Boolean,
    val source: String?,
    val createdAt: String,
    /** Archivo del que se importó, si vino de uno. El contenido de arriba sigue siendo el que manda. */
    val linkedPath: String? = null,
    /** Huella del contenido al importarlo, para saber si el archivo cambió desde entonces. */
    val linkedHash: String? = null,
) {
    /** Aplica a todos los repositorios. */
    val global: Boolean get() = repoId == null
}

/**
 * Convenciones y guías propias del equipo.
 *
 * Existen porque sin ellas la review marca como problema lo que es una decisión tomada: cómo se
 * nombran las interfaces, dónde va la lógica, qué patrón usa cada capa. Un revisor que no las
 * conoce genera ruido, y el ruido hace que se deje de leer lo que dice.
 *
 * El contenido se guarda acá y no como ruta a un archivo: con una ruta, mover o borrar el archivo
 * cambiaría en silencio el criterio con el que se revisa, y nadie se enteraría hasta ver una
 * review rara.
 */
class GuidelineRepository(private val store: Store) {

    fun add(repoId: String?, name: String, content: String, source: String?): String {
        val id = UlidCreator.getUlid().toString()
        store.stmt(
            """INSERT INTO guideline(id, repo_id, name, content, enabled, source, created_at)
               VALUES (?,?,?,?,1,?,?)""",
        ) { ps ->
            ps.setString(1, id)
            ps.setString(2, repoId)
            ps.setString(3, name)
            ps.setString(4, content)
            ps.setString(5, source)
            ps.setString(6, Instant.now().toString())
            ps.executeUpdate()
        }
        return id
    }

    /**
     * Importa un documento encontrado en el repositorio, o actualiza el que ya se había importado
     * de ese mismo archivo.
     *
     * Actualiza en vez de duplicar porque el caso normal es re-importar el mismo CLAUDE.md después
     * de que cambió: dos filas del mismo archivo mandarían el criterio dos veces al prompt, y
     * gastarían dos veces del tope de contexto para decir lo mismo.
     *
     * Conserva el `enabled` de la fila existente: si estaba apagada, actualizar el texto no es
     * motivo para volver a encenderla.
     */
    fun importFromFile(repoId: String?, guide: RepoGuide): String {
        val hash = guideHash(guide.content)
        val existente = store.stmt(
            "SELECT id FROM guideline WHERE linked_path = ? AND (repo_id IS ? OR repo_id = ?) LIMIT 1",
        ) { ps ->
            ps.setString(1, guide.path)
            ps.setString(2, repoId)
            ps.setString(3, repoId)
            ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }
        if (existente != null) {
            store.stmt(
                "UPDATE guideline SET name = ?, content = ?, source = ?, linked_hash = ? WHERE id = ?",
            ) { ps ->
                ps.setString(1, guide.name)
                ps.setString(2, guide.content)
                ps.setString(3, guide.path)
                ps.setString(4, hash)
                ps.setString(5, existente)
                ps.executeUpdate()
            }
            return existente
        }
        val id = UlidCreator.getUlid().toString()
        store.stmt(
            """INSERT INTO guideline(id, repo_id, name, content, enabled, source, created_at,
                                     linked_path, linked_hash)
               VALUES (?,?,?,?,1,?,?,?,?)""",
        ) { ps ->
            ps.setString(1, id)
            ps.setString(2, repoId)
            ps.setString(3, guide.name)
            ps.setString(4, guide.content)
            ps.setString(5, guide.path)
            ps.setString(6, Instant.now().toString())
            ps.setString(7, guide.path)
            ps.setString(8, hash)
            ps.executeUpdate()
        }
        return id
    }

    fun setEnabled(id: String, enabled: Boolean) {
        store.stmt("UPDATE guideline SET enabled = ? WHERE id = ?") { ps ->
            ps.setInt(1, if (enabled) 1 else 0)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    fun delete(id: String) {
        store.stmt("DELETE FROM guideline WHERE id = ?") { ps ->
            ps.setString(1, id); ps.executeUpdate()
        }
    }

    /** Los globales más los del repositorio. Los globales primero: son el marco general. */
    fun forRepo(repoId: String, onlyEnabled: Boolean = true): List<Guideline> =
        query(
            "WHERE (repo_id IS NULL OR repo_id = ?)" +
                (if (onlyEnabled) " AND enabled = 1" else "") +
                " ORDER BY repo_id IS NOT NULL, created_at",
        ) { it.setString(1, repoId) }

    fun globals(): List<Guideline> = query("WHERE repo_id IS NULL ORDER BY created_at") {}

    private fun query(tail: String, bind: (java.sql.PreparedStatement) -> Unit): List<Guideline> =
        store.stmt(
            "SELECT id, repo_id, name, content, enabled, source, created_at, linked_path, linked_hash " +
                "FROM guideline $tail",
        ) { ps ->
            bind(ps)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        Guideline(
                            id = rs.getString(1),
                            repoId = rs.getString(2),
                            name = rs.getString(3),
                            content = rs.getString(4),
                            enabled = rs.getInt(5) == 1,
                            source = rs.getString(6),
                            createdAt = rs.getString(7),
                            linkedPath = rs.getString(8),
                            linkedHash = rs.getString(9),
                        ),
                    )
                }
            }
        }
}
