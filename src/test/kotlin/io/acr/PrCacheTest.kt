package io.acr

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrCacheTest {

    @Test
    fun cacheIsInstantAndRevalidationIsCheap() {
        val ctx = AppContext.bootstrap()
        try {
            val repo = ctx.repos.list().firstOrNull() ?: run { println("SKIP: sin repos"); return }

            // 1) Primera carga: pega a la red y llena el caché.
            val t0 = System.currentTimeMillis()
            val first = runBlocking { ctx.prLoader.refresh(repo, force = true) }
            val ms1 = System.currentTimeMillis() - t0
            println("primera carga (red): ${first.size} PRs en ${ms1}ms")

            // 2) Lectura de caché: sin red.
            val t1 = System.currentTimeMillis()
            val cached = ctx.prLoader.cached(repo.id)
            val ms2 = System.currentTimeMillis() - t1
            println("desde caché: ${cached.size} PRs en ${ms2}ms")
            assertEquals(first.size, cached.size)
            assertTrue(ms2 < 200, "el caché debería ser inmediato, tardó ${ms2}ms")

            // 3) Dentro del TTL no vuelve a pegar a la red.
            val t2 = System.currentTimeMillis()
            runBlocking { ctx.prLoader.refresh(repo, force = false) }
            val ms3 = System.currentTimeMillis() - t2
            println("dentro del TTL: ${ms3}ms")
            assertTrue(ms3 < 200, "el TTL debería evitar la llamada, tardó ${ms3}ms")

            // 4) Forzada: revalida con ETag; si nada cambió no descarga.
            val t3 = System.currentTimeMillis()
            val again = runBlocking { ctx.prLoader.refresh(repo, force = true) }
            println("revalidación forzada: ${again.size} PRs en ${System.currentTimeMillis() - t3}ms")
            assertEquals(first.size, again.size)
        } finally {
            ctx.close()
        }
    }
}
