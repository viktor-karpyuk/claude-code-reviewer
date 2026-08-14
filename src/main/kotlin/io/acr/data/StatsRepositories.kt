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
        return store.stmt("SELECT id, display_name, is_bot FROM person ORDER BY display_name") { ps ->
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
