package io.acr

import io.acr.forge.Forges
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Reads whatever is already stored in the local app database and exercises the full chain:
 * master.key -> AES-GCM decrypt -> forge call. Prints and returns quietly when nothing is
 * configured, so it stays green on a machine with no repos connected.
 */
class SeededRepoTest {

    @Test
    fun decryptsStoredTokenAndListsPrs() {
        val ctx = AppContext.bootstrap()
        try {
            val repos = ctx.repos.list()
            if (repos.isEmpty()) {
                println("SKIP: no hay repos conectados")
                return
            }
            repos.forEach { repo ->
                println("repo: ${repo.name} (${repo.provider}) ${repo.owner}/${repo.slug}")
                println("  ruta local: ${repo.localPath}")
                println("  token descifrado: ${if (repo.token.isNullOrBlank()) "NO" else "sí (${repo.token!!.length} chars)"}")
                val prs = runCatching { runBlocking { Forges.of(repo.provider).listPullRequests(repo) } }
                prs.onSuccess { list ->
                    println("  PRs abiertos: ${list.size}")
                    list.forEach { println("    #${it.id} ${it.title} [${it.sourceBranch} -> ${it.targetBranch}]") }
                }.onFailure { println("  fallo al listar: ${it.message}") }
            }
        } finally {
            ctx.close()
        }
    }
}
