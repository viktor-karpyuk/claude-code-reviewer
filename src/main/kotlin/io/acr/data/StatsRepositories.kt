package io.acr.data

import com.github.f4b6a3.ulid.UlidCreator
import io.acr.stats.CommitStat
import io.acr.stats.GitAuthor
import io.acr.stats.Identity
import io.acr.stats.IdentityKind
import io.acr.stats.Person
import io.acr.stats.matchAuthor
import io.acr.stats.normalizeEmail
import io.acr.stats.normalizeName
import java.time.Instant

/**
 * Personas e identidades: quién es quién antes de contar nada.
 *
 * Los agregados no viven acá porque no se guardan. Se calculan por consulta cada vez, a propósito:
 * un total guardado queda viejo apenas se fusionan dos identidades, y un número que a veces miente
 * es peor que no tenerlo.
 */
class PersonRepository(private val store: Store) {

    fun all(): List<Person> {
        val identidades = store.stmt(
            "SELECT person_id, kind, value, confirmed FROM person_identity",
        ) { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Triple(
                                rs.getString(1),
                                Identity(IdentityKind.valueOf(rs.getString(2)), rs.getString(3)),
                                rs.getInt(4) == 1,
                            ),
                        )
                    }
                }
            }
        }
        val porPersona = identidades.groupBy { it.first }
        return store.stmt("SELECT id, display_name, is_bot, archived FROM person ORDER BY display_name") { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = rs.getString(1)
                        val propias = porPersona[id].orEmpty()
                        add(
                            Person(
                                id = id,
                                displayName = rs.getString(2),
                                identities = propias.map { it.second },
                                isBot = rs.getInt(3) == 1,
                                archived = rs.getInt(4) == 1,
                                // Sin confirmar = la unió la heurística y nadie la revisó todavía.
                                autoMerged = propias.any { !it.third },
                            ),
                        )
                    }
                }
            }
        }
    }

    fun create(displayName: String, identities: List<Identity>, confirmed: Boolean = false): String {
        val id = UlidCreator.getUlid().toString()
        store.transaction { conn ->
            conn.prepareStatement(
                "INSERT INTO person(id, display_name, is_bot, created_at) VALUES (?,?,0,?)",
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, displayName)
                ps.setString(3, Instant.now().toString())
                ps.executeUpdate()
            }
            identities.forEach { addIdentity(conn, id, it, confirmed) }
        }
        return id
    }

    private fun addIdentity(
        conn: java.sql.Connection,
        personId: String,
        identity: Identity,
        confirmed: Boolean,
    ) {
        if (identity.value.isBlank()) return
        // Una identidad pertenece a una sola persona: el UNIQUE(kind,value) lo garantiza, y el
        // OR IGNORE evita que redescubrir la misma identidad reviente la recolección entera.
        conn.prepareStatement(
            "INSERT OR IGNORE INTO person_identity(id, person_id, kind, value, confirmed) VALUES (?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, UlidCreator.getUlid().toString())
            ps.setString(2, personId)
            ps.setString(3, identity.kind.name)
            ps.setString(4, identity.value)
            ps.setInt(5, if (confirmed) 1 else 0)
            ps.executeUpdate()
        }
    }

    fun addIdentity(personId: String, identity: Identity, confirmed: Boolean = false) {
        store.transaction { conn -> addIdentity(conn, personId, identity, confirmed) }
    }

    /** De quién es esta identidad, si ya es de alguien. */
    private fun ownerOf(identity: Identity): String? =
        store.stmt("SELECT person_id FROM person_identity WHERE kind = ? AND value = ?") { ps ->
            ps.setString(1, identity.kind.name)
            ps.setString(2, identity.value)
            ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    /**
     * Suma una identidad, y si ya pertenecía a otra persona, une las dos.
     *
     * Sin esto la resolución depende del orden en que aparecen los commits, y con datos reales se
     * ve: "Lautaro Eloy Islas <lautaro@empresa>" crea una persona, "Lautaro <personal@gmail>" crea
     * otra porque todavía no coincide con nada, y recién después "Lautaro <lautaro@empresa>"
     * intenta agregarle el alias corto a la primera. Ese alias ya es de la segunda, así que el
     * `UNIQUE` lo rechaza y la unión nunca ocurre — el mismo equipo daría números distintos según
     * qué repositorio se lea primero.
     *
     * Encontrar la identidad tomada no es un conflicto: es la prueba de que las dos personas son
     * la misma, que es exactamente lo que hay que hacer con ella.
     */
    private fun addIdentityOrMerge(personId: String, identity: Identity): String {
        if (identity.value.isBlank()) return personId
        val duenio = ownerOf(identity)
        if (duenio == null) {
            addIdentity(personId, identity)
            return personId
        }
        if (duenio == personId) return personId
        // Gana la que más identidades tiene: suele ser la cuenta principal, y así el nombre que
        // queda es el más usado en vez del que apareció primero.
        val a = identityIds(personId).size
        val b = identityIds(duenio).size
        val (queda, absorbida) = if (a >= b) personId to duenio else duenio to personId
        merge(queda, absorbida)
        return queda
    }

    /**
     * Resuelve un autor de git a una persona, creándola si hace falta.
     *
     * Es el punto donde la heurística se vuelve permanente, así que guarda también las identidades
     * nuevas del autor: la próxima vez que aparezca ese alias ya no hay que adivinar.
     */
    fun resolve(author: GitAuthor, cache: MutableList<Person>): String {
        val email = normalizeEmail(author.email)
        val name = normalizeName(author.name)
        if (email.isBlank() && name.isBlank()) return ""

        val personId = when (val m = matchAuthor(author, cache)) {
            // El alias nuevo puede ser de otra persona ya creada: entonces son la misma y se unen.
            is io.acr.stats.Match.ByEmail ->
                if (name.isBlank()) m.personId
                else addIdentityOrMerge(m.personId, Identity(IdentityKind.GIT_NAME, name))
            is io.acr.stats.Match.ByName ->
                if (email.isBlank()) m.personId
                else addIdentityOrMerge(m.personId, Identity(IdentityKind.GIT_EMAIL, email))
            io.acr.stats.Match.New -> create(
                author.name.trim().ifBlank { email },
                buildList {
                    if (email.isNotBlank()) add(Identity(IdentityKind.GIT_EMAIL, email))
                    if (name.isNotBlank()) add(Identity(IdentityKind.GIT_NAME, name))
                },
            )
        }
        // El cache en memoria evita releer la tabla por cada uno de los miles de commits. Si hubo
        // una fusión, la persona absorbida ya no existe: se saca del cache o la próxima búsqueda
        // devolvería un id borrado y los commits quedarían apuntando a la nada.
        val nuevas = buildList {
            if (email.isNotBlank()) add(Identity(IdentityKind.GIT_EMAIL, email))
            if (name.isNotBlank()) add(Identity(IdentityKind.GIT_NAME, name))
        }
        val absorbidas = cache.filter { p ->
            p.id != personId && (p.emails().any { it in nuevas.map { n -> n.value } } ||
                p.names().any { it in nuevas.map { n -> n.value } })
        }
        if (absorbidas.isNotEmpty()) {
            val heredadas = absorbidas.flatMap { it.identities }
            cache.removeAll(absorbidas)
            val j = cache.indexOfFirst { it.id == personId }
            if (j >= 0) cache[j] = cache[j].copy(identities = (cache[j].identities + heredadas + nuevas).distinct())
            else cache += Person(personId, author.name.trim(), (heredadas + nuevas).distinct())
            return personId
        }
        val i = cache.indexOfFirst { it.id == personId }
        if (i >= 0) {
            cache[i] = cache[i].copy(identities = (cache[i].identities + nuevas).distinct())
        } else {
            cache += Person(personId, author.name.trim(), nuevas)
        }
        return personId
    }

    /**
     * Une dos personas en una.
     *
     * Las identidades de la absorbida pasan a la que queda y se marcan confirmadas: fue una
     * decisión humana, no una corazonada de la heurística. Los commits se repuntan en la misma
     * transacción — si quedaran apuntando a una persona borrada, desaparecerían de todos los
     * totales sin que nadie lo pida.
     */
    fun merge(keepId: String, absorbId: String) {
        if (keepId == absorbId) return
        store.transaction { conn ->
            conn.prepareStatement(
                "UPDATE person_identity SET person_id = ?, confirmed = 1 WHERE person_id = ?",
            ).use { ps -> ps.setString(1, keepId); ps.setString(2, absorbId); ps.executeUpdate() }
            conn.prepareStatement("UPDATE commit_stat SET person_id = ? WHERE person_id = ?")
                .use { ps -> ps.setString(1, keepId); ps.setString(2, absorbId); ps.executeUpdate() }
            conn.prepareStatement("DELETE FROM person WHERE id = ?")
                .use { ps -> ps.setString(1, absorbId); ps.executeUpdate() }
        }
    }

    /**
     * Saca una identidad de su persona y la pone en una nueva.
     *
     * Es el deshacer de una fusión equivocada. Los commits que vinieron por esa identidad se
     * repuntan a la persona nueva, porque si no la separación sería sólo cosmética: los números
     * seguirían contando igual que antes.
     */
    fun split(identityId: String, newDisplayName: String): String? {
        val datos = store.stmt("SELECT kind, value FROM person_identity WHERE id = ?") { ps ->
            ps.setString(1, identityId)
            ps.executeQuery().use { if (it.next()) it.getString(1) to it.getString(2) else null }
        } ?: return null

        val nuevo = create(newDisplayName, emptyList(), confirmed = true)
        store.transaction { conn ->
            conn.prepareStatement("UPDATE person_identity SET person_id = ?, confirmed = 1 WHERE id = ?")
                .use { ps -> ps.setString(1, nuevo); ps.setString(2, identityId); ps.executeUpdate() }
            if (datos.first == IdentityKind.GIT_EMAIL.name) {
                conn.prepareStatement("UPDATE commit_stat SET person_id = ? WHERE author_email = ?")
                    .use { ps -> ps.setString(1, nuevo); ps.setString(2, datos.second); ps.executeUpdate() }
            }
        }
        return nuevo
    }

    /**
     * Marca a alguien como fuera del equipo.
     *
     * No borra nada: los commits y los PRs siguen donde estaban. Borrar dejaría los commits sin
     * persona y haría bajar los totales del equipo en trimestres viejos donde esa persona sí
     * estuvo, sin que nadie entienda por qué.
     */
    fun setArchived(personId: String, archived: Boolean) {
        store.stmt("UPDATE person SET archived = ? WHERE id = ?") { ps ->
            ps.setInt(1, if (archived) 1 else 0)
            ps.setString(2, personId)
            ps.executeUpdate()
        }
    }

    fun setBot(personId: String, isBot: Boolean) {
        store.stmt("UPDATE person SET is_bot = ? WHERE id = ?") { ps ->
            ps.setInt(1, if (isBot) 1 else 0)
            ps.setString(2, personId)
            ps.executeUpdate()
        }
    }

    fun rename(personId: String, name: String) {
        store.stmt("UPDATE person SET display_name = ? WHERE id = ?") { ps ->
            ps.setString(1, name)
            ps.setString(2, personId)
            ps.executeUpdate()
        }
    }

    /** Da por revisadas todas las identidades: es lo que apaga el aviso de datos provisionales. */
    fun confirmAll() {
        store.stmt("UPDATE person_identity SET confirmed = 1") { it.executeUpdate() }
    }

    fun identityIds(personId: String): List<Pair<String, Identity>> =
        store.stmt("SELECT id, kind, value FROM person_identity WHERE person_id = ? ORDER BY kind, value") { ps ->
            ps.setString(1, personId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(rs.getString(1) to Identity(IdentityKind.valueOf(rs.getString(2)), rs.getString(3)))
                    }
                }
            }
        }

    /** Quedan identidades que nadie revisó: mientras tanto los números van marcados como provisionales. */
    fun hasUnconfirmed(): Boolean =
        store.stmt("SELECT 1 FROM person_identity WHERE confirmed = 0 LIMIT 1") { ps ->
            ps.executeQuery().use { it.next() }
        }
}

/**
 * Lo que sale de nuestras reviews: qué se le señaló a cada uno y quién participó revisando.
 *
 * Todo lo de acá es **cobertura parcial**: sólo existe para los pull requests que la app revisó o
 * sincronizó. Mezclarlo con el volumen de git —que cubre todo lo commiteado— sin decirlo sería
 * mentir, así que cada consulta devuelve también sobre cuánto se calculó.
 */
class ReviewStatsRepository(private val store: Store) {

    /**
     * Cuántos comentarios dejó cada persona, y en cuántos PRs.
     *
     * Es la métrica que evita que el módulo mida sólo a quien escribe código y trate como
     * invisible a quien revisa. Se atribuye por el nombre que muestra el proveedor, que en estos
     * repositorios normaliza igual que el de git —"Tomás Rivero" y "Tomas Rivero" caen en la misma
     * persona— así que engancha sin configurar nada.
     *
     * @param excludeOurs deja afuera lo que publicó la app en nuestro nombre: son comentarios
     *   nuestros pero no son participación de una persona revisando.
     */
    fun commentsByAuthor(desde: String, hasta: String, excludeOurs: Boolean = true): Map<String, Participation> =
        store.stmt(
            """SELECT author, COUNT(*), COUNT(DISTINCT repo_id || '|' || pr_id)
                 FROM pr_comment
                WHERE is_deleted = 0 AND created_on >= ? AND created_on <= ?
                  ${if (excludeOurs) "AND is_ours = 0" else ""}
                GROUP BY author""",
        ) { ps ->
            ps.setString(1, desde)
            ps.setString(2, hasta)
            ps.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) put(rs.getString(1), Participation(rs.getInt(2), rs.getInt(3)))
                }
            }
        }

    /**
     * Hallazgos que recibió el autor de cada PR, por gravedad.
     *
     * Sólo de la review vigente de cada PR: las superadas dejaron hallazgos que ya se arrastraron
     * o se cerraron, y contarlas sumaría dos veces el mismo problema.
     */
    fun findingsByPrAuthor(desde: String, hasta: String): Map<String, SeverityCount> =
        store.stmt(
            """SELECT r.pr_author,
                      SUM(CASE WHEN f.severity = 'blocker' THEN 1 ELSE 0 END),
                      SUM(CASE WHEN f.severity = 'major'   THEN 1 ELSE 0 END),
                      SUM(CASE WHEN f.severity = 'minor'   THEN 1 ELSE 0 END),
                      COUNT(DISTINCT r.repo_id || '|' || r.pr_id),
                      SUM(CASE WHEN f.resolution IN ('PARTIAL','UNRESOLVED') THEN 1 ELSE 0 END)
                 FROM finding f
                 JOIN review r ON r.id = f.review_id
                WHERE r.pr_author IS NOT NULL AND r.created_at >= ? AND r.created_at <= ?
                  AND f.dismissed_at IS NULL
                GROUP BY r.pr_author""",
        ) { ps ->
            ps.setString(1, desde)
            ps.setString(2, hasta)
            ps.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        put(
                            rs.getString(1),
                            SeverityCount(rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6)),
                        )
                    }
                }
            }
        }

    /**
     * Los hallazgos concretos que recibió alguien, con su PR y su veredicto.
     *
     * Es lo que convierte "4 sin resolver" en algo sobre lo que se puede hablar: cuáles fueron, en
     * qué PR y qué decía cada uno.
     */
    fun findingsFor(author: String, desde: String, hasta: String): List<FindingRow> =
        store.stmt(
            """SELECT r.repo_id, r.pr_id, f.severity, f.title, f.file_path, f.resolution
                 FROM finding f
                 JOIN review r ON r.id = f.review_id
                WHERE r.pr_author = ? AND r.created_at >= ? AND r.created_at <= ?
                  AND f.dismissed_at IS NULL
                ORDER BY CASE f.severity WHEN 'blocker' THEN 0 WHEN 'major' THEN 1 ELSE 2 END""",
        ) { ps ->
            ps.setString(1, author)
            ps.setString(2, desde)
            ps.setString(3, hasta)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            FindingRow(
                                repoId = rs.getString(1),
                                prId = rs.getLong(2),
                                severity = rs.getString(3),
                                title = rs.getString(4).orEmpty(),
                                filePath = rs.getString(5).orEmpty(),
                                resolution = rs.getString(6),
                            ),
                        )
                    }
                }
            }
        }

    /**
     * Sobre qué parte de lo revisado se puede atribuir a alguien.
     *
     * Se muestra siempre al lado de los números: una tabla que dice "3 bloqueantes" sin aclarar
     * que se calculó sobre 7 de 12 PRs invita a conclusiones que los datos no sostienen.
     */
    fun coverage(desde: String, hasta: String): Pair<Int, Int> =
        store.stmt(
            """SELECT COUNT(DISTINCT repo_id || '|' || pr_id),
                      COUNT(DISTINCT CASE WHEN pr_author IS NOT NULL THEN repo_id || '|' || pr_id END)
                 FROM review
                WHERE status = 'DONE' AND created_at >= ? AND created_at <= ?""",
        ) { ps ->
            ps.setString(1, desde)
            ps.setString(2, hasta)
            ps.executeQuery().use { if (it.next()) it.getInt(1) to it.getInt(2) else 0 to 0 }
        }
}

/**
 * El histórico de pull requests, incluidos los que ya se cerraron.
 *
 * Se guarda aparte de `pr_cache` porque son cosas distintas: aquélla es el listado de lo que está
 * abierto y se reemplaza en cada sincronización; esto es un registro que se queda, y es lo único
 * que permite contar hacia atrás.
 */
class PrStatRepository(private val store: Store) {

    fun upsert(repoId: String, pr: io.acr.forge.PullRequest) {
        // El estado y la fecha de cierre cambian cuando el PR se mergea, así que se reemplaza la
        // fila en vez de ignorarla si ya existe.
        store.stmt(
            // Se conserva lo ya calculado de retrabajo: volver a sincronizar el PR no invalida
            // ese número, y recalcularlo cuesta una llamada a git por pull request.
            """INSERT INTO pr_stat(repo_id, pr_id, author, title, state, created_on,
                                    closed_on, synced_at, source_branch)
               VALUES (?,?,?,?,?,?,?,?,?)
               ON CONFLICT(repo_id, pr_id) DO UPDATE SET
                   author = excluded.author, title = excluded.title, state = excluded.state,
                   created_on = excluded.created_on, closed_on = excluded.closed_on,
                   synced_at = excluded.synced_at, source_branch = excluded.source_branch""",
        ) { ps ->
            ps.setString(1, repoId)
            ps.setLong(2, pr.id)
            ps.setString(3, pr.author)
            ps.setString(4, pr.title)
            ps.setString(5, pr.state.name)
            ps.setString(6, pr.createdOn)
            // Sólo para los que ya no están abiertos: en uno abierto `updated_on` es el último
            // commit, y tomarlo como cierre daría tiempos de ciclo de PRs que siguen vivos.
            ps.setString(7, if (pr.state == io.acr.forge.PrState.OPEN) null else pr.updatedOn)
            ps.setString(8, Instant.now().toString())
            ps.setString(9, pr.sourceBranch)
            ps.executeUpdate()
        }
    }

    /**
     * Guarda cuántos commits llegaron después de la review.
     *
     * Null es un valor válido y distinto de cero: significa que no se pudo saber —la rama ya no
     * está, o el commit que revisamos desapareció en un rebase—. Cero diría "no hubo
     * correcciones", que es una afirmación, y ahí no la hay.
     */
    fun setRework(repoId: String, prId: Long, commits: Int?) {
        store.stmt("UPDATE pr_stat SET commits_after_review = ?, rework_at = ? WHERE repo_id = ? AND pr_id = ?") { ps ->
            if (commits == null) ps.setNull(1, java.sql.Types.INTEGER) else ps.setInt(1, commits)
            ps.setString(2, Instant.now().toString())
            ps.setString(3, repoId)
            ps.setLong(4, prId)
            ps.executeUpdate()
        }
    }

    /**
     * Los PRs que hay que medir: los que tuvieron una review terminada **con hallazgos
     * publicados**.
     *
     * La condición de los hallazgos publicados es la que hace que el número signifique algo. Los
     * commits que llegan después de una review que no encontró nada son desarrollo normal, no
     * corrección: contarlos como retrabajo diría que alguien corrigió algo que nadie le señaló.
     */
    fun pendingRework(): List<ReworkTarget> =
        store.stmt(
            """SELECT s.repo_id, s.pr_id, s.source_branch, r.head_sha
                 FROM pr_stat s
                 JOIN review r ON r.repo_id = s.repo_id AND r.pr_id = s.pr_id
                WHERE s.source_branch IS NOT NULL AND r.status = 'DONE'
                  AND EXISTS (
                      SELECT 1 FROM finding f
                       WHERE f.review_id = r.id AND f.published_id IS NOT NULL
                  )
                GROUP BY s.repo_id, s.pr_id
               HAVING r.created_at = MAX(r.created_at)""",
        ) { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(ReworkTarget(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4)))
                    }
                }
            }
        }

    /** Retrabajo por autor: cuántos PRs necesitaron correcciones y cuántos commits en total. */
    fun reworkByAuthor(desde: String, hasta: String): Map<String, Rework> =
        store.stmt(
            """SELECT author,
                      SUM(CASE WHEN commits_after_review > 0 THEN 1 ELSE 0 END),
                      SUM(COALESCE(commits_after_review, 0)),
                      SUM(CASE WHEN commits_after_review IS NOT NULL THEN 1 ELSE 0 END)
                 FROM pr_stat
                WHERE created_on >= ? AND created_on <= ?
                GROUP BY author""",
        ) { ps ->
            ps.setString(1, desde)
            ps.setString(2, hasta)
            ps.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        val medidos = rs.getInt(4)
                        if (medidos > 0) put(rs.getString(1), Rework(rs.getInt(2), rs.getInt(3), medidos))
                    }
                }
            }
        }

    fun count(repoId: String? = null): Int =
        store.stmt("SELECT COUNT(*) FROM pr_stat WHERE (? IS NULL OR repo_id = ?)") { ps ->
            ps.setString(1, repoId)
            ps.setString(2, repoId)
            ps.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
        }

    /** Cuántos PRs abrió cada persona en el período, y cuántos de ésos ya se cerraron. */
    fun openedByAuthor(desde: String, hasta: String): Map<String, PrCount> =
        store.stmt(
            """SELECT author, COUNT(*),
                      SUM(CASE WHEN state = 'MERGED' THEN 1 ELSE 0 END),
                      SUM(CASE WHEN state = 'DECLINED' THEN 1 ELSE 0 END)
                 FROM pr_stat
                WHERE created_on >= ? AND created_on <= ?
                GROUP BY author""",
        ) { ps ->
            ps.setString(1, desde)
            ps.setString(2, hasta)
            ps.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) put(rs.getString(1), PrCount(rs.getInt(2), rs.getInt(3), rs.getInt(4)))
                }
            }
        }

    /**
     * Días que estuvo abierto cada PR mergeado, por autor.
     *
     * Devuelve la lista cruda y no un promedio: el promedio lo corre un PR abandonado tres meses,
     * y lo que describe al resto es la mediana. Quien consulta decide qué estadístico usar sobre
     * los datos completos.
     */
    fun mergedDurationsByAuthor(desde: String, hasta: String): Map<String, List<Double>> =
        store.stmt(
            """SELECT author, julianday(closed_on) - julianday(created_on)
                 FROM pr_stat
                WHERE state = 'MERGED' AND closed_on IS NOT NULL
                  AND created_on >= ? AND created_on <= ?""",
        ) { ps ->
            ps.setString(1, desde)
            ps.setString(2, hasta)
            ps.executeQuery().use { rs ->
                buildMap<String, MutableList<Double>> {
                    while (rs.next()) {
                        val d = rs.getDouble(2)
                        // Una duración negativa sale de fechas mal parseadas o relojes desfasados;
                        // contarla arrastraría la mediana hacia abajo sin que se note.
                        if (!rs.wasNull() && d >= 0) getOrPut(rs.getString(1)) { mutableListOf() }.add(d)
                    }
                }
            }
        }

    /**
     * Los pull requests concretos que componen el número de alguien.
     *
     * Un agregado que no se puede abrir no sirve para conversar: si la ficha dice "8 PRs, mediana
     * 4 días", lo primero que uno quiere ver es cuáles, y cuál fue el que tardó veinte.
     */
    fun listByAuthor(author: String, desde: String, hasta: String): List<PrRow> =
        store.stmt(
            """SELECT repo_id, pr_id, title, state, created_on, closed_on,
                      CASE WHEN closed_on IS NULL THEN NULL
                           ELSE julianday(closed_on) - julianday(created_on) END
                 FROM pr_stat
                WHERE author = ? AND created_on >= ? AND created_on <= ?
                ORDER BY created_on DESC""",
        ) { ps ->
            ps.setString(1, author)
            ps.setString(2, desde)
            ps.setString(3, hasta)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val d = rs.getDouble(7)
                        add(
                            PrRow(
                                repoId = rs.getString(1),
                                prId = rs.getLong(2),
                                title = rs.getString(3).orEmpty(),
                                state = rs.getString(4),
                                createdOn = rs.getString(5),
                                closedOn = rs.getString(6),
                                days = if (rs.wasNull() || d < 0) null else d,
                            ),
                        )
                    }
                }
            }
        }

    /** Rellena el autor de las reviews viejas con lo que trajo el histórico. */
    fun backfillReviewAuthors(): Int =
        store.stmt(
            """UPDATE review SET pr_author = (
                   SELECT s.author FROM pr_stat s
                    WHERE s.repo_id = review.repo_id AND s.pr_id = review.pr_id
               ) WHERE pr_author IS NULL AND EXISTS (
                   SELECT 1 FROM pr_stat s
                    WHERE s.repo_id = review.repo_id AND s.pr_id = review.pr_id
               )""",
        ) { it.executeUpdate() }
}

/** Un PR al que hay que medirle el retrabajo, con lo necesario para preguntarle a git. */
data class ReworkTarget(
    val repoId: String,
    val prId: Long,
    val sourceBranch: String,
    val reviewedSha: String,
)

/**
 * Retrabajo de alguien en un período.
 *
 * @param measured sobre cuántos PRs se pudo medir. Va junto con el resto porque "3 PRs con
 *   correcciones" significa cosas muy distintas si se midieron 4 o 40.
 */
data class Rework(val prsWithFixes: Int, val commits: Int, val measured: Int)

/** Un pull request concreto de la lista que hay detrás de un número. */
data class PrRow(
    val repoId: String,
    val prId: Long,
    val title: String,
    val state: String,
    val createdOn: String,
    val closedOn: String?,
    val days: Double?,
)

/** PRs abiertos por alguien en un período, y en qué terminaron. */
data class PrCount(val opened: Int, val merged: Int, val declined: Int)

/**
 * Mediana y percentil 90 de una lista de días.
 *
 * Los dos y no el promedio: un PR olvidado tres meses corre el promedio y deja de describir a
 * ninguno. La mediana dice cómo es el caso típico y el percentil 90 cuán mal se pone la cola, que
 * es donde vive el problema cuando lo hay.
 */
fun percentiles(dias: List<Double>): Pair<Double, Double>? {
    if (dias.isEmpty()) return null
    val orden = dias.sorted()
    // Rango más cercano hacia arriba, y no truncando: con truncamiento, el percentil 90 de cinco
    // valores devuelve el cuarto y el PR olvidado tres meses nunca aparece — que es exactamente
    // lo que el percentil 90 viene a mostrar. Con equipos chicos eso pasa siempre.
    fun p(q: Double): Double {
        val rango = kotlin.math.ceil(q * orden.size).toInt().coerceIn(1, orden.size)
        return orden[rango - 1]
    }
    return p(0.5) to p(0.9)
}

/** Un hallazgo concreto de los que componen el número de gravedad de alguien. */
data class FindingRow(
    val repoId: String,
    val prId: Long,
    val severity: String,
    val title: String,
    val filePath: String,
    val resolution: String?,
)

/** Cuánto participó alguien revisando: comentarios dejados y en cuántos PRs distintos. */
data class Participation(val comments: Int, val prs: Int)

/**
 * Hallazgos recibidos, por gravedad.
 *
 * @param notFixedFirstTime los que la verificación dio como parciales o sin resolver. Alto no
 *   significa que alguien trabaje peor: puede ser un revisor exigente, un requerimiento mal
 *   definido o un área difícil. Es una señal para preguntar, no una conclusión.
 */
data class SeverityCount(
    val blocker: Int,
    val major: Int,
    val minor: Int,
    val prs: Int,
    val notFixedFirstTime: Int,
) {
    val total: Int get() = blocker + major + minor
}

/** Commits ya procesados, con su volumen de cambio. */
class CommitStatRepository(private val store: Store) {

    fun upsert(repoId: String, personId: String?, c: CommitStat) {
        store.stmt(
            """INSERT OR REPLACE INTO commit_stat(repo_id, sha, person_id, author_name, author_email,
                     authored_at, files, added, deleted, generated_added, generated_deleted)
               VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
        ) { ps ->
            ps.setString(1, repoId)
            ps.setString(2, c.sha)
            ps.setString(3, personId)
            ps.setString(4, c.author.name)
            ps.setString(5, normalizeEmail(c.author.email))
            ps.setString(6, c.authoredAt)
            ps.setInt(7, c.files)
            ps.setInt(8, c.added)
            ps.setInt(9, c.deleted)
            ps.setInt(10, c.generatedAdded)
            ps.setInt(11, c.generatedDeleted)
            ps.executeUpdate()
        }
    }

    fun count(repoId: String): Int =
        store.stmt("SELECT COUNT(*) FROM commit_stat WHERE repo_id = ?") { ps ->
            ps.setString(1, repoId)
            ps.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
        }

    /** ¿Ya está procesado? Sirve para no rehacer trabajo al recolectar de nuevo. */
    fun has(repoId: String, sha: String): Boolean =
        store.stmt("SELECT 1 FROM commit_stat WHERE repo_id = ? AND sha = ?") { ps ->
            ps.setString(1, repoId)
            ps.setString(2, sha)
            ps.executeQuery().use { it.next() }
        }

    /** Volumen por persona en un rango de fechas ISO. Se calcula, no se guarda. */
    fun volumeByPerson(desde: String, hasta: String, repoId: String? = null): Map<String, Volume> =
        store.stmt(
            """SELECT person_id, COUNT(*), SUM(added), SUM(deleted),
                      SUM(generated_added), SUM(generated_deleted)
                 FROM commit_stat
                WHERE authored_at >= ? AND authored_at <= ? AND person_id IS NOT NULL
                  AND (? IS NULL OR repo_id = ?)
                GROUP BY person_id""",
        ) { ps ->
            ps.setString(1, desde)
            ps.setString(2, hasta)
            ps.setString(3, repoId)
            ps.setString(4, repoId)
            ps.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        put(
                            rs.getString(1),
                            Volume(rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6)),
                        )
                    }
                }
            }
        }

    fun markRun(repoId: String, headSha: String?, commits: Int) {
        store.stmt(
            "INSERT OR REPLACE INTO stats_run(repo_id, ran_at, head_sha, commits) VALUES (?,?,?,?)",
        ) { ps ->
            ps.setString(1, repoId)
            ps.setString(2, Instant.now().toString())
            ps.setString(3, headSha)
            ps.setInt(4, commits)
            ps.executeUpdate()
        }
    }

    fun lastRun(repoId: String): Triple<String, String?, Int>? =
        store.stmt("SELECT ran_at, head_sha, commits FROM stats_run WHERE repo_id = ?") { ps ->
            ps.setString(1, repoId)
            ps.executeQuery().use {
                if (it.next()) Triple(it.getString(1), it.getString(2), it.getInt(3)) else null
            }
        }
}

/**
 * Volumen de cambio, siempre desglosado.
 *
 * Nunca un número solo: un refactor que borra 2.000 líneas no es más trabajo que un bugfix de una,
 * y lo generado se guarda aparte para poder decir cuánto se excluyó en vez de esconderlo.
 */
data class Volume(
    val commits: Int,
    val added: Int,
    val deleted: Int,
    val generatedAdded: Int,
    val generatedDeleted: Int,
) {
    val net: Int get() = added - deleted
    val touched: Int get() = added + deleted
    val generated: Int get() = generatedAdded + generatedDeleted
}
