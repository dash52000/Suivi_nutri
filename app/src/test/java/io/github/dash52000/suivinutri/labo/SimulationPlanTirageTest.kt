package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.repas.Observation
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Simulation 8 (lot v1.18 B, fiche SPEC section 10, bloc 4) : l'allocation de Neyman vaut-elle sa complexité face à
 * un tirage uniforme, à budget de phrases égal ? Elle fixe les π définitifs.
 *
 * Deux plans, mêmes mondes (mêmes graines ; les tirages de l'aléa divergent dès qu'une phrase existe ou non) :
 *  - NEYMAN : π de départ de la fiche, puis recalcul de Neyman un dimanche toutes les 4 semaines (premier recalcul 4
 *    semaines après le premier tirage), écarts-types observés par strate, budget 10 phrases par semaine ;
 *  - UNIFORME : même π pour toutes les strates sauf le soir chargé (toujours 1, règle de la fiche, commune aux deux),
 *    calé AVANT la comparaison sur 40 mondes pilotes (graines à part) pour que les phrases réalisées par semaine soient
 *    celles du plan de Neyman (bissection).
 * Monde : phrases qui surestiment de 15 %, refus au hasard 10 %. 150 mondes par plan, 24 semaines ; l'erreur d'une
 * fenêtre de 4 semaines = apport journalier moyen estimé − vrai (`ApportsCalcules.moyenne`), pour chaque fenêtre qui
 * finit à 4, 8, … semaines ; elle est jugée à 12 et 24 semaines (fenêtres déjà observées à cet horizon).
 *
 * Critères écrits le 26/11 AVANT la première exécution, jamais ajustés pour passer :
 *  8a. budget comparable : phrases réalisées par semaine (sur 24 semaines) du plan uniforme à moins de 5 % de celles de
 *      Neyman ;
 *  8b. gain : à 24 semaines, racine de l'erreur quadratique moyenne de Neyman ≤ 0,90 fois celle du plan uniforme (au
 *      moins 10 % de réduction de l'écart-type de l'erreur) ; la largeur moyenne de l'IC est affichée, sans critère ;
 *  8c. pas de perte de justesse : couverture de la moyenne sur 4 semaines du plan de Neyman d'au moins 92 % à 12 et à
 *      24 semaines.
 * Si 8b échoue, la technique ne vaut pas sa complexité : arrêt et rapport (le plan uniforme devient le plan par défaut).
 *
 * RÉSULTAT (première exécution, 26/11) : 8a et 8c passent ; **8b échoue** (rapport 1,014 à 24 semaines, 1,024 à 12 ;
 * largeurs d'IC identiques à 1 kcal près). Damien a validé la conséquence prévue : **plan uniforme par défaut**, pas de
 * recalcul de Neyman. Le critère 8b n'a pas été modifié : il est conservé tel qu'écrit et son échec est documenté ;
 * le test vérifie maintenant 8a, 8c et la décision (Neyman ne gagne pas 10 %), pour qu'un changement futur du
 * générateur qui ferait gagner Neyman soit vu. Non vérifié : si le recalcul déplace beaucoup les π de départ.
 *
 * SUITE (26/11, 15 h) : le plan uniforme (π 0,433, ≈ 9,2 phrases par semaine) a été essayé comme plan par défaut de
 * toutes les simulations (rejouées) : il fait échouer 11d (25 % de faux changements d'habitude du matin, le matin étant
 * tiré à 0,43 au lieu de 0,15), 9c (couverture du jour isolé 91,8 % à 12 semaines) et 3c (moyenne de l'écart
 * ailleurs / maison à −0,030). Les π de départ différenciés, sans recalcul, ont validé les simulations 1 à 11 : ils
 * restent le plan par défaut ; **seul le recalcul de Neyman est abandonné** (il ne gagne rien). Le plan uniforme reste
 * disponible dans `PlanTirage(uniforme = …)` pour la comparaison.
 */
class SimulationPlanTirageTest {

    class Resultat(
        val phrasesParSemaine: Double,
        /** Une erreur par (monde, fenêtre) : estimé − vrai, et si l'IC couvre ; largeur de l'IC. */
        val erreurs: Map<Int, List<Double>>,
        val couvertures: Map<Int, List<Boolean>>,
        val largeurs: Map<Int, List<Double>>,
    ) {
        fun rmse(h: Int) = sqrt(erreurs.getValue(h).sumOf { it * it } / erreurs.getValue(h).size)
        fun couverture(h: Int) = couvertures.getValue(h).count { it }.toDouble() / couvertures.getValue(h).size
        fun largeur(h: Int) = largeurs.getValue(h).average()
    }

    companion object {
        const val MONDES = 150
        const val SEMAINES = 24
        private val monde = Monde(biaisGlobal = ln(0.85))

        private fun phrases(donnees: DonneesSimulees) =
            donnees.repas.count { it.observation == Observation.DECRIT }.toDouble() / SEMAINES

        /** π uniforme tel que les phrases réalisées par semaine égalent [cible] (bissection sur 40 mondes pilotes). */
        private fun calerUniforme(cible: Double): Double {
            var bas = 0.05
            var haut = 1.0
            repeat(12) {
                val milieu = (bas + haut) / 2
                val moyenne = (0 until 40).map { k ->
                    phrases(GenerateurRepas.generer(Random(9000L + k), monde, SEMAINES, 9000L * 1000 + k, PlanTirage(uniforme = milieu)))
                }.average()
                if (moyenne < cible) bas = milieu else haut = milieu
            }
            return (bas + haut) / 2
        }

        private fun executer(plan: PlanTirage, graine: Long): Resultat {
            val erreurs = HashMap<Int, ArrayList<Double>>()
            val couvertures = HashMap<Int, ArrayList<Boolean>>()
            val largeurs = HashMap<Int, ArrayList<Double>>()
            val phr = ArrayList<Double>()
            for (k in 0 until MONDES) {
                val d = GenerateurRepas.generer(Random(graine + k), monde, SEMAINES, graine * 1000 + k, plan)
                phr += phrases(d)
                for (h in listOf(12, 24)) {
                    val fin = d.depart.plusDays(7L * h)
                    val res = BiaisRepas.estimer(d.repas.filter { it.jour < fin }, Labo.observationsBalance(d.jours.take(GenerateurRepas.AVANT + 7 * h + 1), fin), fin)!!
                    val ap = ApportsRepas.calculer(res)
                    for (s in 4..h step 4) {
                        val dernier = d.depart.plusDays(7L * s - 1)
                        val vrai = (0 until 28).map { d.apportsVrais.getValue(dernier.minusDays(it.toLong())) }.average()
                        val ic = ap.moyenne(dernier, 28)!!
                        erreurs.getOrPut(h) { ArrayList() } += ic.estimation - vrai
                        couvertures.getOrPut(h) { ArrayList() } += vrai in ic.bas..ic.haut
                        largeurs.getOrPut(h) { ArrayList() } += ic.haut - ic.bas
                    }
                }
            }
            return Resultat(phr.average(), erreurs, couvertures, largeurs)
        }

        val neyman: Resultat by lazy { executer(PlanTirage(neyman = true), 1100L) }
        val piUniforme: Double by lazy { calerUniforme(neyman.phrasesParSemaine) }
        val uniforme: Resultat by lazy { executer(PlanTirage(uniforme = piUniforme), 1100L) }

        private fun f(x: Double, d: Int = 3) = "%.${d}f".format(x)
        private fun pct(x: Double) = "%.1f %%".format(100 * x)
    }

    @Test
    fun simulation8_neymanContreUniformeABudgetEgal() {
        println("Sim 8 : π uniforme calé = ${f(piUniforme)} ; phrases par semaine : Neyman ${f(neyman.phrasesParSemaine, 2)}, uniforme ${f(uniforme.phrasesParSemaine, 2)}")
        for (h in listOf(12, 24)) {
            println(
                "Sim 8, $h semaines : erreur quadratique moyenne de la moyenne 4 sem : Neyman ${f(neyman.rmse(h), 1)}, uniforme ${f(uniforme.rmse(h), 1)} kcal/j " +
                    "(rapport ${f(neyman.rmse(h) / uniforme.rmse(h))}) · largeur IC : Neyman ${f(neyman.largeur(h), 1)}, uniforme ${f(uniforme.largeur(h), 1)} · " +
                    "couverture : Neyman ${pct(neyman.couverture(h))}, uniforme ${pct(uniforme.couverture(h))} (n=${neyman.erreurs.getValue(h).size})",
            )
        }
        val ecartBudget = abs(uniforme.phrasesParSemaine - neyman.phrasesParSemaine) / neyman.phrasesParSemaine
        assertTrue("8a : budget : Neyman ${neyman.phrasesParSemaine}, uniforme ${uniforme.phrasesParSemaine}", ecartBudget <= 0.05)
        val rapport = neyman.rmse(24) / uniforme.rmse(24)
        // 8b est échoué et documenté (voir le KDoc) : on enregistre la décision « uniforme par défaut ».
        assertTrue("8b (décision uniforme par défaut) : Neyman gagnerait maintenant ${"%.0f".format(100 * (1 - rapport))} % : à réexaminer", rapport > 0.90)
        for (h in listOf(12, 24)) assertTrue("8c : couverture de Neyman à $h semaines : ${neyman.couverture(h)}", neyman.couverture(h) >= 0.92)
    }
}
