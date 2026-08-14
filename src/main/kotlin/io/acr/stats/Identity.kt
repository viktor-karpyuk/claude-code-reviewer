package io.acr.stats

import java.text.Normalizer

/** De dónde salió una identidad. La misma persona aparece distinto según quién la nombre. */
enum class IdentityKind { GIT_EMAIL, GIT_NAME, FORGE_USER }

/** Un par (fuente, valor) que identifica a alguien en algún sistema. */
data class Identity(val kind: IdentityKind, val value: String)

/** Autor de un commit, tal como git lo registra. */
data class GitAuthor(val name: String, val email: String)

/**
 * Nombre comparable: minúsculas, sin tildes, sin espacios de más.
 *
 * Existe porque la misma persona se escribe distinto según la máquina desde la que commitea. En
 * estos repositorios están las dos grafías de "Mateo Andrés Perano" —con y sin tilde, mismo
 * email— y "Tomás Rivero" contra "Tomas Rivero", que además usan emails distintos: sin normalizar,
 * cada uno se parte en dos personas y todas las métricas quedan mal.
 */
fun normalizeName(raw: String): String =
    Normalizer.normalize(raw.trim().lowercase(), Normalizer.Form.NFD)
        // \p{Mn} son las marcas diacríticas que NFD dejó sueltas: así "á" queda "a".
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("\\s+"), " ")

/** El email también se compara normalizado: git no distingue mayúsculas ahí. */
fun normalizeEmail(raw: String): String = raw.trim().lowercase()

/**
 * Una persona con las identidades que ya se le conocen.
 *
 * @param autoMerged si se armó juntando por nombre y no por email. Eso se marca porque es la
 *   unión que puede equivocarse: dos personas distintas con el mismo nombre existen.
 */
data class Person(
    val id: String,
    val displayName: String,
    val identities: List<Identity>,
    val isBot: Boolean = false,
    val autoMerged: Boolean = false,
) {
    fun emails(): Set<String> =
        identities.filter { it.kind == IdentityKind.GIT_EMAIL }.map { it.value }.toSet()

    fun names(): Set<String> =
        identities.filter { it.kind == IdentityKind.GIT_NAME }.map { it.value }.toSet()
}

/** A qué persona corresponde un autor, y por qué se decidió eso. */
sealed interface Match {
    /** Coincide el email: es la señal más fuerte que da git. */
    data class ByEmail(val personId: String) : Match

    /**
     * Coincide el nombre normalizado, con otro email.
     *
     * Acierta seguido —el mismo desarrollador desde su máquina personal y la del trabajo— pero es
     * una apuesta: dos personas distintas pueden llamarse igual. Por eso se marca y se puede
     * deshacer.
     */
    data class ByName(val personId: String) : Match

    /** No se parece a nadie conocido. */
    data object New : Match
}

/**
 * Decide a qué persona pertenece un autor de git.
 *
 * El orden importa y no es intercambiable: el email primero porque es lo que identifica una cuenta,
 * el nombre después porque es lo que un humano escribe distinto cada vez. Al revés, dos personas
 * que comparten nombre se fusionarían aunque tengan cuentas separadas.
 *
 * Nunca junta por nombre a alguien cuyo email ya está tomado por otra persona: si el email
 * pertenece a alguien, esa es la respuesta, y punto.
 */
fun matchAuthor(author: GitAuthor, known: List<Person>): Match {
    val email = normalizeEmail(author.email)
    val name = normalizeName(author.name)

    if (email.isNotBlank()) {
        known.firstOrNull { email in it.emails() }?.let { return Match.ByEmail(it.id) }
    }
    if (name.isNotBlank()) {
        known.firstOrNull { name in it.names() }?.let { return Match.ByName(it.id) }
    }
    return Match.New
}

/**
 * Agrupa una lista de autores en personas, sin tocar la base.
 *
 * Se usa para la pantalla de resolución: mostrar el resultado propuesto antes de guardar nada.
 * Procesa en orden de aparición, así que el nombre que se muestra es el primero que se vio; en la
 * práctica conviene pasarlos ordenados por cantidad de commits para que gane el más usado.
 */
fun groupAuthors(authors: List<GitAuthor>): List<Person> {
    val personas = mutableListOf<Person>()
    var siguiente = 0
    authors.forEach { a ->
        val email = normalizeEmail(a.email)
        val name = normalizeName(a.name)
        when (val m = matchAuthor(a, personas)) {
            is Match.ByEmail -> {
                val i = personas.indexOfFirst { it.id == m.personId }
                // Un alias nuevo del mismo email suma identidad, no persona.
                personas[i] = personas[i].copy(
                    identities = (personas[i].identities + Identity(IdentityKind.GIT_NAME, name)).distinct(),
                )
            }
            is Match.ByName -> {
                val i = personas.indexOfFirst { it.id == m.personId }
                personas[i] = personas[i].copy(
                    identities = (personas[i].identities + Identity(IdentityKind.GIT_EMAIL, email)).distinct(),
                    autoMerged = true,
                )
            }
            Match.New -> personas += Person(
                id = "p${siguiente++}",
                displayName = a.name.trim(),
                identities = buildList {
                    if (email.isNotBlank()) add(Identity(IdentityKind.GIT_EMAIL, email))
                    if (name.isNotBlank()) add(Identity(IdentityKind.GIT_NAME, name))
                },
            )
        }
    }
    return converge(personas)
}

/**
 * Vuelve a pasar hasta que no quede nada por unir.
 *
 * Una sola pasada depende del orden en que aparezcan los autores, y eso se vio con datos reales:
 * "Lautaro Eloy Islas <lautaro@empresa>" se crea primero, después "Lautaro <personal@gmail>" no
 * coincide con nada y abre una persona nueva, y recién entonces "Lautaro <lautaro@empresa>" le
 * agrega el alias corto a la primera. Para cuando ese alias existe, la segunda persona ya estaba
 * creada. Con otro orden de commits salían unidas.
 *
 * Que el resultado dependa del orden significa que el mismo equipo da números distintos según qué
 * repositorio se lea primero. Repetir con las mismas reglas hasta que nada cambie lo vuelve
 * estable, y no afloja el criterio: sólo une lo que ya cumplía la regla.
 */
private fun converge(inicial: MutableList<Person>): List<Person> {
    val personas = inicial
    var cambio = true
    while (cambio) {
        cambio = false
        outer@ for (i in personas.indices) {
            for (j in i + 1 until personas.size) {
                val a = personas[i]
                val b = personas[j]
                val mismoEmail = a.emails().any { it in b.emails() }
                val mismoNombre = a.names().any { it in b.names() }
                if (mismoEmail || mismoNombre) {
                    personas[i] = a.copy(
                        identities = (a.identities + b.identities).distinct(),
                        // Unir por email es un hecho; por nombre es una apuesta y queda marcada.
                        autoMerged = a.autoMerged || b.autoMerged || !mismoEmail,
                    )
                    personas.removeAt(j)
                    cambio = true
                    break@outer
                }
            }
        }
    }
    return personas
}

/**
 * Personas que probablemente sean la misma y la heurística no pudo unir.
 *
 * El caso que lo motiva está en estos repositorios: "Viktor K <viktor@…>" y "Viktor Karpyuk
 * <viktor.karpyuk@…>" son la misma persona con dos cuentas y dos nombres, y no hay regla razonable
 * que lo resuelva sin arriesgarse a fusionar gente distinta. Lo honesto es proponer y que decida
 * quien sabe.
 *
 * Se propone cuando un nombre es prefijo del otro por palabras —"viktor k" contra "viktor
 * karpyuk"— o cuando la parte local de un email contiene la del otro. Son señales débiles a
 * propósito: acá proponer de más cuesta un click, y proponer de menos deja el número partido.
 */
fun mergeSuggestions(personas: List<Person>): List<Pair<String, String>> {
    val sugerencias = mutableListOf<Pair<String, String>>()
    for (i in personas.indices) {
        for (j in i + 1 until personas.size) {
            val a = personas[i]
            val b = personas[j]
            if (a.isBot || b.isBot) continue
            if (seParecen(a, b)) sugerencias += a.id to b.id
        }
    }
    return sugerencias
}

private fun seParecen(a: Person, b: Person): Boolean {
    val nombresA = a.names()
    val nombresB = b.names()
    for (na in nombresA) for (nb in nombresB) {
        if (na.isBlank() || nb.isBlank()) continue
        val pa = na.split(" ")
        val pb = nb.split(" ")
        // Mismo nombre de pila y uno de los dos abreviado: "viktor k" / "viktor karpyuk".
        if (pa.first() == pb.first() && (pa.size == 1 || pb.size == 1 || esPrefijo(pa, pb))) return true
    }
    for (ea in a.emails()) for (eb in b.emails()) {
        val la = ea.substringBefore('@')
        val lb = eb.substringBefore('@')
        if (la.isBlank() || lb.isBlank()) continue
        // "viktor" dentro de "viktor.karpyuk". Se exige 4 caracteres para que "ana" no enganche
        // con cualquier cosa que la contenga.
        if (la.length >= 4 && lb.startsWith(la)) return true
        if (lb.length >= 4 && la.startsWith(lb)) return true
    }
    return false
}

/** ¿Las palabras de uno arrancan igual que las del otro, con una abreviada? "viktor k" ⊂ "viktor karpyuk". */
private fun esPrefijo(a: List<String>, b: List<String>): Boolean {
    val (corto, largo) = if (a.size <= b.size) a to b else b to a
    return corto.indices.all { i -> largo[i].startsWith(corto[i]) }
}
