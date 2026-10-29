package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.EntreeJour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LaboTest {

    private val debut = LocalDate.of(2026, 9, 1)

    @Test
    fun tendanceSuitLesPeseesEtSeReporte() {
        val jours = listOf(
            JourLabo(debut, poids = 80.0),
            JourLabo(debut.plusDays(1), poids = 81.0),
            JourLabo(debut.plusDays(2)),
        )
        val t = Labo.tendance(jours)
        assertEquals(80.0, t[debut]!!, 1e-9)
        assertEquals(80.1, t[debut.plusDays(1)]!!, 1e-9)
        assertEquals(80.1, t[debut.plusDays(2)]!!, 1e-9)
    }

    /** Rab un soir sur deux, +0,5 kg le lendemain d'un rab : l'écart attendu à J+1 est exactement +1 kg. */
    @Test
    fun effetLendemainConnu() {
        val jours = (0L until 40L).map { k ->
            val rabCeSoir = k % 2 == 0L
            val rabLaVeille = k % 2 == 1L
            JourLabo(
                date = debut.plusDays(k),
                poids = 80.0 + if (rabLaVeille) 0.5 else 0.0,
                entree = EntreeJour(debut.plusDays(k), mapOf("resservi" to if (rabCeSoir) "oui" else "non")),
            )
        }
        val r = Labo.analyser(jours, debut.plusDays(40))
        val rab = r.lendemain.first { it.libelle == "Soir avec rab" }
        assertNotNull(rab.j1)
        assertEquals(1.0, rab.j1!!.estimation, 1e-9)
        val dessert = r.lendemain.first { it.libelle == "Dessert pris" }
        assertNull(dessert.j1)
        assertEquals(0, dessert.n1)
    }

    /** Dépense constante de 2 500 kcal/j et perte linéaire de 0,1 kg/j : apports ≈ 2 500 − 770 = 1 730 kcal/j. */
    @Test
    fun apportsEstimesSurUnePerteReguliere() {
        val lundi = LocalDate.of(2026, 8, 3) // un lundi
        val jours = (0L until 70L).map { k ->
            JourLabo(date = lundi.plusDays(k), poids = 85.0 - 0.1 * k, kcal = 2500.0)
        }
        val r = Labo.analyser(jours, lundi.plusDays(70))
        assertTrue(r.semaines.isNotEmpty())
        val derniere = r.semaines.first()
        assertEquals(2500.0, derniere.depenseMoyenne, 1e-9)
        assertEquals(1730.0, derniere.apportsEstimes, 20.0)
    }

    @Test
    fun qualiteCompteLesJoursRenseignes() {
        val aujourdHui = debut.plusDays(30)
        val jours = (1L..28L).map { k ->
            val d = aujourdHui.minusDays(k)
            JourLabo(d, entree = if (k % 2 == 0L) EntreeJour(d, mapOf("diner" to "maison")) else null)
        }
        assertEquals(14, Labo.analyser(jours, aujourdHui).qualite.joursRenseignes28)
    }
}
