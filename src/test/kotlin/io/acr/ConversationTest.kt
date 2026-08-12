package io.acr

import io.acr.data.Finding
import io.acr.data.ReplyDraft
import io.acr.data.ReplyStatus
import io.acr.data.Resolution
import io.acr.data.StoredComment
import io.acr.ui.review.ThreadState
import io.acr.ui.review.buildConversation
import io.acr.ui.review.conversationSettled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El hilo por pregunta: qué señalamos, qué contestaron, qué se preparó para responder y si el
 * código quedó arreglado. Antes esa historia estaba repartida en cuatro pestañas y había que
 * reconstruirla de memoria en cada PR.
 */
class ConversationTest {

    private fun finding(
        id: String,
        publicado: String? = "c$id",
        descartado: Boolean = false,
        resolucion: Resolution? = null,
        linea: Int = 1,
    ) = Finding(
        id, "rev", 7, "src/A.kt", linea, "major", "hallazgo $id", "cuerpo $id",
        publishedId = publicado, publishedUrl = null,
        dismissedAt = if (descartado) "2026-08-09T00:00:00Z" else null,
        resolution = resolucion,
    )

    private fun comment(id: String, autor: String, ours: Boolean, parent: String?, at: String) =
        StoredComment(id, autor, "texto de $id", "src/A.kt", 1, false, ours, at, parent)

    private fun draft(their: String, body: String?, estado: ReplyStatus) = ReplyDraft(
        "d-$their", "r", 7, their, "dev", "objeción", null, null, null, null, body, estado,
        null, null, null, "2026-08-09T00:00:00Z",
    )

    @Test
    fun aQuestionWithAnUnansweredReplyComesFirstAndAsksForAnAnswer() {
        val hilos = buildConversation(
            findings = listOf(finding("1", resolucion = Resolution.RESOLVED), finding("2", linea = 9)),
            comments = listOf(
                comment("c1", "yo", true, null, "2026-08-09T10:00"),
                comment("c2", "yo", true, null, "2026-08-09T10:00"),
                comment("r1", "dev", false, "c2", "2026-08-09T11:00"),
            ),
            replies = emptyList(),
        )
        // El que espera respuesta va primero, aunque el otro esté antes por archivo y línea.
        assertEquals("2", hilos.first().findingId)
        assertEquals(ThreadState.NEEDS_ANSWER, hilos.first().state)
        assertEquals(1, hilos.first().entries.size)
        assertEquals(ThreadState.OK, hilos.last().state)
    }

    @Test
    fun aDraftedAnswerWaitsForYouToPublishIt() {
        val hilos = buildConversation(
            listOf(finding("1")),
            listOf(
                comment("c1", "yo", true, null, "2026-08-09T10:00"),
                comment("r1", "dev", false, "c1", "2026-08-09T11:00"),
            ),
            listOf(draft("r1", "acá va la respuesta", ReplyStatus.DRAFTED)),
        )
        assertEquals(ThreadState.DRAFT_READY, hilos.single().state)
        assertEquals("acá va la respuesta", hilos.single().draft?.body)
    }

    @Test
    fun onceAnsweredItGoesBackToWaitingForTheCode() {
        // Contestada y publicada: lo que queda pendiente ya no es la conversación sino el código.
        val hilos = buildConversation(
            listOf(finding("1")),
            listOf(
                comment("c1", "yo", true, null, "2026-08-09T10:00"),
                comment("r1", "dev", false, "c1", "2026-08-09T11:00"),
                comment("a1", "yo", true, "r1", "2026-08-09T12:00"),
            ),
            listOf(draft("r1", "respondido", ReplyStatus.PUBLISHED)),
        )
        assertEquals(ThreadState.UNVERIFIED, hilos.single().state)
        // Y el hilo muestra las dos entradas, en orden.
        assertEquals(listOf(false, true), hilos.single().entries.map { it.ours })
    }

    @Test
    fun aReplyToOurReplyReopensTheThread() {
        // La segunda vuelta cuelga de nuestra respuesta, no del comentario original: si sólo se
        // miraran los hijos directos, esto no aparecería.
        val hilos = buildConversation(
            listOf(finding("1", resolucion = Resolution.RESOLVED)),
            listOf(
                comment("c1", "yo", true, null, "2026-08-09T10:00"),
                comment("r1", "dev", false, "c1", "2026-08-09T11:00"),
                comment("a1", "yo", true, "r1", "2026-08-09T12:00"),
                comment("r2", "dev", false, "a1", "2026-08-09T13:00"),
            ),
            listOf(draft("r1", "respondido", ReplyStatus.PUBLISHED)),
        )
        assertEquals(ThreadState.NEEDS_ANSWER, hilos.single().state)
        assertEquals(3, hilos.single().entries.size)
    }

    @Test
    fun theCodeVerdictShowsWhenTheConversationIsOver() {
        val sinCorregir = buildConversation(
            listOf(finding("1", resolucion = Resolution.UNRESOLVED)),
            listOf(comment("c1", "yo", true, null, "2026-08-09T10:00")),
            emptyList(),
        )
        assertEquals(ThreadState.NOT_FIXED, sinCorregir.single().state)

        val aMedias = buildConversation(
            listOf(finding("1", resolucion = Resolution.PARTIAL)),
            listOf(comment("c1", "yo", true, null, "2026-08-09T10:00")),
            emptyList(),
        )
        assertEquals(ThreadState.NOT_FIXED, aMedias.single().state)
    }

    @Test
    fun somethingUnpublishedIsAQuestionNeverAsked() {
        // Esconderlo sería fingir que el PR está más cerrado de lo que está.
        val hilos = buildConversation(listOf(finding("1", publicado = null)), emptyList(), emptyList())
        assertEquals(ThreadState.UNPUBLISHED, hilos.single().state)
        assertTrue(hilos.single().open)
    }

    @Test
    fun aDismissedOneIsClosed() {
        val hilos = buildConversation(
            listOf(finding("1", publicado = null, descartado = true)), emptyList(), emptyList(),
        )
        assertEquals(ThreadState.OK, hilos.single().state)
    }

    @Test
    fun aDraftForAnEarlierReplyIsStillVisible() {
        // Antes sólo se miraba el borrador de la ÚLTIMA respuesta ajena: uno preparado para una
        // anterior seguía existiendo en la base pero no aparecía en ningún lado.
        val hilos = buildConversation(
            listOf(finding("1")),
            listOf(
                comment("c1", "yo", true, null, "2026-08-09T10:00"),
                comment("r1", "dev", false, "c1", "2026-08-09T11:00"),
                comment("r2", "otro", false, "c1", "2026-08-09T12:00"),
            ),
            listOf(draft("r1", "respuesta preparada", ReplyStatus.DRAFTED)),
        )
        assertEquals("respuesta preparada", hilos.single().draft?.body)
        assertEquals(ThreadState.DRAFT_READY, hilos.single().state)
    }

    @Test
    fun everyThreadStateHasATranslation() {
        // Los estados se muestran; una clave sin traducir se vería como "thread.x".
        ThreadState.entries.forEach {
            assertTrue(
                io.acr.i18n.I18n.get(io.acr.i18n.Lang.ES, it.labelKey) != it.labelKey,
                "falta la traducción de ${it.labelKey}",
            )
            assertTrue(
                io.acr.i18n.I18n.get(io.acr.i18n.Lang.EN, it.labelKey) != it.labelKey,
                "falta la traducción al inglés de ${it.labelKey}",
            )
        }
    }

    private val hoy = java.time.LocalDate.of(2026, 8, 20)

    @Test
    fun itCountsHowLongWeHaveBeenWaiting() {
        val hilos = buildConversation(
            listOf(finding("1")),
            listOf(comment("c1", "yo", true, null, "2026-08-15T10:00")),
            emptyList(),
            today = hoy,
        )
        assertEquals(5L, hilos.single().waitingDays)
        assertTrue(hilos.single().needsFollowUp(3))
        assertFalse(hilos.single().needsFollowUp(7))
    }

    @Test
    fun theClockRestartsAfterAReminder() {
        // Sin esto la app ofrecería insistir todos los días sobre algo que ya insististe ayer.
        val conRecordatorio = finding("1").copy(followedUpAt = "2026-08-19T10:00")
        val hilos = buildConversation(
            listOf(conRecordatorio),
            listOf(comment("c1", "yo", true, null, "2026-08-01T10:00")),
            emptyList(),
            today = hoy,
        )
        assertEquals(1L, hilos.single().waitingDays)
        assertFalse(hilos.single().needsFollowUp(3), "volvió a pedir insistir al día siguiente")
    }

    @Test
    fun weDoNotChaseWhenTheBallIsOurs() {
        // Si lo último del hilo es de ellos, quien debe algo somos nosotros: recordarles sería
        // exactamente al revés.
        val hilos = buildConversation(
            listOf(finding("1")),
            listOf(
                comment("c1", "yo", true, null, "2026-08-01T10:00"),
                comment("r1", "dev", false, "c1", "2026-08-02T10:00"),
            ),
            emptyList(),
            today = hoy,
        )
        assertEquals(ThreadState.NEEDS_ANSWER, hilos.single().state)
        assertNull(hilos.single().waitingDays)
        assertFalse(hilos.single().needsFollowUp(1))
    }

    @Test
    fun nothingToChaseOnClosedThreads() {
        val resuelto = buildConversation(
            listOf(finding("1", resolucion = Resolution.RESOLVED)),
            listOf(comment("c1", "yo", true, null, "2026-08-01T10:00")),
            emptyList(),
            today = hoy,
        )
        assertNull(resuelto.single().waitingDays)
        assertFalse(resuelto.single().needsFollowUp(1))

        val descartado = buildConversation(
            listOf(finding("1", publicado = null, descartado = true)), emptyList(), emptyList(), hoy,
        )
        assertFalse(descartado.single().needsFollowUp(1))
    }

    @Test
    fun theReminderTextExistsInBothLanguages() {
        // El mensaje se publica en el PR: una clave sin traducir se vería como "followup.message".
        listOf(io.acr.i18n.Lang.ES, io.acr.i18n.Lang.EN).forEach { idioma ->
            val texto = io.acr.i18n.I18n.get(idioma, "followup.message")
            assertTrue(texto != "followup.message", "falta el mensaje en $idioma")
            assertTrue(texto.contains("%d"), "el mensaje en $idioma no dice hace cuánto")
        }
    }

    @Test
    fun aThreadClosedInConversationExpectsNothing() {
        // El comentario que sólo validaba una respuesta, o que quedó saldado hablando: no pide
        // cambios en el código. Antes quedaba esperando una corrección que nunca iba a llegar.
        val cerrado = finding("1", resolucion = null).copy(closedAt = "2026-08-09T00:00:00Z")
        val hilos = buildConversation(
            listOf(cerrado),
            listOf(comment("c1", "yo", true, null, "2026-08-01T10:00")),
            emptyList(),
            today = hoy,
        )
        assertEquals(ThreadState.OK, hilos.single().state)
        assertFalse(hilos.single().open)
        // Y deja de pedir seguimiento: no se espera nada del otro lado.
        assertNull(hilos.single().waitingDays)
        assertTrue(conversationSettled(hilos))
    }

    @Test
    fun theOrderPutsWhatDependsOnYouFirst() {
        // El orden del enum es el de la lista. Publicar depende sólo de vos; verificar espera a
        // que el otro suba algo. Y con este orden, al publicar la tarjeta baja en vez de subir:
        // antes saltaba hacia arriba y las de abajo se corrían mientras seguías publicando.
        assertEquals(
            listOf(
                ThreadState.NEEDS_ANSWER,
                ThreadState.DRAFT_READY,
                ThreadState.NOT_FIXED,
                ThreadState.UNPUBLISHED,
                ThreadState.UNVERIFIED,
                ThreadState.OK,
            ),
            ThreadState.entries.toList(),
        )
    }

    @Test
    fun publishingMovesTheCardDownNotUp() {
        // Dos hallazgos: el "1" en la línea 1 y el "2" en la 9. Mientras el 2 sigue sin publicar,
        // tiene que quedar por encima del 1 ya publicado, aunque por archivo y línea iría después.
        val hilos = buildConversation(
            listOf(finding("1", publicado = "c1"), finding("2", publicado = null, linea = 9)),
            listOf(comment("c1", "yo", true, null, "2026-08-01T10:00")),
            emptyList(),
            today = hoy,
        )
        assertEquals(ThreadState.UNPUBLISHED, hilos.first().state)
        assertEquals("2", hilos.first().findingId, "lo ya publicado quedó por encima de lo que falta")
        assertEquals(ThreadState.UNVERIFIED, hilos.last().state)
    }

    @Test
    fun readyToMergeMeansEveryThreadIsClosed() {
        val cerrados = buildConversation(
            listOf(finding("1", resolucion = Resolution.RESOLVED), finding("2", descartado = true, linea = 4)),
            listOf(comment("c1", "yo", true, null, "2026-08-09T10:00")),
            emptyList(),
        )
        assertTrue(conversationSettled(cerrados))

        val conUnoAbierto = buildConversation(
            listOf(finding("1", resolucion = Resolution.RESOLVED), finding("2", linea = 4)),
            listOf(comment("c1", "yo", true, null, "2026-08-09T10:00")),
            emptyList(),
        )
        assertFalse(conversationSettled(conUnoAbierto))

        // Sin hilos no hay nada que declarar listo: un PR sin review no está "resuelto".
        assertFalse(conversationSettled(emptyList()))
    }
}
