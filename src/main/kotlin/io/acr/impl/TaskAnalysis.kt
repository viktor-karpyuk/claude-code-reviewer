package io.acr.impl

/**
 * De qué clase es un archivo. Es lo que convierte "tocó 12 archivos" en algo que se puede juzgar.
 */
enum class FileKind(val labelKey: String) {
    CODE("kind.code"),
    TEST("kind.test"),
    MIGRATION("kind.migration"),
    CONFIG("kind.config"),
    DOC("kind.doc"),
    ASSET("kind.asset"),
    ;

    companion object {
        /**
         * Clasifica por la ruta.
         *
         * El orden importa: un archivo en `src/test/` que además es `.sql` es un test, no una
         * migración. Se pregunta primero por lo más específico.
         */
        fun of(path: String): FileKind {
            val p = path.lowercase()
            return when {
                p.contains("/test/") || p.contains("/tests/") || p.contains("__tests__") ||
                    p.endsWith("test.kt") || p.endsWith("test.java") || p.endsWith(".spec.ts") ||
                    p.endsWith(".test.ts") || p.endsWith("_test.go") || p.endsWith("test.py") -> TEST
                p.contains("/migration") || p.contains("/migrations/") ||
                    Regex("/v\\d+__").containsMatchIn(p) -> MIGRATION
                p.endsWith(".md") || p.contains("/docs/") -> DOC
                p.endsWith(".json") || p.endsWith(".yml") || p.endsWith(".yaml") ||
                    p.endsWith(".xml") || p.endsWith(".toml") || p.endsWith(".properties") ||
                    p.endsWith(".gradle") || p.endsWith(".gradle.kts") || p.endsWith("dockerfile") -> CONFIG
                p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".svg") ||
                    p.endsWith(".ico") || p.endsWith(".woff") || p.endsWith(".woff2") -> ASSET
                else -> CODE
            }
        }
    }
}

/**
 * Lo que se puede afirmar de una tarea mirando qué archivos tocó.
 *
 * No son adornos: cada una responde una pregunta concreta que uno se hace al revisar código que
 * escribió alguien sin supervisión.
 *
 * @param byKind de qué se compone el cambio. Trescientas líneas de código sin un test es una cosa;
 *   con tests es otra, y el total solo no distingue.
 * @param concentration qué proporción de las líneas se llevó el archivo más grande. Un cambio de
 *   400 líneas en un archivo y 3 en nueve más no es lo mismo que 40 en diez: el primero es un
 *   archivo para leer entero, el segundo es una refactorización dispersa.
 * @param sensitive archivos que cambian el comportamiento del sistema más allá de su propio
 *   código: migraciones y configuración de build. Se marcan aparte porque un error ahí no lo
 *   atrapa una revisión de la lógica.
 */
data class TaskAnalysis(
    val byKind: Map<FileKind, Pair<Int, Int>>,
    val concentration: Double,
    val biggestFile: String?,
    val sensitive: List<String>,
    val touchedTests: Boolean,
    val onlyDocs: Boolean,
)

fun analyze(files: List<FileChange>): TaskAnalysis? {
    if (files.isEmpty()) return null
    val porTipo = files.groupBy { FileKind.of(it.path) }
        .mapValues { (_, fs) -> fs.size to fs.sumOf { it.added + it.deleted } }
    val total = files.sumOf { it.added + it.deleted }
    val mayor = files.maxByOrNull { it.added + it.deleted }
    return TaskAnalysis(
        byKind = porTipo,
        concentration = if (total <= 0) 0.0 else (mayor?.let { it.added + it.deleted } ?: 0) / total.toDouble(),
        biggestFile = mayor?.path,
        sensitive = files.map { it.path }
            .filter { FileKind.of(it) == FileKind.MIGRATION || FileKind.of(it) == FileKind.CONFIG },
        touchedTests = porTipo.containsKey(FileKind.TEST),
        // Una tarea que sólo tocó documentación no escribió nada: puede estar bien, pero conviene
        // que se note en vez de contarse como si hubiera implementado algo.
        onlyDocs = porTipo.keys.all { it == FileKind.DOC },
    )
}
