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

/**
 * Qué es cada repositorio dentro de una implementación.
 *
 * No cambia cómo se ejecuta: le dice al planificador con qué está tratando, para que no proponga
 * una pantalla en el backend ni una migración en el frontend. Con un solo repositorio da igual;
 * con varios es lo que hace que el plan tenga sentido.
 */
enum class RepoRole(val labelKey: String) {
    BACKEND("role.backend"),
    FRONTEND("role.frontend"),
    /** Móvil, infraestructura, librería compartida: lo que no entra en las dos anteriores. */
    OTHER("role.other"),
    ;

    companion object {
        fun fromApi(raw: String?): RepoRole =
            raw?.trim()?.uppercase()?.let { v -> entries.firstOrNull { it.name == v } } ?: OTHER
    }
}

/**
 * Un repositorio dentro de una implementación, con su rol y de qué rama parte.
 *
 * @param baseBranch null = la rama en la que esté parado el clon. Se puede fijar porque no todos
 *   los repositorios usan el mismo nombre —`develop` en unos, `main` en otros— y dejarlo al azar
 *   de lo que alguien tenga abierto hace que la rama nueva salga del lugar equivocado.
 */
data class ImplRepo(
    val repoId: String,
    val role: RepoRole,
    val baseBranch: String? = null,
)

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
    /**
     * Lo último que se pidió al revisar el plan.
     *
     * Se guarda para que la próxima revisión arranque de ahí en vez de en blanco: casi siempre se
     * revisa dos veces seguidas por lo mismo, y volver a escribirlo entero invita a escribir menos.
     */
    val reviewGuidance: String? = null,
    /**
     * Cuántas pasadas de revisión hacer sobre el código ya implementado.
     *
     * Un rango y no un número: no se sabe de antemano cuánto hay para encontrar. Se corre el mínimo
     * siempre y se sigue mientras la pasada anterior haya encontrado algo. Cinco pasadas sobre
     * código limpio son cinco corridas pagas para que digan "no encontré nada"; dos sobre código
     * con problemas se quedan cortas.
     */
    val reviewMin: Int = 2,
    val reviewMax: Int = 5,
    /**
     * Revisar también después de cada tarea, no sólo al final.
     *
     * Cuesta más —una pasada por tarea— pero encuentra el problema cuando todavía es de una tarea
     * sola. Un bug que sobrevive cinco tareas ya tiene código encima que depende de él.
     */
    val reviewEach: Boolean = false,
    /**
     * La rama la eligió una persona, no el modelo.
     *
     * Con esto el nombre sobrevive a una replanificación. Sin la marca no se puede distinguir —una
     * vez planificado, `branch` está lleno en los dos casos— y replanificar pisaría lo elegido.
     */
    val branchFixed: Boolean = false,
    val planModel: String?,
    val codeModel: String?,
    val error: String?,
    val costUsd: Double?,
    val createdAt: String,
    val plannedAt: String?,
    val finishedAt: String?,
)

/**
 * Una pasada de revisión sobre lo implementado.
 *
 * `taskId` en null es una pasada final, sobre la rama entera. Con tarea, es la revisión que corre
 * justo después de esa tarea.
 */
data class ReviewPass(
    val id: String,
    val implId: String,
    val taskId: String?,
    val pass: Int,
    val findings: Int,
    val fixed: Int,
    val summary: String?,
    val detail: String?,
    val commitSha: String?,
    val costUsd: Double?,
    val createdAt: String,
)

/**
 * Un paso dentro de una tarea: lo concreto que hay que hacer.
 *
 * Sirven para dos momentos distintos. Antes de correr, para ver si el plan entendió el problema:
 * una tarea de una línea puede esconder cinco decisiones. Mientras corre, para saber por dónde va.
 */
data class ImplStep(
    val id: String,
    val taskId: String,
    val seq: Int,
    val title: String,
    val status: TaskStatus,
    /** Qué pasó con este paso, cuando el modelo lo cuenta. */
    val note: String?,
    val createdAt: String,
    val updatedAt: String?,
    val startedAt: String?,
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
    /**
     * En qué repositorio corre.
     *
     * Una tarea vive en uno solo: escribir en dos a la vez haría imposible saber qué commit
     * corresponde a qué, y una tarea que toca backend y frontend a la vez son dos tareas con un
     * contrato en el medio.
     */
    val repoId: String?,
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
    /** Qué tocó, tomado del commit. Null en las tareas que no llegaron a commitear. */
    val diff: TaskDiff? = null,
    /**
     * El prompt con el que se le pidió el trabajo.
     *
     * Es lo único que explica por qué una tarea hizo lo que hizo: ante un resultado raro, sin esto
     * sólo queda adivinar si el problema fue el modelo o lo que se le pidió.
     */
    val prompt: String? = null,
    val createdAt: String? = null,
    /** Última vez que cambió algo suyo. Distinto de terminada: replanificar también la modifica. */
    val updatedAt: String? = null,
    val steps: List<ImplStep> = emptyList(),
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
 * Qué archivos tocó una tarea y cuánto código movió.
 *
 * Se separa creado de modificado porque no son lo mismo al revisar: cuatro archivos nuevos son
 * una superficie nueva que mirar entera, y dos modificados son un diff que leer. Un solo número
 * de "archivos tocados" borra esa diferencia.
 */
data class TaskDiff(
    val filesAdded: Int,
    val filesModified: Int,
    val filesDeleted: Int,
    val linesAdded: Int,
    val linesDeleted: Int,
    /** Una línea por archivo: `A|src/Foo.kt|120|0`. Es lo que muestra el detalle. */
    val files: List<FileChange>,
) {
    val filesTouched: Int get() = filesAdded + filesModified + filesDeleted
    val linesTouched: Int get() = linesAdded + linesDeleted
}

/** Un archivo del commit de una tarea. */
data class FileChange(val status: Char, val path: String, val added: Int, val deleted: Int)

/**
 * El esfuerzo total de una implementación: tiempo, código y archivos.
 *
 * Suma lo de las tareas terminadas. Las que fallaron no cuentan: su trabajo no quedó, y sumarlo
 * diría que se produjo algo que no está.
 */
data class Effort(
    val minutes: Double,
    val costUsd: Double,
    val filesAdded: Int,
    val filesModified: Int,
    val filesDeleted: Int,
    val linesAdded: Int,
    val linesDeleted: Int,
) {
    val filesTouched: Int get() = filesAdded + filesModified + filesDeleted
}

fun effortOf(tasks: List<ImplTask>): Effort {
    val hechas = tasks.filter { it.status == TaskStatus.DONE }
    return Effort(
        minutes = hechas.mapNotNull { it.actualMin }.sum(),
        costUsd = hechas.sumOf { it.costUsd ?: 0.0 },
        filesAdded = hechas.sumOf { it.diff?.filesAdded ?: 0 },
        filesModified = hechas.sumOf { it.diff?.filesModified ?: 0 },
        filesDeleted = hechas.sumOf { it.diff?.filesDeleted ?: 0 },
        linesAdded = hechas.sumOf { it.diff?.linesAdded ?: 0 },
        linesDeleted = hechas.sumOf { it.diff?.linesDeleted ?: 0 },
    )
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

/**
 * Cuánto lleva corriendo la tarea que está en curso, ahora mismo.
 *
 * `actualMin` sólo existe cuando la tarea terminó, así que mientras una corre no hay nada que
 * mostrar y la pantalla parece congelada durante minutos. Esto mide contra el reloj.
 */
fun ImplTask.runningMin(now: java.time.Instant = java.time.Instant.now()): Double? {
    if (status != TaskStatus.RUNNING) return null
    val a = startedAt ?: return null
    return runCatching {
        java.time.Duration.between(java.time.Instant.parse(a), now).toMillis() / 60_000.0
    }.getOrNull()?.coerceAtLeast(0.0)
}

/**
 * El avance como fracción, incluyendo lo que va de la tarea en curso.
 *
 * Contar sólo las terminadas hace que la barra se quede quieta durante toda una tarea —que puede
 * ser media hora— y después salte. Se le da crédito parcial según lo que lleva corriendo contra
 * lo que se estimó, **topeado al 90% de esa tarea**: sin tope, una tarea que se pasa de su
 * estimación empujaría la barra hasta completar algo que todavía no terminó, que es la forma más
 * fácil de que una barra de progreso mienta.
 */
fun Progress.fraction(tasks: List<ImplTask>, now: java.time.Instant = java.time.Instant.now()): Float {
    if (total == 0) return 0f
    val enCurso = tasks.filter { it.status == TaskStatus.RUNNING }.sumOf { t ->
        val estimado = (t.estimateMin ?: t.size?.minutes ?: 0).toDouble()
        val corriendo = t.runningMin(now) ?: 0.0
        if (estimado <= 0.0) 0.0 else (corriendo / estimado).coerceIn(0.0, 0.9)
    }
    return ((done + enCurso) / total).coerceIn(0.0, 1.0).toFloat()
}

/** Calcula el avance a partir de las tareas, sin tocar la base. */
fun progressOf(tasks: List<ImplTask>): Progress {
    val total = tasks.size
    val hechas = tasks.filter { it.status == TaskStatus.DONE }
    val estimadoTotal = tasks.sumOf { it.estimateMin ?: it.size?.minutes ?: 0 }
    // Se suma lo que lleva la tarea en curso: sin eso el "van N min" no se mueve mientras corre,
    // que es justo cuando alguien lo mira.
    val transcurrido = tasks.mapNotNull { it.actualMin }.sum() +
        tasks.mapNotNull { it.runningMin() }.sum()

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
