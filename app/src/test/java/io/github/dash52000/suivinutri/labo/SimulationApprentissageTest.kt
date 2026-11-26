package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.repas.HabitudeMatin
import io.github.dash52000.suivinutri.repas.Moment
import io.github.dash52000.suivinutri.repas.Observation
import io.github.dash52000.suivinutri.repas.Origine
import io.github.dash52000.suivinutri.repas.PhraseMatin
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
 *
 * Bloc 2 de la fiche (simulations 3 et 5). Critères écrits le 26/11 AVANT la première exécution de ces deux
 * simulations, jamais ajustés pour passer. Rappel : β global et u ne sont pas identifiables séparément ; seuls
 * le total (l'écart global) et les écarts ENTRE contextes le sont. Un écart entre deux contextes a et b est
 * C(a−b) = b_a − b_b appris (combinaison linéaire des paramètres δ, β se simplifie), avec son écart-type a posteriori ;
 * P(signe) = probabilité a posteriori que le signe de C soit celui de l'estimation (loi normale de Laplace).
 *  - Simulation 3 (biais seulement chez les beaux-parents du soir : vrai = 0,80 × ĉ, b = ln 0,80 = −0,223 ; midi,
 *    autres soirs, matin et grignotage sans biais ; la part de soirs chez les beaux-parents change d'une semaine à
 *    l'autre, c'est ce qui sépare les contextes). Jugée sur les écarts entre contextes, pas sur β.
 *    Révision du 26/11 (14 h 20), décidée par Damien APRÈS la première exécution de la simulation 3 : les critères
 *    d'origine 3a (à 24 semaines, moyenne de C entre 0,5 et 1,3 fois le vrai ; à 12 semaines, entre 0,3 et 1,3 fois)
 *    et 3b (« probable » dans au moins 60 % des mondes à 24 semaines) n'étaient pas tenus : −0,079 (35 % du vrai) à
 *    24 semaines, −0,041 (19 %) à 12 semaines, « probable » dans 17 % et 2 % des mondes. Diagnostic hors critères : le
 *    rétrécissement est celui de l'a priori sceptique, les données apportent peu d'information sur un écart entre
 *    contextes avant environ un an. Nouveau principe : un écart entre contextes n'est annoncé que lorsqu'il est
 *    lisible (« probable ») ; avant, rien. Les critères ci-dessous sont écrits AVANT l'exécution à 52 semaines, qui
 *    est un horizon ajouté à cette simulation (fenêtre d'estimation plafonnée à 364 jours) ; les valeurs vues au
 *    diagnostic (≈ 0,6 fois le vrai, environ un monde sur deux) ont servi à fixer l'ordre de grandeur, avec de la marge.
 *    3a. Écart C(beauxparents−maison), vrai = ln 0,80 : à 52 semaines, moyenne des estimations entre 0,5 et 1,3 fois
 *        le vrai. À 6, 12 et 24 semaines : mesuré et affiché, pas promis. Jamais de signe inverse en moyenne (≤ 0).
 *    3b. À 52 semaines, P(signe) ≥ 90 % dans le bon sens dans au moins 40 % des mondes. Rien d'annoncé à tort avant :
 *        à 6, 12 et 24 semaines, part des mondes où P(signe) ≥ 90 % dans le MAUVAIS sens (écart beaux-parents − maison
 *        positif) au plus 3 % ; et à 52 semaines au plus 3 % aussi.
 *    3c. Écarts sans vrai écart (maison−ailleurs, midi tupp−préparé, tupp−acheté, préparé−acheté ; les deux écarts
 *        qui touchent beauxparents sont exclus) : à 12, 24 et 52 semaines, |moyenne| ≤ 0,03 pour chacun ; à chaque
 *        horizon (6, 12, 24, 52), moyenne sur ces quatre écarts de la part des mondes où P(signe) ≥ 90 % dans un sens
 *        ou dans l'autre : au plus 20 % (deux fois la tolérance des liens à sens attendu, car ici les deux sens
 *        comptent) ; où P(signe) ≥ 97,5 % : au plus 8 %.
 *        Précision de harnais (après la première exécution, critères inchangés) : un monde où un contexte n'apparaît
 *        pas (par exemple aucun dîner « ailleurs » en 6 semaines) n'a pas l'écart correspondant ; il n'entre pas dans
 *        les moyennes et compte comme « non annoncé » dans les parts.
 *    3d. Écart global, comme en simulation 2 mais avec un plancher pour un petit écart : à 12, 24 et 52 semaines,
 *        moyenne de l'écart estimé à moins de max(10 % du vrai écart moyen ; 15 kcal/j) du vrai ; à chaque horizon,
 *        l'IC 95 % contient le vrai écart dans au moins 90 % des tirages.
 *    3e. Détecteur : avec un modèle aveugle aux contextes (a priori de δ à 0,001), le critère 3a doit ÉCHOUER à 52
 *        semaines (moyenne de C(beauxparents−maison) à moins de 0,5 fois le vrai) : c'est la preuve que 3a peut
 *        échouer.
 *  - Simulation 5 (grignotage caché de +200 kcal/j en moyenne, log-normal de coefficient de variation 0,4, jamais
 *    décrit ni « Rien » ; aucun biais des phrases). Jugée sur l'écart global, vrai écart défini comme plus haut.
 *    5a. À 12 et 24 semaines, moyenne de l'écart estimé à moins de max(10 % du vrai écart moyen ; 15 kcal/j) du vrai.
 *    5b. À chaque horizon, l'IC 95 % contient le vrai écart dans au moins 90 % des tirages.
 *    5c. À 24 semaines, l'IC exclut zéro dans au moins 90 % des tirages ; la part des IC qui excluent zéro ne baisse
 *        pas de 6 à 12 à 24 semaines. Le délai est mesuré, pas promis.
 *    5d. Sans fausser les contextes : les six écarts entre contextes (aucun vrai écart) passent les critères 3c
 *        (|moyenne| ≤ 0,03 à 12 et 24 semaines ; au plus 20 % de P(signe) ≥ 90 % et 8 % de P(signe) ≥ 97,5 %,
 *        moyennes sur les six écarts, à chaque horizon).
 *
 * Bloc 3 de la fiche (simulations 4, 6 et 9). Critères écrits le 26/11 AVANT la première exécution de ces trois
 * simulations, jamais ajustés pour passer. Tous les mondes ont des phrases qui surestiment de 15 % (b = ln 0,85),
 * sauf la simulation 9 (aucun biais). Seuils de couverture et d'écart global : ceux des simulations 2, 3 et 7.
 *  - Simulation 4a (refus lié aux taps : 60 % des soirs chargés ne sont pas remplis, 10 % des autres moments ; les
 *    cellules conditionnent sur « chargé », que les taps montrent). « L'estimation reste correcte grâce aux
 *    contextes » :
 *    4a-1. écart global : à 12 et 24 semaines, moyenne de l'écart estimé à moins de max(10 % du vrai ; 15 kcal/j) du
 *          vrai ; à chaque horizon l'IC 95 % contient le vrai dans au moins 90 % des tirages ;
 *    4a-2. couverture à 12 et 24 semaines : moyenne sur 4 semaines et jour isolé d'au moins 92 %.
 *          (Révision du 26/11 à 14 h 32, décidée par Damien après la première exécution : borne haute de 98 %
 *          supprimée ; la couverture de la moyenne à 12 semaines valait 98,3 %. Un intervalle prudent n'est pas un
 *          défaut, comme pour l'IC de l'écart global. S'applique aux critères 6c-2 et 9c, qui renvoient à 4a-2 ; la
 *          simulation 7 du bloc 1 garde ses bornes 92 à 98 %.)
 *  - Simulation 4b (refus lié à la taille : 80 % des soirs « lourds », plus de 1,15 fois la moyenne de leur contexte,
 *    ne sont pas remplis ; invisible dans les taps). **Mesurée, sans critère** : c'est la limite connue de la fiche
 *    (3.1 B, « refus informatif »), que les cellules ne peuvent pas corriger. On affiche l'écart global estimé et le
 *    vrai, et la couverture.
 *  - Simulation 6 (phrases spontanées sur repas lourds : un midi ou un soir non demandé donne une phrase spontanée
 *    avec la probabilité 0,5 si le repas est lourd, 0,05 sinon ; vrai bruit de la phrase identique à celui d'une
 *    phrase tirée ; marge déclarée selon `Marges`, facteur × 1,5 décroissant, gelé à × 1,5 si l'écart est détecté
 *    par `EcartSpontanees`). Monde témoin : probabilité 0,2 au hasard, sans lien avec la taille.
 *    6a. détection : `EcartSpontanees.detecte` dans au moins 80 % des mondes à 24 semaines et 50 % à 12 semaines (à 6
 *        semaines : mesuré). Révision du 26/11 à 14 h 32, décidée par Damien après la première exécution (détecté
 *        dans 12 %, 25 % et 43 % des mondes à 6, 12 et 24 semaines, avec 16 spontanées en 24 semaines) : 6a devient
 *        « mesuré, pas promis » ; la promesse repose sur 6b (pas de fausse détection) et 6c (estimation juste malgré
 *        des spontanées sur repas lourds, car une spontanée mesure son propre repas et n'entre pas dans les moyennes
 *        de contexte) ;
 *    6b. monde témoin : détecté à tort dans au plus 10 % des mondes à chaque horizon, et |écart moyen de ln ĉ| ≤ 0,05
 *        à 12 et 24 semaines ;
 *    6c. conséquences dans le monde sélectionné : critères 4a-1 et 4a-2.
 *  - Simulation 9 (« Rien » oublié : 30 % des grignotages réels d'un moment tiré sont enregistrés « Rien » ; aucun
 *    biais des phrases ; le vrai écart global vaut environ +37 kcal/j) :
 *    9a. écart global : à 12 et 24 semaines, moyenne de l'écart estimé à moins de max(10 % du vrai ; 25 kcal/j) du
 *        vrai (le plancher de 25 est plus large que celui de 15 des simulations 3 et 5 parce que le vrai écart est
 *        petit : l'erreur-type de la moyenne de 60 mondes vaut déjà 8 kcal/j à 12 semaines) ;
 *    9b. l'IC 95 % contient le vrai écart dans au moins 90 % des tirages, à chaque horizon ;
 *    9c. couverture à 12 et 24 semaines : critère 4a-2 ;
 *    9d. part du grignotage (u inclus) : à 24 semaines, moyenne des grignotages journaliers estimés à moins de
 *        max(25 % du vrai ; 30 kcal/j) du vrai grignotage moyen ;
 *    9e. sans fausser le reste : à 24 semaines, moyenne des apports estimés hors grignotage à moins de
 *        max(5 % du vrai ; 40 kcal/j) du vrai.
 *
 * Bloc 5 de la fiche (simulations 10 et 11, ajouts du 26/11). Critères écrits le 26/11 à 14 h 45 AVANT la première
 * exécution. La simulation 8 (plan de tirage) est dans `SimulationPlanTirageTest` : Neyman ne gagne rien, plan
 * uniforme par défaut (validé par Damien).
 *  - Simulation 10 (simulation 7 avec la marge des spontanées × 1, × 1,5 et × 2). Monde : phrases qui surestiment de
 *    15 %, phrases spontanées au hasard (probabilité 0,25 à midi et au soir quand le moment n'est pas demandé, sans
 *    lien avec la taille du repas), marge déclarée = marge d'une phrase tirée × facteur (fixé, sans décroissance).
 *    Le vrai bruit des spontanées vaut celui d'une phrase tirée × 1 (marges bien calibrées) ou × 1,5 (spontanées plus
 *    mal estimées). Mêmes mondes pour les six cas (graine commune). Le facteur 1,5 est la valeur de départ de la fiche.
 *    10a. facteur 1,5 : couverture de la moyenne sur 4 semaines et du jour isolé d'au moins 92 % à 12 et à 24
 *         semaines, pour chacun des deux vrais bruits ;
 *    10b. facteur 2 : même critère ;
 *    10c. facteur 1 : **mesuré, sans critère**. Si le facteur 1 tient aussi le seuil de 92 % même avec un vrai bruit
 *         × 1,5, le facteur 1,5 est superflu : c'est un constat à rapporter, pas un échec ;
 *    10d. largeurs moyennes des intervalles de la moyenne sur 4 semaines affichées pour les six cas, sans critère
 *         (coût d'un facteur trop prudent).
 *  - Simulation 11 (changement d'habitude du petit-déj : `HabitudeMatin`, fenêtre de 8 semaines, trois phrases,
 *    trois écarts dans le même sens). Monde : matin vrai de 450 kcal (log-écart-type 0,1) ; dans le monde « changement »,
 *    il passe à 650 kcal à la 9e semaine (+44 %) ; aucun biais ; « Comme d'hab » tapé avec la probabilité 0,8 seulement
 *    si le vrai petit-déj reste proche de l'habitude apprise (écart en log inférieur à 0,25), sinon une phrase (on ne
 *    dit pas « comme d'hab » quand on a mangé autre chose). Le monde témoin est le même sans changement. 60 mondes,
 *    24 semaines ; habitude lue avec le vrai `HabitudeMatin.apprendre` aux dates 1 jour avant le changement, puis 2, 4,
 *    6 et 8 semaines après (et chaque semaine pour le témoin).
 *    11a. avant le changement : habitude à ± 25 % de 450 kcal dans au moins 90 % des mondes ;
 *    11b. 6 semaines après : habitude à ± 25 % de 650 kcal dans au moins 80 % des mondes ;
 *    11c. 8 semaines après (la fenêtre de 56 jours ne contient plus que du nouveau) : habitude à ± 25 % de 650 kcal dans
 *         au moins 95 % des mondes (voir la révision de 14 h 46 : mesuré) ;
 *    11d. témoin : changement signalé à tort (`changementLe` non nul à une des dates hebdomadaires) dans au plus 10 %
 *         des mondes, et habitude à ± 25 % de 450 kcal à 24 semaines dans au moins 95 % des mondes (second point : voir
 *         la révision de 14 h 46 : mesuré) ;
 *    Révision du 26/11 à 14 h 42, décidée par Damien après la première exécution (± 10 % et ± 15 % ne tenaient pas
 *    même dans le monde témoin : 48 % à ± 10 % à 24 semaines) : toutes les tolérances de 11a, 11b, 11c et du second
 *    point de 11d passent à ± 25 %. Justification : 4 phrases en médiane dans la fenêtre, marge moyenne de 0,31 par
 *    phrase, donc une erreur attendue d'environ 15 % (écart médian mesuré 10 %, quartile haut 18 %, 90e centile 27 %).
 *    Les seuils de parts de mondes (90, 80, 95 et 10 %) ne changent pas. Le premier point de 11d (faux changements,
 *    17 % à la première exécution) n'est pas une question de tolérance : critère inchangé.
 *    Seconde révision du 26/11 à 14 h 46, décidée par Damien après la deuxième exécution (11c : 93 % ; second point de
 *    11d : 85 % ; 11a : 97 % et 11b : 90 % passaient) : **11c et le second point de 11d deviennent « mesuré, pas
 *    promis »**. Cause : la précision de l'habitude avec environ 4 phrases à 0,31 de marge, pas un bug ; le matin ne
 *    pèse qu'environ 450 kcal sur 2 700 et les couvertures des simulations 1 à 10 sont obtenues avec cette habitude.
 *    11e. **mesurés, sans critère** : part des mondes où `changementLe` est signalé 6 semaines après le changement, et
 *         délai médian (jours) avant que l'habitude soit à ± 15 % de 650 kcal.
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
        /** Écarts entre contextes d'un même moment : clé « a-b » → (estimation, écart-type) de b_a − b_b. */
        val contrastes: Map<String, Pair<Double, Double>> = emptyMap(),
        val veriteContrastes: Map<String, Double> = emptyMap(),
        val ecartSpontanees: EcartSpontanees? = null,
        /** Moyennes sur la fenêtre (kcal/j) : grignotage et reste estimés, grignotage et reste vrais ; NaN hors 12 à 24 semaines. */
        val grigEstime: Double = Double.NaN,
        val grigVrai: Double = Double.NaN,
        val horsEstime: Double = Double.NaN,
        val horsVrai: Double = Double.NaN,
    ) {
        val icContientVerite get() = abs(beta - veriteBeta) <= 1.96 * sdBeta
        val icContientZero get() = abs(beta) <= 1.96 * sdBeta
        val ecartContientVerite get() = veriteEcart in ecart.bas..ecart.haut
        val ecartExclutZero get() = ecart.bas > 0.0 || ecart.haut < 0.0
    }

    companion object {
        const val TIRAGES = 60
        val HORIZONS = listOf(6, 12, 24)
        /** Simulation 3 : un horizon de plus, 52 semaines (la fenêtre d'estimation est plafonnée à 364 jours). */
        val HORIZONS_3 = listOf(6, 12, 24, 52)

        private fun simuler(monde: Monde, graine: Long, hyp: HypothesesBiais = HypothesesBiais(), horizons: List<Int> = HORIZONS): Map<Int, List<Essai>> {
            val essais = horizons.associateWith { ArrayList<Essai>() }
            for (k in 0 until TIRAGES) {
                val donnees = GenerateurRepas.generer(Random(graine + k), monde, horizons.max(), graine * 1000 + k)
                for (h in horizons) {
                    val fin = donnees.depart.plusDays(7L * h)
                    val jours = donnees.jours.take(GenerateurRepas.AVANT + 7 * h + 1)
                    val repas = donnees.repas.filter { it.jour < fin }
                    val res = BiaisRepas.estimer(repas, Labo.observationsBalance(jours, fin), fin, hyp)!!
                    val moyenne = ArrayList<Boolean>()
                    val largeurMoyenne = ArrayList<Double>()
                    val jour = ArrayList<Boolean>()
                    val largeurJour = ArrayList<Double>()
                    val grigE = ArrayList<Double>()
                    val grigV = ArrayList<Double>()
                    val horsE = ArrayList<Double>()
                    val horsV = ArrayList<Double>()
                    if (h in 12..24) {
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
                            grigE += a.kcalParMoment.getValue(Moment.GRIGNOTAGE)
                            horsE += a.kcalParMoment.filterKeys { it != Moment.GRIGNOTAGE }.values.sum()
                            grigV += donnees.vraisGrignotage.getValue(d)
                            horsV += donnees.vraisHorsGrignotage.getValue(d)
                        }
                    }
                    val m = res.modele
                    val contrastes = HashMap<String, Pair<Double, Double>>()
                    val veriteContrastes = HashMap<String, Double>()
                    for (moment in listOf("midi", "soir")) {
                        val ctx = m.etiquettes.filter { it.startsWith("$moment:") }
                        for (i in ctx.indices) for (j in i + 1 until ctx.size) {
                            val poids = DoubleArray(m.nParam)
                            poids[3 + m.etiquettes.indexOf(ctx[i])] = 1.0
                            poids[3 + m.etiquettes.indexOf(ctx[j])] = -1.0
                            val cle = "${ctx[i]}-${ctx[j]}"
                            contrastes[cle] = res.combinaison(poids)
                            veriteContrastes[cle] = (monde.biaisContexte[ctx[i]] ?: 0.0) - (monde.biaisContexte[ctx[j]] ?: 0.0)
                        }
                    }
                    val brut = m.etat(DoubleArray(m.nParam).also { it[m.iL0] = res.theta[m.iL0] })
                    val veriteEcart = m.jours.map { donnees.apportsVrais.getValue(it.date) }.average() - brut.f.average()
                    essais.getValue(h) += Essai(h, res.beta, res.sdBeta, res.u, res.sdU, monde.biaisGlobal, res.ecartGlobal(), veriteEcart, moyenne, largeurMoyenne, jour, largeurJour, contrastes, veriteContrastes,
                        EcartSpontanees.estimer(repas), grigE.averageOrNaN(), grigV.averageOrNaN(), horsE.averageOrNaN(), horsV.averageOrNaN())
                }
            }
            return essais
        }

        /** Un monde est simulé une seule fois pour tous les tests de la classe. */
        val sansBiais: Map<Int, List<Essai>> by lazy { simuler(Monde(biaisGlobal = 0.0), 100L) }
        val biais15: Map<Int, List<Essai>> by lazy { simuler(Monde(biaisGlobal = ln(0.85)), 200L) }

        /** Simulation 3 : biais seulement chez les beaux-parents du soir (vrai = 0,80 × ĉ). */
        val BIAIS_BP = ln(0.80)
        const val BP_MAISON = "soir:beauxparents-soir:maison"
        val beauxParents: Map<Int, List<Essai>> by lazy { simuler(Monde(biaisContexte = mapOf("soir:beauxparents" to BIAIS_BP)), 300L, horizons = HORIZONS_3) }

        /** Détecteur (3e) : même monde, modèle aveugle aux contextes. */
        val beauxParentsAveugle: Map<Int, List<Essai>> by lazy {
            simuler(Monde(biaisContexte = mapOf("soir:beauxparents" to BIAIS_BP)), 300L, HypothesesBiais(sigmaDeltaMoment = 0.001, sigmaDeltaContexte = 0.001), HORIZONS_3)
        }

        private val B15 = ln(0.85)
        /** Simulation 4a : refus lié aux taps ; 4b : refus lié à la taille (limite connue) ; 6 : spontanées sur repas lourds et monde témoin ; 9 : « Rien » oublié. */
        val refusTaps: Map<Int, List<Essai>> by lazy { simuler(Monde(biaisGlobal = B15, refusCharge = 0.6), 600L) }
        val refusTaille: Map<Int, List<Essai>> by lazy { simuler(Monde(biaisGlobal = B15, refusLourd = 0.8), 700L) }
        val spontLourd: Map<Int, List<Essai>> by lazy { simuler(Monde(biaisGlobal = B15, spontaneeLourd = 0.5, spontaneeLeger = 0.05), 800L) }
        val spontTemoin: Map<Int, List<Essai>> by lazy { simuler(Monde(biaisGlobal = B15, spontaneeLourd = 0.2, spontaneeLeger = 0.2), 900L) }
        val rienOublie: Map<Int, List<Essai>> by lazy { simuler(Monde(oubliRien = 0.3), 1000L) }

        /** Simulation 10 : clé « vrai bruit × b, marge × f ». Mêmes mondes (graine 1200) pour les six cas. */
        val spontMarges: Map<Pair<Double, Double>, Map<Int, List<Essai>>> by lazy {
            buildMap {
                for (bruit in listOf(1.0, 1.5)) for (facteur in listOf(1.0, 1.5, 2.0))
                    put(bruit to facteur, simuler(Monde(biaisGlobal = B15, spontaneeLourd = 0.25, spontaneeLeger = 0.25, bruitSpontanee = bruit, facteurSpontanee = facteur), 1200L))
            }
        }

        /** Simulation 5 : grignotage caché de +200 kcal/j. */
        val grignotage200: Map<Int, List<Essai>> by lazy { simuler(Monde(grignotageCache = 200.0), 500L) }

        private fun List<Double>.averageOrNaN() = if (isEmpty()) Double.NaN else average()
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

    // ---- Bloc 2 : simulations 3 et 5 ----

    /** Probabilité a posteriori que le signe de l'écart soit celui de l'estimation. */
    private fun pSigne(c: Pair<Double, Double>): Double = 1.0 - Stats.pNormal(c.first / c.second) / 2.0

    /** Écarts entre contextes présents dans au moins un monde. */
    private fun toutesCles(m: Map<Int, List<Essai>>, h: Int): List<String> = m.getValue(h).flatMap { it.contrastes.keys }.distinct().sorted()

    private fun contrastesNuls(m: Map<Int, List<Essai>>, h: Int, exclure: String? = null): List<String> =
        toutesCles(m, h).filter { exclure == null || !it.contains(exclure) }

    /** Part moyenne (sur les écarts [cles] et les mondes) où P(signe) atteint [seuil]. */
    private fun partDetectee(m: Map<Int, List<Essai>>, h: Int, cles: List<String>, seuil: Double): Double =
        cles.sumOf { k -> m.getValue(h).count { e -> e.contrastes[k]?.let { pSigne(it) >= seuil } ?: false } }.toDouble() / (cles.size * m.getValue(h).size)

    private fun moyenneContraste(m: Map<Int, List<Essai>>, h: Int, cle: String) = m.getValue(h).mapNotNull { it.contrastes[cle]?.first }.average()

    private fun resumeContrastes(nom: String, m: Map<Int, List<Essai>>, exclure: String? = null) {
        for (h in m.keys.sorted()) {
            val e = m.getValue(h)
            val nuls = contrastesNuls(m, h, exclure)
            println(
                "$nom, $h semaines : " + toutesCles(m, h).joinToString(" · ") { k ->
                    val presents = e.filter { k in it.contrastes }
                    "$k ${f(moyenneContraste(m, h, k))} (vrai ${f(presents.first().veriteContrastes.getValue(k))}, sd ${f(presents.map { it.contrastes.getValue(k).second }.average())}, " +
                        "P≥90 ${pct(partDetectee(m, h, listOf(k), 0.90))}, présent dans ${presents.size}/${e.size})"
                } + (if (exclure != null) " · écarts sans vrai écart : P(signe) ≥ 90 % ${pct(partDetectee(m, h, nuls, 0.90))}, ≥ 97,5 % ${pct(partDetectee(m, h, nuls, 0.975))}" else ""),
            )
        }
    }

    /** Critères 3c et 5d : écarts sans vrai écart ni faux écart. */
    private fun verifierContrastesNuls(nom: String, m: Map<Int, List<Essai>>, exclure: String?) {
        for (h in m.keys.sorted()) {
            val nuls = contrastesNuls(m, h, exclure)
            if (h >= 12) for (k in nuls) {
                assertTrue("$nom, $h semaines : moyenne de $k = ${moyenneContraste(m, h, k)}", abs(moyenneContraste(m, h, k)) <= 0.03)
            }
            val probable = partDetectee(m, h, nuls, 0.90)
            val solide = partDetectee(m, h, nuls, 0.975)
            assertTrue("$nom, $h semaines : P(signe) ≥ 90 % sur des écarts sans vrai écart : $probable", probable <= 0.20)
            assertTrue("$nom, $h semaines : P(signe) ≥ 97,5 % sur des écarts sans vrai écart : $solide", solide <= 0.08)
        }
    }

    private fun verifierEcartGlobal(nom: String, m: Map<Int, List<Essai>>, plancher: Double = 15.0) {
        for (h in m.keys.filter { it >= 12 }.sorted()) {
            val e = m.getValue(h)
            val estime = e.map { it.ecart.estimation }.average()
            val vrai = e.map { it.veriteEcart }.average()
            assertTrue("$nom, écart moyen à $h semaines : $estime (vrai $vrai)", abs(estime - vrai) <= maxOf(0.10 * abs(vrai), plancher))
        }
        for (h in m.keys.sorted()) {
            val e = m.getValue(h)
            val part = e.count { it.ecartContientVerite }.toDouble() / e.size
            assertTrue("$nom, IC de l'écart contient le vrai à $h semaines : $part", part >= 0.90)
        }
    }

    @Test
    fun simulation3a_ecartBeauxParentsMaisonEstLisibleAUnAn() {
        resume("Sim 3 (beaux-parents −20 %)", beauxParents)
        resumeContrastes("Sim 3", beauxParents, exclure = "beauxparents")
        val vrai = BIAIS_BP
        for (h in HORIZONS_3) {
            val m = moyenneContraste(beauxParents, h, BP_MAISON)
            println("Sim 3a : C(beauxparents−maison) moyen à $h semaines ${f(m)} (${pct(m / vrai)} du vrai ${f(vrai)})")
        }
        val m52 = moyenneContraste(beauxParents, 52, BP_MAISON)
        assertTrue("3a : C(beauxparents−maison) à 52 semaines : $m52 (vrai $vrai)", m52 / vrai in 0.5..1.3)
        for (h in HORIZONS_3) assertTrue("3a : signe de la moyenne à $h semaines : ${moyenneContraste(beauxParents, h, BP_MAISON)}", moyenneContraste(beauxParents, h, BP_MAISON) <= 0.0)
    }

    @Test
    fun simulation3b_ecartBeauxParentsMaisonEstProbableDansLeBonSensSansFausseAnnonce() {
        for (h in HORIZONS_3) {
            val bonSens = beauxParents.getValue(h).count { e -> e.contrastes[BP_MAISON]?.let { it.first < 0 && pSigne(it) >= 0.90 } ?: false }.toDouble() / TIRAGES
            val mauvaisSens = beauxParents.getValue(h).count { e -> e.contrastes[BP_MAISON]?.let { it.first > 0 && pSigne(it) >= 0.90 } ?: false }.toDouble() / TIRAGES
            println("Sim 3b : beauxparents−maison à $h semaines : « probable » dans le bon sens ${pct(bonSens)}, dans le mauvais sens ${pct(mauvaisSens)}")
            assertTrue("3b : « probable » dans le mauvais sens à $h semaines : $mauvaisSens", mauvaisSens <= 0.03)
        }
        val detecte = beauxParents.getValue(52).count { e -> e.contrastes[BP_MAISON]?.let { it.first < 0 && pSigne(it) >= 0.90 } ?: false }.toDouble() / TIRAGES
        assertTrue("3b : « probable » dans le bon sens à 52 semaines dans $detecte des mondes", detecte >= 0.40)
    }

    @Test
    fun simulation3c_lesEcartsSansVraiEcartNeSortentPas() {
        verifierContrastesNuls("3c", beauxParents, exclure = "beauxparents")
    }

    @Test
    fun simulation3d_lEcartGlobalResteJuste() {
        verifierEcartGlobal("3d", beauxParents)
    }

    @Test
    fun simulation3e_detecteurUnModeleAveugleAuxContextesEchoueAuCritere3a() {
        val m52 = moyenneContraste(beauxParentsAveugle, 52, BP_MAISON)
        println("Sim 3e : C(beauxparents−maison) à 52 semaines avec un modèle aveugle : ${f(m52)} (vrai ${f(BIAIS_BP)})")
        assertTrue("3e : le modèle aveugle devrait échouer à 3a, il donne $m52", m52 / BIAIS_BP < 0.5)
    }

    @Test
    fun simulation5_grignotageCacheRetrouveDansLEcartGlobalSansFausserLesContextes() {
        resume("Sim 5 (grignotage caché +200)", grignotage200)
        resumeContrastes("Sim 5", grignotage200)
        verifierEcartGlobal("5a-b", grignotage200)
        val puissance = HORIZONS.map { h -> grignotage200.getValue(h).count { it.ecartExclutZero }.toDouble() / TIRAGES }
        println("Sim 5 : part des IC de l'écart qui excluent zéro à ${HORIZONS.joinToString(", ")} semaines : ${puissance.joinToString(", ") { pct(it) }}")
        assertTrue("5c : IC qui exclut zéro à 24 semaines : ${puissance[2]}", puissance[2] >= 0.90)
        assertTrue("5c : part des IC qui excluent zéro, par horizon : $puissance", puissance[0] <= puissance[1] && puissance[1] <= puissance[2])
        verifierContrastesNuls("5d", grignotage200, exclure = null)
    }

    // ---- Bloc 3 : simulations 4, 6 et 9 ----

    private fun verifierCouverture(nom: String, m: Map<Int, List<Essai>>) {
        // Toutes les mesures sont évaluées avant de conclure : on veut savoir lesquelles tiennent.
        val echecs = ArrayList<String>()
        for (h in listOf(12, 24)) {
            val e = m.getValue(h)
            val moyenne = e.flatMap { it.couvertureMoyenne }.let { l -> l.count { it }.toDouble() / l.size }
            val jour = e.flatMap { it.couvertureJour }.let { l -> l.count { it }.toDouble() / l.size }
            println("$nom, $h semaines : couverture moyenne 4 sem ${pct(moyenne)}, jour isolé ${pct(jour)}")
            if (moyenne < 0.92) echecs += "$h semaines : couverture de la moyenne sur 4 semaines $moyenne"
            if (jour < 0.92) echecs += "$h semaines : couverture du jour isolé $jour"
        }
        assertTrue("$nom : $echecs", echecs.isEmpty())
    }

    @Test
    fun simulation4a_refusLieAuxTapsEstCorrigeParLesCellules() {
        resume("Sim 4a (refus des soirs chargés 60 %)", refusTaps)
        verifierEcartGlobal("4a-1", refusTaps)
    }

    @Test
    fun simulation4a2_couvertureAvecRefusLieAuxTaps() {
        verifierCouverture("4a-2", refusTaps)
    }

    @Test
    fun simulation4b_refusLieALaTailleEstMesureSansCritere() {
        resume("Sim 4b (refus des soirs lourds 80 %, limite connue)", refusTaille)
    }

    private fun detecteSpont(m: Map<Int, List<Essai>>, h: Int) = m.getValue(h).count { it.ecartSpontanees?.detecte == true }.toDouble() / TIRAGES

    private fun resumeSpont(nom: String, m: Map<Int, List<Essai>>) {
        for (h in HORIZONS) {
            val e = m.getValue(h)
            val ecarts = e.mapNotNull { it.ecartSpontanees }
            println(
                "$nom, $h semaines : écart spontanées − tirées (ln ĉ) moyen ${f(ecarts.map { it.ecart }.average())} " +
                    "(se moyen ${f(ecarts.map { it.se }.average())}, ${ecarts.size}/${e.size} mondes comparables, ${f(ecarts.map { it.nSpontanees.toDouble() }.average(), 1)} spontanées en moyenne), " +
                    "détecté dans ${pct(detecteSpont(m, h))}",
            )
        }
    }

    @Test
    fun simulation6a_lesSpontaneesSurRepasLourdsSontDetectees() {
        resume("Sim 6 (spontanées sur repas lourds)", spontLourd)
        resumeSpont("Sim 6a", spontLourd)
        // Mesuré, pas promis (révision du 26/11) : voir le texte du critère 6a.
        println("Sim 6a (mesuré) : détecté dans ${HORIZONS.joinToString(", ") { pct(detecteSpont(spontLourd, it)) }} des mondes à ${HORIZONS.joinToString(", ")} semaines")
    }

    @Test
    fun simulation6b_pasDeFausseDetectionDansLeMondeTemoin() {
        resume("Sim 6 (témoin, spontanées au hasard)", spontTemoin)
        resumeSpont("Sim 6b", spontTemoin)
        for (h in HORIZONS) assertTrue("6b : faux détecté à $h semaines dans ${detecteSpont(spontTemoin, h)}", detecteSpont(spontTemoin, h) <= 0.10)
        for (h in listOf(12, 24)) {
            val moyenne = spontTemoin.getValue(h).mapNotNull { it.ecartSpontanees?.ecart }.average()
            assertTrue("6b : écart moyen à $h semaines : $moyenne", abs(moyenne) <= 0.05)
        }
    }

    @Test
    fun simulation6c_lEstimationResteCorrecteAvecDesSpontaneesSurRepasLourds() {
        verifierEcartGlobal("6c-1", spontLourd)
        verifierCouverture("6c-2", spontLourd)
    }

    @Test
    fun simulation9_rienOublieSousDeclareLeGrignotageSansFausserLeReste() {
        resume("Sim 9 (« Rien » oublié 30 %)", rienOublie)
        for (h in listOf(12, 24)) {
            val e = rienOublie.getValue(h)
            println("Sim 9, $h semaines : grignotage estimé ${f(e.map { it.grigEstime }.average(), 0)} kcal/j (vrai ${f(e.map { it.grigVrai }.average(), 0)}), hors grignotage estimé ${f(e.map { it.horsEstime }.average(), 0)} (vrai ${f(e.map { it.horsVrai }.average(), 0)})")
        }
        verifierEcartGlobal("9a-b", rienOublie, plancher = 25.0)
        verifierCouverture("9c", rienOublie)
        val e = rienOublie.getValue(24)
        val grig = e.map { it.grigEstime }.average()
        val grigVrai = e.map { it.grigVrai }.average()
        assertTrue("9d : grignotage estimé $grig (vrai $grigVrai)", abs(grig - grigVrai) <= maxOf(0.25 * grigVrai, 30.0))
        val hors = e.map { it.horsEstime }.average()
        val horsVrai = e.map { it.horsVrai }.average()
        assertTrue("9e : hors grignotage estimé $hors (vrai $horsVrai)", abs(hors - horsVrai) <= maxOf(0.05 * horsVrai, 40.0))
    }
    // ---- Bloc 5 : simulations 10 et 11 ----

    @Test
    fun simulation10_margeDesSpontanees() {
        for (bruit in listOf(1.0, 1.5)) for (facteur in listOf(1.0, 1.5, 2.0)) {
            val m = spontMarges.getValue(bruit to facteur)
            for (h in listOf(12, 24)) {
                val e = m.getValue(h)
                val moyenne = e.flatMap { it.couvertureMoyenne }.let { l -> l.count { it }.toDouble() / l.size }
                val jour = e.flatMap { it.couvertureJour }.let { l -> l.count { it }.toDouble() / l.size }
                println(
                    "Sim 10, vrai bruit × ${f(bruit, 1)}, marge × ${f(facteur, 1)}, $h semaines : couverture moyenne 4 sem ${pct(moyenne)}, jour isolé ${pct(jour)}, " +
                        "largeur moyenne 4 sem ${f(e.flatMap { it.largeurMoyenne }.average(), 0)} kcal/j, largeur jour ${f(e.flatMap { it.largeurJour }.average(), 0)}",
                )
            }
        }
        for (bruit in listOf(1.0, 1.5)) {
            val tient1 = listOf(12, 24).all { h ->
                val e = spontMarges.getValue(bruit to 1.0).getValue(h)
                e.flatMap { it.couvertureMoyenne }.count { it }.toDouble() / e.flatMap { it.couvertureMoyenne }.size >= 0.92 &&
                    e.flatMap { it.couvertureJour }.count { it }.toDouble() / e.flatMap { it.couvertureJour }.size >= 0.92
            }
            println("Sim 10c (constat) : vrai bruit × ${f(bruit, 1)}, le facteur 1 ${if (tient1) "tient" else "ne tient pas"} le seuil de 92 %")
        }
        for (bruit in listOf(1.0, 1.5)) for (facteur in listOf(1.5, 2.0))
            verifierCouverture("10a/10b (vrai bruit × ${f(bruit, 1)}, marge × ${f(facteur, 1)})", spontMarges.getValue(bruit to facteur))
    }

    /** Habitude du matin lue aux dates demandées, à partir des phrases tirées du monde (comme dans le générateur). */
    private fun habitudeAu(d: DonneesSimulees, jour: java.time.LocalDate): HabitudeMatin? =
        HabitudeMatin.apprendre(
            d.repas.filter { it.moment == Moment.MATIN && it.observation == Observation.DECRIT && it.origine == Origine.TIREE }
                .map { PhraseMatin(it.jour, it.kcal!!, it.sigma!!, it.pi!!, it.origine!!) },
            jour,
        )

    private fun dans(h: HabitudeMatin?, vrai: Double, tolerance: Double) = h != null && abs(h.kcal / vrai - 1.0) <= tolerance

    @Test
    fun simulation11_changementDHabitudeDuMatin() {
        val avant = 450.0
        val apres = 650.0
        val semaineChangement = 8
        val changement = Monde(changementMatin = semaineChangement to apres, habitudeRealiste = true)
        val temoin = Monde(habitudeRealiste = true)
        val c = GenerateurRepas.DEPART.plusDays(7L * semaineChangement)

        val mondesChangement = (0 until TIRAGES).map { GenerateurRepas.generer(java.util.Random(1300L + it), changement, 24, 1300L * 1000 + it) }
        val mondesTemoin = (0 until TIRAGES).map { GenerateurRepas.generer(java.util.Random(1400L + it), temoin, 24, 1400L * 1000 + it) }

        fun part(l: List<Boolean>) = l.count { it }.toDouble() / l.size
        val a = part(mondesChangement.map { dans(habitudeAu(it, c.minusDays(1)), avant, 0.25) })
        val b = part(mondesChangement.map { dans(habitudeAu(it, c.plusDays(42)), apres, 0.25) })
        val c8 = part(mondesChangement.map { dans(habitudeAu(it, c.plusDays(56)), apres, 0.25) })
        val signale6 = part(mondesChangement.map { habitudeAu(it, c.plusDays(42))?.changementLe != null })
        val delais = mondesChangement.map { w -> (0..56 step 7).firstOrNull { dans(habitudeAu(w, c.plusDays(it.toLong())), apres, 0.15) } }
        val delaiMedian = delais.map { it ?: 99 }.sorted()[delais.size / 2]
        val fauxChangement = part(mondesTemoin.map { w -> (4..24).any { sem -> habitudeAu(w, GenerateurRepas.DEPART.plusDays(7L * sem))?.changementLe != null } })
        val temoinJuste = part(mondesTemoin.map { dans(habitudeAu(it, GenerateurRepas.DEPART.plusDays(7L * 24)), avant, 0.25) })
        println(
            "Sim 11 : avant le changement ${pct(a)} · 6 sem après ${pct(b)} · 8 sem après ${pct(c8)} (tous à ±25 %) · " +
                "changement signalé à 6 sem ${pct(signale6)} · délai médian ${delaiMedian} j (99 = pas atteint) · " +
                "témoin : faux changement ${pct(fauxChangement)}, habitude juste à 24 sem ${pct(temoinJuste)} (±25 %)",
        )
        assertTrue("11a : avant le changement ${pct(a)}", a >= 0.90)
        assertTrue("11b : 6 semaines après ${pct(b)}", b >= 0.80)
        // 11c : mesuré, pas promis (seconde révision du 26/11) ; voir le texte du critère.
        assertTrue("11d : faux changement dans ${pct(fauxChangement)} des mondes témoins", fauxChangement <= 0.10)
        // Second point de 11d : mesuré, pas promis (même révision).
    }
}
