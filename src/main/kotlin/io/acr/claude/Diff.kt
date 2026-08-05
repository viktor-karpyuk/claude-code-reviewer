package io.acr.claude

/** Una línea del diff unificado, ya resuelta a números de línea de cada lado. */
data class DiffLine(
    val oldNo: Int?,
    val newNo: Int?,
    val kind: Kind,
    val text: String,
) {
    enum class Kind { CONTEXT, ADDED, REMOVED, HUNK, META }

    /** Ancla para un comentario inline: preferimos el lado nuevo, que es lo que se revisa. */
    val anchorLine: Int? get() = newNo ?: oldNo
}

object DiffParser {

    private val HUNK = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

    /**
     * Convierte la salida de `git diff` de UN archivo en líneas numeradas.
     *
     * Los números importan más de lo que parece: un comentario inline se ancla al número del
     * lado nuevo, y si se calculan mal el comentario aparece en otra línea del PR.
     */
    fun parse(raw: String): List<DiffLine> {
        val out = mutableListOf<DiffLine>()
        var oldNo = 0
        var newNo = 0
        // Sin un hunk previo no hay numeración válida. Un diff binario o un cambio de modo no
        // traen ninguno, y antes sus líneas caían en la rama de contexto con números inventados
        // desde 0: quedaban anclables desde la UI y una nota apuntaba a una "línea 1" inexistente.
        var inHunk = false
        raw.lineSequence().forEach { line ->
            when {
                line.startsWith("@@") -> {
                    inHunk = true
                    HUNK.find(line)?.let { m ->
                        oldNo = m.groupValues[1].toInt()
                        newNo = m.groupValues[2].toInt()
                    }
                    out += DiffLine(null, null, DiffLine.Kind.HUNK, line)
                }
                line.startsWith("diff ") || line.startsWith("index ") ||
                    line.startsWith("--- ") || line.startsWith("+++ ") ||
                    line.startsWith("new file") || line.startsWith("deleted file") ||
                    line.startsWith("similarity ") || line.startsWith("rename ") -> {
                    out += DiffLine(null, null, DiffLine.Kind.META, line)
                }
                !inHunk -> out += DiffLine(null, null, DiffLine.Kind.META, line)
                line.startsWith("+") -> {
                    out += DiffLine(null, newNo, DiffLine.Kind.ADDED, line.drop(1))
                    newNo++
                }
                line.startsWith("-") -> {
                    out += DiffLine(oldNo, null, DiffLine.Kind.REMOVED, line.drop(1))
                    oldNo++
                }
                line.startsWith("\\") -> Unit // "\ No newline at end of file"
                else -> {
                    // Contexto. Una línea vacía en el diff también es contexto.
                    out += DiffLine(oldNo, newNo, DiffLine.Kind.CONTEXT, line.removePrefix(" "))
                    oldNo++
                    newNo++
                }
            }
        }
        return out
    }
}
