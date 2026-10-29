package io.github.dash52000.suivinutri.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.Locale

/**
 * Les réponses d'une journée. Une question absente de [reponses] = pas de réponse (NA),
 * jamais un « non ».
 */
data class EntreeJour(
    val date: LocalDate,
    val reponses: Map<String, String> = emptyMap(),
    val boissons: List<Boisson> = emptyList(),
) {
    val vide: Boolean get() = reponses.isEmpty() && boissons.isEmpty()
    val unitesAlcool: Double get() = boissons.sumOf { it.unites }
}

/**
 * Journal stocké dans un fichier JSON privé de l'appli.
 * Petit volume (une entrée par jour) : lu et écrit en entier, écriture atomique.
 */
class Journal(context: Context) {

    private val fichier = File(context.applicationContext.filesDir, "journal.json")

    @Synchronized
    fun toutes(): Map<LocalDate, EntreeJour> {
        if (!fichier.exists()) return emptyMap()
        return depuisJson(JSONObject(fichier.readText()))
    }

    fun entree(date: LocalDate): EntreeJour = toutes()[date] ?: EntreeJour(date)

    @Synchronized
    fun enregistrer(entree: EntreeJour) {
        val tout = toutes().toMutableMap()
        if (entree.vide) tout.remove(entree.date) else tout[entree.date] = entree
        ecrire(tout.values)
    }

    private fun ecrire(entrees: Collection<EntreeJour>) {
        val tmp = File(fichier.parentFile, "journal.json.tmp")
        tmp.writeText(versJson(entrees).toString(2))
        if (!tmp.renameTo(fichier)) {
            fichier.delete()
            tmp.renameTo(fichier)
        }
    }

    // ---------- Sauvegarde ----------

    fun exportJson(): String = versJson(toutes().values).toString(2)

    /** Fusionne une sauvegarde : pour un même jour, la sauvegarde l'emporte. Renvoie le nombre de jours importés. */
    @Synchronized
    fun importJson(texte: String): Int {
        val importees = depuisJson(JSONObject(texte))
        val tout = toutes().toMutableMap()
        tout.putAll(importees)
        ecrire(tout.values)
        return importees.size
    }

    /** Une ligne par jour, « NA » pour une question sans réponse : prêt pour read.csv() en R. */
    fun exportCsv(perso: List<QuestionPerso> = emptyList()): String {
        val ids = Questionnaire.questions.map { it.id } + perso.map { it.id }
        val entete = listOf("date") + ids + listOf(Questionnaire.ID_ALCOOL, "nb_boissons", "unites_alcool")
        val lignes = toutes().values.sortedBy { it.date }.map { e ->
            val cellules = mutableListOf(e.date.toString())
            ids.forEach { cellules += e.reponses[it] ?: "NA" }
            cellules += e.reponses[Questionnaire.ID_ALCOOL] ?: "NA"
            cellules += if (e.reponses[Questionnaire.ID_ALCOOL] == null) "NA" else e.boissons.size.toString()
            cellules += if (e.reponses[Questionnaire.ID_ALCOOL] == null) "NA"
            else String.format(Locale.US, "%.2f", e.unitesAlcool)
            cellules.joinToString(",")
        }
        return (listOf(entete.joinToString(",")) + lignes).joinToString("\n", postfix = "\n")
    }

    // ---------- Format JSON ----------

    private fun versJson(entrees: Collection<EntreeJour>): JSONObject {
        val jours = JSONArray()
        entrees.sortedBy { it.date }.forEach { e ->
            val reponses = JSONObject()
            e.reponses.forEach { (k, v) -> reponses.put(k, v) }
            val boissons = JSONArray()
            e.boissons.forEach { b ->
                boissons.put(JSONObject().put("style", b.style).put("cl", b.cl).put("degre", b.degre))
            }
            jours.put(
                JSONObject()
                    .put("date", e.date.toString())
                    .put("reponses", reponses)
                    .put("boissons", boissons)
            )
        }
        return JSONObject().put("format", "suivi-nutri").put("version", 1).put("jours", jours)
    }

    private fun depuisJson(racine: JSONObject): Map<LocalDate, EntreeJour> {
        val jours = racine.getJSONArray("jours")
        val resultat = mutableMapOf<LocalDate, EntreeJour>()
        for (i in 0 until jours.length()) {
            val j = jours.getJSONObject(i)
            val date = LocalDate.parse(j.getString("date"))
            val reponsesJson = j.optJSONObject("reponses") ?: JSONObject()
            val reponses = reponsesJson.keys().asSequence().associateWith { reponsesJson.getString(it) }
            val boissonsJson = j.optJSONArray("boissons") ?: JSONArray()
            val boissons = (0 until boissonsJson.length()).map { k ->
                val b = boissonsJson.getJSONObject(k)
                Boisson(b.getString("style"), b.getDouble("cl"), b.getDouble("degre"))
            }
            resultat[date] = EntreeJour(date, reponses, boissons)
        }
        return resultat
    }
}
