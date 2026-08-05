package io.acr.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier

/** Text that behaves as a link/row target without dragging in a Button's chrome. */
fun Modifier.clickableText(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
