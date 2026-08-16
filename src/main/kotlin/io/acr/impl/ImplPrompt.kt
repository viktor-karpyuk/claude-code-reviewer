package io.acr.impl

import java.io.File

/** Un documento de entrada: su nombre y su contenido. */
data class SourceDoc(val name: String, val content: String)

/**
 * Junta los documentos que describen lo que hay que construir.
 *
 * Acepta archivos sueltos o una carpeta —en cuyo caso toma los `.md` que haya adentro, incluidos
 * los de subcarpetas—, porque las specs de un proyecto casi nunca son un solo archivo: suelen ser
 * una carpeta con requerimientos, mockups y un plan.
 *
 * Se ordenan por ruta y no por fecha: el orden alfabético de una carpeta de specs suele ser el que
 * eligió quien las escribió, con prefijos numéricos, y respetarlo cuesta nada.
 */
fun loadSources(paths: List<String>, maxPerFile: Int = 120_000): List<SourceDoc> =
    paths.flatMap { p ->
        val f = File(p)
        when {
            f.isDirectory -> f.walkTopDown()
                .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
                .sortedBy { it.path }
                .toList()
            f.isFile -> listOf(f)
            else -> emptyList()
        }
    }.distinctBy { it.absolutePath }.mapNotNull { f ->
        val texto = runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        // Se recorta por archivo y no en el total: si un documento gigante se comiera el
        // presupuesto, los que vienen después no llegarían y nadie se enteraría de que faltan.
        SourceDoc(f.name, texto.take(maxPerFile))
    }

object ImplPrompt {

    /**
     * El plan que se le pide al modelo que razona.
     *
     * `depends_on` existe aunque la ejecución sea en orden: es lo que permite saber que una tarea
     * fallida bloquea a otras en vez de seguir construyendo sobre algo que no está.
     */
    val PLAN_SCHEMA = """
    {"type":"object","properties":{
      "summary":{"type":"string"},
      "branch":{"type":"string"},
      "tasks":{"type":"array","items":{"type":"object","properties":{
        "seq":{"type":"integer"},
        "title":{"type":"string"},
        "detail":{"type":"string"},
        "depends_on":{"type":"array","items":{"type":"integer"}},
        "size":{"type":"string","enum":["S","M","L","XL"]},
        "estimate_min":{"type":"integer"}
      },"required":["seq","title","detail","size","estimate_min"]}}
    },"required":["summary","branch","tasks"]}
    """.trimIndent()

    /**
     * Prompt de planificación.
     *
     * Va al modelo que mejor razona porque el orden de las tareas es la decisión que más cuesta
     * deshacer: si la número tres necesita algo que recién aparece en la nueve, la implementación
     * se traba a la mitad y hay que replanificar con la mitad del trabajo hecho.
     *
     * Se le pide explícitamente que mire el repositorio antes de planear. Un plan escrito sólo
     * desde las specs propone crear cosas que ya existen y usa convenciones que el proyecto no
     * tiene, y eso se paga tarea por tarea después.
     */
    fun plan(
        docs: List<SourceDoc>,
        extra: String?,
        repoName: String,
        baseBranch: String,
        language: String,
    ): String = """
        Sos un tech lead planificando una implementación. Devolvé ÚNICAMENTE el JSON que se
        describe al final.

        REPOSITORIO: $repoName (rama base: $baseBranch)

        Antes de planear, MIRÁ EL REPOSITORIO: su estructura, sus convenciones, qué ya existe. Los
        comandos de git de sólo lectura y la lectura de archivos están autorizados. Un plan escrito
        sólo desde los documentos propone crear cosas que ya están y usa convenciones que este
        proyecto no tiene, y eso se paga en cada tarea.

        LO QUE HAY QUE CONSTRUIR
        ${docs.joinToString("\n\n") { "--- ${it.name} ---\n${it.content}" }}

        ${extra?.takeIf { it.isNotBlank() }?.let { "PARÁMETROS ADICIONALES\nEsto no está en los documentos y manda sobre ellos:\n$it" }.orEmpty()}

        CÓMO ARMAR EL PLAN
        - Ordená las tareas por dependencia real, no por importancia. La primera tiene que poder
          hacerse sin nada previo, y cada una sólo depende de las anteriores.
        - Cada tarea tiene que terminar con el proyecto **compilando y con sus tests en verde**.
          Una tarea que deja el árbol roto obliga a la siguiente a arreglarlo antes de empezar, y
          si falla no se puede retomar desde ahí.
        - Que cada tarea sea una unidad que se pueda commitear sola y explicar en una línea. Si
          para describirla hace falta un "y además", son dos tareas.
        - Entre 5 y 25 tareas. Menos de cinco esconde el avance —todo parece pendiente hasta que
          está todo listo—; más de veinticinco casi siempre significa que se está planificando el
          detalle de la implementación en vez del camino.
        - `estimate_min` es tu apuesta de cuántos minutos le lleva a un modelo de código hacer esa
          tarea sola en este repositorio. Estimá de verdad; los números se comparan después contra
          lo que tardó.
        - `branch` es el nombre de la rama a crear, en minúsculas y con guiones, describiendo la
          feature. Sin barras iniciales.
        - `summary`: dos o tres oraciones sobre el enfoque y por qué ese orden. Es lo que alguien
          lee para decidir si deja correr esto sin mirar.
        - Escribí en $language.

        QUÉ NO PONER EN EL PLAN
        - Tareas de "investigar" o "analizar": eso es parte de hacer la tarea.
        - Configurar herramientas que el repositorio ya tiene resueltas.
        - Tests como una tarea aparte al final: cada tarea deja lo suyo probado.
    """.trimIndent()

    /**
     * Qué devuelve una tarea.
     *
     * Con esquema y no texto libre porque hay una decisión que la herramienta tiene que poder leer
     * sin interpretar: si la tarea terminó o si se topó con algo que no puede decidir sola. Buscar
     * eso en prosa sería adivinar, y equivocarse ahí significa o frenar de más o —peor— dar por
     * hecha una tarea que quedó a medias.
     */
    val TASK_SCHEMA = """
    {"type":"object","properties":{
      "status":{"type":"string","enum":["DONE","BLOCKED"]},
      "summary":{"type":"string"},
      "question":{"type":["object","null"],"properties":{
        "kind":{"type":"string","enum":["ARCHITECTURE","BUSINESS"]},
        "question":{"type":"string"},
        "context":{"type":"string"},
        "options":{"type":"array","items":{"type":"string"}}
      },"required":["kind","question"]}
    },"required":["status","summary"]}
    """.trimIndent()

    /**
     * Prompt de una tarea, para el modelo que escribe el código.
     *
     * Lleva el plan entero además de la tarea: sin saber qué viene después, el modelo resuelve lo
     * suyo de una forma que la tarea siguiente tiene que deshacer. Y lleva lo ya hecho, para que
     * no vuelva a escribir lo que ya está.
     */
    fun task(
        task: ImplTask,
        all: List<ImplTask>,
        docs: List<SourceDoc>,
        extra: String?,
        language: String,
        /** Preguntas ya contestadas: pregunta y respuesta. No se vuelven a preguntar. */
        decided: List<Pair<String, String>> = emptyList(),
    ): String = """
        Sos un desarrollador senior implementando UNA tarea de un plan. Trabajás sobre el
        repositorio en el que estás parado y podés leer, escribir archivos y correr comandos.

        LA TAREA (${task.seq} de ${all.size})
        ${task.title}

        ${task.detail}

        EL PLAN COMPLETO, para que sepas qué viene después y no resuelvas de una forma que la
        próxima tarea tenga que deshacer:
        ${all.joinToString("\n") { t ->
        val marca = when (t.status) {
            TaskStatus.DONE -> "[hecha]"
            TaskStatus.FAILED -> "[falló]"
            else -> if (t.seq == task.seq) "[ESTA]" else "[pendiente]"
        }
        "  $marca ${t.seq}. ${t.title}"
    }}

        CONTEXTO DE LO QUE SE ESTÁ CONSTRUYENDO
        ${docs.joinToString("\n\n") { "--- ${it.name} ---\n${it.content.take(20_000)}" }}

        ${extra?.takeIf { it.isNotBlank() }?.let { "PARÁMETROS ADICIONALES, mandan sobre los documentos:\n$it" }.orEmpty()}

        ${decided.takeIf { it.isNotEmpty() }?.let { d ->
        "DECISIONES YA TOMADAS\nSe preguntaron y se contestaron. Mandan sobre todo lo demás y no se " +
            "vuelven a preguntar:\n" + d.joinToString("\n") { "- ${it.first}\n  → ${it.second}" }
    }.orEmpty()}

        CUÁNDO PARAR A PREGUNTAR
        Trabajás sin supervisión y decidís vos casi todo: nombres, estructura de una función, orden
        de los parámetros, cómo organizar un archivo. Preguntar por eso convertiría esto en un
        cuestionario.

        Hay exactamente dos cosas que NO podés decidir solo cuando los documentos no las cubren:

        1. **Arquitectura**: cómo se estructura algo que va a quedar —qué capa, qué patrón, qué
           contrato entre módulos, incorporar una dependencia nueva—.
        2. **Negocio**: qué tiene que pasar según la regla del dominio —qué ocurre en un caso
           borde, qué se hace si un pago se rechaza, qué estado sigue a cuál—.

        Ahí devolvé `status: "BLOCKED"` con la pregunta, en vez de elegir por tu cuenta. Adivinar
        una de esas produce código que compila, pasa los tests y hace lo que no era, y el error se
        descubre mucho después. En `options` poné las alternativas que ves, para que se pueda
        contestar eligiendo. En `context` decí qué mirás y por qué los documentos no alcanzan.

        **Sólo si de verdad no está en los documentos.** Antes de bloquear, buscá: leé las specs de
        nuevo y mirá cómo lo resuelve el código que ya existe. Un bloqueo por algo que estaba
        escrito cuesta una espera entera para nada.

        Si te bloqueás, dejá el árbol como estaba o con lo que sí pudiste hacer compilando. No
        dejes a medias algo que no se pueda commitear.

        REGLAS
        - Hacé SÓLO esta tarea. Lo que corresponde a otra, dejalo para esa: adelantarse rompe el
          orden del plan y hace imposible saber qué quedó hecho.
        - Terminá con el proyecto compilando y con los tests en verde. Corré la compilación y los
          tests vos mismo antes de dar la tarea por terminada.
        - Seguí las convenciones que ya tiene el repositorio: mirá el código de al lado antes de
          elegir un estilo, un nombre o una estructura.
        - No hagas commit: de eso se encarga la herramienta cuando la tarea termina bien.
        - Si la tarea no se puede hacer como está descripta —falta algo, o el plan se equivocó—,
          decilo y no la fuerces. Es preferible una tarea fallida y explicada que código que
          aparenta cumplirla.
        - Escribí el código y los comentarios en el idioma que ya usa el repositorio. Explicá lo
          que hiciste en $language.

        Al terminar devolvé el JSON del esquema: `status` en "DONE" con un `summary` de pocas
        líneas contando qué cambiaste y por qué, o "BLOCKED" con la pregunta.
    """.trimIndent()

    /**
     * Herramientas de escritura.
     *
     * Es la diferencia grande con las reviews, que corren sólo con lectura. Acá hace falta
     * escribir, y por eso todo esto pasa en una rama propia y nunca en la de trabajo.
     *
     * Se permite `Bash` sin acotar a una lista de subcomandos: implementar necesita compilar,
     * correr tests y usar las herramientas del proyecto, y no se puede saber de antemano cuáles
     * son. La contención es la rama, no la lista.
     */
    val WRITE_TOOLS = listOf("Read", "Write", "Edit", "Grep", "Glob", "Bash", "Task", "TodoWrite")

    /** Lo que no se toca ni implementando: empujar al remoto es una decisión de una persona. */
    val DENIED_TOOLS = listOf("Bash(git push *)", "Bash(git reset --hard *)", "WebFetch", "WebSearch")
}
