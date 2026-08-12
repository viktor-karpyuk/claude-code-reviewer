package io.acr

import io.acr.ui.state.Selection
import io.acr.ui.state.SelectionStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Volver tiene que llevarte de donde viniste.
 *
 * Antes el "← Volver" de un PR iba siempre a la lista del repositorio: si habías entrado desde el
 * panel, aterrizabas en un lugar en el que nunca estuviste y perdías lo que estabas revisando.
 */
class NavigationTest {

    @Test
    fun fromTheDashboardItGoesBackToTheDashboard() {
        val s = SelectionStore()
        s.go(Selection.Dashboard)
        s.go(Selection.Review("r1", 7))
        s.back(Selection.Repo("r1"))
        assertEquals(Selection.Dashboard, s.current)
    }

    @Test
    fun fromTheRepoListItGoesBackToTheList() {
        val s = SelectionStore()
        s.go(Selection.Repo("r1"))
        s.go(Selection.Review("r1", 7))
        s.back(Selection.Repo("r1"))
        assertEquals(Selection.Repo("r1"), s.current)
    }

    @Test
    fun withoutAUsefulPreviousItUsesTheFallback() {
        // Recién abierta la app —o entrando desde el icono de la barra de menú— la pantalla
        // anterior es la bienvenida, que no es un lugar al que quieras volver.
        val s = SelectionStore()
        s.go(Selection.Review("r1", 7))
        s.back(Selection.Repo("r1"))
        assertEquals(Selection.Repo("r1"), s.current)
    }

    @Test
    fun itNeverGoesBackToTheRepoForm() {
        // Volver al alta de repositorio reabriría un formulario a medio hacer: se usa el fallback.
        val s = SelectionStore()
        s.go(Selection.Dashboard)
        s.go(Selection.RepoForm(null))
        s.go(Selection.Review("r1", 7))
        s.back(Selection.Repo("r1"))
        assertEquals(Selection.Repo("r1"), s.current)
    }

    @Test
    fun switchingBetweenRepoFormsKeepsTheRealOrigin() {
        // Tocar "Editar" en otro repo mientras ya estás en un formulario no puede hacer que
        // "Cancelar" te devuelva al formulario anterior.
        val s = SelectionStore()
        s.go(Selection.Dashboard)
        s.go(Selection.RepoForm("r1"))
        s.go(Selection.RepoForm("r2"))
        s.back()
        assertEquals(Selection.Dashboard, s.current)
    }

    @Test
    fun theRepoFormStillReturnsWhereItWasOpenedFrom() {
        val s = SelectionStore()
        s.go(Selection.Repo("r1"))
        s.go(Selection.RepoForm("r1"))
        s.back()
        assertEquals(Selection.Repo("r1"), s.current)
    }
}
