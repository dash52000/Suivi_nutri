package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.repas.Observation
import io.github.dash52000.suivinutri.repas.Origine
import io.github.dash52000.suivinutri.repas.RepasObserve
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/*
 * Écart entre phrases spontanées et phrases tirées (lot v1.18 B ; fiche SPEC section 5, « Marge des spontanées » ;
 * simulation 6). Aucune dépendance Android.
 *
 * Une phrase spontanée (hors tirage) est écrite quand le repas est notable : si elle porte sur des repas plus lourds
 * que les phrases tirées du même contexte, la mettre en commun biaiserait. L'indicateur `origine_phrase` permet de le
 * mesurer : dans chaque cellule (moment, contexte, soir chargé ou non) qui a des spontanées et au moins deux phrases
 * de référence (tirées ou chargées), écart = moyenne de ln ĉ des spontanées − moyenne de ln ĉ des références ;
 * cellules combinées par poids inverse de la variance (écart-type d'une phrase estimé dans la cellule, avec une
 * pseudo-valeur de 0,3 pour stabiliser les petites cellules).
 *
 * `detecte` : |écart| ≥ 1,96 écart-type. Sert à geler le facteur de marge des spontanées à × 1,5
 * (`Marges.facteurOrigine`, paramètre `ecartDetecte`) ; sans écart détecté, ce facteur décroît vers × 1.
 */
data class EcartSpontanees(
    /** Écart moyen de ln ĉ (spontanées − références), sur les cellules comparables. */
    val ecart: Double,
    val se: Double,
    val nSpontanees: Int,
    val nReferences: Int,
) {
    val z: Double get() = ecart / se
    val detecte: Boolean get() = abs(z) >= 1.96

    companion object {
        private const val SIGMA_PSEUDO = 0.3

        /** Null sans cellule comparable (aucune spontanée, ou moins de 2 références dans leur cellule). */
        fun estimer(repas: List<RepasObserve>): EcartSpontanees? {
            val phrases = repas.filter { it.observation == Observation.DECRIT && (it.kcal ?: 0.0) > 0.0 }
            var somme = 0.0
            var poids = 0.0
            var nS = 0
            var nR = 0
            for ((_, groupe) in phrases.groupBy { Triple(it.moment, it.contexte, Cellules.charge(it)) }) {
                val s = groupe.filter { it.origine == Origine.SPONTANEE }.map { ln(it.kcal!!) }
                val r = groupe.filter { it.origine != Origine.SPONTANEE }.map { ln(it.kcal!!) }
                if (s.isEmpty() || r.size < 2) continue
                val toutes = s + r
                val m = toutes.average()
                val variance = (toutes.sumOf { (it - m) * (it - m) } + SIGMA_PSEUDO * SIGMA_PSEUDO) / (toutes.size - 1 + 1)
                val d = s.average() - r.average()
                val v = variance * (1.0 / s.size + 1.0 / r.size)
                somme += d / v
                poids += 1.0 / v
                nS += s.size
                nR += r.size
            }
            if (poids <= 0.0) return null
            return EcartSpontanees(somme / poids, 1.0 / sqrt(poids), nS, nR)
        }
    }
}
