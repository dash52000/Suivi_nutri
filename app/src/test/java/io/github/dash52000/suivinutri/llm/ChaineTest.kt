package io.github.dash52000.suivinutri.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Chaîne de bascule sur des réponses figées (fiche SPEC section 9, point 4) : chaque cas d'erreur de la section 7,
 * le budget de 60 s avec une fausse horloge, le modèle ayant réellement répondu, et la place des clés dans les requêtes.
 */
class ChaineTest {

    private val cleGemini = "cle-factice-gemini-1234"
    private val cleGroq = "cle-factice-groq-5678"

    private val jsonValide = """
        {"aliments":[{"nom":"omelette","grammes":180,"portion_source":"explicite","moment":null,"rab":false}],
         "boissons":[],"tags":{"lieu":null,"rab":null,"dessert":null,"alcool":null},"confiance":"haute"}
    """.trimIndent()

    private val g1 = Fournisseurs.maillon("gemini-3.8-flash")
    private val g2 = Fournisseurs.maillon("gemini-3.6-flash")
    private val g3 = Fournisseurs.maillon("gemini-2.5-flash")
    private val q1 = Fournisseurs.maillon("openai/gpt-oss-120b")
    private val chaine = listOf(g1, g2, g3, q1)

    // --- Faux transport et fausse horloge ---

    private sealed interface Evenement {
        val dureeMs: Long
    }
    private data class Rep(val code: Int, val corps: String, override val dureeMs: Long = 1_000) : Evenement
    private data class Lance(val erreur: IOException, override val dureeMs: Long = 1_000) : Evenement

    private inner class Banc(
        script: Map<Maillon, List<Evenement>>,
        cles: Map<Fournisseur, String?> = mapOf(Fournisseur.GEMINI to cleGemini, Fournisseur.GROQ to cleGroq),
    ) {
        var maintenant = 1_000_000L
        var attenteTotale = 0L
        val appels = mutableListOf<Pair<Maillon, RequeteHttp>>()
        val delaisReponse = mutableListOf<Long>()
        private val restants = script.mapValues { it.value.toMutableList() }

        val transport = Transport { requete, _, delaiReponse ->
            val maillon = if (requete.url == Fournisseurs.URL_GROQ) q1
            else chaine.first { requete.url == Fournisseurs.URL_GEMINI + it.modele + ":generateContent" }
            appels += maillon to requete
            delaisReponse += delaiReponse
            val ev = restants[maillon]?.removeFirstOrNull() ?: error("appel non prévu à $maillon")
            if (ev is Lance && ev.erreur is DelaiDepasse) {
                maintenant += minOf(ev.dureeMs, delaiReponse) // un délai dépassé ne dure jamais plus que le délai accordé
                throw ev.erreur
            }
            maintenant += ev.dureeMs
            when (ev) {
                is Rep -> ReponseHttp(ev.code, ev.corps)
                is Lance -> throw ev.erreur
            }
        }

        val chaineTestee = Chaine(
            transport = transport,
            cles = SourceCles { cles[it] },
            horloge = { maintenant },
            attendre = { maintenant += it; attenteTotale += it },
        )

        fun lire(maillons: List<Maillon> = chaine) = chaineTestee.lire("omelette 3 œufs", "soir", maillons)
        fun appelsA(m: Maillon) = appels.count { it.first == m }
    }

    private fun geminiOk(texte: String = jsonValide, version: String? = "gemini-3.8-flash-001") = buildString {
        append("""{"candidates":[{"content":{"role":"model","parts":[{"text":""")
        append(kotlinx.serialization.json.JsonPrimitive(texte).toString())
        append("}]}}]")
        if (version != null) append(""","modelVersion":"$version"""")
        append("}")
    }

    private fun groqOk(texte: String = jsonValide, modele: String = "openai/gpt-oss-120b") =
        """{"model":"$modele","choices":[{"index":0,"message":{"role":"assistant","content":${kotlinx.serialization.json.JsonPrimitive(texte)}}}]}"""

    private val delai = Lance(DelaiDepasse("Read timed out"), dureeMs = 20_000)

    // --- Succès ---

    @Test
    fun premierMaillonRepondEtLeModeleRenvoyeEstEnregistre() {
        val b = Banc(mapOf(g1 to listOf(Rep(200, geminiOk()))))
        val r = b.lire() as ResultatChaine.Reussie
        assertEquals(g1, r.maillon)
        assertEquals("gemini-3.8-flash-001", r.modeleRepondu)
        assertEquals(Schema.VERSION_PROMPT, r.versionPrompt)
        assertEquals("omelette", r.extraction.aliments.single().nom)
        assertTrue(r.bascules.isEmpty())
        assertEquals(1, b.appels.size)
    }

    @Test
    fun sansNomRenvoyeOnGardeLeNomDemande() {
        val b = Banc(mapOf(g1 to listOf(Rep(200, geminiOk(version = null)))))
        assertEquals("gemini-3.8-flash", (b.lire() as ResultatChaine.Reussie).modeleRepondu)
    }

    @Test
    fun nomRedirigeParLApiFaitFoi() {
        // Un nom retiré peut être servi par un autre modèle (3.7 Flash → 3.8 Flash) : seul le modèle renvoyé compte.
        val b = Banc(mapOf(g2 to listOf(Rep(200, geminiOk(version = "gemini-3.8-flash")))))
        assertEquals("gemini-3.8-flash", (b.lire(listOf(g2)) as ResultatChaine.Reussie).modeleRepondu)
    }

    @Test
    fun partiesDeReflexionGeminiIgnorees() {
        val corps = """{"candidates":[{"content":{"parts":[{"text":"je réfléchis…","thought":true},""" +
            """{"text":${kotlinx.serialization.json.JsonPrimitive(jsonValide)}}]}}],"modelVersion":"x"}"""
        assertTrue(Banc(mapOf(g1 to listOf(Rep(200, corps)))).lire() is ResultatChaine.Reussie)
    }

    @Test
    fun groqRepondEnDernierRecours() {
        val b = Banc(
            mapOf(
                g1 to listOf(Rep(404, "")),
                g2 to listOf(Rep(404, "")),
                g3 to listOf(Rep(404, "")),
                q1 to listOf(Rep(200, groqOk())),
            )
        )
        val r = b.lire() as ResultatChaine.Reussie
        assertEquals(q1, r.maillon)
        assertEquals("openai/gpt-oss-120b", r.modeleRepondu)
        assertEquals(listOf(g1, g2, g3), r.bascules)
    }

    // --- 503, 429, délai : un nouvel essai après 2 s, puis maillon suivant ---

    @Test
    fun surchargePuisSuccesSurLeMemeModele() {
        val b = Banc(mapOf(g1 to listOf(Rep(503, "{}"), Rep(200, geminiOk()))))
        val r = b.lire() as ResultatChaine.Reussie
        assertEquals(g1, r.maillon)
        assertEquals(2, b.appelsA(g1))
        assertEquals(2_000L, b.attenteTotale)
        assertEquals(listOf(Issue.SURCHARGE, Issue.OK), r.tentatives.map { it.issue })
    }

    @Test
    fun surchargeDeuxFoisAlorsMaillonSuivant() {
        val b = Banc(mapOf(g1 to listOf(Rep(503, ""), Rep(503, "")), g2 to listOf(Rep(200, geminiOk()))))
        val r = b.lire() as ResultatChaine.Reussie
        assertEquals(g2, r.maillon)
        assertEquals(2, b.appelsA(g1))
        assertEquals(listOf(g1), r.bascules)
        assertEquals(2_000L, b.attenteTotale) // une seule attente : pas d'attente avant de changer de maillon
    }

    @Test
    fun autreErreurServeurTraiteeCommeUneSurcharge() {
        val b = Banc(mapOf(g1 to listOf(Rep(500, ""), Rep(502, "")), g2 to listOf(Rep(200, geminiOk()))))
        assertEquals(g2, (b.lire() as ResultatChaine.Reussie).maillon)
        assertEquals(2, b.appelsA(g1))
    }

    @Test
    fun quotaDeuxFoisAlorsMaillonSuivant() {
        val b = Banc(mapOf(g1 to listOf(Rep(429, ""), Rep(429, "")), g2 to listOf(Rep(200, geminiOk()))))
        val r = b.lire() as ResultatChaine.Reussie
        assertEquals(g2, r.maillon)
        assertEquals(listOf(Issue.QUOTA, Issue.QUOTA, Issue.OK), r.tentatives.map { it.issue })
        assertEquals(2_000L, b.attenteTotale)
    }

    @Test
    fun delaiDepasseDeuxFoisAlorsMaillonSuivant() {
        val b = Banc(mapOf(g1 to listOf(delai, delai), g2 to listOf(Rep(200, geminiOk()))))
        val r = b.lire() as ResultatChaine.Reussie
        assertEquals(g2, r.maillon)
        assertEquals(listOf(Issue.DELAI, Issue.DELAI, Issue.OK), r.tentatives.map { it.issue })
        assertEquals(20_000L, b.delaisReponse.first()) // délai de réponse de 20 s
    }

    @Test
    fun coupureReseauTraiteeCommeUnDelai() {
        val b = Banc(mapOf(g1 to listOf(Lance(IOException("reset")), Rep(200, geminiOk()))))
        val r = b.lire() as ResultatChaine.Reussie
        assertEquals(listOf(Issue.ERREUR_RESEAU, Issue.OK), r.tentatives.map { it.issue })
        assertEquals(2_000L, b.attenteTotale)
    }

    // --- JSON invalide : un nouvel essai, puis maillon suivant ---

    @Test
    fun jsonInvalidePuisValideSansAttente() {
        val b = Banc(mapOf(g1 to listOf(Rep(200, geminiOk("pas du json")), Rep(200, geminiOk()))))
        val r = b.lire() as ResultatChaine.Reussie
        assertEquals(g1, r.maillon)
        assertEquals(listOf(Issue.JSON_INVALIDE, Issue.OK), r.tentatives.map { it.issue })
        assertEquals(0L, b.attenteTotale)
    }

    @Test
    fun jsonNonConformeDeuxFoisAlorsMaillonSuivant() {
        val horsSchema = jsonValide.replace("\"haute\"", "\"certaine\"")
        val b = Banc(mapOf(g1 to listOf(Rep(200, geminiOk(horsSchema)), Rep(200, geminiOk("{}"))), g2 to listOf(Rep(200, geminiOk()))))
        val r = b.lire() as ResultatChaine.Reussie
        assertEquals(g2, r.maillon)
        assertEquals(2, b.appelsA(g1))
        assertTrue(r.tentatives.first().detail.contains("confiance"))
    }

    @Test
    fun reponseSansTexteTraiteeCommeJsonInvalide() {
        val bloquee = """{"candidates":[{"finishReason":"SAFETY"}],"modelVersion":"x"}"""
        val b = Banc(mapOf(g1 to listOf(Rep(200, bloquee), Rep(200, bloquee)), g2 to listOf(Rep(200, geminiOk()))))
        assertEquals(g2, (b.lire() as ResultatChaine.Reussie).maillon)
    }

    @Test
    fun groqSortieNonConformeEn400EstUnJsonInvalidePasUn400() {
        val erreur = """{"error":{"message":"Generated JSON does not match the expected schema","type":"invalid_request_error","code":"json_validate_failed"}}"""
        val b = Banc(mapOf(q1 to listOf(Rep(400, erreur), Rep(200, groqOk()))))
        val r = b.lire(listOf(q1)) as ResultatChaine.Reussie
        assertEquals(listOf(Issue.JSON_INVALIDE, Issue.OK), r.tentatives.map { it.issue })
    }

    // --- 401, 403 : fournisseur sauté ; 404 : modèle sauté ; 400 : pas de bascule ---

    @Test
    fun cleRefuseeSauteTousLesMaillonsDuFournisseur() {
        val b = Banc(mapOf(g1 to listOf(Rep(401, "")), q1 to listOf(Rep(200, groqOk()))))
        val r = b.lire() as ResultatChaine.Reussie
        assertEquals(q1, r.maillon)
        assertEquals(1, b.appels.count { it.first.fournisseur == Fournisseur.GEMINI })
        assertEquals(setOf(Fournisseur.GEMINI), r.clesARevoir)
        assertEquals(
            listOf(Issue.CLE_A_REVOIR, Issue.FOURNISSEUR_SAUTE, Issue.FOURNISSEUR_SAUTE, Issue.OK),
            r.tentatives.map { it.issue },
        )
    }

    @Test
    fun accesInterditSauteAussiLeFournisseur() {
        val b = Banc(mapOf(g1 to listOf(Rep(403, "")), q1 to listOf(Rep(200, groqOk()))))
        val r = b.lire() as ResultatChaine.Reussie
        assertEquals(q1, r.maillon)
        assertEquals(setOf(Fournisseur.GEMINI), r.clesARevoir)
        assertEquals(0L, b.attenteTotale)
    }

    @Test
    fun cleGeminiInvalideEn400EstUneCleARevoirPasUnArret() {
        val erreur = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT",""" +
            """"details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID"}]}}"""
        val b = Banc(mapOf(g1 to listOf(Rep(400, erreur)), q1 to listOf(Rep(200, groqOk()))))
        val r = b.lire() as ResultatChaine.Reussie
        assertEquals(q1, r.maillon)
        assertEquals(setOf(Fournisseur.GEMINI), r.clesARevoir)
        assertEquals(1, b.appelsA(g1))
    }

    @Test
    fun modeleRetireSauteSansNouvelEssai() {
        val b = Banc(mapOf(g1 to listOf(Rep(404, "")), g2 to listOf(Rep(200, geminiOk()))))
        val r = b.lire() as ResultatChaine.Reussie
        assertEquals(g2, r.maillon)
        assertEquals(1, b.appelsA(g1))
        assertEquals(listOf("gemini-3.8-flash"), r.modelesRetires)
        assertEquals(0L, b.attenteTotale)
    }

    @Test
    fun requeteRefuseeArreteLaChaineSansBascule() {
        val b = Banc(mapOf(g1 to listOf(Rep(400, """{"error":{"code":400}}"""))))
        val r = b.lire() as ResultatChaine.Echouee
        assertEquals(RaisonEchec.REQUETE_REFUSEE, r.raison)
        assertEquals(1, b.appels.size)
        assertEquals(400, r.tentatives.single().codeHttp)
    }

    // --- Tout en échec, hors ligne, sans clé ---

    @Test
    fun toutEnEchecLaPhraseResteEnAttente() {
        val b = Banc(
            mapOf(
                g1 to listOf(Rep(503, ""), Rep(503, "")),
                g2 to listOf(Rep(429, ""), Rep(429, "")),
                g3 to listOf(Rep(200, geminiOk("{")), Rep(200, geminiOk("{"))),
                q1 to listOf(Rep(503, ""), Rep(503, "")),
            )
        )
        val r = b.lire() as ResultatChaine.Echouee
        assertEquals(RaisonEchec.TOUT_EN_ECHEC, r.raison)
        assertEquals(8, b.appels.size)
        assertTrue(b.maintenant - 1_000_000L <= 60_000L)
    }

    @Test
    fun horsLigneArretImmediat() {
        val b = Banc(mapOf(g1 to listOf(Lance(HorsLigne("hôte inconnu")))))
        val r = b.lire() as ResultatChaine.Echouee
        assertEquals(RaisonEchec.HORS_LIGNE, r.raison)
        assertEquals(1, b.appels.size)
    }

    @Test
    fun sansCleGroqLeMaillonGroqEstSaute() {
        val b = Banc(mapOf(g1 to listOf(Rep(404, "")), g2 to listOf(Rep(404, "")), g3 to listOf(Rep(404, ""))), cles = mapOf(Fournisseur.GEMINI to cleGemini))
        val r = b.lire() as ResultatChaine.Echouee
        assertEquals(RaisonEchec.TOUT_EN_ECHEC, r.raison)
        assertEquals(Issue.SANS_CLE, r.tentatives.last().issue)
    }

    @Test
    fun sansAucuneCleAucunAppel() {
        val b = Banc(emptyMap(), cles = mapOf(Fournisseur.GEMINI to null, Fournisseur.GROQ to "  "))
        val r = b.lire() as ResultatChaine.Echouee
        assertEquals(RaisonEchec.AUCUNE_CLE, r.raison)
        assertTrue(b.appels.isEmpty())
    }

    // --- Budget de 60 s (fausse horloge) ---

    @Test
    fun budgetEpuiseLesMaillonsRestantsSontSautes() {
        // g1 : 20 s + 2 s + 20 s = 42 s ; g2 : délai réduit au restant (18 s) → 60 s ; plus rien ensuite.
        val b = Banc(mapOf(g1 to listOf(delai, delai), g2 to listOf(delai, delai), g3 to listOf(Rep(200, geminiOk()))))
        val r = b.lire() as ResultatChaine.Echouee
        assertEquals(RaisonEchec.BUDGET_EPUISE, r.raison)
        assertEquals(0, b.appelsA(g3))
        assertEquals(0, b.appelsA(q1))
        assertTrue("durée ${b.maintenant - 1_000_000L} ms", b.maintenant - 1_000_000L <= 60_000L)
        assertEquals(listOf(20_000L, 20_000L, 18_000L), b.delaisReponse) // jamais plus que le temps restant
        assertEquals(Issue.BUDGET_EPUISE, r.tentatives.last().issue)
    }

    @Test
    fun budgetPresqueEpuiseAucunNouvelAppel() {
        // Réponses lentes en échec : 29 s + 2 s + 29 s = 60 s, le maillon suivant n'est pas appelé.
        val lent = Rep(503, "", dureeMs = 29_000)
        val b = Banc(mapOf(g1 to listOf(lent, lent), g2 to listOf(Rep(200, geminiOk()))))
        val r = b.lire() as ResultatChaine.Echouee
        assertEquals(RaisonEchec.BUDGET_EPUISE, r.raison)
        assertEquals(0, b.appelsA(g2))
    }

    @Test
    fun pasDAttenteSiLeBudgetNeLePermetPlus() {
        val lent = Rep(503, "", dureeMs = 58_500)
        val b = Banc(mapOf(g1 to listOf(lent)))
        val r = b.lire() as ResultatChaine.Echouee
        assertEquals(RaisonEchec.BUDGET_EPUISE, r.raison)
        assertEquals(0L, b.attenteTotale)
    }

    // --- Requêtes : clé dans l'en-tête, rien d'autre que la phrase et le moment ---

    @Test
    fun cleGeminiDansLEnteteJamaisDansLUrlNiLeCorps() {
        val req = Fournisseurs.requete(g1, cleGemini, "omelette", "soir")
        assertEquals(cleGemini, req.entetes["x-goog-api-key"])
        assertFalse(req.url.contains(cleGemini))
        assertFalse(req.url.contains("key="))
        assertFalse(req.corps.contains(cleGemini))
        assertFalse(req.toString().contains(cleGemini))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent", req.url)
    }

    @Test
    fun cleGroqEnBearer() {
        val req = Fournisseurs.requete(q1, cleGroq, "omelette", "soir")
        assertEquals("Bearer $cleGroq", req.entetes["Authorization"])
        assertFalse(req.corps.contains(cleGroq))
        assertTrue(req.corps.contains("\"strict\":true"))
        assertTrue(req.corps.contains("\"reasoning_effort\":\"low\""))
        assertTrue(req.corps.contains("\"temperature\":0"))
    }

    @Test
    fun reflexionSelonLaFamilleGemini() {
        val recent = Fournisseurs.requete(g1, cleGemini, "x", "midi").corps
        assertTrue(recent.contains("\"thinkingLevel\":\"LOW\""))
        assertFalse(recent.contains("thinkingBudget"))
        val ancien = Fournisseurs.requete(g3, cleGemini, "x", "midi").corps
        assertTrue(ancien.contains("\"thinkingBudget\":512"))
        assertFalse(ancien.contains("thinkingLevel"))
        assertTrue(Fournisseurs.requete(Fournisseurs.maillon("gemini-4-hydrogen"), cleGemini, "x", "midi").corps.contains("\"thinkingLevel\":\"LOW\""))
    }

    @Test
    fun seulsLaPhraseEtLeMomentSontEnvoyes() {
        val corps = Fournisseurs.requete(g1, cleGemini, "  lasagnes, 2 Orval ", "soir").corps
        assertTrue(corps.contains("Moment : soir\\nPhrase : lasagnes, 2 Orval"))
        assertFalse(Regex("20\\d\\d-\\d\\d-\\d\\d").containsMatchIn(corps)) // aucune date
    }

    @Test
    fun journalSansCle() {
        val b = Banc(mapOf(g1 to listOf(Rep(401, "")), q1 to listOf(Rep(200, groqOk()))))
        val r = b.lire()
        r.tentatives.forEach {
            val ligne = it.pourJournal()
            assertFalse(ligne.contains(cleGemini))
            assertFalse(ligne.contains(cleGroq))
        }
        assertTrue(r.tentatives.first().pourJournal().contains("issue=cle_a_revoir;http=401"))
    }

    // --- Tester les modèles ---

    @Test
    fun testerUnAppelParMaillonAvecLaPhraseFixe() {
        val b = Banc(mapOf(g1 to listOf(Rep(503, "")), g2 to listOf(Rep(200, geminiOk(version = "gemini-3.6-flash-002"))), q1 to listOf(Rep(200, groqOk()))), cles = mapOf(Fournisseur.GEMINI to cleGemini, Fournisseur.GROQ to cleGroq))
        val res = b.chaineTestee.tester(listOf(g1, g2, q1))
        assertEquals(listOf(false, true, true), res.map { it.repond })
        assertEquals("gemini-3.6-flash-002", res[1].modeleRepondu)
        assertNull(res[0].modeleRepondu)
        assertEquals(3, b.appels.size) // aucun nouvel essai
        assertTrue(b.appels.all { it.second.corps.contains("omelette 3 œufs, salade, pain") })
    }

    // --- Vrai transport HTTP, contre un serveur local ---

    @Test
    fun httpTransportCodesDelaiEtHorsLigne() {
        val serveur = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        var enteteRecue: String? = null
        var corpsRecu = ""
        serveur.createContext("/ok") { ex ->
            enteteRecue = ex.requestHeaders.getFirst("x-goog-api-key")
            corpsRecu = ex.requestBody.bufferedReader().readText()
            val r = "{\"ok\":\"é\"}".toByteArray()
            ex.sendResponseHeaders(200, r.size.toLong()); ex.responseBody.use { it.write(r) }
        }
        serveur.createContext("/503") { ex ->
            val r = "surcharge".toByteArray()
            ex.sendResponseHeaders(503, r.size.toLong()); ex.responseBody.use { it.write(r) }
        }
        serveur.createContext("/lent") { ex ->
            Thread.sleep(1_500)
            ex.sendResponseHeaders(200, -1); ex.close()
        }
        serveur.executor = java.util.concurrent.Executors.newCachedThreadPool()
        serveur.start()
        try {
            val base = "http://127.0.0.1:${serveur.address.port}"
            val ok = HttpTransport.envoyer(RequeteHttp("$base/ok", mapOf("x-goog-api-key" to cleGemini), "{\"a\":\"œuf\"}"), 2_000, 2_000)
            assertEquals(ReponseHttp(200, "{\"ok\":\"é\"}"), ok)
            assertEquals(cleGemini, enteteRecue)
            assertEquals("{\"a\":\"œuf\"}", corpsRecu)
            assertEquals(ReponseHttp(503, "surcharge"), HttpTransport.envoyer(RequeteHttp("$base/503", emptyMap(), "{}"), 2_000, 2_000))
            try {
                HttpTransport.envoyer(RequeteHttp("$base/lent", emptyMap(), "{}"), 2_000, 300)
                fail("délai attendu")
            } catch (e: DelaiDepasse) {
                // attendu
            }
        } finally {
            serveur.stop(0)
        }
        val portFerme = java.net.ServerSocket(0).use { it.localPort }
        try {
            HttpTransport.envoyer(RequeteHttp("http://127.0.0.1:$portFerme/x", emptyMap(), "{}"), 1_000, 1_000)
            fail("hors ligne attendu")
        } catch (e: HorsLigne) {
            // attendu
        }
    }

    // --- Chaîne de Réglages ---

    @Test
    fun chaineParDefautEtSaisie() {
        assertEquals(8, Fournisseurs.CHAINE_DEFAUT.size)
        assertEquals(Fournisseur.GROQ, Fournisseurs.CHAINE_DEFAUT.last().fournisseur)
        assertTrue(Fournisseurs.CHAINE_DEFAUT.dropLast(1).all { it.fournisseur == Fournisseur.GEMINI })
        val texte = Fournisseurs.chaineVersTexte(Fournisseurs.CHAINE_DEFAUT)
        assertEquals(Fournisseurs.CHAINE_DEFAUT, Fournisseurs.chaineDepuisTexte(texte))
        assertEquals(
            listOf(Fournisseurs.maillon("gemini-3.8-flash"), Fournisseurs.maillon("openai/gpt-oss-120b")),
            Fournisseurs.chaineDepuisTexte("\n models/gemini-3.8-flash \n\nopenai/gpt-oss-120b\ngemini-3.8-flash\nn'importe quoi\n"),
        )
    }
}
