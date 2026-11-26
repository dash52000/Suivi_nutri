package io.github.dash52000.suivinutri.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Un aliment lu dans la phrase. Les kcal ne viennent jamais d'ici (CIQUAL dans le code, étape 5). */
data class AlimentLu(
    val nom: String,
    val grammes: Double,
    val portionSource: String,
    val moment: String?,
    val rab: Boolean,
)

data class BoissonLue(val nom: String, val format: String?, val degre: Double?)

/** Tags : null = la phrase ne dit rien (le tap reste vide, NA), jamais « non » par défaut. */
data class TagsLus(val lieu: String?, val rab: Boolean?, val dessert: Boolean?, val alcool: Boolean?)

data class ExtractionLue(
    val aliments: List<AlimentLu>,
    val boissons: List<BoissonLue>,
    val tags: TagsLus,
    val confiance: String,
)

sealed interface Lecture {
    data class Valide(val extraction: ExtractionLue) : Lecture
    data class Invalide(val raison: String) : Lecture
}

/**
 * Réponse brute du LLM → [ExtractionLue] validée, ou « JSON invalide » avec sa raison.
 *
 * Contrôle complet du schéma ([Schema.SCHEMA]) côté appli, même si les fournisseurs le contraignent déjà : un modèle
 * peut ignorer une partie du schéma (Gemini ignore les mots-clés qu'il ne connaît pas). Toute clé manquante, en trop,
 * de mauvais type ou hors énumération rend la réponse invalide (un nouvel essai, puis maillon suivant).
 * Bornes de bon sens : grammes > 0 et ≤ 5 000 ; degré entre 0 et 100.
 */
object LectureJson {

    const val GRAMMES_MAX = 5_000.0

    private val json = Json { isLenient = false }

    fun lire(brut: String): Lecture = try {
        val racine = json.parseToJsonElement(sansBalises(brut))
        Lecture.Valide(extraction(objet(racine, "racine")))
    } catch (e: Invalide) {
        Lecture.Invalide(e.message ?: "JSON invalide")
    } catch (e: IllegalArgumentException) {
        // Erreur de syntaxe JSON (SerializationException en hérite).
        Lecture.Invalide("syntaxe : " + (e.message?.lineSequence()?.firstOrNull() ?: "JSON illisible"))
    }

    /** Certains modèles entourent le JSON de balises Markdown malgré le mode JSON : on les retire. */
    internal fun sansBalises(brut: String): String {
        val t = brut.trim()
        if (!t.startsWith("```")) return t
        return t.removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
    }

    private class Invalide(message: String) : Exception(message)

    private fun extraction(o: JsonObject): ExtractionLue {
        cles(o, "racine", setOf("aliments", "boissons", "tags", "confiance"))
        val aliments = tableau(o["aliments"]!!, "aliments").mapIndexed { i, e -> aliment(objet(e, "aliments[$i]"), i) }
        val boissons = tableau(o["boissons"]!!, "boissons").mapIndexed { i, e -> boisson(objet(e, "boissons[$i]"), i) }
        val tags = tags(objet(o["tags"]!!, "tags"))
        val confiance = enumeration(o["confiance"]!!, "confiance", Schema.CONFIANCES)
        return ExtractionLue(aliments, boissons, tags, confiance)
    }

    private fun aliment(o: JsonObject, i: Int): AlimentLu {
        val ou = "aliments[$i]"
        cles(o, ou, setOf("nom", "grammes", "portion_source", "moment", "rab"))
        val grammes = nombre(o["grammes"]!!, "$ou.grammes")
        if (grammes <= 0.0 || grammes > GRAMMES_MAX) throw Invalide("$ou.grammes hors bornes : $grammes")
        val moment = if (o["moment"] is JsonNull) null else enumeration(o["moment"]!!, "$ou.moment", Schema.MOMENTS_ALIMENT)
        return AlimentLu(
            nom = nonVide(o["nom"]!!, "$ou.nom"),
            grammes = grammes,
            portionSource = enumeration(o["portion_source"]!!, "$ou.portion_source", Schema.PORTIONS),
            moment = moment,
            rab = booleen(o["rab"]!!, "$ou.rab"),
        )
    }

    private fun boisson(o: JsonObject, i: Int): BoissonLue {
        val ou = "boissons[$i]"
        cles(o, ou, setOf("nom", "format", "degre"))
        val degre = o["degre"]!!.let { if (it is JsonNull) null else nombre(it, "$ou.degre") }
        if (degre != null && (degre < 0.0 || degre > 100.0)) throw Invalide("$ou.degre hors bornes : $degre")
        return BoissonLue(
            nom = nonVide(o["nom"]!!, "$ou.nom"),
            format = o["format"]!!.let { if (it is JsonNull) null else chaine(it, "$ou.format").trim().ifEmpty { null } },
            degre = degre,
        )
    }

    private fun tags(o: JsonObject): TagsLus {
        cles(o, "tags", setOf("lieu", "rab", "dessert", "alcool"))
        fun b(cle: String) = o[cle]!!.let { if (it is JsonNull) null else booleen(it, "tags.$cle") }
        return TagsLus(
            lieu = o["lieu"]!!.let { if (it is JsonNull) null else chaine(it, "tags.lieu").trim().ifEmpty { null } },
            rab = b("rab"),
            dessert = b("dessert"),
            alcool = b("alcool"),
        )
    }

    // --- Contrôles de type ---

    private fun cles(o: JsonObject, ou: String, attendues: Set<String>) {
        val manquantes = attendues - o.keys
        if (manquantes.isNotEmpty()) throw Invalide("$ou : clé(s) manquante(s) ${manquantes.sorted()}")
        val enTrop = o.keys - attendues
        if (enTrop.isNotEmpty()) throw Invalide("$ou : clé(s) en trop ${enTrop.sorted()}")
    }

    private fun objet(e: JsonElement, ou: String): JsonObject = e as? JsonObject ?: throw Invalide("$ou : objet attendu")

    private fun tableau(e: JsonElement, ou: String): JsonArray = e as? JsonArray ?: throw Invalide("$ou : tableau attendu")

    private fun chaine(e: JsonElement, ou: String): String {
        val p = e as? JsonPrimitive
        if (p == null || p is JsonNull || !p.isString) throw Invalide("$ou : chaîne attendue")
        return p.content
    }

    private fun nonVide(e: JsonElement, ou: String): String =
        chaine(e, ou).trim().ifEmpty { throw Invalide("$ou : chaîne vide") }

    private fun nombre(e: JsonElement, ou: String): Double {
        val p = e as? JsonPrimitive
        // isString exclu : « "200" » n'est pas un nombre pour le schéma.
        val v = if (p == null || p is JsonNull || p.isString) null else p.doubleOrNull
        if (v == null || !v.isFinite()) throw Invalide("$ou : nombre attendu")
        return v
    }

    private fun booleen(e: JsonElement, ou: String): Boolean {
        val p = e as? JsonPrimitive
        val v = if (p == null || p is JsonNull || p.isString) null else p.booleanOrNull
        return v ?: throw Invalide("$ou : booléen attendu")
    }

    private fun enumeration(e: JsonElement, ou: String, valeurs: List<String>): String {
        val v = chaine(e, ou)
        if (v !in valeurs) throw Invalide("$ou : « $v » hors énumération")
        return v
    }
}
