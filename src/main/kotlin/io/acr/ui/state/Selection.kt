package io.acr.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed interface Selection {
    data object Welcome : Selection
    data object Dashboard : Selection
    data object Settings : Selection
    data object About : Selection

    /** Quién es quién. No muestra estadísticas: es lo que hay que resolver antes de mostrarlas. */
    data object People : Selection
    data class Repo(val repoId: String) : Selection
    data class Review(val repoId: String, val prId: Long) : Selection

    /** Alta (`repoId` nulo) o edición de un repositorio. Es una pantalla, no un modal. */
    data class RepoForm(val repoId: String?) : Selection
}

class SelectionStore {
    var current by mutableStateOf<Selection>(Selection.Welcome)
        private set

    /** Desde dónde se entró al formulario, para que "Cancelar" vuelva a eso y no a la nada. */
    private var previous: Selection = Selection.Welcome

    fun go(target: Selection) {
        // Se recuerda de dónde venís en todos los casos menos uno: al saltar de un formulario de
        // repositorio a otro —tocando "Editar" en otro repo mientras ya estabas en uno— hay que
        // conservar el origen real, que es la pantalla anterior a los dos formularios.
        val entreFormularios = current is Selection.RepoForm && target is Selection.RepoForm
        if (!entreFormularios) previous = current
        current = target
    }

    /**
     * Vuelve a la pantalla de la que se vino.
     *
     * Importa de dónde: al abrir un PR desde el panel, volver a la lista del repositorio te deja
     * en un lugar en el que nunca estuviste y perdés lo que estabas revisando en el panel.
     *
     * @param fallback a dónde ir cuando no hay una pantalla anterior útil: la bienvenida no lo es,
     *   y el formulario de repositorio tampoco —volver ahí reabriría un alta a medio hacer.
     */
    fun back(fallback: Selection = Selection.Welcome) {
        val destino = previous
        current = when {
            destino is Selection.RepoForm -> fallback
            destino is Selection.Welcome -> fallback
            destino == current -> fallback
            else -> destino
        }
    }
}
