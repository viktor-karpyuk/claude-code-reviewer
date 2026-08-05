package io.acr.claude

/**
 * Elige profundidad y tipo de proyecto cuando el usuario los deja en automático.
 *
 * Todo sale de los archivos que el PR toca: no adivina por el nombre del repositorio, porque un
 * monorepo cambia de naturaleza según el PR. La decisión siempre viene con su motivo, así el
 * automático es auditable en vez de mágico.
 */
object ReviewPlanner {

    data class Plan(val depth: ReviewDepth, val kind: ProjectKind, val reason: String)

    private val WEB_EXT = setOf("ts", "tsx", "js", "jsx", "vue", "svelte", "css", "scss", "less", "html")
    private val BACKEND_EXT = setOf("java", "kt", "go", "rs", "py", "rb", "cs", "php", "scala", "sql")
    private val MOBILE_EXT = setOf("swift", "dart", "m", "mm")

    /**
     * Rutas que delatan mobile aunque la extensión sea compartida (Kotlin sirve para las dos).
     * Deliberadamente NO incluye "build.gradle" ni "info.plist": el primero aparece en cualquier
     * proyecto JVM —incluida esta misma app— y el segundo en empaquetados de escritorio macOS,
     * así que un bump de dependencia se clasificaba como mobile.
     */
    private val MOBILE_HINTS = listOf(
        "android/", "/ios/", "androidmanifest.xml", "pubspec.yaml",
        "podfile", "xcodeproj", "react-native", "capacitor", "/mobile/",
    )

    /**
     * Señales de que el cambio merece la review más cara, aunque sea chico. Se comparan contra
     * PALABRAS de la ruta, no como substring: con `contains` puro, "auth" matcheaba `Author.kt`,
     * "tax" matcheaba `SyntaxHighlighter`, "cron" matcheaba `Acronym` y "lock" matcheaba
     * `yarn.lock`, mandando a Opus cambios que no tenían nada que ver.
     */
    private val RISK_WORDS = setOf(
        "migration", "migrations", "schema", "flyway", "liquibase",
        "auth", "authentication", "authorization", "security", "password", "token",
        "credential", "credentials", "crypto", "secret", "secrets",
        "payment", "payments", "invoice", "billing", "money", "price", "pricing", "tax", "fiscal",
        "concurrency", "concurrent", "transaction", "transactional", "lock", "locking",
        "scheduler", "cron", "queue",
    )

    /** Los lockfiles no son código sensible: son ruido generado y enorme. */
    private val LOCKFILES = setOf(
        "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "cargo.lock",
        "gemfile.lock", "poetry.lock", "composer.lock", "go.sum",
    )

    /** Parte la ruta en palabras, separando también camelCase: AuthorService -> [author, service]. */
    internal fun words(path: String): Set<String> =
        path.split('/', '.', '-', '_', ' ')
            .flatMap { seg -> Regex("[A-Za-z][a-z0-9]*|[0-9]+").findAll(seg).map { it.value } }
            .map { it.lowercase() }
            .toSet()

    internal fun isRisky(path: String): Boolean {
        val name = path.substringAfterLast('/').lowercase()
        if (name in LOCKFILES) return false
        return words(path).any { it in RISK_WORDS }
    }

    fun plan(
        files: List<Git.FileChange>,
        requestedDepth: ReviewDepth?,
        requestedKind: ProjectKind?,
    ): Plan {
        val autoKind = detectKind(files)
        val (autoDepth, depthReason) = detectDepth(files)

        val kind = requestedKind ?: autoKind
        val depth = requestedDepth ?: autoDepth

        val parts = buildList {
            if (requestedDepth == null) add("profundidad ${depth.label.lowercase()} ($depthReason)")
            if (requestedKind == null) add("tipo ${kind.label} (${kindReason(files, kind)})")
        }
        val reason = if (parts.isEmpty()) "perfil fijado a mano" else "auto: " + parts.joinToString(" · ")
        return Plan(depth, kind, reason)
    }

    private fun detectKind(files: List<Git.FileChange>): ProjectKind {
        if (files.isEmpty()) return ProjectKind.GENERIC
        var web = 0
        var backend = 0
        var mobile = 0
        files.forEach { f ->
            val lower = f.path.lowercase()
            val ext = lower.substringAfterLast('.', "")
            val isMobilePath = MOBILE_HINTS.any { lower.contains(it) }
            when {
                ext in MOBILE_EXT || isMobilePath -> mobile += f.touched.coerceAtLeast(1)
                ext in WEB_EXT -> web += f.touched.coerceAtLeast(1)
                ext in BACKEND_EXT -> backend += f.touched.coerceAtLeast(1)
            }
        }
        val total = web + backend + mobile
        if (total == 0) return ProjectKind.GENERIC
        // "Fullstack" sólo si los dos lados pesan de verdad; un archivo suelto no cuenta.
        val webShare = web.toDouble() / total
        val backendShare = backend.toDouble() / total
        val mobileShare = mobile.toDouble() / total
        return when {
            mobileShare >= 0.4 -> ProjectKind.MOBILE
            webShare >= 0.25 && backendShare >= 0.25 -> ProjectKind.FULLSTACK
            webShare > backendShare -> ProjectKind.FRONTEND_WEB
            backendShare > 0 -> ProjectKind.BACKEND
            else -> ProjectKind.GENERIC
        }
    }

    private fun kindReason(files: List<Git.FileChange>, kind: ProjectKind): String {
        if (files.isEmpty()) return "sin archivos en el diff"
        val exts = files.map { it.path.substringAfterLast('.', "") }
            .filter { it.isNotBlank() }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(3).joinToString(", ") { ".${it.key}" }
        return if (exts.isBlank()) "${files.size} archivos" else exts
    }

    private fun detectDepth(files: List<Git.FileChange>): Pair<ReviewDepth, String> {
        if (files.isEmpty()) return ReviewDepth.INTERMEDIATE to "no pude leer el diff"

        val lines = files.sumOf { it.touched }
        val risky = files.filter { isRisky(it.path) }

        // El riesgo manda sobre el tamaño: una migración de tres líneas puede tirar abajo el deploy.
        if (risky.isNotEmpty()) {
            val sample = risky.first().path.substringAfterLast('/')
            return ReviewDepth.HEAVY to "toca ${risky.size} archivo(s) sensible(s), p. ej. $sample"
        }
        if (files.size >= 15 || lines >= 600) {
            return ReviewDepth.HEAVY to "${files.size} archivos, $lines líneas"
        }
        if (files.size <= 3 && lines <= 80) {
            return ReviewDepth.LIGHT to "${files.size} archivos, $lines líneas"
        }
        return ReviewDepth.INTERMEDIATE to "${files.size} archivos, $lines líneas"
    }
}
