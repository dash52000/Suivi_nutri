package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.EntreeJour
import java.time.LocalDate
import kotlin.math.sqrt

/**
 * Pesée et selle (v1.16). La référence est la pesée « avant la selle » (l'habitude de Damien). Une pesée faite
 * après pèse moins (une selle pèse en général 100 à 250 g) ; un matin sans selle peut peser plus. Après un soir
 * copieux, la selle pèse sans doute plus : l'écart après / avant peut dépendre du dîner de la veille (effet croisé).
 *
 * Modèle sur l'écart de la pesée (corrigée de l'eau) à une première tendance qui ignore la selle :
 *
 *   écart = b0 + b1 × copieux(veille) + b2 × après + b3 × sans selle + b4 × après × copieux(veille) + bruit
 *
 * b1 n'est qu'un contrôle (l'effet du soir copieux sur le lendemain est expliqué ailleurs, par le rab, le dessert et
 * les beaux-parents). Régression bayésienne à variance connue (celle des écarts), deux a priori :
 * - pour corriger la pesée, un a priori physiologique sur b2 : N(−0,15 ; 0,10²) kg. Sans aucun matin « après », la
 *   correction vaut donc −0,15 kg ; elle se cale ensuite sur les données ;
 * - pour le niveau de preuve, l'a priori sceptique du Labo, centré sur zéro (comme le lendemain) : sinon un matin
 *   « après » sans donnée serait déjà « probable ».
 * Sans selle et effet croisé : a priori sceptiques dans les deux cas (sens inconnu ou à prouver).
 * La pesée corrigée = pesée corrigée de l'eau − correction ; elle compte un peu moins dans la tendance (variance de
 * la correction ajoutée à celle de la pesée).
 */
object Selle {

    const val ID = "selle"
    const val AVANT = "avant"
    const val APRES = "apres"
    const val SANS = "sans"

    /** A priori physiologique de l'écart après / avant : −0,15 kg, écart-type 0,10 kg. */
    const val PRIOR_APRES = -0.15
    const val TAU_APRES = 0.10

    /** A priori sceptiques : sans selle (0,15 kg), effet croisé après × copieux (0,10 kg), écart après pour la preuve. */
    const val TAU_SANS = 0.15
    const val TAU_CROISE = 0.10
    const val TAU_PREUVE_APRES = 0.15

    /** Contrôles : constante et soir copieux, a priori larges. */
    private const val TAU_LARGE = 1.0

    /** Bruit des écarts par défaut (pesée corrigée de l'eau), tant qu'il y a moins de [MIN_ECARTS] écarts. */
    const val SIGMA_DEFAUT = 0.42
    const val MIN_ECARTS = 14

    enum class Etat { AVANT, APRES, SANS }

    fun etat(e: EntreeJour?): Etat? = when (e?.reponses?.get(ID)) {
        AVANT -> Etat.AVANT
        APRES -> Etat.APRES
        SANS -> Etat.SANS
        else -> null
    }

    /**
     * Soir copieux, déduit des réponses : rab (« un peu » compris), dessert (petite part comprise) ou dîner chez les
     * beaux-parents. Faux si rab et dessert sont répondus « non » (ou dessert mis dans le tupp) sans beaux-parents ;
     * null sinon.
     */
    fun copieux(e: EntreeJour?): Boolean? {
        val r = e?.reponses ?: return null
        val rab = when (r["resservi"]) { "oui", "un_peu" -> true; "non" -> false; else -> null }
        val dessert = when (r["dessert"]) { "petite", "normale" -> true; "non", "tupp" -> false; else -> null }
        val bp = when (r["diner"]) { "beaux_parents" -> true; "maison", "ailleurs" -> false; else -> null }
        if (rab == true || dessert == true || bp == true) return true
        if (rab == false && dessert == false) return false
        return null
    }

    /** Un coefficient a posteriori : estimation, intervalle crédible à 95 %, probabilité qu'il soit négatif. */
    data class Coef(val intervalle: Intervalle, val probNegatif: Double)

    /** Une correction appliquée à une pesée : à retirer de la pesée (kg, négative pour « après »), et sa variance. */
    data class Correction(val kg: Double, val variance: Double)

    class Modele(
        /** A posteriori pour corriger (a priori physiologique). Indices : 0 const, 1 copieux, 2 après, 3 sans, 4 croisé. */
        private val moyenne: DoubleArray,
        private val covariance: Array<DoubleArray>,
        /** Pour le niveau de preuve (a priori sceptique). */
        val apres: Coef?,
        val sans: Coef?,
        val croise: Coef?,
        /** Matins utilisés : avant, après, sans ; après un soir copieux, après un soir normal. */
        val nAvant: Int,
        val nApres: Int,
        val nSans: Int,
        val nApresCopieux: Int,
        val nApresNormal: Int,
        /** Part des soirs copieux, pour un matin « après » dont la veille n'est pas renseignée. */
        val partCopieux: Double,
        val sigma: Double,
    ) {
        /** Correction d'un matin donné : null si la pesée est « avant » ou sans réponse (rien à corriger). */
        fun correction(etat: Etat?, copieuxVeille: Boolean?): Correction? {
            val x = DoubleArray(5)
            when (etat) {
                Etat.APRES -> {
                    x[2] = 1.0
                    x[4] = copieuxVeille?.let { if (it) 1.0 else 0.0 } ?: partCopieux
                }
                Etat.SANS -> x[3] = 1.0
                else -> return null
            }
            var kg = 0.0
            for (a in 0 until 5) kg += x[a] * moyenne[a]
            var v = 0.0
            for (a in 0 until 5) for (b in 0 until 5) v += x[a] * covariance[a][b] * x[b]
            return Correction(kg, maxOf(0.0, v))
        }

        /** Écart après / avant attendu, à soir copieux ou normal (pour l'affichage). */
        fun ecartApres(copieuxVeille: Boolean): Double = moyenne[2] + (if (copieuxVeille) moyenne[4] else 0.0)
        val ecartSans: Double get() = moyenne[3]
    }

    /** Une ligne de la régression : écart de la pesée et le matin qui va avec. */
    class Ligne(val ecart: Double, val etat: Etat, val copieuxVeille: Boolean)

    /** Régression bayésienne à variance connue : a posteriori N(m, S), S = (X'X/σ² + Λ)⁻¹, m = S (X'y/σ² + Λ μ). */
    private fun posteriori(
        lignes: List<Ligne>,
        sigma2: Double,
        mu: DoubleArray,
        tau: DoubleArray,
    ): Pair<DoubleArray, Array<DoubleArray>>? {
        val k = mu.size
        val a = Array(k) { i -> DoubleArray(k) { j -> if (i == j) 1 / (tau[i] * tau[i]) else 0.0 } }
        val b = DoubleArray(k) { mu[it] / (tau[it] * tau[it]) }
        lignes.forEach { l ->
            val x = vecteur(l)
            for (i in 0 until k) {
                b[i] += x[i] * l.ecart / sigma2
                for (j in 0 until k) a[i][j] += x[i] * x[j] / sigma2
            }
        }
        val s = Stats.inverser(a) ?: return null
        val m = DoubleArray(k) { i -> (0 until k).sumOf { j -> s[i][j] * b[j] } }
        return m to s
    }

    private fun vecteur(l: Ligne): DoubleArray {
        val c = if (l.copieuxVeille) 1.0 else 0.0
        val ap = if (l.etat == Etat.APRES) 1.0 else 0.0
        val sa = if (l.etat == Etat.SANS) 1.0 else 0.0
        return doubleArrayOf(1.0, c, ap, sa, ap * c)
    }

    private fun coef(m: DoubleArray, s: Array<DoubleArray>, i: Int): Coef {
        val sd = sqrt(maxOf(1e-12, s[i][i]))
        val q = Stats.Z975 * sd
        // Probabilité que le coefficient soit négatif : Φ(−m / sd).
        val z = m[i] / sd
        val queue = Stats.pNormal(z) / 2
        val probNegatif = if (z <= 0) 1 - queue else queue
        return Coef(Intervalle(m[i], m[i] - q, m[i] + q), probNegatif)
    }

    /**
     * Estime le modèle sur les matins dont la selle et le soir de la veille sont renseignés. Toujours un modèle :
     * sans donnée, c'est l'a priori (correction de −0,15 kg pour un matin « après », 0 pour « sans selle »).
     */
    fun estimer(lignes: List<Ligne>, toutesLesVeilles: List<Boolean>): Modele {
        val sigma = if (lignes.size >= MIN_ECARTS) {
            val moy = lignes.sumOf { it.ecart } / lignes.size
            sqrt(lignes.sumOf { (it.ecart - moy) * (it.ecart - moy) } / (lignes.size - 1))
        } else SIGMA_DEFAUT
        val s2 = sigma * sigma
        val corr = posteriori(
            lignes, s2,
            doubleArrayOf(0.0, 0.0, PRIOR_APRES, 0.0, 0.0),
            doubleArrayOf(TAU_LARGE, TAU_LARGE, TAU_APRES, TAU_SANS, TAU_CROISE),
        ) ?: (doubleArrayOf(0.0, 0.0, PRIOR_APRES, 0.0, 0.0) to Array(5) { i ->
            DoubleArray(5) { j -> if (i == j) listOf(TAU_LARGE, TAU_LARGE, TAU_APRES, TAU_SANS, TAU_CROISE)[i].let { it * it } else 0.0 }
        })
        val preuve = posteriori(
            lignes, s2,
            DoubleArray(5),
            doubleArrayOf(TAU_LARGE, TAU_LARGE, TAU_PREUVE_APRES, TAU_SANS, TAU_CROISE),
        )
        val nApres = lignes.count { it.etat == Etat.APRES }
        val nSans = lignes.count { it.etat == Etat.SANS }
        val nApresCop = lignes.count { it.etat == Etat.APRES && it.copieuxVeille }
        return Modele(
            moyenne = corr.first,
            covariance = corr.second,
            apres = if (preuve != null && nApres > 0) coef(preuve.first, preuve.second, 2) else null,
            sans = if (preuve != null && nSans > 0) coef(preuve.first, preuve.second, 3) else null,
            croise = if (preuve != null && nApresCop > 0 && nApres - nApresCop > 0) coef(preuve.first, preuve.second, 4) else null,
            nAvant = lignes.count { it.etat == Etat.AVANT },
            nApres = nApres,
            nSans = nSans,
            nApresCopieux = nApresCop,
            nApresNormal = nApres - nApresCop,
            partCopieux = if (toutesLesVeilles.isEmpty()) 0.5 else toutesLesVeilles.count { it }.toDouble() / toutesLesVeilles.size,
            sigma = sigma,
        )
    }

    /** Modèle de départ, sans aucune donnée (l'a priori seul). */
    val A_PRIORI: Modele get() = estimer(emptyList(), emptyList())

    /** Correction d'un jour, d'après le journal du jour et de la veille. */
    fun correction(m: Modele, jour: EntreeJour?, veille: EntreeJour?): Correction? =
        m.correction(etat(jour), copieux(veille))

    /** Matins de la régression, par date : le matin et la veille sont lus dans [entree]. */
    fun lignes(
        ecarts: Map<LocalDate, Double>,
        entree: (LocalDate) -> EntreeJour?,
    ): List<Ligne> = ecarts.mapNotNull { (d, e) ->
        val etat = etat(entree(d)) ?: return@mapNotNull null
        val c = copieux(entree(d.minusDays(1))) ?: return@mapNotNull null
        Ligne(e, etat, c)
    }
}
