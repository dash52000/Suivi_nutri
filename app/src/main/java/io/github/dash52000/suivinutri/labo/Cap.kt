package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.EntreeJour
import io.github.dash52000.suivinutri.data.EtatObjectif
import io.github.dash52000.suivinutri.data.PlanWeekEnd
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Modèle de Hall linéarisé (Sanghvi et al. 2015, AJCN) : dPoids/dt = (effort − ε × ΔPoids) / ρ, où l'effort est
 * l'écart d'apports par rapport au départ (kcal/j, négatif = moins manger). Valeurs moyennes publiées ; l'écart
 * d'une personne à l'autre est d'environ ±5 %.
 */
object Hall {
    /** Densité énergétique effective d'une variation de poids, kcal/kg. */
    const val RHO = 8840.0

    /** Variation de la dépense par kg de poids en plus ou en moins, kcal/j par kg. */
    const val EPS = 25.8

    /** Variation de poids attendue après [jours] d'un effort constant [effort] (kcal/j). */
    fun variation(effort: Double, jours: Double): Double = effort / EPS * (1 - exp(-EPS * jours / RHO))

    /** Effort qui donne, au départ, une pente de [penteSemaine] kg par semaine. */
    fun effortPourPente(penteSemaine: Double): Double = RHO * penteSemaine / 7
}

/** Position d'une semaine par rapport au couloir d'effort. */
enum class Verdict(val libelle: String) {
    DANS_LE_COULOIR("dans le couloir"),
    AU_DESSUS("au-dessus du couloir"),
    PLUS_VITE("plus vite que le couloir"),
    PAS_LISIBLE("pas encore lisible"),

    /** Les [Cap.SEMAINES_RODAGE] premières semaines : la tendance met environ 3 semaines à suivre un changement d'effort. */
    RODAGE("rodage, pas encore jugé"),
}

/** Réflexe tenu sur les soirs renseignés (« à moitié » = 0,5). */
data class Adherence(val tenus: Double, val repondus: Int)

/** Effort et pente sur une fenêtre de jours (IC 95 %). */
data class Mesure(val effort: Intervalle, val pente: Intervalle, val jours: Int)

/**
 * Une semaine vue par l'onglet Cap (v1.11) : du dimanche au samedi pour les comportements ; le poids se lit du
 * dimanche matin au dimanche matin suivant.
 */
data class SemaineCap(
    /** Dimanche qui ouvre la semaine. */
    val debut: LocalDate,
    /** 7 pour une semaine finie, jours écoulés pour la semaine en cours. */
    val nbJours: Int,
    val enCours: Boolean,
    /**
     * Effort et pente sur les 2 semaines qui se ferment ce dimanche-là (7 jours la première semaine). Semaine finie :
     * calculés avec les seules pesées connues à la clôture, donc figés. Semaine en cours : provisoires, dès 5 jours.
     */
    val mesure: Mesure?,
    val nbPesees: Int,
    val verdict: Verdict,
    /** Budget bière de la semaine (équivalent 33 cl) : départ + propositions acceptées (v1.12). */
    val budget: Int,
    /** Bières bues, en équivalent 33 cl ; null si l'alcool n'a été renseigné aucun jour. */
    val bieres: Double?,
    val joursAlcool: Int,
    val levier: String?,
    val adherence: Adherence?,
    val nbSeances: Int,
    val minutesSeances: Long,
    /** Dépense moyenne des jours avec la montre. */
    val depenseMoyenne: Double?,
    /** Pas hors sport moyens des jours finis avec la montre (v1.10), et jours au-dessus de la cible. */
    val pasHorsSport: Double? = null,
    val joursPas: Int = 0,
    val joursCiblePas: Int = 0,
    /** Protéines estimées en g/j, moyenne des jours renseignés (v1.11). */
    val proteines: Double? = null,
    val joursProteines: Int = 0,
) {
    val effort: Intervalle? get() = mesure?.effort
    val pente: Intervalle? get() = mesure?.pente
}

/** Soir de la semaine qui dérape le plus, sur les 8 dernières semaines. */
data class MomentDerape(val jour: DayOfWeek, val derapages: Int, val soirs: Int, val parDefaut: Boolean)

/** Plan du week-end face à ce qui a été saisi dans le questionnaire. */
data class BilanPlan(
    val plan: PlanWeekEnd,
    val bieresPrevues: Int,
    /** Bières bues du vendredi au dimanche (équivalent 33 cl), null si l'alcool n'a été renseigné aucun de ces jours. */
    val bieresBues: Double?,
    val dehorsPrevus: Int,
    val dehorsFaits: Int,
    val repasRenseignes: Int,
    /** Repas prévus « normal » et pris dehors, ou l'inverse. */
    val ecarts: List<String>,
)

data class ResultatCap(
    val debut: LocalDate,
    /** Dimanche de la semaine de départ. */
    val semaineDepart: LocalDate,
    /** Couloir d'effort, kcal/j : [effortBas] (le plus fort) à [effortHaut] (le plus faible). */
    val effortBas: Double,
    val effortHaut: Double,
    /** Tendance au départ (null si pas encore de pesée à cette date). */
    val niveauDepart: Double?,
    val niveauActuel: Double?,
    /** Depuis le départ (au moins 7 jours) : pente moyenne (kg/sem) et effort moyen (kcal/j). */
    val penteDepuisDebut: Intervalle?,
    val effortDepuisDebut: Intervalle?,
    /** Dans l'ordre chronologique ; la dernière est la semaine en cours. */
    val semaines: List<SemaineCap>,
    /** Budget de la semaine en cours. */
    val budgetCourant: Int,
    /** Leviers proposés pour la semaine : les réflexes les moins tenus des 14 derniers jours. */
    val propositions: List<String>,
    /** Médiane des pas hors sport sur les 8 semaines avant le départ, et cible = base + 2 000 (v1.10). */
    val basePas: Double? = null,
    val ciblePas: Double? = null,
) {
    val semaineEnCours: SemaineCap get() = semaines.last()
    val semainesFinies: List<SemaineCap> get() = semaines.filter { !it.enCours }
}

/**
 * Objectif de perte : couloir d'effort (v1.11), budget bière, levier de la semaine, plan du week-end et soir qui
 * dérape. Tout se recalcule à partir du journal, de la tendance de Kalman et de l'[EtatObjectif].
 */
object Cap {

    const val MIN_PESEES = 3
    const val JOURS_PROPOSITIONS = 14L
    const val MIN_REPONSES_PROPOSITION = 3
    const val NB_PROPOSITIONS = 3

    /** Fenêtre de lecture de l'effort : 2 semaines glissantes. */
    const val JOURS_FENETRE = 14

    /** Semaine en cours : effort provisoire affiché à partir de 5 jours écoulés (avant, ± 0,5 kg/sem de bruit). */
    const val JOURS_PROVISOIRE = 5

    /**
     * Rodage : les 2 premières semaines ne sont pas jugées. Avec des verdicts figés, la tendance met environ
     * 3 semaines à suivre un changement d'effort (effort estimé −100, −220 puis −310 kcal/j pour un vrai −400) ;
     * sans rodage, 20 à 35 % de fausses propositions en simulation, 10 % avec 2 semaines.
     */
    const val SEMAINES_RODAGE = 2L

    /** « Au-dessus du couloir » si P(effort au-dessus du haut du couloir) ≥ 90 % (choisi le 11/11/2026). */
    const val Z90 = 1.2815515655446004

    /** Un soir avec au moins 2 unités d'alcool (une Tripel de 33 cl) compte comme un dérapage. */
    const val SEUIL_UNITES = 2.0
    const val SEMAINES_SIGNAL = 8L
    const val MIN_SOIRS_SIGNAL = 3
    const val TAUX_MIN_SIGNAL = 0.5
    const val CL_BOUTEILLE = 33.0

    val STYLES_BIERE = setOf("pils", "blonde", "dubbel", "tripel", "quadrupel")

    /** Levier lu dans Health Connect, pas dans le questionnaire (v1.10). */
    const val LEVIER_PAS = "Journée · pas hors sport ≥ cible"

    /** Dimanche qui ouvre la semaine de [d] (v1.11 : la semaine se ferme à la pesée du dimanche matin). */
    fun debutSemaine(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

    // ---------- Couloir d'effort ----------

    /**
     * Pente de la tendance entre le matin de [debut] et le matin de [debut] + [jours], ramenée à 7 jours, avec
     * l'IC 95 % tiré de la covariance a posteriori du lisseur. Null si la période sort du lissage.
     */
    fun pente(lis: Kalman.Lissage, debut: LocalDate, jours: Int): Intervalle? {
        if (jours < 1) return null
        val i = lis.index(debut) ?: return null
        if (i + jours >= lis.n) return null
        val k = 7.0 / jours
        val delta = (lis.niveau(i + jours) - lis.niveau(i)) * k
        val se = sqrt(lis.varDifference(i, jours)) * k
        return Intervalle(delta, delta - Stats.Z975 * se, delta + Stats.Z975 * se)
    }

    /**
     * Effort (kcal/j) entre le matin de [debut] et le matin de [debut] + [jours], par le modèle de Hall inversé :
     * ρ × variation de la tendance ÷ jours + ε × écart moyen de la tendance au niveau de [depart]. Exact pour un
     * effort constant sur la fenêtre. L'IC ne retient que l'incertitude de la variation (le second terme pèse
     * quelques kcal/j).
     */
    fun mesure(lis: Kalman.Lissage, depart: LocalDate, debut: LocalDate, jours: Int): Mesure? {
        val p = pente(lis, debut, jours) ?: return null
        val i0 = lis.index(depart) ?: return null
        val i = lis.index(debut) ?: return null
        val ecartMoyen = (0..jours).map { lis.niveau(i + it) - lis.niveau(i0) }.average()
        val k = Hall.RHO / 7
        val effort = Intervalle(
            k * p.estimation + Hall.EPS * ecartMoyen,
            k * p.bas + Hall.EPS * ecartMoyen,
            k * p.haut + Hall.EPS * ecartMoyen,
        )
        return Mesure(effort, p, jours)
    }

    /**
     * [bas] : effort le plus fort du couloir (−505 kcal/j), [haut] : le plus faible (−316 kcal/j).
     * Au-dessus : P(effort > haut) ≥ 90 %. Plus vite : IC 95 % entièrement sous [bas]. Dans le couloir : estimation
     * entre les deux. Sinon, ou avec moins de [MIN_PESEES] pesées dans la semaine : pas lisible.
     */
    fun verdict(effort: Intervalle?, nbPesees: Int, bas: Double, haut: Double): Verdict {
        if (effort == null || nbPesees < MIN_PESEES) return Verdict.PAS_LISIBLE
        val se = (effort.haut - effort.estimation) / Stats.Z975
        return when {
            effort.estimation - Z90 * se > haut -> Verdict.AU_DESSUS
            effort.haut < bas -> Verdict.PLUS_VITE
            effort.estimation in bas..haut -> Verdict.DANS_LE_COULOIR
            else -> Verdict.PAS_LISIBLE
        }
    }

    /**
     * Ce que donnerait la règle du budget, semaine après semaine : élément 0 = première semaine, puis un élément de
     * plus par semaine finie. 2 semaines de suite au-dessus du couloir : −1 (minimum 0) ; 2 semaines de suite dans
     * le couloir ou plus vite : +1, sans dépasser [initial] ; une semaine pas lisible remet les compteurs à zéro.
     * En v1.11, le budget reste figé ; cette règle servira aux propositions du bilan du dimanche (v1.12).
     */
    fun budgets(verdicts: List<Verdict>, initial: Int): List<Int> {
        val resultat = mutableListOf(initial)
        var budget = initial
        var dessus = 0
        var dedans = 0
        verdicts.forEach { v ->
            when (v) {
                Verdict.AU_DESSUS -> { dessus++; dedans = 0 }
                Verdict.DANS_LE_COULOIR, Verdict.PLUS_VITE -> { dedans++; dessus = 0 }
                Verdict.PAS_LISIBLE, Verdict.RODAGE -> { dessus = 0; dedans = 0 }
            }
            if (dessus == 2) { budget = maxOf(0, budget - 1); dessus = 0 }
            if (dedans == 2) { budget = minOf(initial, budget + 1); dedans = 0 }
            resultat += budget
        }
        return resultat
    }

    /**
     * Proposition de budget pour la semaine qui commence le dimanche [semaine] (v1.12) : −1 après 2 verdicts
     * « au-dessus » de suite, +1 après 2 verdicts dans le couloir ou plus vite (sans dépasser le départ), null sinon.
     * Seules comptent les semaines finies depuis la dernière réponse ; rien si la semaine a déjà sa réponse.
     */
    fun propositionBudget(semaines: List<SemaineCap>, etat: EtatObjectif, semaine: LocalDate): Int? {
        if (etat.decisionsBudget.containsKey(semaine)) return null
        val derniere = etat.decisionsBudget.keys.filter { it.isBefore(semaine) }.maxOrNull()
        val verdicts = semaines
            .filter { !it.enCours && !it.debut.plusDays(7).isAfter(semaine) && (derniere == null || !it.debut.isBefore(derniere)) }
            .sortedBy { it.debut }
            .map { it.verdict }
        if (verdicts.size < 2) return null
        val deux = verdicts.takeLast(2)
        val budget = etat.budget(semaine)
        return when {
            deux.all { it == Verdict.AU_DESSUS } && budget > 0 -> -1
            deux.all { it == Verdict.DANS_LE_COULOIR || it == Verdict.PLUS_VITE } && budget < etat.budgetInitial -> 1
            else -> null
        }
    }

    /** Première semaine (indice dans [verdicts]) où la règle proposerait une bière de moins, null sinon. */
    fun premiereBaisse(verdicts: List<Verdict>): Int? {
        var dessus = 0
        verdicts.forEachIndexed { k, v ->
            dessus = if (v == Verdict.AU_DESSUS) dessus + 1 else 0
            if (dessus == 2) return k
        }
        return null
    }

    // ---------- Bières et protéines ----------

    /**
     * Bières du jour en équivalent bouteille de 33 cl ; null si l'alcool n'a pas été renseigné, ou si la réponse est
     * « oui » sans boisson saisie (v1.11 : on ne sait pas quoi, ce n'est pas zéro).
     */
    fun bieresEq33(e: EntreeJour?): Double? {
        if (e == null) return null
        return when (e.reponses["alcool"]) {
            "aucun" -> 0.0
            "oui" -> if (e.boissons.isEmpty()) null
            else e.boissons.filter { it.style in STYLES_BIERE }.sumOf { it.cl } / CL_BOUTEILLE
            else -> null
        }
    }

    // ---------- Levier de la semaine ----------

    fun adherence(
        entrees: Map<LocalDate, EntreeJour>,
        dates: List<LocalDate>,
        score: (EntreeJour) -> Double?,
    ): Adherence? {
        val s = dates.mapNotNull { d -> entrees[d]?.let(score) }
        return if (s.isEmpty()) null else Adherence(s.sum(), s.size)
    }

    /** Les réflexes les moins tenus sur les 14 derniers jours (au moins 3 réponses), du moins tenu au plus tenu. */
    fun propositions(
        entrees: Map<LocalDate, EntreeJour>,
        aujourdHui: LocalDate,
        reflexes: List<Pair<String, (EntreeJour) -> Double?>>,
        n: Int = NB_PROPOSITIONS,
        /** Leviers mesurés autrement (les pas) : libellé, taux tenu, nombre de jours. */
        autres: List<Triple<String, Double, Int>> = emptyList(),
    ): List<String> {
        val dates = (0 until JOURS_PROPOSITIONS).map { aujourdHui.minusDays(it) }
        return (reflexes
            .mapNotNull { (libelle, score) ->
                val s = dates.mapNotNull { d -> entrees[d]?.let(score) }
                if (s.size >= MIN_REPONSES_PROPOSITION) Triple(libelle, s.sum() / s.size, s.size) else null
            } + autres.filter { it.third >= MIN_REPONSES_PROPOSITION })
            .sortedWith(compareBy<Triple<String, Double, Int>> { it.second }.thenByDescending { it.third })
            .take(n)
            .map { it.first }
    }

    // ---------- Pas hors sport (v1.10) ----------

    /** Pas hors sport d'un jour fini avec la montre portée (au moins [Pas.MIN_PAS_JOUR] pas), sinon null. */
    fun pasRetenus(j: JourLabo?, aujourdHui: LocalDate): Long? {
        if (j == null || !j.date.isBefore(aujourdHui)) return null
        val h = j.pasHorsSport ?: return null
        return if ((j.pasTotal ?: 0L) >= Pas.MIN_PAS_JOUR) h else null
    }

    /**
     * Médiane des pas hors sport sur les 8 semaines avant [depart] ; à défaut (moins de 14 jours), sur les
     * 8 premières semaines de données. Null avec moins de 14 jours en tout.
     */
    fun basePas(jours: List<JourLabo>, depart: LocalDate, aujourdHui: LocalDate): Double? {
        val retenus = jours.sortedBy { it.date }.mapNotNull { j -> pasRetenus(j, aujourdHui)?.let { j.date to it.toDouble() } }
        val avant = retenus.filter { (d, _) -> d.isBefore(depart) && !d.isBefore(depart.minusWeeks(Pas.SEMAINES_BASE)) }
        if (avant.size >= Pas.MIN_JOURS_BASE) return Pas.mediane(avant.map { it.second })
        val premier = retenus.firstOrNull()?.first ?: return null
        val debut = retenus.filter { (d, _) -> d.isBefore(premier.plusWeeks(Pas.SEMAINES_BASE)) }
        return if (debut.size >= Pas.MIN_JOURS_BASE) Pas.mediane(debut.map { it.second }) else null
    }

    // ---------- Soir qui dérape ----------

    /** Rab, dessert en part normale ou au moins [SEUIL_UNITES] unités d'alcool ; null si rien de tout ça n'est renseigné. */
    fun derapage(e: EntreeJour?): Boolean? {
        if (e == null) return null
        val rab = e.reponses["resservi"]
        val dessert = e.reponses["dessert"]
        val alcool = e.reponses["alcool"]
        if (rab == null && dessert == null && alcool == null) return null
        return rab == "oui" || rab == "un_peu" || dessert == "normale" ||
            (alcool == "oui" && e.unitesAlcool >= SEUIL_UNITES)
    }

    /**
     * Sur les 8 semaines avant [aujourdHui], le jour de la semaine où le soir dérape le plus souvent (au moins
     * 3 soirs renseignés, au moins 50 % de dérapages). Sinon le vendredi, pour le plan du week-end.
     */
    fun momentQuiDerape(entrees: Map<LocalDate, EntreeJour>, aujourdHui: LocalDate): MomentDerape {
        val parJour = DayOfWeek.values().associateWith { intArrayOf(0, 0) } // dérapages, soirs renseignés
        (1L..SEMAINES_SIGNAL * 7).forEach { k ->
            val d = aujourdHui.minusDays(k)
            val r = derapage(entrees[d]) ?: return@forEach
            val c = parJour.getValue(d.dayOfWeek)
            c[1]++
            if (r) c[0]++
        }
        val candidat = parJour
            .filter { (_, c) -> c[1] >= MIN_SOIRS_SIGNAL && c[0].toDouble() / c[1] >= TAUX_MIN_SIGNAL }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<DayOfWeek, IntArray>> { it.value[0].toDouble() / it.value[1] }
                    .thenByDescending { it.value[1] }
                    .thenBy { it.key.value }
            )
            .firstOrNull()
        if (candidat != null) return MomentDerape(candidat.key, candidat.value[0], candidat.value[1], parDefaut = false)
        val ven = parJour.getValue(DayOfWeek.FRIDAY)
        return MomentDerape(DayOfWeek.FRIDAY, ven[0], ven[1], parDefaut = true)
    }

    // ---------- Plan du week-end ----------

    /** Repas pris dehors d'après le questionnaire : midi à l'extérieur, dîner chez les beaux-parents ou ailleurs. */
    fun dehors(e: EntreeJour?, midi: Boolean): Boolean? {
        val r = e?.reponses ?: return null
        return if (midi) {
            when (r["midi_lieu"]) { "exterieur" -> true; "maison", "boulot" -> false; else -> null }
        } else {
            when (r["diner"]) { "beaux_parents", "ailleurs" -> true; "maison" -> false; else -> null }
        }
    }

    fun bilanPlan(plan: PlanWeekEnd, entrees: Map<LocalDate, EntreeJour>): BilanPlan {
        var renseignes = 0
        var faits = 0
        val ecarts = mutableListOf<String>()
        PlanWeekEnd.REPAS.forEach { r ->
            val fait = dehors(entrees[plan.vendredi.plusDays(r.decalage)], r.midi) ?: return@forEach
            renseignes++
            if (fait) faits++
            val prevu = plan.repas[r.cle] ?: return@forEach
            if ((prevu == PlanWeekEnd.DEHORS) != fait) ecarts += r.libelle
        }
        val bues = PlanWeekEnd.JOURS_BIERES.mapNotNull { j -> bieresEq33(entrees[plan.vendredi.plusDays(j.decalage)]) }
        return BilanPlan(
            plan = plan,
            bieresPrevues = plan.bieres.values.sum(),
            bieresBues = if (bues.isEmpty()) null else bues.sum(),
            dehorsPrevus = plan.repas.values.count { it == PlanWeekEnd.DEHORS },
            dehorsFaits = faits,
            repasRenseignes = renseignes,
            ecarts = ecarts,
        )
    }

    // ---------- Analyse complète ----------

    /** Tendance calculée avec les seules données connues au matin de [d] : sert à figer les verdicts. */
    fun lissageAu(jours: List<JourLabo>, d: LocalDate): Kalman.Lissage? =
        Labo.lissage(jours.filter { !it.date.isAfter(d) }, d)

    /**
     * [lis] : tendance du poids (corrigée de l'eau) avec toutes les données, null sans pesée ou sans Health
     * Connect : les semaines sont alors « pas lisibles » mais bières, levier et activité restent calculés.
     * [figee] : tendance connue à une date donnée (par défaut recalculée sur les jours jusqu'à cette date).
     */
    fun analyser(
        jours: List<JourLabo>,
        lis: Kalman.Lissage?,
        etat: EtatObjectif,
        aujourdHui: LocalDate,
        reflexes: List<Pair<String, (EntreeJour) -> Double?>>,
        figee: (LocalDate) -> Kalman.Lissage? = { d -> if (lis == null) null else lissageAu(jours, d) },
    ): ResultatCap {
        val parDate = jours.associateBy { it.date }
        val entrees = jours.mapNotNull { j -> j.entree?.let { j.date to it } }.toMap()
        val scores = reflexes.toMap()
        val semaineCourante = debutSemaine(aujourdHui)
        val semaineDepart = minOf(debutSemaine(etat.debut), semaineCourante)
        val bas = Hall.effortPourPente(etat.couloirBas)
        val haut = Hall.effortPourPente(etat.couloirHaut)

        fun pesees(l: Kalman.Lissage?, debut: LocalDate, nb: Int): Int {
            if (l == null) return 0
            return (0..nb).count { k ->
                val d = debut.plusDays(k.toLong())
                val i = l.index(d)
                parDate[d]?.poids != null && i != null && i !in l.ecartees
            }
        }

        val base = basePas(jours, semaineDepart, aujourdHui)
        val cible = base?.let { it + Pas.PAS_EN_PLUS }
        val semaines = mutableListOf<SemaineCap>()
        var s = semaineDepart
        while (!s.isAfter(semaineCourante)) {
            val enCours = s == semaineCourante
            val nb = if (enCours) ChronoUnit.DAYS.between(s, aujourdHui).toInt() else 7
            val fin = if (enCours) aujourdHui else s.plusDays(7)
            val l = if (enCours) lis else figee(fin)
            val debutFenetre = maxOf(semaineDepart, fin.minusDays(JOURS_FENETRE.toLong()))
            val joursFenetre = ChronoUnit.DAYS.between(debutFenetre, fin).toInt()
            val m = if (l != null && (!enCours || nb >= JOURS_PROVISOIRE)) mesure(l, semaineDepart, debutFenetre, joursFenetre) else null
            val nbPesees = pesees(l, s, nb)

            val dates = (0L..6L).map { s.plusDays(it) }.filter { !it.isAfter(aujourdHui) }
            val bieres = dates.mapNotNull { bieresEq33(entrees[it]) }
            val joursLabo = dates.mapNotNull { parDate[it] }
            val kcal = joursLabo.mapNotNull { Labo.kcalRetenue(it) }
            val levier = etat.leviers[s]
            val pas = dates.mapNotNull { pasRetenus(parDate[it], aujourdHui) }
            val joursCible = if (cible == null) 0 else pas.count { it >= cible }
            val proteines = dates.mapNotNull { Labo.proteinesG(entrees[it], etat.portions) }
            semaines += SemaineCap(
                debut = s,
                nbJours = nb,
                enCours = enCours,
                mesure = m,
                nbPesees = nbPesees,
                verdict = verdict(m?.effort, nbPesees, bas, haut).let { v ->
                    // Rodage : un verdict lisible n'est pas encore jugé ; « pas lisible » reste « pas lisible ».
                    if (ChronoUnit.WEEKS.between(semaineDepart, s) < SEMAINES_RODAGE && v != Verdict.PAS_LISIBLE) Verdict.RODAGE else v
                },
                budget = etat.budget(s),
                bieres = if (bieres.isEmpty()) null else bieres.sum(),
                joursAlcool = bieres.size,
                levier = levier,
                adherence = when {
                    levier == LEVIER_PAS -> if (cible == null || pas.isEmpty()) null else Adherence(joursCible.toDouble(), pas.size)
                    else -> levier?.let { scores[it] }?.let { adherence(entrees, dates, it) }
                },
                nbSeances = joursLabo.sumOf { it.nbSeances ?: 0 },
                minutesSeances = joursLabo.sumOf { it.minutesSeances ?: 0L },
                depenseMoyenne = if (kcal.isEmpty()) null else kcal.average(),
                pasHorsSport = if (pas.isEmpty()) null else pas.average(),
                joursPas = pas.size,
                joursCiblePas = joursCible,
                proteines = if (proteines.isEmpty()) null else proteines.average(),
                joursProteines = proteines.size,
            )
            s = s.plusWeeks(1)
        }

        val i0 = lis?.index(semaineDepart)
        val iN = lis?.index(aujourdHui)
        val depuis = if (lis != null && i0 != null && iN != null && iN - i0 >= 7) mesure(lis, semaineDepart, semaineDepart, iN - i0) else null

        return ResultatCap(
            debut = etat.debut,
            semaineDepart = semaineDepart,
            effortBas = bas,
            effortHaut = haut,
            niveauDepart = if (lis != null && i0 != null) lis.niveau(i0) else null,
            niveauActuel = if (lis != null && iN != null) lis.niveau(iN) else null,
            penteDepuisDebut = depuis?.pente,
            effortDepuisDebut = depuis?.effort,
            semaines = semaines,
            budgetCourant = etat.budget(semaineCourante),
            propositions = propositions(entrees, aujourdHui, reflexes, autres = listOfNotNull(cible?.let { c ->
                val p = (0 until JOURS_PROPOSITIONS).mapNotNull { pasRetenus(parDate[aujourdHui.minusDays(it)], aujourdHui) }
                if (p.isEmpty()) null else Triple(LEVIER_PAS, p.count { it >= c }.toDouble() / p.size, p.size)
            })),
            basePas = base,
            ciblePas = cible,
        )
    }
}
