package io.github.dash52000.suivinutri.data

import android.content.Context
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

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

    /** [semaine] : dimanche qui ouvre la semaine. [libelle] null : retire le levier de la semaine. */
    fun choisirLevier(semaine: LocalDate, libelle: String?) {
        val leviers = etat().leviers.toMutableMap()
        if (libelle == null) leviers.remove(semaine) else leviers[semaine] = libelle
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

    fun noterSignal(semaine: LocalDate) {
        prefs.edit().putString(SIGNAL_SEMAINE, semaine.toString()).apply()
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

    /**
     * v1.11 : les semaines commencent le dimanche. Un levier enregistré sur un lundi (v1.9, v1.10) passe au
     * dimanche de la veille, qui ouvre désormais la même semaine.
     */
    private fun leviersDepuisJson(o: JSONObject): Map<LocalDate, String> =
        o.keys().asSequence().mapNotNull { k ->
            runCatching {
                val d = LocalDate.parse(k)
                (if (d.dayOfWeek == DayOfWeek.MONDAY) d.minusDays(1) else d) to o.getString(k)
            }.getOrNull()
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
