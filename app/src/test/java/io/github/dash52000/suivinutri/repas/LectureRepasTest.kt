package io.github.dash52000.suivinutri.repas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Lecture d'une phrase déjà extraite : CIQUAL, marges, tap de quantité (étape 6). */
class LectureRepasTest {

    private val table = TableReelle.table
    private val t0 = Instant.parse("2026-12-02T18:00:00Z")

    private fun phrase(origine: Origine = Origine.TIREE, quantite: Quantite? = null) =
        PhraseRepas("p1", LocalDate.of(2026, 12, 2), Moment.SOIR, "texte", origine, false, t0, quantite)

    private fun extraction(json: String) = Extraction("p1", json, "gemini-3.8-flash", 1, table.version, emptyList(), emptyList(), t0)

    private fun json(aliments: String, boissons: String = "", confiance: String = "moyenne") =
        """{"aliments":[$aliments],"boissons":[$boissons],"tags":{"lieu":null,"rab":null,"dessert":null,"alcool":null},"confiance":"$confiance"}"""

    private fun aliment(nom: String, grammes: Int, portion: String) =
        """{"nom":"$nom","grammes":$grammes,"portion_source":"$portion","moment":null,"rab":false}"""

    private val pates = aliment("pâtes", 200, "standard")
    private val pomme = aliment("pomme", 150, "explicite")

    @Test
    fun uneLigneStandardDonneSesKcalEtSaMargeDeBase() {
        val lu = LectureRepas.lire(phrase(), extraction(json(pates)), table)!!
        assertNotNull(lu.kcal)
        assertTrue(lu.kcal!! > 0.0)
        assertEquals(Marges.SIGMA_STANDARD, lu.sigma!!, 1e-9)
        assertTrue(lu.ligneStandard)
        assertTrue(lu.nonReconnus.isEmpty())
    }

    @Test
    fun grosseMultiplieLesGrammesDesLignesStandardEtPasseLaMargeA030() {
        val base = LectureRepas.lire(phrase(), extraction(json(pates)), table)!!
        val grosse = LectureRepas.lire(phrase(quantite = Quantite.GROSSE), extraction(json(pates)), table)!!
        assertEquals(base.kcal!! * 1.4, grosse.kcal!!, 1e-6)
        assertEquals(Marges.SIGMA_GROSSE_OU_PETITE, grosse.sigma!!, 1e-9)
        assertEquals(PortionSource.GROSSE, grosse.aliments.single().portionSource)
        assertEquals(280.0, grosse.aliments.single().grammes, 1e-9)
    }

    @Test
    fun petiteReduitLesGrammes() {
        val base = LectureRepas.lire(phrase(), extraction(json(pates)), table)!!
        val petite = LectureRepas.lire(phrase(quantite = Quantite.PETITE), extraction(json(pates)), table)!!
        assertEquals(base.kcal!! * 0.7, petite.kcal!!, 1e-6)
        assertEquals(PortionSource.PETITE, petite.aliments.single().portionSource)
    }

    @Test
    fun normaleNeChangeRienNiLesGrammesNiLaMarge() {
        val base = LectureRepas.lire(phrase(), extraction(json(pates)), table)!!
        val normale = LectureRepas.lire(phrase(quantite = Quantite.NORMALE), extraction(json(pates)), table)!!
        assertEquals(base.kcal!!, normale.kcal!!, 1e-9)
        assertEquals(base.sigma!!, normale.sigma!!, 1e-9)
        assertEquals(PortionSource.STANDARD, normale.aliments.single().portionSource)
    }

    @Test
    fun leTapNeToucheNiLesLignesExplicitesNiLesBoissons() {
        val j = json("$pates,$pomme", """{"nom":"bière","format":"33 cl","degre":6.0}""")
        val base = LectureRepas.lire(phrase(), extraction(j), table)!!
        val grosse = LectureRepas.lire(phrase(quantite = Quantite.GROSSE), extraction(j), table)!!
        assertEquals(base.aliments[1].kcal!!, grosse.aliments[1].kcal!!, 1e-9)
        assertEquals(PortionSource.EXPLICITE, grosse.aliments[1].portionSource)
        assertEquals(base.boissons.single().kcal!!, grosse.boissons.single().kcal!!, 1e-9)
        assertEquals(base.aliments[0].kcal!! * 1.4, grosse.aliments[0].kcal!!, 1e-6)
    }

    @Test
    fun ligneStandardResteVraieApresLeTapPourQueLesBoutonsRestentAffiches() {
        assertTrue(LectureRepas.lire(phrase(quantite = Quantite.GROSSE), extraction(json(pates)), table)!!.ligneStandard)
        assertFalse(LectureRepas.lire(phrase(), extraction(json(pomme)), table)!!.ligneStandard)
    }

    @Test
    fun unNomInconnuResteNAEtEstSignale() {
        val lu = LectureRepas.lire(phrase(), extraction(json("$pomme,${aliment("zzqxy", 100, "explicite")}")), table)!!
        assertEquals(listOf("zzqxy"), lu.nonReconnus)
        assertNull(lu.aliments[1].kcal)
        assertNotNull(lu.kcal) // la pomme compte, le nom inconnu non
    }

    @Test
    fun aucuneLigneReconnueLaisseLeRepasNonDecrit() {
        val lu = LectureRepas.lire(phrase(), extraction(json(aliment("zzqxy", 100, "explicite"))), table)!!
        assertNull(lu.kcal)
        assertNull(lu.sigma)
    }

    @Test
    fun uneSpontaneeALaMargeElargie() {
        val tiree = LectureRepas.lire(phrase(Origine.TIREE), extraction(json(pomme)), table)!!
        val spontanee = LectureRepas.lire(phrase(Origine.SPONTANEE), extraction(json(pomme)), table)!!
        assertEquals(tiree.sigma!! * Marges.FACTEUR_SPONTANEE_DEPART, spontanee.sigma!!, 1e-9)
    }

    @Test
    fun platPrincipalEstLAlimentLePlusLourd() {
        val j = json("${aliment("pomme", 150, "explicite")},${aliment("lasagnes", 450, "grosse")},${aliment("salade", 80, "standard")}")
        assertEquals("lasagnes", LectureRepas.platPrincipal(extraction(j)))
        assertNull(LectureRepas.platPrincipal(extraction(json(""))))
    }

    @Test
    fun unJsonIllisibleDonneNull() {
        assertNull(LectureRepas.lire(phrase(), extraction("pas du json"), table))
        assertNull(LectureRepas.platPrincipal(extraction("{}")))
    }
}
