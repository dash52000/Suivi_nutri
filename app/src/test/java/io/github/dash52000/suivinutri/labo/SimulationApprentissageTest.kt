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
 *    l'IC 95 % de β contient 0 dans au moins 90 % des tirages ; moyenne de u à moins de 80 kcal/j de zéro ; à chaque
 *    horizon, l'IC de l'écart global contient le vrai écart dans au moins 90 % des tirages (ajout du 26/11).
 *  - Simulation 2 (les phrases surestiment de 15 % : vrai = 0,85 × ĉ). Critères d'origine sur β (moyenne à 0,04 de
 *    ln 0,85) abandonnés le 26/11 : la balance ne voit que le total et ne sépare pas un biais proportionnel des
 *    phrases (β) d'un apport non dit (u) ; avec des a priori très larges, l'écart-type de β tiré des seules données
 *    vaut environ 0,65 à 24 semaines, et le −15 % se partage entre β et u au prorata des a priori. Décision de
 *    Damien (option A) : l'appli annonce l'écart global en kcal/j (`ResultatBiais.ecartGlobal`), seule part
 *    identifiable. Nouveaux critères, fixés après une exploration (30 à 40 mondes) qui donnait −430 kcal/j estimés
 *    pour −447 vrais, et pas resserrés sur elle : à 12 et 24 semaines, moyenne de l'écart estimé à moins de 10 % du
 *    vrai écart moyen ; à chaque horizon, l'IC 95 % de l'écart contient le vrai écart dans au moins 90 % des tirages
 *    (la borne haute de 99 % d'origine est retirée le 26/11, à la demande de Damien : un IC prudent n'est pas un
 *    défaut) ; la part des IC qui excluent zéro ne baisse pas avec l'horizon. Le délai (« en combien de semaines »)
 *    est mesuré et affiché, pas promis.
 *    Vrai écart d'un monde = moyenne des apports vrais de la fenêtre − moyenne de ce que décrivent les repas sans
 *    correction (phrases et moyennes de contexte, f_t(0)).
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
        /** Écart global estimé (kcal/j) et son IC, vrai écart du monde. */
        val ecart: Intervalle,
        val veriteEcart: Double,
        /** Une case par fenêtre de 4 semaines : l'intervalle de la moyenne couvre-t-il la vérité ? */
        val couvertureMoyenne: List<Boolean>,
        val largeurMoyenne: List<Double>,
        /** Une case par jour depuis le départ. */
        val couvertureJour: List<Boolean>,
        val largeurJour: List<Double>,
    ) {
        val icContientVerite get() = abs(beta - veriteBeta) <= 1.96 * sdBeta
        val icContientZero get() = abs(beta) <= 1.96 * sdBeta
        val ecartContientVerite get() = veriteEcart in ecart.bas..ecart.haut
        val ecartExclutZero get() = ecart.bas > 0.0 || ecart.haut < 0.0
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
                    val res = BiaisRepas.estimer(repas, Labo.observationsBalance(jours, fin), fin)!!
                    val moyenne = ArrayList<Boolean>()
                    val largeurMoyenne = ArrayList<Double>()
                    val jour = ArrayList<Boolean>()
                    val largeurJour = ArrayList<Double>()
                    if (h >= 12) {
                        val ap = ApportsRepas.calculer(res)
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
                    val m = res.modele
                    val brut = m.etat(DoubleArray(m.nParam).also { it[m.iL0] = res.theta[m.iL0] })
                    val veriteEcart = m.jours.map { donnees.apportsVrais.getValue(it.date) }.average() - brut.f.average()
                    essais.getValue(h) += Essai(h, res.beta, res.sdBeta, res.u, res.sdU, monde.biaisGlobal, res.ecartGlobal(), veriteEcart, moyenne, largeurMoyenne, jour, largeurJour)
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
                    "IC contient la vérité ${pct(e.count { it.icContientVerite }.toDouble() / e.size)} · exclut zéro ${pct(e.count { !it.icContientZero }.toDouble() / e.size)} · " +
                    "écart ${f(e.map { it.ecart.estimation }.average(), 0)} kcal/j (vrai ${f(e.map { it.veriteEcart }.average(), 0)}, ± ${f(e.map { (it.ecart.haut - it.ecart.bas) / 2 }.average(), 0)}), " +
                    "IC contient le vrai ${pct(e.count { it.ecartContientVerite }.toDouble() / e.size)}, exclut zéro ${pct(e.count { it.ecartExclutZero }.toDouble() / e.size)}" +
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
        for (h in HORIZONS) {
            val e = sansBiais.getValue(h)
            assertTrue("IC de l'écart contient le vrai à $h semaines : ${e.count { it.ecartContientVerite }}/${e.size}", e.count { it.ecartContientVerite } >= 0.90 * e.size)
        }
    }

    @Test
    fun simulation2_biaisGlobalDeMoins15PourCentRetrouveDansLEcartGlobal() {
        resume("Sim 2 (phrases −15 %)", biais15)
        for (h in listOf(12, 24)) {
            val e = biais15.getValue(h)
            val estime = e.map { it.ecart.estimation }.average()
            val vrai = e.map { it.veriteEcart }.average()
            assertTrue("écart moyen à $h semaines : $estime (vrai $vrai)", abs(estime - vrai) <= 0.10 * abs(vrai))
        }
        for (h in HORIZONS) {
            val e = biais15.getValue(h)
            val part = e.count { it.ecartContientVerite }.toDouble() / e.size
            assertTrue("IC de l'écart contient le vrai à $h semaines : $part", part >= 0.90)
        }
        val puissance = HORIZONS.map { h -> biais15.getValue(h).count { it.ecartExclutZero }.toDouble() / TIRAGES }
        println("Sim 2 : part des IC de l'écart qui excluent zéro à ${HORIZONS.joinToString(", ")} semaines : ${puissance.joinToString(", ") { pct(it) }}")
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
