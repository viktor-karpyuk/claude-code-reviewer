package io.acr.data

import java.time.LocalDate

/**
 * El estado de un repositorio en un momento dado.
 *
 * Las cuatro primeras son **deuda de revisión**: trabajo empezado que espera algo nuestro. Están
 * juntas porque responden la única pregunta que uno tiene al abrir la lista de repositorios —¿cuál
 * me está esperando?— y porque separadas cada una parece chica: en esta instalación son 69
 * respuestas sin contestar y 51 hallazgos publicados que nadie verificó, repartidos de a poco.
 *
 * @param findingsPerPr densidad de hallazgos. Dice más del repositorio y de la profundidad
 *   configurada que de quien escribió el código: 23,2 por PR en un backend contra 3,0 en otro no
 *   significa que uno esté peor programado, significa que se revisan distinto.
 * @param costPerPr lo que cuesta revisar ahí. Varía cien veces entre repositorios de la misma
 *   instalación, y es el número que explica en qué se va el consumo.
 */
data class RepoHealth(
    val openPrs: Int,
    val pendingReplies: Int,
    val unverified: Int,
    val unpublished: Int,
    val openFindings: Int,
    val reviewedPrs: Int,
    val costUsd: Double,
    val oldestPrDays: Long?,
    val commits: Int,
) {
    /** Todo lo que espera una acción nuestra. Es el número que decide a qué repositorio entrar. */
    val debt: Int get() = pendingReplies + unverified + unpublished

    val findingsPerPr: Double get() = if (reviewedPrs == 0) 0.0 else openFindings.toDouble() / reviewedPrs
    val costPerPr: Double get() = if (reviewedPrs == 0) 0.0 else costUsd / reviewedPrs
}

/** Un día de historia de un repositorio. */
data class RepoSnapshot(
    val day: String,
    val openPrs: Int,
    val pendingReplies: Int,
    val unverified: Int,
    val unpublished: Int,
    val openFindings: Int,
    val costUsd: Double,
)

/**
 * Salud de cada repositorio: lo de ahora, calculado, y lo de antes, guardado.
 *
 * Lo de ahora se consulta cada vez porque cambia con cada acción y guardarlo lo dejaría viejo. Lo
 * de antes se guarda una vez por día, que es la única forma de saber si algo viene subiendo.
 */
class RepoHealthRepository(private val store: Store) {

    fun current(repoId: String, today: LocalDate = LocalDate.now()): RepoHealth =
        store.stmt(
            """SELECT
                 (SELECT COUNT(*) FROM pr_cache WHERE repo_id = ?),
                 (SELECT COUNT(*) FROM reply_draft
                   WHERE repo_id = ? AND published_id IS NULL AND dismissed_at IS NULL),
                 (SELECT COUNT(*) FROM finding f JOIN review v ON v.id = f.review_id
                   WHERE v.repo_id = ? AND f.published_id IS NOT NULL AND f.resolution IS NULL
                     AND f.dismissed_at IS NULL AND f.closed_at IS NULL),
                 (SELECT COUNT(DISTINCT pr_id) FROM review
                   WHERE repo_id = ? AND status = 'DONE' AND published_url IS NULL),
                 (SELECT COUNT(*) FROM finding f JOIN review v ON v.id = f.review_id
                   WHERE v.repo_id = ? AND f.dismissed_at IS NULL),
                 (SELECT COUNT(DISTINCT pr_id) FROM review WHERE repo_id = ? AND status = 'DONE'),
                 (SELECT COALESCE(SUM(cost_usd), 0) FROM review WHERE repo_id = ?),
                 (SELECT MIN(created_on) FROM pr_cache WHERE repo_id = ?),
                 (SELECT COUNT(*) FROM commit_stat WHERE repo_id = ?)""",
        ) { ps ->
            repeat(9) { ps.setString(it + 1, repoId) }
            ps.executeQuery().use { rs ->
                if (!rs.next()) RepoHealth(0, 0, 0, 0, 0, 0, 0.0, null, 0)
                else RepoHealth(
                    openPrs = rs.getInt(1),
                    pendingReplies = rs.getInt(2),
                    unverified = rs.getInt(3),
                    unpublished = rs.getInt(4),
                    openFindings = rs.getInt(5),
                    reviewedPrs = rs.getInt(6),
                    costUsd = rs.getDouble(7),
                    // Del PR más viejo interesa hace cuánto espera, no la fecha: nadie calcula de
                    // cabeza cuántos días pasaron desde una fecha, y menos con varios repos.
                    oldestPrDays = rs.getString(8)?.take(10)?.let { f ->
                        runCatching {
                            java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(f), today)
                                .coerceAtLeast(0)
                        }.getOrNull()
                    },
                    commits = rs.getInt(9),
                )
            }
        }

    /**
     * Guarda la foto del día, si no está.
     *
     * Se pisa la del mismo día en vez de acumular: dentro de una jornada la deuda sube y baja con
     * cada acción, y lo que interesa es dónde quedó. Guardar cada variación llenaría la tabla para
     * dibujar exactamente la misma línea.
     */
    fun snapshot(repoId: String, h: RepoHealth, today: LocalDate = LocalDate.now()) {
        store.stmt(
            """INSERT OR REPLACE INTO repo_snapshot(repo_id, day, open_prs, pending_replies,
                     unverified, unpublished, open_findings, cost_usd)
               VALUES (?,?,?,?,?,?,?,?)""",
        ) { ps ->
            ps.setString(1, repoId)
            ps.setString(2, today.toString())
            ps.setInt(3, h.openPrs)
            ps.setInt(4, h.pendingReplies)
            ps.setInt(5, h.unverified)
            ps.setInt(6, h.unpublished)
            ps.setInt(7, h.openFindings)
            ps.setDouble(8, h.costUsd)
            ps.executeUpdate()
        }
    }

    /** Los últimos [dias] días guardados, del más viejo al más nuevo. */
    fun history(repoId: String, dias: Int = 30): List<RepoSnapshot> =
        store.stmt(
            """SELECT day, open_prs, pending_replies, unverified, unpublished, open_findings, cost_usd
                 FROM repo_snapshot WHERE repo_id = ?
                ORDER BY day DESC LIMIT ?""",
        ) { ps ->
            ps.setString(1, repoId)
            ps.setInt(2, dias)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            RepoSnapshot(
                                rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                                rs.getInt(5), rs.getInt(6), rs.getDouble(7),
                            ),
                        )
                    }
                }
            }.reversed()
        }
}
