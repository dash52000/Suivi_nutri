package io.github.dash52000.suivinutri.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

enum class TypePerso(val libelle: String, val options: List<Option>) {
    OUI_NON("Oui / Non", listOf(Option("oui", "Oui"), Option("non", "Non"))),
    ECHELLE("Non / Un peu / Oui", listOf(Option("non", "Non"), Option("un_peu", "Un peu"), Option("oui", "Oui"))),
}

/** Une question ajoutée par l'utilisateur. [tenuSi] = la réponse qui compte comme « réflexe tenu ». */
data class QuestionPerso(
    val id: String,
    val libelle: String,
    val type: TypePerso,
    val tenuSi: String,
    val condition: Condition,
) {
    fun versQuestion(): Question = Question(id, libelle, type.options, condition)

    /** 1 = réflexe tenu, 0,5 = « un peu », 0 = pas tenu, null = pas de réponse. */
    fun score(code: String?): Double? = when (code) {
        null -> null
        tenuSi -> 1.0
        "un_peu" -> 0.5
        else -> 0.0
    }
}

/** Questions en pause et questions perso, stockées dans les préférences de l'appli. */
class Configuration(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("questions", Context.MODE_PRIVATE)

    fun enPause(): Set<String> = prefs.getStringSet("en_pause", emptySet()).orEmpty().toSet()

    fun mettreEnPause(id: String, pause: Boolean) {
        if (id == Questionnaire.ID_DINER) return // conditionne d'autres questions : toujours posée
        val ids = enPause().toMutableSet()
        if (pause) ids += id else ids -= id
        prefs.edit().putStringSet("en_pause", ids).apply()
    }

    fun perso(): List<QuestionPerso> = persoDepuisJson(JSONArray(prefs.getString("perso", "[]")))

    fun ajouter(libelle: String, type: TypePerso, tenuSi: String, condition: Condition): QuestionPerso {
        val existants = perso()
        val base = "perso_" + slug(libelle).ifEmpty { "question" }
        var id = base
        var n = 2
        while (existants.any { it.id == id } || Questionnaire.questions.any { it.id == id }) id = base + "_" + n++
        val q = QuestionPerso(id, libelle.trim(), type, tenuSi, condition)
        enregistrerPerso(existants + q)
        return q
    }

    /** Supprime la définition ; les réponses déjà données restent dans le journal et la sauvegarde. */
    fun supprimer(id: String) {
        enregistrerPerso(perso().filter { it.id != id })
        mettreEnPause(id, false)
    }

    /** Questions à poser, dans l'ordre : réflexes intégrés, questions perso, puis ressentis. */
    fun questionnaire(): List<Question> {
        val pause = enPause()
        val integrees = Questionnaire.questions
        val liste = integrees.filter { !it.ressenti } + perso().map { it.versQuestion() } + integrees.filter { it.ressenti }
        return liste.filter { it.id !in pause }
    }

    /** Change dès qu'une question est ajoutée, supprimée ou mise en pause. */
    fun signature(): Int = (perso().toString() + enPause().sorted().toString()).hashCode()

    // ---------- Sauvegarde ----------

    fun versJson(): JSONObject = JSONObject()
        .put("en_pause", JSONArray(enPause().sorted()))
        .put("perso", persoVersJson(perso()))

    /** Restaure la configuration d'une sauvegarde : questions perso fusionnées par identifiant. */
    fun importer(json: JSONObject) {
        json.optJSONArray("perso")?.let { tableau ->
            val importees = persoDepuisJson(tableau)
            val parId = perso().associateBy { it.id }.toMutableMap()
            importees.forEach { parId[it.id] = it }
            enregistrerPerso(parId.values.toList())
        }
        json.optJSONArray("en_pause")?.let { tableau ->
            val ids = (0 until tableau.length()).map { tableau.getString(it) }.toSet() - Questionnaire.ID_DINER
            prefs.edit().putStringSet("en_pause", ids).apply()
        }
    }

    private fun enregistrerPerso(liste: List<QuestionPerso>) {
        prefs.edit().putString("perso", persoVersJson(liste).toString()).apply()
    }

    private fun persoVersJson(liste: List<QuestionPerso>): JSONArray {
        val tableau = JSONArray()
        liste.forEach { q ->
            tableau.put(
                JSONObject()
                    .put("id", q.id)
                    .put("libelle", q.libelle)
                    .put("type", q.type.name)
                    .put("tenu_si", q.tenuSi)
                    .put("condition", q.condition.name)
            )
        }
        return tableau
    }

    private fun persoDepuisJson(tableau: JSONArray): List<QuestionPerso> =
        (0 until tableau.length()).mapNotNull { i ->
            val o = tableau.getJSONObject(i)
            runCatching {
                QuestionPerso(
                    id = o.getString("id"),
                    libelle = o.getString("libelle"),
                    type = TypePerso.valueOf(o.getString("type")),
                    tenuSi = o.getString("tenu_si"),
                    condition = Condition.valueOf(o.getString("condition")),
                )
            }.getOrNull()
        }

    companion object {
        /** Identifiant lisible pour R : minuscules, sans accents, mots séparés par « _ ». */
        fun slug(texte: String): String =
            Normalizer.normalize(texte.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .take(30)
                .trimEnd('_')
    }
}
