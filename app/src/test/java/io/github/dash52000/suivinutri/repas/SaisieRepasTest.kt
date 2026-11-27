package io.github.dash52000.suivinutri.repas

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate

/**
 * Logique de l'écran Ce soir (étape 6, fiche SPEC sections 4, 5 et 9) : tirage des moments, origine des phrases, taps,
 * réponse tardive, moments ignorés, petit-déj habituel, midi pré-rempli, quantité, journal d'usage.
 */
class SaisieRepasTest {

    private lateinit var dossier: File
    private lateinit var stock: StockRepas
    private val table = TableReelle.table

    /** Mercredi 2 décembre 2026 : semaine. */
    private val aujourdhui = LocalDate.of(2026, 12, 2)
    private val hier = aujourdhui.minusDays(1)
    private var jourCourant = aujourdhui
    private var horloge = Instant.parse("2026-12-02T19:00:00Z")
    private var compteurId = 0
    private val evenements = mutableListOf<Pair<String, String>>()
    private lateinit var saisie: SaisieRepas

    private val sansTap = emptyMap<String, String>()

    @Before
    fun creer() {
        dossier = Files.createTempDirectory("saisie-test").toFile()
        stock = StockRepas(File(dossier, "repas.json"))
        saisie = nouvelle()
    }

    @After
    fun nettoyer() {
        dossier.deleteRecursively()
    }

    private fun nouvelle() = SaisieRepas(
        stock = stock,
        table = { table },
        aujourdhui = { jourCourant },
        maintenant = { horloge },
        nouvelId = { "id${++compteurId}" },
        journal = { e, d -> evenements += e to d },
    )

    /** Fixe la graine pour que les moments [demandes] soient demandés (true) ou non (false) ce [jour], avec les π de départ. */
    private fun fixerGraine(jour: LocalDate, vararg demandes: Pair<Moment, Boolean>) {
        val probas = ProbasTirage()
        val g = (1L..200_000L).first { g ->
            val t = Tirage(g, probas)
            demandes.all { (m, d) -> (t.u(jour, m) < probas.pi(Strate.de(jour, m, false), 0)) == d }
        }
        stock.graine { g }
    }

    private fun evenement(nom: String) = evenements.filter { it.first == nom }

    private fun json(vararg aliments: Pair<String, Int>) =
        """{"aliments":[${aliments.joinToString(",") { """{"nom":"${it.first}","grammes":${it.second},"portion_source":"standard","moment":null,"rab":false}""" }}],""" +
            """"boissons":[],"tags":{"lieu":null,"rab":null,"dessert":null,"alcool":null},"confiance":"moyenne"}"""

    private fun extraire(phraseId: String, vararg aliments: Pair<String, Int>) =
        stock.enregistrerExtraction(Extraction(phraseId, json(*aliments), "gemini-3.8-flash", io.github.dash52000.suivinutri.llm.Schema.VERSION_PROMPT, table.version, emptyList(), emptyList(), horloge))

    // ---------- Tirage ----------

    @Test
    fun ouvrirCreeUneLigneDeTirageAvecLaPiDeDepartEtLaGraine() {
        fixerGraine(aujourdhui, Moment.MIDI to true)
        val e = saisie.ouvrir(aujourdhui, Moment.MIDI, sansTap)
        val t = e.tirage!!
        assertEquals(ProbasDepart.MIDI, t.pi, 0.0)
        assertEquals(stock.graine(), t.graine)
        assertEquals(Strate.de(aujourdhui, Moment.MIDI, false), t.strate)
        assertNull(t.reponse)
        assertTrue(e.demande)
        assertEquals(t, stock.tirage(aujourdhui, Moment.MIDI))
        assertEquals(1, evenement("moment_demande").size)
    }

    @Test
    fun ouvrirDeuxFoisNeChangeRienEtNeJournaliseQuUneFois() {
        fixerGraine(aujourdhui, Moment.MIDI to true)
        saisie.ouvrir(aujourdhui, Moment.MIDI, sansTap)
        val avant = stock.exportJson()
        saisie.ouvrir(aujourdhui, Moment.MIDI, sansTap)
        assertEquals(avant, stock.exportJson())
        assertEquals(1, evenement("moment_demande").size)
    }

    @Test
    fun unMomentNonDemandeAUneLigneMaisPasDeDemande() {
        fixerGraine(aujourdhui, Moment.MIDI to false)
        val e = saisie.ouvrir(aujourdhui, Moment.MIDI, sansTap)
        assertNotNull(e.tirage)
        assertFalse(e.demande)
        assertTrue(evenement("moment_demande").isEmpty())
    }

    @Test
    fun hierEstDansLaFenetreMaisPasAvantHier() {
        assertNotNull(saisie.ouvrir(hier, Moment.SOIR, sansTap).tirage)
        val ancien = saisie.ouvrir(aujourdhui.minusDays(2), Moment.SOIR, sansTap)
        assertNull(ancien.tirage)
        assertFalse(ancien.demande)
        assertNull(stock.tirage(aujourdhui.minusDays(2), Moment.SOIR))
    }

    @Test
    fun unSoirQuiDevientChargeMonteAPiUnEtDevientDemande() {
        fixerGraine(aujourdhui, Moment.SOIR to false)
        val avant = saisie.ouvrir(aujourdhui, Moment.SOIR, sansTap)
        assertFalse(avant.demande)
        assertEquals(ProbasDepart.SOIR_NORMAL, avant.tirage!!.pi, 0.0)
        assertTrue(evenement("moment_demande").isEmpty())

        val apres = saisie.ouvrir(aujourdhui, Moment.SOIR, mapOf("resservi" to "un_peu"))
        assertTrue(apres.demande)
        assertEquals(1.0, apres.tirage!!.pi, 0.0)
        assertTrue(apres.tirage!!.strate.charge)
        assertEquals(1, evenement("moment_demande").size)
    }

    @Test
    fun lAlcoolRendAussiLeSoirCharge() {
        fixerGraine(aujourdhui, Moment.SOIR to false)
        assertTrue(saisie.ouvrir(aujourdhui, Moment.SOIR, mapOf("alcool" to "oui")).tirage!!.strate.charge)
    }

    @Test
    fun resserviNonEtAlcoolAucunNeChargentPasLeSoir() {
        fixerGraine(aujourdhui, Moment.SOIR to false)
        val e = saisie.ouvrir(aujourdhui, Moment.SOIR, mapOf("resservi" to "non", "alcool" to "aucun", "dessert" to "normale", "diner" to "beaux_parents"))
        assertFalse(e.tirage!!.strate.charge)
        assertFalse(e.demande)
    }

    @Test
    fun uneLigneRepondueEstFigeeMemeSiLesTapsChangent() {
        fixerGraine(aujourdhui, Moment.SOIR to true)
        saisie.ouvrir(aujourdhui, Moment.SOIR, sansTap)
        assertTrue(saisie.repondre(aujourdhui, Moment.SOIR, Reponse.REFUS, sansTap))
        val e = saisie.ouvrir(aujourdhui, Moment.SOIR, mapOf("resservi" to "oui"))
        assertEquals(ProbasDepart.SOIR_NORMAL, e.tirage!!.pi, 0.0)
        assertFalse(e.tirage!!.strate.charge)
        assertEquals(Reponse.REFUS, e.reponse)
    }

    // ---------- Phrases ----------

    @Test
    fun phraseSurUnMomentDemandeEstTireeEtMarqueLaReponse() {
        fixerGraine(aujourdhui, Moment.MIDI to true)
        val p = saisie.enregistrerPhrase(aujourdhui, Moment.MIDI, "  tupp de pâtes, une pomme ", sansTap)!!
        assertEquals(Origine.TIREE, p.origine)
        assertEquals("tupp de pâtes, une pomme", p.texte)
        assertFalse(p.tardive)
        assertEquals(Reponse.PHRASE, stock.tirage(aujourdhui, Moment.MIDI)!!.reponse)
        assertEquals(listOf(p), stock.phrasesDuJour(aujourdhui))
        assertEquals(1, evenement("moment_rempli").size)
    }

    @Test
    fun phraseSurUnMomentNonDemandeEstSpontaneeEtLaLigneResteOuverte() {
        fixerGraine(aujourdhui, Moment.MIDI to false)
        val p = saisie.enregistrerPhrase(aujourdhui, Moment.MIDI, "salade", sansTap)!!
        assertEquals(Origine.SPONTANEE, p.origine)
        assertNull(stock.tirage(aujourdhui, Moment.MIDI)!!.reponse)
    }

    @Test
    fun phraseSurUnSoirChargeEstChargee() {
        fixerGraine(aujourdhui, Moment.SOIR to false)
        val p = saisie.enregistrerPhrase(aujourdhui, Moment.SOIR, "lasagnes, rab, 2 Orval", mapOf("resservi" to "oui", "alcool" to "oui"))!!
        assertEquals(Origine.CHARGEE, p.origine)
        assertEquals(Reponse.PHRASE, stock.tirage(aujourdhui, Moment.SOIR)!!.reponse)
    }

    @Test
    fun phraseSurUnJourHorsFenetreEstSpontaneeSansLigneDeTirage() {
        val jour = aujourdhui.minusDays(5)
        val p = saisie.enregistrerPhrase(jour, Moment.SOIR, "pizza", sansTap)!!
        assertEquals(Origine.SPONTANEE, p.origine)
        assertTrue(p.tardive)
        assertNull(stock.tirage(jour, Moment.SOIR))
    }

    @Test
    fun uneChaineVideNEnregistreRien() {
        assertNull(saisie.enregistrerPhrase(aujourdhui, Moment.MIDI, "   ", sansTap))
        assertTrue(stock.contenu().phrases.isEmpty())
    }

    @Test
    fun matinMidiEtSoirNeGardentQuUnePhraseParJourMaisLeGrignotageEnCumule() {
        saisie.enregistrerPhrase(aujourdhui, Moment.MIDI, "salade", sansTap)
        val seconde = saisie.enregistrerPhrase(aujourdhui, Moment.MIDI, "salade et pain", sansTap)!!
        assertEquals(listOf(seconde), stock.phrasesDuJour(aujourdhui).filter { it.moment == Moment.MIDI })

        saisie.enregistrerPhrase(aujourdhui, Moment.GRIGNOTAGE, "16h chips", sansTap)
        saisie.enregistrerPhrase(aujourdhui, Moment.GRIGNOTAGE, "22h biscuits", sansTap)
        assertEquals(2, stock.phrasesDuJour(aujourdhui).count { it.moment == Moment.GRIGNOTAGE })
    }

    @Test
    fun corrigerUnePhraseSupprimeAussiSonExtraction() {
        val p = saisie.enregistrerPhrase(aujourdhui, Moment.MIDI, "salade", sansTap)!!
        extraire(p.id, "salade" to 150)
        saisie.enregistrerPhrase(aujourdhui, Moment.MIDI, "salade et pain", sansTap)
        assertNull(stock.extractionRetenue(p.id))
        assertTrue(stock.contenu().extractions.isEmpty())
    }

    @Test
    fun uneReponseDonneeLeLendemainEstTardiveEtGardeSaNature() {
        jourCourant = hier
        fixerGraine(hier, Moment.GRIGNOTAGE to true)
        saisie.ouvrir(hier, Moment.GRIGNOTAGE, sansTap)
        jourCourant = aujourdhui
        assertTrue(saisie.repondre(hier, Moment.GRIGNOTAGE, Reponse.RIEN, sansTap))
        val t = stock.tirage(hier, Moment.GRIGNOTAGE)!!
        assertEquals(Reponse.RIEN, t.reponse)
        assertTrue(t.tardive)
        assertEquals("oui", evenement("moment_rien").single().second.substringAfter("tardif=").take(3))
    }

    @Test
    fun uneReponseLeJourMemeNEstPasTardive() {
        fixerGraine(aujourdhui, Moment.GRIGNOTAGE to true)
        saisie.repondre(aujourdhui, Moment.GRIGNOTAGE, Reponse.RIEN, sansTap)
        assertFalse(stock.tirage(aujourdhui, Moment.GRIGNOTAGE)!!.tardive)
    }

    @Test
    fun supprimerLaDernierePhraseRendLaReponsePhraseAuMomentOuvert() {
        fixerGraine(aujourdhui, Moment.MIDI to true)
        val p = saisie.enregistrerPhrase(aujourdhui, Moment.MIDI, "salade", sansTap)!!
        saisie.supprimerPhrase(p.id)
        assertNull(stock.tirage(aujourdhui, Moment.MIDI)!!.reponse)
        assertTrue(stock.contenu().phrases.isEmpty())
    }

    @Test
    fun supprimerUneDeuxPhrasesDeGrignotageGardeLaReponse() {
        fixerGraine(aujourdhui, Moment.GRIGNOTAGE to true)
        val a = saisie.enregistrerPhrase(aujourdhui, Moment.GRIGNOTAGE, "16h chips", sansTap)!!
        saisie.enregistrerPhrase(aujourdhui, Moment.GRIGNOTAGE, "22h biscuits", sansTap)
        saisie.supprimerPhrase(a.id)
        assertEquals(Reponse.PHRASE, stock.tirage(aujourdhui, Moment.GRIGNOTAGE)!!.reponse)
    }

    // ---------- Taps ----------

    @Test
    fun rienNEstPossibleQueSurLeGrignotage() {
        fixerGraine(aujourdhui, Moment.MIDI to true, Moment.GRIGNOTAGE to true)
        assertFalse(saisie.repondre(aujourdhui, Moment.MIDI, Reponse.RIEN, sansTap))
        assertTrue(saisie.repondre(aujourdhui, Moment.GRIGNOTAGE, Reponse.RIEN, sansTap))
        assertEquals(Reponse.RIEN, stock.tirage(aujourdhui, Moment.GRIGNOTAGE)!!.reponse)
    }

    @Test
    fun pasMaintenantSurMidiEtSoirMaisPasSurLeMatin() {
        fixerGraine(aujourdhui, Moment.MIDI to true, Moment.MATIN to true)
        assertTrue(saisie.repondre(aujourdhui, Moment.MIDI, Reponse.REFUS, sansTap))
        assertFalse(saisie.repondre(aujourdhui, Moment.MATIN, Reponse.REFUS, sansTap))
        assertEquals(1, evenement("moment_refuse").size)
    }

    @Test
    fun retaperLeMemeTapLEfface() {
        fixerGraine(aujourdhui, Moment.GRIGNOTAGE to true)
        saisie.repondre(aujourdhui, Moment.GRIGNOTAGE, Reponse.RIEN, sansTap)
        assertTrue(saisie.repondre(aujourdhui, Moment.GRIGNOTAGE, Reponse.RIEN, sansTap))
        assertNull(stock.tirage(aujourdhui, Moment.GRIGNOTAGE)!!.reponse)
        assertFalse(stock.tirage(aujourdhui, Moment.GRIGNOTAGE)!!.tardive)
    }

    @Test
    fun unTapChangeUnAutreTap() {
        fixerGraine(aujourdhui, Moment.GRIGNOTAGE to true)
        saisie.repondre(aujourdhui, Moment.GRIGNOTAGE, Reponse.RIEN, sansTap)
        saisie.repondre(aujourdhui, Moment.GRIGNOTAGE, Reponse.REFUS, sansTap)
        assertEquals(Reponse.REFUS, stock.tirage(aujourdhui, Moment.GRIGNOTAGE)!!.reponse)
    }

    @Test
    fun pasDeTapSurUnMomentNonDemandeNiSurUnMomentDejaDecrit() {
        fixerGraine(aujourdhui, Moment.MIDI to false, Moment.GRIGNOTAGE to true)
        assertFalse(saisie.repondre(aujourdhui, Moment.MIDI, Reponse.REFUS, sansTap))
        saisie.enregistrerPhrase(aujourdhui, Moment.GRIGNOTAGE, "16h chips", sansTap)
        assertFalse(saisie.repondre(aujourdhui, Moment.GRIGNOTAGE, Reponse.RIEN, sansTap))
        assertEquals(Reponse.PHRASE, stock.tirage(aujourdhui, Moment.GRIGNOTAGE)!!.reponse)
    }

    @Test
    fun ecrireUnePhraseApresUnTapLeRemplace() {
        fixerGraine(aujourdhui, Moment.MIDI to true)
        saisie.repondre(aujourdhui, Moment.MIDI, Reponse.REFUS, sansTap)
        saisie.enregistrerPhrase(aujourdhui, Moment.MIDI, "salade", sansTap)
        assertEquals(Reponse.PHRASE, stock.tirage(aujourdhui, Moment.MIDI)!!.reponse)
    }

    @Test
    fun horsFenetrePasDeTap() {
        assertFalse(saisie.repondre(aujourdhui.minusDays(3), Moment.GRIGNOTAGE, Reponse.RIEN, sansTap))
    }

    @Test
    fun boutonsPossiblesSuiventLaFiche() {
        assertEquals(listOf(Reponse.COMME_DHAB), saisie.boutonsPossibles(Moment.MATIN, true))
        assertTrue(saisie.boutonsPossibles(Moment.MATIN, false).isEmpty())
        assertEquals(listOf(Reponse.REFUS), saisie.boutonsPossibles(Moment.MIDI, false))
        assertEquals(listOf(Reponse.REFUS), saisie.boutonsPossibles(Moment.SOIR, false))
        assertEquals(listOf(Reponse.RIEN, Reponse.REFUS), saisie.boutonsPossibles(Moment.GRIGNOTAGE, false))
    }

    // ---------- Moments ignorés ----------

    @Test
    fun unMomentDemandeSansReponseAvantHierPasseAIgnore() {
        val ancien = aujourdhui.minusDays(2)
        jourCourant = ancien
        fixerGraine(ancien, Moment.MIDI to true, Moment.SOIR to false)
        saisie.ouvrir(ancien, Moment.MIDI, sansTap)
        saisie.ouvrir(ancien, Moment.SOIR, sansTap)
        jourCourant = aujourdhui
        assertEquals(1, saisie.fermerAnciens())
        assertEquals(Reponse.IGNORE, stock.tirage(ancien, Moment.MIDI)!!.reponse)
        assertNull(stock.tirage(ancien, Moment.SOIR)!!.reponse) // non demandé : rien à fermer
        assertEquals(1, evenement("moment_ignore").size)
        assertEquals(0, saisie.fermerAnciens())
    }

    @Test
    fun hierEtAujourdhuiResteOuvertsPourLeRattrapage() {
        fixerGraine(hier, Moment.MIDI to true)
        saisie.ouvrir(hier, Moment.MIDI, sansTap)
        assertEquals(0, saisie.fermerAnciens())
        assertNull(stock.tirage(hier, Moment.MIDI)!!.reponse)
    }

    // ---------- Petit-déj habituel ----------

    /** Trois matins tirés déjà lus, sur les jours qui précèdent (hors fenêtre de tirage : lignes posées à la main). */
    private fun habitueMatin() {
        listOf(2L to 300, 3L to 320, 4L to 310).forEach { (retard, grammes) ->
            val jour = aujourdhui.minusDays(retard)
            val p = PhraseRepas("m$retard", jour, Moment.MATIN, "tartines", Origine.TIREE, false, horloge)
            stock.ajouterPhrase(p)
            stock.enregistrerTirage(TirageMoment(jour, Moment.MATIN, Strate.de(jour, Moment.MATIN, false), 0.5, 1L, Reponse.PHRASE))
            extraire(p.id, "pain" to grammes)
        }
    }

    @Test
    fun sansHabitudeLeMatinNeProposeAucunTapEtGardeUnePiDeCinquantePourCent() {
        fixerGraine(aujourdhui, Moment.MATIN to true)
        val v = saisie.vue(aujourdhui, Moment.MATIN, sansTap, sansTap)
        assertFalse(v.habitudeConnue)
        assertEquals(ProbasDepart.MATIN_SANS_HABITUDE, v.etat.tirage!!.pi, 0.0)
        assertTrue(v.boutons.isEmpty())
    }

    @Test
    fun avecTroisPhrasesTireesLHabitudeExisteEtLePassageAPi015Se_Fait() {
        habitueMatin()
        val h = saisie.habitudeMatin()!!
        assertEquals(3, h.nPhrases)
        assertTrue(h.kcal > 0.0)
        // La ligne du jour est créée avec la π de l'habitude (0,15), plus 0,5.
        val e = saisie.ouvrir(aujourdhui, Moment.MATIN, sansTap)
        assertEquals(ProbasDepart.MATIN, e.tirage!!.pi, 0.0)
    }

    @Test
    fun commeDhabEstProposeEtEnregistreAvecUneHabitudeSeulement() {
        habitueMatin()
        // Graine : le matin demandé avec π = 0,15.
        val g = (1L..500_000L).first { Tirage(it).u(aujourdhui, Moment.MATIN) < ProbasDepart.MATIN }
        stock.graine { g }
        val v = saisie.vue(aujourdhui, Moment.MATIN, sansTap, sansTap)
        assertTrue(v.habitudeConnue)
        assertTrue(v.etat.demande)
        assertEquals(listOf(Reponse.COMME_DHAB), v.boutons)
        assertTrue(saisie.repondre(aujourdhui, Moment.MATIN, Reponse.COMME_DHAB, sansTap))
        assertEquals(Reponse.COMME_DHAB, stock.tirage(aujourdhui, Moment.MATIN)!!.reponse)
        assertEquals(1, evenement("moment_comme_dhab").size)
    }

    @Test
    fun commeDhabSansHabitudeEstRefuse() {
        fixerGraine(aujourdhui, Moment.MATIN to true)
        assertFalse(saisie.repondre(aujourdhui, Moment.MATIN, Reponse.COMME_DHAB, sansTap))
    }

    @Test
    fun uneSpontaneeOuUneCommeDhabNApprennentPasLHabitude() {
        habitueMatin()
        val spont = PhraseRepas("s1", aujourdhui.minusDays(5), Moment.MATIN, "croissant", Origine.SPONTANEE, false, horloge)
        stock.ajouterPhrase(spont)
        extraire(spont.id, "croissant" to 400)
        assertEquals(3, saisie.habitudeMatin()!!.nPhrases)
    }

    // ---------- Midi pré-rempli, quantité, vue ----------

    @Test
    fun midiPreRempliAvecLePlatDeLaVeille() {
        val soir = PhraseRepas("sv", hier, Moment.SOIR, "lasagnes, tarte", Origine.CHARGEE, false, horloge)
        stock.ajouterPhrase(soir)
        extraire(soir.id, "tarte" to 120, "lasagnes" to 450)
        val txt = saisie.midiPrerempli(aujourdhui, mapOf("midi_quoi" to "tupp"), mapOf("tupp" to "oui"))
        assertEquals("Restes de lasagnes, assiette normale", txt)
    }

    @Test
    fun midiPreRempliSansPhraseDeLaVeilleDitLaVeille() {
        assertEquals(
            "Restes de la veille, assiette normale",
            saisie.midiPrerempli(aujourdhui, mapOf("midi_quoi" to "tupp"), mapOf("tupp" to "oui")),
        )
    }

    @Test
    fun midiNEstPasPreRempliSansTuppAujourdhuiOuSansTuppRemplisHier() {
        assertNull(saisie.midiPrerempli(aujourdhui, mapOf("midi_quoi" to "prepare"), mapOf("tupp" to "oui")))
        assertNull(saisie.midiPrerempli(aujourdhui, mapOf("midi_quoi" to "tupp"), mapOf("tupp" to "non")))
        assertNull(saisie.midiPrerempli(aujourdhui, mapOf("midi_quoi" to "tupp"), sansTap))
    }

    @Test
    fun laVueDuMidiPorteLePreRempliSeulementSansPhrase() {
        fixerGraine(aujourdhui, Moment.MIDI to true)
        val tupp = mapOf("midi_quoi" to "tupp")
        val veille = mapOf("tupp" to "oui")
        assertEquals("Restes de la veille, assiette normale", saisie.vue(aujourdhui, Moment.MIDI, tupp, veille).prerempli)
        saisie.enregistrerPhrase(aujourdhui, Moment.MIDI, "salade", tupp)
        assertNull(saisie.vue(aujourdhui, Moment.MIDI, tupp, veille).prerempli)
    }

    @Test
    fun choisirUneQuantiteLaStockePuisLEfface() {
        val p = saisie.enregistrerPhrase(aujourdhui, Moment.SOIR, "pâtes", sansTap)!!
        saisie.choisirQuantite(p.id, Quantite.GROSSE)
        assertEquals(Quantite.GROSSE, stock.phrase(p.id)!!.quantite)
        saisie.choisirQuantite(p.id, Quantite.PETITE)
        assertEquals(Quantite.PETITE, stock.phrase(p.id)!!.quantite)
        saisie.choisirQuantite(p.id, Quantite.PETITE)
        assertNull(stock.phrase(p.id)!!.quantite)
        assertEquals(3, evenement("quantite").size)
    }

    @Test
    fun laVueMontreLaLectureSeulementQuandElleExisteEtMasqueLesTapsQuandUnePhraseEstEcrite() {
        fixerGraine(aujourdhui, Moment.GRIGNOTAGE to true)
        val avant = saisie.vue(aujourdhui, Moment.GRIGNOTAGE, sansTap, sansTap)
        assertEquals(listOf(Reponse.RIEN, Reponse.REFUS), avant.boutons)

        val p = saisie.enregistrerPhrase(aujourdhui, Moment.GRIGNOTAGE, "16h chips", sansTap)!!
        val enAttente = saisie.vue(aujourdhui, Moment.GRIGNOTAGE, sansTap, sansTap)
        assertTrue(enAttente.boutons.isEmpty())
        assertTrue(enAttente.lectures.containsKey(p.id))
        assertNull(enAttente.lectures[p.id])

        extraire(p.id, "chips" to 40)
        val lue = saisie.vue(aujourdhui, Moment.GRIGNOTAGE, sansTap, sansTap).lectures[p.id]
        assertNotNull(lue)
        assertTrue(lue!!.ligneStandard)
    }

    @Test
    fun leJournalNeContientJamaisLeTexteDesPhrases() {
        fixerGraine(aujourdhui, Moment.MIDI to true)
        saisie.enregistrerPhrase(aujourdhui, Moment.MIDI, "confidentiel : tupp secret", sansTap)
        assertTrue(evenements.none { it.second.contains("confidentiel") || it.second.contains("secret") })
    }

    // ---------- Écran « Phrases lues » (Données) ----------

    @Test
    fun lignesPhrasesMontreLEtatDeLectureEtLesKcalRecalculees() {
        fixerGraine(aujourdhui, Moment.MIDI to true, Moment.SOIR to true)
        val midi = saisie.enregistrerPhrase(aujourdhui, Moment.MIDI, "lasagnes", sansTap)!!
        val soir = saisie.enregistrerPhrase(aujourdhui, Moment.SOIR, "omelette", sansTap)!!
        extraire(midi.id, "lasagnes" to 300)

        val lignes = saisie.lignesPhrases(aujourdhui)
        assertEquals(listOf(Moment.MIDI, Moment.SOIR), lignes.map { it.phrase.moment })
        val lueMidi = lignes[0]
        assertNotNull(lueMidi.lu)
        assertEquals("gemini-3.8-flash", lueMidi.modele)
        assertTrue((lueMidi.lu!!.kcal ?: 0.0) > 100.0)
        // Le soir n'est pas encore lu : ni lecture ni modèle.
        assertNull(lignes[1].lu)
        assertNull(lignes[1].modele)
        assertEquals(soir.id, lignes[1].phrase.id)
    }

    @Test
    fun lignesPhrasesSignaleLesAlimentsNonReconnusEtRespecteLaFenetre() {
        fixerGraine(aujourdhui, Moment.SOIR to true)
        fixerGraine(hier, Moment.SOIR to true)
        val vieille = PhraseRepas("vieille", aujourdhui.minusDays(20), Moment.SOIR, "x", Origine.SPONTANEE, false, horloge)
        stock.ajouterPhrase(vieille)
        val p = saisie.enregistrerPhrase(aujourdhui, Moment.SOIR, "zzqx", sansTap)!!
        extraire(p.id, "zzqxwv" to 100)

        val lignes = saisie.lignesPhrases(aujourdhui.minusDays(14))
        assertEquals(listOf(p.id), lignes.map { it.phrase.id })
        val lu = lignes.single().lu!!
        assertNull(lu.kcal)
        assertEquals(listOf("zzqxwv"), lu.nonReconnus)
    }

    @Test
    fun lignesPhrasesRangeLesJoursDuPlusRecentAuPlusAncien() {
        fixerGraine(aujourdhui, Moment.SOIR to true)
        val p1 = saisie.enregistrerPhrase(aujourdhui, Moment.SOIR, "a", sansTap)!!
        stock.ajouterPhrase(PhraseRepas("hier", hier, Moment.MIDI, "b", Origine.SPONTANEE, false, horloge))
        assertEquals(listOf(p1.id, "hier"), saisie.lignesPhrases(hier).map { it.phrase.id })
    }

    @Test
    fun lignesPhrasesSignaleUnePhraseLueAvecUnAncienPrompt() {
        fixerGraine(aujourdhui, Moment.SOIR to true)
        val p = saisie.enregistrerPhrase(aujourdhui, Moment.SOIR, "omelette", sansTap)!!
        stock.enregistrerExtraction(
            Extraction(p.id, json("omelette" to 150), "gemini-3.5-flash-lite", io.github.dash52000.suivinutri.llm.Schema.VERSION_PROMPT - 1, table.version, emptyList(), emptyList(), horloge),
        )
        val l = saisie.lignesPhrases(aujourdhui).single()
        assertTrue(l.aRelire)
        assertNotNull(l.lu)
        horloge = horloge.plusSeconds(60)
        extraire(p.id, "omelette" to 150)
        assertFalse(saisie.lignesPhrases(aujourdhui).single().aRelire)
    }
}
