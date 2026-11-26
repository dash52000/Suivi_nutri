package io.github.dash52000.suivinutri.repas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fiche SPEC sections 6 et 7 : table CIQUAL embarquée, rapprochement par nom, kcal des aliments et des boissons.
 * Lit le vrai fichier `assets/ciqual.tsv` (Gradle lance les tests depuis le dossier `app/`).
 */
class CiqualTest {

    private val table: TableCiqual by lazy {
        val f = listOf(File("src/main/assets/ciqual.tsv"), File("app/src/main/assets/ciqual.tsv")).first { it.exists() }
        TableCiqual.depuisTexte(f.readText(Charsets.UTF_8))
    }

    private fun nom(requete: String, portee: TableCiqual.Portee = TableCiqual.Portee.ALIMENT) =
        table.meilleur(requete, portee)?.aliment?.nom

    // --- table ------------------------------------------------------------------------------------------------

    @Test
    fun tableEmbarqueeCoherente() {
        assertEquals("2025-11-03", table.version)
        assertEquals(3341, table.aliments.size)
        assertTrue(table.aliments.all { it.kcal in 0.0..950.0 })
        assertEquals(table.aliments.size, table.aliments.map { it.code }.toSet().size)
        assertNotNull(table.parCode("9811"))
    }

    @Test
    fun lectureRefuseUneTableMalformee() {
        val ok = "#ciqual\t2025-11-03\n#code\tnom\tgroupe\tkcal\tprot_g\tgluc_g\tlip_g\tsel_g\talcool_g\n1\tPain\t070101\t270.0\t8.0\t50.0\t1.0\t1.0\t0.0\n"
        assertEquals(1, TableCiqual.depuisTexte(ok).aliments.size)
        var refuse = false
        try { TableCiqual.depuisTexte("1\tPain\t070101\t270.0\n") } catch (e: IllegalArgumentException) { refuse = true }
        assertTrue(refuse)
        refuse = false
        try { TableCiqual.depuisTexte(ok + "1\tDoublon\t070101\t100.0\t0.0\t0.0\t0.0\t0.0\t0.0\n") } catch (e: IllegalArgumentException) { refuse = true }
        assertTrue(refuse)
    }

    // --- rapprochement par nom -------------------------------------------------------------------------------

    @Test
    fun alimentsCourantsTrouvesAvecLeBonMotDeTete() {
        val attendus = mapOf(
            "pâtes" to "Pâtes", "riz" to "Riz", "œufs" to "Oeuf", "huile d'olive" to "Huile d'olive", "saucisson" to "Saucisson sec",
            "jambon" to "Jambon cuit", "raclette" to "Raclette", "blanquette de veau" to "Blanquette", "steak haché" to "Boeuf, steak haché",
            "pommes" to "Pomme,", "patates" to "Pomme de terre", "yaourt" to "Yaourt",
        )
        for ((requete, debut) in attendus) {
            val trouve = nom(requete)
            assertNotNull("$requete non reconnu", trouve)
            assertTrue("« $requete » → « $trouve » (attendu : $debut…)", trouve!!.startsWith(debut))
        }
    }

    @Test
    fun pateEtPatesSeDistinguent() {
        assertTrue(nom("pâtes")!!.startsWith("Pâtes"))
        assertTrue(nom("pâté")!!.startsWith("Pâté"))
    }

    @Test
    fun pommeNEstPasPommeDeTerre() {
        assertTrue(nom("pomme")!!.startsWith("Pomme,"))
        assertTrue(nom("pomme de terre")!!.startsWith("Pomme de terre"))
    }

    @Test
    fun pouletNEstPasUnFoie() {
        assertTrue(nom("poulet")!!.startsWith("Poulet"))
    }

    @Test
    fun inconnuResteNonReconnu() {
        assertNull(table.meilleur("xyzzy qwerty"))
        assertNull(table.meilleur(""))
    }

    @Test
    fun alimentExclutLesBoissonsEtBoissonLesAlimentsSolides() {
        assertTrue(table.chercher("cola", 5, TableCiqual.Portee.ALIMENT).none { it.aliment.groupe.startsWith("06") })
        assertTrue(table.chercher("café", 5, TableCiqual.Portee.BOISSON).all { it.aliment.groupe.startsWith("06") })
    }

    // --- kcal d'un aliment -----------------------------------------------------------------------------------

    @Test
    fun kcalEgalKcalPour100gFoisGrammes() {
        val a = Estimation.aliment(table, "pâtes", 200.0, PortionSource.EXPLICITE, Confiance.HAUTE, Origine.TIREE, "midi", false)
        val ref = table.parCode(a.codeCiqual!!)!!
        assertEquals(ref.kcal * 2.0, a.kcal!!, 1e-9)
        assertEquals(Marges.SIGMA_EXPLICITE, a.sigma, 1e-12)
    }

    @Test
    fun alimentInconnuResteNaEtEstSignale() {
        val a = Estimation.aliment(table, "xyzzy qwerty", 100.0, PortionSource.STANDARD, Confiance.BASSE, Origine.TIREE, "soir", false)
        assertNull(a.kcal)
        assertNull(a.codeCiqual)
        assertEquals(Marges.SIGMA_STANDARD * Marges.FACTEUR_CONFIANCE_BASSE, a.sigma, 1e-12)
        assertEquals(listOf("xyzzy qwerty"), Estimation.nonReconnus(listOf(a), emptyList()))
    }

    @Test
    fun gramsInvalidesRefuses() {
        for (g in listOf(0.0, -5.0, Double.NaN)) {
            var refuse = false
            try { Estimation.aliment(table, "riz", g, PortionSource.STANDARD, Confiance.HAUTE, Origine.TIREE, null, false) } catch (e: IllegalArgumentException) { refuse = true }
            assertTrue("grammes $g", refuse)
        }
    }

    // --- boissons --------------------------------------------------------------------------------------------

    @Test
    fun volumeLuTelQuel() {
        assertEquals(330.0, Boissons.volume("33 cl", Boissons.Type.BIERE).ml, 1e-9)
        assertEquals(500.0, Boissons.volume("0,5 l", Boissons.Type.AUTRE).ml, 1e-9)
        assertEquals(660.0, Boissons.volume("2 bouteilles de 33 cl", Boissons.Type.BIERE).ml, 1e-9)
        assertTrue(Boissons.volume("33 cl", Boissons.Type.BIERE).explicite)
    }

    @Test
    fun volumeUsuelQuandIlNEstPasDit() {
        val v = Boissons.volume("un verre", Boissons.Type.VIN)
        assertEquals(120.0, v.ml, 1e-9)
        assertTrue(!v.explicite)
        assertEquals(330.0, Boissons.volume(null, Boissons.Type.BIERE).ml, 1e-9)
        assertEquals(500.0, Boissons.volume("pinte", Boissons.Type.BIERE).ml, 1e-9)
    }

    @Test
    fun typesDeBoissonsReconnus() {
        assertEquals(Boissons.Type.BIERE, Boissons.type("Orval"))
        assertEquals(Boissons.Type.BIERE, Boissons.type("bière blonde"))
        assertEquals(Boissons.Type.VIN, Boissons.type("vin rouge"))
        assertEquals(Boissons.Type.FORT, Boissons.type("whisky"))
        assertEquals(Boissons.Type.AUTRE, Boissons.type("coca"))
    }

    @Test
    fun bierePlusForteDonneDavantageDeKcal() {
        val legere = Boissons.estimer(table, "bière", "33 cl", 4.5)!!
        val forte = Boissons.estimer(table, "bière", "33 cl", 9.0)!!
        assertTrue("${legere.kcal} < ${forte.kcal}", legere.kcal < forte.kcal)
        // 33 cl à 9 % ≈ 23,5 g d'alcool × 7 kcal ≈ 165 kcal d'alcool, plus les glucides : entre 200 et 330 kcal.
        assertTrue("forte = ${forte.kcal}", forte.kcal in 200.0..330.0)
        // 33 cl à 4,5 % : environ 120-170 kcal.
        assertTrue("légère = ${legere.kcal}", legere.kcal in 100.0..180.0)
    }

    @Test
    fun trappisteAvecDegreCorrigeSurLAlcool() {
        val orval = Boissons.estimer(table, "Orval", "33 cl", 6.2)!!
        assertTrue("orval = ${orval.kcal}", orval.kcal in 150.0..260.0)
    }

    @Test
    fun colaEtVinAvecVolume() {
        val cola = Boissons.estimer(table, "coca", "33 cl", null)!!
        assertTrue("cola = ${cola.kcal}", cola.kcal in 90.0..160.0)
        val vin = Boissons.estimer(table, "vin rouge", "un verre", 12.5)!!
        assertTrue("vin = ${vin.kcal}", vin.kcal in 60.0..130.0)
    }

    @Test
    fun boissonSansFormatPrendLaMargeStandard() {
        val b = Estimation.boisson(table, "bière", null, 6.0, Confiance.HAUTE, Origine.TIREE)
        assertEquals(Marges.SIGMA_STANDARD, b.sigma, 1e-12)
        assertEquals(330.0, b.millilitres!!, 1e-9)
        val c = Estimation.boisson(table, "bière", "33 cl", 6.0, Confiance.HAUTE, Origine.TIREE)
        assertEquals(Marges.SIGMA_EXPLICITE, c.sigma, 1e-12)
    }

    @Test
    fun boissonInconnueResteNa() {
        val b = Estimation.boisson(table, "xyzzy qwerty", "25 cl", null, Confiance.HAUTE, Origine.TIREE)
        assertNull(b.kcal)
        assertEquals(listOf("xyzzy qwerty"), Estimation.nonReconnus(emptyList(), listOf(b)))
    }

    // --- repas -----------------------------------------------------------------------------------------------

    @Test
    fun repasSommeLesLignesReconnuesEtCombineLesMarges() {
        val a = Estimation.aliment(table, "pâtes", 250.0, PortionSource.STANDARD, Confiance.HAUTE, Origine.TIREE, "midi", false)
        val b = Estimation.boisson(table, "bière", "33 cl", 6.0, Confiance.HAUTE, Origine.TIREE)
        val inconnu = Estimation.aliment(table, "xyzzy qwerty", 100.0, PortionSource.STANDARD, Confiance.HAUTE, Origine.TIREE, "midi", false)
        val (kcal, sigma) = Estimation.repas(listOf(a, inconnu), listOf(b))!!
        assertEquals(a.kcal!! + b.kcal!!, kcal, 1e-9)
        assertTrue(sigma > 0.0 && sigma < Marges.SIGMA_STANDARD)
    }

    @Test
    fun repasSansAucuneLigneReconnueEstNonDecrit() {
        val inconnu = Estimation.aliment(table, "xyzzy qwerty", 100.0, PortionSource.STANDARD, Confiance.HAUTE, Origine.TIREE, "midi", false)
        assertNull(Estimation.repas(listOf(inconnu), emptyList()))
        assertNull(Estimation.repas(emptyList(), emptyList()))
    }
}
