package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.EntreeJour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/** Valeurs attendues calculées avec l'implémentation de référence numpy (voir KalmanTest). */
class LaboTest {

    private val debut = LocalDate.of(2026, 9, 1)

    /** Rab un soir sur deux, +0,5 kg le lendemain d'un rab : écart à la tendance de +0,5 kg (référence 0,499115). */
    @Test
    fun effetLendemainConnu() {
        val jours = (0L until 40L).map { k ->
            JourLabo(
                date = debut.plusDays(k),
                poids = 80.0 + if (k % 2 == 1L) 0.5 else 0.0,
                entree = EntreeJour(debut.plusDays(k), mapOf("resservi" to if (k % 2 == 0L) "oui" else "non")),
            )
        }
        val r = Labo.analyser(jours, debut.plusDays(39))
        val rab = r.lendemain.first { it.libelle == "Soir avec rab" }
        assertNotNull(rab.j1)
        assertEquals(0.499115, rab.j1!!.estimation, 1e-4)
        val dessert = r.lendemain.first { it.libelle == "Dessert pris" }
        assertNull(dessert.j1)
        assertEquals(0, dessert.n1)
    }

    /**
     * Rab et dessert arrivent souvent ensemble (corrélation 0,58), mais seul le dessert fait monter le poids
     * (+0,6 kg). La régression jointe attribue l'effet au dessert ; une comparaison rab / sans rab, elle,
     * donnerait +0,26 kg au rab. Référence numpy : rab −0,103316, dessert 0,658245.
     */
    @Test
    fun regressionJointeSepareLesExpositions() {
        val n = 60
        val dessert = (0 until n).map { it % 3 == 0 }
        val rab = (0 until n).map { t -> if (t % 5 == 0) !dessert[t] else dessert[t] }
        val jours = (0 until n).map { t ->
            JourLabo(
                date = debut.plusDays(t.toLong()),
                poids = 80 + 0.2 * kotlin.math.sin(1.7 * t) + if (t > 0 && dessert[t - 1]) 0.6 else 0.0,
                entree = EntreeJour(
                    debut.plusDays(t.toLong()),
                    mapOf(
                        "resservi" to if (rab[t]) "oui" else "non",
                        "dessert" to if (dessert[t]) "normale" else "non",
                    ),
                ),
            )
        }
        val r = Labo.analyser(jours, debut.plusDays((n - 1).toLong()))
        val effetRab = r.lendemain.first { it.libelle == "Soir avec rab" }.j1!!
        val effetDessert = r.lendemain.first { it.libelle == "Dessert pris" }.j1!!
        assertEquals(-0.103316, effetRab.estimation, 1e-4)
        assertEquals(0.658245, effetDessert.estimation, 1e-4)
        assertTrue(abs(effetRab.estimation) < 0.2)
        assertNotNull(effetDessert.p)
    }

    /** Dépense 2 500 kcal/j, perte de 0,1 kg/j : apports ≈ 2 500 − 770 = 1 730 (référence 1 725,39 en semaine 5). */
    @Test
    fun apportsEstimesAvecIntervalle() {
        val lundi = LocalDate.of(2026, 8, 3) // un lundi
        val jours = (0L until 71L).map { k -> JourLabo(date = lundi.plusDays(k), poids = 85.0 - 0.1 * k, kcal = 2500.0) }
        val r = Labo.analyser(jours, lundi.plusDays(70))
        val s5 = r.semaines.first { it.lundi == lundi.plusWeeks(5) }
        assertFalse(s5.enCours)
        assertEquals(2500.0, s5.depenseMoyenne, 1e-9)
        assertEquals(1725.39, s5.apportsEstimes, 0.05)
        // Écart-type de référence 135,409 kcal/j : demi-largeur de l'IC = 1,96 × 135,409.
        assertEquals(1.959964 * 135.409, (s5.apports.haut - s5.apports.bas) / 2, 0.05)
    }

    @Test
    fun semaineEnCoursApresDeuxJours() {
        val lundi = LocalDate.of(2026, 10, 26)
        val aujourdHui = lundi.plusWeeks(1).plusDays(3) // jeudi
        val jours = (0L..10L).map { k -> JourLabo(date = lundi.plusDays(k), poids = 88.0, kcal = 2600.0) }
        val r = Labo.analyser(jours, aujourdHui)
        val enCours = r.semaines.first()
        assertTrue(enCours.enCours)
        assertEquals(3, enCours.nbJours)
        assertEquals(2600.0, enCours.depenseMoyenne, 1e-9)
        assertEquals(2600.0, enCours.apportsEstimes, 1.0)
    }

    @Test
    fun peseeAberranteEcartee() {
        val jours = (0L until 10L).map { k ->
            JourLabo(date = debut.plusDays(k), poids = if (k == 6L) 93.0 else 80.0)
        }
        val r = Labo.analyser(jours, debut.plusDays(9))
        assertTrue(r.poids.first { it.date == debut.plusDays(6) }.ecartee)
        assertEquals(80.0, r.poids.last().tendance, 0.01)
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
