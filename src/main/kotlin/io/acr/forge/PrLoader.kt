package io.acr.forge

import io.acr.data.PrCacheRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Carga la lista de PRs de un repo con caché.
 *
 * Medido contra Bitbucket: una respuesta buena tarda medio segundo, pero 3 de cada 5 llamadas
 * caen en el límite de frecuencia y ahí el backoff exponencial se lleva decenas de segundos. La
 * latencia real no es la red, es el throttle — así que lo que hace falta no es una llamada más
 * rápida sino menos llamadas, y mostrar algo mientras tanto.
 */
class PrLoader(private val cache: PrCacheRepository) {

    /** Lo último conocido, sin tocar la red. Vale para pintar la lista de inmediato. */
    fun cached(repoId: String): List<PullRequest> = cache.get(repoId).prs

    private val inFlight = Mutex()

    /**
     * Revalida contra el proveedor. Si el ETag coincide vuelve el caché sin descargar nada.
     * @param force ignora el TTL; es lo que hace el botón de refrescar.
     */
    suspend fun refresh(repo: RepoRecord, force: Boolean = false): List<PullRequest> = inFlight.withLock {
        val current = cache.get(repo.id)
        if (!force && current.prs.isNotEmpty() && isFresh(current.fetchedAt)) return current.prs

        // Si al caché le falta un dato que ahora sí usamos, no sirve revalidar: el proveedor
        // contestaría 304 —"tu copia sigue vigente"— y la copia se quedaría incompleta para
        // siempre, porque nada la invalida. Pasó al agregar la fecha de creación: la columna nueva
        // quedaba vacía en todas las filas ya cacheadas. Se pide el cuerpo entero una vez.
        val incompleto = current.prs.any { it.createdOn.isBlank() }
        val etag = current.etag.takeUnless { incompleto }
        val result = Forges.of(repo.provider).listPullRequestsConditional(repo, etag)
        return if (result.notModified) {
            cache.touch(repo.id)
            current.prs
        } else {
            cache.put(repo.id, result.prs, result.etag)
            result.prs
        }
    }

    /** Ventana corta: evita repetir la llamada al saltar entre repos o pantallas. */
    private fun isFresh(fetchedAt: String?): Boolean {
        val t = runCatching { java.time.Instant.parse(fetchedAt) }.getOrNull() ?: return false
        return java.time.Duration.between(t, java.time.Instant.now()).seconds < TTL_SECONDS
    }

    private companion object { const val TTL_SECONDS = 60 }
}
