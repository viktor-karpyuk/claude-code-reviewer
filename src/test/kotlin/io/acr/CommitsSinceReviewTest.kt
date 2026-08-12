package io.acr

import io.acr.claude.Git
import io.acr.ui.code.commitsSinceReview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Cuántos commits llegaron después del que se revisó. Es el dato que dice si el autor corrigió
 * algo o el PR está igual que cuando lo miramos.
 */
class CommitsSinceReviewTest {

    // Del más nuevo al más viejo, como los devuelve git.
    private val commits = listOf("eee", "ddd", "ccc", "bbb", "aaa").map {
        Git.Commit(sha = it.repeat(8), author = "dev", date = "2026-08-11", subject = it, body = "")
    }

    @Test
    fun countsWhatCameAfterTheReviewedCommit() {
        assertEquals(0, commitsSinceReview(commits, "eee".repeat(8)))
        assertEquals(2, commitsSinceReview(commits, "ccc".repeat(8)))
        assertEquals(4, commitsSinceReview(commits, "aaa".repeat(8)))
    }

    @Test
    fun shortAndLongShasMatch() {
        // El sha del PR viene completo y el de git a veces corto; comparar por igualdad estricta
        // daría "no lo encuentro" siempre.
        assertEquals(2, commitsSinceReview(commits, "ccc"))
        assertEquals(2, commitsSinceReview(commits, "cccccccccccccccccccccccc"))
    }

    @Test
    fun withoutAReviewedCommitItSaysSoInsteadOfGuessing() {
        assertNull(commitsSinceReview(commits, null))
        assertNull(commitsSinceReview(commits, ""))
    }

    @Test
    fun aRewrittenHistoryIsNotReportedAsZero() {
        // Tras un rebase el commit revisado ya no está en la rama. Decir "0 nuevos" haría creer
        // que nadie tocó nada, y "todos nuevos" tampoco es cierto: no se sabe.
        assertNull(commitsSinceReview(commits, "999".repeat(8)))
    }

    @Test
    fun anEmptyBranchDoesNotBreakIt() {
        assertNull(commitsSinceReview(emptyList(), "aaa"))
        assertNull(commitsSinceReview(emptyList(), null))
    }
}
