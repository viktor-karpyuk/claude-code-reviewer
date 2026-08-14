package io.acr

import io.acr.jira.JiraIssue
import io.acr.jira.issueKeysFor
import io.acr.jira.issueKeysIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sacar el ticket de un pull request y usarlo para revisar.
 *
 * Los casos salen de los repositorios conectados: ramas como `KS-655`, `POS-84`,
 * `FIS-389-auto-create-remediation` y `feature/KS-631`, conviviendo con otras que no mencionan
 * ningún ticket.
 */
class JiraTest {

    @Test
    fun theKeyIsFoundInTheUsualBranchShapes() {
        assertEquals(listOf("KS-655"), issueKeysIn("KS-655"))
        assertEquals(listOf("KS-631"), issueKeysIn("feature/KS-631"))
        assertEquals(listOf("FIS-389"), issueKeysIn("FIS-389-auto-create-remediation"))
        assertEquals(listOf("POS-84"), issueKeysIn("feat(POS-84): Wire the real Mercado Pago adapter"))
    }

    @Test
    fun lowercaseBranchesAreNotMistakenForTickets() {
        // Con minúsculas, estas ramas reales darían un falso positivo y la app mostraría un ticket
        // que no tiene nada que ver, o peor: uno que existe y es de otra cosa.
        assertEquals(emptyList(), issueKeysIn("tasks-ms-rabbit-placeholders"))
        assertEquals(emptyList(), issueKeysIn("feature/pos-ar-fiscal"))
        assertEquals(emptyList(), issueKeysIn("bugfixing"))
        assertEquals(emptyList(), issueKeysIn("develop"))
    }

    @Test
    fun theBranchWinsOverTheTitle() {
        // Caso real: el PR #154 tiene rama KS-655 y título "KS-644 history". La rama se crea al
        // empezar y casi nunca se toca; el título se edita a mano y se equivoca.
        val claves = issueKeysFor("KS-655", "KS-644 history | Sales-channel registry")
        assertEquals("KS-655", claves.first())
        assertTrue(claves.contains("KS-644"), "el del título no se descarta, queda después")
    }

    @Test
    fun aPrCanCloseMoreThanOneTicket() {
        // Quedarse con el primero escondería el otro.
        assertEquals(listOf("KS-10", "KS-11"), issueKeysIn("KS-10 y KS-11 juntos"))
    }

    @Test
    fun theSameKeyTwiceIsOneTicket() {
        assertEquals(listOf("POS-84"), issueKeysFor("POS-84", "feat(POS-84): algo"))
    }

    @Test
    fun noiseThatLooksLikeAKeyIsIgnored() {
        // Un hash o una fecha no son tickets.
        assertEquals(emptyList(), issueKeysIn("2026-08"))
        assertEquals(emptyList(), issueKeysIn("release/v1-2"))
    }

    @Test
    fun aBlankInputIsNotAnError() {
        assertEquals(emptyList(), issueKeysFor(null, null))
        assertEquals(emptyList(), issueKeysFor("", "   "))
    }

    // --- lo que llega al prompt ---

    private fun ticket(key: String, resumen: String, desc: String = "") =
        JiraIssue(key, resumen, desc, "Story", "In Progress", "Ana", "https://x/browse/$key")

    @Test
    fun theTicketReachesThePromptSoTheChangeCanBeCheckedAgainstIt() {
        // Es la diferencia entre "esto está bien escrito" y "esto hace lo que había que hacer".
        val seccion = io.acr.claude.ReviewPrompt.issuesSection(
            listOf(ticket("KS-654", "Pantallas de canales de venta", "Debe permitir alta y baja.")),
        )
        assertTrue(seccion.contains("KS-654"))
        assertTrue(seccion.contains("Pantallas de canales de venta"))
        assertTrue(seccion.contains("Debe permitir alta y baja"))
        assertTrue(seccion.contains("FUNCTIONAL"), "y dice cómo reportar si el cambio no cubre lo pedido")
    }

    @Test
    fun withoutJiraThePromptStaysExactlyAsItWas() {
        // La integración no puede cambiar el comportamiento de quien no la usa.
        assertEquals("", io.acr.claude.ReviewPrompt.issuesSection(emptyList()))
    }

    @Test
    fun aVeryLongTicketDoesNotEatThePrompt() {
        // Hay tickets con hilos enteros de discusión pegados; lo que sirve para revisar está arriba.
        val gigante = ticket("KS-1", "resumen", "x".repeat(50_000))
        val seccion = io.acr.claude.ReviewPrompt.issuesSection(listOf(gigante))
        assertTrue(seccion.length < 6_000, "se recorta: ${seccion.length}")
    }
}
