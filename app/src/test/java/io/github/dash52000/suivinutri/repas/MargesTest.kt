package io.github.dash52000.suivinutri.repas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fiche SPEC section 9.5 : explicite ±15 %, standard ±40 %, grosse / petite ±30 %, confiance basse × 1,5, spontanées × 1,5 décroissant. */
class MargesTest {

    @Test
    fun margesParPortion() {
        assertEquals(0.15, Marges.sigmaPortion(PortionSource.EXPLICITE), 0.0)
        assertEquals(0.40, Marges.sigmaPortion(PortionSource.STANDARD), 0.0)
        assertEquals(0.30, Marges.sigmaPortion(PortionSource.GROSSE), 0.0)
        assertEquals(0.30, Marges.sigmaPortion(PortionSource.PETITE), 0.0)
    }

    @Test
    fun confianceBasseMultiplieParUnVirguleCinq() {
        assertEquals(0.6, Marges.sigma(PortionSource.STANDARD, Confiance.BASSE, Origine.TIREE), 1e-12)
        assertEquals(0.225, Marges.sigma(PortionSource.EXPLICITE, Confiance.BASSE, Origine.CHARGEE), 1e-12)
        assertEquals(0.4, Marges.sigma(PortionSource.STANDARD, Confiance.MOYENNE, Origine.TIREE), 1e-12)
        assertEquals(0.15, Marges.sigma(PortionSource.EXPLICITE, Confiance.HAUTE, Origine.TIREE), 1e-12)
    }

    @Test
    fun spontaneesDecroissentLineairementJusqua25PhrasesComparables() {
        assertEquals(1.5, Marges.facteurOrigine(Origine.SPONTANEE, 0), 1e-12)
        assertEquals(1.3, Marges.facteurOrigine(Origine.SPONTANEE, 10), 1e-12)
        assertEquals(1.26, Marges.facteurOrigine(Origine.SPONTANEE, 12), 1e-12)
        assertEquals(1.0, Marges.facteurOrigine(Origine.SPONTANEE, 25), 1e-12)
        assertEquals(1.0, Marges.facteurOrigine(Origine.SPONTANEE, 1_000), 1e-12)
        assertEquals(1.5, Marges.facteurOrigine(Origine.SPONTANEE, -3), 1e-12)
        var precedent = Double.MAX_VALUE
        for (n in 0..30) {
            val f = Marges.facteurOrigine(Origine.SPONTANEE, n)
            assertTrue(f <= precedent)
            precedent = f
        }
    }

    @Test
    fun ecartSpontaneeTireeDetecteGardeLeFacteurDeDepart() {
        assertEquals(1.5, Marges.facteurOrigine(Origine.SPONTANEE, 25, ecartDetecte = true), 1e-12)
        assertEquals(1.5, Marges.facteurOrigine(Origine.SPONTANEE, 500, ecartDetecte = true), 1e-12)
    }

    @Test
    fun tireesEtChargeesNeRecoiventPasLeFacteurDesSpontanees() {
        for (n in listOf(0, 5, 25)) for (ecart in listOf(false, true)) {
            assertEquals(1.0, Marges.facteurOrigine(Origine.TIREE, n, ecart), 0.0)
            assertEquals(1.0, Marges.facteurOrigine(Origine.CHARGEE, n, ecart), 0.0)
        }
    }

    @Test
    fun facteursSeMultiplient() {
        // 0,40 × 1,5 (confiance basse) × 1,5 (spontanée, 0 comparable) = 0,90 : le maximum possible.
        assertEquals(0.9, Marges.sigma(PortionSource.STANDARD, Confiance.BASSE, Origine.SPONTANEE, 0), 1e-12)
        assertEquals(0.4 * 1.3, Marges.sigma(PortionSource.STANDARD, Confiance.HAUTE, Origine.SPONTANEE, 10), 1e-12)
        assertEquals(0.3 * 1.5, Marges.sigma(PortionSource.GROSSE, Confiance.HAUTE, Origine.SPONTANEE, 100, ecartDetecte = true), 1e-12)
    }

    @Test
    fun margeDUnRepasParMethodeDelta() {
        // Un seul aliment : sa marge.
        assertEquals(0.4, Marges.sigmaRepas(listOf(250.0 to 0.4)), 1e-12)
        // Deux aliments égaux et indépendants : σ / √2.
        assertEquals(0.4 / Math.sqrt(2.0), Marges.sigmaRepas(listOf(100.0 to 0.4, 100.0 to 0.4)), 1e-12)
        // Un gros aliment précis et un petit aliment flou : √(45² + 40²) / 400.
        assertEquals(Math.sqrt(45.0 * 45.0 + 40.0 * 40.0) / 400.0, Marges.sigmaRepas(listOf(300.0 to 0.15, 100.0 to 0.4)), 1e-12)
        assertEquals(0.15052, Marges.sigmaRepas(listOf(300.0 to 0.15, 100.0 to 0.4)), 1e-4)
        // Jamais plus large que le plus large des aliments, jamais plus étroit que la part de variance du plus gros.
        val repas = listOf(500.0 to 0.15, 200.0 to 0.3, 80.0 to 0.9)
        assertTrue(Marges.sigmaRepas(repas) < 0.9)
    }

    @Test
    fun margeDUnRepasRefuseLesEntreesInvalides() {
        assertThrows(IllegalArgumentException::class.java) { Marges.sigmaRepas(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { Marges.sigmaRepas(listOf(0.0 to 0.4)) }
        assertThrows(IllegalArgumentException::class.java) { Marges.sigmaRepas(listOf(100.0 to -0.1)) }
        assertThrows(IllegalArgumentException::class.java) { Marges.sigmaRepas(listOf(Double.NaN to 0.4)) }
    }
}
