package io.acr.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed interface Selection {
    data object Welcome : Selection
    data object Dashboard : Selection
    data object Settings : Selection
    data class Repo(val repoId: String) : Selection
    data class Review(val repoId: String, val prId: Long) : Selection
}

class SelectionStore {
    var current by mutableStateOf<Selection>(Selection.Welcome)
        private set

    fun go(target: Selection) {
        current = target
    }
}
