package io.acr

import io.acr.data.FindingCategory
import io.acr.claude.ReviewPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La categoría de un hallazgo: qué clase de problema es.
 *
 * Es un eje distinto de la gravedad y hacen falta los dos. Para quien recibe el comentario no es
 * lo mismo "esto rompe la lógica de negocio" que "esto convendría resolverlo con otro patrón",
 * aunque los dos lleguen marcados como importantes: lo primero se arregla antes de mergear y lo
 * segundo se conversa.
 */
class FindingCategoryTest {

    @Test
    fun theModelAnswerIsReadInAnyCasing() {
        assertEquals(FindingCategory.FUNCTIONAL, FindingCategory.fromApi("FUNCTIONAL"))
        assertEquals(FindingCategory.DESIGN, FindingCategory.fromApi("design"))
        assertEquals(FindingCategory.BUG, FindingCategory.fromApi(" Bug "))
    }

    @Test
    fun anUnknownCategoryIsNotInvented() {
        // Ponerle una etiqueta a algo que nadie clasificó le daría aire de dato a una suposición.
        assertNull(FindingCategory.fromApi("REFACTOR"))
        assertNull(FindingCategory.fromApi(""))
        assertNull(FindingCategory.fromApi(null))
    }

    @Test
    fun precedenceIsWhatTheDeclarationOrderSays() {
        // Algo que rompe el negocio se reporta como funcional aunque además sea un problema de
        // diseño, porque eso es lo que define qué hacer con él.
        assertEquals(
            listOf(
                FindingCategory.FUNCTIONAL,
                FindingCategory.BUG,
                FindingCategory.DESIGN,
                FindingCategory.CONVENTION,
            ),
            FindingCategory.entries.toList(),
        )
    }

    @Test
    fun thereAreFourAndNotMore() {
        // Cada categoría de más es una decisión que el modelo puede errar, y la utilidad de la
        // etiqueta cae apenas hay que pensar en cuál de dos parecidas va.
        assertEquals(4, FindingCategory.entries.size)
    }

    @Test
    fun theSchemaDemandsACategoryOnEveryFinding() {
        // Si fuera opcional, el modelo la omitiría en cuanto dudara y la mitad de los hallazgos
        // llegarían sin clasificar.
        listOf(ReviewPrompt.SCHEMA, ReviewPrompt.INCREMENTAL_SCHEMA).forEach { esquema ->
            assertTrue(esquema.contains("\"category\""), "el esquema declara la categoría")
            assertTrue(esquema.contains("\"required\":[\"file\",\"severity\",\"category\""), "y la exige")
            FindingCategory.entries.forEach {
                assertTrue(esquema.contains("\"${it.name}\""), "${it.name} está entre los valores")
            }
        }
    }

    @Test
    fun everyCategoryHasItsLabelInBothLanguages() {
        FindingCategory.entries.forEach {
            val es = io.acr.i18n.I18n.get(io.acr.i18n.Lang.ES, it.labelKey)
            val en = io.acr.i18n.I18n.get(io.acr.i18n.Lang.EN, it.labelKey)
            assertTrue(es != it.labelKey, "falta la etiqueta en español de ${it.name}")
            assertTrue(en != it.labelKey, "falta la etiqueta en inglés de ${it.name}")
        }
    }
}
