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

            // v22 — un hallazgo puede quedar resuelto sin publicarse, y un fallo al publicar tiene
            // que sobrevivir a la pantalla.
            //
            // Antes había un solo estado posible: publicado o pendiente. Un nitpick que uno decide
            // no mandar dejaba el PR contando como "listo para publicar" para siempre, y si el
            // proveedor rechazaba un comentario el error vivía en un snackbar que se iba solo: el
            // hallazgo quedaba pendiente sin que nadie supiera por qué.
            """
            ALTER TABLE finding ADD COLUMN dismissed_at TEXT;--split--
            ALTER TABLE finding ADD COLUMN publish_error TEXT
            """.trimIndent(),

            // v23 — verificación de que lo señalado efectivamente se corrigió.
            //
            // Publicar un comentario no es lo mismo que que lo hayan resuelto. Sin esto, decidir
            // si el PR está listo era leer a mano cada hallazgo contra los commits nuevos y el
            // hilo. `resolution` guarda el veredicto por hallazgo y `resolution_note` la evidencia
            // concreta —qué commit o qué línea lo arregla—, que es lo que permite discutirlo.
            """
            ALTER TABLE finding ADD COLUMN resolution TEXT;--split--
            ALTER TABLE finding ADD COLUMN resolution_note TEXT;--split--
            ALTER TABLE review ADD COLUMN resolution_summary TEXT;--split--
            ALTER TABLE review ADD COLUMN resolution_at TEXT
            """.trimIndent(),

            // v24 — contra qué commit se verificó.
            //
            // Sin esto no hay forma de saber si una verificación sigue valiendo: al llegar un
            // commit nuevo la anterior queda vieja, y sin el dato el barrido automático la
            // repetiría en cada vuelta —cada una cuesta una corrida del modelo— o no la repetiría
            // nunca. Guardar el head convierte "¿hace falta?" en una comparación.
            "ALTER TABLE review ADD COLUMN resolution_head TEXT",

            // v25 — cuándo se mandó el último recordatorio de un comentario sin responder.
            //
            // Sin esto el reloj de "hace N días que no me contestás" arrancaría siempre desde el
            // comentario original, y la app te ofrecería insistir todos los días sobre algo que ya
            // insististe ayer. Insistir de más es peor que no insistir.
            "ALTER TABLE finding ADD COLUMN followed_up_at TEXT",

            // v26 — un comentario puede cerrarse en la conversación, sin cambios en el código.
            //
            // Faltaba un caso: comentarios que no piden que cambien nada —una validación, un
            // "dale, tenés razón", una pregunta ya contestada—. Sin esto quedaban esperando una
            // corrección que nunca iba a llegar, y el PR no podía darse por listo. Distinto de
            // `dismissed_at`, que es "decidí no publicarlo": esto es "se publicó, se habló y se
            // cerró".
            "ALTER TABLE finding ADD COLUMN closed_at TEXT",

            // v27 — la pasada final: una última mirada al código completo antes de mergear.
            //
            // Es distinta de la verificación: aquélla pregunta "¿arreglaron lo que dije?", ésta
            // "¿hay algo que frene el merge, mirando el PR como está ahora?". Se guarda contra qué
            // commit corrió, por lo mismo que la verificación: si el PR avanza, la pasada quedó
            // vieja y no puede seguir contando como hecha.
            """
            ALTER TABLE review ADD COLUMN final_pass_head TEXT;--split--
            ALTER TABLE review ADD COLUMN final_pass_summary TEXT;--split--
            ALTER TABLE review ADD COLUMN final_pass_blockers INTEGER NOT NULL DEFAULT 0
            """.trimIndent(),

            // v28 — cómo debería resolverse el hallazgo.
            //
            // Señalar un problema sin decir cómo se arregla deja todo el trabajo de pensar la
            // solución del otro lado. Va en su propia columna y no dentro del cuerpo para poder
            // mostrarla aparte, publicarla con formato propio y saber cuántos hallazgos traen una.
            "ALTER TABLE finding ADD COLUMN suggestion TEXT",

            // v29 — quién aprobó cada PR.
            //
            // El listado de Bitbucket NO trae las aprobaciones: sólo el PR individual, en
            // `participants`. Pedir uno por uno sería una llamada por fila, y con el 401
            // intermitente que ya conocemos eso es inviable. Se registra lo que se sabe —cuando
            // aprobamos desde la app, y cuando se abre un PR y se ven sus participantes— y la
            // lista lee de acá, gratis.
            """
            CREATE TABLE pr_approval (
                repo_id     TEXT NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
                pr_id       INTEGER NOT NULL,
                approved_by TEXT NOT NULL,
                by_us       INTEGER NOT NULL DEFAULT 0,
                approved_at TEXT NOT NULL,
                PRIMARY KEY (repo_id, pr_id, approved_by)
            )
            """.trimIndent(),

            // v30 — "aprobado" no es el único estado: también se pueden pedir cambios.
            //
            // Bitbucket lo modela así en `participants[].state` —approved, changes_requested o
            // nada— y son excluyentes. Con una sola columna booleana, pedir cambios se veía igual
            // que no haber opinado, y el botón seguía ofreciendo "Aprobar" a quien ya aprobó.
            "ALTER TABLE pr_approval ADD COLUMN state TEXT NOT NULL DEFAULT 'APPROVED'",

            // v31 — nuestras acciones se guardaban bajo el nombre genérico "nosotros".
            //
            // Eso creaba una persona fantasma: la misma aprobación figuraba dos veces, una como
            // "nosotros" —la que registró la app— y otra con el nombre real, cuando el sync la
            // traía de la API. Se unifican usando el nombre con el que el proveedor nos nombra,
            // que sale de los comentarios que ya sabemos nuestros.
            """
            UPDATE pr_approval SET approved_by = (
                SELECT author FROM pr_comment WHERE is_ours = 1
                 GROUP BY author ORDER BY COUNT(*) DESC LIMIT 1
            )
             WHERE approved_by = 'nosotros'
               AND NOT EXISTS (
                   SELECT 1 FROM pr_approval x
                    WHERE x.repo_id = pr_approval.repo_id AND x.pr_id = pr_approval.pr_id
                      AND x.approved_by = (SELECT author FROM pr_comment WHERE is_ours = 1
                                            GROUP BY author ORDER BY COUNT(*) DESC LIMIT 1)
               );--split--
            UPDATE pr_approval SET by_us = 1
             WHERE approved_by = (SELECT author FROM pr_comment WHERE is_ours = 1
                                   GROUP BY author ORDER BY COUNT(*) DESC LIMIT 1)
               AND EXISTS (
                   SELECT 1 FROM pr_approval x
                    WHERE x.repo_id = pr_approval.repo_id AND x.pr_id = pr_approval.pr_id
                      AND x.approved_by = 'nosotros'
               );--split--
            DELETE FROM pr_approval WHERE approved_by = 'nosotros'
            """.trimIndent(),

            // v32 — una respuesta se puede dar por cerrada sin contestarla.
            //
            // No toda respuesta pide una contestación: "corregido", "gracias", "dale". Sin una
            // salida, esas quedaban bloqueando el merge para siempre —24 así en un solo PR— y la
            // única forma de destrabarlo era escribir algo que nadie necesitaba leer.
            "ALTER TABLE reply_draft ADD COLUMN dismissed_at TEXT",

            // v33 — trabajo que quedó a medias cuando se cerró la app.
            //
            // Hasta acá una review interrumpida se marcaba fallida y ahí moría: había 16 así. El
            // subproceso muerto no se puede retomar —se fue con su contexto— así que "continuar"
            // sólo puede significar volver a correrla con los mismos parámetros. Se guardan acá
            // para poder hacerlo al abrir, en vez de perder el trabajo en silencio.
            """
            CREATE TABLE pending_job (
                id         TEXT PRIMARY KEY,
                repo_id    TEXT NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
                pr_id      INTEGER NOT NULL,
                depth      TEXT,
                kind       TEXT,
                model      TEXT NOT NULL DEFAULT '',
                auto       INTEGER NOT NULL DEFAULT 0,
                attempts   INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                UNIQUE (repo_id, pr_id)
            )
            """.trimIndent(),

            // v34 — convenciones y guías de arquitectura propias del equipo.
            //
            // Sin esto, la review marca como problema lo que en realidad es una decisión tomada:
            // cómo se nombran las interfaces, dónde va la lógica, qué patrón usa cada capa. El
            // texto se guarda en la base y no como ruta a un archivo: si fuera una ruta, mover o
            // borrar el archivo cambiaría en silencio con qué criterio se revisa.
            //
            // `repo_id` nulo significa que aplica a todos los repositorios.
            """
            CREATE TABLE guideline (
                id         TEXT PRIMARY KEY,
                repo_id    TEXT REFERENCES repo(id) ON DELETE CASCADE,
                name       TEXT NOT NULL,
                content    TEXT NOT NULL,
                enabled    INTEGER NOT NULL DEFAULT 1,
                source     TEXT,
                created_at TEXT NOT NULL
            );--split--
            CREATE INDEX ix_guideline_repo ON guideline(repo_id, enabled)
            """.trimIndent(),

            // v35 — de dónde salió una guía importada de un archivo del repositorio.
            //
            // El contenido sigue viviendo en la base, por lo que dice la v34: si la review leyera
            // el archivo en cada corrida, cambiar de rama cambiaría las reglas de revisión sin
            // que nadie se entere. Pero un CLAUDE.md vive en git y se edita, así que una copia
            // congelada envejece igual de callada.
            //
            // La salida es avisar en vez de decidir por el usuario: se guarda la ruta y una huella
            // del contenido importado, y cuando el archivo difiere la pantalla lo marca como
            // desactualizado con un botón para actualizarlo. Nada cambia solo, nada queda viejo
            // en silencio.
            """
            ALTER TABLE guideline ADD COLUMN linked_path TEXT;--split--
            ALTER TABLE guideline ADD COLUMN linked_hash TEXT
            """.trimIndent(),

            // v36 — una review puede continuar a otra en vez de empezar de cero.
            //
            // Hasta acá cada corrida miraba la rama entera. En esta base eso significó que el PR
            // #149, de 23 commits, se revisara 25 veces de punta a punta por US$ 225: el 60% de
            // todo el consumo, releyendo archivos ya aprobados 24 veces.
            //
            // `previous_review_id` es de dónde vienen los hallazgos arrastrados y `since_sha` el
            // commit desde el que se miró. Se guardan aunque se puedan deducir porque una review
            // que miró sólo una parte tiene que poder decirlo después: leer un resultado sin saber
            // qué alcance tuvo lleva a creer que se revisó algo que nadie miró en esa corrida.
            """
            ALTER TABLE review ADD COLUMN previous_review_id TEXT;--split--
            ALTER TABLE review ADD COLUMN since_sha TEXT
            """.trimIndent(),

            // v37 — quién es quién, y qué commiteó cada uno.
            //
            // Es el problema cero de las estadísticas: medido sobre los siete repositorios
            // conectados, la misma persona aparece hasta con dos nombres y dos emails —"Viktor K
            // <viktor@>" y "Viktor Karpyuk <viktor.karpyuk@>", 223 y 26 commits—. Agrupar por
            // email parte a esa persona en dos; agrupar por nombre parte a Mateo, que commitea con
            // y sin tilde. Sin resolver esto, todos los números que se muestren están mal.
            //
            // De ahí la entidad persona con identidades: un par (fuente, valor) por cada forma en
            // que alguien aparece. `confirmed` distingue lo que decidió una persona de lo que
            // adivinó la heurística, porque unir por nombre puede equivocarse y hay que poder
            // revisarlo.
            //
            // Los agregados NO se guardan: se calculan por consulta. Guardar totales obliga a
            // recalcularlos cada vez que se fusiona una identidad, y esa desincronización es
            // exactamente lo que hace que nadie vuelva a confiar en el número. `commit_stat` sí,
            // porque recorrer el git log de meses en cada apertura sería lento.
            """
            CREATE TABLE person (
                id           TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                is_bot       INTEGER NOT NULL DEFAULT 0,
                created_at   TEXT NOT NULL
            );--split--
            CREATE TABLE person_identity (
                id        TEXT PRIMARY KEY,
                person_id TEXT NOT NULL REFERENCES person(id) ON DELETE CASCADE,
                kind      TEXT NOT NULL,
                value     TEXT NOT NULL,
                confirmed INTEGER NOT NULL DEFAULT 0,
                UNIQUE (kind, value)
            );--split--
            CREATE INDEX ix_identity_person ON person_identity(person_id);--split--
            CREATE TABLE commit_stat (
                repo_id           TEXT NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
                sha               TEXT NOT NULL,
                person_id         TEXT REFERENCES person(id) ON DELETE SET NULL,
                author_name       TEXT NOT NULL,
                author_email      TEXT NOT NULL,
                authored_at       TEXT NOT NULL,
                files             INTEGER NOT NULL DEFAULT 0,
                added             INTEGER NOT NULL DEFAULT 0,
                deleted           INTEGER NOT NULL DEFAULT 0,
                generated_added   INTEGER NOT NULL DEFAULT 0,
                generated_deleted INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (repo_id, sha)
            );--split--
            CREATE INDEX ix_commit_person ON commit_stat(person_id, authored_at);--split--
            CREATE TABLE stats_run (
                repo_id  TEXT PRIMARY KEY REFERENCES repo(id) ON DELETE CASCADE,
                ran_at   TEXT NOT NULL,
                head_sha TEXT,
                commits  INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),

            // v38 — de quién era el PR que se revisó.
            //
            // Sin esto no se puede decir a quién le tocó cada hallazgo: la review guarda el título
            // y el commit del PR, pero no su autor, y `pr_cache` sólo tiene los que siguen
            // abiertos. Medido en esta base: de 12 PRs revisados, apenas 7 tienen autor conocido,
            // y 205 de 264 comentarios están en PRs de los que no sabemos de quién eran.
            //
            // Se rellena lo que se pueda desde `pr_cache` y de acá en más se guarda al arrancar
            // cada review. Lo viejo no se puede recuperar sin volver a pedirle el histórico al
            // proveedor, así que las métricas por persona van a decir sobre qué parte se calculan
            // en vez de disimular el agujero.
            """
            ALTER TABLE review ADD COLUMN pr_author TEXT;--split--
            UPDATE review SET pr_author = (
                SELECT p.author FROM pr_cache p
                 WHERE p.repo_id = review.repo_id AND p.pr_id = review.pr_id
            ) WHERE pr_author IS NULL
            """.trimIndent(),

            // v39 — el histórico de pull requests, para poder contar los que ya se cerraron.
            //
            // `pr_cache` sirve a la pantalla de PRs abiertos y se reemplaza en cada sincronización;
            // esto es otra cosa: una fila por PR que se queda, incluidos los mergeados y
            // rechazados. Sin ella no se puede decir cuántos abrió cada uno ni cuánto tardaron en
            // cerrarse, porque el proveedor devuelve los cerrados sólo si se los pide y son
            // cientos —148 contra 4 abiertos en un repositorio de esta instalación—.
            //
            // `closed_on` es cuándo dejó de estar abierto. En Bitbucket sale de `updated_on`, que
            // para un PR cerrado es la última actividad y coincide con el cierre; no hay una fecha
            // de merge propiamente dicha en el listado. La pantalla lo aclara en vez de presentar
            // una precisión que el dato no tiene.
            """
            CREATE TABLE pr_stat (
                repo_id    TEXT NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
                pr_id      INTEGER NOT NULL,
                author     TEXT NOT NULL,
                title      TEXT,
                state      TEXT NOT NULL,
                created_on TEXT NOT NULL,
                closed_on  TEXT,
                synced_at  TEXT NOT NULL,
                PRIMARY KEY (repo_id, pr_id)
            );--split--
            CREATE INDEX ix_pr_stat_author ON pr_stat(author, created_on)
            """.trimIndent(),

            // v40 — quién sigue en el equipo.
            //
            // La spec dejó esto como pregunta abierta: si alguien que se fue se archiva y sale de
            // los agregados, o si sigue apareciendo en los períodos en que sí trabajó. Lo segundo
            // es más fiel al histórico y lo primero es más útil para leer, y la respuesta correcta
            // resultó ser las dos: se archiva y desaparece de los reportes por defecto, pero sus
            // datos no se borran y hay un interruptor para volver a verlos.
            //
            // Borrarlos sería peor de lo que parece: los commits quedarían sin persona y los
            // totales del equipo bajarían sin que nadie entienda por qué, incluso en trimestres
            // viejos donde esa persona sí estuvo.
            """
            ALTER TABLE person ADD COLUMN archived INTEGER NOT NULL DEFAULT 0
            """.trimIndent(),

            // v41 — retrabajo: los commits que llegaron después de que revisamos.
            //
            // Hace falta la rama del PR para poder preguntarle a git qué vino después del commit
            // que miramos; sin ella sólo se sabe la fecha, y la fecha no distingue entre un commit
            // de esa rama y cualquier otro del repositorio.
            //
            // `commits_after_review` se guarda en vez de calcularse en cada apertura porque es una
            // llamada a git por pull request. Null significa "todavía no se calculó", que es
            // distinto de cero: cero es "no hubo correcciones" y null es "no sé".
            """
            ALTER TABLE pr_stat ADD COLUMN source_branch TEXT;--split--
            ALTER TABLE pr_stat ADD COLUMN commits_after_review INTEGER;--split--
            ALTER TABLE pr_stat ADD COLUMN rework_at TEXT
            """.trimIndent(),

            // v42 — una foto por día y por repositorio, para poder ver tendencias.
            //
            // Todo lo que la app sabe hoy es un "ahora": cuántos PRs hay abiertos, cuántas
            // respuestas esperan. Con eso no se puede contestar la pregunta que importa cuando
            // algo se acumula —¿viene subiendo o bajando?— y en esta base hay 69 respuestas sin
            // contestar sin forma de saber si son de esta semana o de hace dos meses.
            //
            // Una fila por día y no por cambio: la deuda de revisión se mira en días, guardar cada
            // variación llenaría la tabla para dibujar exactamente la misma línea.
            """
            CREATE TABLE repo_snapshot (
                repo_id         TEXT NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
                day             TEXT NOT NULL,
                open_prs        INTEGER NOT NULL DEFAULT 0,
                pending_replies INTEGER NOT NULL DEFAULT 0,
                unverified      INTEGER NOT NULL DEFAULT 0,
                unpublished     INTEGER NOT NULL DEFAULT 0,
                open_findings   INTEGER NOT NULL DEFAULT 0,
                cost_usd        REAL NOT NULL DEFAULT 0,
                PRIMARY KEY (repo_id, day)
            )
            """.trimIndent(),

            // v43 — qué clase de problema es cada hallazgo.
            //
            // La gravedad dice cuán urgente y la categoría qué clase de cosa es: son ejes
            // distintos y hacían falta los dos. Para quien recibe el comentario no es lo mismo
            // "esto rompe la lógica de negocio" que "esto convendría resolverlo con otro patrón",
            // aunque los dos lleguen marcados como importantes; lo primero se arregla antes de
            // mergear y lo segundo se discute.
            //
            // Null en los hallazgos viejos: no se puede clasificar hacia atrás sin volver a correr
            // la review, y adivinar la categoría a partir del título daría etiquetas equivocadas
            // con aire de dato.
            """
            ALTER TABLE finding ADD COLUMN category TEXT
            """.trimIndent(),

            // v44 — los tickets de Jira que menciona cada pull request.
            //
            // Se guardan y no se piden cada vez porque el contenido de un ticket casi no cambia
            // mientras el PR está abierto, y pedirlo en cada apertura de pantalla sería una
            // llamada por PR contra un servicio que no controlamos.
            //
            // La descripción se guarda aplanada a texto: lo que hace falta es qué se pidió, y
            // para eso el formato no aporta. Además así entra directo en el prompt de la review.
            """
            CREATE TABLE jira_issue (
                key         TEXT PRIMARY KEY,
                summary     TEXT NOT NULL,
                description TEXT NOT NULL,
                type        TEXT,
                status      TEXT,
                assignee    TEXT,
                url         TEXT,
                fetched_at  TEXT NOT NULL
            );--split--
            CREATE TABLE pr_issue (
                repo_id  TEXT NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
                pr_id    INTEGER NOT NULL,
                key      TEXT NOT NULL,
                PRIMARY KEY (repo_id, pr_id, key)
            )
            """.trimIndent(),

            // v45 — varios sitios de Jira, no uno.
            //
            // La primera versión asumía un solo Jira para todo, y los datos dicen otra cosa: los
            // tickets de estos repositorios salen de tres familias de proyectos —KS y POS, CON,
            // FIA, FIMA y TLOG, y FIS— que son de clientes distintos y viven en instancias
            // distintas. Con una sola configuración, dos de las tres quedaban afuera.
            //
            // El ruteo es por prefijo de proyecto y no por repositorio, porque un repositorio
            // puede mencionar tickets de varios proyectos a la vez: `talos-apirest` usa cuatro.
            //
            // El token va cifrado con la misma clave que los del proveedor de git: es una
            // credencial de la misma clase.
            """
            CREATE TABLE jira_site (
                id           TEXT PRIMARY KEY,
                name         TEXT NOT NULL,
                base_url     TEXT NOT NULL,
                email        TEXT NOT NULL,
                token_cipher BLOB,
                projects     TEXT NOT NULL DEFAULT '',
                created_at   TEXT NOT NULL
            )
            """.trimIndent(),

            // v46 — implementaciones: construir una feature entera a partir de sus specs.
            //
            // Es lo primero en esta app que ESCRIBE código. Todo lo demás corre Claude con
            // permisos de sólo lectura; esto no puede. Por eso cada implementación tiene su propia
            // rama —nunca la de trabajo— y cada tarea que termina bien queda commiteada: si la
            // número siete falla, las seis anteriores siguen ahí y se puede seguir desde ahí en
            // vez de perder todo.
            //
            // Las tareas se guardan aparte y no como un JSON adentro de la implementación porque
            // cada una tiene su estado, su duración y su commit, y eso cambia mientras corre: en
            // un blob habría que reescribir el documento entero por cada avance.
            """
            CREATE TABLE implementation (
                id            TEXT PRIMARY KEY,
                repo_id       TEXT NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
                title         TEXT NOT NULL,
                sources       TEXT NOT NULL,
                extra_prompt  TEXT,
                branch        TEXT,
                base_branch   TEXT,
                status        TEXT NOT NULL,
                plan_summary  TEXT,
                plan_model    TEXT,
                code_model    TEXT,
                error         TEXT,
                cost_usd      REAL,
                created_at    TEXT NOT NULL,
                planned_at    TEXT,
                finished_at   TEXT
            );--split--
            CREATE TABLE impl_task (
                id            TEXT PRIMARY KEY,
                impl_id       TEXT NOT NULL REFERENCES implementation(id) ON DELETE CASCADE,
                seq           INTEGER NOT NULL,
                title         TEXT NOT NULL,
                detail        TEXT NOT NULL DEFAULT '',
                depends_on    TEXT NOT NULL DEFAULT '',
                size          TEXT,
                estimate_min  INTEGER,
                status        TEXT NOT NULL,
                commit_sha    TEXT,
                result        TEXT,
                error         TEXT,
                cost_usd      REAL,
                started_at    TEXT,
                finished_at   TEXT
            );--split--
            CREATE INDEX ix_impl_task ON impl_task(impl_id, seq)
            """.trimIndent(),

            // v47 — lo único que interrumpe una implementación autónoma: una decisión que las
            // specs no cubren.
            //
            // El módulo corre solo de punta a punta, pero hay una clase de cosas que no puede
            // decidir por su cuenta: arquitectura y negocio. Elegir un patrón de persistencia o
            // qué pasa cuando un pago se rechaza no es un detalle de implementación — es una
            // decisión que alguien va a tener que sostener después, y adivinarla produce código
            // que compila, pasa los tests y hace lo que no era.
            //
            // Lo demás —nombres, orden de los parámetros, cómo estructurar una función— se decide
            // solo: preguntar por eso convertiría la autonomía en un cuestionario.
            """
            CREATE TABLE impl_question (
                id          TEXT PRIMARY KEY,
                impl_id     TEXT NOT NULL REFERENCES implementation(id) ON DELETE CASCADE,
                task_id     TEXT,
                kind        TEXT NOT NULL,
                question    TEXT NOT NULL,
                context     TEXT,
                options     TEXT,
                answer      TEXT,
                asked_at    TEXT NOT NULL,
                answered_at TEXT
            );--split--
            CREATE INDEX ix_impl_question ON impl_question(impl_id, answered_at)
            """.trimIndent(),

            // v48 — una implementación puede abarcar varios repositorios.
            //
            // El caso que lo motiva es el cruzado: el contrato del backend tiene que existir antes
            // de que el frontend lo consuma. Con un plan por repositorio eso no se puede ordenar
            // —son dos listas que no se conocen— y alguien termina coordinando a mano cuál corre
            // primero, que es exactamente el trabajo que este módulo viene a sacar.
            //
            // Por eso el plan es UNO solo y cada tarea dice en qué repositorio corre: así el
            // planificador puede poner el endpoint antes que la pantalla que lo llama.
            //
            // El rol —backend, frontend, otro— no cambia la ejecución: le dice al planificador qué
            // es cada repositorio para que no proponga una pantalla en el backend.
            """
            CREATE TABLE impl_repo (
                impl_id TEXT NOT NULL REFERENCES implementation(id) ON DELETE CASCADE,
                repo_id TEXT NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
                role    TEXT NOT NULL DEFAULT 'OTHER',
                PRIMARY KEY (impl_id, repo_id)
            );--split--
            ALTER TABLE impl_task ADD COLUMN repo_id TEXT;--split--
            INSERT INTO impl_repo(impl_id, repo_id, role)
                SELECT id, repo_id, 'OTHER' FROM implementation
            """.trimIndent(),
        )
    }
}
