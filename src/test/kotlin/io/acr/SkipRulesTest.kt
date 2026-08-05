package io.acr

import io.acr.forge.PullRequest
import io.acr.forge.SkipRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkipRulesTest {

    private fun pr(title: String, author: String = "Alguien", target: String = "develop", draft: Boolean = false) =
        PullRequest(1, title, author, "rama", target, "sha", 0, "", "", draft)

    @Test
    fun skipsTheRealDoNotMergePrs() {
        val r = SkipRules()
        // Los dos que existen hoy en los repos conectados.
        assertTrue(r.skipReason(pr("DO NOT MERGE | POS-113 | company audit log")) != null)
        assertTrue(r.skipReason(pr("DO NOT MERGE | POS-114 | feat: fiscal admin")) != null)
        // Y uno normal se revisa.
        assertNull(r.skipReason(pr("Feature/pos ar fiscal")))
    }

    @Test
    fun matchingIsCaseInsensitiveAndConfigurable() {
        assertTrue(SkipRules(skipTitles = "wip").skipReason(pr("[WIP] algo")) != null)
        assertTrue(SkipRules(skipTitles = "spike,poc").skipReason(pr("POC de cache")) != null)
        // Vaciar la lista desactiva la regla.
        assertNull(SkipRules(skipTitles = "").skipReason(pr("DO NOT MERGE algo")))
    }

    @Test
    fun draftsAndAuthorsAndTargets() {
        assertEquals("es borrador", SkipRules().skipReason(pr("normal", draft = true)))
        assertTrue(SkipRules(skipAuthors = "Viktor Karpyuk").skipReason(pr("x", author = "viktor karpyuk")) != null)
        // Sólo hacia develop: un PR a master se saltea.
        val onlyDev = SkipRules(onlyTargets = "develop")
        assertNull(onlyDev.skipReason(pr("x", target = "develop")))
        assertTrue(onlyDev.skipReason(pr("x", target = "master")) != null)
        // Vacío = todas las ramas.
        assertNull(SkipRules(onlyTargets = "").skipReason(pr("x", target = "cualquiera")))
    }

    @Test
    fun reasonIsHumanReadable() {
        val why = SkipRules().skipReason(pr("DO NOT MERGE | algo"))
        println("motivo: $why")
        assertTrue(why!!.contains("DO NOT MERGE"), why)
    }
}
