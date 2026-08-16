package io.acr.impl

/** En qué anda una implementación. */
enum class ImplStatus {
    /** Cargada, con sus documentos, sin plan todavía. */
    DRAFT,

    /** Fable 5 está leyendo las specs y armando las tareas. */
    PLANNING,

    /** Hay plan y espera que alguien lo mire antes de escribir código. */
    PLANNED,

    /** Opus 5 está implementando, tarea por tarea. */
    RUNNING,

    DONE,
    FAILED,

    /** Se frenó a mano. Lo hecho queda commiteado y se puede retomar. */
    STOPPED,

    /**
     * Todo lo que podía avanzar avanzó, y lo que falta espera una decisión.
     *
     * Es distinto de fallado: acá no hay nada roto, hay algo que alguien tiene que definir.
     */
    AWAITING,
}

/**
 * En qué anda una tarea.
 *
 * `BLOCKED` es lo único que frena una implementación autónoma: la tarea encontró una decisión de
 * arquitectura o de negocio que las specs no cubren y no la inventó. Se retoma sola en cuanto la
 * pregunta tiene respuesta.
 */
enum class TaskStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED, BLOCKED }

/** Qué clase de decisión hace falta. Sólo estas dos frenan: el resto se decide solo. */
enum class QuestionKind(val labelKey: String) {
    /** Cómo se estructura algo que va a quedar: capas, patrón, contrato, dependencia. */
    ARCHITECTURE("q.architecture"),

    /** Qué tiene que pasar según el negocio: una regla, un caso borde, una política. */
    BUSINESS("q.business"),
    ;

    companion object {
        fun fromApi(raw: String?): QuestionKind =
            raw?.trim()?.uppercase()?.let { v -> entries.firstOrNull { it.name == v } } ?: ARCHITECTURE
    }
}

/** Una decisión que la implementación no puede tomar sola. */
data class ImplQuestion(
    val id: String,
    val implId: String,
    val taskId: String?,
    val kind: QuestionKind,
    val question: String,
    val context: String?,
    /** Las alternativas que el modelo ve, para poder contestar eligiendo en vez de redactando. */
    val options: List<String>,
    val answer: String?,
    val askedAt: String,
    val answeredAt: String?,
)

/**
 * Tamaño de una tarea, tal como lo estima quien planifica.
 *
 * Se pide un tamaño además de los minutos porque las dos cosas se equivocan distinto: los minutos
 * son una apuesta sobre una máquina y un modelo concretos, y el tamaño relativo se sostiene aunque
 * la apuesta falle. Con el tamaño se puede reordenar el plan; con los minutos, sólo esperar.
 */
enum class TaskSize(val minutes: Int) {
    S(6),
    M(15),
    L(35),
    XL(75),
    ;

    companion object {
        fun fromApi(raw: String?): TaskSize? =
            raw?.trim()?.uppercase()?.let { v -> entries.firstOrNull { it.name == v } }
    }
}

/** Una implementación: qué hay que construir, dónde, y con qué se lo describió. */
data class Implementation(
    val id: String,
    val repoId: String,
    val title: String,
    /** Rutas de los `.md` que la describen, una por línea. */
    val sources: List<String>,
    /** Lo que no está en los documentos: decisiones, restricciones, preferencias. */
    val extraPrompt: String?,
    val branch: String?,
    val baseBranch: String?,
    val status: ImplStatus,
    val planSummary: String?,
    val planModel: String?,
    val codeModel: String?,
    val error: String?,
    val costUsd: Double?,
    val createdAt: String,
    val plannedAt: String?,
    val finishedAt: String?,
)

/**
 * Una tarea del plan.
 *
 * @param dependsOn números de tarea que tienen que estar listas antes. Se guarda aunque hoy la
 *   ejecución sea en orden: es lo que permite saber que una tarea fallida bloquea a otras en vez
 *   de seguir construyendo sobre algo que no existe.
 */
data class ImplTask(
    val id: String,
    val implId: String,
    val seq: Int,
    val title: String,
    val detail: String,
    val dependsOn: List<Int>,
    val size: TaskSize?,
    val estimateMin: Int?,
    val status: TaskStatus,
    val commitSha: String?,
    val result: String?,
    val error: String?,
    val costUsd: Double?,
    val startedAt: String?,
    val finishedAt: String?,
) {
    /** Cuánto tardó de verdad, en minutos. Null mientras no haya terminado. */
    val actualMin: Double?
        get() {
            val a = startedAt ?: return null
            val b = finishedAt ?: return null
            return runCatching {
                java.time.Duration.between(java.time.Instant.parse(a), java.time.Instant.parse(b))
                    .toMillis() / 60_000.0
            }.getOrNull()
        }
}

/**
 * Cuánto falta, según lo estimado y lo que ya se midió.
 *
 * La estimación inicial sale del planificador y es una apuesta. Apenas hay tareas terminadas se
 * corrige con lo real: si las primeras cinco tardaron el doble de lo estimado, lo que falta
 * también va a tardar el doble, y decir lo contrario sería sostener un número que ya se sabe malo.
 */
data class Progress(
    val total: Int,
    val done: Int,
    val failed: Int,
    val running: Int,
    val estimatedMin: Int,
    val elapsedMin: Double,
    val remainingMin: Double,
    /** Cuánto se está desviando lo real de lo estimado. 1.0 = clavado. Null si no hay con qué. */
    val drift: Double?,
)

/** Calcula el avance a partir de las tareas, sin tocar la base. */
fun progressOf(tasks: List<ImplTask>): Progress {
    val total = tasks.size
    val hechas = tasks.filter { it.status == TaskStatus.DONE }
    val estimadoTotal = tasks.sumOf { it.estimateMin ?: it.size?.minutes ?: 0 }
    val transcurrido = tasks.mapNotNull { it.actualMin }.sum()

    // El desvío se calcula sólo sobre las tareas que ya terminaron y tenían estimación: mezclar
    // las que corren daría un número que baja solo mientras la tarea avanza.
    val conEstimacion = hechas.filter { (it.estimateMin ?: it.size?.minutes) != null && it.actualMin != null }
    val estimadoDeEsas = conEstimacion.sumOf { (it.estimateMin ?: it.size?.minutes ?: 0).toDouble() }
    val realDeEsas = conEstimacion.sumOf { it.actualMin ?: 0.0 }
    val desvio = if (conEstimacion.isEmpty() || estimadoDeEsas <= 0.0) null else realDeEsas / estimadoDeEsas

    val faltanEstimado = tasks
        .filter { it.status == TaskStatus.PENDING || it.status == TaskStatus.RUNNING }
        .sumOf { (it.estimateMin ?: it.size?.minutes ?: 0).toDouble() }

    return Progress(
        total = total,
        done = hechas.size,
        failed = tasks.count { it.status == TaskStatus.FAILED },
        running = tasks.count { it.status == TaskStatus.RUNNING },
        estimatedMin = estimadoTotal,
        elapsedMin = transcurrido,
        remainingMin = faltanEstimado * (desvio ?: 1.0),
        drift = desvio,
    )
}
