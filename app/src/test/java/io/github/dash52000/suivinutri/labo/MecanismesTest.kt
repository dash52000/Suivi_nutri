package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.EntreeJour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random
import kotlin.math.abs

/** Mécanismes des réflexes en probabilités bayésiennes (v1.12). */
class MecanismesTest {

    private val debut = LocalDate.of(2026, 1, 4)
    private val temps = Mecanismes.liste.first { it.libelle == "Pris ton temps" }

    /** Soirs générés : (tenu, rab, lieu du dîner) répétés. */
    private fun soirs(vararg groupes: Triple<Int, Pair<Boolean, Boolean>, String>): List<EntreeJour> {
        var k = 0L
        return groupes.flatMap { (n, tr, lieu) ->
            (0 until n).map {
                val d = debut.plusDays(k++)
                EntreeJour(d, mapOf("temps" to if (tr.first) "oui" else "non", "resservi" to if (tr.second) "oui" else "non", "diner" to lieu))
            }
        }
    }

    @Test
    fun effetNetSolide() {
        // Tenu : 2 rabs sur 20 ; pas tenu : 12 sur 20.
        val e = soirs(
            Triple(2, true to true, "maison"), Triple(18, true to false, "maison"),
            Triple(12, false to true, "maison"), Triple(8, false to false, "maison"),
        )
        val r = Mecanismes.evaluer(temps, e)
        assertEquals(20, r.n1)
        assertEquals(20, r.n0)
        assertEquals(0.10, r.p1!!, 1e-9)
        assertEquals(0.60, r.p0!!, 1e-9)
        assertTrue("prob ${r.probReduit}", r.probReduit!! > 0.975)
        assertEquals(Niveau.SOLIDE, r.niveau)
        assertEquals(Niveau.AUCUN, r.niveauInverse)
        // A priori sceptique : l'écart est tiré vers zéro, sans changer de signe.
        assertTrue(r.ecart!!.estimation < 0 && r.ecart!!.estimation > -0.5)
    }

    @Test
    fun contexteNeGonflePasLEffet() {
        // Même taux de rab, tenu ou non, dans chaque lieu ; mais on prend plus son temps à la maison, où on se
        // ressert moins. Brut : 25 % contre 37,5 %. À lieu égal : aucun effet.
        val e = soirs(
            Triple(2, true to true, "maison"), Triple(14, true to false, "maison"),
            Triple(1, false to true, "maison"), Triple(7, false to false, "maison"),
            Triple(4, true to true, "beaux_parents"), Triple(4, true to false, "beaux_parents"),
            Triple(8, false to true, "beaux_parents"), Triple(8, false to false, "beaux_parents"),
        )
        val r = Mecanismes.evaluer(temps, e)
        assertEquals(0.25, r.p1!!, 1e-9)
        assertEquals(0.375, r.p0!!, 1e-9)
        assertTrue("écart ${r.ecart!!.estimation}", abs(r.ecart!!.estimation) < 0.05)
        assertTrue(r.niveau < Niveau.PROBABLE)
    }

    @Test
    fun tropPeuDeSoirs() {
        val e = soirs(Triple(2, true to false, "maison"), Triple(5, false to true, "maison"))
        val r = Mecanismes.evaluer(temps, e)
        assertNull(r.probReduit)
        assertEquals(Niveau.AUCUN, r.niveau)
    }

    @Test
    fun questionsEnPauseEcartees() {
        val tous = Mecanismes.evaluer(emptyMap(), debut).map { it.libelle }
        val sansBoites = Mecanismes.evaluer(emptyMap(), debut, setOf("boites")).map { it.libelle }
        assertTrue("Boîtes posées" in tous)
        assertTrue("Boîtes posées" !in sansBoites)
        assertEquals(tous.size - 1, sansBoites.size)
    }

    @Test
    fun niveauxSelonSeuilsEtNombre() {
        assertEquals(Niveau.SOLIDE, Mecanismes.niveau(0.98, 8, 8))
        assertEquals(Niveau.PROBABLE, Mecanismes.niveau(0.98, 6, 8)) // pas assez de soirs pour « solide »
        assertEquals(Niveau.PISTE, Mecanismes.niveau(0.75, 3, 3))
        assertEquals(Niveau.AUCUN, Mecanismes.niveau(0.65, 20, 20))
    }

    /**
     * Garde-fou : sans effet réel (40 % de rab dans les deux groupes, 20 soirs chacun), « probable » ne doit pas
     * sortir plus d'une fois sur 8, « solide » plus d'une fois sur 20 ; avec un effet fort (20 % contre 60 %),
     * « probable » doit sortir au moins 8 fois sur 10. Simulation de référence (numpy, 11/11/2026) : 6 %, 2 %, 91 %.
     */
    @Test
    fun simulationDesNiveaux() {
        val alea = Random(7L)
        fun taux(p1: Double, p0: Double, essais: Int = 150): Pair<Double, Double> {
            var probable = 0
            var solide = 0
            repeat(essais) {
                val e = (0 until 40).map { k ->
                    val tenu = k < 20
                    val rab = alea.nextDouble() < if (tenu) p1 else p0
                    EntreeJour(debut.plusDays(k.toLong()), mapOf("temps" to if (tenu) "oui" else "non", "resservi" to if (rab) "oui" else "non", "diner" to "maison"))
                }
                val n = Mecanismes.evaluer(temps, e).niveau
                if (n >= Niveau.PROBABLE) probable++
                if (n == Niveau.SOLIDE) solide++
            }
            return probable.toDouble() / essais to solide.toDouble() / essais
        }
        val (fauxProbable, fauxSolide) = taux(0.4, 0.4)
        assertTrue("faux probable $fauxProbable", fauxProbable <= 0.125)
        assertTrue("faux solide $fauxSolide", fauxSolide <= 0.05)
        val (vraiProbable, _) = taux(0.2, 0.6)
        assertTrue("vrai probable $vraiProbable", vraiProbable >= 0.80)
    }
}
