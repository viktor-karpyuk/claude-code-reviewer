package io.acr

import io.acr.claude.Git
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

class Pr151Test {
    @Test
    fun commitsOfPr151() {
        val dir = File("/Users/viktor/dev/kubrik/ks-erp/kubrik-erp-be")
        val commits = runBlocking { Git.commits(dir, "develop", "KS-600") }
        println("Git.commits devolvió: ${commits.size}")
        commits.forEach { println("  [${it.sha.take(8)}] [${it.author}] [${it.date}] [${it.subject}]") }

        val files = runBlocking { Git.numstat(dir, "origin/develop...origin/KS-600") }
        println("Git.numstat devolvió: ${files.size} archivos")
    }
}
