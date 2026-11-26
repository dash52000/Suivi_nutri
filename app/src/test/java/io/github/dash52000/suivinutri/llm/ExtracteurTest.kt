package io.github.dash52000.suivinutri.llm

import io.github.dash52000.suivinutri.repas.Moment
import io.github.dash52000.suivinutri.repas.Origine
import io.github.dash52000.suivinutri.repas.PhraseRepas
import io.github.dash52000.suivinutri.repas.StockRepas
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate

/** Étape 6 : la lecture d'une phrase par la chaîne est rangée dans `repas.json`, ou la phrase reste en attente. */
class ExtracteurTest {

    private lateinit var dossier: File
    private lateinit var stock: StockRepas
    private val jour = LocalDate.of(2026, 12, 2)
    private val t0 = Instant.parse("2026-12-02T19:00:00Z")
    private val gemini = Maillon(Fournisseur.GEMINI, "gemini-3.8-flash")
    private val hydrogen = Maillon(Fournisseur.GEMINI, "gemini-4-hydrogen")

    private val brut = """{"aliments":[{"nom":"pâtes","grammes":200,"portion_source":"standard","moment":null,"rab":false}],"boissons":[],"tags":{"lieu":null,"rab":null,"dessert":null,"alcool":null},"confiance":"moyenne"}"""
    private val appels = mutableListOf<Pair<String, String>>()
    private val evenements = mutableListOf<Pair<String, String>>()
    private var ciqual = "2025-11-03"
    private var horloge = t0
    private var suivant: (String, String) -> ResultatChaine = { _, _ -> reussie() }

    @Before
    fun creer() {
        dossier = Files.createTempDirectory("extracteur-test").toFile()
        stock = StockRepas(File(dossier, "repas.json"))
    }

    @After
    fun nettoyer() {
        dossier.deleteRecursively()
    }

    private fun extracteur() = Extracteur(
        stock = stock,
        versionCiqual = { ciqual },
        lecteur = { texte, moment -> appels += texte to moment; suivant(texte, moment) },
        maintenant = { horloge },
        journal = { e, d -> evenements += e to d },
    )

    private fun phrase(id: String, texte: String = "pâtes", moment: Moment = Moment.SOIR, h: Instant = t0) =
        PhraseRepas(id, jour, moment, texte, Origine.TIREE, false, h).also { stock.ajouterPhrase(it) }

    private fun reussie(maillon: Maillon = gemini, quittes: List<Maillon> = emptyList(), repondu: String = maillon.modele): ResultatChaine {
        val lue = (LectureJson.lire(brut) as Lecture.Valide).extraction
        val essais = quittes.map { Tentative(it, 1, Issue.SURCHARGE, 503, 120) } + Tentative(maillon, 1, Issue.OK, 200, 800)
        return ResultatChaine.Reussie(lue, brut, maillon, repondu, Schema.VERSION_PROMPT, essais)
    }

    private fun echouee(raison: RaisonEchec = RaisonEchec.HORS_LIGNE): ResultatChaine =
        ResultatChaine.Echouee(raison, listOf(Tentative(gemini, 1, Issue.HORS_LIGNE)))

    @Test
    fun uneLectureReussieEstStockeeAvecLeModeleLesBasculesEtLesVersions() {
        phrase("p1")
        suivant = { _, _ -> reussie(hydrogen, quittes = listOf(gemini), repondu = "gemini-4-hydrogen-001") }
        val r = extracteur().lire("p1")
        assertTrue(r is ResultatChaine.Reussie)
        val e = stock.extractionRetenue("p1")!!
        assertEquals("gemini-4-hydrogen-001", e.modele)
        assertEquals(brut, e.jsonBrut)
        assertEquals(Schema.VERSION_PROMPT, e.versionPrompt)
        assertEquals("2025-11-03", e.versionCiqual)
        assertEquals(listOf("gemini-3.8-flash"), e.bascules)
        assertEquals(listOf("gemini-3.8-flash:surcharge:503"), e.erreurs)
        assertEquals(t0, e.horodatage)
        assertTrue(stock.phrasesEnAttente().isEmpty())
    }

    @Test
    fun laChaineRecoitLeTexteEtLeMomentEtRienD_Autre() {
        phrase("p1", "tupp de pâtes", Moment.MIDI)
        extracteur().lire("p1")
        assertEquals(listOf("tupp de pâtes" to "midi"), appels)
    }

    @Test
    fun uneLectureEchoueeNeStockeRienEtLaPhraseResteEnAttente() {
        phrase("p1")
        suivant = { _, _ -> echouee(RaisonEchec.TOUT_EN_ECHEC) }
        assertTrue(extracteur().lire("p1") is ResultatChaine.Echouee)
        assertNull(stock.extractionRetenue("p1"))
        assertEquals(listOf("p1"), stock.phrasesEnAttente().map { it.id })
        assertEquals("llm_lecture", evenements.last().first)
        assertTrue(evenements.last().second.contains("echec=tout_en_echec"))
    }

    @Test
    fun uneFoisLuePhraseNEstPasRelueAvecLesMemesVersions() {
        phrase("p1")
        val ex = extracteur()
        ex.lire("p1")
        assertNull(ex.lire("p1"))
        assertEquals(1, appels.size)
    }

    @Test
    fun uneNouvelleTableCiqualRelitLaPhraseEtGardeLAncienneExtraction() {
        phrase("p1")
        val ex = extracteur()
        ex.lire("p1")
        ciqual = "2026-06-01"
        horloge = t0.plusSeconds(3600)
        assertNotNull(ex.lire("p1"))
        assertEquals(2, appels.size)
        assertEquals(2, stock.contenu().extractions.size)
        assertEquals("2026-06-01", stock.extractionRetenue("p1")!!.versionCiqual)
    }

    @Test
    fun unePhraseInconnueNeFaitRien() {
        assertNull(extracteur().lire("absente"))
        assertTrue(appels.isEmpty())
    }

    @Test
    fun uneLectureReussieNEcritRienPourUnePhraseRetireePendantLAppel() {
        phrase("p1")
        suivant = { _, _ -> stock.supprimerPhrase("p1"); reussie() }
        extracteur().lire("p1")
        assertTrue(stock.contenu().extractions.isEmpty())
        assertTrue(evenements.last().second.contains("abandon=phrase_retiree"))
    }

    @Test
    fun enAttenteLitToutesLesPhrasesLaPlusAncienneDAbord() {
        phrase("b", h = t0.plusSeconds(60))
        phrase("a", h = t0)
        suivant = { texte, _ -> reussie() }
        assertEquals(2, extracteur().traiterEnAttente())
        assertEquals(listOf("pâtes", "pâtes"), appels.map { it.first })
        assertTrue(stock.phrasesEnAttente().isEmpty())
    }

    @Test
    fun enAttenteSArreteAuPremierEchecPourNePasAttendreSoixanteSecondesParPhrase() {
        phrase("a", "première", h = t0)
        phrase("b", "seconde", h = t0.plusSeconds(60))
        phrase("c", "troisième", h = t0.plusSeconds(120))
        suivant = { _, _ -> echouee() }
        assertEquals(0, extracteur().traiterEnAttente())
        assertEquals(listOf("première"), appels.map { it.first })
        assertEquals(3, stock.phrasesEnAttente().size)
    }

    @Test
    fun enAttenteReprendApresUnePhraseDejaLue() {
        phrase("a", "première", h = t0)
        phrase("b", "seconde", h = t0.plusSeconds(60))
        val ex = extracteur()
        ex.lire("a")
        appels.clear()
        assertEquals(1, ex.traiterEnAttente())
        assertEquals(listOf("seconde"), appels.map { it.first })
    }

    @Test
    fun leJournalDUsageGardeLesEssaisMaisJamaisLeTexteDeLaPhrase() {
        phrase("p1", "texte très privé : tupp secret")
        suivant = { _, _ -> reussie(hydrogen, quittes = listOf(gemini)) }
        extracteur().lire("p1")
        assertEquals(2, evenements.count { it.first == "llm_essai" })
        assertTrue(evenements.none { it.second.contains("privé") || it.second.contains("secret") })
        assertTrue(evenements.last().second.contains("modele=gemini-4-hydrogen"))
    }
}
