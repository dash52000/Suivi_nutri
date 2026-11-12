package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.Boisson
import io.github.dash52000.suivinutri.data.EntreeJour
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Garde-fou (v1.15) : la cascade simulée de bout en bout avec le vrai code (Kalman, Hall, effort déclaré, montre).
 *
 * Chaque mois tire de nouvelles habitudes (rab, dessert, alcool, pas, séances). Vrais apports = coûts de table ×
 * [Scenario.kVrai] + un changement non déclaré ; vraie dépense = 2 700 + ε × écart de poids + activité. La montre
 * voit 2 650 + [Scenario.epsMontre] × écart de poids + [Scenario.biaisActivite] × activité ± 150 kcal par jour.
 *
 * Étude préalable (12/11/2026, même simulateur, cascade par fenêtres de 4 semaines) : un facteur personnel sur les
 * coûts de table ne s'apprend pas proprement. A priori N(1 ; 0,3²) : écart-type a posteriori 0,23 à 3 mois, 0,19 à 6,
 * 0,15 à 12 ; vrai facteur 1,4 estimé à 1,24 au bout d'un an ; un changement non déclaré qui accompagne le déclaré
 * (corrélation 0,5) le pousse à 1,30 alors que les coûts sont justes. Abandonné.
 *
 * Résultats (12/11/2026, 30 tirages, 12 semaines de montre et 12 jours de questionnaire avant le départ, cas réel) :
 * - total mesuré depuis le départ : ±65 kcal/j à 3 mois, ±40 à 6, ±22 à 12 (couverture de l'IC 63 à 80 % : trop
 *   étroit, recalage du 26/11) ;
 * - marges des barres (référence courte, elles ne diminuent pas) : rab ±82, dessert ±63, alcool ±22, activité ±80 ;
 * - non expliqué : biais +8 à +15 kcal/j, marge ±145 à ±150 dès 3 mois, couverture 80 à 100 %, « lisible » à tort
 *   0 à 13 % ; des portions réduites de 150 kcal/j non déclarées : « lisible » 47 % à 3 et 6 mois, 50 % à 12 ;
 * - montre sans le poids : activité −33 kcal/j à 12 mois ; montre qui surestime l'activité de 20 % : −28.
 * Corrigé pendant l'étude : moyennes par jour de semaine (biais +36 sans), marge des barres = référence seulement.
 */
class SimulationCascadeTest {

    class Scenario(
        val kVrai: Double = 1.0,
        val sdNonDeclare: Double = 0.0,
        /** Changement non déclaré constant dès le départ (portions), kcal/j. */
        val nonDeclareFixe: Double = 0.0,
        val epsMontre: Double = 10.0,
        val biaisActivite: Double = 1.0,
    )

    class Mois(val rab: Double, val dessert: Double, val unitesSemaine: Double, val pasEnPlus: Double, val seancesSemaine: Double, val nonDeclare: Double)

    /** Erreurs de la cascade « depuis le départ » vue à la fin d'un mois. */
    class Point(
        val mois: Int,
        val erreurMesure: Double,
        val couvreMesure: Boolean,
        val erreurActivite: Double,
        val nonExplique: Double,
        val vraiNonExplique: Double,
        val lisible: Boolean,
        val demiMarge: Double,
        val couvreNe: Boolean,
        val demiMargeNe: Double,
    )

    private val depart = LocalDate.of(2026, 3, 1)
    private val margesVues = mutableMapOf<Int, List<Pair<String, Double>>>()

    fun simuler(alea: Random, mois: Int, s: Scenario): List<Point> {
        val base = Mois(3.0 / 7, 0.55, 10.0, 0.0, 3.0, 0.0)
        val plan = listOf(base) + (1..mois).map {
            Mois(
                rab = 0.05 + 0.4 * alea.nextDouble(),
                dessert = 0.1 + 0.5 * alea.nextDouble(),
                unitesSemaine = 2.0 + 10.0 * alea.nextDouble(),
                pasEnPlus = 3000.0 * alea.nextDouble(),
                seancesSemaine = 2.0 + 3.0 * alea.nextDouble(),
                nonDeclare = s.nonDeclareFixe + s.sdNonDeclare * alea.nextGaussian(),
            )
        }
        // 12 semaines avant le départ (la montre a de l'historique), questionnaire seulement les 12 derniers jours,
        // comme dans le cas réel (questionnaire depuis le 27/10, départ le 8/11).
        val avant = 84
        val jours = avant + 28 * mois
        val ee0 = 2700.0
        val coutBase = s.kVrai * (300 * base.rab + 250 * base.dessert + 105 * base.unitesSemaine / 7)
        val activiteBase = base.seancesSemaine / 7 * 600
        val i0 = ee0 + activiteBase - coutBase
        var niveau = 80.0
        var eau = 0.0
        val donnees = mutableListOf<JourLabo>()
        val apportsEnPlus = DoubleArray(jours)
        val activiteEnPlus = DoubleArray(jours)
        val nonDeclareJour = DoubleArray(jours)
        var niveauDepart = 80.0
        for (k in 0 until jours) {
            val date = depart.plusDays((k - avant).toLong())
            if (k == avant) niveauDepart = niveau
            val m = if (k < avant) base else plan[1 + (k - avant) / 28]
            val rab = alea.nextDouble() < m.rab
            val dessert = alea.nextDouble() < m.dessert
            val weekEnd = date.dayOfWeek == DayOfWeek.FRIDAY || date.dayOfWeek == DayOfWeek.SATURDAY
            val nbVerres = if (weekEnd) {
                val parSoir = m.unitesSemaine / 2 / 1.848
                parSoir.toInt() + if (alea.nextDouble() < parSoir % 1) 1 else 0
            } else 0
            val boissons = List(nbVerres) { Boisson("dubbel", 33.0, 7.0) }
            val entree = EntreeJour(
                date,
                mapOf(
                    "resservi" to if (rab) "oui" else "non",
                    "dessert" to if (dessert) "normale" else "non",
                    "alcool" to if (boissons.isEmpty()) "aucun" else "oui",
                ),
                boissons,
            )
            val pas = (6000 + m.pasEnPlus + 1500 * alea.nextGaussian()).coerceAtLeast(500.0)
            val seance = alea.nextDouble() < m.seancesSemaine / 7
            val activite = 0.045 * (pas - 6000) + (if (seance) 600.0 else 0.0)
            val tableJour = 300.0 * (if (rab) 1 else 0) + 250.0 * (if (dessert) 1 else 0) + entree.unitesAlcool * 105
            val apports = i0 + s.kVrai * tableJour + m.nonDeclare + 300 * alea.nextGaussian()
            val depense = ee0 + Hall.EPS * (niveau - 80.0) + activite
            val montre = 2650 + s.epsMontre * (niveau - 80.0) + s.biaisActivite * activite + 150 * alea.nextGaussian()
            apportsEnPlus[k] = apports - i0 - coutBase
            activiteEnPlus[k] = activite - activiteBase
            // Ce que la table ne voit pas : le non-déclaré, et l'écart des vrais coûts à la table.
            nonDeclareJour[k] = m.nonDeclare + (s.kVrai - 1) * tableJour - (s.kVrai - 1) * coutBase / s.kVrai

            eau = 0.5 * eau + 0.5 * sqrt(0.75) * alea.nextGaussian()
            val poids = niveau + 0.73 * eau + 0.42 * alea.nextGaussian()
            niveau += (apports - depense) / Hall.RHO
            donnees += JourLabo(
                date = date, poids = poids, kcal = montre, sommeilMin = 420, entree = if (k >= avant - 12) entree else null, heurePesee = 7,
                eauKg = 45.0 + eau, pasTotal = pas.toLong() + 1000, pasHorsSport = pas.toLong(),
            )
        }
        val parDate = donnees.associateBy { it.date }
        return (1..mois).mapNotNull { mi ->
            val fin = depart.plusDays(28L * mi)
            val connus = donnees.filter { !it.date.isAfter(fin) }
            val lis = Labo.lissage(connus, fin) ?: return@mapNotNull null
            val c = CalculCascade.calculer(parDate.filterKeys { !it.isAfter(fin) }, lis, depart, depart, fin) ?: return@mapNotNull null
            val idx = avant until avant + 28 * mi
            // Effort de Hall vrai : apports en plus − activité en plus − ε × (niveau de départ − 80).
            val correction = Hall.EPS * (niveauDepart - 80.0)
            val vrai = idx.map { apportsEnPlus[it] - activiteEnPlus[it] }.average() - correction
            val vraiActivite = -idx.map { activiteEnPlus[it] }.average()
            if (mi in listOf(3, 6)) margesVues[mi] = c.barres.map { it.libelle to it.marge }
            val activite = c.barres.firstOrNull { it.libelle == CalculCascade.ACTIVITE }?.kcalJour ?: return@mapNotNull null
            Point(
                mi,
                c.mesure.estimation - vrai,
                vrai in c.mesure.bas..c.mesure.haut,
                activite - vraiActivite,
                c.nonExplique.estimation,
                idx.map { nonDeclareJour[it] }.average() - correction,
                c.lisible,
                (c.mesure.haut - c.mesure.bas) / 2,
                (idx.map { nonDeclareJour[it] }.average() - correction) in c.nonExplique.bas..c.nonExplique.haut,
                (c.nonExplique.haut - c.nonExplique.bas) / 2,
            )
        }
    }

    fun points(graine: Long, s: Scenario, tirages: Int = 30, mois: Int = 12): List<Point> {
        val alea = Random(graine)
        return (1..tirages).flatMap { simuler(alea, mois, s) }
    }

    private fun sd(x: List<Double>) = sqrt(x.sumOf { it * it } / x.size)

    /** Coûts justes, rien de non déclaré, montre juste : la cascade retombe sur ses pieds, peu de fausses alertes. */
    @Test
    fun cascadeJuste() {
        val p = points(1300L, Scenario(), tirages = 12, mois = 6).filter { it.mois >= 3 }
        val erreurNe = p.map { it.nonExplique - it.vraiNonExplique }.average()
        assertTrue("non expliqué : $erreurNe", abs(erreurNe) < 30)
        val activite = p.map { it.erreurActivite }.average()
        assertTrue("activité : $activite", abs(activite) < 25)
        val alertes = p.count { it.lisible }.toDouble() / p.size
        assertTrue("lisible à tort : $alertes", alertes <= 0.2)
    }

    /** Des portions réduites de 150 kcal/j sans le déclarer ressortent au moins une fois sur cinq à 6 mois. */
    @Test
    fun portionsNonDeclarees() {
        val p = points(1301L, Scenario(nonDeclareFixe = -150.0), tirages = 15, mois = 6).filter { it.mois == 6 }
        val vus = p.count { it.lisible && it.nonExplique < 0 }.toDouble() / p.size
        assertTrue("vus : $vus", vus >= 0.2)
    }

    @Test
    fun diagnosticMarges() {
        if (System.getenv("SIMUL_CASCADE") == null) return
        val marges = mutableMapOf<String, MutableList<Double>>()
        val alea = Random(1200)
        repeat(20) {
            margesVues.clear()
            simuler(alea, 6, Scenario())
            margesVues.forEach { (m, l) -> l.forEach { (lib, v) -> marges.getOrPut("$m:$lib") { mutableListOf() } += v } }
        }
        marges.toSortedMap().forEach { (k, v) -> println("Marge %s : ±%.0f kcal/j".format(k, v.average())) }
    }

    @Test
    fun diagnostic() {
        if (System.getenv("SIMUL_CASCADE") == null) return
        val scenarios = listOf(
            "coûts justes, montre juste (ε montre 10)" to Scenario(),
            "montre sans le poids (ε montre 0)" to Scenario(epsMontre = 0.0),
            "montre très sensible au poids (15)" to Scenario(epsMontre = 15.0),
            "montre qui surestime l'activité de 20 %" to Scenario(biaisActivite = 1.2),
            "coûts réels × 1,4" to Scenario(kVrai = 1.4),
            "non déclaré ± 150 kcal/j par mois" to Scenario(sdNonDeclare = 150.0),
            "portions −150 kcal/j non déclarées" to Scenario(nonDeclareFixe = -150.0),
        )
        scenarios.forEachIndexed { i, (nom, s) ->
            val p = points(1100L + i, s)
            println("— $nom")
            listOf(1, 3, 6, 12).forEach { m ->
                val l = p.filter { it.mois == m }
                val erreurNe = l.map { it.nonExplique - it.vraiNonExplique }
                val ea = l.map { it.erreurActivite }
                println(
                    "   mois %2d : mesure ±%.0f (couverture %.2f) · activité biais %+.0f, écart-type %.0f · non expliqué : erreur %+.0f ± %.0f, vrai moyen %+.0f, lisible %.2f, marge ±%.0f, couverture %.2f"
                        .format(
                            m, 1.96 * sd(l.map { it.erreurMesure }), l.count { it.couvreMesure }.toDouble() / l.size,
                            ea.average(), sd(ea.map { it - ea.average() }),
                            erreurNe.average(), sd(erreurNe.map { it - erreurNe.average() }), l.map { it.vraiNonExplique }.average(),
                            l.count { it.lisible }.toDouble() / l.size, l.map { it.demiMargeNe }.average(),
                            l.count { it.couvreNe }.toDouble() / l.size,
                        )
                )
            }
        }
    }
}
