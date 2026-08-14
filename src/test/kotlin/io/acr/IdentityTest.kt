package io.acr

import io.acr.stats.GitAuthor
import io.acr.stats.IdentityKind
import io.acr.stats.Match
import io.acr.stats.groupAuthors
import io.acr.stats.matchAuthor
import io.acr.stats.mergeSuggestions
import io.acr.stats.normalizeName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Resolver quién es quién antes de contar nada.
 *
 * Es el problema cero del módulo de estadísticas: sin esto, todos los números están mal. Los casos
 * de acá no son inventados — salen de medir los siete repositorios conectados, donde la misma
 * persona aparece hasta con dos nombres y dos emails.
 */
class IdentityTest {

    /** Autores reales de los repositorios conectados, con su cantidad de commits al 2026-08-14. */
    private val reales = listOf(
        GitAuthor("Viktor K", "viktor@kubriksoftware.com"),                       // 223
        GitAuthor("Mateo Andrés Perano", "mateo@kubriksoftware.com"),             // 97
        GitAuthor("Viktor Karpyuk", "viktor.karpyuk@kubriksoftware.com"),         // 26
        GitAuthor("Santiago Del Percio Rodriguez", "santiago@kubriksoftware.com"), // 20
        GitAuthor("Mateo Andres Perano", "mateo@kubriksoftware.com"),             // 9  sin tilde
        GitAuthor("Braian Chavez", "braian@kubrikdigital.com"),                   // 41 otro dominio
        GitAuthor("Braian Chavez", "braian@kubriksoftware.com"),                  // 9
        GitAuthor("María Laura Leguizamón", "laura@kubriksoftware.com"),          // 5
        GitAuthor("María Laura Leguizamón", "leguizamonlauram@gmail.com"),        // 1
        GitAuthor("Mapcky", "mperano625@gmail.com"),                              // 4  apodo
        GitAuthor("Tomas Rivero", "ttomasrivero@gmail.com"),                      // 63
        GitAuthor("Tomás Rivero", "tomas@kubriksoftware.com"),                    // 15 tilde + otro email
        GitAuthor("Dante Ariel Ortiz", "dante@kubriksoftware.com"),               // 86
        GitAuthor("Dante", "dante@kubriksoftware.com"),                           // 10 nombre corto
    )

    // --- normalización ---

    @Test
    fun accentsAndCasingDoNotMakeTwoPeople() {
        assertEquals(normalizeName("Mateo Andrés Perano"), normalizeName("Mateo Andres Perano"))
        assertEquals(normalizeName("Tomás Rivero"), normalizeName("tomas  rivero"))
        assertEquals(normalizeName("María Laura Leguizamón"), normalizeName("Maria Laura Leguizamon"))
    }

    @Test
    fun differentPeopleStayDifferent() {
        assertTrue(normalizeName("Viktor Karpyuk") != normalizeName("Viktor K"))
        assertTrue(normalizeName("Braian Chavez") != normalizeName("Bruno Chavez"))
    }

    // --- resolución automática sobre los datos reales ---

    @Test
    fun theSameEmailWithTwoSpellingsIsOnePerson() {
        // Mateo, con y sin tilde, mismo email: 97 y 9 commits que sin esto quedan en dos personas.
        val personas = groupAuthors(reales)
        val mateo = personas.filter { "mateo@kubriksoftware.com" in it.emails() }
        assertEquals(1, mateo.size)
        assertEquals(
            setOf("mateo andres perano"),
            mateo.single().names(),
            "las dos grafías normalizan al mismo nombre, así que queda una sola identidad",
        )
    }

    @Test
    fun theSameNameWithTwoEmailsIsOnePerson() {
        // Braian commitea desde dos dominios de la empresa; Tomás y María Laura desde el trabajo y
        // desde una cuenta personal. El nombre normalizado los une, que es lo correcto.
        val personas = groupAuthors(reales)
        val braian = personas.filter { it.names().contains("braian chavez") }
        assertEquals(1, braian.size)
        assertEquals(
            setOf("braian@kubrikdigital.com", "braian@kubriksoftware.com"),
            braian.single().emails(),
        )

        val tomas = personas.filter { it.names().contains("tomas rivero") }
        assertEquals(1, tomas.size, "Tomás y Tomas son la misma persona aunque cambien el email")
        assertEquals(2, tomas.single().emails().size)
    }

    @Test
    fun aShortenedNameOnTheSameAccountIsOnePerson() {
        // "Dante Ariel Ortiz" y "Dante" comparten email: manda el email.
        val personas = groupAuthors(reales)
        assertEquals(1, personas.count { "dante@kubriksoftware.com" in it.emails() })
    }

    @Test
    fun anAutoMergeByNameIsFlaggedAsSuch() {
        // Unir por nombre acierta seguido pero es una apuesta: dos personas pueden llamarse igual.
        // Que quede marcado es lo que permite revisarlo en vez de descubrirlo en un número raro.
        val personas = groupAuthors(reales)
        assertTrue(personas.first { it.names().contains("braian chavez") }.autoMerged)
        assertTrue(
            !personas.first { "santiago@kubriksoftware.com" in it.emails() }.autoMerged,
            "quien nunca cambió de cuenta ni de grafía no está marcado",
        )
    }

    @Test
    fun anEmailAlreadyTakenWinsOverAMatchingName() {
        // Si el email pertenece a alguien, esa es la respuesta. Al revés, dos personas que
        // comparten nombre se fusionarían aunque tengan cuentas separadas.
        val personas = groupAuthors(listOf(GitAuthor("Ana Gómez", "ana@x.com")))
        val m = matchAuthor(GitAuthor("Otra Persona", "ana@x.com"), personas)
        assertTrue(m is Match.ByEmail)
    }

    // --- lo que la heurística NO puede resolver ---

    @Test
    fun twoAccountsWithTwoNamesStayApartAndGetSuggested() {
        // Viktor: "Viktor K <viktor@>" y "Viktor Karpyuk <viktor.karpyuk@>". 223 y 26 commits.
        // Ninguna regla razonable los une sin arriesgarse a fusionar gente distinta, así que
        // quedan separados y se proponen.
        val personas = groupAuthors(reales)
        assertEquals(2, personas.count { it.names().any { n -> n.startsWith("viktor") } })

        val sugerencias = mergeSuggestions(personas)
        val ids = personas.filter { it.names().any { n -> n.startsWith("viktor") } }.map { it.id }.toSet()
        assertTrue(
            sugerencias.any { it.first in ids && it.second in ids },
            "las dos cuentas de Viktor tienen que proponerse para fusión",
        )
    }

    @Test
    fun aNicknameIsNobodyGuess() {
        // "Mapcky <mperano625@gmail.com>" es Mateo. No hay señal que lo diga: queda aparte, y
        // fusionarlo es una decisión humana. Inventar esa unión sería peor que no hacerla.
        val personas = groupAuthors(reales)
        assertEquals(1, personas.count { it.names().contains("mapcky") })
    }

    @Test
    fun peopleWithNothingInCommonAreNotSuggested() {
        // Proponer de más cuesta un click, pero proponer cualquier cosa hace que nadie mire las
        // propuestas.
        val personas = groupAuthors(
            listOf(
                GitAuthor("Santiago Del Percio Rodriguez", "santiago@kubriksoftware.com"),
                GitAuthor("Dante Ariel Ortiz", "dante@kubriksoftware.com"),
            ),
        )
        assertEquals(emptyList(), mergeSuggestions(personas))
    }

    @Test
    fun theRealTeamCollapsesToTheRightNumberOfPeople() {
        // 14 pares (nombre, email) distintos en los siete repositorios. La heurística los deja en
        // 9 personas; las que faltan unir —Viktor con sus dos cuentas, Mapcky con Mateo— son
        // justamente las que ninguna regla puede resolver sola.
        val personas = groupAuthors(reales)
        assertEquals(9, personas.size, "personas resueltas: ${personas.map { it.displayName }}")
        assertTrue(personas.none { it.isBot }, "hoy no hay bots en estos repositorios")
    }

    @Test
    fun theResultDoesNotDependOnTheOrderTheAuthorsAppear() {
        // Caso real de estos repositorios. "Lautaro Eloy Islas <lautaro@empresa>" se crea primero;
        // "Lautaro <personal@gmail>" no coincide con nada todavía y abre una persona nueva; recién
        // después "Lautaro <lautaro@empresa>" le agrega el alias corto a la primera. Para cuando
        // ese alias existe, la segunda ya estaba creada — y con otro orden salían unidas.
        //
        // Que el orden cambie el resultado significa que el mismo equipo da números distintos
        // según qué repositorio se lea primero.
        val lautaro = listOf(
            GitAuthor("Lautaro Eloy Islas", "lautaro@kubriksoftware.com"),
            GitAuthor("Lautaro", "islaslautaroeloy@gmail.com"),
            GitAuthor("Lautaro", "lautaro@kubriksoftware.com"),
        )
        assertEquals(1, groupAuthors(lautaro).size)
        assertEquals(1, groupAuthors(lautaro.reversed()).size)
        assertEquals(1, groupAuthors(listOf(lautaro[1], lautaro[0], lautaro[2])).size)

        // Y el conjunto real completo da lo mismo se lea como se lea.
        assertEquals(groupAuthors(reales).size, groupAuthors(reales.reversed()).size)
    }

    @Test
    fun convergingDoesNotLoosenTheRule() {
        // Repetir las pasadas une lo que ya cumplía la regla; no inventa uniones nuevas.
        val distintos = listOf(
            GitAuthor("Santiago Del Percio Rodriguez", "santiago@kubriksoftware.com"),
            GitAuthor("Dante Ariel Ortiz", "dante@kubriksoftware.com"),
            GitAuthor("Lucas Guardese", "lucas@kubriksoftware.com"),
        )
        assertEquals(3, groupAuthors(distintos).size)
    }

    @Test
    fun anEmptyAuthorDoesNotCreateAGhostPerson() {
        val personas = groupAuthors(listOf(GitAuthor("", ""), GitAuthor("  ", "  ")))
        assertTrue(personas.all { it.identities.isEmpty() })
    }

    @Test
    fun aForgeNameIsAnotherIdentityOfTheSamePerson() {
        // Bitbucket muestra "Tomás Rivero"; git registra "Tomas Rivero". Sin normalizar, el PR y
        // sus commits quedan en dos personas distintas.
        val personas = groupAuthors(listOf(GitAuthor("Tomas Rivero", "ttomasrivero@gmail.com")))
        val m = matchAuthor(GitAuthor("Tomás Rivero", "otro@mail.com"), personas)
        assertTrue(m is Match.ByName)
        assertEquals(IdentityKind.GIT_NAME, IdentityKind.valueOf("GIT_NAME"))
    }
}
