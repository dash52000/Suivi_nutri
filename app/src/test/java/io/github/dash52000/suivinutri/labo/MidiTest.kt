package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.Condition
import io.github.dash52000.suivinutri.data.EntreeJour
import io.github.dash52000.suivinutri.data.Questionnaire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate

class MidiTest {

    private val d0 = LocalDate.of(2026, 9, 1)

    @Test
    fun ecranSeulementSiPasALExterieur() {
        val c = Condition.MIDI_PAS_EXTERIEUR
        assertEquals(false, Questionnaire.conditionRemplie(c, emptyMap()))
        assertEquals(false, Questionnaire.conditionRemplie(c, mapOf("midi_lieu" to "exterieur")))
        assertEquals(true, Questionnaire.conditionRemplie(c, mapOf("midi_lieu" to "boulot")))
        assertEquals(null, Questionnaire.conditionRemplie(Condition.TOUJOURS, emptyMap()))
    }

    /** Un soir sur deux tupp remplis ; le midi suivant, tupp mangé seulement après un soir « oui ». */
    @Test
    fun tuppDeLaVeilleVersMidi() {
        val jours = (0L until 20L).map { k ->
            val tuppCeSoir = if (k % 2 == 0L) "oui" else "non"
            val tuppHier = k % 2 == 1L
            val reponses = mutableMapOf("tupp" to tuppCeSoir, "diner" to "beaux_parents")
            if (k > 0) reponses["midi_quoi"] = if (tuppHier) "tupp" else "achete"
            JourLabo(d0.plusDays(k), entree = EntreeJour(d0.plusDays(k), reponses))
        }
        val r = Labo.analyser(jours, d0.plusDays(20))
        val effet = r.croises.first { it.issue == "tupp mangé le midi" }
        assertEquals(1.0, effet.p1!!, 0.0)
        assertEquals(0.0, effet.p0!!, 0.0)
        assertNotNull(effet.ecart)
        assertEquals(1.0, effet.ecart!!.estimation, 1e-9)
    }

    @Test
    fun seanceDuMidiVersPetitCreux() {
        val jours = (0L until 20L).map { k ->
            val seance = k % 2 == 0L
            JourLabo(
                date = d0.plusDays(k),
                entree = EntreeJour(d0.plusDays(k), mapOf("midi_creux" to if (seance) "oui" else "non")),
                seanceMidi = seance,
            )
        }
        val effet = Labo.analyser(jours, d0.plusDays(20)).croises.first { it.issue == "petit creux l'après-midi" }
        assertEquals(10, effet.n1)
        assertEquals(10, effet.n0)
        assertEquals(1.0, effet.ecart!!.estimation, 1e-9)
    }
}
