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

    fun markPublished(id: String, url: String) {
        store.stmt("UPDATE review SET published_url = ? WHERE id = ?") { ps ->
            ps.setString(1, url)
            ps.setString(2, id)
            ps.executeUpdate()
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
     * Cierra las reviews que quedaron en RUNNING de una ejecución anterior.
     *
     * El estado "corriendo" vive en memoria del motor: si la app se cierra —o se mata— en medio
     * de una review, la fila queda en RUNNING para siempre y el panel la muestra como activa
     * aunque no haya ningún proceso detrás. Al arrancar, ninguna puede seguir viva.
     */
    fun failOrphanedRunning(): Int =
        store.stmt(
            """UPDATE review SET status = 'FAILED',
                   error = COALESCE(error, 'Interrumpida: la app se cerró mientras corría.'),
                   finished_at = ?
               WHERE status = 'RUNNING'""",
        ) { ps ->
            ps.setString(1, Instant.now().toString())
            ps.executeUpdate()
        }

    /** Reviews terminadas que todavía no se publicaron: lo accionable del dashboard. */
    fun readyToPublish(limit: Int = 50): List<ReviewRecord> =
        query("WHERE status = 'DONE' AND published_url IS NULL ORDER BY created_at DESC LIMIT $limit") {}

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
                      trigger_kind, denied_tools
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
)

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
                                           severity, title, body, created_at)
                       VALUES (?,?,?,?,?,?,?,?,?,?)""",
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
                    ps.executeUpdate()
                }
            }
        }
    }

    fun markPublished(id: String, commentId: String, url: String?) {
        store.stmt("UPDATE finding SET published_id = ?, published_url = ? WHERE id = ?") { ps ->
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
     * Sólo los hallazgos de la review más reciente del PR.
     *
     * `forPr` devuelve los de TODAS las reviews, y como cada corrida crea un review_id nuevo, los
     * viejos nunca se borran. Al superponerlos sobre el diff actual quedaban anclados a números
     * de línea de un diff anterior —es decir, señalando el código equivocado— y encima seguían
     * siendo publicables como si fueran vigentes.
     */
    fun forLatestReview(repoId: String, prId: Long): List<Finding> =
        query(
            """WHERE repo_id = ? AND pr_id = ? AND review_id = (
                   SELECT id FROM review WHERE repo_id = ? AND pr_id = ?
                   ORDER BY created_at DESC LIMIT 1
               )""",
        ) {
            it.setString(1, repoId); it.setLong(2, prId)
            it.setString(3, repoId); it.setLong(4, prId)
        }

    private fun query(tail: String, bind: (java.sql.PreparedStatement) -> Unit): List<Finding> =
        store.stmt(
            """SELECT id, review_id, pr_id, file_path, line_no, severity, title, body, published_id,
                      published_url
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
    val publishedUrl: String?,
    val createdAt: String,
)

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
        query("WHERE status IN ('PENDING','DRAFTED','FAILED') ORDER BY created_at DESC LIMIT 50") {}

    fun get(id: String): ReplyDraft? = query("WHERE id = ?") { it.setString(1, id) }.firstOrNull()

    /** Cuántas respuestas esperan contestación, por PR. Alimenta el estado de la lista. */
    /** Todas las respuestas de un repo, sin importar el PR. */
    fun forPr2(repoId: String): List<ReplyDraft> = query("WHERE repo_id = ?") { it.setString(1, repoId) }

    fun openCountsByPr(repoId: String): Map<Long, Int> =
        store.stmt(
            """SELECT pr_id, COUNT(*) FROM reply_draft
               WHERE repo_id = ? AND status != 'PUBLISHED' GROUP BY pr_id""",
        ) { ps ->
            ps.setString(1, repoId)
            ps.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(rs.getLong(1), rs.getInt(2)) }
            }
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
                      published_url, created_at
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
                        ),
                    )
                }
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
