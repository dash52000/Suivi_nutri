package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.repas.BiaisContexte
import io.github.dash52000.suivinutri.repas.Moment
import io.github.dash52000.suivinutri.repas.NiveauBiais
import io.github.dash52000.suivinutri.repas.Observation
import io.github.dash52000.suivinutri.repas.Origine
import io.github.dash52000.suivinutri.repas.RepasObserve
import java.time.LocalDate
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/*
 * Biais par contexte (lot v1.18 B, étape 3 ; archi section 6, `suivi-nutri.md` 4.8). Aucune dépendance Android.
 *
 * Le modèle ne connaît que `RepasObserve` (+ les `SemaineApports` du Labo). Pour une semaine w de n jours :
 *
 *   apports moyens par jour (balance)  ~  N( f_w(θ) ; V_w )
 *   f_w(θ) = (1/n) · [ Σ repas décrits ĉ · e^b  +  Σ repas non décrits m_cellule · e^b ]  +  u
 *   b(repas) = β + δ_moment (midi, soir) + δ_contexte        (kcal vrais = ĉ · e^b)
 *
 *  - « Rien » = 0 observé ; « Comme d'hab » = l'habitude du matin, traitée comme un repas décrit ;
 *  - un repas non décrit prend la moyenne de sa cellule (moment × contexte × soir chargé ou non), moyenne de Hajek
 *    (poids 1/π) des repas observés par tirage ou chargés ; repli sur le moment, puis sur une « portion normale » ;
 *  - u = ce que la balance voit en plus (kcal/j, grignotage caché compris) ;
 *  - V_w = variance de la balance (IC du Labo) + variance du modèle (marges des phrases, variabilité des repas non
 *    décrits, incertitude de leur moyenne, variabilité jour à jour de u).
 *
 * A priori sceptiques centrés sur zéro (archi 6, valeurs de départ) : β ~ N(0 ; 0,2²), δ ~ N(0 ; 0,1²), u ~ N(0 ; 300²).
 * Estimation : Gauss-Newton jusqu'au maximum a posteriori (variance V_w recalculée à chaque tour, sans la dériver :
 * quasi-vraisemblance), covariance = inverse de la hessienne (approximation de Laplace), tirages à graine fixe.
 *
 * Simplifications connues :
 *  - les erreurs de la balance de deux semaines voisines sont traitées comme indépendantes (elles partagent en
 *    réalité le niveau du dimanche entre les deux : corrélation négative, mesurée par `SimulationApprentissageTest`) ;
 *  - l'erreur sur la moyenne d'une cellule est traitée comme indépendante d'une semaine à l'autre ;
 *  - seules les semaines complètes (dimanche → samedi, quatre moments pour chaque jour) entrent dans l'estimation.
 */

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
        // Part de cette variance due aux marges des phrases (le repas vrai varie moins que son estimation).
        val bruit = liste.indices.sumOf { w[it] * v[it] * v[it] * (exp((liste[it].sigma ?: 0.0).let { s -> s * s }) - 1.0) } / sw
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
    /** Indice du δ de son contexte, −1 si le contexte n'a pas de paramètre (matin, grignotage). */
    val iCtx: Int,
    /** ĉ (repas décrit, « Comme d'hab ») ou moyenne de la cellule (non décrit) ; 0 pour « Rien ». */
    val valeur: Double,
    /** Variance de `valeur` à b = 0 (kcal²) : marge de la phrase, ou variabilité d'un repas de la cellule. */
    val varBase: Double,
    /** Cellule d'imputation (repas non décrit), sinon null. */
    val cellule: Resume?,
)

class SemaineModele(
    val debut: LocalDate,
    val nJours: Int,
    /** Apports moyens par jour estimés par la balance (kcal/j) et son écart-type. */
    val y: Double,
    val se: Double,
    val repas: List<RepasModele>,
) {
    /** Les repas non décrits d'une même cellule partagent l'erreur sur sa moyenne. */
    val groupe: IntArray
    val seGroupe: DoubleArray

    init {
        val index = HashMap<Resume, Int>()
        val ses = ArrayList<Double>()
        groupe = IntArray(repas.size) { i ->
            val c = repas[i].cellule
            if (c == null) -1 else index.getOrPut(c) { ses.add(c.seMoyenne); ses.size - 1 }
        }
        seGroupe = ses.toDoubleArray()
    }
}

class Prediction(val f: Double, val variance: Double, val jac: DoubleArray)

/**
 * Paramètres : [0] β ; [1] δ midi ; [2] δ soir ; [3 … 3+K−1] δ des K contextes midi et soir ; [3+K] u en milliers de kcal/j
 * (échelle interne : garde la hessienne bien conditionnée).
 */
class ModeleBiais(val semaines: List<SemaineModele>, val etiquettes: List<String>, val hyp: HypothesesBiais) {

    val nCtx = etiquettes.size
    val nParam = 4 + nCtx
    val iU = 3 + nCtx

    /** Écart-types des a priori, dans l'échelle interne. */
    val sigmaPrior: DoubleArray = DoubleArray(nParam) { j ->
        when {
            j == 0 -> hyp.sigmaBeta
            j <= 2 -> hyp.sigmaDeltaMoment
            j == iU -> hyp.sigmaU / ECHELLE_U
            else -> hyp.sigmaDeltaContexte
        }
    }

    fun biais(theta: DoubleArray, r: RepasModele): Double =
        theta[0] + (if (r.iMoment >= 0) theta[1 + r.iMoment] else 0.0) + (if (r.iCtx >= 0) theta[3 + r.iCtx] else 0.0)

    fun predire(theta: DoubleArray, s: SemaineModele): Prediction {
        val jac = DoubleArray(nParam)
        var somme = 0.0
        var varRepas = 0.0
        val sommeE = DoubleArray(s.seGroupe.size)
        for (i in s.repas.indices) {
            val r = s.repas[i]
            if (r.valeur == 0.0 && r.varBase == 0.0) continue // « Rien »
            val e = exp(biais(theta, r))
            val k = r.valeur * e
            somme += k
            jac[0] += k
            if (r.iMoment >= 0) jac[1 + r.iMoment] += k
            if (r.iCtx >= 0) jac[3 + r.iCtx] += k
            varRepas += e * e * r.varBase
            val g = s.groupe[i]
            if (g >= 0) sommeE[g] += e
        }
        val n = s.nJours.toDouble()
        for (j in 0 until iU) jac[j] /= n
        jac[iU] = ECHELLE_U
        var varMoyennes = 0.0
        for (g in sommeE.indices) varMoyennes += (sommeE[g] * s.seGroupe[g]).let { it * it }
        val variance = s.se * s.se + (varRepas + varMoyennes) / (n * n) + hyp.sigmaUJour * hyp.sigmaUJour / n
        return Prediction(somme / n + ECHELLE_U * theta[iU], variance, jac)
    }

    /** Moins deux fois le log de l'a posteriori, à variances données (quasi-vraisemblance). */
    fun critere(theta: DoubleArray, variances: DoubleArray): Double {
        var q = 0.0
        for ((i, s) in semaines.withIndex()) {
            val r = s.y - predire(theta, s).f
            q += r * r / variances[i]
        }
        for (j in 0 until nParam) q += (theta[j] / sigmaPrior[j]).let { it * it }
        return q
    }

    companion object {
        /** kcal/j par unité du paramètre u. */
        const val ECHELLE_U = 1000.0
    }
}

/** Résultat de l'estimation : maximum a posteriori, covariance de Laplace, tirages, biais par niveau. */
class ResultatBiais(
    val modele: ModeleBiais,
    val theta: DoubleArray,
    val covariance: Array<DoubleArray>,
    val cellules: Cellules,
    val aujourdhui: LocalDate,
    val nSemaines: Int,
    val nSemainesIgnorees: Int,
    val iterations: Int,
) {
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

    /** Biais appris : global, midi, soir, puis chaque contexte (kcal vrais = ĉ × exp(β)). */
    fun biais(): List<BiaisContexte> {
        val m = modele
        val repas = m.semaines.flatMap { it.repas }
        val resultat = ArrayList<BiaisContexte>()
        fun ajouter(niveau: NiveauBiais, nom: String, poids: DoubleArray, n: Int) {
            val (moyenne, sd) = combinaison(poids)
            resultat += BiaisContexte(niveau, nom, moyenne, sd, n, aujourdhui)
        }
        fun e(vararg indices: Int) = DoubleArray(m.nParam).also { v -> indices.forEach { v[it] = 1.0 } }
        val actifs = repas.filter { !(it.valeur == 0.0 && it.varBase == 0.0) }
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

    /** Un repas tel que le modèle le lit (valeur, variance, indices des paramètres), y compris hors des semaines de l'ajustement. */
    fun lire(r: RepasObserve): RepasModele = BiaisRepas.lire(r, cellules, modele.etiquettes, modele.hyp)
}

object BiaisRepas {

    /**
     * Estime les biais à partir des [repas] (quatre moments par jour) et des apports de la balance [semaines]
     * (`ResultatLabo.semaines`). Null si aucune semaine complète n'a ses quatre moments pour chaque jour, ou si la
     * hessienne est singulière.
     */
    fun estimer(
        repas: List<RepasObserve>,
        semaines: List<SemaineApports>,
        aujourdhui: LocalDate,
        hyp: HypothesesBiais = HypothesesBiais(),
    ): ResultatBiais? {
        val parJour = repas.groupBy { it.jour }
        // Semaines utilisables : complètes (hors semaine en cours) et un repas pour chaque (jour, moment).
        val retenues = ArrayList<SemaineApports>()
        var ignorees = 0
        for (s in semaines) {
            if (s.enCours || s.nbJours < 1) { ignorees++; continue }
            val jours = (0 until s.nbJours).map { s.debut.plusDays(it.toLong()) }
            val complet = jours.all { j -> parJour[j]?.map { it.moment }?.toSet()?.size == Moment.entries.size && parJour[j]!!.size == Moment.entries.size }
            if (!complet || s.apports.haut <= s.apports.bas) { ignorees++; continue }
            retenues += s
        }
        if (retenues.isEmpty()) return null

        val cellules = Cellules(repas, hyp)
        val reperes = retenues.flatMap { s -> (0 until s.nbJours).flatMap { parJour.getValue(s.debut.plusDays(it.toLong())) } }
        val etiquettes = reperes.filter { it.moment == Moment.MIDI || it.moment == Moment.SOIR }
            .map { etiquette(it) }.distinct().sortedWith(compareBy({ !it.startsWith("${Moment.MIDI.code}:") }, { it }))

        val semainesModele = retenues.map { s ->
            val modele = (0 until s.nbJours).flatMap { parJour.getValue(s.debut.plusDays(it.toLong())) }.map { lire(it, cellules, etiquettes, hyp) }
            val se = (s.apports.haut - s.apports.bas) / (2 * Stats.Z975)
            SemaineModele(s.debut, s.nbJours, s.apports.estimation, se, modele)
        }
        val modele = ModeleBiais(semainesModele, etiquettes, hyp)
        val (theta, hessienne, tours) = maximumAPosteriori(modele)
        val covariance = Stats.inverser(hessienne) ?: return null
        return ResultatBiais(modele, theta, covariance, cellules, aujourdhui, retenues.size, ignorees, tours)
    }

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
                    // Sans cela, la pente « balance contre prédiction » est atténuée (régression vers la moyenne) :
                    // le modèle croit voir un biais négatif compensé par un non expliqué positif (simulation 1).
                    val varVrai = cell.ecartType * cell.ecartType
                    val varBruit = (cell.moyenne * s) * (cell.moyenne * s)
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

    private data class Optimum(val theta: DoubleArray, val hessienne: Array<DoubleArray>, val tours: Int)

    private fun maximumAPosteriori(m: ModeleBiais): Optimum {
        val p = m.nParam
        var theta = DoubleArray(p)
        var tours = 0
        while (tours < 100) {
            tours++
            val (h, g, variances) = systeme(m, theta)
            val inv = Stats.inverser(h) ?: break
            val pas = DoubleArray(p) { i -> (0 until p).sumOf { j -> inv[i][j] * g[j] } }
            val q0 = m.critere(theta, variances)
            var t = 1.0
            var suivant: DoubleArray? = null
            while (t > 1e-6) {
                val essai = DoubleArray(p) { theta[it] + t * pas[it] }
                if (m.critere(essai, variances) <= q0) { suivant = essai; break }
                t /= 2
            }
            if (suivant == null) break
            val deplacement = (0 until p).maxOf { abs(suivant[it] - theta[it]) / m.sigmaPrior[it] }
            theta = suivant
            if (deplacement < 1e-9) break
        }
        return Optimum(theta, systeme(m, theta).first, tours)
    }

    /** Hessienne de Gauss-Newton, gradient (−½ ∇ du critère) et variances par semaine, au point [theta]. */
    private fun systeme(m: ModeleBiais, theta: DoubleArray): Triple<Array<DoubleArray>, DoubleArray, DoubleArray> {
        val p = m.nParam
        val h = Array(p) { i -> DoubleArray(p) { j -> if (i == j) 1.0 / (m.sigmaPrior[i] * m.sigmaPrior[i]) else 0.0 } }
        val g = DoubleArray(p) { -theta[it] / (m.sigmaPrior[it] * m.sigmaPrior[it]) }
        val variances = DoubleArray(m.semaines.size)
        for ((w, s) in m.semaines.withIndex()) {
            val pr = m.predire(theta, s)
            variances[w] = pr.variance
            val r = s.y - pr.f
            for (i in 0 until p) {
                g[i] += pr.jac[i] * r / pr.variance
                for (j in 0 until p) h[i][j] += pr.jac[i] * pr.jac[j] / pr.variance
            }
        }
        return Triple(h, g, variances)
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
