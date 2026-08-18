package io.acr.impl

import java.io.File

/** Un documento de entrada: su nombre y su contenido. */
data class SourceDoc(val name: String, val content: String)

/**
 * Formatos que se leen de una carpeta de documentos.
 *
 * Markdown es lo habitual pero no lo único: las specs reales aparecen como `.txt` exportado de un
 * documento, `.rst` de una wiki, `.adoc` de un proyecto Java. Aceptar sólo `.md` hacía que una
 * carpeta llena de requerimientos se leyera como vacía, y el aviso "0 documentos" parecía un error
 * de la app cuando era una decisión suya.
 *
 * Un archivo elegido a mano se lee sea cual sea su extensión: si alguien lo señaló, quiere ese.
 */
private val TEXTO = setOf("md", "markdown", "mdx", "txt", "text", "rst", "adoc", "asciidoc", "org")

/**
 * Carpetas que no son documentación aunque estén adentro.
 *
 * Una carpeta de specs vive muchas veces dentro de un repositorio, y sin esto una sola elección
 * arrastraba miles de archivos de `node_modules` o de `build` — que no aportan nada y se comen el
 * presupuesto del prompt antes de llegar a la spec que importaba.
 */
private val BASURA = setOf(
    ".git", ".idea", ".vscode", ".gradle", "node_modules", "build", "target", "dist", "out",
    "vendor", "__pycache__", ".venv", "venv", ".next", ".cache",
)

/**
 * Junta los documentos que describen lo que hay que construir.
 *
 * Acepta archivos sueltos o una carpeta. Una carpeta se lee **entera**, con todas sus subcarpetas:
 * las specs de un proyecto casi nunca son un solo archivo ni un solo nivel — suelen ser
 * requerimientos, mockups, ADRs y un plan repartidos en subcarpetas, y pedirle a alguien que las
 * elija de a una es pedirle que arme a mano una lista que ya existe.
 *
 * Se ordenan por ruta y no por fecha: el orden alfabético de una carpeta de specs suele ser el que
 * eligió quien las escribió, con prefijos numéricos, y respetarlo cuesta nada.
 */
fun loadSources(paths: List<String>, maxPerFile: Int = 120_000): List<SourceDoc> =
    paths.flatMap { p ->
        val f = File(p)
        when {
            f.isDirectory -> f.walkTopDown()
                .onEnter { it.name !in BASURA && !(it.name.startsWith(".") && it.name.length > 1) }
                .filter { it.isFile && it.extension.lowercase() in TEXTO }
                .sortedBy { it.path }
                // Tope duro: una carpeta mal elegida —la raíz de un repositorio, por ejemplo—
                // puede tener miles de archivos de texto, y mandarlos todos no da un plan mejor,
                // da un prompt que el modelo no puede leer entero.
                .take(300)
                .toList()
            // Un archivo señalado a mano se lee sea cual sea su extensión: si alguien lo eligió,
            // quiere ese.
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
     *
     * `steps` es lo que convierte una tarea en algo revisable antes de correrla. `detail` dice qué
     * hay que lograr; los pasos dicen cómo, y ahí se ve si el plan entendió el problema: una tarea
     * de una línea puede esconder cinco decisiones y eso no se nota hasta leerla desarmada.
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
        "repo":{"type":"string"},
        "size":{"type":"string","enum":["S","M","L","XL"]},
        "estimate_min":{"type":"integer"},
        "steps":{"type":"array","items":{"type":"string"}}
      },"required":["seq","title","detail","repo","size","estimate_min","steps"]}}
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
        /** Nombre, rol y ruta local de cada repositorio de la implementación. */
        repos: List<Triple<String, String, String>>,
        baseBranch: String,
        language: String,
        /**
         * El plan anterior y en qué sentido revisarlo, cuando esto es una replanificación.
         *
         * Se le da el plan viejo en vez de pedirle uno nuevo desde cero porque revisar y volver a
         * empezar no dan el mismo resultado: desde cero se pierden las decisiones de orden que ya
         * estaban bien y aparecen otras distintas, y entonces no hay forma de saber si la revisión
         * mejoró algo o simplemente barajó de nuevo.
         */
        revision: Pair<String, String>? = null,
    ): String = """
        Sos un tech lead planificando una implementación. Devolvé ÚNICAMENTE el JSON que se
        describe al final.

        REPOSITORIOS (rama base: $baseBranch)
        ${repos.joinToString("\n") { (nombre, rol, ruta) -> "- $nombre — $rol — $ruta" }}

        Antes de planear, MIRÁ LOS REPOSITORIOS: su estructura, sus convenciones, qué ya existe. La
        lectura de archivos y los comandos de git de sólo lectura están autorizados. Un plan
        escrito sólo desde los documentos propone crear cosas que ya están y usa convenciones que
        estos proyectos no tienen, y eso se paga en cada tarea.

        ${if (repos.size > 1) """
        ESTO ABARCA VARIOS REPOSITORIOS
        Cada tarea corre en UNO solo: poné su nombre en `repo`, tal cual aparece arriba. Una tarea
        que toca dos son dos tareas con un contrato en el medio.

        El orden entre repositorios es lo que hace valioso planificar esto junto: lo que se
        consume tiene que existir antes. El endpoint antes de la pantalla que lo llama, el tipo
        compartido antes de los dos que lo usan. Si el frontend necesita un contrato que todavía
        no está, la tarea del backend va primero y la del frontend depende de ella.
        """ else "Todas las tareas corren en ${repos.first().first}; poné ese nombre en `repo`."}

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
        - `steps`: entre 2 y 8 pasos concretos por tarea, en el orden en que se hacen. Son el cómo:
          qué archivo se toca, qué se agrega, qué se corre para verificar. `detail` explica el qué y
          el por qué; los pasos tienen que poder tildarse uno por uno. Nada de "implementar la
          lógica" —eso repite el título—: si un paso no dice dónde ni qué, no es un paso.
        - Escribí en $language.

        ${revision?.let { (planViejo, guia) ->
        REVIEW_RULES + "\n\nPLAN ACTUAL\n" + planViejo +
            // La guía es complementaria, no la orden principal. Cuando no hay nada escrito, este
            // bloque no aparece: el criterio de revisión ya está completo arriba, y agregar un
            // "no hay nada puntual que corregir" sería ponerle palabras a alguien que no habló —y
            // el modelo las leería como una instrucción.
            guia.trim().takeIf { it.isNotBlank() }?.let { g ->
                "\n\nADEMÁS, ESTO PIDIÓ QUIEN LO MANDÓ A REVISAR\n" +
                    "Son acotaciones sobre lo de arriba, no en lugar de lo de arriba: el criterio de " +
                    "revisión sigue valiendo entero. Si algo de esto contradice ese criterio, mandan " +
                    "estas líneas — quien las escribió conoce el proyecto.\n\n" + g
            }.orEmpty()
    }.orEmpty()}

        QUÉ NO PONER EN EL PLAN
        - Tareas de "investigar" o "analizar": eso es parte de hacer la tarea.
        - Configurar herramientas que el repositorio ya tiene resueltas.
        - Tests como una tarea aparte al final: cada tarea deja lo suyo probado.
    """.trimIndent()

    /**
     * Lo que se le pide al modelo cuando revisa un plan en vez de escribir uno.
     *
     * Está acá afuera y no enterrado en el prompt porque la pantalla lo muestra: quien va a escribir
     * una guía de revisión necesita saber qué se le pide al modelo por defecto, o va a repetirlo con
     * otras palabras —"que las tareas sean más chicas" cuando eso ya está— y a gastar una corrida
     * en nada.
     */
    val REVIEW_RULES = """
        ESTO ES UNA REVISIÓN DE UN PLAN QUE YA EXISTE
        No estás empezando de cero. Abajo está el plan actual y lo que se pidió corregir. Devolvé el
        plan revisado completo, con la misma forma.

        Mantené lo que está bien: si el orden de unas tareas ya era correcto, dejalo. Cambiar lo que
        no hacía falta hace imposible saber si la revisión mejoró algo o sólo barajó de nuevo.

        Antes de cambiar nada, revisá el plan contra tres cosas:
        - **El orden.** ¿Alguna tarea necesita algo que recién aparece más adelante? Eso traba la
          implementación a la mitad y es lo más caro de descubrir corriendo.
        - **El tamaño.** ¿Alguna tarea esconde dos trabajos? Si para describirla hace falta un "y
          además", son dos. Y al revés: tres tareas que tocan el mismo archivo son una.
        - **Los pasos.** ¿Cada tarea dice cómo se hace, o repite el título en otras palabras? Un
          paso que no dice dónde ni qué no es un paso.

        Detallá más donde haga falta: una tarea con pasos vagos es una tarea que va a improvisar.

        Esto es lo que hay que hacer siempre, haya o no acotaciones de quien pidió la revisión.
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
      "steps_done":{"type":"array","items":{"type":"object","properties":{
        "seq":{"type":"integer"},
        "done":{"type":"boolean"},
        "note":{"type":"string"}
      },"required":["seq","done"]}},
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
        /**
         * Lo que quedó de un intento anterior de esta misma tarea.
         *
         * Null la primera vez. Con valor, esto no es una tarea nueva: es una que se cortó y hay que
         * seguirla. La diferencia importa más de lo que parece — sin decirlo, el modelo reescribe
         * desde cero archivos que ya estaban a mitad de camino y pisa lo que había quedado bien.
         */
        context: String? = null,
        /** Los archivos que el intento anterior dejó modificados en el árbol. */
        dirty: List<String>? = null,
    ): String = """
        Sos un desarrollador senior implementando UNA tarea de un plan. Trabajás sobre el
        repositorio en el que estás parado y podés leer, escribir archivos y correr comandos.

        LA TAREA (${task.seq} de ${all.size})
        ${task.title}

        ${task.detail}

        ${task.steps.takeIf { it.isNotEmpty() }?.let { pasos ->
        "LOS PASOS que se planificaron para esta tarea. Son una guía, no una jaula: si el " +
            "repositorio muestra que hay que hacerlo de otra forma, hacelo y contalo en la nota " +
            "del paso.\n" + pasos.joinToString("\n") { "  ${'$'}{it.seq}. ${'$'}{it.title}" }
    }.orEmpty()}

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

        ${context?.takeIf { it.isNotBlank() }?.let { c ->
        """
        ESTA TAREA YA SE EMPEZÓ
        Un intento anterior se cortó antes de terminar. **No arranques de cero**: lo de abajo ya
        está hecho, y rehacerlo pisa trabajo que estaba bien.

        $c

        ${dirty?.takeIf { it.isNotEmpty() }?.let { d ->
            "En el árbol de trabajo hay cambios sin commitear, de ese intento:\n" +
                d.joinToString("\n") { "  - $it" } +
                "\nMiralos antes de escribir: son tu propio trabajo a medio hacer, no de otro."
        }.orEmpty()}

        Empezá por verificar en qué estado quedó de verdad —leé los archivos, corré la compilación—
        y seguí desde ahí. Lo que ya esté bien, dejalo.
        """
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

        ${if (task.steps.isEmpty()) "" else """
        En `steps_done` poné una entrada por cada paso, con su número y si lo hiciste. Decí la
        verdad: un paso marcado hecho que no se hizo es peor que uno sin hacer y admitido, porque
        el que lea el resultado no va a volver a mirar. En `note` va qué pasó con ese paso cuando
        no fue lo obvio: por qué no hacía falta, o qué encontraste que lo cambió.
        """}
    """.trimIndent()

    /**
     * Qué devuelve una pasada de revisión.
     *
     * `fixed` aparte de `findings` porque no son lo mismo y confundirlos arruina el número: una
     * pasada que encuentra ocho cosas y arregla dos no hizo el mismo trabajo que una que encuentra
     * dos y arregla dos, y con un solo contador las dos se ven igual.
     */
    val REVIEW_SCHEMA = """
    {"type":"object","properties":{
      "summary":{"type":"string"},
      "findings":{"type":"array","items":{"type":"object","properties":{
        "kind":{"type":"string","enum":["BUG","PERFORMANCE","DESIGN","ARCHITECTURE","SECURITY","TEST"]},
        "severity":{"type":"string","enum":["HIGH","MEDIUM","LOW"]},
        "file":{"type":"string"},
        "what":{"type":"string"},
        "fixed":{"type":"boolean"},
        "why_not":{"type":"string"}
      },"required":["kind","severity","what","fixed"]}}
    },"required":["summary","findings"]}
    """.trimIndent()

    /**
     * Prompt de una pasada de revisión sobre el código ya implementado.
     *
     * Implementar y revisar son trabajos distintos, y el modelo que acaba de escribir algo es el
     * peor juez de ese algo: ya decidió que estaba bien. Esta pasada mira el diff completo con otra
     * intención —romperlo, no terminarlo— y por eso encuentra lo que la anterior no podía ver.
     *
     * Arregla lo que encuentra en vez de sólo reportarlo. Una lista de problemas que nadie va a
     * leer es trabajo tirado: el punto de correr esto sin supervisión es que la rama quede mejor,
     * no que quede documentada.
     */
    fun review(
        pass: Int,
        total: Int,
        /** El rango de commits a mirar: la base y la punta de la rama. */
        range: String,
        docs: List<SourceDoc>,
        scope: String?,
        previous: List<String>,
        language: String,
    ): String = """
        Sos un revisor senior mirando código recién escrito. Podés leer, escribir y correr comandos.

        QUÉ MIRAR
        ${scope ?: "Todo lo que cambió en esta rama: `git diff $range`."}

        Es la pasada $pass de hasta $total. Buscá, en este orden:

        1. **Bugs.** Casos borde sin cubrir, nulls, off-by-one, condiciones invertidas, errores que
           se tragan, estado que queda inconsistente si algo falla a la mitad.
        2. **Performance.** Consultas adentro de un bucle, trabajo repetido en cada recomposición o
           request, estructuras que se recorren de más, lo que no escala con el tamaño de los datos.
        3. **Diseño.** Lógica en la capa equivocada, duplicación que va a divergir, nombres que
           mienten, funciones que hacen dos cosas.
        4. **Arquitectura.** Lo que contradice cómo está armado el resto del proyecto: una
           dependencia al revés, un módulo hablándole a otro por atrás, un patrón nuevo donde ya
           había uno.
        5. **Tests.** Lo que se agregó y quedó sin probar, y los tests que pasan sin verificar nada.

        ${previous.takeIf { it.isNotEmpty() }?.let {
        "LO QUE YA ENCONTRARON LAS PASADAS ANTERIORES\nNo lo repitas; si algo de esto no quedó bien " +
            "arreglado, decilo:\n" + it.joinToString("\n") { p -> "- $p" }
    }.orEmpty()}

        CONTEXTO DE LO QUE SE ESTÁ CONSTRUYENDO
        ${docs.joinToString("\n\n") { "--- ${it.name} ---\n${it.content.take(15_000)}" }}

        QUÉ HACER CON LO QUE ENCONTRÁS
        Arreglalo. Una lista de problemas que nadie va a leer es trabajo tirado: el punto de esto es
        que la rama quede mejor, no que quede documentada.

        Con dos excepciones, que se reportan con `fixed: false` y el motivo en `why_not`:
        - Si arreglarlo es una decisión de arquitectura o de negocio que los documentos no cubren.
        - Si el arreglo es más grande que lo que se está revisando —una refactorización que toca
          media aplicación no entra en una pasada de revisión—.

        REGLAS
        - Terminá con el proyecto compilando y los tests en verde. Corré la compilación y los tests
          vos mismo.
        - No agregues features ni "mejoras" que nadie pidió: esto es una revisión, no una segunda
          implementación. Cambiar lo que funciona porque a vos te gusta más de otra forma hace
          imposible revisar el diff de la revisión.
        - No hagas commit: de eso se encarga la herramienta.
        - **Si no encontrás nada, decilo.** `findings` vacío es una respuesta legítima y esperada, y
          es lo que hace que las pasadas se corten. Inventar un hallazgo menor para justificar la
          pasada hace que la siguiente corra al pedo y que el número no signifique nada.
        - Escribí en $language.

        Devolvé el JSON del esquema.
    """.trimIndent()

    /** Lo que devuelve el análisis de los documentos. */
    val SPECS_SCHEMA = """
    {"type":"object","properties":{
      "summary":{"type":"string"},
      "document":{"type":"string"},
      "issues":{"type":"array","items":{"type":"object","properties":{
        "kind":{"type":"string","enum":["MISSING","AMBIGUOUS","CONTRADICTION","ASSUMPTION"]},
        "where":{"type":"string"},
        "what":{"type":"string"},
        "resolved":{"type":"boolean"}
      },"required":["kind","what","resolved"]}}
    },"required":["summary","document","issues"]}
    """.trimIndent()

    /**
     * Prompt para mejorar los documentos antes de planificar sobre ellos.
     *
     * Un plan no puede ser mejor que las specs de las que sale. Lo que las specs no dicen, el
     * planificador lo inventa —y lo inventa bien, con seguridad, sin marcarlo— así que el hueco
     * aparece recién cuando el código está escrito y hace otra cosa.
     *
     * **No reescribe los documentos originales.** Deja uno nuevo al lado con lo que falta, lo que
     * es ambiguo y lo que se contradice. Editar en el lugar borraría la versión que alguien escribió
     * y acordó con otros, y dejaría sin forma de saber qué se cambió: una spec es un acuerdo, no un
     * borrador de la app.
     */
    fun improveSpecs(
        docs: List<SourceDoc>,
        repos: List<Triple<String, String, String>>,
        extra: String?,
        language: String,
    ): String = """
        Sos un analista funcional revisando los documentos de los que va a salir una implementación.
        Sólo lectura: no escribas ni modifiques archivos.

        REPOSITORIOS
        ${repos.joinToString("\n") { (nombre, rol, ruta) -> "- $nombre — $rol — $ruta" }}

        Miralos antes de opinar. La mitad de lo que "falta" en una spec ya está resuelto en el
        código, y señalarlo como hueco hace que el planificador vuelva a construir algo que existe.

        LOS DOCUMENTOS
        ${docs.joinToString("\n\n") { "--- ${it.name} ---\n${it.content}" }}

        ${extra?.takeIf { it.isNotBlank() }?.let { "PARÁMETROS ADICIONALES, mandan sobre los documentos:\n$it" }.orEmpty()}

        QUÉ BUSCAR
        - **Lo que falta.** Casos borde sin definir, estados sin transición, errores sin
          comportamiento, permisos sin decir quién, datos sin decir qué pasa si no están.
        - **Lo ambiguo.** Frases que se pueden implementar de dos formas distintas y las dos cumplen
          lo escrito. Son las peores: nadie las nota hasta que el código hace la otra.
        - **Lo que se contradice.** Dos documentos que dicen cosas distintas sobre lo mismo, o un
          mockup que no coincide con el texto.
        - **Lo que se está asumiendo.** Lo que el documento da por sabido y no está en ningún lado.

        QUÉ DEVOLVER
        En `document`, un markdown completo y autocontenido —el que se va a guardar al lado de las
        specs y va a entrar en el plan—. Que resuelva lo que se pueda resolver mirando el código y
        el resto de los documentos, y que **liste como preguntas abiertas lo que no**. Resolver algo
        de negocio inventando la respuesta es exactamente lo que esto viene a evitar: si no está y
        no se deduce, va como pregunta.

        Empezalo con un título y una línea diciendo qué es y de qué documentos salió.

        En `issues`, cada cosa encontrada con `resolved` en true si el documento nuevo la contesta,
        o false si queda como pregunta abierta.

        No repitas los documentos originales: el que escribas se lee **además** de ellos, no en su
        lugar. Escribí en $language.
    """.trimIndent()

    /** Lo que devuelve la auditoría del plan contra los documentos. */
    val AUDIT_SCHEMA = """
    {"type":"object","properties":{
      "summary":{"type":"string"},
      "covered":{"type":"integer"},
      "issues":{"type":"array","items":{"type":"object","properties":{
        "kind":{"type":"string","enum":["MISSING","EXTRA","WRONG_ORDER","CONTRADICTS","VAGUE"]},
        "requirement":{"type":"string"},
        "task":{"type":"integer"},
        "what":{"type":"string"},
        "fix":{"type":"string"}
      },"required":["kind","what"]}}
    },"required":["summary","issues"]}
    """.trimIndent()

    /**
     * Prompt para auditar el plan contra los documentos.
     *
     * Planificar y verificar el plan son trabajos distintos, y el que planificó es mal juez: para
     * él el plan cubre todo, porque lo armó pensando eso. Esta pasada va al revés —de los
     * documentos al plan, requisito por requisito— que es el único orden en el que se ve lo que
     * quedó afuera. Yendo del plan a los documentos, lo que falta no aparece nunca: no hay tarea que
     * lo mencione.
     */
    fun auditPlan(
        docs: List<SourceDoc>,
        plan: String,
        repos: List<Triple<String, String, String>>,
        language: String,
    ): String = """
        Sos un tech lead auditando un plan de implementación contra los documentos que lo originaron.
        Sólo lectura.

        REPOSITORIOS
        ${repos.joinToString("\n") { (nombre, rol, ruta) -> "- $nombre — $rol — $ruta" }}

        Miralos: una tarea que "falta" puede estar ya resuelta en el código, y entonces no falta.

        LOS DOCUMENTOS
        ${docs.joinToString("\n\n") { "--- ${it.name} ---\n${it.content}" }}

        EL PLAN
        $plan

        CÓMO AUDITAR
        Andá **de los documentos al plan**, requisito por requisito, y por cada uno preguntá qué
        tarea lo cubre. Ese orden importa: yendo del plan a los documentos, lo que falta no aparece
        nunca, porque no hay ninguna tarea que lo mencione.

        Marcá:
        - `MISSING`: un requisito que ninguna tarea cubre.
        - `EXTRA`: una tarea que no sale de ningún requisito. No siempre está mal —puede ser trabajo
          técnico necesario— pero decilo, porque también es como se cuela trabajo que nadie pidió.
        - `WRONG_ORDER`: una tarea que necesita algo que recién aparece más adelante.
        - `CONTRADICTS`: una tarea que hace algo distinto de lo que el documento pide.
        - `VAGUE`: una tarea cuyo texto no alcanza para saber si cubre el requisito o no.

        En `covered` poné cuántos requisitos identificaste que sí están cubiertos. En `fix`, qué
        habría que cambiar en el plan: es lo que se usa después para replanificar.

        Si el plan está bien, decilo con `issues` vacío. Inventar un problema menor para justificar
        la pasada hace que el número deje de significar algo. Escribí en $language.
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
