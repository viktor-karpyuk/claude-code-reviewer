package io.acr

import io.acr.forge.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Un PR aprobado no se vuelve a revisar: la aprobación es la señal de que se terminó de mirar, y
 * seguir revisándolo gasta una corrida del modelo en algo ya decidido.
 *
 * El listado de Bitbucket no trae las aprobaciones —sólo el PR individual, en `participants`— así
 * que se guardan localmente y la lista lee de ahí, sin una llamada por fila.
 */
class ApprovalTest {

    private fun conRepo(block: (AppContext, String, Long) -> Unit) {
        val ctx = AppContext.bootstrap()
        try {
            val prId = System.nanoTime() % 100_000
            val repoId = ctx.repos.create(
                "tmp-apr-$prId", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            try { block(ctx, repoId, prId) } finally { ctx.repos.delete(repoId) }
        } finally { ctx.close() }
    }

    @Test
    fun ourApprovalIsRemembered() = conRepo { ctx, repoId, prId ->
        ctx.approvals.record(repoId, prId, "nosotros", byUs = true)
        val guardadas = ctx.approvals.forPr(repoId, prId)
        assertEquals(1, guardadas.size)
        assertTrue(guardadas.single().byUs)
    }

    @Test
    fun approvalsSeenOnThePrAreStored() = conRepo { ctx, repoId, prId ->
        // Es el caso real: al abrir el PR #149 se ve que Tomás Rivero ya lo había aprobado.
        ctx.approvals.sync(repoId, prId, listOf("Tomás Rivero"))
        assertEquals(listOf("Tomás Rivero"), ctx.approvals.forPr(repoId, prId).map { it.approvedBy })
    }

    @Test
    fun withdrawingAnApprovalRemovesIt() = conRepo { ctx, repoId, prId ->
        // Se puede retirar una aprobación en Bitbucket; si no se borrara, el PR quedaría marcado
        // como aprobado para siempre y nunca se volvería a revisar.
        ctx.approvals.sync(repoId, prId, listOf("Tomás Rivero", "Otro"))
        assertEquals(2, ctx.approvals.forPr(repoId, prId).size)
        ctx.approvals.sync(repoId, prId, listOf("Otro"))
        assertEquals(listOf("Otro"), ctx.approvals.forPr(repoId, prId).map { it.approvedBy })
    }

    @Test
    fun syncDoesNotEraseOurOwnApproval() = conRepo { ctx, repoId, prId ->
        // La nuestra la conocemos de primera mano; un sync que no la vea —porque la API tardó en
        // reflejarla— no puede borrarla y hacer que el barrido vuelva a revisar el PR.
        ctx.approvals.record(repoId, prId, "nosotros", byUs = true)
        ctx.approvals.sync(repoId, prId, emptyList())
        assertEquals(listOf("nosotros"), ctx.approvals.forPr(repoId, prId).map { it.approvedBy })
    }

    @Test
    fun approvingTwiceDoesNotDuplicate() = conRepo { ctx, repoId, prId ->
        ctx.approvals.record(repoId, prId, "nosotros", byUs = true)
        ctx.approvals.record(repoId, prId, "nosotros", byUs = true)
        assertEquals(1, ctx.approvals.forPr(repoId, prId).size)
    }

    @Test
    fun approvingAndRequestingChangesAreExclusive() {
        // Es como lo modela Bitbucket: `participants[].state` es approved, changes_requested o
        // nada, y son excluyentes. Pedir cambios después de aprobar tiene que reemplazar, no sumar.
        conRepo { ctx, repoId, prId ->
            ctx.approvals.record(repoId, prId, "nosotros", byUs = true, io.acr.data.ReviewStance.APPROVED)
            ctx.approvals.record(
                repoId, prId, "nosotros", byUs = true, io.acr.data.ReviewStance.CHANGES_REQUESTED,
            )
            val nuestra = ctx.approvals.forPr(repoId, prId).single { it.byUs }
            assertEquals(io.acr.data.ReviewStance.CHANGES_REQUESTED, nuestra.stance)
        }
    }

    @Test
    fun withdrawingRemovesOnlyOurStance() = conRepo { ctx, repoId, prId ->
        ctx.approvals.record(repoId, prId, "nosotros", byUs = true)
        ctx.approvals.sync(repoId, prId, listOf("Tomás Rivero"))
        ctx.approvals.clearOurs(repoId, prId)
        val quedan = ctx.approvals.forPr(repoId, prId)
        assertEquals(listOf("Tomás Rivero"), quedan.map { it.approvedBy })
    }

    @Test
    fun othersRequestingChangesAreStoredToo() = conRepo { ctx, repoId, prId ->
        ctx.approvals.sync(
            repoId, prId,
            aprobadores = listOf("Tomás Rivero"),
            pidieronCambios = listOf("Braian Chavez"),
        )
        val porPostura = ctx.approvals.forPr(repoId, prId).associate { it.approvedBy to it.stance }
        assertEquals(io.acr.data.ReviewStance.APPROVED, porPostura["Tomás Rivero"])
        assertEquals(io.acr.data.ReviewStance.CHANGES_REQUESTED, porPostura["Braian Chavez"])
    }

    @Test
    fun ourActionsUseTheNameTheProviderGivesUs() {
        // Guardar "nosotros" creaba una persona fantasma: la misma aprobación figuraba dos veces,
        // una registrada por la app y otra con el nombre real cuando el sync la traía de la API.
        val ctx = AppContext.bootstrap()
        try {
            val prId = System.nanoTime() % 100_000
            val repoId = ctx.repos.create(
                "tmp-nom-$prId", Provider.BITBUCKET, "acme", "demo",
                System.getProperty("java.io.tmpdir"), null, null, null, "", false,
                io.acr.forge.SkipRules(), io.acr.forge.ReplyMode.OFF,
            )
            try {
                ctx.comments.sync(
                    repoId, prId,
                    listOf(
                        io.acr.forge.PrComment("c1", "Viktor Karpyuk", "b", null, null, false, "2026-08-13", null),
                        io.acr.forge.PrComment("c2", "Otro", "b", null, null, false, "2026-08-13", null),
                    ),
                    ourCommentIds = setOf("c1"),
                )
                assertEquals("Viktor Karpyuk", ctx.comments.ourDisplayName())

                // Registrada con ese nombre, el sync desde la API la reconoce como la misma fila.
                ctx.approvals.record(repoId, prId, "Viktor Karpyuk", byUs = true)
                ctx.approvals.sync(repoId, prId, listOf("Viktor Karpyuk"))
                val filas = ctx.approvals.forPr(repoId, prId)
                assertEquals(1, filas.size, "quedó duplicada la misma persona")
                assertTrue(filas.single().byUs, "se perdió que era nuestra")
            } finally {
                ctx.repos.delete(repoId)
            }
        } finally {
            ctx.close()
        }
    }

    @Test
    fun participatingWithoutWeighingInIsNotAnApproval() {
        // Se guarda para poder mostrarlo en gris —"está, no opinó"— pero no puede contar como
        // pronunciamiento: si contara, el barrido dejaría de revisar un PR que nadie aprobó.
        conRepo { ctx, repoId, prId ->
            ctx.approvals.sync(
                repoId, prId,
                aprobadores = emptyList(),
                pidieronCambios = emptyList(),
                sinPronunciarse = listOf("Mateo Andrés Perano", "Braian Chavez"),
            )
            assertEquals(2, ctx.approvals.forPr(repoId, prId).size)
            assertTrue(
                ctx.approvals.statedByPr(repoId).isEmpty(),
                "un participante sin postura se contó como pronunciamiento",
            )
        }
    }

    @Test
    fun theThreeStancesCoexistOnOnePr() {
        // Es la foto real del PR #149: uno aprobó, otros participan sin opinar.
        conRepo { ctx, repoId, prId ->
            ctx.approvals.sync(
                repoId, prId,
                aprobadores = listOf("Tomás Rivero"),
                pidieronCambios = listOf("Braian Chavez"),
                sinPronunciarse = listOf("Mateo Andrés Perano"),
            )
            val porNombre = ctx.approvals.forPr(repoId, prId).associate { it.approvedBy to it.stance }
            assertEquals(io.acr.data.ReviewStance.APPROVED, porNombre["Tomás Rivero"])
            assertEquals(io.acr.data.ReviewStance.CHANGES_REQUESTED, porNombre["Braian Chavez"])
            assertEquals(io.acr.data.ReviewStance.NONE, porNombre["Mateo Andrés Perano"])
            // Y sólo dos cuentan como postura.
            assertEquals(2, ctx.approvals.statedByPr(repoId)[prId]!!.size)
        }
    }

    @Test
    fun theListReadsThemInOneQuery() = conRepo { ctx, repoId, prId ->
        // Una consulta para todo el repositorio: pedir el PR individual por fila sería una llamada
        // de red por PR, y con el 401 intermitente de Bitbucket eso es inviable.
        ctx.approvals.record(repoId, prId, "a", byUs = true)
        ctx.approvals.record(repoId, prId + 1, "b", byUs = false)
        val porPr = ctx.approvals.byPr(repoId)
        assertEquals(2, porPr.size)
        assertEquals("a", porPr[prId]!!.single().approvedBy)
    }
}
