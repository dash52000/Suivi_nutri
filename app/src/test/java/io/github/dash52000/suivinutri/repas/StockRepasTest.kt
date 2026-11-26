package io.github.dash52000.suivinutri.repas

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate

/**
 * Fiche SPEC section 9.3 : repas.json se relit à l'identique, les phrases ne se perdent pas, une version inconnue est
 * refusée sans écriture, la sauvegarde se réimporte, l'écriture est atomique. Architecture section 3.
 */
class StockRepasTest {

    private lateinit var dossier: File
    private lateinit var fichier: File
    private lateinit var stock: StockRepas

    private val jour = LocalDate.of(2026, 11, 30)
    private val t0 = Instant.parse("2026-11-30T12:15:00Z")

    @Before
    fun creer() {
        dossier = Files.createTempDirectory("repas-test").toFile()
        fichier = File(dossier, "repas.json")
        stock = StockRepas(fichier)
    }

    @After
    fun nettoyer() {
        dossier.deleteRecursively()
    }

    private fun phrase(id: String = "p1", moment: Moment = Moment.MIDI, texte: String = "tupp de pâtes, une pomme", origine: Origine = Origine.TIREE, tardive: Boolean = false, jour: LocalDate = this.jour, h: Instant = t0) =
        PhraseRepas(id, jour, moment, texte, origine, tardive, h)

    private fun tirage(moment: Moment = Moment.MIDI, reponse: Reponse? = null, jour: LocalDate = this.jour, pi: Double = 0.6, tardive: Boolean = false) =
        TirageMoment(jour, moment, Strate.de(jour, moment, soirCharge = false), pi, graine = 42L, reponse = reponse, tardive = tardive)

    private fun extraction(phraseId: String = "p1", v: Int = 1, ciqual: String = "2020", h: Instant = t0, json: String = """{"aliments":[]}""") =
        Extraction(phraseId, json, "gemini-3-flash", v, ciqual, listOf("503 gemini"), listOf("gemini → groq"), h)

    // ---------- Fichier absent, vide, premier démarrage ----------

    @Test
    fun fichierAbsentDonneUnContenuVideSansRienEcrire() {
        val c = stock.contenu()
        assertTrue(c.phrases.isEmpty() && c.tirages.isEmpty() && c.extractions.isEmpty())
        assertNull(c.reglages.graine)
        assertNull(stock.premierTirage())
        assertTrue(stock.phrasesEnAttente().isEmpty())
        assertFalse(fichier.exists())
    }

    // ---------- Aller-retour ----------

    @Test
    fun toutSeRelitAIdentiqueApresUneNouvelleOuverture() {
        val p = listOf(
            phrase("a", Moment.MATIN, "yaourt, muesli", Origine.TIREE),
            phrase("b", Moment.SOIR, "pâtes carbonara, 2 bières \"Rochefort\" 33 cl, tiramisu\nsuite", Origine.CHARGEE, tardive = true, h = t0.plusSeconds(60)),
            phrase("c", Moment.GRIGNOTAGE, "des noix — poignée œuf", Origine.SPONTANEE, h = t0.plusSeconds(120)),
        )
        val t = listOf(
            tirage(Moment.MATIN, Reponse.PHRASE, pi = 0.15),
            tirage(Moment.SOIR, Reponse.COMME_DHAB, pi = 0.5),
            tirage(Moment.GRIGNOTAGE, Reponse.RIEN, pi = 0.4, tardive = true),
            tirage(Moment.MIDI, null, jour = jour.plusDays(1)),
        )
        val e = listOf(
            extraction("a", json = """{"aliments":[{"nom":"yaourt","moment":null}],"note":"é\"ç"}"""),
            extraction("b", v = 2, ciqual = "2025", h = t0.plusSeconds(90)),
        )
        p.forEach(stock::ajouterPhrase)
        t.forEach(stock::enregistrerTirage)
        e.forEach(stock::enregistrerExtraction)
        stock.graine { 123_456_789_012_345L }
        stock.enregistrerProbas(ProbasTirage().avec(mapOf(Strate(Moment.MIDI, TypeJour.SEMAINE) to 0.37)), LocalDate.of(2026, 12, 27))

        val relu = StockRepas(fichier).contenu() // nouvelle instance : tout vient du disque
        assertEquals(p.toSet(), relu.phrases.toSet())
        assertEquals(t.toSet(), relu.tirages.toSet())
        assertEquals(e.toSet(), relu.extractions.toSet())
        assertEquals(123_456_789_012_345L, relu.reglages.graine)
        assertEquals(LocalDate.of(2026, 12, 27), relu.reglages.dernierRecalcul)
        assertEquals(0.37, relu.reglages.probas!!.getValue(Strate(Moment.MIDI, TypeJour.SEMAINE)), 0.0)
    }

    @Test
    fun deuxSauvegardesDuMemeContenuSontIdentiquesOctetPourOctet() {
        stock.ajouterPhrase(phrase("z", h = t0.plusSeconds(5)))
        stock.ajouterPhrase(phrase("a"))
        stock.enregistrerTirage(tirage(Moment.SOIR))
        stock.enregistrerTirage(tirage(Moment.MATIN))
        val premier = fichier.readText()
        stock.ajouterPhrase(phrase("a")) // même contenu réécrit
        assertEquals(premier, fichier.readText())
        assertEquals(premier, stock.exportJson())
        // L'ordre d'insertion n'y change rien.
        val autre = File(dossier, "autre.json")
        val s2 = StockRepas(autre)
        s2.enregistrerTirage(tirage(Moment.MATIN))
        s2.enregistrerTirage(tirage(Moment.SOIR))
        s2.ajouterPhrase(phrase("a"))
        s2.ajouterPhrase(phrase("z", h = t0.plusSeconds(5)))
        assertEquals(premier, autre.readText())
    }

    @Test
    fun leFichierPorteLaVersionEtNeContientAucuneCle() {
        stock.ajouterPhrase(phrase())
        val texte = fichier.readText()
        assertTrue(texte.contains("\"version\": 1"))
        for (interdit in listOf("api_key", "apikey", "AIza", "gsk_", "Authorization", "Bearer")) assertFalse(interdit, texte.contains(interdit))
    }

    // ---------- Clés naturelles : on remplace, on ne duplique pas ----------

    @Test
    fun uneMemePhraseNeSeDuplique() {
        stock.ajouterPhrase(phrase("p1", texte = "avant"))
        stock.ajouterPhrase(phrase("p1", texte = "après"))
        assertEquals(1, stock.contenu().phrases.size)
        assertEquals("après", stock.phrase("p1")!!.texte)
        assertNull(stock.phrase("inconnue"))
    }

    @Test
    fun unTirageSeMetAJourParJourEtMoment() {
        stock.enregistrerTirage(tirage(Moment.MIDI, null))
        stock.enregistrerTirage(tirage(Moment.SOIR, null))
        stock.enregistrerTirage(tirage(Moment.MIDI, Reponse.PHRASE)) // la réponse arrive
        assertEquals(2, stock.contenu().tirages.size)
        assertEquals(Reponse.PHRASE, stock.tirage(jour, Moment.MIDI)!!.reponse)
        assertEquals(0.6, stock.tirage(jour, Moment.MIDI)!!.pi, 0.0)
        assertNull(stock.tirage(jour, Moment.MATIN))
        assertNull(stock.tirage(jour.plusDays(1), Moment.MIDI))
    }

    @Test
    fun plusieursPhrasesParMomentSontGardeesEtTrieesParHeure() {
        stock.ajouterPhrase(phrase("b", Moment.SOIR, h = t0.plusSeconds(30)))
        stock.ajouterPhrase(phrase("a", Moment.SOIR, h = t0))
        stock.ajouterPhrase(phrase("m", Moment.MATIN, h = t0.plusSeconds(99)))
        stock.ajouterPhrase(phrase("autre", Moment.SOIR, jour = jour.plusDays(1)))
        assertEquals(listOf("m", "a", "b"), stock.phrasesDuJour(jour).map { it.id })
    }

    @Test
    fun premierTirageEstLePlusAncienJour() {
        stock.enregistrerTirage(tirage(Moment.MIDI, jour = jour.plusDays(3)))
        stock.enregistrerTirage(tirage(Moment.MIDI, jour = jour))
        stock.enregistrerTirage(tirage(Moment.SOIR, jour = jour.plusDays(1)))
        assertEquals(jour, stock.premierTirage())
    }

    // ---------- Extractions ----------

    @Test
    fun extractionRetenueEstLaPlusRecenteEtPlusieursVersionsCoexistent() {
        stock.ajouterPhrase(phrase("p1"))
        assertNull(stock.extractionRetenue("p1"))
        stock.enregistrerExtraction(extraction("p1", v = 1, h = t0))
        stock.enregistrerExtraction(extraction("p1", v = 2, h = t0.plusSeconds(3600), json = """{"v":2}"""))
        stock.enregistrerExtraction(extraction("p1", v = 1, h = t0.plusSeconds(10), json = """{"v":"1bis"}""")) // même clé que la première
        val toutes = stock.contenu().extractions.filter { it.phraseId == "p1" }
        assertEquals(2, toutes.size)
        assertEquals("""{"v":2}""", stock.extractionRetenue("p1")!!.jsonBrut)
        assertEquals(2, stock.extractionRetenue("p1")!!.versionPrompt)
        // Autre table CIQUAL = autre clé.
        stock.enregistrerExtraction(extraction("p1", v = 2, ciqual = "2026", h = t0.plusSeconds(7200), json = """{"ciqual":"2026"}"""))
        assertEquals(3, stock.contenu().extractions.count { it.phraseId == "p1" })
        assertEquals("""{"ciqual":"2026"}""", stock.extractionRetenue("p1")!!.jsonBrut)
    }

    @Test
    fun phrasesEnAttenteSontCellesSansExtraction() {
        stock.ajouterPhrase(phrase("vieille", Moment.MIDI, jour = jour.minusDays(2)))
        stock.ajouterPhrase(phrase("lue", Moment.MIDI))
        stock.ajouterPhrase(phrase("soir", Moment.SOIR, h = t0.plusSeconds(1)))
        stock.ajouterPhrase(phrase("matin", Moment.MATIN))
        stock.enregistrerExtraction(extraction("lue"))
        assertEquals(listOf("vieille", "matin", "soir"), stock.phrasesEnAttente().map { it.id })
        stock.enregistrerExtraction(extraction("vieille"))
        assertEquals(listOf("matin", "soir"), stock.phrasesEnAttente().map { it.id })
    }

    @Test
    fun uneExtractionEnEchecSansJsonNeFaitPasDisparaitreLaPhrase() {
        stock.ajouterPhrase(phrase("p1"))
        // Aucune extraction écrite (hors ligne) : la phrase est là et en attente.
        assertEquals("tupp de pâtes, une pomme", stock.phrase("p1")!!.texte)
        assertEquals(listOf("p1"), stock.phrasesEnAttente().map { it.id })
    }

    // ---------- Graine et probabilités ----------

    @Test
    fun grainCreeUneFoisPuToujoursLaMeme() {
        var appels = 0
        val g = stock.graine { appels++; 777L }
        assertEquals(777L, g)
        assertEquals(777L, stock.graine { appels++; 999L })
        assertEquals(777L, StockRepas(fichier).graine { appels++; 111L })
        assertEquals(1, appels)
        // Sans fournisseur : une graine aléatoire est créée puis conservée.
        val autre = StockRepas(File(dossier, "b.json"))
        val g1 = autre.graine()
        assertEquals(g1, autre.graine())
        assertEquals(g1, StockRepas(File(dossier, "b.json")).reglages().graine)
    }

    @Test
    fun probasParDefautPuisTableEnregistree() {
        val midi = Strate(Moment.MIDI, TypeJour.SEMAINE)
        assertEquals(0.6, stock.probas().pi(midi, 3), 0.0)
        stock.enregistrerProbas(ProbasTirage().avec(mapOf(midi to 0.8)), LocalDate.of(2026, 12, 27))
        assertEquals(0.8, StockRepas(fichier).probas().pi(midi, 3), 0.0)
        assertEquals(LocalDate.of(2026, 12, 27), stock.reglages().dernierRecalcul)
        // La graine n'a pas bougé en passant par un autre réglage.
        stock.graine { 5L }
        stock.enregistrerProbas(ProbasTirage(), LocalDate.of(2027, 1, 24))
        assertEquals(5L, stock.reglages().graine)
    }

    // ---------- Versions et fichiers abîmés ----------

    @Test
    fun versionAbsenteVautUnEtLeContenuEstLu() {
        fichier.writeText(
            """{"phrases":[{"id":"x","jour":"2026-11-30","moment":"soir","texte":"soupe","origine":"tiree","tardive":false,"horodatage":"2026-11-30T19:00:00Z"}]}"""
        )
        val c = stock.contenu()
        assertEquals(1, c.phrases.size)
        assertEquals(Moment.SOIR, c.phrases[0].moment)
        assertTrue(c.tirages.isEmpty() && c.extractions.isEmpty())
        // Une écriture suivante réécrit en version 1 complète.
        stock.ajouterPhrase(phrase("y"))
        assertTrue(fichier.readText().contains("\"version\": 1"))
        assertEquals(2, stock.contenu().phrases.size)
    }

    @Test
    fun versionInconnueEstRefuseeEtLeFichierResteIntact() {
        val original = """{"version":2,"phrases":[],"truc":"nouveau"}"""
        fichier.writeText(original)
        val e = assertThrows(VersionInconnue::class.java) { stock.contenu() }
        assertEquals(2, e.version)
        assertThrows(VersionInconnue::class.java) { stock.ajouterPhrase(phrase()) }
        assertThrows(VersionInconnue::class.java) { stock.enregistrerTirage(tirage()) }
        assertThrows(VersionInconnue::class.java) { stock.graine { 1L } }
        assertEquals(original, fichier.readText())
        assertFalse(File(dossier, "repas.json.tmp").exists())
    }

    @Test
    fun jsonIllisibleLeveUneErreurSansToucherAuFichier() {
        for (abime in listOf("", "{pas du json", "[1,2,3]", """{"version":1,"phrases":[{"id":"x"}]}""", """{"version":1,"phrases":[{"id":"x","jour":"pas-une-date","moment":"soir","texte":"t","origine":"tiree","tardive":false,"horodatage":"2026-11-30T19:00:00Z"}]}""")) {
            fichier.writeText(abime)
            assertThrows("« $abime »", FormatIllisible::class.java) { stock.contenu() }
            assertThrows(FormatIllisible::class.java) { stock.ajouterPhrase(phrase()) }
            assertEquals(abime, fichier.readText())
        }
    }

    // ---------- Écriture atomique ----------

    @Test
    fun ecritureAtomiqueSansFichierTemporaireRestant() {
        repeat(20) { stock.ajouterPhrase(phrase("p$it", h = t0.plusSeconds(it.toLong()))) }
        assertEquals(setOf("repas.json"), dossier.list()!!.toSet())
        assertEquals(20, StockRepas(fichier).contenu().phrases.size)
    }

    @Test
    fun unFichierTemporaireOrphelinNeGenePasLaLecture() {
        stock.ajouterPhrase(phrase("p1"))
        File(dossier, "repas.json.tmp").writeText("{moitié d'écriture")
        assertEquals(1, StockRepas(fichier).contenu().phrases.size)
        stock.ajouterPhrase(phrase("p2")) // l'orphelin est simplement écrasé
        assertEquals(2, StockRepas(fichier).contenu().phrases.size)
        assertFalse(File(dossier, "repas.json.tmp").exists())
    }

    @Test
    fun leDossierEstCreeAuBesoin() {
        val profond = File(dossier, "a/b/repas.json")
        StockRepas(profond).ajouterPhrase(phrase())
        assertTrue(profond.exists())
    }

    // ---------- Sauvegarde : export / import ----------

    @Test
    fun exportPuisImportDansUnStockVideRetrouveTout() {
        stock.ajouterPhrase(phrase("a"))
        stock.ajouterPhrase(phrase("b", Moment.SOIR))
        stock.enregistrerTirage(tirage(Moment.SOIR, Reponse.PHRASE))
        stock.enregistrerExtraction(extraction("a"))
        stock.graine { 31337L }
        stock.enregistrerProbas(ProbasTirage(), LocalDate.of(2026, 12, 27))
        val sauvegarde = stock.exportJson()

        val neuf = StockRepas(File(dossier, "neuf.json"))
        assertEquals(2, neuf.importJson(sauvegarde))
        assertEquals(stock.contenu(), neuf.contenu())
        assertEquals(sauvegarde, neuf.exportJson())
    }

    @Test
    fun importFusionneEtLaSauvegardeL_emporteSurLesClesCommunes() {
        stock.ajouterPhrase(phrase("local", Moment.MIDI))
        stock.ajouterPhrase(phrase("commune", texte = "version locale"))
        stock.enregistrerTirage(tirage(Moment.MIDI, null))
        stock.graine { 1L }

        val ailleurs = StockRepas(File(dossier, "ailleurs.json"))
        ailleurs.ajouterPhrase(phrase("commune", texte = "version sauvegarde"))
        ailleurs.ajouterPhrase(phrase("distante", Moment.SOIR))
        ailleurs.enregistrerTirage(tirage(Moment.MIDI, Reponse.PHRASE))
        ailleurs.graine { 2L }
        val sauvegarde = ailleurs.exportJson()

        assertEquals(2, stock.importJson(sauvegarde))
        val c = stock.contenu()
        assertEquals(setOf("local", "commune", "distante"), c.phrases.map { it.id }.toSet())
        assertEquals("version sauvegarde", stock.phrase("commune")!!.texte)
        assertEquals(1, c.tirages.size)
        assertEquals(Reponse.PHRASE, c.tirages[0].reponse)
        assertEquals(2L, c.reglages.graine)
        // Réimporter la même sauvegarde ne change rien (idempotent).
        val avant = fichier.readText()
        stock.importJson(sauvegarde)
        assertEquals(avant, fichier.readText())
    }

    @Test
    fun importIllisibleOuDeVersionInconnueNeModifieRien() {
        stock.ajouterPhrase(phrase("a"))
        val avant = fichier.readText()
        assertThrows(FormatIllisible::class.java) { stock.importJson("pas du json") }
        assertThrows(VersionInconnue::class.java) { stock.importJson("""{"version":9}""") }
        assertEquals(avant, fichier.readText())
        // Une sauvegarde sans graine ne retire pas celle du stock.
        stock.graine { 77L }
        StockRepas(File(dossier, "s.json")).also { it.ajouterPhrase(phrase("b")) }.let { stock.importJson(it.exportJson()) }
        assertEquals(77L, stock.reglages().graine)
        assertNotNull(stock.phrase("b"))
    }

    @Test
    fun tousLesCasDeReponseEtDOrigineSurvivent() {
        Moment.entries.forEach { m -> stock.ajouterPhrase(phrase("p-${m.code}", m)) }
        Reponse.entries.forEachIndexed { i, r -> stock.enregistrerTirage(tirage(Moment.MIDI, r, jour = jour.plusDays(i.toLong()))) }
        Origine.entries.forEach { o -> stock.ajouterPhrase(phrase("o-${o.code}", origine = o, h = t0.plusSeconds(500))) }
        val relu = StockRepas(fichier).contenu()
        assertEquals(Moment.entries.size + Origine.entries.size, relu.phrases.size)
        assertEquals(Reponse.entries.toSet(), relu.tirages.map { it.reponse!! }.toSet())
        assertEquals(Origine.entries.toSet(), relu.phrases.filter { it.id.startsWith("o-") }.map { it.origine }.toSet())
    }

    // ---------- Étape 6 : suppression et quantité ----------

    @Test
    fun supprimerUnePhraseRetireAussiSesExtractionsEtRendFauxSiInconnue() {
        stock.ajouterPhrase(phrase("p1"))
        stock.ajouterPhrase(phrase("p2", texte = "salade"))
        stock.enregistrerExtraction(extraction("p1"))
        stock.enregistrerExtraction(extraction("p1", v = 2))
        stock.enregistrerExtraction(extraction("p2"))
        assertTrue(stock.supprimerPhrase("p1"))
        assertNull(stock.phrase("p1"))
        assertEquals(listOf("p2"), stock.contenu().extractions.map { it.phraseId })
        assertFalse(stock.supprimerPhrase("p1"))
    }

    @Test
    fun laQuantiteSeStockeSeRelitEtSEfface() {
        stock.ajouterPhrase(phrase("p1"))
        assertTrue(stock.definirQuantite("p1", Quantite.GROSSE))
        assertEquals(Quantite.GROSSE, StockRepas(fichier).phrase("p1")!!.quantite)
        assertTrue(stock.definirQuantite("p1", null))
        assertNull(StockRepas(fichier).phrase("p1")!!.quantite)
        assertFalse(stock.definirQuantite("inconnue", Quantite.PETITE))
    }

    @Test
    fun sansQuantiteLeFichierNeContientPasLaCleEtResteIdentiqueAAvantLEtapeSix() {
        stock.ajouterPhrase(phrase("p1"))
        assertFalse(fichier.readText().contains("quantite"))
        stock.definirQuantite("p1", Quantite.NORMALE)
        assertTrue(fichier.readText().contains("\"quantite\": \"normale\""))
    }

    @Test
    fun unFichierDAvantLEtapeSixSansLaCleSeLitAvecQuantiteNull() {
        stock.ajouterPhrase(phrase("p1"))
        assertNull(StockRepas(fichier).phrase("p1")!!.quantite)
    }

    @Test
    fun laQuantiteSurvitALExportEtALImport() {
        stock.ajouterPhrase(phrase("p1"))
        stock.definirQuantite("p1", Quantite.PETITE)
        val autre = StockRepas(File(dossier, "autre.json"))
        autre.importJson(stock.exportJson())
        assertEquals(Quantite.PETITE, autre.phrase("p1")!!.quantite)
    }
}
