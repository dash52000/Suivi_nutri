package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.EntreeJour
import io.github.dash52000.suivinutri.data.EtatObjectif
import io.github.dash52000.suivinutri.data.PlanWeekEnd
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.sqrt

/** Position d'une semaine par rapport au couloir de perte. */
enum class Verdict(val libelle: String) {
    DANS_LE_COULOIR("dans le couloir"),
    AU_DESSUS("au-dessus du couloir"),
    PLUS_VITE("plus vite que le couloir"),
    PAS_LISIBLE("pas encore lisible"),
}

/** Réflexe tenu sur les soirs renseignés (« à moitié » = 0,5). */
data class Adherence(val tenus: Double, val repondus: Int)

/** Une semaine vue par l'onglet Cap (lundi → dimanche ; poids du lundi matin au lundi matin suivant). */
data class SemaineCap(
    val lundi: LocalDate,
    /** 7 pour une semaine finie, jours écoulés pour la semaine en cours. */
    val nbJours: Int,
    val enCours: Boolean,
    /** Pente de la tendance, en kg par semaine, avec IC 95 %. */
    val pente: Intervalle?,
    val nbPesees: Int,
    val verdict: Verdict,
    /** Budget bière en vigueur cette semaine (équivalent 33 cl). */
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
)

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
    val lundiDepart: LocalDate,
    val couloirBas: Double,
    val couloirHaut: Double,
    /** Tendance au lundi de départ (null si pas encore de pesée à cette date). */
    val niveauDepart: Double?,
    val niveauActuel: Double?,
    /** Pente moyenne depuis le départ, en kg par semaine (au moins 7 jours). */
    val penteDepuisDebut: Intervalle?,
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
 * Objectif de perte de poids (v1.9) : couloir de pente, budget bière, levier de la semaine, plan du week-end et
 * soir qui dérape. Tout se recalcule à partir du journal, de la tendance de Kalman et de l'[EtatObjectif].
 */
object Cap {

    const val MIN_PESEES = 3
    const val JOURS_PROPOSITIONS = 14L
    const val MIN_REPONSES_PROPOSITION = 3
    const val NB_PROPOSITIONS = 3

    /** Un soir avec au moins 2 unités d'alcool (une Tripel de 33 cl) compte comme un dérapage. */
    const val SEUIL_UNITES = 2.0
    const val SEMAINES_SIGNAL = 8L
    const val MIN_SOIRS_SIGNAL = 3
    const val TAUX_MIN_SIGNAL = 0.5
    const val CL_BOUTEILLE = 33.0

    val STYLES_BIERE = setOf("pils", "blonde", "dubbel", "tripel", "quadrupel")

    /** Levier lu dans Health Connect, pas dans le questionnaire (v1.10). */
    const val LEVIER_PAS = "Journée · pas hors sport ≥ cible"

    fun lundiDe(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    // ---------- Couloir ----------

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

    fun verdict(pente: Intervalle?, nbPesees: Int, bas: Double, haut: Double): Verdict = when {
        pente == null || nbPesees < MIN_PESEES -> Verdict.PAS_LISIBLE
        pente.bas > haut -> Verdict.AU_DESSUS
        pente.haut < bas -> Verdict.PLUS_VITE
        pente.estimation in bas..haut -> Verdict.DANS_LE_COULOIR
        else -> Verdict.PAS_LISIBLE
    }

    /**
     * Budget en vigueur pour chaque semaine : élément 0 = première semaine, puis un élément de plus par semaine
     * finie. 2 semaines de suite au-dessus du couloir : −1 (minimum 0) ; 2 semaines de suite dans le couloir ou
     * plus vite : +1, sans dépasser [initial] ; une semaine pas lisible remet les deux compteurs à zéro.
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
                Verdict.PAS_LISIBLE -> { dessus = 0; dedans = 0 }
            }
            if (dessus == 2) { budget = maxOf(0, budget - 1); dessus = 0 }
            if (dedans == 2) { budget = minOf(initial, budget + 1); dedans = 0 }
            resultat += budget
        }
        return resultat
    }

    // ---------- Bières ----------

    /** Bières du jour en équivalent bouteille de 33 cl ; null si l'alcool n'a pas été renseigné. */
    fun bieresEq33(e: EntreeJour?): Double? {
        if (e == null) return null
        return when (e.reponses["alcool"]) {
            "aucun" -> 0.0
            "oui" -> e.boissons.filter { it.style in STYLES_BIERE }.sumOf { it.cl } / CL_BOUTEILLE
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
     * Médiane des pas hors sport sur les 8 semaines avant [lundiDepart] ; à défaut (moins de 14 jours), sur les
     * 8 premières semaines de données. Null avec moins de 14 jours en tout.
     */
    fun basePas(jours: List<JourLabo>, lundiDepart: LocalDate, aujourdHui: LocalDate): Double? {
        val retenus = jours.sortedBy { it.date }.mapNotNull { j -> pasRetenus(j, aujourdHui)?.let { j.date to it.toDouble() } }
        val avant = retenus.filter { (d, _) -> d.isBefore(lundiDepart) && !d.isBefore(lundiDepart.minusWeeks(Pas.SEMAINES_BASE)) }
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

    /**
     * [lis] : tendance du poids (corrigée de l'eau), null sans pesée ou sans Health Connect : les semaines sont
     * alors « pas lisibles » mais bières, levier et activité restent calculés.
     */
    fun analyser(
        jours: List<JourLabo>,
        lis: Kalman.Lissage?,
        etat: EtatObjectif,
        aujourdHui: LocalDate,
        reflexes: List<Pair<String, (EntreeJour) -> Double?>>,
    ): ResultatCap {
        val parDate = jours.associateBy { it.date }
        val entrees = jours.mapNotNull { j -> j.entree?.let { j.date to it } }.toMap()
        val scores = reflexes.toMap()
        val lundiCourant = lundiDe(aujourdHui)
        val lundiDepart = minOf(lundiDe(etat.debut), lundiCourant)

        fun peseesRetenues(debut: LocalDate, nb: Int): Int {
            val l = lis ?: return 0
            return (0..nb).count { k ->
                val d = debut.plusDays(k.toLong())
                val i = l.index(d)
                parDate[d]?.poids != null && i != null && i !in l.ecartees
            }
        }

        class Brute(val lundi: LocalDate, val nbJours: Int, val enCours: Boolean, val pente: Intervalle?, val pesees: Int, val verdict: Verdict)

        val brutes = mutableListOf<Brute>()
        var lundi = lundiDepart
        while (!lundi.isAfter(lundiCourant)) {
            val enCours = lundi == lundiCourant
            val nb = if (enCours) ChronoUnit.DAYS.between(lundi, aujourdHui).toInt() else 7
            val p = if (lis != null && nb >= 2) pente(lis, lundi, nb) else null
            val pesees = peseesRetenues(lundi, nb)
            brutes += Brute(lundi, nb, enCours, p, pesees, verdict(p, pesees, etat.couloirBas, etat.couloirHaut))
            lundi = lundi.plusWeeks(1)
        }

        val budgets = budgets(brutes.filter { !it.enCours }.map { it.verdict }, etat.budgetInitial)
        val base = basePas(jours, lundiDepart, aujourdHui)
        val cible = base?.let { it + Pas.PAS_EN_PLUS }

        val semaines = brutes.mapIndexed { idx, b ->
            val dates = (0L..6L).map { b.lundi.plusDays(it) }.filter { !it.isAfter(aujourdHui) }
            val bieres = dates.mapNotNull { bieresEq33(entrees[it]) }
            val joursLabo = dates.mapNotNull { parDate[it] }
            val kcal = joursLabo.mapNotNull { Labo.kcalRetenue(it) }
            val levier = etat.leviers[b.lundi]
            val pas = dates.mapNotNull { pasRetenus(parDate[it], aujourdHui) }
            val joursCible = if (cible == null) 0 else pas.count { it >= cible }
            SemaineCap(
                lundi = b.lundi,
                nbJours = b.nbJours,
                enCours = b.enCours,
                pente = b.pente,
                nbPesees = b.pesees,
                verdict = b.verdict,
                budget = budgets[minOf(idx, budgets.size - 1)],
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
            )
        }

        val i0 = lis?.index(lundiDepart)
        val iN = lis?.index(aujourdHui)
        val depuisDebut = if (lis != null && i0 != null && iN != null && iN - i0 >= 7) pente(lis, lundiDepart, iN - i0) else null

        return ResultatCap(
            debut = etat.debut,
            lundiDepart = lundiDepart,
            couloirBas = etat.couloirBas,
            couloirHaut = etat.couloirHaut,
            niveauDepart = if (lis != null && i0 != null) lis.niveau(i0) else null,
            niveauActuel = if (lis != null && iN != null) lis.niveau(iN) else null,
            penteDepuisDebut = depuisDebut,
            semaines = semaines,
            budgetCourant = semaines.last().budget,
            propositions = propositions(entrees, aujourdHui, reflexes, autres = listOfNotNull(cible?.let { c ->
                val p = (0 until JOURS_PROPOSITIONS).mapNotNull { pasRetenus(parDate[aujourdHui.minusDays(it)], aujourdHui) }
                if (p.isEmpty()) null else Triple(LEVIER_PAS, p.count { it >= c }.toDouble() / p.size, p.size)
            })),
            basePas = base,
            ciblePas = cible,
        )
    }
}
