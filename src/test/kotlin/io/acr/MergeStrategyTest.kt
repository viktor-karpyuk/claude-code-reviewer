package io.acr

import io.acr.forge.MergeStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Las tres estrategias que ofrece Bitbucket. Cuál está habilitada lo decide el repositorio y la
 * API no lo publica en ningún endpoint consultable —lo verifiqué contra el repo real—, así que se
 * ofrecen las tres y el proveedor rechaza la que no corresponda.
 */
class MergeStrategyTest {

    @Test
    fun bitbucketNamesAreTheOnesTheApiExpects() {
        assertEquals("merge_commit", MergeStrategy.MERGE_COMMIT.bitbucket)
        assertEquals("squash", MergeStrategy.SQUASH.bitbucket)
        assertEquals("fast_forward", MergeStrategy.FAST_FORWARD.bitbucket)
    }

    @Test
    fun gitHubNamesAreTheOnesThatApiExpects() {
        assertEquals("merge", MergeStrategy.MERGE_COMMIT.github)
        assertEquals("squash", MergeStrategy.SQUASH.github)
        // El mapeo no es exacto: el rebase de GitHub reescribe los commits sobre la punta del
        // destino, que se parece a un fast-forward pero no es lo mismo. Se elige el más cercano.
        assertEquals("rebase", MergeStrategy.FAST_FORWARD.github)
    }

    @Test
    fun theDefaultIsTheLeastSurprising() {
        // Un commit de merge conserva la historia de la rama; squash y fast-forward la pierden o
        // la reescriben. Ante un valor guardado inválido, la que menos rompe.
        assertEquals(MergeStrategy.MERGE_COMMIT, MergeStrategy.fromName(null))
        assertEquals(MergeStrategy.MERGE_COMMIT, MergeStrategy.fromName(""))
        assertEquals(MergeStrategy.MERGE_COMMIT, MergeStrategy.fromName("REBASE_Y_REZAR"))
    }

    @Test
    fun theChoiceSurvivesARestart() {
        assertEquals(MergeStrategy.SQUASH, MergeStrategy.fromName(MergeStrategy.SQUASH.name))
    }

    @Test
    fun everyStrategyHasALabelInBothLanguages() {
        MergeStrategy.entries.forEach { e ->
            listOf(io.acr.i18n.Lang.ES, io.acr.i18n.Lang.EN).forEach { idioma ->
                assertTrue(
                    io.acr.i18n.I18n.get(idioma, e.labelKey) != e.labelKey,
                    "falta ${e.labelKey} en $idioma",
                )
            }
        }
    }
}
