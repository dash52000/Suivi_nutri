package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.repas.BiaisContexte
import io.github.dash52000.suivinutri.repas.Moment
import io.github.dash52000.suivinutri.repas.NiveauBiais
import io.github.dash52000.suivinutri.repas.Observation
import io.github.dash52000.suivinutri.repas.Origine
import io.github.dash52000.suivinutri.repas.RepasObserve
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.IdentityHashMap
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/*
 * Biais par contexte (lot v1.18 B, étape 3 ; archi section 6, `suivi-nutri.md` 4.8). Aucune dépendance Android.
 *
 * Le modèle ne connaît que `RepasObserve`, les pesées retenues et la dépense de la montre
 * (`Labo.observationsBalance`). Vraisemblance journalière sur les pesées (décision du 26/11, remplace la
 * vraisemblance hebdomadaire sur les `SemaineApports`, dont les erreurs étaient lissées et corrélées) :
 *
 *   niveau du matin t+1 = niveau du matin t + (apport_t − dépense_t) / ρ + η_t        η_t ~ N(0 ; q)
 *   apport_t = f_t(θ) + ε_t,   f_t(θ) = Σ repas décrits ĉ·e^b + Σ repas non décrits m_cellule·e^b + u
 *   pesée du matin t = niveau_t + e_t                                                 e_t ~ N(0 ; variance du Labo)
 *   b(repas) = β + δ_moment (midi, soir) + δ_contexte                                 (kcal vrais = ĉ · e^b)
 *
 *  - ε_t : marges des phrases, variabilité des repas non décrits, variabilité jour à jour de u (indépendants d'un jour
 *    à l'autre), plus l'erreur sur la moyenne de chaque cellule d'imputation, partagée par tous les jours qui s'en
 *    servent ;
 *  - le niveau du premier matin, L0, est un paramètre (a priori large autour de la première pesée) ;
 *  - les pesées sont donc gaussiennes de moyenne μ(θ) et de covariance Σ exacte (marche aléatoire + cellules + bruit
 *    de pesée) : toutes les corrélations entre jours sont prises en compte ;
 *  - « Rien » = 0 observé ; « Comme d'hab » = l'habitude du matin, traitée comme un repas décrit ;
 *  - un repas non décrit prend la moyenne de sa cellule (moment × contexte × soir chargé ou non), moyenne de Hajek
 *    (poids 1/π) des repas observés par tirage ou chargés ; repli sur le moment, puis sur une « portion normale » ;
 *  - un moment absent des données (jour incomplet) est traité comme non décrit ;
 *  - u = ce que la balance voit en plus (kcal/j, grignotage caché et biais moyen de la montre compris).
 *
 * A priori sceptiques centrés sur zéro (archi 6, valeurs de départ) : β ~ N(0 ; 0,2²), δ ~ N(0 ; 0,1²), u ~ N(0 ; 300²).
 * Estimation : Gauss-Newton jusqu'au maximum a posteriori (Σ recalculée à chaque tour sans être dérivée :
 * quasi-vraisemblance), covariance = inverse de la hessienne (approximation de Laplace).
 */

/** Une pesée retenue par le Labo : corrigée de l'eau et de la selle, avec sa variance (kg²). */
data class PeseeJour(val date: LocalDate, val kg: Double, val variance: Double)

/** Pesées retenues et dépense retenue de chaque jour (kcal), lues par `Labo.observationsBalance`. */
class ObservationsBalance(val pesees: List<PeseeJour>, val depenses: Map<LocalDate, Double>)

/** « Portion normale » d'un moment, tant qu'aucun repas du moment n'a été observé (prior large). */
data class PortionDefaut(val moyenne: Double, val ecartType: Double)

data class HypothesesBiais(
    val sigmaBeta: Double = 0.2,
    val sigmaDeltaMoment: Double = 0.1,
    val sigmaDeltaContexte: Double = 0.1,
    /** kcal/j. */
    val sigmaU: Double = 300.0,
    /** Variabilité d'un jour à l'autre du non expliqué (grignotage caché), kcal/j. */
    val sigmaUJour: Double = 75.0,
    /** Variance d'un jour du niveau hors apports (kg²/j) : eau non corrigée, erreurs de la montre. */
    val qNiveau: Double = 0.0004,
    /** Jour sans dépense retenue : dépense moyenne de la fenêtre, avec cet écart-type (kcal). */
    val sigmaDepenseManquante: Double = 500.0,
    /** A priori du niveau du premier matin autour de la première pesée (kg). */
    val sigmaL0: Double = 2.0,
    /** Fenêtre d'estimation (jours) : les repas plus anciens n'entrent pas. */
    val joursMax: Int = 364,
    val portionsDefaut: Map<Moment, PortionDefaut> = mapOf(
        Moment.MATIN to PortionDefaut(450.0, 100.0),
        Moment.MIDI to PortionDefaut(750.0, 300.0),
        Moment.SOIR to PortionDefaut(900.0, 350.0),
        // Un grignotage non observé vaut zéro dans la partie connue : la balance le range dans u.
        Moment.GRIGNOTAGE to PortionDefaut(0.0, 150.0),
    ),
    /** Observations minimum pour qu'une cellule serve à imputer ; en dessous, repli sur le niveau du dessus. */
    val minCellule: Int = 2,
    /** Poids (en observations) de la portion normale dans la variance d'une cellule : stabilise les petites cellules. */
    val pseudoObservations: Double = 1.0,
    val nTirages: Int = 4000,
    val graine: Long = 20261126L,
)

/** Ce qu'on sait des repas d'une cellule : moyenne de Hajek (poids 1/π), écart-type d'un repas, erreur sur la moyenne. */
class Resume(val moyenne: Double, val ecartType: Double, val seMoyenne: Double, val n: Int)

/**
 * Cellules d'imputation, construites sur les repas observés par tirage ou chargés (« décrit » hors phrases spontanées,
 * « Comme d'hab », « Rien »). Clé : (moment, contexte, soir chargé). Les phrases spontanées n'entrent pas : leur
 * choix n'est pas aléatoire (bloc 3 des simulations).
 */
class Cellules(repas: List<RepasObserve>, private val hyp: HypothesesBiais) {

    private val table = HashMap<Triple<Moment, String?, Boolean?>, Resume>()

    init {
        val retenus = repas.filter {
            when (it.observation) {
                Observation.DECRIT -> it.origine != Origine.SPONTANEE
                Observation.COMME_DHAB, Observation.RIEN -> true
                Observation.NON_DECRIT -> false
            }
        }
        fun ajouter(cle: Triple<Moment, String?, Boolean?>, liste: List<RepasObserve>) {
            resumer(liste, hyp.portionsDefaut.getValue(cle.first))?.let { table[cle] = it }
        }
        retenus.groupBy { Triple(it.moment, it.contexte as String?, charge(it)) }.forEach { (c, l) -> ajouter(c, l) }
        retenus.groupBy { Triple(it.moment, null as String?, charge(it) as Boolean?) }.forEach { (c, l) -> ajouter(c, l) }
        retenus.groupBy { Triple(it.moment, null as String?, null as Boolean?) }.forEach { (c, l) -> ajouter(c, l) }
    }

    /** Cellule observée la plus proche : contexte, puis moment (même statut), puis moment ; null si aucune n'a assez de repas. */
    fun observee(moment: Moment, contexte: String, charge: Boolean): Resume? {
        val min = hyp.minCellule
        return table[Triple(moment, contexte as String?, charge as Boolean?)]?.takeIf { it.n >= min }
            ?: table[Triple(moment, null as String?, charge as Boolean?)]?.takeIf { it.n >= min }
            ?: table[Triple(moment, null as String?, null as Boolean?)]?.takeIf { it.n >= min }
    }

    /** Cellule d'imputation d'un repas non décrit : la cellule observée, sinon la portion normale du moment (prior large). */
    fun trouver(moment: Moment, contexte: String, charge: Boolean): Resume =
        observee(moment, contexte, charge)
            ?: hyp.portionsDefaut.getValue(moment).let { Resume(it.moyenne, it.ecartType, it.ecartType, 0) }

    private fun resumer(liste: List<RepasObserve>, defaut: PortionDefaut): Resume? {
        if (liste.isEmpty()) return null
        val v = liste.map { it.kcal ?: 0.0 }
        val w = liste.map { 1.0 / (it.pi ?: 1.0) }
        val sw = w.sum()
        val sw2 = w.sumOf { it * it }
        val m = v.indices.sumOf { w[it] * v[it] } / sw
        val n = liste.size
        val denominateur = sw - sw2 / sw
        // Variance des ĉ (pondérée), ramenée vers la portion normale par une pseudo-observation : stabilise n petit.
        val s2 = if (n >= 2 && denominateur > 1e-12) v.indices.sumOf { w[it] * (v[it] - m) * (v[it] - m) } / denominateur else defaut.ecartType * defaut.ecartType
        val k0 = hyp.pseudoObservations
        val s2c = ((n - 1).coerceAtLeast(0) * s2 + k0 * defaut.ecartType * defaut.ecartType) / (n - 1 + k0).coerceAtLeast(1e-9)
        // Part de cette variance due aux marges des phrases (le repas vrai varie moins que son estimation). Bruit
        // log-normal de moyenne 1 : E[ĉ²] = E[vrai²]·e^{σ²}, d'où un bruit de E[ĉ²]·(1 − e^{−σ²}).
        val bruit = liste.indices.sumOf { w[it] * v[it] * v[it] * (1.0 - exp(-(liste[it].sigma ?: 0.0).let { s -> s * s })) } / sw
        val s2t = max(s2c - bruit, (0.1 * m) * (0.1 * m))
        val nEff = sw * sw / sw2
        return Resume(m, sqrt(s2t), sqrt(s2c / nEff), n)
    }

    companion object {
        /** Un soir chargé est tiré à π = 1 ; tous les autres moments sont tirés au sort. */
        fun charge(r: RepasObserve): Boolean = r.moment == Moment.SOIR && (r.pi ?: 0.0) >= 1.0
    }
}


/** Un repas tel que le modèle le lit. */
class RepasModele(
    val source: RepasObserve,
    /** 0 midi, 1 soir, −1 autres. */
    val iMoment: Int,
    /** Indice du δ de son contexte, −1 si le contexte n'a pas de paramètre (matin, grignotage, contexte inconnu). */
    val iCtx: Int,
    /** ĉ (repas décrit, « Comme d'hab ») ou moyenne de la cellule (non décrit) ; 0 pour « Rien ». */
    val valeur: Double,
    /** Variance de `valeur` à b = 0 (kcal²) : marge de la phrase, ou variabilité d'un repas de la cellule. */
    val varBase: Double,
    /** Cellule d'imputation (repas non décrit), sinon null. */
    val cellule: Resume?,
) {
    val rien: Boolean get() = valeur == 0.0 && varBase == 0.0
}

/** Un jour de la fenêtre : ses repas (un par moment), sa dépense (kcal) et la variance de celle-ci. */
class JourModele(val date: LocalDate, val repas: List<RepasModele>, val depense: Double, val varDepense: Double)

/** Pesée du matin t : t = 0 avant les repas du premier jour, t = T le lendemain du dernier. */
class PeseeModele(val t: Int, val kg: Double, val variance: Double)

/** Tout ce qui dépend de θ, calculé pour un θ donné. T jours, N pesées, p paramètres, G cellules. */
class EtatModele(
    /** [T] apport attendu du jour (kcal). */
    val f: DoubleArray,
    /** [T][4] apport attendu par moment ; u est rangé dans le grignotage. */
    val fMoment: Array<DoubleArray>,
    /** [T][p] dérivées de f. */
    val jacF: Array<DoubleArray>,
    /** [T] variance de l'apport du jour, part indépendante d'un jour à l'autre (kcal²). */
    val vI: DoubleArray,
    val vIMoment: Array<DoubleArray>,
    /** [T][G] Σ e^b des repas non décrits de la cellule g. */
    val c: Array<DoubleArray>,
    /** [T+1][G] cumul de c avant le matin t. */
    val cCumul: Array<DoubleArray>,
    /** [N] moyenne des pesées, [N][p] ses dérivées, [N][N] covariance. */
    val mu: DoubleArray,
    val jacMu: Array<DoubleArray>,
    val sigma: Array<DoubleArray>,
)

/**
 * Paramètres : [0] β ; [1] δ midi ; [2] δ soir ; [3 … 3+K−1] δ des K contextes midi et soir ; [3+K] u en milliers de
 * kcal/j (échelle interne : garde la hessienne bien conditionnée) ; [4+K] L0, niveau du premier matin (kg).
 */
class ModeleBiais(
    val jours: List<JourModele>,
    val pesees: List<PeseeModele>,
    val etiquettes: List<String>,
    val hyp: HypothesesBiais,
    /** Moyenne a priori de L0 (kg). */
    val l0: Double,
) {
    val nCtx = etiquettes.size
    val nParam = 5 + nCtx
    val iU = 3 + nCtx
    val iL0 = 4 + nCtx
    val nJours = jours.size

    /** Cellules d'imputation utilisées, dans l'ordre d'apparition ; leur moment. */
    val cellules: List<Resume>
    val momentCellule: IntArray
    private val iCellule = IdentityHashMap<Resume, Int>()

    init {
        val liste = ArrayList<Resume>()
        val moments = ArrayList<Int>()
        for (j in jours) for (r in j.repas) {
            val c = r.cellule ?: continue
            if (!iCellule.containsKey(c)) { iCellule[c] = liste.size; liste += c; moments += r.source.moment.ordinal }
        }
        cellules = liste
        momentCellule = moments.toIntArray()
    }

    fun indiceCellule(c: Resume): Int = iCellule.getValue(c)

    /** Écart-types et moyennes des a priori, dans l'échelle interne. */
    val sigmaPrior: DoubleArray = DoubleArray(nParam) { j ->
        when {
            j == 0 -> hyp.sigmaBeta
            j <= 2 -> hyp.sigmaDeltaMoment
            j == iU -> hyp.sigmaU / ECHELLE_U
            j == iL0 -> hyp.sigmaL0
            else -> hyp.sigmaDeltaContexte
        }
    }
    val moyennePrior: DoubleArray = DoubleArray(nParam) { if (it == iL0) l0 else 0.0 }

    fun biais(theta: DoubleArray, r: RepasModele): Double =
        theta[0] + (if (r.iMoment >= 0) theta[1 + r.iMoment] else 0.0) + (if (r.iCtx >= 0) theta[3 + r.iCtx] else 0.0)

    fun etat(theta: DoubleArray): EtatModele {
        val t = nJours
        val p = nParam
        val g = cellules.size
        val f = DoubleArray(t)
        val fMoment = Array(t) { DoubleArray(Moment.entries.size) }
        val jacF = Array(t) { DoubleArray(p) }
        val vI = DoubleArray(t)
        val vIMoment = Array(t) { DoubleArray(Moment.entries.size) }
        val c = Array(t) { DoubleArray(g) }
        val grignotage = Moment.GRIGNOTAGE.ordinal
        val uJour = hyp.sigmaUJour * hyp.sigmaUJour
        for (s in 0 until t) {
            for (r in jours[s].repas) {
                if (r.rien) continue
                val m = r.source.moment.ordinal
                val e = exp(biais(theta, r))
                val k = r.valeur * e
                f[s] += k
                fMoment[s][m] += k
                jacF[s][0] += k
                if (r.iMoment >= 0) jacF[s][1 + r.iMoment] += k
                if (r.iCtx >= 0) jacF[s][3 + r.iCtx] += k
                val v = e * e * r.varBase
                vI[s] += v
                vIMoment[s][m] += v
                r.cellule?.let { c[s][iCellule.getValue(it)] += e }
            }
            val u = ECHELLE_U * theta[iU]
            f[s] += u
            fMoment[s][grignotage] += u
            jacF[s][iU] = ECHELLE_U
            vI[s] += uJour
            vIMoment[s][grignotage] += uJour
        }
        // Cumuls jusqu'au matin t (t = 0 … T).
        val rho = Hall.RHO
        val niveau = DoubleArray(t + 1)
        val jacNiveau = Array(t + 1) { DoubleArray(p) }
        val d = DoubleArray(t + 1)
        val cCumul = Array(t + 1) { DoubleArray(g) }
        niveau[0] = theta[iL0]
        jacNiveau[0][iL0] = 1.0
        for (s in 0 until t) {
            niveau[s + 1] = niveau[s] + (f[s] - jours[s].depense) / rho
            for (j in 0 until p) jacNiveau[s + 1][j] = jacNiveau[s][j] + jacF[s][j] / rho
            d[s + 1] = d[s] + (vI[s] + jours[s].varDepense) / (rho * rho) + hyp.qNiveau
            for (k in 0 until g) cCumul[s + 1][k] = cCumul[s][k] + c[s][k]
        }
        val n = pesees.size
        val mu = DoubleArray(n) { niveau[pesees[it].t] }
        val jacMu = Array(n) { jacNiveau[pesees[it].t].copyOf() }
        val se2 = DoubleArray(g) { cellules[it].seMoyenne * cellules[it].seMoyenne / (rho * rho) }
        val sigma = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in 0..i) {
            // Pesées triées par jour : min(t_i, t_j) = t_j.
            var v = d[pesees[j].t]
            val ci = cCumul[pesees[i].t]
            val cj = cCumul[pesees[j].t]
            for (k in 0 until g) v += se2[k] * ci[k] * cj[k]
            if (i == j) v += pesees[i].variance
            sigma[i][j] = v
            sigma[j][i] = v
        }
        return EtatModele(f, fMoment, jacF, vI, vIMoment, c, cCumul, mu, jacMu, sigma)
    }

    /** Moyenne des pesées seulement (recherche linéaire). */
    fun moyennes(theta: DoubleArray): DoubleArray {
        val rho = Hall.RHO
        val niveau = DoubleArray(nJours + 1)
        niveau[0] = theta[iL0]
        for (s in 0 until nJours) {
            var f = ECHELLE_U * theta[iU]
            for (r in jours[s].repas) if (!r.rien) f += r.valeur * exp(biais(theta, r))
            niveau[s + 1] = niveau[s] + (f - jours[s].depense) / rho
        }
        return DoubleArray(pesees.size) { niveau[pesees[it].t] }
    }

    /** Moins deux fois le log de l'a posteriori, à covariance donnée (facteur de Cholesky [l]). */
    fun critere(theta: DoubleArray, l: Array<DoubleArray>): Double {
        val mu = moyennes(theta)
        val x = BiaisRepas.descente(l, DoubleArray(pesees.size) { pesees[it].kg - mu[it] })
        var q = x.sumOf { it * it }
        for (j in 0 until nParam) q += ((theta[j] - moyennePrior[j]) / sigmaPrior[j]).let { it * it }
        return q
    }

    companion object {
        /** kcal/j par unité du paramètre u. */
        const val ECHELLE_U = 1000.0
    }
}

/** Résultat de l'estimation : maximum a posteriori, covariance de Laplace, et ce qu'il faut pour conditionner les apports. */
class ResultatBiais(
    val modele: ModeleBiais,
    val theta: DoubleArray,
    val covariance: Array<DoubleArray>,
    val cellules: Cellules,
    val aujourdhui: LocalDate,
    val iterations: Int,
    /** État au maximum a posteriori. */
    val etat: EtatModele,
    /** Facteur de Cholesky de Σ, Σ⁻¹ (pesées − μ), L⁻¹ ∂μ/∂θ. */
    val choleskySigma: Array<DoubleArray>,
    val residusNormes: DoubleArray,
    val jacMuNormee: Array<DoubleArray>,
) {
    val nJours: Int get() = modele.nJours
    val nPesees: Int get() = modele.pesees.size

    val beta: Double get() = theta[0]
    val sdBeta: Double get() = sqrt(covariance[0][0])

    /** u, kcal/j. */
    val u: Double get() = theta[modele.iU] * ModeleBiais.ECHELLE_U
    val sdU: Double get() = sqrt(covariance[modele.iU][modele.iU]) * ModeleBiais.ECHELLE_U

    /** Combinaison linéaire des paramètres : moyenne et écart-type a posteriori. */
    fun combinaison(poids: DoubleArray): Pair<Double, Double> {
        val p = modele.nParam
        var m = 0.0
        var v = 0.0
        for (i in 0 until p) {
            m += poids[i] * theta[i]
            for (j in 0 until p) v += poids[i] * covariance[i][j] * poids[j]
        }
        return m to sqrt(max(0.0, v))
    }

    /** β ± 1,96 écart-type. */
    fun intervalleBeta(): Intervalle = Intervalle(beta, beta - Stats.Z975 * sdBeta, beta + Stats.Z975 * sdBeta)

    /**
     * Écart global (décision du 26/11, option A) : ce que la balance voit en plus (ou en moins) de ce que décrivent les
     * repas, en kcal/j, en moyenne sur les [nJours] derniers jours de la fenêtre (toute la fenêtre par défaut).
     * = moyenne de f_t(θ) − f_t(0) : phrases et moyennes de contexte corrigées des biais, plus u, moins les mêmes sans
     * correction. C'est la seule part identifiable : la balance ne voit que le total, elle ne sépare pas un biais
     * proportionnel des phrases (β) d'un apport non dit (u). β et u restent internes ; l'appli n'annonce que cet écart.
     * Intervalle à 95 % par la méthode delta.
     */
    fun ecartGlobal(nJours: Int = modele.nJours): Intervalle {
        val m = modele
        val debut = (m.nJours - nJours).coerceAtLeast(0)
        val n = (m.nJours - debut).toDouble()
        val brut = m.etat(DoubleArray(m.nParam).also { it[m.iL0] = theta[m.iL0] })
        var ecart = 0.0
        val grad = DoubleArray(m.nParam)
        for (s in debut until m.nJours) {
            ecart += (etat.f[s] - brut.f[s]) / n
            for (j in 0 until m.nParam) grad[j] += etat.jacF[s][j] / n
        }
        var v = 0.0
        for (i in 0 until m.nParam) for (j in 0 until m.nParam) v += grad[i] * covariance[i][j] * grad[j]
        val e = Stats.Z975 * sqrt(max(0.0, v))
        return Intervalle(ecart, ecart - e, ecart + e)
    }

    /** Biais appris : global, midi, soir, puis chaque contexte (kcal vrais = ĉ × exp(β)). */
    fun biais(): List<BiaisContexte> {
        val m = modele
        val actifs = m.jours.flatMap { it.repas }.filter { !it.rien }
        val resultat = ArrayList<BiaisContexte>()
        fun ajouter(niveau: NiveauBiais, nom: String, poids: DoubleArray, n: Int) {
            val (moyenne, sd) = combinaison(poids)
            resultat += BiaisContexte(niveau, nom, moyenne, sd, n, aujourdhui)
        }
        fun e(vararg indices: Int) = DoubleArray(m.nParam).also { v -> indices.forEach { v[it] = 1.0 } }
        ajouter(NiveauBiais.GLOBAL, "global", e(0), actifs.size)
        ajouter(NiveauBiais.MIDI_SOIR, "midi", e(0, 1), actifs.count { it.iMoment == 0 })
        ajouter(NiveauBiais.MIDI_SOIR, "soir", e(0, 2), actifs.count { it.iMoment == 1 })
        for ((k, nom) in m.etiquettes.withIndex()) {
            val moment = if (nom.startsWith("${Moment.MIDI.code}:")) 1 else 2
            ajouter(NiveauBiais.CONTEXTE, nom, e(0, moment, 3 + k), actifs.count { it.iCtx == k })
        }
        return resultat
    }

    /** Tirages dans la loi normale de Laplace, graine fixe : même résultat à chaque appel. */
    fun tirages(n: Int = modele.hyp.nTirages, graine: Long = modele.hyp.graine): List<DoubleArray> {
        val l = BiaisRepas.cholesky(covariance)
        val alea = Random(graine)
        val p = modele.nParam
        return List(n) {
            val z = DoubleArray(p) { alea.nextGaussian() }
            DoubleArray(p) { i ->
                var s = theta[i]
                for (j in 0..i) s += l[i][j] * z[j]
                s
            }
        }
    }

    /** Un repas tel que le modèle le lit (valeur, variance, indices des paramètres). */
    fun lire(r: RepasObserve): RepasModele = BiaisRepas.lire(r, cellules, modele.etiquettes, modele.hyp)
}

object BiaisRepas {

    /**
     * Estime les biais à partir des [repas] et de la balance [obs] (`Labo.observationsBalance`). Fenêtre : du premier
     * jour avec un repas (au plus [HypothesesBiais.joursMax] jours avant [aujourdhui]) au dernier. Null sans repas,
     * sans dépense, avec moins de 2 pesées dans la fenêtre, ou si la hessienne est singulière.
     */
    fun estimer(
        repas: List<RepasObserve>,
        obs: ObservationsBalance,
        aujourdhui: LocalDate,
        hyp: HypothesesBiais = HypothesesBiais(),
    ): ResultatBiais? {
        val debutMax = aujourdhui.minusDays(hyp.joursMax.toLong())
        val fenetre = repas.filter { !it.jour.isBefore(debutMax) && !it.jour.isAfter(aujourdhui) }
        if (fenetre.isEmpty()) return null
        val d0 = fenetre.minOf { it.jour }
        val nJours = ChronoUnit.DAYS.between(d0, fenetre.maxOf { it.jour }).toInt() + 1
        val dates = (0 until nJours).map { d0.plusDays(it.toLong()) }
        val connues = dates.mapNotNull { obs.depenses[it] }
        if (connues.isEmpty()) return null
        val depenseMoyenne = connues.average()

        val parJour = fenetre.groupBy { it.jour }
        // Un moment absent est traité comme non décrit, sans contexte (repli sur la cellule du moment).
        val complets = dates.associateWith { d ->
            val l = parJour[d].orEmpty()
            l + Moment.entries.filter { m -> l.none { it.moment == m } }.map { RepasObserve.nonDecrit(d, it, CONTEXTE_INCONNU) }
        }
        val cellules = Cellules(fenetre, hyp)
        val etiquettes = fenetre.filter { (it.moment == Moment.MIDI || it.moment == Moment.SOIR) && it.contexte != CONTEXTE_INCONNU }
            .map { etiquette(it) }.distinct().sortedWith(compareBy({ !it.startsWith("${Moment.MIDI.code}:") }, { it }))

        val jours = dates.map { d ->
            val dep = obs.depenses[d]
            JourModele(
                d, complets.getValue(d).map { lire(it, cellules, etiquettes, hyp) },
                dep ?: depenseMoyenne, if (dep == null) hyp.sigmaDepenseManquante * hyp.sigmaDepenseManquante else 0.0,
            )
        }
        val pesees = obs.pesees.mapNotNull { p ->
            val t = ChronoUnit.DAYS.between(d0, p.date).toInt()
            if (t in 0..nJours) PeseeModele(t, p.kg, p.variance) else null
        }.sortedBy { it.t }
        if (pesees.size < 2) return null

        val modele = ModeleBiais(jours, pesees, etiquettes, hyp, pesees.first().kg)
        return maximumAPosteriori(modele, cellules, aujourdhui)
    }

    const val CONTEXTE_INCONNU = ""

    fun etiquette(r: RepasObserve) = "${r.moment.code}:${r.contexte}"

    fun lire(r: RepasObserve, cellules: Cellules, etiquettes: List<String>, hyp: HypothesesBiais): RepasModele {
        val iMoment = when (r.moment) { Moment.MIDI -> 0; Moment.SOIR -> 1; else -> -1 }
        val iCtx = if (iMoment >= 0) etiquettes.indexOf(etiquette(r)) else -1
        return when (r.observation) {
            Observation.DECRIT, Observation.COMME_DHAB -> {
                val c = r.kcal!!
                val s = r.sigma!!
                val cell = cellules.observee(r.moment, r.contexte, Cellules.charge(r))
                if (cell == null) {
                    // Contexte jamais observé : la phrase telle quelle, avec sa marge.
                    RepasModele(r, iMoment, iCtx, c, c * c * (exp(s * s) - 1.0), null)
                } else {
                    // Rétrécissement vers la moyenne de la cellule (moyenne a posteriori du repas vrai sachant sa
                    // phrase) : l'écart d'une phrase à la moyenne de son contexte est en partie du bruit d'estimation.
                    // Sans cela, la pente « balance contre prédiction » est atténuée (régression vers la moyenne).
                    val varVrai = cell.ecartType * cell.ecartType
                    val varBruit = cell.moyenne * cell.moyenne * (exp(s * s) - 1.0)
                    val poids = varVrai / (varVrai + varBruit)
                    RepasModele(r, iMoment, iCtx, cell.moyenne + poids * (c - cell.moyenne), poids * varBruit, null)
                }
            }
            Observation.RIEN -> RepasModele(r, iMoment, iCtx, 0.0, 0.0, null)
            Observation.NON_DECRIT -> {
                val cell = cellules.trouver(r.moment, r.contexte, Cellules.charge(r))
                RepasModele(r, iMoment, iCtx, cell.moyenne, cell.ecartType * cell.ecartType, cell)
            }
        }
    }

    private class Systeme(
        val etat: EtatModele,
        val l: Array<DoubleArray>,
        val x: DoubleArray,
        val xJac: Array<DoubleArray>,
        val h: Array<DoubleArray>,
        val g: DoubleArray,
    )

    /** Hessienne de Gauss-Newton et gradient (−½ ∇ du critère) au point [theta], Σ fixée en ce point. */
    private fun systeme(m: ModeleBiais, theta: DoubleArray): Systeme? {
        val etat = m.etat(theta)
        val l = choleskyStrict(etat.sigma) ?: return null
        val n = m.pesees.size
        val p = m.nParam
        val x = descente(l, DoubleArray(n) { m.pesees[it].kg - etat.mu[it] })
        // L⁻¹ ∂μ/∂θ, colonne par colonne, rangé [N][p].
        val xJac = Array(n) { DoubleArray(p) }
        for (j in 0 until p) {
            val col = descente(l, DoubleArray(n) { etat.jacMu[it][j] })
            for (i in 0 until n) xJac[i][j] = col[i]
        }
        val h = Array(p) { i -> DoubleArray(p) { j -> if (i == j) 1.0 / (m.sigmaPrior[i] * m.sigmaPrior[i]) else 0.0 } }
        val g = DoubleArray(p) { -(theta[it] - m.moyennePrior[it]) / (m.sigmaPrior[it] * m.sigmaPrior[it]) }
        for (i in 0 until n) {
            val ligne = xJac[i]
            for (a in 0 until p) {
                g[a] += ligne[a] * x[i]
                for (b in 0 until p) h[a][b] += ligne[a] * ligne[b]
            }
        }
        return Systeme(etat, l, x, xJac, h, g)
    }

    private fun maximumAPosteriori(m: ModeleBiais, cellules: Cellules, aujourdhui: LocalDate): ResultatBiais? {
        val p = m.nParam
        var theta = m.moyennePrior.copyOf()
        var tours = 0
        while (tours < 100) {
            tours++
            val sys = systeme(m, theta) ?: return null
            val inv = Stats.inverser(sys.h) ?: return null
            val pas = DoubleArray(p) { i -> (0 until p).sumOf { j -> inv[i][j] * sys.g[j] } }
            val q0 = m.critere(theta, sys.l)
            var t = 1.0
            var suivant: DoubleArray? = null
            while (t > 1e-6) {
                val essai = DoubleArray(p) { theta[it] + t * pas[it] }
                if (m.critere(essai, sys.l) <= q0) { suivant = essai; break }
                t /= 2
            }
            if (suivant == null) break
            val deplacement = (0 until p).maxOf { abs(suivant[it] - theta[it]) / m.sigmaPrior[it] }
            theta = suivant
            if (deplacement < 1e-7) break
        }
        val sys = systeme(m, theta) ?: return null
        val covariance = Stats.inverser(sys.h) ?: return null
        val w = remontee(sys.l, sys.x)
        return ResultatBiais(m, theta, covariance, cellules, aujourdhui, tours, sys.etat, sys.l, w, sys.xJac)
    }

    /** Facteur de Cholesky inférieur d'une matrice symétrique définie positive ; null si elle ne l'est pas. */
    fun choleskyStrict(a: Array<DoubleArray>): Array<DoubleArray>? {
        val n = a.size
        val l = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in 0..i) {
            var s = a[i][j]
            val li = l[i]
            val lj = l[j]
            for (k in 0 until j) s -= li[k] * lj[k]
            if (i == j) {
                if (s <= 0.0) return null
                li[j] = sqrt(s)
            } else li[j] = s / lj[j]
        }
        return l
    }

    /** Résout L x = b (L triangulaire inférieure). */
    fun descente(l: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val x = DoubleArray(n)
        for (i in 0 until n) {
            var s = b[i]
            val li = l[i]
            for (k in 0 until i) s -= li[k] * x[k]
            x[i] = s / li[i]
        }
        return x
    }

    /** Résout Lᵀ x = b. */
    fun remontee(l: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = b[i]
            for (k in i + 1 until n) s -= l[k][i] * x[k]
            x[i] = s / l[i][i]
        }
        return x
    }

    /** Facteur de Cholesky inférieur d'une matrice symétrique définie positive (de petites valeurs propres négatives sont ramenées à zéro). */
    fun cholesky(a: Array<DoubleArray>): Array<DoubleArray> {
        val n = a.size
        val l = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in 0..i) {
            var s = a[i][j]
            for (k in 0 until j) s -= l[i][k] * l[j][k]
            if (i == j) l[i][j] = sqrt(max(s, 1e-18)) else l[i][j] = s / l[j][j]
        }
        return l
    }
}
