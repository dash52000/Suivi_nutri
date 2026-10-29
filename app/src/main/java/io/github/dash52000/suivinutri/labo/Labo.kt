package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.EntreeJour
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Une journée vue par le labo : données Health Connect + réponses du questionnaire. */
data class JourLabo(
    val date: LocalDate,
    val poids: Double? = null,
    val kcal: Double? = null,
    val sommeilMin: Long? = null,
    val entree: EntreeJour? = null,
)

data class PointPoids(val date: LocalDate, val brut: Double?, val tendance: Double)

data class SemaineApports(
    val lundi: LocalDate,
    val depenseMoyenne: Double,
    val deltaTendanceKg: Double,
    val apportsEstimes: Double,
    val nbPesees: Int,
    val nbJoursDepense: Int,
)

/** Apports d'une semaine, alignés dans le temps pour l'analyse des réflexes. */
data class SemaineAnalyse(val lundi: LocalDate, val apports: Double)

/** Écart de poids après un soir « exposé », comparé aux autres soirs. */
data class EffetLendemain(
    val libelle: String,
    val n1: Int,
    val n0: Int,
    val j1: Intervalle?,
    val j2: Double?,
    val j3: Double?,
)

/** Effet d'un réflexe sur les apports estimés, semaine par semaine. */
data class EffetSemaine(
    val libelle: String,
    val parQuoi: String,
    val nbSemaines: Int,
    val effet: Intervalle?,
)

/** Une exposition (nuit courte, alcool la veille) et sa fréquence d'issue (rab, dessert). */
data class EffetCroise(
    val exposition: String,
    val reference: String,
    val issue: String,
    val n1: Int,
    val n0: Int,
    val p1: Double?,
    val p0: Double?,
    val ecart: Intervalle?,
)

data class Qualite(
    val joursRenseignes28: Int,
    /** Poids du lendemain : jours sans réponse − jours renseignés. Positif = signe de NA non aléatoires. */
    val ecartSansReponse: Intervalle?,
    val nSans: Int,
    val nAvec: Int,
)

/** Une ligne du calendrier des réflexes. */
data class LigneCalendrier(val libelle: String, val scores: List<Double?>) {
    /** Somme des scores (un « à moitié » compte pour 0,5) sur les jours renseignés. */
    val tenus: Double get() = scores.filterNotNull().sum()
    val repondus: Int get() = scores.count { it != null }
}

data class ResultatLabo(
    val poids: List<PointPoids>,
    val semaines: List<SemaineApports>,
    val lendemain: List<EffetLendemain>,
    val effetsSemaine: List<EffetSemaine>,
    val croises: List<EffetCroise>,
    val qualite: Qualite,
)

object Labo {

    /** Lissage exponentiel de la tendance (une pesée pèse 10 %). */
    const val ALPHA = 0.1
    const val KCAL_PAR_KG = 7700.0
    const val MIN_GROUPE = 5
    const val MIN_SEMAINES = 8
    const val NUIT_COURTE_MIN = 390L
    private const val MIN_PESEES_AVANT = 5
    private const val KCAL_JOUR_MIN = 800.0

    // ---------- Codage des réponses ----------

    private fun EntreeJour.rep(id: String): String? = reponses[id]

    /** Score de chaque réflexe un soir donné : 1 = tenu, 0 = pas tenu, null = sans objet ou sans réponse. */
    val reflexes: List<Pair<String, (EntreeJour) -> Double?>> = listOf(
        "Boîtes posées" to { e: EntreeJour ->
            when (e.rep("boites")) { "oui" -> 1.0; "non" -> 0.0; else -> null }
        },
        "Tupp remplis" to { e: EntreeJour ->
            when (e.rep("tupp")) { "oui" -> 1.0; "non" -> 0.0; else -> null }
        },
        "Assiette équilibrée" to { e: EntreeJour ->
            when (e.rep("assiette")) { "oui" -> 1.0; "en_partie" -> 0.5; "non" -> 0.0; else -> null }
        },
        "Pris ton temps" to { e: EntreeJour ->
            when (e.rep("temps")) { "oui" -> 1.0; "a_moitie" -> 0.5; "non" -> 0.0; else -> null }
        },
        "Pas de rab" to { e: EntreeJour ->
            when (e.rep("resservi")) { "non" -> 1.0; "un_peu" -> 0.5; "oui" -> 0.0; else -> null }
        },
        "Dessert évité ou mis de côté" to { e: EntreeJour ->
            when (e.rep("dessert")) { "non", "tupp" -> 1.0; "petite" -> 0.5; "normale" -> 0.0; else -> null }
        },
    )

    private fun rab(e: EntreeJour?): Boolean? = when (e?.rep("resservi")) {
        "un_peu", "oui" -> true; "non" -> false; else -> null
    }

    private fun dessertPris(e: EntreeJour?): Boolean? = when (e?.rep("dessert")) {
        "petite", "normale" -> true; "non", "tupp" -> false; else -> null
    }

    private fun alcool(e: EntreeJour?): Boolean? = when (e?.rep("alcool")) {
        "oui" -> true; "aucun" -> false; else -> null
    }

    private fun beauxParents(e: EntreeJour?): Boolean? = when (e?.rep("diner")) {
        "beaux_parents" -> true; "maison", "ailleurs" -> false; else -> null
    }

    /** Unités d'alcool d'un jour renseigné, null si la question est sans réponse. */
    private fun unites(e: EntreeJour?): Double? {
        if (e == null) return null
        return when (e.rep("alcool")) {
            "oui" -> e.unitesAlcool; "aucun" -> 0.0; else -> null
        }
    }

    // ---------- Poids ----------

    /** Tendance lissée, reportée sur les jours sans pesée. */
    fun tendance(jours: List<JourLabo>): Map<LocalDate, Double> {
        val resultat = LinkedHashMap<LocalDate, Double>()
        var t: Double? = null
        for (j in jours.sortedBy { it.date }) {
            val p = j.poids
            if (p != null) {
                val precedente = t
                t = if (precedente == null) p else precedente + ALPHA * (p - precedente)
            }
            val courante = t
            if (courante != null) resultat[j.date] = courante
        }
        return resultat
    }

    // ---------- Analyse complète ----------

    /**
     * [reflexesPerso] : questions ajoutées par l'utilisateur, avec leur score (1 = tenu, 0 = pas tenu),
     * analysées comme les réflexes intégrés.
     */
    fun analyser(
        jours: List<JourLabo>,
        aujourdHui: LocalDate,
        reflexesPerso: List<Pair<String, (EntreeJour) -> Double?>> = emptyList(),
    ): ResultatLabo {
        val tries = jours.sortedBy { it.date }
        val parDate = tries.associateBy { it.date }
        val tendance = tendance(tries)

        val poids = tries
            .filter { !it.date.isBefore(aujourdHui.minusDays(89)) }
            .mapNotNull { j -> tendance[j.date]?.let { PointPoids(j.date, j.poids, it) } }

        val semaines = semaines(tries, parDate, tendance, aujourdHui)
        val analyse = semainesAnalyse(tries, aujourdHui)

        return ResultatLabo(
            poids = poids,
            semaines = semaines.sortedByDescending { it.lundi },
            lendemain = lendemain(tries, parDate),
            effetsSemaine = effetsSemaine(parDate, analyse, reflexes + reflexesPerso),
            croises = croises(tries, parDate),
            qualite = qualite(tries, parDate, aujourdHui),
        )
    }

    /** Apports estimés = dépense moyenne + variation de la tendance × 7 700 kcal/kg ÷ 7. Semaines complètes seulement. */
    private fun semaines(
        tries: List<JourLabo>,
        parDate: Map<LocalDate, JourLabo>,
        tendance: Map<LocalDate, Double>,
        aujourdHui: LocalDate,
    ): List<SemaineApports> {
        if (tries.isEmpty()) return emptyList()
        val premierLundi = tries.first().date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val dernierLundi = aujourdHui.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
        val resultat = mutableListOf<SemaineApports>()
        var lundi = premierLundi
        while (!lundi.isAfter(dernierLundi)) {
            val veille = lundi.minusDays(1)
            val dimanche = lundi.plusDays(6)
            val tDebut = tendance[veille]
            val tFin = tendance[dimanche]
            val semaine = (0L..6L).mapNotNull { parDate[lundi.plusDays(it)] }
            val kcal = semaine.mapNotNull { it.kcal }.filter { it >= KCAL_JOUR_MIN }
            val pesees = semaine.count { it.poids != null }
            val peseesAvant = tries.count { it.poids != null && !it.date.isAfter(veille) }
            if (tDebut != null && tFin != null && kcal.size >= 5 && pesees >= 3 && peseesAvant >= MIN_PESEES_AVANT) {
                val depense = kcal.average()
                val delta = tFin - tDebut
                resultat += SemaineApports(lundi, depense, delta, depense + delta * KCAL_PAR_KG / 7, pesees, kcal.size)
            }
            lundi = lundi.plusWeeks(1)
        }
        return resultat
    }

    /**
     * Apports de la semaine pour l'analyse, sans le retard de la tendance lissée (environ 9 jours) :
     * dépense moyenne de la semaine + différence centrée des pesées moyennes,
     * (moyenne semaine suivante − moyenne semaine précédente) ÷ 2, × 7 700 kcal/kg ÷ 7.
     * La fenêtre est centrée sur la semaine, donc alignée avec les réflexes de cette même semaine.
     * Il faut au moins 3 pesées dans chacune des semaines voisines et 5 jours de dépense.
     */
    fun semainesAnalyse(jours: List<JourLabo>, aujourdHui: LocalDate): List<SemaineAnalyse> {
        if (jours.isEmpty()) return emptyList()
        val parDate = jours.associateBy { it.date }
        fun moyennePesees(lundi: LocalDate): Double? {
            val p = (0L..6L).mapNotNull { parDate[lundi.plusDays(it)]?.poids }
            return if (p.size >= 3) p.average() else null
        }
        val premierLundi = jours.minOf { it.date }.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        // La semaine suivante doit être complète : la dernière semaine analysable est l'avant-dernière.
        val dernierLundi = aujourdHui.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(2)
        val resultat = mutableListOf<SemaineAnalyse>()
        var lundi = premierLundi
        while (!lundi.isAfter(dernierLundi)) {
            val avant = moyennePesees(lundi.minusWeeks(1))
            val apres = moyennePesees(lundi.plusWeeks(1))
            val kcal = (0L..6L).mapNotNull { parDate[lundi.plusDays(it)]?.kcal }.filter { it >= KCAL_JOUR_MIN }
            if (avant != null && apres != null && kcal.size >= 5) {
                val deltaSemaine = (apres - avant) / 2
                resultat += SemaineAnalyse(lundi, kcal.average() + deltaSemaine * KCAL_PAR_KG / 7)
            }
            lundi = lundi.plusWeeks(1)
        }
        return resultat
    }

    private fun ecartPoids(parDate: Map<LocalDate, JourLabo>, date: LocalDate, k: Long): Double? {
        val avant = parDate[date]?.poids ?: return null
        val apres = parDate[date.plusDays(k)]?.poids ?: return null
        return apres - avant
    }

    /** Effet lendemain : pesée du matin J+k moins pesée du matin J, soirs exposés contre les autres. */
    private fun lendemain(tries: List<JourLabo>, parDate: Map<LocalDate, JourLabo>): List<EffetLendemain> {
        val expositions: List<Pair<String, (EntreeJour?) -> Boolean?>> = listOf(
            "Soir avec rab" to { e: EntreeJour? -> rab(e) },
            "Dessert pris" to { e: EntreeJour? -> dessertPris(e) },
            "Soir avec alcool" to { e: EntreeJour? -> alcool(e) },
            "Dîner chez les beaux-parents" to { e: EntreeJour? -> beauxParents(e) },
        )
        return expositions.map { (libelle, expose) ->
            fun groupes(k: Long): Pair<List<Double>, List<Double>> {
                val oui = mutableListOf<Double>()
                val non = mutableListOf<Double>()
                tries.forEach { j ->
                    val e = expose(j.entree) ?: return@forEach
                    val d = ecartPoids(parDate, j.date, k) ?: return@forEach
                    if (e) oui += d else non += d
                }
                return oui to non
            }
            fun ecartMoyen(k: Long): Double? {
                val (oui, non) = groupes(k)
                return if (oui.size >= MIN_GROUPE && non.size >= MIN_GROUPE) Stats.moyenne(oui) - Stats.moyenne(non) else null
            }
            val (oui, non) = groupes(1)
            val assez = oui.size >= MIN_GROUPE && non.size >= MIN_GROUPE
            EffetLendemain(
                libelle = libelle,
                n1 = oui.size,
                n0 = non.size,
                j1 = if (assez) Stats.welch(oui, non) else null,
                j2 = if (assez) ecartMoyen(2) else null,
                j3 = if (assez) ecartMoyen(3) else null,
            )
        }
    }

    /** Réflexes agrégés par semaine, mis face aux apports de la même semaine (version alignée). */
    private fun effetsSemaine(
        parDate: Map<LocalDate, JourLabo>,
        semaines: List<SemaineAnalyse>,
        aAnalyser: List<Pair<String, (EntreeJour) -> Double?>>,
    ): List<EffetSemaine> {
        fun joursDe(s: SemaineAnalyse) = (0L..6L).mapNotNull { parDate[s.lundi.plusDays(it)] }
        fun position(s: SemaineAnalyse) = (s.lundi.toEpochDay() / 7).toInt()

        val effets = aAnalyser.map { (libelle, score) ->
            val points = semaines.mapNotNull { s ->
                val scores = joursDe(s).mapNotNull { j -> j.entree?.let(score) }
                if (scores.size >= 2) Triple(scores.average(), s.apports, position(s)) else null
            }
            val effet = if (points.size >= MIN_SEMAINES) {
                Stats.penteNeweyWest(points.map { it.first }, points.map { it.second }, points.map { it.third })
                    ?.fois(1.0 / 7)
            } else null
            EffetSemaine(libelle, "par soir de plus sur 7", points.size, effet)
        }

        val pointsAlcool = semaines.mapNotNull { s ->
            val u = joursDe(s).mapNotNull { unites(it.entree) }
            if (u.size >= 4) Triple(u.sum() / u.size * 7, s.apports, position(s)) else null
        }
        val effetAlcool = if (pointsAlcool.size >= MIN_SEMAINES) {
            Stats.penteNeweyWest(pointsAlcool.map { it.first }, pointsAlcool.map { it.second }, pointsAlcool.map { it.third })
                ?.fois(5.0)
        } else null

        return effets + EffetSemaine("Alcool", "par 5 unités de plus par semaine", pointsAlcool.size, effetAlcool)
    }

    /** Nuit courte ou alcool la veille : change-t-il les réflexes du soir ? */
    private fun croises(tries: List<JourLabo>, parDate: Map<LocalDate, JourLabo>): List<EffetCroise> {
        val nuitCourte: (JourLabo) -> Boolean? = { j -> j.sommeilMin?.let { it < NUIT_COURTE_MIN } }
        val alcoolVeille: (JourLabo) -> Boolean? = { j -> alcool(parDate[j.date.minusDays(1)]?.entree) }
        val expositions: List<Triple<String, String, (JourLabo) -> Boolean?>> = listOf(
            Triple("Après une nuit de moins de 6 h 30", "après une nuit plus longue", nuitCourte),
            Triple("Le lendemain d'un soir avec alcool", "le lendemain d'un soir sans", alcoolVeille),
        )
        val issues: List<Pair<String, (EntreeJour?) -> Boolean?>> = listOf(
            "rab" to { e: EntreeJour? -> rab(e) },
            "dessert pris" to { e: EntreeJour? -> dessertPris(e) },
        )
        return expositions.flatMap { (exposition, reference, expose) ->
            issues.map { (issue, mesure) ->
                var s1 = 0; var n1 = 0; var s0 = 0; var n0 = 0
                tries.forEach { j ->
                    val e = expose(j) ?: return@forEach
                    val r = mesure(j.entree) ?: return@forEach
                    if (e) { n1++; if (r) s1++ } else { n0++; if (r) s0++ }
                }
                val assez = n1 >= MIN_GROUPE && n0 >= MIN_GROUPE
                EffetCroise(
                    exposition = exposition,
                    reference = reference,
                    issue = issue,
                    n1 = n1,
                    n0 = n0,
                    p1 = if (n1 > 0) s1.toDouble() / n1 else null,
                    p0 = if (n0 > 0) s0.toDouble() / n0 else null,
                    ecart = if (assez) Stats.differenceProportions(s1, n1, s0, n0) else null,
                )
            }
        }
    }

    private fun qualite(tries: List<JourLabo>, parDate: Map<LocalDate, JourLabo>, aujourdHui: LocalDate): Qualite {
        val renseignes28 = (1L..28L).count { k -> parDate[aujourdHui.minusDays(k)]?.entree?.vide == false }
        val premierRenseigne = tries.firstOrNull { it.entree?.vide == false }?.date
        val sans = mutableListOf<Double>()
        val avec = mutableListOf<Double>()
        if (premierRenseigne != null) {
            tries.filter { !it.date.isBefore(premierRenseigne) && it.date.isBefore(aujourdHui) }.forEach { j ->
                val d = ecartPoids(parDate, j.date, 1) ?: return@forEach
                if (j.entree?.vide == false) avec += d else sans += d
            }
        }
        val assez = sans.size >= MIN_GROUPE && avec.size >= MIN_GROUPE
        return Qualite(
            joursRenseignes28 = renseignes28,
            ecartSansReponse = if (assez) Stats.welch(sans, avec) else null,
            nSans = sans.size,
            nAvec = avec.size,
        )
    }

    /**
     * Une ligne par réflexe, une case par date : 1 = tenu, 0,5 = à moitié, 0 = pas tenu, null = sans réponse.
     * Les réflexes sans aucune réponse sur la période sont omis.
     */
    fun calendrier(
        entrees: Map<LocalDate, EntreeJour>,
        dates: List<LocalDate>,
        aAnalyser: List<Pair<String, (EntreeJour) -> Double?>>,
    ): List<LigneCalendrier> =
        aAnalyser
            .map { (libelle, score) -> LigneCalendrier(libelle, dates.map { d -> entrees[d]?.let(score) }) }
            .filter { ligne -> ligne.scores.any { it != null } }

    /** Nombre de jours couverts, pour l'affichage. */
    fun etendue(jours: List<JourLabo>): Long =
        if (jours.isEmpty()) 0 else ChronoUnit.DAYS.between(jours.minOf { it.date }, jours.maxOf { it.date }) + 1
}
