package io.acr.claude

/**
 * Cuánto profundiza la review. Los tres niveles no cambian sólo el tono del prompt: cambian
 * qué herramientas se le habilitan al subproceso y qué modelo corre, porque un nivel que
 * "pide" más profundidad sin darle acceso al historial no profundiza nada.
 */
enum class ReviewDepth(val label: String, val blurb: String, val defaultModel: String) {
    LIGHT(
        "Liviana",
        "Sólo el diff. Bloqueantes obvios. Rápida y barata.",
        "haiku",
    ),
    INTERMEDIATE(
        "Intermedia",
        "Diff + archivos completos donde haga falta. El equilibrio por defecto.",
        "sonnet",
    ),
    HEAVY(
        "Profunda",
        "Suma historial, regresiones y verificación adversarial de cada hallazgo.",
        "opus",
    );

    /** Historial y blame sólo se habilitan en profunda: es lo que la distingue de verdad. */
    fun allowedTools(): List<String> = when (this) {
        // Se incluyen subcomandos de git de sólo lectura que el modelo intenta igual: cada
        // denegación le cuesta tokens reintentando y no aporta seguridad, porque ninguno escribe.
        LIGHT -> listOf(
            "Read", "Grep", "Glob",
            "Bash(git diff *)", "Bash(git rev-parse *)", "Bash(git status *)", "Bash(git ls-files *)",
        )
        INTERMEDIATE -> listOf(
            "Read", "Grep", "Glob",
            "Bash(git diff *)", "Bash(git show *)", "Bash(git ls-tree *)", "Bash(git rev-parse *)",
            "Bash(git status *)", "Bash(git ls-files *)", "Bash(git cat-file *)", "Bash(git grep *)",
        )
        HEAVY -> listOf(
            "Read", "Grep", "Glob",
            "Bash(git diff *)", "Bash(git log *)", "Bash(git show *)",
            "Bash(git blame *)", "Bash(git ls-tree *)", "Bash(git rev-parse *)",
            "Bash(git status *)", "Bash(git ls-files *)", "Bash(git cat-file *)", "Bash(git grep *)",
            "Bash(git merge-base *)", "Bash(git describe *)",
        )
    }

    fun instructions(): String = when (this) {
        LIGHT -> """
            PROFUNDIDAD: LIVIANA
            Mirá únicamente el diff. No abras el historial ni leas archivos completos, salvo que
            una parte del diff sea incomprensible sin ese contexto. Reportá sólo lo que frenaría
            el merge: bugs claros, agujeros de seguridad y violaciones explícitas de un CLAUDE.md.
            Máximo 3 hallazgos. Ante la duda, no lo reportes.
        """.trimIndent()

        INTERMEDIATE -> """
            PROFUNDIDAD: INTERMEDIA
            Leé el diff completo y, para lo que sea sutil, abrí el archivo entero en la rama del PR
            para ver el contexto que el diff recorta. Sumá a los bloqueantes: manejo de errores,
            casos borde, y consistencia con el código vecino. Hasta 6 hallazgos, priorizados.
        """.trimIndent()

        HEAVY -> """
            PROFUNDIDAD: PROFUNDA
            Además de todo lo anterior:
            - Mirá el historial del código tocado (`git log`, `git blame`) y detectá si el cambio
              revierte un fix anterior o rompe una invariante que un commit previo estableció.
            - Buscá interacciones que el diff no muestra: quién más llama a lo que cambió.
            - Si hay migraciones, compará su numeración contra la rama destino.
            - Antes de escribir cada hallazgo, intentá refutarlo. Si no resiste, descartalo.
            Sin tope de hallazgos, pero cada uno verificado contra el código, no inferido.
        """.trimIndent()
    }
}

/** Qué mirar. Cambia el foco del review según lo que sea el proyecto. */
enum class ProjectKind(val label: String) {
    BACKEND("Backend / API"),
    FRONTEND_WEB("Frontend web"),
    MOBILE("App mobile"),
    FULLSTACK("Fullstack"),
    GENERIC("Genérico");

    fun focus(): String = when (this) {
        BACKEND -> """
            TIPO DE PROYECTO: BACKEND / API
            Prestá atención especialmente a:
            - Transacciones e idempotencia: reintentos que duplican efectos, escrituras sin rollback.
            - Concurrencia: condiciones de carrera, locks, actualizaciones perdidas.
            - Aislamiento multi-tenant: consultas, índices y claves de caché sin el tenant.
            - Consultas: N+1, falta de índice, paginación hecha en memoria en vez de en SQL.
            - Migraciones: colisión de numeración contra la rama destino, cambios destructivos,
              edición de una migración ya aplicada.
            - Dinero: precisión decimal, redondeo, monedas mezcladas.
            - Contratos de API: cambios incompatibles, códigos de estado, forma del error.
            - Llamadas externas: timeouts, reintentos, y qué pasa si el tercero responde a medias.
        """.trimIndent()

        FRONTEND_WEB -> """
            TIPO DE PROYECTO: FRONTEND WEB
            Prestá atención especialmente a:
            - Estado y renders: efectos que se disparan de más, dependencias mal declaradas, fugas.
            - Datos: paginación, filtrado y búsqueda hechos en el cliente sobre una colección
              completa en vez de pedirlos al backend.
            - Errores de red: qué ve el usuario cuando falla, y si el estado queda consistente.
            - i18n: textos visibles hardcodeados en vez de pasar por el diccionario.
            - Accesibilidad: foco, roles, contraste, navegación por teclado.
            - Seguridad: HTML inyectado sin sanitizar, datos sensibles en el cliente.
            - Peso: imports que engordan el bundle, imágenes sin optimizar.
        """.trimIndent()

        MOBILE -> """
            TIPO DE PROYECTO: APP MOBILE
            Prestá atención especialmente a:
            - Offline: qué pasa sin conexión, si la mutación se encola y si se reintenta sola.
            - Ciclo de vida: trabajo que sigue tras salir de la pantalla, listeners sin soltar.
            - Permisos: pedidos en el momento correcto y con el camino de rechazo resuelto.
            - Recursos: batería, red y memoria; imágenes a resolución de pantalla, no la original.
            - Navegación y estados de carga: pantallas que quedan en blanco o en spinner infinito.
            - Almacenamiento local: qué se guarda en el dispositivo y si algo de eso es sensible.
        """.trimIndent()

        FULLSTACK -> """
            TIPO DE PROYECTO: FULLSTACK
            Cubrí backend y frontend, y sobre todo la costura entre los dos: contratos que cambian
            de un lado y no del otro, validación que existe sólo en el cliente, formas de error que
            el frontend no sabe interpretar, y estados que las dos capas modelan distinto.
        """.trimIndent()

        GENERIC -> ""
    }
}
