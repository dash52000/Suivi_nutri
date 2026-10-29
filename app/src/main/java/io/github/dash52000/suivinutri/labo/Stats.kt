package io.github.dash52000.suivinutri.labo

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** Une estimation avec son intervalle de confiance à 95 %. */
data class Intervalle(val estimation: Double, val bas: Double, val haut: Double) {
    /** L'intervalle contient zéro : pas d'effet net visible. */
    val contientZero: Boolean get() = bas <= 0.0 && haut >= 0.0
    fun fois(k: Double): Intervalle =
        if (k >= 0) Intervalle(estimation * k, bas * k, haut * k) else Intervalle(estimation * k, haut * k, bas * k)
}

object Stats {

    private const val Z975 = 1.959963984540054

    /** Quantiles 97,5 % de Student pour 1 à 30 degrés de liberté. */
    private val T975 = doubleArrayOf(
        12.706205, 4.302653, 3.182446, 2.776445, 2.570582, 2.446912, 2.364624, 2.306004, 2.262157, 2.228139,
        2.200985, 2.178813, 2.160369, 2.144787, 2.131450, 2.119905, 2.109816, 2.100922, 2.093024, 2.085963,
        2.079614, 2.073873, 2.068658, 2.063899, 2.059539, 2.055529, 2.051831, 2.048407, 2.045230, 2.042272,
    )

    /** Quantile 97,5 % de Student ; au-delà de 30 ddl, développement de Cornish-Fisher. */
    fun t975(ddl: Double): Double {
        require(ddl > 0)
        if (ddl <= 30) {
            val bas = ddl.toInt().coerceIn(1, 30)
            val haut = (bas + 1).coerceAtMost(30)
            if (bas == haut || ddl == bas.toDouble()) return T975[bas - 1]
            // Interpolation sur 1/ddl, quasi linéaire pour les quantiles de Student.
            val f = (1.0 / bas - 1.0 / ddl) / (1.0 / bas - 1.0 / haut)
            return T975[bas - 1] + f * (T975[haut - 1] - T975[bas - 1])
        }
        val z = Z975
        return z + (z.pow(3) + z) / (4 * ddl) + (5 * z.pow(5) + 16 * z.pow(3) + 3 * z) / (96 * ddl * ddl)
    }

    fun moyenne(x: List<Double>): Double = x.sum() / x.size

    fun variance(x: List<Double>): Double {
        val m = moyenne(x)
        return x.sumOf { (it - m) * (it - m) } / (x.size - 1)
    }

    /** Différence de moyennes (groupe 1 − groupe 0), intervalle de Welch. Null si un groupe a moins de 2 valeurs. */
    fun welch(groupe1: List<Double>, groupe0: List<Double>): Intervalle? {
        val n1 = groupe1.size
        val n0 = groupe0.size
        if (n1 < 2 || n0 < 2) return null
        val v1 = variance(groupe1) / n1
        val v0 = variance(groupe0) / n0
        val diff = moyenne(groupe1) - moyenne(groupe0)
        val se = sqrt(v1 + v0)
        if (se == 0.0) return Intervalle(diff, diff, diff)
        val ddl = (v1 + v0).pow(2) / (v1 * v1 / (n1 - 1) + v0 * v0 / (n0 - 1))
        val t = t975(ddl)
        return Intervalle(diff, diff - t * se, diff + t * se)
    }

    /**
     * Différence de proportions (groupe 1 − groupe 0), intervalle d'Agresti-Caffo
     * (on ajoute un succès et un échec à chaque groupe), fiable sur petits effectifs.
     */
    fun differenceProportions(succes1: Int, n1: Int, succes0: Int, n0: Int): Intervalle? {
        if (n1 == 0 || n0 == 0) return null
        val brut = succes1.toDouble() / n1 - succes0.toDouble() / n0
        val p1 = (succes1 + 1.0) / (n1 + 2)
        val p0 = (succes0 + 1.0) / (n0 + 2)
        val centre = p1 - p0
        val se = sqrt(p1 * (1 - p1) / (n1 + 2) + p0 * (1 - p0) / (n0 + 2))
        return Intervalle(brut, centre - Z975 * se, centre + Z975 * se)
    }

    /**
     * Pente d'une régression linéaire simple y ~ x, avec erreur-type de Newey-West
     * (autocorrélation entre périodes voisines, retard [retard]).
     * [positions] donne le rang de chaque observation dans le temps (ex. numéro de semaine) :
     * seules les observations réellement voisines comptent dans la correction.
     */
    fun penteNeweyWest(x: List<Double>, y: List<Double>, positions: List<Int>, retard: Int = 1): Intervalle? {
        val n = x.size
        require(y.size == n && positions.size == n)
        if (n < 3) return null
        val mx = moyenne(x)
        val my = moyenne(y)
        val sxx = x.sumOf { (it - mx) * (it - mx) }
        if (sxx == 0.0) return null
        val pente = x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / sxx
        val ordonnee = my - pente * mx
        val u = x.indices.map { (x[it] - mx) * (y[it] - ordonnee - pente * x[it]) }
        val rang = positions.withIndex().associate { (i, p) -> p to i }

        var s = u.sumOf { it * it }
        for (l in 1..retard) {
            val poids = 1.0 - l.toDouble() / (retard + 1)
            var somme = 0.0
            positions.forEachIndexed { i, p -> rang[p - l]?.let { j -> somme += u[i] * u[j] } }
            s += 2 * poids * somme
        }
        val varPente = (s / (sxx * sxx)) * n / (n - 2)
        val se = sqrt(abs(varPente))
        val t = t975((n - 2).toDouble())
        return Intervalle(pente, pente - t * se, pente + t * se)
    }
}
