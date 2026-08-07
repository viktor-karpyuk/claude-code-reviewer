package io.acr

import io.acr.forge.PrState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * El estado del PR. Los históricos —mergeados y cerrados— no se cargan solos: en `kubrik-erp-be`
 * son 148 contra 4 abiertos, así que traerlos siempre sería descargar cientos de resultados para
 * mostrar los cuatro que importan.
 */
class PrStateTest {

    @Test
    fun mapsTheStatesTheProviderSends() {
        assertEquals(PrState.OPEN, PrState.fromApi("OPEN"))
        assertEquals(PrState.MERGED, PrState.fromApi("MERGED"))
        assertEquals(PrState.DECLINED, PrState.fromApi("DECLINED"))
        // Bitbucket manda en mayúsculas, pero no hay que depender de eso.
        assertEquals(PrState.MERGED, PrState.fromApi("merged"))
    }

    @Test
    fun anUnknownStateIsNotTreatedAsOpen() {
        // SUPERSEDED existe en Bitbucket y no está en nuestro enum. Para revisar, lo único que
        // importa es que ya no está abierto: darlo por abierto lo pondría en la lista de trabajo.
        assertEquals(PrState.DECLINED, PrState.fromApi("SUPERSEDED"))
        assertEquals(PrState.DECLINED, PrState.fromApi("algo_nuevo"))
    }

    @Test
    fun missingStateDefaultsToOpen() {
        // El listado por defecto pide state=OPEN, así que si el campo no viene, abierto es lo
        // correcto — es lo que se pidió.
        assertEquals(PrState.OPEN, PrState.fromApi(null))
        assertEquals(PrState.OPEN, PrState.fromApi(""))
    }

    @Test
    fun defaultIsOpenOnly() {
        // Si esto cambia, la app empieza a descargar el histórico sin que nadie lo pida.
        assertEquals(PrState.OPEN, io.acr.forge.PullRequest(
            id = 1, title = "t", author = "a", sourceBranch = "s", targetBranch = "d",
            headSha = "h", commentCount = 0, updatedOn = "", url = "",
        ).state)
    }
}
