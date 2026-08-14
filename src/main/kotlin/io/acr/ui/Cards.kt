package io.acr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.acr.data.PrefsRepo

/**
 * Tarjeta con título, plegable y con scroll propio.
 *
 * Nace de un caso concreto: la pasada final del PR #149 dejó 3.728 caracteres de texto. En una
 * tarjeta rígida eso empuja hacia abajo todo lo que sigue —la verificación, las pestañas, el
 * contenido— y para llegar a lo de abajo hay que scrollear la pantalla entera pasando por un muro
 * de texto que quizá ya leíste.
 *
 * Dos salidas, y hacen falta las dos: el contenido largo se acota con scroll interno para que no
 * empuje nada, y la tarjeta se pliega para sacarla de encima cuando ya no interesa. El plegado se
 * recuerda, porque cuál te importa no cambia entre arranques.
 *
 * @param maxHeight hasta dónde crece antes de scrollear por dentro. Null = sin tope, para
 *   contenido que siempre es corto.
 */
@Composable
fun CollapsibleCard(
    title: String,
    prefs: PrefsRepo,
    key: String,
    accent: Color = MaterialTheme.colorScheme.onSurface,
    trailing: String? = null,
    trailingColor: Color = accent,
    maxHeight: Dp? = 220.dp,
    defaultCollapsed: Boolean = false,
    content: @Composable () -> Unit,
) {
    var plegada by remember(key) {
        mutableStateOf(prefs.get("card.$key")?.let { it == "1" } ?: defaultCollapsed)
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickableText {
                plegada = !plegada
                prefs.put("card.$key", if (plegada) "1" else "0")
            },
        ) {
            Text(
                if (plegada) "▸" else "▾",
                style = MaterialTheme.typography.titleSmall,
                color = accent,
                modifier = Modifier.width(18.dp),
            )
            Text(title, style = MaterialTheme.typography.titleSmall, color = accent)
            trailing?.let {
                Spacer(Modifier.width(8.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = trailingColor)
            }
        }
        if (!plegada) {
            Spacer(Modifier.height(6.dp))
            Column(
                if (maxHeight == null) Modifier.fillMaxWidth()
                else Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
            ) {
                content()
            }
        }
    }
}
