package io.acr

import kotlin.test.Test
import kotlin.test.assertTrue

class DashboardTest {

    @Test
    fun dashboardQueriesWork() {
        val ctx = AppContext.bootstrap()
        try {
            val ready = ctx.reviews.readyToPublish()
            val recent = ctx.reviews.recent()
            val usage = ctx.reviews.usage()
            val names = ctx.repos.list().associate { it.id to it.name }

            println("listas para publicar: ${ready.size}")
            ready.take(5).forEach { println("   ${names[it.repoId]} #${it.prId} — ${it.prTitle.take(50)}") }
            println("actividad reciente: ${recent.size}")
            recent.take(5).forEach {
                println("   ${it.createdAt.take(16)} ${names[it.repoId]} #${it.prId} ${it.status} " +
                    (it.costUsd?.let { c -> "US$ %.3f".format(c) } ?: ""))
            }
            println("costo estimado: US$ %.4f · tokens: %d".format(usage.costUsd, usage.tokens))

            // Lo que promete la vista: "listas" son DONE y sin publicar.
            assertTrue(ready.all { it.status == io.acr.data.ReviewStatus.DONE && it.publishedUrl == null })
            assertTrue(recent.size >= ready.size)
            assertTrue(usage.costUsd >= 0 && usage.tokens >= 0)
        } finally {
            ctx.close()
        }
    }
}
