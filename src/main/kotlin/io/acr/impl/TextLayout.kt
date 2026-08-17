package io.acr.impl

/**
 * Un trozo de texto con su marca de enumeración, si la tenía.
 *
 * `marker` null es prosa: un párrafo, o la frase que introduce a la lista.
 */
data class TextBlock(val marker: String?, val text: String)

/**
 * Desarma un texto en párrafos y elementos de lista.
 *
 * Los modelos escriben las enumeraciones en línea —"hay que 1) migrar la tabla, 2) exponer el
 * endpoint, 3) cablear la pantalla"— y renderizado como párrafo corrido eso se lee como una sola
 * oración larga donde los números son ruido. Puestos uno debajo del otro se leen como lo que son:
 * cosas distintas que hay que hacer, contables de un vistazo.
 *
 * **Hacen falta al menos dos marcas para partir.** Un "1)" solo no es una lista, es una aclaración,
 * y partir ahí inventaría una estructura que el texto no tiene. Por lo mismo la marca tiene que
 * venir precedida de espacio y seguida de espacio: sin eso, `v1.2` y `Art. 5.` se convertirían en
 * items de una lista que nadie escribió.
 *
 * Los saltos de línea que ya estaban se respetan: si alguien se tomó el trabajo de separar, esa
 * separación gana sobre cualquier heurística de acá.
 */
fun splitBlocks(texto: String): List<TextBlock> =
    texto.trim().lines()
        .flatMap { linea -> desarmarLinea(linea) }
        .filter { it.text.isNotBlank() || it.marker != null }

/** Las marcas de lista al principio de una línea: `-`, `*`, `•`, `1.`, `1)`, `a)`. */
private val AL_INICIO = Regex("""^\s*([-*•‣]|\d{1,2}[.)]|[a-z][.)])\s+""", RegexOption.IGNORE_CASE)

/**
 * Marcas de enumeración en medio de una oración.
 *
 * Sólo con paréntesis o punto **después** de un número o una letra sola, y siempre entre espacios.
 * El punto es el caso delicado: `1.` en medio de una frase puede ser el final de una oración que
 * termina en un número, así que se exige que lo que sigue arranque con minúscula o con una palabra,
 * no con otro número.
 */
private val EN_LINEA = Regex("""(?<=\s)(\d{1,2}[.)]|[a-z][)])\s+""")

/**
 * ¿Está la posición [pos] adentro de un paréntesis?
 *
 * Es lo que separa una enumeración de una referencia. Los planes reales están llenos de
 * `(mockup 01)`, `(tarea 13)`, `(paso 2)`: el número va precedido de espacio y seguido de espacio,
 * igual que un item de lista, y sin mirar el paréntesis el texto se parte justo en el medio de una
 * idea. Salió de mirar descripciones de verdad, no de imaginarlas.
 */
private fun dentroDeParentesis(texto: String, pos: Int): Boolean {
    var abiertos = 0
    for (i in 0 until pos) {
        when (texto[i]) {
            '(' -> abiertos++
            ')' -> if (abiertos > 0) abiertos--
        }
    }
    return abiertos > 0
}

private fun desarmarLinea(linea: String): List<TextBlock> {
    val conMarca = AL_INICIO.find(linea)
    val marcaInicial = conMarca?.groupValues?.get(1)
    val cuerpo = if (conMarca != null) linea.substring(conMarca.range.last + 1) else linea.trim()

    val marcas = EN_LINEA.findAll(cuerpo)
        .filter { !dentroDeParentesis(cuerpo, it.range.first) }
        .toList()
    // Una marca sola no es una lista: es una aclaración, y partir ahí inventaría una estructura
    // que el texto no tiene.
    if (marcas.size < 2) return listOf(TextBlock(marcaInicial, cuerpo.trim()))

    val bloques = mutableListOf<TextBlock>()
    // Lo que va antes de la primera marca es la frase que introduce la lista. Se conserva como
    // párrafo: tirarla dejaría los items sin decir de qué son.
    cuerpo.substring(0, marcas.first().range.first).trim()
        .takeIf { it.isNotBlank() }
        ?.let { bloques += TextBlock(marcaInicial, it.trimEnd(':', ',', ' ')) }

    marcas.forEachIndexed { i, m ->
        val desde = m.range.last + 1
        val hasta = marcas.getOrNull(i + 1)?.range?.first ?: cuerpo.length
        val texto = cuerpo.substring(desde, hasta).trim().trimEnd(',', ';')
        if (texto.isNotBlank()) bloques += TextBlock(m.groupValues[1], texto)
    }
    return bloques
}
