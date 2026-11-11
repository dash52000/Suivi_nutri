package io.github.dash52000.suivinutri.labo

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Une estimation avec son intervalle de confiance à 95 %, et la p-valeur bilatérale du test
 * « effet nul » quand elle existe.
 */
data class Intervalle(val estimation: Double, val bas: Double, val haut: Double, val p: Double? = null) {
    /** L'intervalle contient zéro : pas d'effet net visible. */
    val contientZero: Boolean get() = bas <= 0.0 && haut >= 0.0
    fun fois(k: Double): Intervalle =
        if (k >= 0) Intervalle(estimation * k, bas * k, haut * k, p) else Intervalle(estimation * k, haut * k, bas * k, p)
}

/** Résultat d'une régression linéaire : coefficients, erreurs-types (HAC) et degrés de liberté résiduels. */
class ResultatOls(val coef: DoubleArray, val se: DoubleArray, val ddl: Int) {
    /** Intervalle à 95 % et p-valeur (Student, [ddl] degrés de liberté) du coefficient [j]. */
    fun intervalle(j: Int): Intervalle {
        val t = Stats.t975(ddl.toDouble())
        val p = if (se[j] > 0) Stats.pStudent(coef[j] / se[j], ddl.toDouble()) else null
        return Intervalle(coef[j], coef[j] - t * se[j], coef[j] + t * se[j], p)
    }
}

object Stats {

    const val Z975 = 1.959963984540054

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

    // ---------- Lois et p-valeurs ----------

    private val LANCZOS = doubleArrayOf(
        0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
        -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6,
        1.5056327351493116e-7,
    )

    /** ln Γ(x) pour x ≥ 0,5 (approximation de Lanczos, g = 7). */
    fun lnGamma(x: Double): Double {
        require(x >= 0.5)
        val xx = x - 1
        var a = LANCZOS[0]
        for (i in 1 until LANCZOS.size) a += LANCZOS[i] / (xx + i)
        val t = xx + 7.5
        return 0.5 * ln(2 * Math.PI) + (xx + 0.5) * ln(t) - t + ln(a)
    }

    /** Fraction continue de la bêta incomplète (méthode de Lentz). */
    private fun betaFractionContinue(a: Double, b: Double, x: Double): Double {
        val minimum = 1e-300
        val qab = a + b
        val qap = a + 1
        val qam = a - 1
        var c = 1.0
        var d = 1 - qab * x / qap
        if (abs(d) < minimum) d = minimum
        d = 1 / d
        var h = d
        for (m in 1..500) {
            val m2 = 2 * m
            var aa = m * (b - m) * x / ((qam + m2) * (a + m2))
            d = 1 + aa * d; if (abs(d) < minimum) d = minimum
            c = 1 + aa / c; if (abs(c) < minimum) c = minimum
            d = 1 / d
            h *= d * c
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
            d = 1 + aa * d; if (abs(d) < minimum) d = minimum
            c = 1 + aa / c; if (abs(c) < minimum) c = minimum
            d = 1 / d
            val delta = d * c
            h *= delta
            if (abs(delta - 1) < 1e-15) break
        }
        return h
    }

    /** Fonction bêta incomplète régularisée I_x(a, b). */
    fun betaIncomplete(x: Double, a: Double, b: Double): Double {
        if (x <= 0) return 0.0
        if (x >= 1) return 1.0
        val facteur = exp(lnGamma(a + b) - lnGamma(a) - lnGamma(b) + a * ln(x) + b * ln(1 - x))
        return if (x < (a + 1) / (a + b + 2)) facteur * betaFractionContinue(a, b, x) / a
        else 1 - facteur * betaFractionContinue(b, a, 1 - x) / b
    }

    /** p-valeur bilatérale d'une statistique de Student [t] à [ddl] degrés de liberté. */
    fun pStudent(t: Double, ddl: Double): Double = betaIncomplete(ddl / (ddl + t * t), ddl / 2, 0.5)

    /** Fonction d'erreur complémentaire (erreur relative < 1,2 × 10⁻⁷). */
    fun erfc(x: Double): Double {
        val z = abs(x)
        val t = 1 / (1 + 0.5 * z)
        val r = t * exp(
            -z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806 +
                t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277))))))))
        )
        return if (x >= 0) r else 2 - r
    }

    /** p-valeur bilatérale d'une statistique normale [z]. */
    fun pNormal(z: Double): Double = erfc(abs(z) / sqrt(2.0))

    /**
     * Correction de Benjamini-Hochberg : q-valeurs dans l'ordre des p-valeurs fournies.
     * Un effet est retenu si q < 5 % : on accepte en moyenne 5 % de fausses découvertes parmi les effets retenus.
     */
    fun benjaminiHochberg(p: List<Double>): List<Double> {
        val m = p.size
        if (m == 0) return emptyList()
        val ordre = p.indices.sortedBy { p[it] }
        val q = DoubleArray(m)
        var minimum = 1.0
        for (rang in m downTo 1) {
            val i = ordre[rang - 1]
            minimum = minOf(minimum, p[i] * m / rang)
            q[i] = minimum
        }
        return q.toList()
    }

    // ---------- Tests et intervalles ----------

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
        return Intervalle(diff, diff - t * se, diff + t * se, pStudent(diff / se, ddl))
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
        return Intervalle(brut, centre - Z975 * se, centre + Z975 * se, pNormal(centre / se))
    }

    /**
     * Pente d'une régression linéaire simple y ~ x, avec erreur-type de Newey-West
     * (autocorrélation entre périodes voisines, retard [retard]).
     * [positions] donne le rang de chaque observation dans le temps (ex. numéro de semaine) :
     * seules les observations réellement voisines comptent dans la correction.
     */
    fun penteNeweyWest(x: List<Double>, y: List<Double>, positions: List<Int>, retard: Int = 1): Intervalle? {
        require(x.size == y.size && positions.size == x.size)
        if (x.size < 3) return null
        val r = olsHac(x.map { doubleArrayOf(1.0, it) }, y, positions, retard) ?: return null
        return r.intervalle(1)
    }

    /**
     * Moindres carrés ordinaires avec matrice de covariance de Newey-West (noyau de Bartlett,
     * retard [retard], correction n / (n − k)). [lignes] contient déjà la constante si on la veut.
     * Seules les observations dont les [positions] diffèrent exactement de l = 1..retard sont croisées :
     * un trou dans la série ne crée pas de fausse autocorrélation. Null si la matrice est singulière.
     */
    fun olsHac(lignes: List<DoubleArray>, y: List<Double>, positions: List<Int>, retard: Int): ResultatOls? {
        val n = lignes.size
        require(y.size == n && positions.size == n)
        if (n == 0) return null
        val k = lignes[0].size
        if (n <= k) return null

        val xtx = Array(k) { DoubleArray(k) }
        val xty = DoubleArray(k)
        for (i in 0 until n) {
            val l = lignes[i]
            for (a in 0 until k) {
                xty[a] += l[a] * y[i]
                for (b in 0 until k) xtx[a][b] += l[a] * l[b]
            }
        }
        val inv = inverser(xtx) ?: return null
        val coef = DoubleArray(k) { a -> (0 until k).sumOf { b -> inv[a][b] * xty[b] } }

        // u_i = x_i × résidu_i
        val u = Array(n) { i ->
            val e = y[i] - (0 until k).sumOf { a -> lignes[i][a] * coef[a] }
            DoubleArray(k) { a -> lignes[i][a] * e }
        }
        val s = Array(k) { DoubleArray(k) }
        for (i in 0 until n) for (a in 0 until k) for (b in 0 until k) s[a][b] += u[i][a] * u[i][b]
        val rang = positions.withIndex().associate { (i, p) -> p to i }
        for (l in 1..retard) {
            val poids = 1.0 - l.toDouble() / (retard + 1)
            positions.forEachIndexed { i, p ->
                val j = rang[p - l] ?: return@forEachIndexed
                for (a in 0 until k) for (b in 0 until k) s[a][b] += poids * (u[i][a] * u[j][b] + u[j][a] * u[i][b])
            }
        }
        val correction = n.toDouble() / (n - k)
        val se = DoubleArray(k) { a ->
            var v = 0.0
            for (b in 0 until k) for (c in 0 until k) v += inv[a][b] * s[b][c] * inv[c][a]
            sqrt(abs(v * correction))
        }
        return ResultatOls(coef, se, n - k)
    }

    /** Inverse d'une matrice carrée (Gauss-Jordan, pivot partiel). Null si (quasi) singulière. */
    fun inverser(m: Array<DoubleArray>): Array<DoubleArray>? {
        val k = m.size
        val a = Array(k) { i -> DoubleArray(2 * k) { j -> if (j < k) m[i][j] else if (j - k == i) 1.0 else 0.0 } }
        val echelle = m.maxOf { ligne -> ligne.maxOf { abs(it) } }.takeIf { it > 0 } ?: return null
        for (col in 0 until k) {
            val pivot = (col until k).maxBy { abs(a[it][col]) }
            if (abs(a[pivot][col]) < 1e-12 * echelle) return null
            val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
            val d = a[col][col]
            for (j in 0 until 2 * k) a[col][j] /= d
            for (i in 0 until k) if (i != col) {
                val f = a[i][col]
                if (f != 0.0) for (j in 0 until 2 * k) a[i][j] -= f * a[col][j]
            }
        }
        return Array(k) { i -> DoubleArray(k) { j -> a[i][j + k] } }
    }

    // ---------- Tirages pour les lois a posteriori (v1.12) ----------

    /** Tirage d'une loi gamma de forme [forme] et d'échelle 1 (Marsaglia et Tsang, 2000). */
    fun tirageGamma(alea: java.util.Random, forme: Double): Double {
        if (forme < 1.0) return tirageGamma(alea, forme + 1.0) * alea.nextDouble().pow(1.0 / forme)
        val d = forme - 1.0 / 3
        val c = 1.0 / sqrt(9 * d)
        while (true) {
            var x: Double
            var v: Double
            do {
                x = alea.nextGaussian()
                v = 1 + c * x
            } while (v <= 0)
            v = v * v * v
            val u = alea.nextDouble()
            if (u < 1 - 0.0331 * x * x * x * x) return d * v
            if (ln(u) < 0.5 * x * x + d * (1 - v + ln(v))) return d * v
        }
    }

    /** Tirage d'une loi bêta(a, b). */
    fun tirageBeta(alea: java.util.Random, a: Double, b: Double): Double {
        val x = tirageGamma(alea, a)
        val y = tirageGamma(alea, b)
        return x / (x + y)
    }
}
