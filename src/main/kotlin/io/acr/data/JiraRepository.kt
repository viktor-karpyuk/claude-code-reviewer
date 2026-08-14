package io.acr.data

import io.acr.jira.JiraIssue
import java.time.Instant

/**
 * Tickets de Jira, guardados.
 *
 * Se cachean porque el contenido de un ticket casi no cambia mientras el pull request está abierto,
 * y pedirlo en cada apertura de pantalla sería una llamada por PR contra un servicio que no
 * controlamos. Refrescar es una acción explícita.
 */
class JiraRepository(private val store: Store) {

    fun save(issue: JiraIssue) {
        store.stmt(
            """INSERT OR REPLACE INTO jira_issue(key, summary, description, type, status,
                     assignee, url, fetched_at)
               VALUES (?,?,?,?,?,?,?,?)""",
        ) { ps ->
            ps.setString(1, issue.key)
            ps.setString(2, issue.summary)
            ps.setString(3, issue.description)
            ps.setString(4, issue.type)
            ps.setString(5, issue.status)
            ps.setString(6, issue.assignee)
            ps.setString(7, issue.url)
            ps.setString(8, Instant.now().toString())
            ps.executeUpdate()
        }
    }

    fun get(key: String): JiraIssue? =
        store.stmt(
            "SELECT key, summary, description, type, status, assignee, url FROM jira_issue WHERE key = ?",
        ) { ps ->
            ps.setString(1, key)
            ps.executeQuery().use {
                if (!it.next()) null
                else JiraIssue(
                    it.getString(1), it.getString(2), it.getString(3),
                    it.getString(4).orEmpty(), it.getString(5).orEmpty(),
                    it.getString(6), it.getString(7).orEmpty(),
                )
            }
        }

    /**
     * Deja registrados los tickets de un PR, reemplazando los anteriores.
     *
     * Reemplaza en vez de sumar porque la rama o el título pueden corregirse: si sólo se agregara,
     * un ticket puesto por error quedaría pegado al PR para siempre.
     */
    fun link(repoId: String, prId: Long, keys: List<String>) {
        store.transaction { conn ->
            conn.prepareStatement("DELETE FROM pr_issue WHERE repo_id = ? AND pr_id = ?").use { ps ->
                ps.setString(1, repoId); ps.setLong(2, prId); ps.executeUpdate()
            }
            keys.forEach { k ->
                conn.prepareStatement(
                    "INSERT OR IGNORE INTO pr_issue(repo_id, pr_id, key) VALUES (?,?,?)",
                ).use { ps ->
                    ps.setString(1, repoId); ps.setLong(2, prId); ps.setString(3, k)
                    ps.executeUpdate()
                }
            }
        }
    }

    fun keysOf(repoId: String, prId: Long): List<String> =
        store.stmt("SELECT key FROM pr_issue WHERE repo_id = ? AND pr_id = ? ORDER BY key") { ps ->
            ps.setString(1, repoId)
            ps.setLong(2, prId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    /** Los tickets de un PR con su contenido, salteando los que todavía no se trajeron. */
    fun issuesOf(repoId: String, prId: Long): List<JiraIssue> = keysOf(repoId, prId).mapNotNull { get(it) }

    /**
     * Cuánto trabajo movió cada ticket.
     *
     * Cruza los tickets con los PRs que los mencionan: cuántos pull requests, y cuántas líneas
     * cambiaron esos PRs. Es la pregunta de "¿cuánto costó esto?" que un ticket solo no contesta.
     */
    fun workByIssue(): List<IssueWork> =
        store.stmt(
            """SELECT i.key, COALESCE(j.summary, ''), COUNT(DISTINCT i.repo_id || '|' || i.pr_id),
                      COALESCE(SUM(s.commits_after_review), 0)
                 FROM pr_issue i
                 LEFT JOIN jira_issue j ON j.key = i.key
                 LEFT JOIN pr_stat s ON s.repo_id = i.repo_id AND s.pr_id = i.pr_id
                GROUP BY i.key ORDER BY 3 DESC, i.key""",
        ) { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(IssueWork(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4)))
                }
            }
        }
}

/** Cuánto movió un ticket: en cuántos PRs apareció y cuántos commits de corrección tuvieron. */
data class IssueWork(val key: String, val summary: String, val prs: Int, val reworkCommits: Int)
