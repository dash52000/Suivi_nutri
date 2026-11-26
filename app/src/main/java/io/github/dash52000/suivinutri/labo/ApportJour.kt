package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.repas.ApportJour
import io.github.dash52000.suivinutri.repas.Moment
import io.github.dash52000.suivinutri.repas.Observation
import io.github.dash52000.suivinutri.repas.SourceMoment
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/*
 * Apport journalier avec barre d'erreur (lot v1.18 B, étape 3 ; archi section 6, `suivi-nutri.md` 4.8).
 * Aucune dépendance Android.
 *
 * À θ fixé, apports des jours et pesées sont conjointement gaussiens (modèle de `BiaisRepas`) : l'apport d'un jour,
 * ou la moyenne de plusieurs jours, se conditionne exactement par toutes les pesées (espérance et variance
 * conditionnelles). L'incertitude sur θ s'y ajoute par la méthode delta (gradient de l'espérance conditionnelle,
 * covariance de Laplace). Intervalles à 95 % gaussiens.
 *
 * Deux sorties : [ApportsCalcules.moyenne] (moyenne journalière sur N jours, ancrée sur la balance) et
 * [ApportsCalcules.jour] (un jour isolé, barre plus large). Seuls les jours de la fenêtre d'estimation sont servis.
 */
class ApportsCalcules(private val res: ResultatBiais) {

    private val m = res.modele
    private val et = res.etat
    val jours: List<LocalDate> = m.jours.map { it.date }
    private val index = jours.withIndex().associate { it.value to it.index }
    private val rho = Hall.RHO
    private val se2 = DoubleArray(m.cellules.size) { m.cellules[it].seMoyenne * m.cellules[it].seMoyenne }

    /** [jour][moment] : espérance conditionnelle par moment (u rangé dans le grignotage). */
    private val parMoment: Array<DoubleArray> = Array(m.nJours) { s ->
        DoubleArray(Moment.entries.size) { mo ->
            var v = et.fMoment[s][mo]
            for ((i, p) in m.pesees.withIndex()) {
                var b = if (s < p.t) et.vIMoment[s][mo] else 0.0
                for (g in se2.indices) if (m.momentCellule[g] == mo) b += se2[g] * et.c[s][g] * et.cCumul[p.t][g]
                v += b / rho * res.residusNormes[i]
            }
            v
        }
    }

    private val sources: List<Map<Moment, SourceMoment>> = m.jours.map { j ->
        j.repas.associate { r ->
            r.source.moment to when (r.source.observation) {
                Observation.DECRIT -> SourceMoment.DECRIT
                Observation.NON_DECRIT -> SourceMoment.CONTEXTE
                Observation.RIEN, Observation.COMME_DHAB -> SourceMoment.TAP
            }
        }
    }

    fun jour(d: LocalDate): ApportJour? {
        val i = index[d] ?: return null
        val a = DoubleArray(m.nJours).also { it[i] = 1.0 }
        val ic = intervalle(a)
        val kcalParMoment = Moment.entries.associateWith { parMoment[i][it.ordinal] }
        return ApportJour(d, kcalParMoment, sources[i], ic.estimation, ic.bas, ic.haut)
    }

    /** Moyenne journalière des [nJours] jours finissant à [fin] (compris). Null si l'un d'eux est hors de la fenêtre. */
    fun moyenne(fin: LocalDate, nJours: Int = 28): Intervalle? {
        val a = DoubleArray(m.nJours)
        for (k in 0 until nJours) a[index[fin.minusDays(k.toLong())] ?: return null] = 1.0 / nJours
        return intervalle(a)
    }

    /** Espérance et variance a posteriori de Σ a_s × apport_s. */
    fun fonctionnelle(a: DoubleArray): Pair<Double, Double> {
        val n = m.pesees.size
        val p = m.nParam
        val g = se2.size
        var moyenne = 0.0
        var varPropre = 0.0
        val grad = DoubleArray(p)
        val ac = DoubleArray(g)
        for (s in 0 until m.nJours) {
            if (a[s] == 0.0) continue
            moyenne += a[s] * et.f[s]
            varPropre += a[s] * a[s] * et.vI[s]
            for (j in 0 until p) grad[j] += a[s] * et.jacF[s][j]
            for (k in 0 until g) ac[k] += a[s] * et.c[s][k]
        }
        for (k in 0 until g) varPropre += se2[k] * ac[k] * ac[k]
        // Cumul de a_s × vI_s avant le matin t.
        val cumul = DoubleArray(m.nJours + 1)
        for (s in 0 until m.nJours) cumul[s + 1] = cumul[s] + a[s] * et.vI[s]
        // b = Cov(pesées, Σ a apport).
        val b = DoubleArray(n) { i ->
            val t = m.pesees[i].t
            var v = cumul[t]
            for (k in 0 until g) v += se2[k] * ac[k] * et.cCumul[t][k]
            v / rho
        }
        for (i in 0 until n) moyenne += b[i] * res.residusNormes[i]
        val y = BiaisRepas.descente(res.choleskySigma, b)
        var varCond = varPropre - y.sumOf { it * it }
        for (i in 0 until n) for (j in 0 until p) grad[j] -= y[i] * res.jacMuNormee[i][j]
        for (i in 0 until p) for (j in 0 until p) varCond += grad[i] * res.covariance[i][j] * grad[j]
        return moyenne to max(0.0, varCond)
    }

    /**
     * Intervalle log-normal de même espérance et même variance : un apport est positif et asymétrique (repas
     * log-normaux), surtout pour un jour isolé ; pour une moyenne sur 4 semaines il est presque symétrique.
     */
    private fun intervalle(a: DoubleArray): Intervalle {
        val (moy, v) = fonctionnelle(a)
        if (moy <= 0.0) return Intervalle(moy, moy - Stats.Z975 * sqrt(v), moy + Stats.Z975 * sqrt(v))
        val s2 = ln(1.0 + v / (moy * moy))
        val centre = ln(moy) - s2 / 2
        val e = Stats.Z975 * sqrt(s2)
        return Intervalle(moy, exp(centre - e), exp(centre + e))
    }
}

object ApportsRepas {
    fun calculer(resultat: ResultatBiais): ApportsCalcules = ApportsCalcules(resultat)
}
