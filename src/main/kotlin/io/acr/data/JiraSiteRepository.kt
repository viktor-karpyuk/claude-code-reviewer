package io.acr.data

import com.github.f4b6a3.ulid.UlidCreator
import io.acr.crypto.Secrets
import io.acr.jira.JiraConfig
import java.time.Instant

/**
 * Un sitio de Jira conectado, con los proyectos que le corresponden.
 *
 * @param projects prefijos de proyecto separados por coma —"KS,POS"—. Es lo que decide a qué sitio
 *   pedirle cada ticket: el ruteo va por prefijo y no por repositorio porque un repositorio puede
 *   mencionar tickets de varios proyectos, y de hecho pasa —uno de estos usa cuatro—.
 */
data class JiraSite(
    val id: String,
    val name: String,
    val baseUrl: String,
    val email: String,
    val token: String?,
    val projects: List<String>,
) {
    fun config(): JiraConfig = JiraConfig(baseUrl, email, token.orEmpty())

    fun handles(key: String): Boolean {
        val prefijo = key.substringBefore('-').uppercase()
        return projects.any { it.equals(prefijo, ignoreCase = true) }
    }
}

/** Los sitios de Jira conectados. Se agregan como los repositorios: son una lista, no un ajuste. */
class JiraSiteRepository(private val store: Store, private val secrets: Secrets) {

    fun list(): List<JiraSite> =
        store.stmt(
            "SELECT id, name, base_url, email, token_cipher, projects FROM jira_site ORDER BY name",
        ) { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            JiraSite(
                                id = rs.getString(1),
                                name = rs.getString(2),
                                baseUrl = rs.getString(3),
                                email = rs.getString(4),
                                token = rs.getBytes(5)?.let { runCatching { secrets.decrypt(it) }.getOrNull() },
                                projects = rs.getString(6).orEmpty()
                                    .split(',').map { it.trim().uppercase() }.filter { it.isNotBlank() },
                            ),
                        )
                    }
                }
            }
        }

    fun save(
        id: String?,
        name: String,
        baseUrl: String,
        email: String,
        token: String?,
        projects: String,
    ): String {
        val nuevo = id ?: UlidCreator.getUlid().toString()
        // El token sólo se reescribe si vino uno: al editar el resto de los campos, un campo vacío
        // significa "no lo toqués", no "borralo".
        val cifrado = token?.takeIf { it.isNotBlank() }?.let { secrets.encrypt(it) }
        store.stmt(
            if (id == null || cifrado != null) {
                """INSERT OR REPLACE INTO jira_site(id, name, base_url, email, token_cipher, projects, created_at)
                   VALUES (?,?,?,?,?,?,?)"""
            } else {
                """INSERT OR REPLACE INTO jira_site(id, name, base_url, email, token_cipher, projects, created_at)
                   VALUES (?,?,?,?,(SELECT token_cipher FROM jira_site WHERE id = ?),?,?)"""
            },
        ) { ps ->
            ps.setString(1, nuevo)
            ps.setString(2, name.trim())
            ps.setString(3, baseUrl.trim().trimEnd('/'))
            ps.setString(4, email.trim())
            if (id == null || cifrado != null) ps.setBytes(5, cifrado) else ps.setString(5, nuevo)
            ps.setString(6, projects.uppercase().replace(" ", ""))
            ps.setString(7, Instant.now().toString())
            ps.executeUpdate()
        }
        return nuevo
    }

    fun delete(id: String) {
        store.stmt("DELETE FROM jira_site WHERE id = ?") { ps -> ps.setString(1, id); ps.executeUpdate() }
    }

    /**
     * A qué sitio pedirle un ticket.
     *
     * Con un solo sitio conectado se usa ese aunque no declare proyectos: pedir que alguien liste
     * sus prefijos cuando no hay ambigüedad es trabajo sin motivo. Con varios, manda la lista de
     * proyectos; si ninguno lo reclama devuelve null en vez de probar con cualquiera, porque un
     * ticket traído del sitio equivocado es peor que ninguno — puede existir y ser de otra cosa.
     */
    /**
     * @param sites pasar la lista cuando se resuelven varias claves seguidas. Sin eso, el valor
     *   por defecto consulta la base una vez por ticket, y un PR con cuatro tickets hacía cuatro
     *   lecturas para elegir entre dos sitios que no cambian.
     */
    fun siteFor(key: String, sites: List<JiraSite> = list()): JiraSite? {
        if (sites.isEmpty()) return null
        sites.firstOrNull { it.handles(key) }?.let { return it }
        return sites.singleOrNull()?.takeIf { it.projects.isEmpty() }
    }
}
