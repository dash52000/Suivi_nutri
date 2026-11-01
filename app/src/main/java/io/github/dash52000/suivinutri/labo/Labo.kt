package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.EntreeJour
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.sqrt

/** Une journée vue par le labo : données Health Connect + réponses du questionnaire. */
data class JourLabo(
    val date: LocalDate,
    val poids: Double? = null,
    val kcal: Double? = null,
    val sommeilMin: Long? = null,
    val entree: EntreeJour? = null,
    /** Séance démarrée entre 10 h et 14 h 59 ; null si Health Connect n'a rien pour ce jour. */
    val seanceMidi: Boolean? = null,
    /** Heure locale de la pesée (0 à 23). */
    val heurePesee: Int? = null,
    /** Séance d'endurance d'au moins [Labo.LONGUE_SORTIE_MIN] minutes ; null si Health Connect n'a rien lu. */
    val longueSortie: Boolean? = null,
    val minutesSeances: Long? = null,
    /** Composition de la balance (bio-impédance) : taux de graisse en %, masses en kg. */
    val graissePct: Double? = null,
    val masseMaigreHcKg: Double? = null,
    val eauKg: Double? = null,
    val osKg: Double? = null,
    val sourcePoids: String? = null,
) {
    /** Masse grasse du jour : poids × taux de graisse. */
    val masseGrasse: Double? get() = if (poids != null && graissePct != null) poids * graissePct / 100 else null

    /** Masse maigre du jour : poids − masse grasse (eau, muscle, glycogène, os). */
    val masseMaigre: Double? get() = masseGrasse?.let { poids!! - it }
}

/** Comment les apports d'une semaine ont été estimés. */
enum class Methode(val libelle: String) {
    POIDS("variation du poids × 7 700 kcal/kg"),
    COMPOSITION("masse grasse × 9 440 + masse maigre × 1 816 kcal/kg"),
}

/** Tendances de la composition au dernier jour. */
data class ResumeComposition(
    val date: LocalDate,
    val graisseKg: Double,
    val graisseSe: Double,
    val maigreKg: Double,
    val maigreSe: Double,
) {
    val graissePct: Double get() = 100 * graisseKg / (graisseKg + maigreKg)
}

data class PointPoids(
    val date: LocalDate,
    val brut: Double?,
    val tendance: Double,
    val seTendance: Double,
    /** Pesée écartée par le Kalman (aberrante). */
    val ecartee: Boolean,
    val apresMidi: Boolean,
)

/**
 * Apports estimés d'une semaine (ou des jours écoulés de la semaine en cours) :
 * dépense moyenne + variation du niveau lissé × 7 700 kcal/kg ÷ nombre de jours, avec IC 95 %.
 */
data class SemaineApports(
    val lundi: LocalDate,
    val depenseMoyenne: Double,
    val deltaTendanceKg: Double,
    val apports: Intervalle,
    val nbPesees: Int,
    val nbJoursDepense: Int,
    val enCours: Boolean,
    val nbJours: Int,
    val methode: Methode = Methode.POIDS,
    val deltaGraisseKg: Double? = null,
    val deltaMaigreKg: Double? = null,
) {
    val apportsEstimes: Double get() = apports.estimation
}

/** Effet d'une exposition du jour J sur l'écart du poids à la tendance, à J+1 (avec IC), J+2 et J+3. */
data class EffetLendemain(
    val libelle: String,
    val n1: Int,
    val n0: Int,
    val j1: Intervalle?,
    val j2: Double?,
    val j3: Double?,
    /** q-valeur de Benjamini-Hochberg, sur l'ensemble des effets du labo. */
    val q: Double? = null,
)

/** Effet d'un réflexe sur les apports estimés, semaine par semaine. */
data class EffetSemaine(
    val libelle: String,
    val parQuoi: String,
    val nbSemaines: Int,
    val effet: Intervalle?,
    val q: Double? = null,
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
    val q: Double? = null,
    /** Test direct du mécanisme d'un réflexe, le jour même (ex. pris ton temps → rab). */
    val mecanisme: Boolean = false,
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
    val composition: ResumeComposition? = null,
    val semaines: List<SemaineApports>,
    val lendemain: List<EffetLendemain>,
    val effetsSemaine: List<EffetSemaine>,
    val croises: List<EffetCroise>,
    val qualite: Qualite,
)

object Labo {

    const val KCAL_PAR_KG = 7700.0

    /** Densités énergétiques de Kevin Hall (2008) : 39,5 MJ/kg de graisse, 7,6 MJ/kg de masse maigre. */
    const val KCAL_PAR_KG_GRAISSE = 9440.0
    const val KCAL_PAR_KG_MAIGRE = 1816.0
    const val MIN_MESURES_COMPOSITION = 3
    const val MIN_GROUPE = 5
    const val MIN_SEMAINES = 8
    const val NUIT_COURTE_MIN = 390L
    const val LONGUE_SORTIE_MIN = 75L

    /** Seuil de q-valeur (Benjamini-Hochberg) pour qu'un effet soit présenté comme net. */
    const val SEUIL_Q = 0.05

    /** Régression du lendemain : au moins 15 jours complets, retard de Newey-West de 2 jours. */
    const val MIN_JOURS_REGRESSION = 15
    private const val RETARD_JOURS = 2
    private const val KCAL_JOUR_MIN = 800.0

    // ---------- Codage des réponses ----------

    private fun EntreeJour.rep(id: String): String? = reponses[id]

    private val SCORE_TEMPS = mapOf("oui" to 1.0, "a_moitie" to 0.5, "non" to 0.0)
    private val SCORE_ASSIETTE = mapOf("oui" to 1.0, "en_partie" to 0.5, "non" to 0.0) // « pas possible » : NA

    /**
     * Score de chaque réflexe un jour donné : 1 = tenu, 0,5 = à moitié, 0 = pas tenu, null = sans objet
     * ou sans réponse. Dans l'ordre de la journée.
     */
    val reflexes: List<Pair<String, (EntreeJour) -> Double?>> = listOf(
        "Midi · lentement" to { e: EntreeJour -> e.rep("midi_temps")?.let { SCORE_TEMPS[it] } },
        "Midi · sans écran" to { e: EntreeJour ->
            when (e.rep("midi_ecran")) { "non" -> 1.0; "oui" -> 0.0; else -> null }
        },
        "Midi · assiette ½ ¼ ¼" to { e: EntreeJour -> e.rep("midi_assiette")?.let { SCORE_ASSIETTE[it] } },
        "Soir · boîtes posées" to { e: EntreeJour ->
            when (e.rep("boites")) { "oui" -> 1.0; "non" -> 0.0; else -> null }
        },
        "Soir · assiette ½ ¼ ¼" to { e: EntreeJour -> e.rep("assiette")?.let { SCORE_ASSIETTE[it] } },
        "Soir · lentement" to { e: EntreeJour -> e.rep("temps")?.let { SCORE_TEMPS[it] } },
        "Soir · pas de rab" to { e: EntreeJour ->
            when (e.rep("resservi")) { "non" -> 1.0; "un_peu" -> 0.5; "oui" -> 0.0; else -> null }
        },
        "Soir · dessert évité ou gardé" to { e: EntreeJour ->
            when (e.rep("dessert")) { "non", "tupp" -> 1.0; "petite" -> 0.5; "normale" -> 0.0; else -> null }
        },
        "Soir · tupp remplis" to { e: EntreeJour ->
            when (e.rep("tupp")) { "oui" -> 1.0; "non" -> 0.0; else -> null }
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

    /** Pesée retenue d'un jour (non écartée par le Kalman). */
    private fun peseeRetenue(lis: Kalman.Lissage, parDate: Map<LocalDate, JourLabo>, d: LocalDate): Double? {
        val i = lis.index(d) ?: return null
        if (i in lis.ecartees) return null
        return parDate[d]?.poids
    }

    /** Lissage de Kalman de toutes les pesées, de la première jusqu'à aujourd'hui. Null sans pesée. */
    fun lissage(jours: List<JourLabo>, aujourdHui: LocalDate): Kalman.Lissage? {
        val pesees = jours.filter { it.poids != null }.sortedBy { it.date }
        if (pesees.isEmpty()) return null
        val debut = pesees.first().date
        val fin = maxOf(aujourdHui, pesees.last().date)
        val n = ChronoUnit.DAYS.between(debut, fin).toInt() + 1
        val mesures = pesees.map { j ->
            Kalman.Pesee(
                jour = ChronoUnit.DAYS.between(debut, j.date).toInt(),
                poids = j.poids!!,
                variance = Kalman.variancePesee(j.heurePesee),
            )
        }
        return Kalman.lisser(debut, n, mesures)
    }

    /** Lissages de la masse grasse et de la masse maigre, sur la même grille de jours. */
    class Compartiments(val graisse: Kalman.Lissage, val maigre: Kalman.Lissage)

    /** Null tant qu'il y a moins de [MIN_MESURES_COMPOSITION] jours avec poids et taux de graisse. */
    fun compartiments(jours: List<JourLabo>, aujourdHui: LocalDate): Compartiments? {
        val mesures = jours.filter { it.masseGrasse != null }.sortedBy { it.date }
        if (mesures.size < MIN_MESURES_COMPOSITION) return null
        val debut = mesures.first().date
        val n = ChronoUnit.DAYS.between(debut, maxOf(aujourdHui, mesures.last().date)).toInt() + 1
        fun jour(j: JourLabo) = ChronoUnit.DAYS.between(debut, j.date).toInt()
        val graisse = mesures.map { Kalman.Pesee(jour(it), it.masseGrasse!!, Kalman.GRAISSE.variance(it.heurePesee)) }
        val maigre = mesures.map { Kalman.Pesee(jour(it), it.masseMaigre!!, Kalman.MAIGRE.variance(it.heurePesee)) }
        return Compartiments(
            Kalman.lisser(debut, n, graisse, Kalman.GRAISSE),
            Kalman.lisser(debut, n, maigre, Kalman.MAIGRE),
        )
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
        val lis = lissage(tries, aujourdHui)

        val poids = if (lis == null) emptyList() else {
            val depuis = maxOf(lis.debut, aujourdHui.minusDays(89))
            generateSequence(depuis) { it.plusDays(1) }
                .takeWhile { !it.isAfter(aujourdHui) }
                .mapNotNull { d ->
                    val i = lis.index(d) ?: return@mapNotNull null
                    val j = parDate[d]
                    PointPoids(
                        date = d,
                        brut = j?.poids,
                        tendance = lis.niveau(i),
                        seTendance = sqrt(lis.varNiveau(i)),
                        ecartee = i in lis.ecartees,
                        apresMidi = (j?.heurePesee ?: 0) >= Kalman.HEURE_APRES_MIDI,
                    )
                }
                .toList()
        }

        val comp = compartiments(tries, aujourdHui)
        val resume = comp?.let { c ->
            val i = c.graisse.index(aujourdHui) ?: return@let null
            ResumeComposition(
                date = aujourdHui,
                graisseKg = c.graisse.niveau(i),
                graisseSe = sqrt(c.graisse.varNiveau(i)),
                maigreKg = c.maigre.niveau(i),
                maigreSe = sqrt(c.maigre.varNiveau(i)),
            )
        }
        val semaines = if (lis == null) emptyList() else semaines(parDate, lis, comp, aujourdHui)
        val lendemain = lendemain(tries, parDate, lis)
        val effetsSemaine = effetsSemaine(parDate, semaines.filter { !it.enCours }, reflexes + reflexesPerso)
        val croises = croises(tries, parDate)

        // Benjamini-Hochberg sur l'ensemble des effets testés : une vingtaine d'intervalles à 95 %
        // donneraient sinon environ un « effet » par pur hasard.
        val pValeurs = lendemain.mapNotNull { it.j1?.p } + effetsSemaine.mapNotNull { it.effet?.p } +
            croises.mapNotNull { it.ecart?.p }
        val q = Stats.benjaminiHochberg(pValeurs).iterator()

        return ResultatLabo(
            poids = poids,
            composition = resume,
            semaines = semaines.sortedByDescending { it.lundi },
            lendemain = lendemain.map { e -> if (e.j1?.p != null) e.copy(q = q.next()) else e },
            effetsSemaine = effetsSemaine.map { e -> if (e.effet?.p != null) e.copy(q = q.next()) else e },
            croises = croises.map { e -> if (e.ecart?.p != null) e.copy(q = q.next()) else e },
            qualite = qualite(tries, parDate, aujourdHui),
        )
    }

    /**
     * Semaines complètes (lundi matin → lundi matin suivant) avec au moins 3 pesées retenues et 5 jours de
     * dépense, puis la semaine en cours dès 2 jours écoulés. Le niveau lissé du lundi matin reflète ce qui a
     * été mangé jusqu'au dimanche soir.
     */
    private fun semaines(
        parDate: Map<LocalDate, JourLabo>,
        lis: Kalman.Lissage,
        comp: Compartiments?,
        aujourdHui: LocalDate,
    ): List<SemaineApports> {
        val resultat = mutableListOf<SemaineApports>()
        val lundiCourant = aujourdHui.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        fun estimer(lundi: LocalDate, jours: Int, enCours: Boolean, minPesees: Int, minDepense: Int) {
            val i = lis.index(lundi) ?: return
            if (i + jours >= lis.n) return
            val periode = (0 until jours).map { lundi.plusDays(it.toLong()) }
            // Semaine en cours : la pesée de ce matin compte aussi.
            val joursPesees = if (enCours) periode + aujourdHui else periode
            val pesees = joursPesees.count { peseeRetenue(lis, parDate, it) != null }
            val kcal = periode.mapNotNull { parDate[it]?.kcal }.filter { it >= KCAL_JOUR_MIN }
            if (pesees < minPesees || kcal.size < minDepense) return
            val delta = lis.niveau(i + jours) - lis.niveau(i)

            // Par compartiments si la balance a donné assez de mesures sur la période, sinon par le poids.
            val ic = comp?.graisse?.index(lundi)
            val mesuresCompo = joursPesees.count { parDate[it]?.masseGrasse != null }
            val parCompo = comp != null && ic != null && ic + jours < comp.graisse.n && mesuresCompo >= minPesees
            val estimation: Double
            val se: Double
            var dGraisse: Double? = null
            var dMaigre: Double? = null
            if (parCompo) {
                val g = comp!!.graisse
                val m = comp.maigre
                dGraisse = g.niveau(ic!! + jours) - g.niveau(ic)
                dMaigre = m.niveau(ic + jours) - m.niveau(ic)
                estimation = kcal.average() + (KCAL_PAR_KG_GRAISSE * dGraisse + KCAL_PAR_KG_MAIGRE * dMaigre) / jours
                se = sqrt(
                    KCAL_PAR_KG_GRAISSE * KCAL_PAR_KG_GRAISSE * g.varDifference(ic, jours) +
                        KCAL_PAR_KG_MAIGRE * KCAL_PAR_KG_MAIGRE * m.varDifference(ic, jours)
                ) / jours
            } else {
                val parKg = KCAL_PAR_KG / jours
                estimation = kcal.average() + delta * parKg
                se = sqrt(lis.varDifference(i, jours)) * parKg
            }
            resultat += SemaineApports(
                lundi = lundi,
                depenseMoyenne = kcal.average(),
                deltaTendanceKg = delta,
                apports = Intervalle(estimation, estimation - Stats.Z975 * se, estimation + Stats.Z975 * se),
                nbPesees = pesees,
                nbJoursDepense = kcal.size,
                enCours = enCours,
                nbJours = jours,
                methode = if (parCompo) Methode.COMPOSITION else Methode.POIDS,
                deltaGraisseKg = dGraisse,
                deltaMaigreKg = dMaigre,
            )
        }

        var lundi = lis.debut.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        while (lundi.isBefore(lundiCourant)) {
            estimer(lundi, 7, enCours = false, minPesees = 3, minDepense = 5)
            lundi = lundi.plusWeeks(1)
        }
        val ecoules = ChronoUnit.DAYS.between(lundiCourant, aujourdHui).toInt()
        if (ecoules >= 2) estimer(lundiCourant, ecoules, enCours = true, minPesees = 1, minDepense = 2)
        return resultat
    }

    private fun ecartPoids(parDate: Map<LocalDate, JourLabo>, date: LocalDate, k: Long): Double? {
        val avant = parDate[date]?.poids ?: return null
        val apres = parDate[date.plusDays(k)]?.poids ?: return null
        return apres - avant
    }

    /**
     * Effet lendemain par régression jointe : l'écart de la pesée de J+k à la tendance lissée est expliqué
     * par toutes les expositions du jour J à la fois (rab, dessert, alcool, beaux-parents, longue sortie).
     * Chaque effet est donc estimé « à expositions égales par ailleurs ». Une exposition n'entre dans le
     * modèle que si elle compte au moins [MIN_GROUPE] jours exposés et [MIN_GROUPE] non exposés parmi les
     * jours complets. Erreurs-types de Newey-West (retard 2 jours).
     */
    private fun lendemain(
        tries: List<JourLabo>,
        parDate: Map<LocalDate, JourLabo>,
        lis: Kalman.Lissage?,
    ): List<EffetLendemain> {
        // Expositions graduées : rab et dessert sur 3 niveaux (« un peu », « petite part » = moitié),
        // alcool en unités ; le coefficient est l'effet d'un oui franc, ou d'une unité d'alcool.
        fun binaire(b: Boolean?): Double? = b?.let { if (it) 1.0 else 0.0 }
        val expositions: List<Pair<String, (JourLabo) -> Double?>> = listOf(
            "Soir avec rab" to { j: JourLabo ->
                when (j.entree?.rep("resservi")) { "oui" -> 1.0; "un_peu" -> 0.5; "non" -> 0.0; else -> null }
            },
            "Dessert pris" to { j: JourLabo ->
                when (j.entree?.rep("dessert")) { "normale" -> 1.0; "petite" -> 0.5; "non", "tupp" -> 0.0; else -> null }
            },
            "Alcool, par unité" to { j: JourLabo -> unites(j.entree) },
            "Dîner chez les beaux-parents" to { j: JourLabo -> binaire(beauxParents(j.entree)) },
            "Longue sortie (1 h 15 et plus)" to { j: JourLabo -> binaire(j.longueSortie) },
        )
        if (lis == null) return expositions.map { (l, _) -> EffetLendemain(l, 0, 0, null, null, null) }

        fun residu(d: LocalDate): Double? {
            val y = peseeRetenue(lis, parDate, d) ?: return null
            return y - lis.niveau(lis.index(d)!!)
        }

        class Ligne(val position: Int, val residu: Double, val x: List<Double?>)

        fun lignes(k: Long): List<Ligne> = tries.mapNotNull { j ->
            val e = residu(j.date.plusDays(k)) ?: return@mapNotNull null
            Ligne(j.date.toEpochDay().toInt(), e, expositions.map { (_, f) -> f(j) })
        }

        // Jours exposés (valeur > 0) et non exposés (valeur 0).
        fun compte(l: List<Ligne>, v: Int) =
            l.count { (it.x[v] ?: 0.0) > 0.0 && it.x[v] != null } to l.count { it.x[v] == 0.0 }

        // Sélection des expositions sur J+1, jusqu'à stabilité (les jours complets dépendent de la sélection).
        val l1 = lignes(1)
        var retenues = expositions.indices.filter { v ->
            val (oui, non) = compte(l1.filter { it.x[v] != null }, v)
            oui >= MIN_GROUPE && non >= MIN_GROUPE
        }
        var completes: List<Ligne> = l1
        while (true) {
            completes = l1.filter { ligne -> retenues.all { ligne.x[it] != null } }
            val stables = retenues.filter { v ->
                val (oui, non) = compte(completes, v)
                oui >= MIN_GROUPE && non >= MIN_GROUPE
            }
            if (stables == retenues) break
            retenues = stables
        }

        fun ajuster(lignes: List<Ligne>): ResultatOls? {
            val c = lignes.filter { ligne -> retenues.all { ligne.x[it] != null } }
            if (retenues.isEmpty() || c.size < MIN_JOURS_REGRESSION || c.size - retenues.size - 1 < 5) return null
            return Stats.olsHac(
                c.map { ligne -> DoubleArray(retenues.size + 1) { a -> if (a == 0) 1.0 else ligne.x[retenues[a - 1]]!! } },
                c.map { it.residu },
                c.map { it.position },
                RETARD_JOURS,
            )
        }

        val m1 = ajuster(l1)
        val m2 = if (m1 != null) ajuster(lignes(2)) else null
        val m3 = if (m1 != null) ajuster(lignes(3)) else null

        return expositions.mapIndexed { v, (libelle, _) ->
            val a = retenues.indexOf(v)
            val (oui, non) = if (a >= 0 && m1 != null) compte(completes, v) else compte(l1.filter { it.x[v] != null }, v)
            EffetLendemain(
                libelle = libelle,
                n1 = oui,
                n0 = non,
                j1 = if (a >= 0) m1?.intervalle(a + 1) else null,
                j2 = if (a >= 0) m2?.coef?.get(a + 1) else null,
                j3 = if (a >= 0) m3?.coef?.get(a + 1) else null,
            )
        }
    }

    /** Réflexes agrégés par semaine, mis face aux apports estimés de la même semaine (Kalman, sans retard). */
    private fun effetsSemaine(
        parDate: Map<LocalDate, JourLabo>,
        semaines: List<SemaineApports>,
        aAnalyser: List<Pair<String, (EntreeJour) -> Double?>>,
    ): List<EffetSemaine> {
        fun joursDe(s: SemaineApports) = (0L..6L).mapNotNull { parDate[s.lundi.plusDays(it)] }
        fun position(s: SemaineApports) = (s.lundi.toEpochDay() / 7).toInt()

        val effets = aAnalyser.map { (libelle, score) ->
            val points = semaines.mapNotNull { s ->
                val scores = joursDe(s).mapNotNull { j -> j.entree?.let(score) }
                if (scores.size >= 2) Triple(scores.average(), s.apportsEstimes, position(s)) else null
            }
            val effet = if (points.size >= MIN_SEMAINES) {
                Stats.penteNeweyWest(points.map { it.first }, points.map { it.second }, points.map { it.third })
                    ?.fois(1.0 / 7)
            } else null
            EffetSemaine(libelle, "par soir de plus sur 7", points.size, effet)
        }

        val pointsAlcool = semaines.mapNotNull { s ->
            val u = joursDe(s).mapNotNull { unites(it.entree) }
            if (u.size >= 4) Triple(u.sum() / u.size * 7, s.apportsEstimes, position(s)) else null
        }
        val effetAlcool = if (pointsAlcool.size >= MIN_SEMAINES) {
            Stats.penteNeweyWest(pointsAlcool.map { it.first }, pointsAlcool.map { it.second }, pointsAlcool.map { it.third })
                ?.fois(5.0)
        } else null

        return effets + EffetSemaine("Alcool", "par 5 unités de plus par semaine", pointsAlcool.size, effetAlcool)
    }

    /** Une exposition d'un jour et l'issue mesurée le même jour. */
    private class Croisement(
        val exposition: String,
        val reference: String,
        val issue: String,
        val expose: (JourLabo) -> Boolean?,
        val mesure: (JourLabo) -> Boolean?,
        val mecanisme: Boolean = false,
    )

    /** Ce qui se passe avant (nuit, veille, midi) change-t-il ce qui suit (midi, soir) ? */
    private fun croises(tries: List<JourLabo>, parDate: Map<LocalDate, JourLabo>): List<EffetCroise> {
        val nuitCourte: (JourLabo) -> Boolean? = { j -> j.sommeilMin?.let { it < NUIT_COURTE_MIN } }
        val alcoolVeille: (JourLabo) -> Boolean? = { j -> alcool(parDate[j.date.minusDays(1)]?.entree) }
        val tuppVeille: (JourLabo) -> Boolean? = { j ->
            when (parDate[j.date.minusDays(1)]?.entree?.rep("tupp")) { "oui" -> true; "non" -> false; else -> null }
        }
        val creux: (JourLabo) -> Boolean? = { j ->
            when (j.entree?.rep("midi_creux")) { "oui" -> true; "non" -> false; else -> null }
        }
        val tuppMidi: (JourLabo) -> Boolean? = { j ->
            when (j.entree?.rep("midi_quoi")) { "tupp" -> true; "prepare", "achete" -> false; else -> null }
        }
        val rabSoir: (JourLabo) -> Boolean? = { j -> rab(j.entree) }
        val dessertSoir: (JourLabo) -> Boolean? = { j -> dessertPris(j.entree) }
        fun ouiNon(id: String): (JourLabo) -> Boolean? = { j ->
            when (j.entree?.rep(id)) { "oui" -> true; "non" -> false; else -> null }
        }
        // « À moitié » et « pas fait gaffe » sont exclus : on compare des soirs francs.
        val pasDeRab: (JourLabo) -> Boolean? = { j -> rab(j.entree)?.not() }

        val croisements = listOf(
            // Mécanismes : chaque réflexe fait-il, le soir même, ce pour quoi il a été conçu ?
            Croisement("Soirs où tu as pris ton temps", "les soirs mangés vite", "rab le soir", ouiNon("temps"), rabSoir, true),
            Croisement("Soirs avec les boîtes posées", "sans les boîtes", "rab le soir", ouiNon("boites"), rabSoir, true),
            Croisement("Soirs où le plaisir a baissé", "quand il n'a pas baissé", "rab le soir", ouiNon("plaisir_baisse"), rabSoir, true),
            Croisement("Jours avec collation", "sans collation", "fringale en séance", ouiNon("collation"), ouiNon("fringale"), true),
            Croisement("Soirs sans rab", "les soirs avec rab", "faim au coucher", pasDeRab, ouiNon("faim_coucher"), true),

            Croisement("Après une nuit de moins de 6 h 30", "après une nuit plus longue", "rab le soir", nuitCourte, rabSoir),
            Croisement("Après une nuit de moins de 6 h 30", "après une nuit plus longue", "dessert pris", nuitCourte, dessertSoir),
            Croisement("Le lendemain d'un soir avec alcool", "le lendemain d'un soir sans", "rab le soir", alcoolVeille, rabSoir),
            Croisement("Le lendemain d'un soir avec alcool", "le lendemain d'un soir sans", "dessert pris", alcoolVeille, dessertSoir),
            Croisement("Tupp remplis la veille", "quand ils ne l'ont pas été", "tupp mangé le midi", tuppVeille, tuppMidi),
            Croisement("Les jours de séance le midi", "les autres jours", "petit creux l'après-midi", { j -> j.seanceMidi }, creux),
            Croisement("Après un petit creux l'après-midi", "sans creux", "rab le soir", creux, rabSoir),
        )
        return croisements.map { c ->
            var s1 = 0; var n1 = 0; var s0 = 0; var n0 = 0
            tries.forEach { j ->
                val e = c.expose(j) ?: return@forEach
                val r = c.mesure(j) ?: return@forEach
                if (e) { n1++; if (r) s1++ } else { n0++; if (r) s0++ }
            }
            val assez = n1 >= MIN_GROUPE && n0 >= MIN_GROUPE
            EffetCroise(
                exposition = c.exposition,
                reference = c.reference,
                issue = c.issue,
                n1 = n1,
                n0 = n0,
                p1 = if (n1 > 0) s1.toDouble() / n1 else null,
                p0 = if (n0 > 0) s0.toDouble() / n0 else null,
                ecart = if (assez) Stats.differenceProportions(s1, n1, s0, n0) else null,
                mecanisme = c.mecanisme,
            )
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
