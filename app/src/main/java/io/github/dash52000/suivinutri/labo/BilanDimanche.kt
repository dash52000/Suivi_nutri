package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.EntreeJour
import io.github.dash52000.suivinutri.data.EtatObjectif
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/** Une pesée de la semaine décomposée, et ce qui s'est passé la veille (bloc « Ta balance cette semaine »). */
data class JourBalance(
    val date: LocalDate,
    /** Écart de la pesée à la tendance, part de l'eau du jour et reste ; null sans pesée retenue. */
    val ecart: Double?,
    val partEau: Double?,
    /** Le jour sort du lot (écart d'au moins [BilanDimanche.SEUIL_JOUR]). */
    val sortDuLot: Boolean,
    /** Expositions de la veille, avec leur effet habituel sur la pesée du lendemain quand il est estimé. */
    val causes: List<String>,
)

/** Une composante de l'effort déclaré, en kcal/j par rapport à l'habitude d'avant le départ. */
data class Composante(val libelle: String, val kcalJour: Double, val jours: Int)

/** Effort déclaré : le questionnaire converti en kcal/j (négatif = moins d'apports ou plus de dépense). */
data class EffortDeclare(val total: Double, val composantes: List<Composante>, val manquantes: List<String>)


/** Un levier proposé pour la semaine qui vient, avec ce qu'il vaut si tenu tous les soirs. */
data class PropositionLevier(
    val libelle: String,
    /** Part des soirs (ou jours) où il a été tenu sur 14 jours. */
    val adherence: Double?,
    /** Gain estimé s'il était tenu à chaque fois, kcal par semaine et kg au bout d'un mois (modèle de Hall). */
    val gainKcalSemaine: Double?,
    val gainKgMois: Double?,
    /** Niveau de preuve du lien qui fonde le gain (null si le gain est direct : rab, dessert, pas). */
    val preuve: Niveau?,
)

/** Le levier de la semaine écoulée face aux leviers proposés et non choisis (différence de différences). */
data class BilanLevier(
    val libelle: String,
    /** Adhérence la semaine du levier, et les 14 jours d'avant. */
    val avant: Double?,
    val apres: Double?,
    /** Variation moyenne des témoins (leviers proposés non choisis), et leur nombre. */
    val variationTemoins: Double?,
    val nbTemoins: Int,
    /** Effet propre du levier = sa variation − celle des témoins, en points d'adhérence. */
    val effet: Double?,
)

/** Un lien qui a changé de niveau de preuve cette semaine (probable ou solide). */
/** Un lien qui a changé de niveau depuis le bilan d'avant (v1.14 : réflexes, lendemain et effets croisés). */
data class ChangementLien(val lien: Lien, val avant: Niveau) {
    val apres: Niveau get() = lien.niveau
}

data class ResultatBilan(
    /** Dimanche du bilan : clôture de la semaine écoulée, début de la semaine qui vient. */
    val dimanche: LocalDate,
    /** 1. Le cap : la semaine qui se ferme ce matin, et le chemin depuis le départ face à la bande de Hall. */
    val semaine: SemaineCap?,
    val semainesDepuisDepart: Int,
    val ecartDepart: Double?,
    /** Variation attendue depuis le départ aux deux bords du couloir d'effort (le moins fort, le plus fort). */
    val attenduHaut: Double?,
    val attenduBas: Double?,
    /** 2. Ta balance cette semaine : les 7 pesées du lundi au dimanche matin. */
    val balance: List<JourBalance>,
    /** 3. Graisse et maigre sur 4 semaines (variation et IC 95 %). */
    val graisse4: Intervalle?,
    val maigre4: Intervalle?,
    /** 4. Protection du muscle. */
    val renfos: Int,
    val proteinesGkg: Double?,
    val joursProteines: Int,
    val rythmeTropRapide: Boolean,
    /** 5. Effort déclaré de la semaine (affiché tant qu'il n'y a pas de cascade). */
    val effortDeclare: EffortDeclare?,
    /**
     * 5. La cascade depuis le départ (v1.15, à partir de 4 semaines ; remplace le face-à-face mensuel). Avant, seul
     * [effortDeclare] de la semaine est affiché.
     */
    val cascade: Cascade?,
    /**
     * 6. Ce qu'on a appris : les liens qui deviennent annoncés (probable dans le sens attendu, solide dans le sens
     * inverse) ou qui ne le sont plus, depuis le dimanche d'avant.
     */
    val appris: List<ChangementLien>,
    val mecanismes: List<ResultatMecanisme>,
    /** 7. À valider pour la semaine qui vient. */
    val bilanLevier: BilanLevier?,
    val propositionsLevier: List<PropositionLevier>,
    val propositionBudget: Int?,
    val budget: Int,
    val soirARisque: MomentDerape,
    val bilanPlan: BilanPlan?,
)

/**
 * Bilan du dimanche (v1.12) : seul endroit où l'appli explique. Tout se recalcule avec les données connues le
 * dimanche du bilan, donc un bilan passé se relit à l'identique.
 */
object BilanDimanche {

    /**
     * Une pesée « sort du lot » à partir de 0,8 kg d'écart à la tendance. L'écart brut fluctue de ±0,56 kg (analyse R
     * du 10/11/2026) : à 0,5 kg, 37 % des jours sortiraient par pur bruit ; à 0,8 kg, environ 15 %, un jour par semaine.
     */
    const val SEUIL_JOUR = 0.8

    /** Face-à-face déclaré / mesuré : toutes les 4 semaines, à partir de la 8e (avant, la tendance rattrape le départ). */

    // ---------- Coûts en kcal (estimations de table, affichées comme telles) ----------

    const val KCAL_RAB = 300.0
    const val KCAL_RAB_UN_PEU = 150.0
    const val KCAL_DESSERT = 250.0
    const val KCAL_PETITE_PART = 120.0

    /** kcal par unité d'alcool (10 g × 7 kcal), multipliées par le reste de la boisson : glucides de la bière, du vin. */
    const val KCAL_UNITE = 70.0
    const val FACTEUR_BIERE = 1.5
    const val FACTEUR_VIN = 1.1

    /** Dépense d'un pas, en kcal (+2 000 pas ≈ 90 kcal). */
    const val KCAL_PAS = 0.045

    private const val MIN_JOURS_SEMAINE = 3
    private const val MIN_JOURS_REFERENCE = 5
    private const val JOURS_REFERENCE = 28L

    fun kcalRab(e: EntreeJour): Double? = when (e.reponses["resservi"]) {
        "oui" -> KCAL_RAB; "un_peu" -> KCAL_RAB_UN_PEU; "non" -> 0.0; else -> null
    }

    fun kcalDessert(e: EntreeJour): Double? = when (e.reponses["dessert"]) {
        "normale" -> KCAL_DESSERT; "petite" -> KCAL_PETITE_PART; "non", "tupp" -> 0.0; else -> null
    }

    fun kcalAlcool(e: EntreeJour): Double? = when (e.reponses["alcool"]) {
        "aucun" -> 0.0
        "oui" -> if (e.boissons.isEmpty()) null else e.boissons.sumOf { b ->
            b.unites * KCAL_UNITE * when (b.style) { in Cap.STYLES_BIERE -> FACTEUR_BIERE; "vin" -> FACTEUR_VIN; else -> 1.0 }
        }
        else -> null
    }

    /**
     * Effort déclaré sur les jours [dates] face à la référence [reference] : pour chaque composante, moyenne du jour
     * moins moyenne de référence (au moins 3 jours d'un côté, 5 de l'autre). Négatif = moins d'apports ou plus de pas.
     */
    fun effortDeclare(parDate: Map<LocalDate, JourLabo>, dates: List<LocalDate>, reference: List<LocalDate>, auj: LocalDate): EffortDeclare? {
        val composantes = mutableListOf<Composante>()
        val manquantes = mutableListOf<String>()
        fun ajouter(libelle: String, valeur: (LocalDate) -> Double?) {
            val v = dates.mapNotNull(valeur)
            val r = reference.mapNotNull(valeur)
            if (v.size >= MIN_JOURS_SEMAINE && r.size >= MIN_JOURS_REFERENCE) composantes += Composante(libelle, v.average() - r.average(), v.size)
            else manquantes += libelle
        }
        ajouter("Rab") { d -> parDate[d]?.entree?.let(::kcalRab) }
        ajouter("Dessert") { d -> parDate[d]?.entree?.let(::kcalDessert) }
        ajouter("Alcool") { d -> parDate[d]?.entree?.let(::kcalAlcool) }
        ajouter("Pas hors sport") { d -> Cap.pasRetenus(parDate[d], auj)?.let { -it * KCAL_PAS } }
        if (composantes.isEmpty()) return null
        return EffortDeclare(composantes.sumOf { it.kcalJour }, composantes, manquantes)
    }

    // ---------- Leviers ----------

    private fun adherence(entrees: Map<LocalDate, EntreeJour>, dates: List<LocalDate>, score: (EntreeJour) -> Double?): Pair<Double, Int>? {
        val s = dates.mapNotNull { d -> entrees[d]?.let(score) }
        return if (s.isEmpty()) null else s.average() to s.size
    }

    /**
     * Leviers proposés (v1.12) : d'abord ceux dont on peut chiffrer le gain (rab et dessert directement, réflexes
     * dont le lien avec le rab est au moins une piste, pas hors sport), du plus gros au plus petit ; puis les moins
     * tenus. Gain = ce que rapporterait le levier tenu à chaque fois, d'après les 14 derniers jours.
     */
    fun propositionsLevier(
        parDate: Map<LocalDate, JourLabo>,
        entrees: Map<LocalDate, EntreeJour>,
        dimanche: LocalDate,
        reflexes: List<Pair<String, (EntreeJour) -> Double?>>,
        mecanismes: List<ResultatMecanisme>,
        ciblePas: Double?,
        n: Int = Cap.NB_PROPOSITIONS,
    ): List<PropositionLevier> {
        val dates = (1L..Cap.JOURS_PROPOSITIONS).map { dimanche.minusDays(it) }
        fun gainKg(kcalSemaine: Double?) = kcalSemaine?.let { Hall.variation(-it / 7, 30.0) }
        val candidats = reflexes.mapNotNull { (libelle, score) ->
            val (adh, nb) = adherence(entrees, dates, score) ?: return@mapNotNull null
            if (nb < Cap.MIN_REPONSES_PROPOSITION) return@mapNotNull null
            val soirsSemaine = nb.toDouble() / dates.size * 7
            val lien = mecanismes.firstOrNull { it.reflexe == libelle && it.niveau >= Niveau.PISTE && (it.ecart?.estimation ?: 0.0) < 0 }
            val gain = when {
                libelle == "Soir · pas de rab" -> (1 - adh) * soirsSemaine * KCAL_RAB
                libelle == "Soir · dessert évité ou gardé" -> (1 - adh) * soirsSemaine * KCAL_DESSERT
                lien != null -> (1 - adh) * soirsSemaine * -lien.ecart!!.estimation * KCAL_RAB
                else -> null
            }
            PropositionLevier(libelle, adh, gain, gainKg(gain), lien?.niveau)
        }.toMutableList()
        if (ciblePas != null) {
            val pas = dates.mapNotNull { Cap.pasRetenus(parDate[it], dimanche) }
            if (pas.size >= Cap.MIN_REPONSES_PROPOSITION) {
                val gain = maxOf(0.0, ciblePas - pas.average()) * KCAL_PAS * 7
                candidats += PropositionLevier(Cap.LEVIER_PAS, pas.count { it >= ciblePas }.toDouble() / pas.size, gain, gainKg(gain), null)
            }
        }
        val chiffres = candidats.filter { (it.gainKcalSemaine ?: 0.0) > 0 }.sortedByDescending { it.gainKcalSemaine }
        val autres = (candidats - chiffres.toSet()).sortedBy { it.adherence ?: 1.0 }
        return (chiffres + autres).take(n)
    }

    /** Le levier de la semaine qui commence le dimanche [semaine], face aux leviers proposés non choisis. */
    fun bilanLevier(
        etat: EtatObjectif,
        semaine: LocalDate,
        entrees: Map<LocalDate, EntreeJour>,
        parDate: Map<LocalDate, JourLabo>,
        reflexes: List<Pair<String, (EntreeJour) -> Double?>>,
        ciblePas: Double?,
        auj: LocalDate,
    ): BilanLevier? {
        val levier = etat.leviers[semaine] ?: return null
        val pendant = (0L..6L).map { semaine.plusDays(it) }
        val avant = (1L..14L).map { semaine.minusDays(it) }
        val scores = reflexes.toMap()
        fun variation(libelle: String): Triple<Double?, Double?, Double?> {
            val (a, b) = if (libelle == Cap.LEVIER_PAS) {
                if (ciblePas == null) return Triple(null, null, null)
                fun taux(ds: List<LocalDate>) = ds.mapNotNull { Cap.pasRetenus(parDate[it], auj) }
                    .takeIf { it.isNotEmpty() }?.let { p -> p.count { it >= ciblePas }.toDouble() / p.size }
                taux(avant) to taux(pendant)
            } else {
                val s = scores[libelle] ?: return Triple(null, null, null)
                adherence(entrees, avant, s)?.first to adherence(entrees, pendant, s)?.first
            }
            return Triple(a, b, if (a != null && b != null) b - a else null)
        }
        val (a, b, d) = variation(levier)
        val temoins = etat.propositionsLevier[semaine].orEmpty().filter { it != levier }.mapNotNull { variation(it).third }
        val vt = if (temoins.isEmpty()) null else temoins.average()
        return BilanLevier(levier, a, b, vt, temoins.size, if (d != null && vt != null) d - vt else null)
    }

    // ---------- Bilan complet ----------

    fun calculer(
        jours: List<JourLabo>,
        dimancheDemande: LocalDate,
        etat: EtatObjectif,
        reflexes: List<Pair<String, (EntreeJour) -> Double?>>,
        enPause: Set<String> = emptySet(),
    ): ResultatBilan {
        val dimanche = Cap.debutSemaine(dimancheDemande)
        val connus = jours.filter { !it.date.isAfter(dimanche) }
        val parDate = connus.associateBy { it.date }
        val entrees = connus.mapNotNull { j -> j.entree?.let { j.date to it } }.toMap()
        // Le Labo du bilan lit les réponses jusqu'au samedi soir : celle du dimanche, souvent remplie après 19 h,
        // changerait le bilan après coup.
        fun sansDimanche(js: List<JourLabo>, d: LocalDate) =
            js.filter { !it.date.isAfter(d) }.map { if (it.date == d) it.copy(entree = null) else it }
        val labo = Labo.analyser(sansDimanche(jours, dimanche), dimanche, enPause = enPause)
        val lis = labo.lissage
        val cap = Cap.analyser(connus, lis, etat, dimanche, reflexes)
        val semaine = cap.semaines.firstOrNull { it.debut == dimanche.minusDays(7) }
        val t = ChronoUnit.DAYS.between(cap.semaineDepart, dimanche).toDouble()
        val semainesDepuis = (t / 7).toInt()

        // 2. Balance de la semaine.
        val points = labo.poids.associateBy { it.date }
        val effets = labo.lendemain.associateBy { it.libelle }
        fun effet(libelle: String, x: Double = 1.0): String {
            val e = effets[libelle] ?: return "effet pas encore estimé"
            val p = e.post1 ?: return "effet pas encore estimé"
            val v = p.estimation * x
            val niveau = maxOf(e.niveau, e.niveauInverse)
            return "effet habituel ${if (v >= 0) "+" else "−"}${String.format(Locale.FRENCH, "%.1f", abs(v))} kg, " +
                if (niveau >= Niveau.PISTE) niveau.libelle else "pas encore de piste"
        }
        val balance = (6L downTo 0L).map { k ->
            val d = dimanche.minusDays(k)
            val p = points[d]
            val ecart = p?.ecart
            val veille = parDate[d.minusDays(1)]
            val e = veille?.entree
            val causes = mutableListOf<String>()
            if (e != null) {
                Labo.unitesDuJour(e)?.takeIf { it > 0 }?.let { u ->
                    causes += "alcool la veille, ${String.format(Locale.FRENCH, "%.1f", u)} u (${effet("Alcool, par unité", u)})"
                }
                if (e.reponses["diner"] == "beaux_parents") causes += "dîner chez les beaux-parents (${effet("Dîner chez les beaux-parents")})"
                if (e.reponses["resservi"] == "oui" || e.reponses["resservi"] == "un_peu") causes += "rab la veille (${effet("Soir avec rab")})"
                if (e.reponses["dessert"] == "normale") causes += "dessert la veille (${effet("Dessert pris")})"
            }
            if (veille?.longueSortie == true) causes += "longue sortie la veille (${effet("Longue sortie (1 h 15 et plus)")})"
            JourBalance(d, ecart, p?.partEau, ecart != null && abs(ecart) >= SEUIL_JOUR, causes)
        }

        // 3. Graisse et maigre sur 4 semaines.
        val comp = Labo.compartiments(connus, dimanche)
        fun variation4(l: Kalman.Lissage?): Intervalle? {
            l ?: return null
            val i = l.index(dimanche.minusDays(28)) ?: return null
            val j = l.index(dimanche) ?: return null
            val d = l.niveau(j) - l.niveau(i)
            val se = sqrt(l.varDifference(i, 28))
            return Intervalle(d, d - Stats.Z975 * se, d + Stats.Z975 * se)
        }

        // 5. Effort déclaré de la semaine ; cascade depuis le départ dès 4 semaines (v1.15).
        val reference = (1L..JOURS_REFERENCE).map { cap.semaineDepart.minusDays(it) }
        val joursSemaine = (1L..7L).map { dimanche.minusDays(it) }
        val declare = effortDeclare(parDate, joursSemaine, reference, dimanche)
        val cascade = lis?.let { CalculCascade.calculer(parDate, it, cap.semaineDepart, cap.semaineDepart, dimanche) }

        // 6. Ce qu'on a appris : liens annoncés ou retirés depuis le dimanche d'avant, calculés comme ce bilan-là.
        val maintenant = labo.mecanismes
        val precedent = dimanche.minusDays(7)
        val avant = Liens.tous(Labo.analyser(sansDimanche(jours, precedent), precedent, enPause = enPause))
            .associate { it.cle to it.niveau }
        val appris = Liens.tous(labo).mapNotNull { l ->
            val a = avant[l.cle] ?: Niveau.AUCUN
            ChangementLien(l, a).takeIf {
                a != l.niveau && (Liens.annonce(l.niveau, l.inverse) || Liens.annonce(a, l.inverse))
            }
        }

        // 7. Propositions.
        val proposes = propositionsLevier(parDate, entrees, dimanche, reflexes, maintenant, cap.ciblePas)
        val planVendredi = dimanche.minusDays(2)

        return ResultatBilan(
            dimanche = dimanche,
            semaine = semaine,
            semainesDepuisDepart = semainesDepuis,
            ecartDepart = cap.niveauActuel?.let { a -> cap.niveauDepart?.let { a - it } },
            attenduHaut = Hall.variation(cap.effortHaut, t),
            attenduBas = Hall.variation(cap.effortBas, t),
            balance = balance,
            graisse4 = variation4(comp?.graisse),
            maigre4 = variation4(comp?.maigre),
            renfos = joursSemaine.sumOf { parDate[it]?.nbRenfos ?: 0 },
            proteinesGkg = semaine?.proteines?.let { p -> cap.niveauActuel?.let { p / it } },
            joursProteines = semaine?.joursProteines ?: 0,
            rythmeTropRapide = semaine?.verdict == Verdict.PLUS_VITE,
            effortDeclare = declare,
            cascade = cascade,
            appris = appris,
            mecanismes = maintenant,
            bilanLevier = bilanLevier(etat, dimanche.minusDays(7), entrees, parDate, reflexes, cap.ciblePas, dimanche),
            propositionsLevier = proposes,
            propositionBudget = Cap.propositionBudget(cap.semaines, etat, dimanche),
            budget = etat.budget(dimanche),
            soirARisque = Cap.momentQuiDerape(entrees, dimanche),
            bilanPlan = etat.plans[planVendredi]?.let { Cap.bilanPlan(it, entrees) },
        )
    }
}
