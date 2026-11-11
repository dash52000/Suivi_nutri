package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.EtatObjectif
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random

/**
 * Garde-fou (v1.11) : la règle du couloir d'effort est simulée de bout en bout, avec le bruit réel, avant d'être
 * livrée. Pesées quotidiennes = niveau + 0,73 × écart d'eau + bruit (0,42 kg) ; l'eau mesurée par la balance porte
 * l'écart (0,5 kg) ; le niveau suit le modèle de Hall et fluctue de 0,05 kg par jour (le bruit supposé par le
 * Kalman). « Proposition » = 2 verdicts « au-dessus » de suite sur 26 semaines, verdicts figés.
 * Simulation de référence (numpy, 11/11/2026, seuil 90 %) : 11 % de fausses propositions avec un effort tenu à
 * −400 kcal/j, 90 % de propositions avec un effort trop faible à −250 kcal/j.
 */
class SimulationCouloirTest {

    private val semaines = 26
    private val depart = LocalDate.of(2026, 3, 1) // un dimanche

    private fun simuler(effort: (jour: Int, ecart: Double) -> Double, alea: Random): List<JourLabo> {
        val avant = 28
        val n = avant + 7 * semaines + 1
        var niveau = 80.0
        return (0 until n).map { k ->
            if (k > avant) {
                val ecart = niveau - 80.0
                niveau += (effort(k - avant, ecart) - Hall.EPS * ecart) / Hall.RHO + 0.05 * alea.nextGaussian()
            }
            val eau = 0.5 * alea.nextGaussian()
            JourLabo(
                date = depart.plusDays((k - avant).toLong()),
                poids = niveau + 0.73 * eau + 0.42 * alea.nextGaussian(),
                eauKg = 45.0 + eau,
                heurePesee = 7,
            )
        }
    }

    /** Part des simulations où la règle proposerait une bière de moins dans les 26 semaines. */
    private fun tauxProposition(effort: (Int, Double) -> Double, graine: Long, n: Int = 60): Double {
        val alea = Random(graine)
        var propositions = 0
        repeat(n) {
            val jours = simuler(effort, alea)
            val fin = depart.plusWeeks(semaines.toLong())
            val r = Cap.analyser(jours, Labo.lissage(jours, fin), EtatObjectif(debut = depart), fin, emptyList())
            if (Cap.premiereBaisse(r.semainesFinies.map { it.verdict }) != null) propositions++
        }
        return propositions.toDouble() / n
    }

    @Test
    fun effortTenuPeuDeFaussesAlertes() {
        val taux = tauxProposition({ _, _ -> -400.0 }, graine = 11L)
        assertTrue("fausses propositions : $taux", taux <= 0.20)
    }

    @Test
    fun effortTropFaibleRepere() {
        val taux = tauxProposition({ _, _ -> -250.0 }, graine = 12L)
        assertTrue("propositions : $taux", taux >= 0.80)
    }

    @Test
    fun appetitQuiCompenseRepere() {
        // +100 kcal/j d'apports par kg perdu (Polidori 2016), sans résistance.
        val taux = tauxProposition({ _, ecart -> -400.0 - 100.0 * ecart }, graine = 13L)
        assertTrue("propositions : $taux", taux >= 0.90)
    }
}
