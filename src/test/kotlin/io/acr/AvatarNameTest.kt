package io.acr

import io.acr.ui.iniciales
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * De dónde salen las iniciales del avatar.
 *
 * En la conversación, la burbuja de lo que preguntamos se rotula "Lo que preguntamos" y el avatar
 * sacaba de ahí sus iniciales: mostraba "LP", que no es nadie. El rótulo describe el mensaje y el
 * avatar tiene que decir quién lo escribió; son dos cosas distintas y por eso van por separado.
 */
class AvatarNameTest {

    @Test
    fun aLabelIsNotAPerson() {
        // El síntoma exacto que se reportó.
        assertEquals("LP", iniciales("Lo que preguntamos"))
        assertEquals("VK", iniciales("Viktor Karpyuk"))
    }

    @Test
    fun aRealNameGivesRealInitials() {
        // Nombre y apellido, salteando lo del medio: con "Santiago Del Percio Rodriguez" lo que
        // identifica a la persona es "SR" y no "SD", que sería Santiago Del.
        assertEquals("MP", iniciales("Mateo Andrés Perano"))
        assertEquals("TR", iniciales("Tomás Rivero"))
        assertEquals("SR", iniciales("Santiago Del Percio Rodriguez"))
    }

    @Test
    fun aSingleNameStillWorks() {
        // Varios autores de estos repositorios tienen una sola palabra: "Mapcky", "Tobias".
        assertEquals("MA", iniciales("Mapcky"))
        assertEquals("TO", iniciales("Tobias"))
    }

    @Test
    fun anEmptyNameDoesNotCrash() {
        // El nombre del proveedor puede faltar; un avatar vacío es feo, romper es peor.
        iniciales("")
        iniciales("   ")
    }
}
