package io.github.dash52000.suivinutri.labo

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random
import kotlin.math.abs

/**
 * Garde-fou (v1.13) : les apports du Labo sont simulés de bout en bout avec le vrai code. Chaque jour : apports
 * = 2 300 kcal ± 400, dépense = 2 700 + ε × ΔPoids ± 300 (activité, mesurée par la montre), le poids du matin
 * suit le bilan de la veille divisé par ρ (Hall), avec l'eau (0,73 × écart de 0,5 kg) et le bruit de la balance
 * (0,42 kg). Vérité d'une semaine = moyenne des apports de ses jours.
 * Résultat du 11/11/2026 (40 tirages × 18 semaines) : avec 7 700 kcal/kg, apports surestimés de 43 kcal/j et IC 95 %
 * qui couvre 88 % ; avec ρ = 8 840, biais nul et couverture 95 % (demi-largeur 290 kcal/j).
 */
class SimulationApportsTest {

    data class Bilan(val biais: Double, val couverture: Double, val demiLargeur: Double, val n: Int)

    private val depart = LocalDate.of(2026, 3, 1) // un dimanche

    private fun simuler(alea: Random, semaines: Int = 20): Pair<List<JourLabo>, Map<LocalDate, Double>> {
        val avant = 28
        val n = avant + 7 * semaines + 1
        var niveau = 80.0
        val apports = mutableMapOf<LocalDate, Double>()
        val jours = (0 until n).map { k ->
            val date = depart.plusDays((k - avant).toLong())
            val eau = 0.5 * alea.nextGaussian()
            val poids = niveau + 0.73 * eau + 0.42 * alea.nextGaussian()
            val ecart = niveau - 80.0
            val depense = 2700.0 + Hall.EPS * ecart + 300.0 * alea.nextGaussian()
            val mange = (if (k >= avant) 2300.0 else 2700.0) + 400.0 * alea.nextGaussian()
            apports[date] = mange
            niveau += (mange - depense) / Hall.RHO
            JourLabo(date = date, poids = poids, kcal = depense, sommeilMin = 420, eauKg = 45.0 + eau, heurePesee = 7)
        }
        return jours to apports
    }

    fun bilan(graine: Long, tirages: Int = 40): Bilan {
        val alea = Random(graine)
        val erreurs = mutableListOf<Double>()
        var dedans = 0
        var largeur = 0.0
        repeat(tirages) {
            val (jours, apports) = simuler(alea)
            val fin = jours.last().date
            val r = Labo.analyser(jours, fin)
            r.semaines.filter { !it.enCours && it.debut.isAfter(depart.plusWeeks(1)) }.forEach { s ->
                val verite = (0 until s.nbJours).map { apports.getValue(s.debut.plusDays(it.toLong())) }.average()
                erreurs += s.apportsEstimes - verite
                if (verite in s.apports.bas..s.apports.haut) dedans++
                largeur += (s.apports.haut - s.apports.bas) / 2
            }
        }
        return Bilan(erreurs.average(), dedans.toDouble() / erreurs.size, largeur / erreurs.size, erreurs.size)
    }

    @Test
    fun apportsSansBiais() {
        val b = bilan(31L)
        assertTrue("biais : ${b.biais}", abs(b.biais) < 25.0)
    }

    @Test
    fun intervallesCouvrent() {
        val b = bilan(32L)
        assertTrue("couverture : ${b.couverture}", b.couverture in 0.88..0.99)
    }
}
