package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.repas.Confiance
import io.github.dash52000.suivinutri.repas.HabitudeMatin
import io.github.dash52000.suivinutri.repas.Marges
import io.github.dash52000.suivinutri.repas.Moment
import io.github.dash52000.suivinutri.repas.Origine
import io.github.dash52000.suivinutri.repas.PhraseMatin
import io.github.dash52000.suivinutri.repas.PortionSource
import io.github.dash52000.suivinutri.repas.RepasObserve
import io.github.dash52000.suivinutri.repas.TapsSoir
import io.github.dash52000.suivinutri.repas.Tirage
import java.time.LocalDate
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/*
 * Générateur des simulations d'apprentissage (SimulationApprentissageTest, étape 3 du lot v1.18 B ; archi section 6).
 * Commun aux simulations 1 à 11 : il produit de vrais repas par contexte avec un vrai biais ; le vrai `Tirage` choisit
 * les moments décrits ; les vraies `Marges` bruitent ĉ ; le vrai `HabitudeMatin` apprend le petit-déj ; les pesées
 * viennent de la boucle de Hall de `SimulationApportsTest`. Ni LLM, ni CIQUAL, ni Android.
 *
 * Convention : kcal vrais = ĉ × exp(b), donc ĉ = vrai × exp(−b) × N, où N est un bruit log-normal de moyenne 1 et de
 * log-écart-type σ (la marge déclarée de la phrase : un monde où les marges sont bien calibrées).
 */

/** Un monde à simuler. Les clés de [biaisContexte] sont celles du modèle : « soir:beauxparents », « midi:tupp »… */
data class Monde(
    /** b global : −0,1625 = les phrases surestiment de 15 % (vrai = 0,85 × ĉ). */
    val biaisGlobal: Double = 0.0,
    val biaisContexte: Map<String, Double> = emptyMap(),
    /** Grignotage jamais déclaré, kcal/j en moyenne (jamais décrit, jamais « Rien » : la balance le voit seule). */
    val grignotageCache: Double = 0.0,
    /** Part des moments tirés qu'il ne remplit pas, au hasard (refus sans lien avec le repas). */
    val refus: Double = 0.1,
)

class DonneesSimulees(
    val depart: LocalDate,
    val jours: List<JourLabo>,
    val repas: List<RepasObserve>,
    /** Apports vrais de chaque jour depuis le départ (repas + grignotage caché). */
    val apportsVrais: Map<LocalDate, Double>,
)

object GenerateurRepas {

    const val AVANT = 28

    /** Un dimanche, comme `SimulationApportsTest`. */
    val DEPART: LocalDate = LocalDate.of(2026, 3, 1)

    private fun lognormal(alea: Random, moyenne: Double, cv: Double): Double {
        val s = sqrt(ln(1.0 + cv * cv))
        return moyenne * exp(s * alea.nextGaussian() - s * s / 2)
    }

    private fun choisir(alea: Random, poids: List<Pair<String, Double>>): String {
        var u = alea.nextDouble() * poids.sumOf { it.second }
        for ((nom, p) in poids) { u -= p; if (u < 0) return nom }
        return poids.last().first
    }

    private class Soir(val contexte: String, val charge: Boolean, val vrai: Double)

    /**
     * [semaines] semaines après le départ (dimanche → samedi), précédées de 28 jours de base à 2 700 kcal/j sans repas.
     * Jours : 28 + 7 × semaines + 1 (la pesée du dimanche qui clôt la dernière semaine).
     */
    fun generer(alea: Random, monde: Monde, semaines: Int, graineTirage: Long): DonneesSimulees {
        val tirage = Tirage(graineTirage)
        val nJours = AVANT + 7 * semaines + 1
        var niveau = 80.0
        val jours = ArrayList<JourLabo>()
        val repas = ArrayList<RepasObserve>()
        val vrais = HashMap<LocalDate, Double>()
        val phrasesMatin = ArrayList<PhraseMatin>()
        var partBeauxParents = 0.5

        for (k in 0 until nJours) {
            val date = DEPART.plusDays((k - AVANT).toLong())
            val eau = 0.5 * alea.nextGaussian()
            val poids = niveau + 0.73 * eau + 0.42 * alea.nextGaussian()
            val depense = 2700.0 + Hall.EPS * (niveau - 80.0) + 300.0 * alea.nextGaussian()
            val mange: Double

            if (k < AVANT) {
                mange = 2700.0 + 400.0 * alea.nextGaussian()
            } else {
                // Une semaine avec ou sans beaux-parents : c'est ce qui sépare les contextes (archi section 6).
                if (date.dayOfWeek == java.time.DayOfWeek.SUNDAY) partBeauxParents = 0.1 + 0.8 * alea.nextDouble()
                val vraiMatin = lognormal(alea, 450.0, 0.1)
                val ctxMidi = choisir(alea, listOf("tupp" to 0.6, "prepare" to 0.25, "achete" to 0.15))
                val vraiMidi = lognormal(alea, when (ctxMidi) { "tupp" -> 700.0; "prepare" -> 850.0; else -> 950.0 }, 0.2)
                val ctxSoir = choisir(alea, listOf("beauxparents" to partBeauxParents, "maison" to (1 - partBeauxParents) * 0.85, "ailleurs" to (1 - partBeauxParents) * 0.15))
                val charge = alea.nextDouble() < when (ctxSoir) { "beauxparents" -> 0.55; "maison" -> 0.35; else -> 0.5 }
                val base = when (ctxSoir) { "beauxparents" -> 1050.0; "maison" -> 800.0; else -> 950.0 }
                val soir = Soir(ctxSoir, charge, lognormal(alea, base + if (charge) 250.0 else 0.0, 0.2))
                val vraiGrignotage = if (alea.nextDouble() < 0.5) lognormal(alea, 250.0, 0.5) else 0.0
                val cache = if (monde.grignotageCache > 0) lognormal(alea, monde.grignotageCache, 0.4) else 0.0
                mange = vraiMatin + vraiMidi + soir.vrai + vraiGrignotage + cache
                vrais[date] = mange

                val habitude = HabitudeMatin.apprendre(phrasesMatin, date)
                val nHabitude = habitude?.nPhrases ?: 0
                fun biais(moment: Moment, ctx: String) = monde.biaisGlobal + (monde.biaisContexte["${moment.code}:$ctx"] ?: 0.0)

                fun phrase(vrai: Double, b: Double): Pair<Double, Double> {
                    val portion = when {
                        alea.nextDouble() < 0.35 -> PortionSource.EXPLICITE
                        alea.nextDouble() < 0.7 -> PortionSource.STANDARD
                        else -> if (alea.nextBoolean()) PortionSource.GROSSE else PortionSource.PETITE
                    }
                    val confiance = when { alea.nextDouble() < 0.55 -> Confiance.HAUTE; alea.nextDouble() < 0.67 -> Confiance.MOYENNE; else -> Confiance.BASSE }
                    val sigma = Marges.sigma(portion, confiance, Origine.TIREE)
                    return vrai * exp(-b) * exp(sigma * alea.nextGaussian() - sigma * sigma / 2) to sigma
                }

                // Matin
                run {
                    val d = tirage.decider(date, Moment.MATIN, nHabitude)
                    if (!d.demande) repas += RepasObserve.nonDecrit(date, Moment.MATIN, "matin", d.pi)
                    else if (alea.nextDouble() < monde.refus) repas += RepasObserve.nonDecrit(date, Moment.MATIN, "matin", d.pi)
                    else if (habitude != null && alea.nextDouble() < 0.8) repas += RepasObserve.commeDhab(date, Moment.MATIN, "matin", habitude.kcal, habitude.sigma, d.pi)
                    else {
                        val (c, s) = phrase(vraiMatin, biais(Moment.MATIN, "matin"))
                        repas += RepasObserve.decrit(date, Moment.MATIN, "matin", c, s, Origine.TIREE, d.pi)
                        phrasesMatin += PhraseMatin(date, c, s, d.pi, Origine.TIREE)
                    }
                }
                // Midi
                run {
                    val d = tirage.decider(date, Moment.MIDI, nHabitude)
                    if (!d.demande || alea.nextDouble() < monde.refus) repas += RepasObserve.nonDecrit(date, Moment.MIDI, ctxMidi, d.pi)
                    else {
                        val (c, s) = phrase(vraiMidi, biais(Moment.MIDI, ctxMidi))
                        repas += RepasObserve.decrit(date, Moment.MIDI, ctxMidi, c, s, Origine.TIREE, d.pi)
                    }
                }
                // Soir : les taps (resservi) sont connus avant le tirage ; chargé = π 1.
                run {
                    val d = tirage.decider(date, Moment.SOIR, nHabitude, TapsSoir(rab = charge))
                    if (!d.demande || alea.nextDouble() < monde.refus) repas += RepasObserve.nonDecrit(date, Moment.SOIR, soir.contexte, d.pi)
                    else {
                        val (c, s) = phrase(soir.vrai, biais(Moment.SOIR, soir.contexte))
                        repas += RepasObserve.decrit(date, Moment.SOIR, soir.contexte, c, s, if (charge) Origine.CHARGEE else Origine.TIREE, d.pi)
                    }
                }
                // Grignotage : « Rien » quand rien n'a été mangé ; le grignotage caché n'est jamais dit.
                run {
                    val d = tirage.decider(date, Moment.GRIGNOTAGE, nHabitude)
                    if (!d.demande || alea.nextDouble() < monde.refus) repas += RepasObserve.nonDecrit(date, Moment.GRIGNOTAGE, "grignotage", d.pi)
                    else if (vraiGrignotage == 0.0) repas += RepasObserve.rien(date, Moment.GRIGNOTAGE, "grignotage", d.pi)
                    else {
                        val (c, s) = phrase(vraiGrignotage, biais(Moment.GRIGNOTAGE, "grignotage"))
                        repas += RepasObserve.decrit(date, Moment.GRIGNOTAGE, "grignotage", c, s, Origine.TIREE, d.pi)
                    }
                }
            }
            niveau += (mange - depense) / Hall.RHO
            jours += JourLabo(date = date, poids = poids, kcal = depense, sommeilMin = 420, eauKg = 45.0 + eau, heurePesee = 7)
        }
        return DonneesSimulees(DEPART, jours, repas, vrais)
    }
}
