package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.EntreeJour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Random

class SemaineTypeTest {

    private val debut = LocalDate.of(2026, 9, 6) // un dimanche

    /** Le lundi pèse 0,6 kg de plus : son écart moyen ressort, les autres jours restent près de zéro. */
    @Test
    fun balanceDuLundi() {
        val alea = Random(1)
        val jours = (0L until 56L).map { k ->
            val d = debut.plusDays(k)
            JourLabo(d, poids = 80.0 + 0.05 * alea.nextGaussian() + if (d.dayOfWeek == DayOfWeek.MONDAY) 0.6 else 0.0)
        }
        val r = Labo.analyser(jours, debut.plusDays(55))
        val lundi = r.balanceParJour.first { it.jour == DayOfWeek.MONDAY }
        assertEquals(8, lundi.n)
        assertTrue(lundi.ecart!!.estimation > 0.4)
        assertTrue(lundi.ecart!!.bas > 0)
        r.balanceParJour.filter { it.jour != DayOfWeek.MONDAY }.forEach { assertTrue(it.ecart!!.estimation < 0.0) }
    }

    @Test
    fun soirsDuVendredi() {
        val jours = (0L until 28L).map { k ->
            val d = debut.plusDays(k)
            JourLabo(d, entree = EntreeJour(d, mapOf("resservi" to if (d.dayOfWeek == DayOfWeek.FRIDAY) "oui" else "non")))
        }
        val r = Labo.analyser(jours, debut.plusDays(27))
        assertEquals(1.0, r.soirsParJour.first { it.jour == DayOfWeek.FRIDAY }.rab!!, 0.0)
        assertEquals(0.0, r.soirsParJour.first { it.jour == DayOfWeek.TUESDAY }.rab!!, 0.0)
        assertNull(r.soirsParJour.first { it.jour == DayOfWeek.TUESDAY }.alcool)
    }

    /** La pesée ne bouge qu'avec le rab de la veille : la veille explique l'essentiel, l'eau rien. */
    @Test
    fun origineVeille() {
        val alea = Random(2)
        val rab = (0 until 60).map { alea.nextDouble() < 0.4 }
        val jours = (0 until 60).map { k ->
            val d = debut.plusDays(k.toLong())
            JourLabo(
                d,
                poids = 80.0 + 0.05 * alea.nextGaussian() + if (k > 0 && rab[k - 1]) 0.6 else 0.0,
                entree = EntreeJour(d, mapOf("resservi" to if (rab[k]) "oui" else "non")),
            )
        }
        val r = Labo.analyser(jours, debut.plusDays(59))
        val o = r.origineEcart
        assertNotNull(o)
        assertTrue("veille ${o!!.veille}", o.veille > 0.6)
        assertEquals(0.0, o.eau, 1e-9)
        assertEquals(1.0, o.veille + o.eau + o.reste, 1e-9)
    }

    /** La pesée ne bouge qu'avec l'eau mesurée : l'eau explique l'essentiel. */
    @Test
    fun origineEau() {
        val alea = Random(3)
        val jours = (0 until 60).map { k ->
            val d = debut.plusDays(k.toLong())
            val eau = 0.6 * alea.nextGaussian()
            JourLabo(d, poids = 80.0 + 0.73 * eau + 0.05 * alea.nextGaussian(), eauKg = 45.0 + eau, graissePct = 25.0)
        }
        val r = Labo.analyser(jours, debut.plusDays(59))
        val o = r.origineEcart!!
        assertTrue("eau ${o.eau}", o.eau > 0.6)
        assertTrue(r.serieComposition.isNotEmpty())
        assertEquals(r.poids.last().date, r.serieComposition.last().date)
    }
}
