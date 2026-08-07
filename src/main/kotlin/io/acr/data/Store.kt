package io.acr.data

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite storage. Forward-only migrations applied transactionally at startup, mirroring the
 * mongo-explorer v3 approach: a migration is never edited once shipped, only appended to.
 */
class Store(private val dbPath: Path) : AutoCloseable {

    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath").apply {
        createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("PRAGMA foreign_keys=ON")
        }
    }

    /**
     * Toda la app comparte UNA sola `Connection`, y `java.sql.Connection` no es thread-safe.
     * Peor que las lecturas sueltas son las transacciones: un bloque que hace
     * `autoCommit = false … commit() … autoCommit = true` es global a la conexión, así que una
     * escritura de otra corrutina que caiga en el medio queda arrastrada dentro de esa
     * transacción — y si la transacción hace rollback, se pierde trabajo ajeno.
     *
     * Se serializa todo el acceso con este monitor. Sirve tanto desde el hilo de UI como desde
     * corrutinas (a diferencia de un Mutex de corrutinas, que exigiría suspend en todos lados).
     * Con SQLite local las operaciones son de microsegundos, así que la contención es irrelevante.
     */
    val lock = Any()

    /** Ejecuta [block] con acceso exclusivo a la conexión. */
    inline fun <T> read(block: (Connection) -> T): T = synchronized(lock) { block(conn) }

    /** Prepara y ejecuta una sentencia bajo el lock, cerrándola siempre. */
    inline fun <T> stmt(sql: String, block: (java.sql.PreparedStatement) -> T): T =
        synchronized(lock) { conn.prepareStatement(sql).use(block) }

    /** Igual que [read], pero envuelto en una transacción real y atómica frente a otros hilos. */
    inline fun <T> transaction(block: (Connection) -> T): T = synchronized(lock) {
        conn.autoCommit = false
        try {
            val result = block(conn)
            conn.commit()
            result
        } catch (e: Throwable) {
            runCatching { conn.rollback() }
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    init {
        migrate()
    }

    private fun migrate() {
        conn.createStatement().use {
            it.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
        }
        val current = conn.createStatement().use { st ->
            st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
        // drop(n) con n > size devuelve lista vacía: sin este guard, abrir una base creada por
        // una versión más nueva de la app no migraba nada y fallaba después, en runtime, con un
        // "no such column" en un lugar arbitrario.
        check(current <= MIGRATIONS.size) {
            "La base está en la versión $current pero esta build sólo conoce ${MIGRATIONS.size}. " +
                "Actualizá la app: seguir podría corromper datos."
        }
        MIGRATIONS.drop(current).forEachIndexed { offset, sql ->
            val version = current + offset + 1
            conn.autoCommit = false
            try {
                conn.createStatement().use { st -> sql.split(";--split--").forEach { st.execute(it) } }
                conn.prepareStatement("INSERT INTO schema_version(version) VALUES (?)").use { ps ->
                    ps.setInt(1, version)
                    ps.executeUpdate()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw IllegalStateException("Migration $version failed: ${e.message}", e)
            } finally {
                conn.autoCommit = true
            }
        }
    }

    /** Versión de esquema aplicada, y cuántas conoce este build. Para la pantalla de info. */
    fun schemaVersion(): Pair<Int, Int> = synchronized(lock) {
        val applied = conn.createStatement().use { st ->
            st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version").use {
                if (it.next()) it.getInt(1) else 0
            }
        }
        applied to MIGRATIONS.size
    }

    val path: String = dbPath.toString()

    override fun close() = conn.close()

    private companion object {
        val MIGRATIONS = listOf(
            // v1 — repositories and reviews
            """
            CREATE TABLE repo (
                id            TEXT PRIMARY KEY,
                name          TEXT NOT NULL,
                provider      TEXT NOT NULL,
                owner         TEXT NOT NULL,
                slug          TEXT NOT NULL,
                local_path    TEXT NOT NULL,
                token_cipher  BLOB,
                created_at    TEXT NOT NULL,
                UNIQUE (provider, owner, slug)
            );--split--
            CREATE TABLE review (
                id            TEXT PRIMARY KEY,
                repo_id       TEXT NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
                pr_id         INTEGER NOT NULL,
                pr_title      TEXT NOT NULL,
                head_sha      TEXT NOT NULL,
                status        TEXT NOT NULL,
                body          TEXT,
                error         TEXT,
                session_id    TEXT,
                cost_usd      REAL,
                published_url TEXT,
                created_at    TEXT NOT NULL,
                finished_at   TEXT
            );--split--
            CREATE INDEX ix_review_repo_pr ON review(repo_id, pr_id, created_at DESC);--split--
            CREATE TABLE pref (
                k TEXT PRIMARY KEY,
                v TEXT NOT NULL
            )
            """.trimIndent(),

            // v2 — historial: cada publicación es un evento propio, y guardamos el hilo del PR.
            """
            CREATE TABLE publication (
                id           TEXT PRIMARY KEY,
                review_id    TEXT NOT NULL REFERENCES review(id) ON DELETE CASCADE,
                repo_id      TEXT NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
                pr_id        INTEGER NOT NULL,
                comment_id   TEXT,
                url          TEXT,
                body         TEXT NOT NULL,
                published_at TEXT NOT NULL
            );--split--
            CREATE INDEX ix_publication_repo_pr ON publication(repo_id, pr_id, published_at DESC);--split--
            CREATE TABLE pr_comment (
                id          TEXT PRIMARY KEY,
                repo_id     TEXT NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
                pr_id       INTEGER NOT NULL,
                comment_id  TEXT NOT NULL,
                author      TEXT NOT NULL,
                body        TEXT NOT NULL,
                inline_path TEXT,
                inline_line INTEGER,
                is_deleted  INTEGER NOT NULL DEFAULT 0,
                is_ours     INTEGER NOT NULL DEFAULT 0,
                created_on  TEXT NOT NULL,
                synced_at   TEXT NOT NULL,
                UNIQUE (repo_id, pr_id, comment_id)
            );--split--
            CREATE INDEX ix_pr_comment_repo_pr ON pr_comment(repo_id, pr_id, created_on)
            """.trimIndent(),

            // v3 — perfil de review: profundidad y tipo de proyecto.
            // Los defaults dejan a los repos existentes en el comportamiento previo.
            """
            ALTER TABLE repo ADD COLUMN project_kind TEXT NOT NULL DEFAULT 'GENERIC';--split--
            ALTER TABLE repo ADD COLUMN default_depth TEXT NOT NULL DEFAULT 'INTERMEDIATE';--split--
            ALTER TABLE review ADD COLUMN depth TEXT;--split--
            ALTER TABLE review ADD COLUMN project_kind TEXT
            """.trimIndent(),

            // v4 — modelo elegible y modo automático.
            // 'AUTO' en project_kind/default_depth significa "inferilo del diff".
            """
            ALTER TABLE repo ADD COLUMN default_model TEXT NOT NULL DEFAULT 'AUTO';--split--
            ALTER TABLE review ADD COLUMN model TEXT
            """.trimIndent(),

            // v5 — notas locales ancladas a archivo:línea, previas a publicarse.
            """
            CREATE TABLE local_note (
                id           TEXT PRIMARY KEY,
                repo_id      TEXT NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
                pr_id        INTEGER NOT NULL,
                file_path    TEXT NOT NULL,
                line_no      INTEGER,
                side         TEXT NOT NULL DEFAULT 'NEW',
                body         TEXT NOT NULL,
                published_id TEXT,
                created_at   TEXT NOT NULL
            );--split--
            CREATE INDEX ix_local_note_repo_pr ON local_note(repo_id, pr_id, file_path, line_no)
            """.trimIndent(),

            // v6 — hallazgos de la review, cada uno anclado a su archivo y línea.
            """
            CREATE TABLE finding (
                id           TEXT PRIMARY KEY,
                review_id    TEXT NOT NULL REFERENCES review(id) ON DELETE CASCADE,
                repo_id      TEXT NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
                pr_id        INTEGER NOT NULL,
                file_path    TEXT NOT NULL,
                line_no      INTEGER,
                severity     TEXT NOT NULL,
                title        TEXT NOT NULL,
                body         TEXT NOT NULL,
                published_id TEXT,
                created_at   TEXT NOT NULL
            );--split--
            CREATE INDEX ix_finding_repo_pr ON finding(repo_id, pr_id, file_path, line_no)
            """.trimIndent(),

            // v7 — índice que faltaba: forReview y el DELETE de replaceForReview filtran por
            // review_id, que no era columna líder de ningún índice y hacía full scan.
            """
            CREATE INDEX ix_finding_review ON finding(review_id)
            """.trimIndent(),

            // v8 — revisión autónoma por repositorio.
            """
            ALTER TABLE repo ADD COLUMN auto_review INTEGER NOT NULL DEFAULT 0
            """.trimIndent(),

            // v9 — historial del repo: historyFor filtra por repo_id y ordena por created_at, pero
            // en ix_review_repo_pr created_at va tercera, detrás de pr_id, así que el índice ordena
            // por PR y no por fecha. El motor terminaba leyendo TODAS las reviews del repo y
            // ordenándolas —filas gordas, con el markdown del body adentro— para quedarse con 100.
            // Mismo caso que la v7 con ix_finding_review, una tabla más allá.
            """
            CREATE INDEX ix_review_repo_created ON review(repo_id, created_at DESC)
            """.trimIndent(),

            // v10 — quién disparó la review: el barrido automático o una persona. El panel las
            // muestra juntas, así que necesita distinguirlas.
            """
            ALTER TABLE review ADD COLUMN trigger_kind TEXT NOT NULL DEFAULT 'MANUAL'
            """.trimIndent(),

            // v11 — tokens de cada corrida. Con suscripción el costo en dólares es un
            // equivalente teórico; lo que se consume de verdad son tokens.
            """
            ALTER TABLE review ADD COLUMN tokens_in INTEGER;--split--
            ALTER TABLE review ADD COLUMN tokens_out INTEGER;--split--
            ALTER TABLE review ADD COLUMN tokens_cache_read INTEGER;--split--
            ALTER TABLE review ADD COLUMN tokens_cache_write INTEGER
            """.trimIndent(),

            // v12 — URL del comentario publicado. Se guardaba sólo el id, así que no había
            // adónde abrir: un hallazgo publicado decía "publicado" y nada más.
            """
            ALTER TABLE finding ADD COLUMN published_url TEXT;--split--
            ALTER TABLE local_note ADD COLUMN published_url TEXT
            """.trimIndent(),

            // v13 — hilos: de qué comentario cuelga cada respuesta, y borradores de contestación.
            """
            ALTER TABLE pr_comment ADD COLUMN parent_id TEXT;--split--
            CREATE TABLE reply_draft (
                id                TEXT PRIMARY KEY,
                repo_id           TEXT NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
                pr_id             INTEGER NOT NULL,
                their_comment_id  TEXT NOT NULL,
                their_author      TEXT NOT NULL,
                their_body        TEXT NOT NULL,
                our_comment_id    TEXT,
                our_body          TEXT,
                file_path         TEXT,
                line_no           INTEGER,
                body              TEXT,
                status            TEXT NOT NULL,
                error             TEXT,
                published_id      TEXT,
                published_url     TEXT,
                cost_usd          REAL,
                created_at        TEXT NOT NULL,
                UNIQUE (repo_id, pr_id, their_comment_id)
            );--split--
            CREATE INDEX ix_reply_repo_pr ON reply_draft(repo_id, pr_id, created_at DESC)
            """.trimIndent(),

            // v14 — herramientas denegadas como dato aparte. Antes se pegaban al final del cuerpo
            // de la review, así que terminaban publicadas en el PR: diagnóstico interno que al
            // lector del PR no le dice nada.
            """
            ALTER TABLE review ADD COLUMN denied_tools TEXT
            """.trimIndent(),

            // v15 — qué NO revisar. El barrido automático revisaba todo PR abierto, incluidos los
            // marcados "DO NOT MERGE", y volvía a revisarlos con cada commit nuevo.
            """
            ALTER TABLE repo ADD COLUMN skip_drafts INTEGER NOT NULL DEFAULT 1;--split--
            ALTER TABLE repo ADD COLUMN skip_titles TEXT NOT NULL DEFAULT 'DO NOT MERGE,WIP';--split--
            ALTER TABLE repo ADD COLUMN skip_authors TEXT NOT NULL DEFAULT '';--split--
            ALTER TABLE repo ADD COLUMN only_targets TEXT NOT NULL DEFAULT ''
            """.trimIndent(),

            // v16 — qué hacer cuando el desarrollador responde. El default es DRAFT: preparar y
            // avisar. AUTO publica sin intervención y es opt-in explícito por repositorio.
            """
            ALTER TABLE repo ADD COLUMN reply_mode TEXT NOT NULL DEFAULT 'DRAFT'
            """.trimIndent(),

            // v17 — PRs ya vistos. Sin esto no hay forma de distinguir "apareció uno nuevo" de
            // "está abierto desde hace una semana", y avisar de todos sería ruido inservible.
            """
            CREATE TABLE seen_pr (
                repo_id       TEXT NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
                pr_id         INTEGER NOT NULL,
                title         TEXT NOT NULL,
                author        TEXT NOT NULL,
                first_seen_at TEXT NOT NULL,
                PRIMARY KEY (repo_id, pr_id)
            )
            """.trimIndent(),

            // v18 — caché de la lista de PRs. Traerla tarda medio segundo cuando el proveedor
            // responde, pero bajo rate limit el backoff la lleva a decenas de segundos; con caché
            // la lista aparece al instante y se revalida atrás.
            """
            CREATE TABLE pr_cache (
                repo_id    TEXT NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
                pr_id      INTEGER NOT NULL,
                title      TEXT NOT NULL,
                author     TEXT NOT NULL,
                source     TEXT NOT NULL,
                target     TEXT NOT NULL,
                head_sha   TEXT NOT NULL,
                comments   INTEGER NOT NULL DEFAULT 0,
                updated_on TEXT NOT NULL DEFAULT '',
                url        TEXT NOT NULL DEFAULT '',
                is_draft   INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (repo_id, pr_id)
            );--split--
            CREATE TABLE pr_cache_meta (
                repo_id    TEXT PRIMARY KEY REFERENCES repo(id) ON DELETE CASCADE,
                etag       TEXT,
                fetched_at TEXT NOT NULL
            )
            """.trimIndent(),

            // v19 — corrige las reviews que se publicaron hallazgo por hallazgo.
            //
            // `review.published_url` sólo se escribía al publicar el comentario resumen. Quien
            // publicaba los hallazgos como comentarios inline —el camino normal— dejaba la review
            // con la columna en NULL, así que el PR seguía figurando "listo para publicar" para
            // siempre, en la lista, en el panel y en el icono de la barra de menú.
            //
            // Se marcan publicadas las reviews que tienen hallazgos y ninguno sin publicar,
            // heredando la URL de uno de sus comentarios.
            //
            // La condición mira `published_id` y no `published_url`: el proveedor a veces devuelve
            // el comentario creado sin link (hay 2 así en esta base), y condicionar por la URL
            // dejaría esas reviews colgadas como pendientes para siempre.
            """
            UPDATE review
               SET published_url = COALESCE((
                   SELECT f.published_url FROM finding f
                    WHERE f.review_id = review.id AND f.published_url IS NOT NULL
                      AND f.published_url <> ''
                    LIMIT 1
               ), '')
             WHERE published_url IS NULL
               AND EXISTS (SELECT 1 FROM finding f WHERE f.review_id = review.id)
               AND NOT EXISTS (
                   SELECT 1 FROM finding f
                    WHERE f.review_id = review.id AND f.published_id IS NULL
               )
            """.trimIndent(),

            // v20 — marca como nuestros los comentarios que publicamos y no figuraban así.
            //
            // Al sincronizar el hilo sólo se pasaban los ids del comentario resumen, así que los
            // hallazgos publicados inline y nuestras propias respuestas se guardaban con
            // `is_ours = 0`: el historial los mostraba como si fueran de otra persona, y una
            // respuesta a una contestación nuestra no se detectaba porque su padre no figuraba
            // como nuestro.
            """
            UPDATE pr_comment
               SET is_ours = 1
             WHERE is_ours = 0
               AND comment_id IN (
                   SELECT f.published_id FROM finding f
                    WHERE f.repo_id = pr_comment.repo_id AND f.pr_id = pr_comment.pr_id
                      AND f.published_id IS NOT NULL
                   UNION
                   SELECT d.published_id FROM reply_draft d
                    WHERE d.repo_id = pr_comment.repo_id AND d.pr_id = pr_comment.pr_id
                      AND d.published_id IS NOT NULL
                   UNION
                   SELECT p.comment_id FROM publication p
                    WHERE p.repo_id = pr_comment.repo_id AND p.pr_id = pr_comment.pr_id
                      AND p.comment_id IS NOT NULL
               )
            """.trimIndent(),

            // v21 — cuándo se abrió el PR, para poder empezar por los más viejos.
            //
            // Hasta acá sólo se guardaba `updated_on`, y los proveedores devuelven la lista
            // ordenada por actividad reciente: el PR que lleva más tiempo esperando quedaba
            // último. Queda vacío hasta el primer refresco, y el orden trata el vacío como
            // desconocido en vez de como "muy viejo".
            "ALTER TABLE pr_cache ADD COLUMN created_on TEXT NOT NULL DEFAULT ''",
        )
    }
}
