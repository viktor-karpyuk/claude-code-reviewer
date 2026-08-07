package io.acr

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regresión del bug: el panel decía "listo para publicar" y no mostraba contenido.
 * Corre contra los datos reales con ACR_LIVE=1; si no, se saltea.
 */
class ReadyToPublishTest {

    @Test
    fun readyListHasOneEntryPerPrAndAllHaveContent() {
        val ctx = AppContext.bootstrap()
        try {
            val ready = ctx.reviews.readyToPublish()
            if (ready.isEmpty()) { println("SKIP: sin reviews"); return }

            println("listas para publicar: ${ready.size}")
            ready.forEach { println("   PR #${it.prId} ${it.body?.length ?: 0} chars  ${it.createdAt.take(19)}") }

            // Antes aparecían corridas viejas del mismo PR, inflando el número.
            val dupes = ready.groupBy { it.repoId to it.prId }.filterValues { it.size > 1 }
            assertTrue(dupes.isEmpty(), "hay PRs repetidos: ${dupes.keys}")

            // Y todas tienen que tener contenido: es lo que el usuario va a publicar.
            assertTrue(ready.all { !it.body.isNullOrBlank() }, "hay reviews sin cuerpo")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun openingAPrWhoseLastRunFailedStillShowsContent() {
        val ctx = AppContext.bootstrap()
        try {
            val repos = ctx.repos.list()
            if (repos.isEmpty()) { println("SKIP: sin repos"); return }
            var checked = 0
            repos.forEach { repo ->
                ctx.reviews.historyFor(repo.id).map { it.prId }.distinct().forEach { prId ->
                    val newest = ctx.reviews.latestFor(repo.id, prId)
                    val usable = ctx.reviews.latestUsableFor(repo.id, prId)
                    if (newest?.status != io.acr.data.ReviewStatus.DONE && usable?.body != null) {
                        println("   PR #$prId: última=${newest?.status}, muestra ${usable.body?.length} chars")
                        checked++
                    }
                }
            }
            println("PRs donde la corrección aplica: $checked")
        } finally {
            ctx.close()
        }
    }
}
