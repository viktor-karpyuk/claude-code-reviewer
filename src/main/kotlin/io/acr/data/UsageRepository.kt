package io.acr.data

import com.github.f4b6a3.ulid.UlidCreator
import java.time.Duration
import java.time.Instant

/** Una corrida del CLI y lo que consumió. */
data class UsageEvent(
    val kind: String,
    val model: String?,
    val sessionId: String?,
    val ok: Boolean,
    val seconds: Long?,
    val tokensIn: Long,
    val tokensOut: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val costUsd: Double?,
)

/** Lo consumido en una ventana de tiempo. */
data class UsageWindow(
    val runs: Int,
    val tokensIn: Long,
    val tokensOut: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val costUsd: Double,
    val seconds: Long,
) {
    /**
     * Los tokens que de verdad se pagan.
     *
     * Los de caché se cobran distinto y son casi siempre la mayoría del volumen: sumarlos junto con
     * el resto daría un número enorme que no se parece a nada. Se muestran aparte.
     */
    val billable: Long get() = tokensIn + tokensOut

    companion object {
        val VACIA = UsageWindow(0, 0, 0, 0, 0, 0.0, 0)
    }
}

/**
 * Cuánto se consumió, y cuándo.
 *
 * **Lo que esto puede afirmar y lo que no.** Cuenta lo que gastó esta app: sus reviews, sus
 * planificaciones, sus tareas. No ve lo que uno consume en su propia terminal ni sabe cuál es el
 * límite real de la cuenta — el CLI no lo expone, y el archivo de estadísticas que deja en disco no
 * trae límites y se actualiza cuando quiere. Inventar un porcentaje sobre un límite adivinado sería
 * lo peor de los dos mundos: un número que parece exacto y decide por vos.
 *
 * Así que el tope lo declara quien lo conoce. Lo que la app aporta es la cuenta fiel de su propio
 * consumo, que es la parte que ella causa y la única que puede medir.
 */
class UsageRepository(private val store: Store) {

    fun record(e: UsageEvent) {
        store.stmt(
            """INSERT INTO cli_usage(id, at, kind, model, session_id, ok, seconds,
                     tokens_in, tokens_out, cache_read, cache_write, cost_usd)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
        ) { ps ->
            ps.setString(1, UlidCreator.getUlid().toString())
            ps.setString(2, Instant.now().toString())
            ps.setString(3, e.kind)
            ps.setString(4, e.model)
            ps.setString(5, e.sessionId)
            ps.setInt(6, if (e.ok) 1 else 0)
            if (e.seconds == null) ps.setNull(7, java.sql.Types.INTEGER) else ps.setLong(7, e.seconds)
            ps.setLong(8, e.tokensIn)
            ps.setLong(9, e.tokensOut)
            ps.setLong(10, e.cacheRead)
            ps.setLong(11, e.cacheWrite)
            if (e.costUsd == null) ps.setNull(12, java.sql.Types.REAL) else ps.setDouble(12, e.costUsd)
            ps.executeUpdate()
        }
    }

    /**
     * Lo consumido desde un instante hasta ahora.
     *
     * Se agrega en SQL y no trayendo las filas: con una corrida por tarea y varias por review, esto
     * son miles de filas en una semana y lo que la pantalla muestra son seis números.
     */
    fun since(desde: Instant): UsageWindow =
        store.stmt(
            """SELECT COUNT(*), COALESCE(SUM(tokens_in),0), COALESCE(SUM(tokens_out),0),
                      COALESCE(SUM(cache_read),0), COALESCE(SUM(cache_write),0),
                      COALESCE(SUM(cost_usd),0), COALESCE(SUM(seconds),0)
                 FROM cli_usage WHERE at >= ?""",
        ) { ps ->
            ps.setString(1, desde.toString())
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    UsageWindow(
                        rs.getInt(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getDouble(6), rs.getLong(7),
                    )
                } else {
                    UsageWindow.VACIA
                }
            }
        }

    /**
     * La ventana de sesión: las últimas cinco horas.
     *
     * Cinco porque es la ventana con la que trabaja Claude Code. No es el límite de la cuenta —eso
     * no se puede leer— pero sí el período que importa para saber si conviene lanzar algo grande
     * ahora o esperar.
     */
    fun session(): UsageWindow = since(Instant.now().minus(Duration.ofHours(5)))

    /** Los últimos siete días, corridos y no de lunes a domingo: la pregunta es "cuánto llevo". */
    fun week(): UsageWindow = since(Instant.now().minus(Duration.ofDays(7)))

    /** Lo consumido por tipo de corrida en una ventana, para saber dónde se va. */
    fun byKind(desde: Instant): List<Triple<String, Int, Double>> =
        store.stmt(
            """SELECT kind, COUNT(*), COALESCE(SUM(cost_usd),0) FROM cli_usage
                WHERE at >= ? GROUP BY kind ORDER BY 3 DESC""",
        ) { ps ->
            ps.setString(1, desde.toString())
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(Triple(rs.getString(1), rs.getInt(2), rs.getDouble(3))) }
            }
        }

    /** Y por modelo: es la otra pregunta, porque no todos cuestan lo mismo. */
    fun byModel(desde: Instant): List<Triple<String, Int, Double>> =
        store.stmt(
            """SELECT COALESCE(model,'?'), COUNT(*), COALESCE(SUM(cost_usd),0) FROM cli_usage
                WHERE at >= ? GROUP BY model ORDER BY 3 DESC""",
        ) { ps ->
            ps.setString(1, desde.toString())
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(Triple(rs.getString(1), rs.getInt(2), rs.getDouble(3))) }
            }
        }

    /** La corrida más antigua que se registró. Sin esto, un total sin período no dice nada. */
    fun firstAt(): String? =
        store.stmt("SELECT MIN(at) FROM cli_usage") { ps ->
            ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }
}
