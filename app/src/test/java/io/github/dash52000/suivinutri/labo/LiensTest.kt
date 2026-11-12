package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.EntreeJour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LiensTest {

    @Test
    fun regleDAnnonce() {
        assertTrue(Liens.annonce(Niveau.PROBABLE, inverse = false))
        assertFalse(Liens.annonce(Niveau.PISTE, inverse = false))
        assertFalse(Liens.annonce(Niveau.PROBABLE, inverse = true))
        assertTrue(Liens.annonce(Niveau.SOLIDE, inverse = true))
    }

    /** Pris ton temps : jamais de rab quand il est tenu, toujours sinon → annoncé, sens attendu, en tête. */
    @Test
    fun ceQuOnSait() {
        val debut = LocalDate.of(2026, 9, 1)
        val jours = (0L until 20L).map { k ->
            val lent = k % 2 == 0L
            JourLabo(
                debut.plusDays(k),
                entree = EntreeJour(debut.plusDays(k), mapOf("temps" to if (lent) "oui" else "non", "resservi" to if (lent) "non" else "oui")),
            )
        }
        val r = Labo.analyser(jours, debut.plusDays(19))
        val sus = Liens.annonces(r)
        assertEquals("Pris ton temps → moins de rab", sus.first().phrase)
        assertEquals(Niveau.SOLIDE, sus.first().niveau)
        assertFalse(sus.any { it.inverse })
        assertTrue(Liens.tous(r).any { it.cle == "meca:Pris ton temps:inverse" && it.niveau == Niveau.AUCUN })
    }
}
