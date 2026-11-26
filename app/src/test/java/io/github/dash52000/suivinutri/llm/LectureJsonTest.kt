package io.github.dash52000.suivinutri.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Lecture des réponses du LLM : schéma contrôlé côté appli, tout écart donne « JSON invalide ». */
class LectureJsonTest {

    private val complet = """
        {
          "aliments": [
            {"nom": "lasagnes", "grammes": 450, "portion_source": "grosse", "moment": null, "rab": false},
            {"nom": "lasagnes", "grammes": 200, "portion_source": "standard", "moment": "soir", "rab": true},
            {"nom": "tarte aux pommes", "grammes": 120.5, "portion_source": "standard", "moment": null, "rab": false}
          ],
          "boissons": [
            {"nom": "Orval", "format": "33 cl", "degre": 6.2},
            {"nom": "eau", "format": null, "degre": null}
          ],
          "tags": {"lieu": "beaux-parents", "rab": true, "dessert": true, "alcool": true},
          "confiance": "moyenne"
        }
    """.trimIndent()

    private fun valide(brut: String): ExtractionLue = when (val l = LectureJson.lire(brut)) {
        is Lecture.Valide -> l.extraction
        is Lecture.Invalide -> { fail("attendu valide, raison : ${l.raison}"); error("") }
    }

    private fun invalide(brut: String): String = when (val l = LectureJson.lire(brut)) {
        is Lecture.Valide -> { fail("attendu invalide : $brut"); error("") }
        is Lecture.Invalide -> l.raison
    }

    @Test
    fun reponseCompleteLue() {
        val e = valide(complet)
        assertEquals(3, e.aliments.size)
        assertEquals(AlimentLu("lasagnes", 450.0, "grosse", null, false), e.aliments[0])
        assertEquals("soir", e.aliments[1].moment)
        assertTrue(e.aliments[1].rab)
        assertEquals(120.5, e.aliments[2].grammes, 0.0)
        assertEquals(BoissonLue("Orval", "33 cl", 6.2), e.boissons[0])
        assertEquals(BoissonLue("eau", null, null), e.boissons[1])
        assertEquals(TagsLus("beaux-parents", rab = true, dessert = true, alcool = true), e.tags)
        assertEquals("moyenne", e.confiance)
    }

    @Test
    fun tagsNullRestentNullJamaisNon() {
        val e = valide("""{"aliments":[],"boissons":[],"tags":{"lieu":null,"rab":null,"dessert":false,"alcool":null},"confiance":"basse"}""")
        assertNull(e.tags.lieu)
        assertNull(e.tags.rab)
        assertEquals(false, e.tags.dessert)
        assertNull(e.tags.alcool)
        assertTrue(e.aliments.isEmpty())
    }

    @Test
    fun balisesMarkdownRetirees() {
        valide("```json\n$complet\n```")
        valide("```\n$complet\n```")
        valide("  \n$complet\n ")
    }

    @Test
    fun syntaxeInvalide() {
        assertTrue(invalide("pas du json").startsWith("syntaxe"))
        invalide("")
        invalide(complet.dropLast(3))
        invalide("[]")
        invalide("null")
    }

    @Test
    fun cleManquante() {
        assertTrue(invalide("""{"aliments":[],"boissons":[],"tags":{"lieu":null,"rab":null,"dessert":null,"alcool":null}}""").contains("confiance"))
        assertTrue(invalide("""{"aliments":[],"boissons":[],"tags":{"lieu":null,"rab":null,"dessert":null},"confiance":"haute"}""").contains("alcool"))
        val sansRab = """{"aliments":[{"nom":"pain","grammes":50,"portion_source":"standard","moment":null}],""" +
            """"boissons":[],"tags":{"lieu":null,"rab":null,"dessert":null,"alcool":null},"confiance":"haute"}"""
        assertTrue(invalide(sansRab).contains("rab"))
        // « moment » absent n'est pas « moment » null : le format strict exige la clé.
        invalide(sansRab.replace("\"moment\":null", "\"rab\":false"))
    }

    @Test
    fun cleEnTrop() {
        assertTrue(invalide(complet.replace("\"confiance\": \"moyenne\"", "\"confiance\": \"moyenne\", \"kcal\": 900")).contains("kcal"))
        assertTrue(invalide(complet.replace("\"degre\": 6.2", "\"degre\": 6.2, \"kcal\": 180")).contains("kcal"))
    }

    @Test
    fun mauvaisTypes() {
        invalide(complet.replace("\"grammes\": 450", "\"grammes\": \"450\""))
        invalide(complet.replace("\"grammes\": 450", "\"grammes\": null"))
        invalide(complet.replace("\"rab\": false", "\"rab\": \"false\""))
        invalide(complet.replace("\"rab\": false", "\"rab\": null"))
        invalide(complet.replace("\"lieu\": \"beaux-parents\"", "\"lieu\": 3"))
        invalide(complet.replace("\"alcool\": true", "\"alcool\": \"oui\""))
        invalide(complet.replace("\"degre\": 6.2", "\"degre\": \"6,2\""))
        invalide(complet.replace("\"boissons\": [", "\"boissons\": {\"a\": [").replace("\"degre\": null}\n  ]", "\"degre\": null}\n  ]}"))
        invalide(complet.replace("\"nom\": \"Orval\"", "\"nom\": \"  \""))
    }

    @Test
    fun horsEnumeration() {
        assertTrue(invalide(complet.replace("\"grosse\"", "\"énorme\"")).contains("portion_source"))
        assertTrue(invalide(complet.replace("\"moment\": \"soir\"", "\"moment\": \"16h\"")).contains("moment"))
        assertTrue(invalide(complet.replace("\"moyenne\"", "\"certaine\"")).contains("confiance"))
    }

    @Test
    fun bornesDeBonSens() {
        invalide(complet.replace("\"grammes\": 450", "\"grammes\": 0"))
        invalide(complet.replace("\"grammes\": 450", "\"grammes\": -20"))
        invalide(complet.replace("\"grammes\": 450", "\"grammes\": 50000"))
        invalide(complet.replace("\"degre\": 6.2", "\"degre\": 140"))
        valide(complet.replace("\"grammes\": 450", "\"grammes\": 5000"))
    }

    @Test
    fun formatVideDevientNull() {
        assertNull(valide(complet.replace("\"33 cl\"", "\"  \"")).boissons[0].format)
    }

    // --- Le schéma envoyé respecte le format strict de Groq, et la lecture suit le schéma ---

    @Test
    fun schemaAuFormatStrict() {
        fun verifier(e: JsonElement, ou: String) {
            when (e) {
                is JsonObject -> {
                    if ((e["type"] as? JsonPrimitive)?.content == "object") {
                        assertEquals("$ou : additionalProperties", false, (e["additionalProperties"] as JsonPrimitive).booleanOrNull)
                        val proprietes = (e["properties"] as JsonObject).keys
                        val requises = (e["required"] as JsonArray).map { (it as JsonPrimitive).content }.toSet()
                        assertEquals("$ou : toutes les propriétés requises", proprietes, requises)
                    }
                    e.forEach { (k, v) -> verifier(v, "$ou.$k") }
                }
                is JsonArray -> e.forEachIndexed { i, v -> verifier(v, "$ou[$i]") }
                else -> Unit
            }
        }
        verifier(Schema.SCHEMA, "schema")
        assertTrue(Schema.SCHEMA.toString().contains("\"anyOf\""))
        assertTrue(!Schema.SCHEMA.toString().contains("\"enum\":[\"matin\",\"midi\",\"apres-midi\",\"soir\",\"nuit\",null]"))
    }

    @Test
    fun promptSansCalculDeCalories() {
        assertTrue(Schema.PROMPT.contains("jamais de calories"))
        assertTrue(!Schema.SCHEMA.toString().contains("kcal"))
        assertEquals("Moment : midi\nPhrase : restes de lasagnes", Schema.message("  restes de lasagnes ", "midi"))
    }
}
