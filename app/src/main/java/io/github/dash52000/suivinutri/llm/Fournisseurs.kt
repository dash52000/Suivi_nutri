package io.github.dash52000.suivinutri.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

enum class Fournisseur(val libelle: String) {
    GEMINI("Gemini"),
    GROQ("Groq"),
}

/** Un maillon de la chaîne de bascule : un fournisseur et un nom de modèle épinglé. */
data class Maillon(val fournisseur: Fournisseur, val modele: String) {
    override fun toString(): String = "${fournisseur.libelle} $modele"
}

/** Ce qu'un fournisseur a renvoyé avec un code 200 : le texte (censé être du JSON) et le modèle qui a répondu. */
data class ReponseModele(val texte: String?, val modeleRepondu: String?)

/**
 * Format des requêtes et des réponses Gemini et Groq. Aucun réseau ici : construire et lire, donc testable.
 *
 * Choix vérifiés le 26/11/2026 dans la doc des fournisseurs :
 * - Gemini : clé dans l'en-tête `x-goog-api-key` (jamais dans l'URL) ; schéma dans
 *   `generationConfig.responseFormat.text` (`responseJsonSchema` est déprécié) ; réflexion par `thinkingLevel` pour
 *   Gemini 3 et plus, mais ce champ est refusé (erreur) par les modèles plus anciens : les 2.x reçoivent
 *   `thinkingBudget` à la place ; parties marquées `thought` ignorées ; modèle réel dans `modelVersion`.
 * - Groq : `Authorization: Bearer` ; `response_format` json_schema en mode strict (gpt-oss) ; `reasoning_effort` bas ;
 *   modèle réel dans `model`.
 */
object Fournisseurs {

    const val URL_GEMINI = "https://generativelanguage.googleapis.com/v1beta/models/"
    const val URL_GROQ = "https://api.groq.com/openai/v1/chat/completions"

    /** Chaîne de départ (fiche SPEC section 5, offre gratuite). Modifiable dans Réglages, jamais lue ailleurs en dur. */
    val CHAINE_DEFAUT: List<Maillon> = listOf(
        "gemini-3.8-flash",
        "gemini-4-hydrogen",
        "gemini-3.6-flash",
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "openai/gpt-oss-120b",
    ).map { maillon(it) }

    /** Budget de réflexion des Gemini 2.x (bas ; 512 est le minimum accepté par 2.5 Flash-Lite quand il réfléchit). */
    const val BUDGET_REFLEXION_2X = 512

    private val json = Json { ignoreUnknownKeys = true }

    /** Fournisseur déduit du nom : `gemini-…` (ou `models/gemini-…`) chez Google, le reste chez Groq. */
    fun maillon(modele: String): Maillon {
        val m = modele.trim().removePrefix("models/")
        return Maillon(if (m.startsWith("gemini", ignoreCase = true)) Fournisseur.GEMINI else Fournisseur.GROQ, m)
    }

    /** Chaîne saisie dans Réglages : un modèle par ligne, lignes vides et doublons ignorés. */
    fun chaineDepuisTexte(texte: String): List<Maillon> =
        texte.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.contains(' ') }.map { maillon(it) }.distinct()

    fun chaineVersTexte(chaine: List<Maillon>): String = chaine.joinToString("\n") { it.modele }

    fun requete(maillon: Maillon, cle: String, texte: String, moment: String): RequeteHttp = when (maillon.fournisseur) {
        Fournisseur.GEMINI -> requeteGemini(maillon.modele, cle, texte, moment)
        Fournisseur.GROQ -> requeteGroq(maillon.modele, cle, texte, moment)
    }

    /** Gemini 2.x et plus anciens : pas de `thinkingLevel`. */
    internal fun geminiAncien(modele: String): Boolean = Regex("^gemini-[12]\\.").containsMatchIn(modele)

    private fun requeteGemini(modele: String, cle: String, texte: String, moment: String): RequeteHttp {
        val corps = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", Schema.PROMPT) }) }
            }
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", Schema.message(texte, moment)) }) }
                })
            }
            putJsonObject("generationConfig") {
                put("temperature", 0)
                putJsonObject("responseFormat") {
                    putJsonObject("text") {
                        put("mimeType", "APPLICATION_JSON")
                        put("schema", Schema.SCHEMA)
                    }
                }
                putJsonObject("thinkingConfig") {
                    if (geminiAncien(modele)) put("thinkingBudget", BUDGET_REFLEXION_2X) else put("thinkingLevel", "LOW")
                }
            }
        }
        return RequeteHttp(
            url = URL_GEMINI + modele + ":generateContent",
            entetes = mapOf("x-goog-api-key" to cle),
            corps = corps.toString(),
        )
    }

    private fun requeteGroq(modele: String, cle: String, texte: String, moment: String): RequeteHttp {
        val corps = buildJsonObject {
            put("model", modele)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", Schema.PROMPT)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", Schema.message(texte, moment))
                })
            }
            put("temperature", 0)
            if (modele.startsWith("openai/gpt-oss")) put("reasoning_effort", "low")
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "phrase_repas")
                    put("strict", true)
                    put("schema", Schema.SCHEMA)
                }
            }
        }
        return RequeteHttp(
            url = URL_GROQ,
            entetes = mapOf("Authorization" to "Bearer $cle"),
            corps = corps.toString(),
        )
    }

    /** Lit une réponse 200. Un corps illisible ou sans texte donne `texte = null` (traité comme un JSON invalide). */
    fun lireReponse(fournisseur: Fournisseur, corps: String): ReponseModele = try {
        val o = json.parseToJsonElement(corps) as? JsonObject
        when {
            o == null -> ReponseModele(null, null)
            fournisseur == Fournisseur.GEMINI -> {
                val parties = ((o["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject)
                    ?.let { (it["content"] as? JsonObject)?.get("parts") as? JsonArray }
                val texte = parties
                    ?.mapNotNull { it as? JsonObject }
                    ?.filter { (it["thought"] as? JsonPrimitive)?.booleanOrNull != true }
                    ?.mapNotNull { (it["text"] as? JsonPrimitive)?.contentOrNull }
                    ?.joinToString("")
                    ?.ifBlank { null }
                ReponseModele(texte, (o["modelVersion"] as? JsonPrimitive)?.contentOrNull)
            }
            else -> {
                val message = ((o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
                ReponseModele(
                    (message?.get("content") as? JsonPrimitive)?.contentOrNull?.ifBlank { null },
                    (o["model"] as? JsonPrimitive)?.contentOrNull,
                )
            }
        }
    } catch (e: IllegalArgumentException) {
        ReponseModele(null, null)
    }

    /**
     * Un 400 de Groq « json_validate_failed » signale une sortie non conforme au schéma, pas une erreur de requête :
     * il est traité comme un JSON invalide (nouvel essai, puis maillon suivant), pas comme un 400.
     */
    fun estSortieNonConforme(fournisseur: Fournisseur, code: Int, corps: String): Boolean =
        fournisseur == Fournisseur.GROQ && code == 400 && corps.contains("json_validate_failed")

    /**
     * Gemini signale une clé invalide par un 400 `API_KEY_INVALID` (pas un 401) : traité comme une clé refusée
     * (fournisseur sauté), sinon une clé mal saisie arrêterait toute la chaîne, Groq compris.
     */
    fun estCleRefusee(fournisseur: Fournisseur, code: Int, corps: String): Boolean =
        fournisseur == Fournisseur.GEMINI && code == 400 && corps.contains("API_KEY_INVALID")
}
