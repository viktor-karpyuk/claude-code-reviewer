package io.acr.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed interface Selection {
    data object Welcome : Selection
    data object Dashboard : Selection
    data object Settings : Selection
    data object About : Selection
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
        if (target !is Selection.RepoForm) previous = current
        current = target
    }

    /** Vuelve a la pantalla anterior; si esa era el formulario, cae en la bienvenida. */
    fun back() {
        current = previous.takeIf { it !is Selection.RepoForm } ?: Selection.Welcome
    }
}
