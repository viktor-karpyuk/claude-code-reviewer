package io.acr.ui.prs

import io.acr.forge.PullRequest

/**
 * Cómo se ordena la lista de pull requests.
 *
 * El default es [OLDEST] a propósito: los proveedores devuelven la lista por actividad reciente,
 * que para revisar es el orden inverso al útil —el PR que lleva tres semanas abierto aparecía
 * último—. Lo primero que hay que mirar es lo que más tiempo lleva esperando.
 */
enum class PrSort(val key: String, val labelKey: String) {
    OLDEST("oldest", "prs.sort.oldest"),
    NEWEST("newest", "prs.sort.newest"),
    STALE("stale", "prs.sort.stale"),
    ACTIVE("active", "prs.sort.active"),
    ;

    companion object {
        fun fromKey(key: String?): PrSort = entries.firstOrNull { it.key == key } ?: OLDEST
    }
}

/**
 * Ordena sin perder los que no tienen fecha.
 *
 * Un PR cacheado antes de que existiera `created_on` viene con la fecha vacía. Vacío significa
 * "no sé", no "año cero": si se ordenara tal cual, esos irían primero en «más viejos» y el usuario
 * empezaría por lo que menos información tiene. Van siempre al final, en cualquier orden, y el
 * número de PR desempata para que la lista no baile entre refrescos.
 */
fun List<PullRequest>.sortedBy(sort: PrSort): List<PullRequest> {
    fun byDate(date: (PullRequest) -> String, ascending: Boolean): List<PullRequest> {
        val (conFecha, sinFecha) = partition { date(it).isNotBlank() }
        val ordenados = if (ascending) {
            conFecha.sortedWith(compareBy({ date(it) }, { it.id }))
        } else {
            conFecha.sortedWith(compareByDescending<PullRequest> { date(it) }.thenByDescending { it.id })
        }
        return ordenados + sinFecha.sortedByDescending { it.id }
    }
    return when (sort) {
        PrSort.OLDEST -> byDate({ it.createdOn }, ascending = true)
        PrSort.NEWEST -> byDate({ it.createdOn }, ascending = false)
        PrSort.STALE -> byDate({ it.updatedOn }, ascending = true)
        PrSort.ACTIVE -> byDate({ it.updatedOn }, ascending = false)
    }
}
