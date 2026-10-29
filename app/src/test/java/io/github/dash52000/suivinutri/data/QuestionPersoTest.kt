package io.github.dash52000.suivinutri.data

import io.github.dash52000.suivinutri.labo.JourLabo
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
    fun slugLisiblePourR() {
        assertEquals("grignote_apres_le_diner", Configuration.slug("Grignoté après le dîner ?"))
        assertEquals("bu_1_5_l_d_eau", Configuration.slug("Bu 1,5 L d'eau"))
        assertTrue(Configuration.slug("Une question vraiment beaucoup trop longue pour un nom de colonne").length <= 30)
        assertTrue(!Configuration.slug("Une question vraiment beaucoup trop longue pour un nom de colonne").endsWith("_"))
    }

    @Test
    fun leLaboAnalyseLesQuestionsPerso() {
        val q = QuestionPerso("perso_grignote", "Grignoté après le dîner ?", TypePerso.OUI_NON, "non", Condition.TOUJOURS)
        val debut = LocalDate.of(2026, 9, 1)
        val jours = (0L until 14L).map { k ->
            JourLabo(debut.plusDays(k), entree = EntreeJour(debut.plusDays(k), mapOf(q.id to "non")))
        }
        val perso = listOf(q.libelle to { e: EntreeJour -> q.score(e.reponses[q.id]) })
        val r = Labo.analyser(jours, debut.plusDays(14), perso)
        assertTrue(r.effetsSemaine.any { it.libelle == q.libelle })
    }
}
