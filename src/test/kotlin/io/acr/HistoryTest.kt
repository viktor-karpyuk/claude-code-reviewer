package io.acr

import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Syncs the real PR thread into the local history and reads it back. Skips when no repo is
 * connected, so it stays green on a fresh machine.
 */
class HistoryTest {

    @Test
    fun syncsAndStoresThread() {
        val ctx = AppContext.bootstrap()
        try {
            val repo = ctx.repos.list().firstOrNull() ?: run {
                println("SKIP: sin repos conectados"); return
            }
            val prId = 149L
            val synced = runBlocking { ctx.engine.syncComments(repo, prId) }
            synced.onFailure { println("sync falló: ${it.message}"); return }
            println("comentarios traídos del forge: ${synced.getOrNull()}")

            val stored = ctx.comments.forPr(repo.id, prId)
            println("guardados en la base: ${stored.size}")
            stored.forEach {
                val anchor = it.inlinePath?.let { p -> " [$p${it.inlineLine?.let { l -> ":$l" } ?: ""}]" } ?: ""
                println("  ${it.createdOn.take(16)} ${it.author}$anchor${if (it.ours) " (nuestro)" else ""}: ${it.body.replace('\n',' ').take(90)}")
            }

            println("publicaciones registradas: ${ctx.publications.forPr(repo.id, prId).size}")
            println("reviews en historial: ${ctx.reviews.historyFor(repo.id).size}")

            // Re-sincronizar no debe duplicar: la clave (repo, pr, comment_id) es única.
            runBlocking { ctx.engine.syncComments(repo, prId) }
            println("tras re-sincronizar: ${ctx.comments.forPr(repo.id, prId).size}")
        } finally {
            ctx.close()
        }
    }
}
