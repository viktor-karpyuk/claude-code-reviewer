package io.acr

import io.acr.claude.Git
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class CommitsTest {

    @Test
    fun readsCommitsOfARealPr() {
        val dir = File("/Users/viktor/dev/kubrik/ks-erp/kubrik-erp-be")
        if (!Git.isRepo(dir)) { println("SKIP"); return }
        val commits = runBlocking { Git.commits(dir, "develop", "feature/pos-ar-fiscal") }
        println("commits: ${commits.size}")
        commits.take(3).forEach {
            println("  ${it.sha.take(8)} ${it.date} ${it.author}: ${it.subject.take(60)}")
            println("    cuerpo: ${it.body.replace('\n',' ').take(70)}")
        }
        assertTrue(commits.isNotEmpty())
        // El sha tiene que ser un sha completo y limpio: si el parseo se corre, arrastra basura.
        assertTrue(commits.all { it.sha.length == 40 && it.sha.all { ch -> ch.isLetterOrDigit() } },
            "sha mal parseado: ${commits.firstOrNull { it.sha.length != 40 }?.sha}")
        assertTrue(commits.all { it.subject.isNotBlank() })

        val first = commits.first()
        val files = runBlocking { Git.commitFiles(dir, first.sha) }
        println("archivos del commit ${first.sha.take(8)}: ${files.size}")
        assertTrue(files.isNotEmpty())
        val diff = runBlocking { Git.commitDiff(dir, first.sha, files.first().path) }
        println("diff del primer archivo: ${diff.lines().size} líneas")
        assertTrue(diff.isNotBlank())
    }
}
