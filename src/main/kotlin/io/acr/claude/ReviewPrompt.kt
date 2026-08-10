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
        "title":{"type":"string"},
        "body":{"type":"string"}
      },"required":["file","severity","title","body"]}}
    },"required":["summary","findings"]}
    """.trimIndent()

    /** Veredicto por hallazgo, más el global. Los ids vuelven tal cual para poder anclar cada uno. */
    val RESOLUTION_SCHEMA = """
    {"type":"object","properties":{
      "summary":{"type":"string"},
      "mergeable":{"type":"boolean"},
      "items":{"type":"array","items":{"type":"object","properties":{
        "id":{"type":"string"},
        "resolution":{"type":"string","enum":["RESOLVED","PARTIAL","UNRESOLVED"]},
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
        - RESOLVED sólo si el cambio arregla lo señalado de verdad. Si atiende una parte, o lo
          mueve de lugar sin resolverlo, es PARTIAL.
        - UNRESOLVED si el código sigue igual o el cambio no tiene que ver.
        - En `evidence` citá lo concreto: archivo y línea, o el commit. Una frase, no un ensayo.
          Si es UNRESOLVED, decí qué falta hacer.
        - `mergeable` es true sólo si TODAS son RESOLVED y no encontrás nada nuevo que frene el
          merge. Ante la duda, false: mergear de más no se puede deshacer.
        - Escribí en $language.
    """.trimIndent()

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

    fun build(
        pr: PullRequest,
        language: String,
        depth: ReviewDepth,
        kind: ProjectKind,
        existing: List<StoredComment> = emptyList(),
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
            threadSection(existing),

            """
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
