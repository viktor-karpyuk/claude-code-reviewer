package io.acr.claude

import io.acr.data.StoredComment
import io.acr.forge.PullRequest

object ReviewPrompt {

    /**
     * Esquema de salida. El CLI lo valida y reintenta si el modelo se desvía, así que los
     * hallazgos llegan siempre con su archivo; la línea es opcional porque hay observaciones
     * que son del archivo entero y forzar un número inventaría un ancla falsa.
     */
    val SCHEMA = """
    {"type":"object","properties":{
      "summary":{"type":"string"},
      "findings":{"type":"array","items":{"type":"object","properties":{
        "file":{"type":"string"},
        "line":{"type":["integer","null"]},
        "severity":{"type":"string","enum":["blocker","major","minor"]},
        "category":{"type":"string","enum":["FUNCTIONAL","BUG","DESIGN","CONVENTION"]},
        "title":{"type":"string"},
        "body":{"type":"string"},
        "suggestion":{"type":["string","null"]}
      },"required":["file","severity","category","title","body"]}}
    },"required":["summary","findings"]}
    """.trimIndent()

    /**
     * Igual que [SCHEMA] más el dictamen sobre cada hallazgo anterior.
     *
     * Los dos van juntos en una sola corrida a propósito: separar "¿qué hay de nuevo?" de "¿lo
     * anterior sigue en pie?" son dos lecturas del mismo diff y dos veces el mismo costo, que es
     * justamente lo que esta mejora viene a evitar.
     */
    val INCREMENTAL_SCHEMA = """
    {"type":"object","properties":{
      "summary":{"type":"string"},
      "findings":{"type":"array","items":{"type":"object","properties":{
        "file":{"type":"string"},
        "line":{"type":["integer","null"]},
        "severity":{"type":"string","enum":["blocker","major","minor"]},
        "category":{"type":"string","enum":["FUNCTIONAL","BUG","DESIGN","CONVENTION"]},
        "title":{"type":"string"},
        "body":{"type":"string"},
        "suggestion":{"type":["string","null"]}
      },"required":["file","severity","category","title","body"]}},
      "carried":{"type":"array","items":{"type":"object","properties":{
        "id":{"type":"string"},
        "verdict":{"type":"string","enum":["STILL_OPEN","FIXED","OBSOLETE"]},
        "line":{"type":["integer","null"]},
        "evidence":{"type":"string"}
      },"required":["id","verdict","evidence"]}}
    },"required":["summary","findings","carried"]}
    """.trimIndent()

    /** Veredicto por hallazgo, más el global. Los ids vuelven tal cual para poder anclar cada uno. */
    val RESOLUTION_SCHEMA = """
    {"type":"object","properties":{
      "summary":{"type":"string"},
      "mergeable":{"type":"boolean"},
      "items":{"type":"array","items":{"type":"object","properties":{
        "id":{"type":"string"},
        "resolution":{"type":"string","enum":["RESOLVED","PARTIAL","UNRESOLVED","WONT_FIX"]},
        "evidence":{"type":"string"}
      },"required":["id","resolution","evidence"]}}
    },"required":["summary","mergeable","items"]}
    """.trimIndent()

    /**
     * Prompt para verificar si lo que señalamos se corrigió.
     *
     * El trabajo es de evidencia, no de opinión: hay que mirar qué cambió entre el commit que se
     * revisó y el actual, y decir para cada hallazgo si eso lo arregla. Se le pide explícitamente
     * que no acepte una respuesta del autor como prueba —"ya lo arreglé" no es el código— porque
     * ese es el error que haría mergear algo sin corregir.
     */
    fun resolutionPrompt(
        language: String,
        prTitle: String,
        rangeSinceReview: String,
        items: String,
        thread: String,
    ): String = """
        Sos un revisor senior verificando si las observaciones de una review anterior fueron
        atendidas. Devolvé ÚNICAMENTE el JSON que se describe al final.

        PULL REQUEST: $prTitle
        CAMBIOS DESDE LA REVIEW: $rangeSinceReview

        Empezá por `git diff --stat $rangeSinceReview` para ver qué se tocó desde entonces, y
        después mirá el diff de los archivos que importan. Los comandos de git de sólo lectura ya
        están autorizados.

        OBSERVACIONES A VERIFICAR
        $items

        $thread

        REGLAS
        - Juzgá por el código, no por lo que alguien haya dicho. Que el autor conteste "ya está
          arreglado" no es evidencia; el diff sí lo es.
        - **Pero una respuesta directa a un comentario hay que contestarla.** Cuando debajo de un
          hallazgo aparece una `↳ RESPUESTA`, alguien se tomó el trabajo de explicar qué pasa con
          eso, y hay que juzgar lo que dice:
          · "ya lo arreglé" → comprobalo en el código. Si el código no lo muestra, UNRESOLVED, y en
            `evidence` decí que la respuesta dice una cosa y el diff otra.
          · "no aplica porque X", "es a propósito", "se hace en otro PR" → si el motivo se sostiene
            con lo que ves, es WONT_FIX con el motivo en `evidence`. Estas no se arreglan nunca en
            este diff, y dejarlas como UNRESOLVED para siempre hace que el estado no signifique
            nada: nadie vuelve a mirar una lista que nunca se vacía.
          · Si la respuesta no alcanza para decidir, UNRESOLVED y pedí lo que falta.
        - RESOLVED sólo si el cambio arregla lo señalado de verdad. Si atiende una parte, o lo
          mueve de lugar sin resolverlo, es PARTIAL.
        - UNRESOLVED si el código sigue igual o el cambio no tiene que ver **y nadie explicó por
          qué**.
        - En `evidence` citá lo concreto: archivo y línea, o el commit. Una frase, no un ensayo.
          Si es UNRESOLVED, decí qué falta hacer.
        - `mergeable` es true sólo si TODAS son RESOLVED o WONT_FIX y no encontrás nada nuevo que frene el
          merge. Ante la duda, false: mergear de más no se puede deshacer.
        - Escribí en $language.
    """.trimIndent()

    /** Bloqueantes de la pasada final. Vacío significa que no encontró nada que frene el merge. */
    val FINAL_PASS_SCHEMA = """
    {"type":"object","properties":{
      "summary":{"type":"string"},
      "mergeable":{"type":"boolean"},
      "blockers":{"type":"array","items":{"type":"object","properties":{
        "file":{"type":"string"},
        "line":{"type":["integer","null"]},
        "title":{"type":"string"},
        "body":{"type":"string"}
      },"required":["file","title","body"]}}
    },"required":["summary","mergeable","blockers"]}
    """.trimIndent()

    /**
     * Prompt de la última mirada antes de mergear.
     *
     * No es otra review: es una pregunta más chica y más dura —¿hay algo acá que no deba entrar a
     * la rama destino?—. Se le pasa lo que ya se discutió para que no lo repita, y se le pide
     * explícitamente que devuelva vacío cuando no encuentra nada: una pasada final que siempre
     * encuentra algo no sirve para decidir, porque nunca dejaría mergear.
     */
    fun finalPassPrompt(
        language: String,
        prTitle: String,
        range: String,
        yaDiscutido: String,
    ): String = """
        Sos un revisor senior haciendo la última mirada antes de mergear un pull request. Devolvé
        ÚNICAMENTE el JSON que se describe al final.

        PULL REQUEST: $prTitle
        Rango del diff: $range

        Los comandos de git de sólo lectura ya están autorizados. Empezá por
        `git diff --stat $range` y después mirá lo que importe.

        $yaDiscutido

        QUÉ BUSCAR
        Sólo lo que NO debería entrar a la rama destino tal como está:
        - Bugs que se disparan en un caso concreto que puedas describir.
        - Agujeros de seguridad o fugas de datos.
        - Pérdida de datos, migraciones destructivas o irreversibles.
        - Cambios incompatibles en un contrato que otros usan.
        - Restos de depuración: credenciales, endpoints de prueba, código comentado que se coló.

        QUÉ NO
        - Nada de lo que ya se discutió arriba.
        - Estilo, nombres, preferencias, cobertura de tests.
        - Mejoras posibles. La pregunta no es si se puede mejorar sino si frena el merge.

        REGLAS
        - `blockers` vacío y `mergeable` true es la respuesta esperada de un PR sano. No busques
          algo para justificar la corrida.
        - Cada bloqueante tiene que decir el caso concreto en el que rompe, con archivo y línea.
          Si no podés describir cómo falla, no es un bloqueante.
        - Escribí en $language.
    """.trimIndent()

    /**
     * Cuánto texto de convenciones entra en el prompt.
     *
     * Hay tope porque el contexto no es gratis: una guía de arquitectura de cien páginas empujaría
     * afuera el diff, que es lo que hay que revisar. Se recorta y se avisa, en vez de fallar o de
     * mandar todo y que la review pierda foco.
     */
    const val MAX_GUIDELINES_CHARS = 60_000

    /**
     * Las convenciones del equipo, para que la review no marque como problema lo que es una
     * decisión tomada.
     *
     * Las globales van primero y las del repositorio después: lo último que se lee es lo que
     * manda, así que un repositorio puede contradecir la regla general sin tener que editarla.
     */
    fun guidelinesSection(docs: List<io.acr.data.Guideline>): String {
        if (docs.isEmpty()) return ""
        val sb = StringBuilder()
        var restante = MAX_GUIDELINES_CHARS
        var recortados = 0
        docs.forEach { d ->
            if (restante <= 0) { recortados++; return@forEach }
            val ambito = if (d.global) "todos los repositorios" else "este repositorio"
            val cuerpo = if (d.content.length <= restante) d.content else {
                recortados++
                d.content.take(restante) + "\n[…recortado…]"
            }
            restante -= cuerpo.length
            sb.append("\n### ").append(d.name).append(" (").append(ambito).append(")\n")
            sb.append(cuerpo).append('\n')
        }
        val aviso = if (recortados > 0) {
            "\n(Se recortaron $recortados documento(s) por tamaño; puede faltar contexto.)\n"
        } else ""
        return """
        CONVENCIONES Y ARQUITECTURA DE ESTE EQUIPO
        Lo que sigue son decisiones ya tomadas, no sugerencias. NO las reportes como problemas:
        un nombre, una estructura o un patrón que las cumple está bien aunque vos harías otra cosa.
        Sí reportá el código que las CONTRADICE, citando cuál regla incumple.
        Cuando una regla de este repositorio contradiga una general, manda la del repositorio.
        $sb$aviso
        """.trimIndent()
    }

    /** Escribir en el árbol o salir a la red está fuera de alcance en cualquier nivel. */
    val DISALLOWED_TOOLS = listOf("Edit", "Write", "WebFetch", "WebSearch")

    /**
     * Renderiza el hilo existente para que la review no vuelva a reportar lo que ya se dijo.
     * Se trunca cada comentario: al revisor le sirve la afirmación, no el ensayo completo.
     */
    private fun threadSection(comments: List<StoredComment>): String {
        if (comments.isEmpty()) return ""
        val items = comments.joinToString("\n") { c ->
            val anchor = c.inlinePath?.let { " [$it${c.inlineLine?.let { l -> ":$l" } ?: ""}]" } ?: ""
            "- ${c.author}$anchor: ${c.body.replace('\n', ' ').take(400)}"
        }
        return """
        COMENTARIOS QUE YA ESTÁN EN EL PR
        $items

        No repitas ninguno de esos puntos. Si tu análisis coincide con uno ya planteado, omitilo:
        volver a decirlo es ruido. Sí podés contradecir uno si el código muestra lo contrario, y en
        ese caso explicá por qué.
        """.trimIndent()
    }

    /**
     * Prompt para contestar una respuesta a un comentario nuestro.
     *
     * Lo importante acá no es defender el hallazgo: es verificarlo de nuevo con lo que la persona
     * aporta. Si tiene razón, se concede sin rodeos; si no, se sostiene con evidencia del código,
     * no con autoridad. Un revisor que nunca cede es ruido.
     */
    fun replyPrompt(
        language: String,
        prTitle: String,
        range: String,
        ourComment: String?,
        theirAuthor: String,
        theirBody: String,
        filePath: String?,
        lineNo: Int?,
    ): String = """
        Sos un revisor de código senior contestando en el hilo de un pull request.

        PULL REQUEST: $prTitle
        Rango del diff: $range
        ${filePath?.let { "Anclado en: $it${lineNo?.let { l -> ":${'$'}l" } ?: ""}" } ?: ""}

        LO QUE COMENTAMOS NOSOTROS
        ${ourComment ?: "(no se conserva el texto original)"}

        LO QUE RESPONDIÓ $theirAuthor
        $theirBody

        QUÉ HACER
        1. Verificá su respuesta CONTRA EL CÓDIGO, no contra tu memoria: leé el archivo y el diff.
        2. Si tiene razón —total o parcialmente— decilo derecho y sin rodeos, y agradecé la
           corrección. Retractarse rápido vale más que defender un hallazgo flojo.
        3. Si el código sigue mostrando el problema, sostenelo con evidencia concreta: archivo,
           línea y el escenario exacto en el que se rompe. Nada de "podría llegar a pasar".
        4. Si su respuesta abre una pregunta que no podés resolver leyendo el código, decilo y
           preguntá lo puntual que falta.
        5. Si lo que plantea es una decisión de producto o de criterio, no una cuestión técnica,
           reconocelo y dejá la decisión de su lado.

        FORMATO
        Devolvé SOLO el texto de la respuesta, en $language, en Markdown, sin preámbulo ni firma.
        Breve: 2 a 6 oraciones. Es una respuesta en un hilo, no otra review.
        Tono de par, no de auditor. Nada de condescendencia ni de disculpas de más.
    """.trimIndent()

    /**
     * Prompt de una review que mira sólo lo que llegó después de la anterior.
     *
     * La diferencia con [build] no es sólo el rango: acá hay que hacer dos trabajos en una sola
     * lectura del diff —revisar lo nuevo y dictaminar sobre lo viejo— porque separarlos costaría
     * dos veces lo mismo que se está tratando de ahorrar.
     *
     * Se le da el rango completo además del corto, y no por costumbre: un cambio nuevo puede
     * romper algo que se agregó tres commits atrás, y sin poder mirar el resto de la rama eso es
     * invisible. Lo que se acota es qué *se reporta*, no qué se *puede leer*.
     *
     * @param sinceSha el commit ya revisado. El rango nuevo es `sinceSha..head`.
     * @param carried los hallazgos abiertos de la review anterior, con su id: vuelven dictaminados.
     */
    fun buildIncremental(
        pr: PullRequest,
        language: String,
        depth: ReviewDepth,
        kind: ProjectKind,
        sinceSha: String,
        carried: List<io.acr.data.Finding>,
        existing: List<StoredComment> = emptyList(),
        guidelines: List<io.acr.data.Guideline> = emptyList(),
        issues: List<io.acr.jira.JiraIssue> = emptyList(),
    ): String {
        val rangoCompleto = "origin/${pr.targetBranch}...origin/${pr.sourceBranch}"
        val rangoNuevo = "$sinceSha..${pr.headSha}"
        val blocks = listOf(
            """
            Sos un revisor de código senior. Este pull request YA SE REVISÓ antes: tu trabajo ahora
            es mirar lo que llegó después y decidir qué pasó con lo que se había señalado. Devolvé
            UNICAMENTE el JSON que se describe al final.

            PULL REQUEST
            - Título: ${pr.title}
            - Autor: ${pr.author}
            - Rama: ${pr.sourceBranch} -> ${pr.targetBranch}
            - Commit head actual: ${pr.headSha}
            - Último commit ya revisado: $sinceSha
            - **Commits nuevos a revisar: $rangoNuevo**
            - Rama completa, para contexto: $rangoCompleto

            Los comandos de git de sólo lectura YA ESTAN AUTORIZADOS en esta sesión: corrélos sin
            pedir permiso y sin avisar que no podrías. Si uno falla, mostrá el error exacto.

            Empezá por `git diff --stat $rangoNuevo`: eso es lo que hay que revisar. Todo lo
            anterior ya se revisó y no hace falta volver a mirarlo en busca de problemas nuevos.

            PODÉS leer el resto de la rama —`git diff $rangoCompleto`, los archivos completos— y a
            veces tenés que hacerlo: un cambio nuevo puede romper algo que se agregó antes, y eso
            no se ve mirando sólo el diff corto. La regla no es qué podés leer sino qué reportás.
            """.trimIndent(),

            depth.instructions(),
            kind.focus(),
            guidelinesSection(guidelines),
            issuesSection(issues),
            threadSection(existing),
            carriedSection(carried, language),

            """
            QUÉ REPORTAR COMO HALLAZGO NUEVO
            Sólo problemas introducidos por los commits nuevos ($rangoNuevo), o problemas que esos
            commits provocan en código anterior. Si algo ya estaba mal antes y sigue igual, no es
            un hallazgo nuevo: o ya está en la lista de arriba, o se decidió no reportarlo.

            QUÉ NO REPORTAR
            - Problemas preexistentes en líneas que estos commits no tocaron.
            - Repetir con otras palabras algo que ya está en la lista de hallazgos anteriores.
              Si sigue en pie, va como STILL_OPEN en "carried", no como hallazgo nuevo.
            - Cosas que un linter, el compilador o el type-checker ya detectan.
            - Nitpicks de estilo que un ingeniero senior no marcaría.
            - Falta de tests o documentación, salvo que un CLAUDE.md lo exija explícitamente.
            - Hallazgos que no puedas verificar leyendo el código.

            FORMATO DE SALIDA — JSON, sin texto alrededor
            Un objeto con "summary", "findings" y "carried".

            "findings": sólo lo NUEVO, con el mismo formato de siempre:
            - "file": ruta EXACTA como aparece en el diff, relativa a la raíz del repo.
            - "line": línea del LADO NUEVO, o null si es del archivo entero. Verificá el número
              contra el diff: uno equivocado ancla el comentario en otro lado.
            - "severity": "blocker" | "major" | "minor".
            - "category": "FUNCTIONAL" | "BUG" | "DESIGN" | "CONVENTION", según el criterio de arriba.
            - "title": una línea, la afirmación concreta.
            - "body": 2-4 oraciones en $language con el escenario de falla.
            - "suggestion": cómo se arregla, o null si no podés proponer algo que sostengas.

            "carried": UNA entrada por cada hallazgo anterior de la lista, ninguno de más ni de
            menos, usando su "id" tal cual:
            - "verdict": "STILL_OPEN" si el problema sigue ahí; "FIXED" si los commits nuevos lo
              corrigieron; "OBSOLETE" si el código que lo motivaba ya no existe o cambió tanto que
              la observación dejó de aplicar.
            - "line": si sigue abierto y el código se movió, la línea nueva. Null si no cambió o
              no aplica. Un hallazgo publicado quedó anclado a una línea que ahora puede ser otra.
            - "evidence": qué miraste para decidirlo, citando el cambio concreto. **Que el autor
              haya dicho que lo arregló no es evidencia; el diff sí lo es.** Ante la duda,
              STILL_OPEN: cerrar algo que sigue roto es peor que dejarlo abierto de más.

            "summary": una o dos oraciones en $language sobre lo que trajeron los commits nuevos.
            """.trimIndent(),
        )
        return blocks.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    /** Los hallazgos que vienen de la review anterior, para que el modelo los dictamine. */
    private fun carriedSection(carried: List<io.acr.data.Finding>, language: String): String {
        if (carried.isEmpty()) return ""
        val items = carried.joinToString("\n\n") { f ->
            buildString {
                append("- id: ${f.id}\n")
                append("  archivo: ${f.filePath}${f.lineNo?.let { ":$it" } ?: ""}\n")
                append("  gravedad: ${f.severity}\n")
                append("  título: ${f.title}\n")
                append("  detalle: ${f.body.take(600)}")
                if (f.publishedId != null) append("\n  (ya publicado como comentario en el PR)")
            }
        }
        return """
            HALLAZGOS DE LA REVIEW ANTERIOR — hay que dictaminar cada uno
            Estos se señalaron antes y quedaron abiertos. Para cada uno decidí, MIRANDO EL CÓDIGO,
            si sigue en pie, si se corrigió o si dejó de aplicar. Escribí en $language.

            $items
        """.trimIndent()
    }

    /**
     * Lo que pide el ticket, para poder revisar contra lo pedido y no sólo contra el código.
     *
     * Es la diferencia entre "esto está bien escrito" y "esto hace lo que había que hacer". Sin el
     * ticket, un cambio impecable que resuelve otra cosa pasa la review sin que nadie lo note.
     *
     * Se recorta cada descripción: hay tickets con hilos enteros de discusión pegados, y lo que
     * sirve para revisar está siempre arriba.
     */
    fun issuesSection(issues: List<io.acr.jira.JiraIssue>): String {
        if (issues.isEmpty()) return ""
        val cuerpo = issues.joinToString("\n\n") { i ->
            buildString {
                append("- ${i.key} (${i.type.ifBlank { "sin tipo" }}, ${i.status.ifBlank { "sin estado" }}): ${i.summary}")
                if (i.description.isNotBlank()) append("\n  ").append(i.description.take(3_000).replace("\n", "\n  "))
            }
        }
        return """
            LO QUE PIDE EL TICKET
            El pull request dice resolver esto. Usalo para revisar contra lo pedido y no sólo
            contra el código: un cambio impecable que resuelve otra cosa igual está mal.

            $cuerpo

            Si el cambio no cubre lo que el ticket pide, o hace bastante más de lo pedido sin que
            se justifique, reportalo como hallazgo FUNCTIONAL. Si el ticket es ambiguo, no
            inventes la intención: decilo en el body y no lo cuentes como problema del código.
        """.trimIndent()
    }

    fun build(
        pr: PullRequest,
        language: String,
        depth: ReviewDepth,
        kind: ProjectKind,
        existing: List<StoredComment> = emptyList(),
        guidelines: List<io.acr.data.Guideline> = emptyList(),
        issues: List<io.acr.jira.JiraIssue> = emptyList(),
    ): String {
        val range = "origin/${pr.targetBranch}...origin/${pr.sourceBranch}"
        val blocks = listOf(
            """
            Sos un revisor de código senior. Revisá el siguiente pull request y devolvé UNICAMENTE
            el JSON que se describe al final — sin preámbulo, sin "acá está la review", sin texto
            alrededor. Cada hallazgo se publica anclado a su archivo y su línea en el PR, así que
            la ruta y el número tienen que ser exactos.

            PULL REQUEST
            - Título: ${pr.title}
            - Autor: ${pr.author}
            - Rama: ${pr.sourceBranch} -> ${pr.targetBranch}
            - Commit head: ${pr.headSha}
            - Rango del diff: $range

            Los comandos de git de sólo lectura YA ESTAN AUTORIZADOS en esta sesión: corrélos sin
            pedir permiso y sin avisar que no podrías. Si uno falla, mostrá el error exacto que
            devolvió; no supongas que es un problema de permisos.

            Empezá por `git diff --stat $range` para dimensionar el cambio antes de leer nada.
            Si hay CLAUDE.md en la raíz o en los directorios afectados, leelos y verificá que el
            cambio cumpla lo que dicen; cuando marques una violación, citá textualmente la regla.
            """.trimIndent(),

            depth.instructions(),
            kind.focus(),
            guidelinesSection(guidelines),
            issuesSection(issues),
            threadSection(existing),

            """
            CÓMO DEBERÍA RESOLVERSE
            Cuando puedas, además de señalar el problema decí cómo se arregla, en el campo
            `suggestion`. Señalar sin proponer deja todo el trabajo de pensar la solución del otro
            lado, y muchas veces el que encontró el problema ya sabe cómo se resuelve.

            - Concreto y mínimo: el cambio más chico que resuelve lo señalado, no un rediseño.
            - Si es código, un bloque corto en Markdown con el lenguaje declarado. Nada de
              archivos enteros ni de pseudocódigo.
            - Coherente con el código que está alrededor: mismas convenciones, mismos helpers.
            - **Dejalo en null si no podés proponer algo que sostengas.** Una sugerencia inventada
              es peor que ninguna: hace perder tiempo a quien la lee y desprestigia al resto de la
              review. Si la decisión depende de contexto que no tenés —una regla de negocio, una
              preferencia del equipo— decilo en el `body` y no propongas.


            CÓMO CLASIFICAR CADA HALLAZGO
            Además de la gravedad, cada hallazgo lleva una **categoría**. Son ejes distintos: la
            gravedad dice cuán urgente es, la categoría qué clase de problema es. Quien recibe el
            comentario necesita las dos para saber qué hacer: lo funcional se arregla antes de
            mergear, lo de diseño se conversa.

            - "FUNCTIONAL": cambia o rompe el comportamiento que el negocio espera. Un cálculo que
              da otro resultado, una regla que deja de aplicarse, un estado que queda inconsistente.
            - "BUG": defecto de código que se rompe con cierta entrada o en cierto estado —un nulo,
              un índice, una condición de carrera— sin que la regla de negocio esté mal pensada.
            - "DESIGN": patrón, arquitectura, acoplamiento, lógica en la capa equivocada, algo que
              va a doler mantener. Incluye las buenas prácticas que pidan las convenciones.
            - "CONVENTION": incumple una regla escrita en las convenciones del equipo, cuando el
              problema es la regla incumplida y no una consecuencia técnica.

            **Si entra en varias, gana la primera de esa lista**: algo que rompe el negocio se
            reporta como FUNCTIONAL aunque además sea un problema de diseño, porque eso es lo que
            define qué hacer con él. No uses DESIGN para un bug ni BUG para algo que simplemente no
            sigue una convención.

            QUÉ NO REPORTAR
            - Problemas preexistentes en líneas que el PR no tocó.
            - Cosas que un linter, el compilador o el type-checker ya detectan.
            - Nitpicks de estilo que un ingeniero senior no marcaría.
            - Falta de tests o documentación, salvo que un CLAUDE.md lo exija explícitamente.
            - Hallazgos que no puedas verificar leyendo el código. Si no estás seguro, no lo pongas.

            Es mucho mejor devolver dos hallazgos sólidos que ocho especulativos.

            FORMATO DE SALIDA — JSON, sin texto alrededor
            Devolvé un objeto con "summary" y "findings". Cada finding va anclado al código:

            - "file": ruta EXACTA tal como aparece en el diff, relativa a la raíz del repo.
              No inventes rutas ni las abrevies.
            - "line": el número de línea del LADO NUEVO del diff (el de la rama del PR), o null
              si la observación es del archivo entero y no de una línea puntual. Verificá el número
              contra el diff antes de escribirlo: un número equivocado ancla el comentario en otro
              lado. Si no estás seguro de la línea, poné null en vez de aproximar.
            - "severity": "blocker" si frena el merge, "major" si hay que arreglarlo pero no frena,
              "minor" para lo menor.
            - "category": "FUNCTIONAL", "BUG", "DESIGN" o "CONVENTION", según el criterio de arriba.
            - "title": una línea, la afirmación concreta.
            - "body": 2-4 oraciones en $language con el escenario de falla: con qué entrada o en qué
              situación se rompe y qué pasa como consecuencia. Markdown permitido.

            "summary": una o dos oraciones en $language sobre el cambio en general. Si no encontraste
            nada que valga la pena, devolvé "findings" vacío y decilo en el summary.

            Ordená los findings del más grave al más leve.
            """.trimIndent(),
        )
        return blocks.filter { it.isNotBlank() }.joinToString("\n\n")
    }
}
