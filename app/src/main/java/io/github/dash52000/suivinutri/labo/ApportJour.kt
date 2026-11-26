package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.repas.ApportJour
import io.github.dash52000.suivinutri.repas.Moment
import io.github.dash52000.suivinutri.repas.Observation
import io.github.dash52000.suivinutri.repas.RepasObserve
import io.github.dash52000.suivinutri.repas.SourceMoment
import java.time.LocalDate
import java.util.IdentityHashMap
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/*
 * Apport journalier avec barre d'erreur (lot v1.18 B, étape 3 ; archi section 6, `suivi-nutri.md` 4.8).
 * Aucune dépendance Android.
 *
 * Pour chaque tirage de l'a posteriori (`ResultatBiais.tirages`) :
 *  - θ(s) donne les biais ; la moyenne de chaque cellule d'imputation est tirée dans son erreur d'échantillonnage
 *    (la même pour tous les jours qui s'en servent) ;
 *  - chaque repas décrit varie selon sa marge σ (log-normale de moyenne 1), chaque repas non décrit selon la
 *    variabilité de sa cellule, chaque jour selon la variabilité du non expliqué u ;
 *  - un repas « Rien » vaut 0, un « Comme d'hab » se traite comme un repas décrit ;
 *  - si le jour appartient à une semaine de l'ajustement, ses écarts sont conditionnés par la balance de cette
 *    semaine (règle de Matheron : les écarts du jour sont décalés d'une part de l'écart entre la balance observée et
 *    la balance simulée, proportionnelle à leur variance). Sans cela, la barre de la moyenne sur 4 semaines serait
 *    trop large : elle ignorerait que la balance a déjà vu ces semaines.
 *
 * Deux sorties : [ApportsCalcules.moyenne] (moyenne journalière sur N jours, ancrée sur la balance) et
 * [ApportsCalcules.jour] (un jour isolé, barre plus large). Intervalles à 95 % (quantiles 2,5 et 97,5 % des tirages).
 */
class ApportsCalcules(
    val jours: List<LocalDate>,
    /** [tirage][jour] : kcal du jour, après conditionnement. */
    private val totaux: Array<DoubleArray>,
    /** [jour][moment.ordinal] : moyenne sur les tirages. */
    private val parMoment: Array<DoubleArray>,
    private val sources: List<Map<Moment, SourceMoment>>,
    /** Jour appartenant à une semaine de l'ajustement (conditionné par la balance). */
    val conditionne: BooleanArray,
) {
    private val index = jours.withIndex().associate { it.value to it.index }

    fun jour(d: LocalDate): ApportJour? {
        val i = index[d] ?: return null
        val v = totaux.map { it[i] }.toDoubleArray()
        val (bas, haut) = quantiles(v)
        val kcalParMoment = Moment.entries.filter { it in sources[i] }.associateWith { parMoment[i][it.ordinal] }
        return ApportJour(d, kcalParMoment, sources[i], v.average(), bas, haut)
    }

    /** Moyenne journalière des [nJours] jours finissant à [fin] (compris). Null si l'un d'eux manque. */
    fun moyenne(fin: LocalDate, nJours: Int = 28): Intervalle? {
        val idx = (0 until nJours).map { index[fin.minusDays(it.toLong())] ?: return null }
        val v = DoubleArray(totaux.size) { s -> idx.sumOf { totaux[s][it] } / nJours }
        val (bas, haut) = quantiles(v)
        return Intervalle(v.average(), bas, haut)
    }

    private fun quantiles(v: DoubleArray): Pair<Double, Double> {
        val t = v.sortedArray()
        fun q(p: Double): Double {
            val x = p * (t.size - 1)
            val i = x.toInt()
            return if (i + 1 < t.size) t[i] + (x - i) * (t[i + 1] - t[i]) else t[i]
        }
        return q(0.025) to q(0.975)
    }
}

object ApportsRepas {

    /**
     * Apports de tous les jours de [repas] (quatre moments par jour). Les jours des semaines de l'ajustement sont
     * conditionnés par la balance ; les autres (semaine en cours, avant le départ) restent des prédictions du modèle.
     */
    fun calculer(
        resultat: ResultatBiais,
        repas: List<RepasObserve>,
        nTirages: Int = resultat.modele.hyp.nTirages,
        graine: Long = resultat.modele.hyp.graine + 1,
    ): ApportsCalcules {
        val hyp = resultat.modele.hyp
        val jours = repas.map { it.jour }.distinct().sorted()
        val index = jours.withIndex().associate { it.value to it.index }
        val repasModele = jours.map { j -> repas.filter { it.jour == j }.map { resultat.lire(it) } }

        // Semaines de l'ajustement : jours couverts, balance observée et son écart-type.
        val semaines = resultat.modele.semaines
        val semaineDuJour = IntArray(jours.size) { -1 }
        for ((w, s) in semaines.withIndex()) for (k in 0 until s.nJours) index[s.debut.plusDays(k.toLong())]?.let { semaineDuJour[it] = w }

        // Cellules d'imputation utilisées (moyenne tirée une fois par tirage).
        val cellules = repasModele.flatten().mapNotNull { it.cellule }.distinct()
        val iCellule = IdentityHashMap<Resume, Int>().also { m -> cellules.forEachIndexed { i, c -> m[c] = i } }

        val alea = Random(graine)
        val thetas = resultat.tirages(nTirages)
        val totaux = Array(nTirages) { DoubleArray(jours.size) }
        val somme = Array(jours.size) { DoubleArray(Moment.entries.size) }
        val valeur = Array(jours.size) { DoubleArray(Moment.entries.size) }
        val variance = Array(jours.size) { DoubleArray(Moment.entries.size) }
        val uJour = hyp.sigmaUJour

        for (s in 0 until nTirages) {
            val theta = thetas[s]
            val moyennes = DoubleArray(cellules.size) { max(0.0, cellules[it].moyenne + cellules[it].seMoyenne * alea.nextGaussian()) }
            for (d in jours.indices) {
                valeur[d].fill(0.0)
                variance[d].fill(0.0)
                for (r in repasModele[d]) {
                    val m = r.source.moment.ordinal
                    val e = exp(resultat.modele.biais(theta, r))
                    when (r.source.observation) {
                        Observation.RIEN -> Unit
                        Observation.DECRIT, Observation.COMME_DHAB -> {
                            val moy = r.valeur * e
                            val sigma = r.source.sigma!!
                            valeur[d][m] += moy * exp(sigma * alea.nextGaussian() - sigma * sigma / 2)
                            variance[d][m] += moy * moy * (exp(sigma * sigma) - 1.0)
                        }
                        Observation.NON_DECRIT -> {
                            val cell = r.cellule!!
                            val moy = moyennes[iCellule.getValue(cell)] * e
                            if (moy > 1e-9 && cell.moyenne > 1e-9) {
                                val cv = cell.ecartType / cell.moyenne
                                val sl = sqrt(ln(1.0 + cv * cv))
                                valeur[d][m] += moy * exp(sl * alea.nextGaussian() - sl * sl / 2)
                                variance[d][m] += moy * moy * (exp(sl * sl) - 1.0)
                            }
                        }
                    }
                }
                // Non expliqué : rangé dans le grignotage (moment non observé), variable d'un jour à l'autre.
                val g = Moment.GRIGNOTAGE.ordinal
                valeur[d][g] += theta[resultat.modele.iU] * ModeleBiais.ECHELLE_U + uJour * alea.nextGaussian()
                variance[d][g] += uJour * uJour
            }
            // Conditionnement semaine par semaine par la balance (Matheron).
            for ((w, sem) in semaines.withIndex()) {
                val idx = (0 until sem.nJours).mapNotNull { index[sem.debut.plusDays(it.toLong())] }
                if (idx.size != sem.nJours) continue
                val n = sem.nJours.toDouble()
                val total = idx.sumOf { valeur[it].sum() }
                val v = idx.sumOf { variance[it].sum() }
                val simulee = total / n + sem.se * alea.nextGaussian()
                val gain = (sem.y - simulee) / (v / (n * n) + sem.se * sem.se) / n
                for (d in idx) for (m in Moment.entries.indices) valeur[d][m] += variance[d][m] * gain
            }
            for (d in jours.indices) {
                totaux[s][d] = valeur[d].sum()
                for (m in Moment.entries.indices) somme[d][m] += valeur[d][m]
            }
        }
        val parMoment = Array(jours.size) { d -> DoubleArray(Moment.entries.size) { m -> somme[d][m] / nTirages } }
        val sources = repasModele.map { liste ->
            liste.associate { r ->
                r.source.moment to when (r.source.observation) {
                    Observation.DECRIT -> SourceMoment.DECRIT
                    Observation.NON_DECRIT -> SourceMoment.CONTEXTE
                    Observation.RIEN, Observation.COMME_DHAB -> SourceMoment.TAP
                }
            }
        }
        return ApportsCalcules(jours, totaux, parMoment, sources, BooleanArray(jours.size) { semaineDuJour[it] >= 0 })
    }
}
