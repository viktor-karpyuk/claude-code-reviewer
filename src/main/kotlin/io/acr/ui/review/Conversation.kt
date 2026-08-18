package io.acr.ui.review

import io.acr.data.Finding
import io.acr.data.ReplyDraft
import io.acr.data.ReplyStatus
import io.acr.data.Resolution
import io.acr.data.StoredComment

/** Un mensaje del hilo, ya sea nuestro o de la otra persona. */
data class ThreadEntry(
    val author: String,
    val body: String,
    val ours: Boolean,
    val at: String,
)

/**
 * En qué estado está una pregunta. El orden del enum es el de urgencia: lo primero espera algo
 * tuyo, lo último no espera nada.
 */
enum class ThreadState(val labelKey: String) {
    /** Te contestaron y todavía no hay nada preparado. */
    NEEDS_ANSWER("thread.needsAnswer"),

    /** La IA redactó una respuesta y está esperando que la leas y la publiques. */
    DRAFT_READY("thread.draftReady"),

    /** El veredicto sobre el código dice que no se corrigió, o se corrigió a medias. */
    NOT_FIXED("thread.notFixed"),

    /**
     * Todavía no se publicó: la pregunta no llegó a hacerse.
     *
     * Va antes que [UNVERIFIED] a propósito: publicar depende sólo de vos, mientras que verificar
     * espera a que el otro suba algo. Además hace que al publicar la tarjeta baje en la lista en
     * vez de subir, que es lo que uno espera al terminar con ella —antes saltaba hacia arriba y
     * las de abajo se corrían mientras seguías publicando.
     */
    UNPUBLISHED("thread.unpublished"),

    /** Publicado, pero todavía nadie miró si los commits nuevos lo arreglan. */
    UNVERIFIED("thread.unverified"),

    /** Cerrado: descartado, o corregido y verificado. */
    OK("thread.ok"),
}

/**
 * Una pregunta y todo lo que pasó con ella.
 *
 * Es la unidad con la que uno revisa de verdad: qué señalamos, qué contestaron, qué preparamos
 * para responder y si el código quedó arreglado. Dispersa en cuatro pestañas —hallazgos, hilo,
 * respuestas, verificación— esa historia había que reconstruirla de memoria en cada PR.
 */
data class ConversationThread(
    val findingId: String?,
    val filePath: String?,
    val lineNo: Int?,
    val title: String,
    /** Gravedad tal como la devolvió la review: blocker, major o minor. */
    val severity: String,
    val question: String,
    /** Cómo se resolvería, si la review propuso algo. */
    val suggestion: String? = null,
    val entries: List<ThreadEntry>,
    val draft: ReplyDraft?,
    val resolution: Resolution?,
    val resolutionNote: String?,
    val state: ThreadState,
    /**
     * Hace cuántos días que esperamos respuesta, contando desde el último recordatorio si hubo
     * uno. Null cuando no estamos esperando a nadie.
     */
    val waitingDays: Long? = null,
    /** Cuándo se mandó el último recordatorio, si se mandó. */
    val followedUpAt: String? = null,
) {
    /** Lo accionable: todo lo que no está cerrado espera algo de alguien. */
    val open: Boolean get() = state != ThreadState.OK

    /** Vale la pena insistir: la pelota está del otro lado y pasó el plazo. */
    fun needsFollowUp(afterDays: Long): Boolean =
        open && waitingDays != null && waitingDays >= afterDays
}

/**
 * Arma los hilos a partir de lo que ya está guardado.
 *
 * Cada hallazgo publicado es la raíz de un hilo; las respuestas cuelgan por `parentId`, en cadena,
 * así que una respuesta a nuestra respuesta también entra. Los hallazgos sin publicar aparecen
 * igual, en estado propio: son preguntas que todavía no se hicieron, y esconderlas sería fingir
 * que el PR está más cerrado de lo que está.
 */
fun buildConversation(
    findings: List<Finding>,
    comments: List<StoredComment>,
    replies: List<ReplyDraft>,
    today: java.time.LocalDate = java.time.LocalDate.now(),
): List<ConversationThread> {
    val porId = comments.associateBy { it.commentId }
    val hijos = comments.filter { it.parentId != null }.groupBy { it.parentId!! }
    val draftPorComentario = replies.associateBy { it.theirCommentId }

    /** Recorre la cadena de respuestas en orden, saltando el nodo raíz. */
    fun descendientes(rootId: String): List<StoredComment> {
        val salida = mutableListOf<StoredComment>()
        val cola = ArrayDeque(hijos[rootId].orEmpty())
        while (cola.isNotEmpty()) {
            val c = cola.removeFirst()
            salida += c
            hijos[c.commentId]?.let { cola.addAll(it) }
        }
        return salida.sortedBy { it.createdOn }
    }

    return findings.map { f ->
        val raiz = f.publishedId?.let { porId[it] }
        val cadena = f.publishedId?.let { descendientes(it) }.orEmpty()
        // El borrador vigente es el de la última respuesta ajena. Si esa no tiene, se busca
        // cualquier otro sin publicar de la cadena: un borrador preparado para una respuesta
        // anterior seguía existiendo pero no se veía en ningún lado.
        val ultimaAjena = cadena.lastOrNull { !it.ours }
        val draft = ultimaAjena?.let { draftPorComentario[it.commentId] }
            ?: cadena.filter { !it.ours }
                .mapNotNull { draftPorComentario[it.commentId] }
                .lastOrNull { it.status != ReplyStatus.PUBLISHED }

        val hayRespuestaSinContestar = ultimaAjena != null &&
            // Una respuesta cerrada sin contestar deja de esperar algo nuestro: "corregido" o
            // "gracias" no piden una contestación.
            (draft == null || !draft.settled) &&
            cadena.none { it.ours && it.createdOn > ultimaAjena.createdOn }

        val estado = when {
            // Cerrado en la conversación: se habló y no espera ningún cambio. Es el caso del
            // comentario que sólo validaba una respuesta, no pedía tocar el código.
            f.dismissedAt != null || f.closedAt != null -> ThreadState.OK
            f.publishedId == null -> ThreadState.UNPUBLISHED
            hayRespuestaSinContestar && draft?.body.isNullOrBlank() -> ThreadState.NEEDS_ANSWER
            hayRespuestaSinContestar -> ThreadState.DRAFT_READY
            f.resolution?.closed == false ->
                ThreadState.NOT_FIXED
            f.resolution == null -> ThreadState.UNVERIFIED
            else -> ThreadState.OK
        }

        // Hace cuánto esperamos. La pelota es nuestra si lo último que hay en el hilo es de otro
        // —ahí esperamos nosotros, no ellos— así que sólo cuenta cuando lo último es nuestro.
        val ultimoNuestro = (listOfNotNull(raiz) + cadena).filter { it.ours }.maxByOrNull { it.createdOn }
        val esperandoDesde = if (hayRespuestaSinContestar || estado == ThreadState.OK) {
            null
        } else {
            // El reloj arranca en el último recordatorio si hubo uno: si no, la app ofrecería
            // insistir todos los días sobre algo que ya insististe ayer.
            listOfNotNull(f.followedUpAt, ultimoNuestro?.createdOn).maxOrNull()
        }
        val diasEsperando = esperandoDesde?.let { io.acr.ui.prs.ageInDays(it, today) }

        ConversationThread(
            findingId = f.id,
            filePath = f.filePath,
            lineNo = f.lineNo,
            title = f.title,
            severity = f.severity,
            question = raiz?.body ?: f.body,
            suggestion = f.suggestion,
            entries = cadena.map { ThreadEntry(it.author, it.body, it.ours, it.createdOn) },
            draft = draft,
            resolution = f.resolution,
            resolutionNote = f.resolutionNote,
            state = estado,
            waitingDays = diasEsperando,
            followedUpAt = f.followedUpAt,
        )
    }.sortedWith(compareBy({ it.state.ordinal }, { it.filePath }, { it.lineNo ?: 0 }))
}

/**
 * ¿El PR quedó listo para mergear?
 *
 * Es la misma pregunta que responde el botón, dicha desde la conversación: no queda ningún hilo
 * esperando algo. Se calcula aparte para poder mostrarlo como atributo del PR —en la lista, en el
 * panel— sin depender de tener la pantalla del PR abierta.
 */
fun conversationSettled(threads: List<ConversationThread>): Boolean =
    threads.isNotEmpty() && threads.none { it.open }

/** Color del estado: lo que espera algo tuyo se ve, lo cerrado se apaga. */
@androidx.compose.runtime.Composable
fun ThreadState.color(): androidx.compose.ui.graphics.Color = when (this) {
    ThreadState.NEEDS_ANSWER -> androidx.compose.material3.MaterialTheme.colorScheme.error
    ThreadState.DRAFT_READY -> androidx.compose.material3.MaterialTheme.colorScheme.primary
    ThreadState.NOT_FIXED -> androidx.compose.material3.MaterialTheme.colorScheme.error
    ThreadState.UNVERIFIED -> androidx.compose.ui.graphics.Color(0xFFD08A2C)
    ThreadState.UNPUBLISHED -> androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    ThreadState.OK -> io.acr.ui.VERDE_OK
}
