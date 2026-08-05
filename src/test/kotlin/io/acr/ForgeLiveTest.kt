package io.acr

import io.acr.forge.BitbucketForge
import io.acr.forge.Provider
import io.acr.forge.RepoRecord
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Hits the real Bitbucket API. Skipped unless ACR_TEST_TOKEN is set, so CI and a fresh clone
 * stay green without credentials.
 */
class ForgeLiveTest {

    @Test
    fun listsOpenPullRequests() {
        val token = System.getenv("ACR_TEST_TOKEN")
        if (token.isNullOrBlank()) {
            println("SKIP: ACR_TEST_TOKEN not set")
            return
        }
        val repo = RepoRecord(
            id = "test",
            name = "kubrik-erp-be",
            provider = Provider.BITBUCKET,
            owner = "vkarp",
            slug = "kubrik-erp-be",
            localPath = "/Users/viktor/dev/kubrik/ks-erp/kubrik-erp-be",
            token = token,
        )
        val prs = runBlocking { BitbucketForge().listPullRequests(repo) }
        println("PRs abiertos: ${prs.size}")
        prs.take(5).forEach { println("  #${it.id} ${it.title} [${it.sourceBranch} -> ${it.targetBranch}] ${it.headSha.take(8)}") }
        assertTrue(prs.isNotEmpty(), "esperaba al menos un PR abierto")
    }
}
