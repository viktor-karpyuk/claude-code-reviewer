package io.acr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lee de la base fuera del hilo de UI y expone el resultado como estado de Compose.
 *
 * El patrón que reemplaza —`val x = remember(k) { ctx.repo.query() }`— corre JDBC dentro de la
 * composición. Además de bloquear el frame, compite por el mismo lock de la única conexión que
 * usa una review escribiendo de fondo: con el modo automático encendido, esas dos cosas coinciden
 * seguido. Acá la consulta va a Dispatchers.IO y la UI dibuja [initial] hasta que llega.
 *
 * Nota: es asíncrono, así que hay un frame con el valor inicial. Para listas eso es invisible;
 * para un dato del que dependa una decisión, conviene contemplar ese estado intermedio.
 */
@Composable
fun <T> dbState(vararg keys: Any?, initial: T, load: () -> T): T {
    var value by remember(*keys) { mutableStateOf(initial) }
    LaunchedEffect(*keys) {
        value = withContext(Dispatchers.IO) { load() }
    }
    return value
}
