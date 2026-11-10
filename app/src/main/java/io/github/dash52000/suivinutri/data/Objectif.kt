package io.github.dash52000.suivinutri.data

import android.content.Context
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Un repas ou un jour du plan du week-end, à partir du vendredi. */
data class CreneauPlan(val cle: String, val libelle: String, val decalage: Long, val midi: Boolean)

/**
 * Plan du week-end, saisi du vendredi au dimanche (v1.9) : repas « normal » ou « dehors », bières prévues par jour.
 * Une clé absente = pas encore prévu.
 */
data class PlanWeekEnd(
    val vendredi: LocalDate,
    val repas: Map<String, String> = emptyMap(),
    val bieres: Map<String, Int> = emptyMap(),
) {
    val vide: Boolean get() = repas.isEmpty() && bieres.isEmpty()

    /** Bières prévues un jour donné, null hors plan ou sans prévision ce jour-là. */
    fun bieresPrevues(date: LocalDate): Int? = PlanWeekEnd.JOURS_BIERES
        .firstOrNull { vendredi.plusDays(it.decalage) == date }
        ?.let { bieres[it.cle] }

    /** Repas prévus dehors un jour donné, null si aucun repas de ce jour n'est prévu. */
    fun repasDehorsPrevus(date: LocalDate): Int? {
        val duJour = REPAS.filter { vendredi.plusDays(it.decalage) == date && repas[it.cle] != null }
        return if (duJour.isEmpty()) null else duJour.count { repas[it.cle] == DEHORS }
    }

    companion object {
        const val NORMAL = "normal"
        const val DEHORS = "dehors"

        val REPAS: List<CreneauPlan> = listOf(
            CreneauPlan("ven_soir", "Ven soir", 0, midi = false),
            CreneauPlan("sam_midi", "Sam midi", 1, midi = true),
            CreneauPlan("sam_soir", "Sam soir", 1, midi = false),
            CreneauPlan("dim_midi", "Dim midi", 2, midi = true),
            CreneauPlan("dim_soir", "Dim soir", 2, midi = false),
        )

        val JOURS_BIERES: List<CreneauPlan> = listOf(
            CreneauPlan("ven", "Ven", 0, midi = false),
            CreneauPlan("sam", "Sam", 1, midi = false),
            CreneauPlan("dim", "Dim", 2, midi = false),
        )

        /** Vendredi du week-end auquel appartient [date] (vendredi, samedi ou dimanche), null les autres jours. */
        fun vendrediDe(date: LocalDate): LocalDate? = when (date.dayOfWeek) {
            DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY ->
                date.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY))
            else -> null
        }
    }
}

/** Tout ce que l'utilisateur a réglé ou choisi pour l'objectif de perte (v1.9). */
data class EtatObjectif(
    /** Date de départ ; les semaines comptent à partir du lundi de cette date. */
    val debut: LocalDate,
    /** Couloir de perte, en kg par semaine (négatif = perte). */
    val couloirBas: Double = COULOIR_BAS,
    val couloirHaut: Double = COULOIR_HAUT,
    /** Budget bière de départ, en équivalent bouteille de 33 cl par semaine. */
    val budgetInitial: Int = BUDGET_INITIAL,
    /** Levier choisi, par lundi de semaine (libellé du réflexe). */
    val leviers: Map<LocalDate, String> = emptyMap(),
    /** Plans du week-end, par vendredi. */
    val plans: Map<LocalDate, PlanWeekEnd> = emptyMap(),
    val signalActif: Boolean = true,
) {
    companion object {
        const val COULOIR_BAS = -0.40
        const val COULOIR_HAUT = -0.25
        const val BUDGET_INITIAL = 3
    }
}

/** Stockage de l'objectif dans les préférences de l'appli. */
class Objectif(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("objectif", Context.MODE_PRIVATE)

    init {
        // Démarrage « tout de suite » : la date du premier lancement de la v1.9.
        if (!prefs.contains(DEBUT)) prefs.edit().putString(DEBUT, LocalDate.now().toString()).apply()
    }

    fun etat(): EtatObjectif = EtatObjectif(
        debut = LocalDate.parse(prefs.getString(DEBUT, null) ?: LocalDate.now().toString()),
        budgetInitial = prefs.getInt(BUDGET, EtatObjectif.BUDGET_INITIAL),
        leviers = leviersDepuisJson(JSONObject(prefs.getString(LEVIERS, "{}") ?: "{}")),
        plans = plansDepuisJson(JSONObject(prefs.getString(PLANS, "{}") ?: "{}")),
        signalActif = prefs.getBoolean(SIGNAL, true),
    )

    /** [libelle] null : retire le levier de la semaine. */
    fun choisirLevier(lundi: LocalDate, libelle: String?) {
        val leviers = etat().leviers.toMutableMap()
        if (libelle == null) leviers.remove(lundi) else leviers[lundi] = libelle
        prefs.edit().putString(LEVIERS, leviersVersJson(leviers).toString()).apply()
    }

    fun enregistrerPlan(plan: PlanWeekEnd) {
        val plans = etat().plans.toMutableMap()
        if (plan.vide) plans.remove(plan.vendredi) else plans[plan.vendredi] = plan
        prefs.edit().putString(PLANS, plansVersJson(plans).toString()).apply()
    }

    fun reglerBudget(n: Int) {
        prefs.edit().putInt(BUDGET, n.coerceIn(0, 14)).apply()
    }

    fun reglerSignal(actif: Boolean) {
        prefs.edit().putBoolean(SIGNAL, actif).apply()
    }

    /** Repart de [date] : les semaines d'avant ne comptent plus dans le budget. Leviers et plans sont gardés. */
    fun repartir(date: LocalDate) {
        prefs.edit().putString(DEBUT, date.toString()).apply()
    }

    /** Budget de la semaine en cours, calculé par l'onglet Cap ; sert au texte du signal (qui ne lit pas le poids). */
    fun memoriserBudgetCourant(n: Int) {
        prefs.edit().putInt(BUDGET_COURANT, n).apply()
    }

    fun budgetCourant(): Int = prefs.getInt(BUDGET_COURANT, prefs.getInt(BUDGET, EtatObjectif.BUDGET_INITIAL))

    // ---------- Signal de la semaine : état technique ----------

    fun derniereSemaineSignal(): LocalDate? = prefs.getString(SIGNAL_SEMAINE, null)?.let { LocalDate.parse(it) }

    fun noterSignal(lundi: LocalDate) {
        prefs.edit().putString(SIGNAL_SEMAINE, lundi.toString()).apply()
    }

    // ---------- Sauvegarde ----------

    fun versJson(): JSONObject {
        val e = etat()
        return JSONObject()
            .put("debut", e.debut.toString())
            .put("budget_initial", e.budgetInitial)
            .put("signal_actif", e.signalActif)
            .put("leviers", leviersVersJson(e.leviers))
            .put("plans", plansVersJson(e.plans))
    }

    /** Restaure une sauvegarde : leviers et plans fusionnés, la sauvegarde l'emporte pour un même jour. */
    fun importer(json: JSONObject) {
        val e = etat()
        val leviers = e.leviers + (json.optJSONObject("leviers")?.let { leviersDepuisJson(it) } ?: emptyMap())
        val plans = e.plans + (json.optJSONObject("plans")?.let { plansDepuisJson(it) } ?: emptyMap())
        val edit = prefs.edit()
            .putString(LEVIERS, leviersVersJson(leviers).toString())
            .putString(PLANS, plansVersJson(plans).toString())
        json.optString("debut", "").takeIf { it.isNotEmpty() }?.let { edit.putString(DEBUT, it) }
        if (json.has("budget_initial")) edit.putInt(BUDGET, json.getInt("budget_initial"))
        if (json.has("signal_actif")) edit.putBoolean(SIGNAL, json.getBoolean("signal_actif"))
        edit.apply()
    }

    private fun leviersVersJson(leviers: Map<LocalDate, String>): JSONObject {
        val o = JSONObject()
        leviers.toSortedMap().forEach { (d, l) -> o.put(d.toString(), l) }
        return o
    }

    private fun leviersDepuisJson(o: JSONObject): Map<LocalDate, String> =
        o.keys().asSequence().mapNotNull { k ->
            runCatching { LocalDate.parse(k) to o.getString(k) }.getOrNull()
        }.toMap()

    private fun plansVersJson(plans: Map<LocalDate, PlanWeekEnd>): JSONObject {
        val o = JSONObject()
        plans.toSortedMap().forEach { (d, p) ->
            val repas = JSONObject()
            p.repas.forEach { (k, v) -> repas.put(k, v) }
            val bieres = JSONObject()
            p.bieres.forEach { (k, v) -> bieres.put(k, v) }
            o.put(d.toString(), JSONObject().put("repas", repas).put("bieres", bieres))
        }
        return o
    }

    private fun plansDepuisJson(o: JSONObject): Map<LocalDate, PlanWeekEnd> =
        o.keys().asSequence().mapNotNull { k ->
            runCatching {
                val p = o.getJSONObject(k)
                val repas = p.optJSONObject("repas") ?: JSONObject()
                val bieres = p.optJSONObject("bieres") ?: JSONObject()
                val vendredi = LocalDate.parse(k)
                vendredi to PlanWeekEnd(
                    vendredi = vendredi,
                    repas = repas.keys().asSequence().associateWith { repas.getString(it) },
                    bieres = bieres.keys().asSequence().associateWith { bieres.getInt(it) },
                )
            }.getOrNull()
        }.toMap()

    companion object {
        private const val DEBUT = "debut"
        private const val BUDGET = "budget_initial"
        private const val BUDGET_COURANT = "budget_courant"
        private const val LEVIERS = "leviers"
        private const val PLANS = "plans"
        private const val SIGNAL = "signal_actif"
        private const val SIGNAL_SEMAINE = "signal_semaine"
    }
}
