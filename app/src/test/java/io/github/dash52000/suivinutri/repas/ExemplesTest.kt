package io.github.dash52000.suivinutri.repas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exemple grisé selon le contexte (3.1 B) ; jamais de kcal sur l'écran Ce soir. */
class ExemplesTest {

    @Test
    fun midiApresUnTuppDonneLExempleDesRestes() {
        val c = ContexteSaisie.depuis(mapOf("midi_quoi" to "tupp"))
        assertEquals("ex. : restes de lasagnes, assiette normale, une pomme", Exemples.pour(Moment.MIDI, c))
    }

    @Test
    fun soirChezLesBeauxParentsAvecRabMontreLeRabEtLesBoissons() {
        val c = ContexteSaisie.depuis(mapOf("diner" to "beaux_parents", "resservi" to "oui"))
        assertEquals("ex. : lasagnes, 1 grosse assiette + ½ en rab, tarte, 2 Orval", Exemples.pour(Moment.SOIR, c))
    }

    @Test
    fun lAlcoolSuffitAMontrerLeRabEtLesBoissons() {
        val c = ContexteSaisie.depuis(mapOf("diner" to "beaux_parents", "alcool" to "oui"))
        assertEquals("ex. : lasagnes, 1 grosse assiette + ½ en rab, tarte, 2 Orval", Exemples.pour(Moment.SOIR, c))
    }

    @Test
    fun soirALaMaisonEtSansTapDonnentLOmelette() {
        assertEquals("ex. : omelette 3 œufs, salade, pain", Exemples.pour(Moment.SOIR, ContexteSaisie.depuis(mapOf("diner" to "maison"))))
        assertEquals("ex. : omelette 3 œufs, salade, pain", Exemples.pour(Moment.SOIR))
    }

    @Test
    fun grignotageMontreLHeureDansLaPhrase() {
        assertEquals("ex. : 16h paquet de chips", Exemples.pour(Moment.GRIGNOTAGE))
    }

    @Test
    fun resserviNonNeChangePasLExempleDesBeauxParentsEnRab() {
        val sans = ContexteSaisie.depuis(mapOf("diner" to "beaux_parents", "resservi" to "non"))
        val avec = ContexteSaisie.depuis(mapOf("diner" to "beaux_parents", "resservi" to "un_peu"))
        assertNotEquals(Exemples.pour(Moment.SOIR, sans), Exemples.pour(Moment.SOIR, avec))
    }

    @Test
    fun chaqueMomentEtChaqueContexteOntUnExempleSansKcal() {
        val contextes = listOf(
            ContexteSaisie(),
            ContexteSaisie.depuis(mapOf("midi_quoi" to "tupp")),
            ContexteSaisie.depuis(mapOf("midi_quoi" to "prepare")),
            ContexteSaisie.depuis(mapOf("midi_quoi" to "achete")),
            ContexteSaisie.depuis(mapOf("diner" to "ailleurs")),
            ContexteSaisie.depuis(mapOf("diner" to "beaux_parents")),
            ContexteSaisie.depuis(mapOf("diner" to "beaux_parents", "resservi" to "oui", "alcool" to "oui")),
        )
        for (m in Moment.entries) for (c in contextes) {
            val e = Exemples.pour(m, c)
            assertTrue("exemple vide pour $m $c", e.startsWith("ex. : "))
            assertFalse("kcal dans l'exemple : $e", e.lowercase().contains("kcal") || e.lowercase().contains("calorie"))
        }
    }
}
