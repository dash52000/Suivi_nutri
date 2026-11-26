package io.github.dash52000.suivinutri.repas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Fiche SPEC section 5 : habitude du matin apprise sur 8 semaines, 3 phrases minimum, nouvelle habitude sur 3 écarts. */
class HabitudeMatinTest {

    private val aujourdhui = LocalDate.of(2026, 12, 20)

    private fun p(joursAvant: Long, kcal: Double, sigma: Double = 0.2, pi: Double = 0.15, origine: Origine = Origine.TIREE) =
        PhraseMatin(aujourdhui.minusDays(joursAvant), kcal, sigma, pi, origine)

    @Test
    fun moinsDeTroisPhrasesTireesPasDHabitude() {
        assertNull(HabitudeMatin.apprendre(emptyList(), aujourdhui))
        assertNull(HabitudeMatin.apprendre(listOf(p(1, 300.0), p(2, 310.0)), aujourdhui))
        assertFalse(HabitudeMatin.proposable(null))
        val h = HabitudeMatin.apprendre(listOf(p(1, 300.0), p(2, 310.0), p(3, 290.0)), aujourdhui)
        assertNotNull(h)
        assertTrue(HabitudeMatin.proposable(h))
        assertEquals(3, h!!.nPhrases)
    }

    @Test
    fun seulesLesPhrasesTireesApprennent() {
        val phrases = listOf(
            p(1, 300.0), p(2, 300.0),
            p(3, 900.0, origine = Origine.SPONTANEE), p(4, 900.0, origine = Origine.CHARGEE),
        )
        assertNull(HabitudeMatin.apprendre(phrases, aujourdhui)) // 2 tirées seulement
        val h = HabitudeMatin.apprendre(phrases + p(5, 300.0), aujourdhui)!!
        assertEquals(3, h.nPhrases)
        assertEquals(300.0, h.kcal, 1e-9)
    }

    @Test
    fun fenetreDeHuitSemainesBornesIncluses() {
        assertEquals(56, HabitudeMatin.FENETRE_JOURS)
        val dedans = listOf(p(0, 300.0), p(55, 300.0), p(30, 300.0))
        val h = HabitudeMatin.apprendre(dedans, aujourdhui)!!
        assertEquals(3, h.nPhrases)
        assertEquals(aujourdhui.minusDays(55), h.fenetreDebut)
        assertEquals(aujourdhui, h.fenetreFin)
        // Le 56e jour avant aujourd'hui est hors fenêtre, un jour futur aussi.
        assertNull(HabitudeMatin.apprendre(listOf(p(0, 300.0), p(56, 300.0), p(30, 300.0)), aujourdhui))
        assertNull(HabitudeMatin.apprendre(listOf(p(0, 300.0), p(-1, 300.0), p(30, 300.0)), aujourdhui))
    }

    @Test
    fun moyennePondereeParUnSurPi() {
        // 400 kcal tiré avec π = 0,15 compte 3 fois plus que 200 kcal tiré avec π = 0,45.
        val phrases = listOf(p(1, 400.0, pi = 0.15), p(2, 200.0, pi = 0.45), p(3, 200.0, pi = 0.45))
        val h = HabitudeMatin.apprendre(phrases, aujourdhui)!!
        val w = listOf(1 / 0.15, 1 / 0.45, 1 / 0.45)
        val attendu = (400.0 * w[0] + 200.0 * w[1] + 200.0 * w[2]) / w.sum()
        assertEquals(attendu, h.kcal, 1e-9)
        assertTrue(h.kcal > (400.0 + 200.0 + 200.0) / 3)
    }

    @Test
    fun phrasesIdentiquesLaMargeNeVientQueDesPhrases() {
        val n = 5
        val phrases = (1..n).map { p(it.toLong(), 300.0, sigma = 0.2) }
        val h = HabitudeMatin.apprendre(phrases, aujourdhui)!!
        assertEquals(300.0, h.kcal, 1e-9)
        assertEquals(sqrt(0.2 * 0.2 / n), h.sigma, 1e-9) // dispersion nulle : reste σ²/n
        // Plus de phrases = habitude mieux connue.
        val plus = HabitudeMatin.apprendre((1..20).map { p(it.toLong(), 300.0, sigma = 0.2) }, aujourdhui)!!
        assertTrue(plus.sigma < h.sigma)
    }

    @Test
    fun dispersionEntreLesMatinsElargitLaMarge() {
        val calme = HabitudeMatin.apprendre(listOf(p(1, 300.0), p(2, 300.0), p(3, 300.0), p(4, 300.0)), aujourdhui)!!
        val variable = HabitudeMatin.apprendre(listOf(p(1, 200.0), p(2, 450.0), p(3, 280.0), p(4, 380.0)), aujourdhui)!!
        assertTrue(variable.sigma > calme.sigma)
    }

    @Test
    fun ordreDEntreeSansEffet() {
        val phrases = listOf(p(5, 300.0), p(1, 320.0), p(9, 280.0), p(3, 310.0))
        assertEquals(HabitudeMatin.apprendre(phrases, aujourdhui), HabitudeMatin.apprendre(phrases.reversed(), aujourdhui))
    }

    // ---------- Nouvelle habitude ----------

    private val ancienne = listOf(p(30, 300.0), p(28, 310.0), p(26, 295.0), p(24, 305.0))

    @Test
    fun troisEcartsAuDelaDeLaMargeDansLeMemeSensRepartentDeCesPhrases() {
        // Habitude à 300 kcal (marge 20 %), puis 450, 460, 440 : > 300 × e^0,2 = 366 chaque fois.
        val phrases = ancienne + listOf(p(10, 450.0), p(8, 460.0), p(6, 440.0))
        val h = HabitudeMatin.apprendre(phrases, aujourdhui)!!
        assertEquals(aujourdhui.minusDays(10), h.changementLe)
        assertEquals(3, h.nPhrases)
        assertEquals(450.0, h.kcal, 1.0)
    }

    @Test
    fun ecartsDeSensOpposeSontDuBruitPasUnChangement() {
        val phrases = ancienne + listOf(p(10, 450.0), p(8, 180.0), p(6, 460.0))
        val h = HabitudeMatin.apprendre(phrases, aujourdhui)!!
        assertNull(h.changementLe)
        assertEquals(7, h.nPhrases)
    }

    @Test
    fun ecartsDansLaMargeNeChangentRien() {
        val phrases = ancienne + listOf(p(10, 340.0), p(8, 350.0), p(6, 345.0)) // +13 % : sous la marge de 20 %
        val h = HabitudeMatin.apprendre(phrases, aujourdhui)!!
        assertNull(h.changementLe)
        assertEquals(7, h.nPhrases)
    }

    @Test
    fun deuxEcartsNeSuffisentPas() {
        val phrases = ancienne + listOf(p(8, 450.0), p(6, 460.0))
        val h = HabitudeMatin.apprendre(phrases, aujourdhui)!!
        assertNull(h.changementLe)
        assertEquals(6, h.nPhrases)
    }

    @Test
    fun unEcartIsoleNeSuffitPasMemeSuiviDUnRetourALHabitude() {
        val phrases = ancienne + listOf(p(10, 450.0), p(8, 460.0), p(6, 305.0), p(4, 300.0))
        assertNull(HabitudeMatin.apprendre(phrases, aujourdhui)!!.changementLe)
    }

    @Test
    fun uneNouvelleHabitudeEstSuivieDUneAutre() {
        val phrases = ancienne + listOf(p(20, 450.0), p(18, 460.0), p(16, 440.0)) + listOf(p(8, 220.0), p(6, 215.0), p(4, 225.0))
        val h = HabitudeMatin.apprendre(phrases, aujourdhui)!!
        assertEquals(aujourdhui.minusDays(8), h.changementLe)
        assertEquals(3, h.nPhrases)
        assertEquals(220.0, h.kcal, 5.0)
    }

    @Test
    fun margeAssezLargeEmpecheLaDetection() {
        // Phrases très incertaines (σ = 0,6) : un écart de +50 % reste dans la marge.
        val phrases = ancienne.map { it.copy(sigma = 0.6) } + listOf(p(10, 450.0, 0.6), p(8, 460.0, 0.6), p(6, 440.0, 0.6))
        assertNull(HabitudeMatin.apprendre(phrases, aujourdhui)!!.changementLe)
        assertTrue(ln(450.0 / 300.0) < 0.6 && exp(0.6) > 1.8)
    }
}
