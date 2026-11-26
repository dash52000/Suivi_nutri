package io.github.dash52000.suivinutri.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Schéma de sortie unique de la lecture d'une phrase du repas (lot v1.18 B, fiche SPEC section 7), et prompt.
 *
 * Format strict de Groq, accepté aussi par Gemini : toutes les propriétés dans `required`,
 * `additionalProperties: false`, une valeur facultative est une union avec `null`.
 * Le `moment` d'un aliment est un `anyOf` (chaîne de l'énumération ou null) plutôt qu'un `enum` contenant `null` :
 * la doc Gemini ne garantit pas `null` dans un `enum` (vérifié le 26/11/2026), `anyOf` passe chez les deux.
 *
 * Le LLM ne produit que des données : ni kcal, ni nutriments, ni texte affiché (garde-fou 6.4).
 * Toute modification du prompt ou du schéma doit incrémenter [VERSION_PROMPT] (stockée avec chaque extraction).
 */
object Schema {

    const val VERSION_PROMPT = 1

    val PORTIONS = listOf("explicite", "standard", "grosse", "petite")
    val MOMENTS_ALIMENT = listOf("matin", "midi", "apres-midi", "soir", "nuit")
    val CONFIANCES = listOf("haute", "moyenne", "basse")

    /** Moments de saisie (les quatre champs de Ce soir), envoyés avec la phrase. */
    val MOMENTS_SAISIE = listOf("matin", "midi", "soir", "grignotage")

    /** Phrase fixe du bouton « Tester les modèles » : jamais une vraie phrase de Damien. */
    const val PHRASE_TEST = "omelette 3 œufs, salade, pain"
    const val MOMENT_TEST = "soir"

    val SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonArray("required") { listOf("aliments", "boissons", "tags", "confiance").forEach { add(JsonPrimitive(it)) } }
        putJsonObject("properties") {
            putJsonObject("aliments") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    put("additionalProperties", false)
                    putJsonArray("required") {
                        listOf("nom", "grammes", "portion_source", "moment", "rab").forEach { add(JsonPrimitive(it)) }
                    }
                    putJsonObject("properties") {
                        putJsonObject("nom") { put("type", "string") }
                        putJsonObject("grammes") { put("type", "number") }
                        putJsonObject("portion_source") {
                            put("type", "string")
                            put("enum", chaines(PORTIONS))
                        }
                        putJsonObject("moment") {
                            putJsonArray("anyOf") {
                                add(buildJsonObject {
                                    put("type", "string")
                                    put("enum", chaines(MOMENTS_ALIMENT))
                                })
                                add(buildJsonObject { put("type", "null") })
                            }
                        }
                        putJsonObject("rab") { put("type", "boolean") }
                    }
                }
            }
            putJsonObject("boissons") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    put("additionalProperties", false)
                    putJsonArray("required") { listOf("nom", "format", "degre").forEach { add(JsonPrimitive(it)) } }
                    putJsonObject("properties") {
                        putJsonObject("nom") { put("type", "string") }
                        putJsonObject("format") { put("type", nullable("string")) }
                        putJsonObject("degre") { put("type", nullable("number")) }
                    }
                }
            }
            putJsonObject("tags") {
                put("type", "object")
                put("additionalProperties", false)
                putJsonArray("required") { listOf("lieu", "rab", "dessert", "alcool").forEach { add(JsonPrimitive(it)) } }
                putJsonObject("properties") {
                    putJsonObject("lieu") { put("type", nullable("string")) }
                    putJsonObject("rab") { put("type", nullable("boolean")) }
                    putJsonObject("dessert") { put("type", nullable("boolean")) }
                    putJsonObject("alcool") { put("type", nullable("boolean")) }
                }
            }
            putJsonObject("confiance") {
                put("type", "string")
                put("enum", chaines(CONFIANCES))
            }
        }
    }

    val PROMPT: String = """
        Tu lis une phrase courte, souvent dictée, qui décrit ce qu'une personne a mangé et bu à un moment de la journée.
        Tu réponds uniquement par un objet JSON conforme au schéma fourni. Aucun texte autour.

        Règles :
        - aliments : chaque aliment solide ou plat, avec un nom générique en français (« omelette », « pain », « lasagnes »).
          N'y mets pas les boissons.
        - grammes : ton estimation de la quantité mangée, en grammes. Tu ne calcules jamais de calories ni de nutriments.
        - portion_source :
          « explicite » si la quantité est donnée (grammes, nombre d'unités, « 3 œufs », « 2 tranches ») ;
          « grosse » ou « petite » si la phrase qualifie la portion (« grosse assiette », « un peu de », « petite part ») ;
          « standard » sinon, et pour les mots vagues (« des », « quelques »).
        - moment : seulement si la phrase précise l'heure ou le moment de cet aliment (« 16h » donne « apres-midi ») ; sinon null.
        - rab : true seulement pour la part resservie (« + ½ en rab », « resservi ») ; sinon false.
        - boissons : nom, format (« 33 cl », « verre », « bouteille ») et degré d'alcool s'ils sont dits ou évidents, sinon null.
        - tags : lieu (« maison », « beaux-parents », « boulot », « restaurant »…) si la phrase le dit, sinon null ;
          rab, dessert, alcool : true si la phrase le dit, false seulement si elle dit explicitement le contraire
          (« pas de dessert »), null sinon.
        - confiance : « haute » si la phrase est précise, « moyenne » si une partie est floue, « basse » si elle est vague.
        - N'invente rien : une information absente reste null.
    """.trimIndent()

    /** Message utilisateur : le texte de la phrase et son moment, rien d'autre (fiche SPEC section 7). */
    fun message(texte: String, moment: String): String = "Moment : $moment\nPhrase : ${texte.trim()}"

    private fun chaines(valeurs: List<String>): JsonArray = buildJsonArray { valeurs.forEach { add(JsonPrimitive(it)) } }

    private fun nullable(type: String): JsonArray = buildJsonArray {
        add(JsonPrimitive(type))
        add(JsonPrimitive("null"))
    }
}
