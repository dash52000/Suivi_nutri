package io.github.dash52000.suivinutri.labo

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Filtre et lisseur de Kalman pour le poids : modèle « niveau local + pente », pas journalier.
 *
 *   niveau(t+1) = niveau(t) + pente(t) + bruit      (variance [Q_NIVEAU])
 *   pente(t+1)  = pente(t) + bruit                  (variance [Q_PENTE])
 *   pesée(t)    = niveau(t) + bruit de mesure       (variance selon l'heure : eau, repas, toilettes)
 *
 * Le lisseur (Rauch-Tung-Striebel) utilise toutes les pesées, avant et après chaque jour :
 * la tendance n'a pas de retard, contrairement à un lissage exponentiel.
 * Paramètres à valider dans l'analyse R avant toute évolution.
 */
object Kalman {

    /** Bruit d'une pesée du matin : (0,6 kg)². */
    const val VAR_MATIN = 0.36

    /** Bruit d'une pesée d'après-midi : (1,2 kg)², elle compte 4 fois moins. */
    const val VAR_APRES_MIDI = 1.44
    const val HEURE_APRES_MIDI = 12

    /** Écart quotidien du niveau à la tendance linéaire : (0,05 kg)². */
    const val Q_NIVEAU = 0.0025

    /** Variation quotidienne de la pente : (0,005 kg/j)². */
    const val Q_PENTE = 2.5e-5

    const val VAR_NIVEAU_INITIALE = 4.0
    const val VAR_PENTE_INITIALE = 0.0025

    /** Une pesée à plus de 4 écarts-types de la prévision est écartée (faute de frappe, balance…). */
    const val SEUIL_ABERRANT = 4.0

    data class Pesee(val jour: Int, val poids: Double, val variance: Double)

    fun variancePesee(heure: Int?): Double =
        if (heure != null && heure >= HEURE_APRES_MIDI) VAR_APRES_MIDI else VAR_MATIN

    /** Matrices 2 × 2 stockées [a, b, c, d] pour [[a, b], [c, d]]. */
    private fun mul(x: DoubleArray, y: DoubleArray) = doubleArrayOf(
        x[0] * y[0] + x[1] * y[2], x[0] * y[1] + x[1] * y[3],
        x[2] * y[0] + x[3] * y[2], x[2] * y[1] + x[3] * y[3],
    )

    private fun transposee(x: DoubleArray) = doubleArrayOf(x[0], x[2], x[1], x[3])
    private fun plus(x: DoubleArray, y: DoubleArray) = DoubleArray(4) { x[it] + y[it] }
    private fun moins(x: DoubleArray, y: DoubleArray) = DoubleArray(4) { x[it] - y[it] }
    private fun inverse(x: DoubleArray): DoubleArray {
        val det = x[0] * x[3] - x[1] * x[2]
        return doubleArrayOf(x[3] / det, -x[1] / det, -x[2] / det, x[0] / det)
    }

    private val F = doubleArrayOf(1.0, 1.0, 0.0, 1.0)
    private val FT = transposee(F)
    private val Q = doubleArrayOf(Q_NIVEAU, 0.0, 0.0, Q_PENTE)

    /** Résultat du lissage, jour par jour à partir de [debut]. */
    class Lissage internal constructor(
        val debut: LocalDate,
        private val niveaux: DoubleArray,
        private val pentes: DoubleArray,
        private val covariances: Array<DoubleArray>,
        private val gains: Array<DoubleArray>,
        /** Indices des jours dont la pesée a été écartée. */
        val ecartees: Set<Int>,
    ) {
        val n: Int get() = niveaux.size

        fun index(date: LocalDate): Int? {
            val i = ChronoUnit.DAYS.between(debut, date).toInt()
            return if (i in 0 until n) i else null
        }

        fun date(i: Int): LocalDate = debut.plusDays(i.toLong())
        fun niveau(i: Int): Double = niveaux[i]
        fun pente(i: Int): Double = pentes[i]
        fun varNiveau(i: Int): Double = covariances[i][0]

        /** Cov(niveau(i), niveau(i + k)) sachant toutes les pesées : J_i … J_{i+k−1} P_{i+k}. */
        fun covNiveaux(i: Int, k: Int): Double {
            var m = covariances[i + k]
            for (t in i + k - 1 downTo i) m = mul(gains[t], m)
            return m[0]
        }

        /** Variance de niveau(i + k) − niveau(i). */
        fun varDifference(i: Int, k: Int): Double =
            maxOf(0.0, varNiveau(i) + varNiveau(i + k) - 2 * covNiveaux(i, k))
    }

    /**
     * [n] jours à partir de [debut] ; la première pesée doit tomber le jour 0.
     * Plusieurs pesées le même jour sont intégrées l'une après l'autre.
     */
    fun lisser(debut: LocalDate, n: Int, pesees: List<Pesee>): Lissage {
        require(n >= 1 && pesees.isNotEmpty())
        val parJour = pesees.groupBy { it.jour }
        require(pesees.minOf { it.jour } == 0)

        val xp = Array(n) { DoubleArray(2) }
        val pp = Array(n) { DoubleArray(4) }
        val xf = Array(n) { DoubleArray(2) }
        val pf = Array(n) { DoubleArray(4) }
        val ecartees = mutableSetOf<Int>()

        var x = doubleArrayOf(parJour.getValue(0).first().poids, 0.0)
        var p = doubleArrayOf(VAR_NIVEAU_INITIALE, 0.0, 0.0, VAR_PENTE_INITIALE)
        for (t in 0 until n) {
            if (t > 0) {
                x = doubleArrayOf(x[0] + x[1], x[1])
                p = plus(mul(mul(F, p), FT), Q)
            }
            xp[t] = x
            pp[t] = p
            parJour[t]?.forEach { m ->
                val s = p[0] + m.variance
                val v = m.poids - x[0]
                if (abs(v) > SEUIL_ABERRANT * sqrt(s)) {
                    ecartees += t
                } else {
                    val k0 = p[0] / s
                    val k1 = p[2] / s
                    x = doubleArrayOf(x[0] + k0 * v, x[1] + k1 * v)
                    p = doubleArrayOf(p[0] - k0 * p[0], p[1] - k0 * p[1], p[2] - k1 * p[0], p[3] - k1 * p[1])
                }
            }
            xf[t] = x
            pf[t] = p
        }

        val xs = Array(n) { xf[it].copyOf() }
        val ps = Array(n) { pf[it].copyOf() }
        val gains = Array(n) { DoubleArray(4) }
        for (t in n - 2 downTo 0) {
            val j = mul(mul(pf[t], FT), inverse(pp[t + 1]))
            gains[t] = j
            val dx0 = xs[t + 1][0] - xp[t + 1][0]
            val dx1 = xs[t + 1][1] - xp[t + 1][1]
            xs[t] = doubleArrayOf(xf[t][0] + j[0] * dx0 + j[1] * dx1, xf[t][1] + j[2] * dx0 + j[3] * dx1)
            ps[t] = plus(pf[t], mul(mul(j, moins(ps[t + 1], pp[t + 1])), transposee(j)))
        }

        return Lissage(
            debut = debut,
            niveaux = DoubleArray(n) { xs[it][0] },
            pentes = DoubleArray(n) { xs[it][1] },
            covariances = ps,
            gains = gains,
            ecartees = ecartees,
        )
    }
}
