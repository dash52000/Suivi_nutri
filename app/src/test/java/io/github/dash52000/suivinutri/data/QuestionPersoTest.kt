package io.github.dash52000.suivinutri.data

import io.github.dash52000.suivinutri.labo.Cap
import io.github.dash52000.suivinutri.labo.Labo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class QuestionPersoTest {

    @Test
    fun scoreOuiNonTenuSiNon() {
        val q = QuestionPerso("perso_grignote", "Grignoté après le dîner ?", TypePerso.OUI_NON, "non", Condition.TOUJOURS)
        assertEquals(1.0, q.score("non")!!, 0.0)
        assertEquals(0.0, q.score("oui")!!, 0.0)
        assertNull(q.score(null))
    }

    @Test
    fun scoreEchelleTenuSiOui() {
        val q = QuestionPerso("perso_eau", "Bu assez d'eau ?", TypePerso.ECHELLE, "oui", Condition.TOUJOURS)
        assertEquals(1.0, q.score("oui")!!, 0.0)
        assertEquals(0.5, q.score("un_peu")!!, 0.0)
        assertEquals(0.0, q.score("non")!!, 0.0)
    }

    @Test
    fun pauseParDefautV17() {
        assertEquals(setOf("faim_coucher", "collation"), Configuration.pausesV17(emptySet()))
        assertEquals(setOf("faim_coucher", "collation", "cafes"), Configuration.pausesV17(setOf("cafes")))
        assertTrue(Configuration.PAUSE_V17.all { id -> Questionnaire.questions.any { it.id == id } })
    }

    @Test
    fun slugLisiblePourR() {
        assertEquals("grignote_apres_le_diner", Configuration.slug("Grignoté après le dîner ?"))
        assertEquals("bu_1_5_l_d_eau", Configuration.slug("Bu 1,5 L d'eau"))
        assertTrue(Configuration.slug("Une question vraiment beaucoup trop longue pour un nom de colonne").length <= 30)
        assertTrue(!Configuration.slug("Une question vraiment beaucoup trop longue pour un nom de colonne").endsWith("_"))
    }

    @Test
    fun pauseParDefautV111() {
        // v1.11 : cafés et énergie ne servent à aucun calcul, en pause ; ce sont bien des questions existantes.
        assertEquals(setOf("cafes", "energie"), Configuration.PAUSE_V111)
        assertTrue(Configuration.PAUSE_V111.all { id -> Questionnaire.questions.any { it.id == id } })
    }

    /** Les questions perso entrent dans le calendrier des réflexes et dans les leviers proposés (v1.11). */
    @Test
    fun leLaboAnalyseLesQuestionsPerso() {
        val q = QuestionPerso("perso_grignote", "Grignoté après le dîner ?", TypePerso.OUI_NON, "non", Condition.TOUJOURS)
        val debut = LocalDate.of(2026, 9, 1)
        val entrees = (0L until 14L).associate { k ->
            debut.plusDays(k) to EntreeJour(debut.plusDays(k), mapOf(q.id to "oui"))
        }
        val perso = listOf(q.libelle to { e: EntreeJour -> q.score(e.reponses[q.id]) })
        val calendrier = Labo.calendrier(entrees, entrees.keys.sorted(), Labo.reflexes + perso)
        assertTrue(calendrier.any { it.libelle == q.libelle && it.repondus == 14 })
        assertTrue(q.libelle in Cap.propositions(entrees, debut.plusDays(13), Labo.reflexes + perso))
    }
}
