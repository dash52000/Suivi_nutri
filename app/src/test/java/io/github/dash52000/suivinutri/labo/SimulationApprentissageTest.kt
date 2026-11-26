package io.github.dash52000.suivinutri.labo

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.ln

/**
 * Garde-fou 1 (lot v1.18 B, fiche SPEC section 10) : le modèle de biais par contexte et l'apport journalier sont
 * simulés de bout en bout avec le vrai code : `GenerateurRepas` (vrais repas par contexte et vrai biais), vrai
 * `Tirage`, vraies `Marges`, vrai `HabitudeMatin`, pesées de la boucle de Hall (comme `SimulationApportsTest`), puis
 * vrais `Labo.analyser`, `BiaisRepas` et `ApportsRepas`. Ni LLM, ni CIQUAL, ni Android.
 *
 * 60 tirages (mondes), 6, 12 et 24 semaines, bruit actuel (0,42 kg ; 0,05 kg/j). Bloc 1 de la fiche : on ne passe
 * aux blocs 2 à 4 que si celui-ci tient.
 *
 * Critères fixés AVANT la première exécution (jamais ajustés pour passer ; ce qui a servi à régler les a priori est
 * dit dans `suivi-nutri.md`) :
 *  - Simulation 1 (aucun biais) : à 12 et 24 semaines, moyenne des β estimés à moins de 0,03 de zéro ; à 24 semaines,
 *    l'IC 95 % de β contient 0 dans au moins 90 % des tirages ; moyenne de u à moins de 80 kcal/j de zéro.
 *  - Simulation 2 (les phrases surestiment de 15 % : vrai = 0,85 × ĉ, β = ln 0,85 = −0,1625) : à 12 et 24 semaines,
 *    moyenne des β estimés à moins de 0,04 de la vérité ; l'IC 95 % de β contient la vérité dans 90 à 99 % des tirages
 *    à chaque horizon ; la part des tirages dont l'IC exclut zéro ne baisse pas avec l'horizon. Le délai (« en combien
 *    de semaines ») est mesuré et affiché, pas promis : la fiche dit que ce sont des hypothèses, pas des résultats.
 *  - Simulation 7 (couverture) : les intervalles à 95 % de l'apport (moyenne sur 4 semaines, jour isolé) couvrent la
 *    vraie valeur dans 92 à 98 % des cas (critère provisoire de la fiche, section 9.7), sur les ajustements à 12 et
 *    24 semaines, dans le monde sans biais et dans le monde à −15 %.
 */
class SimulationApprentissageTest {

    class Essai(
        val horizon: Int,
        val beta: Double,
        val sdBeta: Double,
        val u: Double,
        val sdU: Double,
        val veriteBeta: Double,
        /** Une case par fenêtre de 4 semaines : l'intervalle de la moyenne couvre-t-il la vérité ? */
        val couvertureMoyenne: List<Boolean>,
        val largeurMoyenne: List<Double>,
        /** Une case par jour depuis le départ. */
        val couvertureJour: List<Boolean>,
        val largeurJour: List<Double>,
    ) {
        val icContientVerite get() = abs(beta - veriteBeta) <= 1.96 * sdBeta
        val icContientZero get() = abs(beta) <= 1.96 * sdBeta
    }

    companion object {
        const val TIRAGES = 60
        val HORIZONS = listOf(6, 12, 24)

        private fun simuler(monde: Monde, graine: Long): Map<Int, List<Essai>> {
            val essais = HORIZONS.associateWith { ArrayList<Essai>() }
            for (k in 0 until TIRAGES) {
                val donnees = GenerateurRepas.generer(Random(graine + k), monde, HORIZONS.max(), graine * 1000 + k)
                for (h in HORIZONS) {
                    val fin = donnees.depart.plusDays(7L * h)
                    val jours = donnees.jours.take(GenerateurRepas.AVANT + 7 * h + 1)
                    val repas = donnees.repas.filter { it.jour < fin }
                    val labo = Labo.analyser(jours, fin)
                    val res = BiaisRepas.estimer(repas, labo.semaines, fin)!!
                    val moyenne = ArrayList<Boolean>()
                    val largeurMoyenne = ArrayList<Double>()
                    val jour = ArrayList<Boolean>()
                    val largeurJour = ArrayList<Double>()
                    if (h >= 12) {
                        val ap = ApportsRepas.calculer(res, repas)
                        for (s in 4..h step 4) {
                            val dernier = donnees.depart.plusDays(7L * s - 1)
                            val verite = (0 until 28).map { donnees.apportsVrais.getValue(dernier.minusDays(it.toLong())) }.average()
                            val ic = ap.moyenne(dernier, 28)!!
                            moyenne += verite in ic.bas..ic.haut
                            largeurMoyenne += ic.haut - ic.bas
                        }
                        for (j in 0 until 7 * h) {
                            val d = donnees.depart.plusDays(j.toLong())
                            val a = ap.jour(d)!!
                            jour += donnees.apportsVrais.getValue(d) in a.bas..a.haut
                            largeurJour += a.haut - a.bas
                        }
                    }
                    essais.getValue(h) += Essai(h, res.beta, res.sdBeta, res.u, res.sdU, monde.biaisGlobal, moyenne, largeurMoyenne, jour, largeurJour)
                }
            }
            return essais
        }

        /** Un monde est simulé une seule fois pour tous les tests de la classe. */
        val sansBiais: Map<Int, List<Essai>> by lazy { simuler(Monde(biaisGlobal = 0.0), 100L) }
        val biais15: Map<Int, List<Essai>> by lazy { simuler(Monde(biaisGlobal = ln(0.85)), 200L) }

        private fun pct(x: Double) = "%.0f %%".format(100 * x)
        private fun f(x: Double, d: Int = 3) = "%.${d}f".format(x)
    }

    private fun resume(nom: String, m: Map<Int, List<Essai>>) {
        for (h in HORIZONS) {
            val e = m.getValue(h)
            val moy = e.flatMap { it.couvertureMoyenne }
            val jour = e.flatMap { it.couvertureJour }
            println(
                "$nom, $h semaines : β ${f(e.map { it.beta }.average())} (écart-type entre mondes ${f(sd(e.map { it.beta }))}, " +
                    "annoncé ${f(e.map { it.sdBeta }.average())}) · u ${f(e.map { it.u }.average(), 0)} ± ${f(e.map { it.sdU }.average(), 0)} · " +
                    "IC contient la vérité ${pct(e.count { it.icContientVerite }.toDouble() / e.size)} · exclut zéro ${pct(e.count { !it.icContientZero }.toDouble() / e.size)}" +
                    if (moy.isEmpty()) "" else " · couverture moyenne 4 sem ${pct(moy.count { it }.toDouble() / moy.size)} (largeur ${f(e.flatMap { it.largeurMoyenne }.average(), 0)}, n=${moy.size}) · " +
                        "jour ${pct(jour.count { it }.toDouble() / jour.size)} (largeur ${f(e.flatMap { it.largeurJour }.average(), 0)}, n=${jour.size})",
            )
        }
    }

    private fun sd(x: List<Double>): Double {
        val m = x.average()
        return kotlin.math.sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1))
    }

    @Test
    fun simulation1_sansBiaisLeBiaisEstimeEstCentreSurZero() {
        resume("Sim 1 (aucun biais)", sansBiais)
        for (h in listOf(12, 24)) {
            val e = sansBiais.getValue(h)
            assertTrue("β moyen à $h semaines : ${e.map { it.beta }.average()}", abs(e.map { it.beta }.average()) < 0.03)
        }
        val e24 = sansBiais.getValue(24)
        assertTrue("IC contient 0 : ${e24.count { it.icContientZero }}/${e24.size}", e24.count { it.icContientZero } >= 0.90 * e24.size)
        assertTrue("u moyen : ${e24.map { it.u }.average()}", abs(e24.map { it.u }.average()) < 80.0)
    }

    @Test
    fun simulation2_biaisGlobalDeMoins15PourCentRetrouve() {
        resume("Sim 2 (phrases −15 %)", biais15)
        val verite = ln(0.85)
        for (h in listOf(12, 24)) {
            val e = biais15.getValue(h)
            assertTrue("β moyen à $h semaines : ${e.map { it.beta }.average()} (vrai $verite)", abs(e.map { it.beta }.average() - verite) <= 0.04)
        }
        for (h in HORIZONS) {
            val e = biais15.getValue(h)
            val part = e.count { it.icContientVerite }.toDouble() / e.size
            assertTrue("IC contient la vérité à $h semaines : $part", part in 0.90..0.99)
        }
        val puissance = HORIZONS.map { h -> biais15.getValue(h).count { !it.icContientZero }.toDouble() / TIRAGES }
        assertTrue("part des IC qui excluent zéro, par horizon : $puissance", puissance[0] <= puissance[1] && puissance[1] <= puissance[2])
    }

    @Test
    fun simulation7_couvertureDesIntervallesDeLApport() {
        resume("Sim 7 (aucun biais)", sansBiais)
        resume("Sim 7 (phrases −15 %)", biais15)
        for ((nom, monde) in listOf("aucun biais" to sansBiais, "phrases −15 %" to biais15)) {
            for (h in listOf(12, 24)) {
                val e = monde.getValue(h)
                val moyenne = e.flatMap { it.couvertureMoyenne }.let { l -> l.count { it }.toDouble() / l.size }
                val jour = e.flatMap { it.couvertureJour }.let { l -> l.count { it }.toDouble() / l.size }
                assertTrue("$nom, $h semaines : couverture de la moyenne sur 4 semaines $moyenne", moyenne in 0.92..0.98)
                assertTrue("$nom, $h semaines : couverture du jour isolé $jour", jour in 0.92..0.98)
            }
        }
    }
}
