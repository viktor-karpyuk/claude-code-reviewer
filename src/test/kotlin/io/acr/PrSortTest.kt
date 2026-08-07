package io.acr

import io.acr.forge.PullRequest
import io.acr.ui.prs.PrSort
import io.acr.ui.prs.sortedBy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Los proveedores devuelven la lista por actividad reciente, que para revisar es el orden inverso
 * al útil: el PR que lleva más tiempo abierto quedaba último. El default es empezar por los más
 * viejos, y el resto de los órdenes están para poder cambiarlo.
 */
class PrSortTest {

    private fun pr(id: Long, creado: String, actualizado: String) = PullRequest(
        id = id, title = "PR $id", author = "dev", sourceBranch = "f$id", targetBranch = "main",
        headSha = "sha$id", commentCount = 0, updatedOn = actualizado, createdOn = creado, url = "",
    )

    // Caso real medido en kubrik-erp-be: el #148 es el más viejo y el proveedor lo devuelve último.
    private val lista = listOf(
        pr(152, "2026-08-06 15:11", "2026-08-06 15:11"),
        pr(149, "2026-08-03 19:47", "2026-08-06 13:58"),
        pr(151, "2026-08-05 17:46", "2026-08-05 21:17"),
        pr(148, "2026-08-03 19:24", "2026-08-05 17:41"),
    )

    @Test
    fun oldestFirstIsTheDefault() {
        assertEquals(PrSort.OLDEST, PrSort.fromKey(null))
        assertEquals(PrSort.OLDEST, PrSort.fromKey("basura"))
        assertEquals(listOf(148L, 149L, 151L, 152L), lista.sortedBy(PrSort.OLDEST).map { it.id })
    }

    @Test
    fun theOtherOrdersAreReallyDifferent() {
        assertEquals(listOf(152L, 151L, 149L, 148L), lista.sortedBy(PrSort.NEWEST).map { it.id })
        // Por actividad el 149 sube: se creó primero pero tuvo commits hoy.
        assertEquals(listOf(148L, 151L, 149L, 152L), lista.sortedBy(PrSort.STALE).map { it.id })
        assertEquals(listOf(152L, 149L, 151L, 148L), lista.sortedBy(PrSort.ACTIVE).map { it.id })
    }

    @Test
    fun prsWithoutADateGoLastInsteadOfFirst() {
        // Un PR cacheado antes de que se guardara la fecha viene vacío. Vacío es "no sé", no
        // "año cero": si se ordenara tal cual encabezaría «más viejos» y uno empezaría por lo que
        // menos se sabe.
        val conHuerfano = lista + pr(200, "", "")
        val orden = conHuerfano.sortedBy(PrSort.OLDEST).map { it.id }
        assertEquals(200L, orden.last())
        assertEquals(listOf(148L, 149L, 151L, 152L, 200L), orden)
    }

    @Test
    fun sameDateIsBrokenDeterministically() {
        // Dos PRs con la misma fecha no pueden bailar de lugar entre refrescos.
        val empatados = listOf(
            pr(10, "2026-08-01 10:00", "2026-08-01 10:00"),
            pr(11, "2026-08-01 10:00", "2026-08-01 10:00"),
        )
        assertEquals(listOf(10L, 11L), empatados.sortedBy(PrSort.OLDEST).map { it.id })
        assertEquals(listOf(10L, 11L), empatados.reversed().sortedBy(PrSort.OLDEST).map { it.id })
        assertEquals(listOf(11L, 10L), empatados.sortedBy(PrSort.NEWEST).map { it.id })
        assertEquals(listOf(11L, 10L), empatados.reversed().sortedBy(PrSort.NEWEST).map { it.id })
    }
}
