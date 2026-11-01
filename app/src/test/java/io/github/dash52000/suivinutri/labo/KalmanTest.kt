package io.github.dash52000.suivinutri.labo

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Valeurs de référence : implémentation numpy du filtre et du lisseur, elle-même vérifiée contre la loi
 * a posteriori exacte de tous les états (forme information) à 10⁻¹² près, y compris les covariances croisées.
 * Série de 20 jours avec trous, une pesée d'après-midi (jour 13) et une aberrante (jour 11, +5,7 kg).
 */
class KalmanTest {

    private val valeurs = mapOf(
        0 to 88.0, 1 to 87.6, 2 to 88.3, 4 to 87.9, 5 to 87.4, 9 to 87.8, 10 to 87.2,
        11 to 93.5, 13 to 88.1, 15 to 87.0, 16 to 87.3, 19 to 86.9,
    )

    private val lissage = Kalman.lisser(
        LocalDate.of(2026, 10, 1),
        20,
        valeurs.map { (j, p) -> Kalman.Pesee(j, p, if (j == 13) Kalman.VAR_APRES_MIDI else Kalman.VAR_MATIN) },
    )

    @Test
    fun peseeAberranteEcartee() {
        assertEquals(setOf(11), lissage.ecartees)
    }

    @Test
    fun niveauxEtPentesLisses() {
        assertEquals(87.882357, lissage.niveau(0), 2e-6)
        assertEquals(-0.036339, lissage.pente(0), 2e-6)
        assertEquals(87.809245, lissage.niveau(2), 2e-6)
        assertEquals(87.527642, lissage.niveau(9), 2e-6)
        assertEquals(87.362419, lissage.niveau(13), 2e-6)
        assertEquals(87.113445, lissage.niveau(19), 2e-6)
    }

    @Test
    fun variances() {
        assertEquals(0.080179, lissage.varNiveau(0), 2e-6)
        assertEquals(0.061161, lissage.varNiveau(2), 2e-6)
        assertEquals(0.040872, lissage.varNiveau(9), 2e-6)
        assertEquals(0.117608, lissage.varNiveau(19), 2e-6)
    }

    @Test
    fun covarianceCroiseeEtDifference() {
        assertEquals(0.030302, lissage.covNiveaux(2, 7), 2e-6)
        assertEquals(0.041430, lissage.varDifference(2, 7), 2e-6)
    }

    @Test
    fun peseeApresMidiMoinsPonderee() {
        assertEquals(Kalman.VAR_MATIN, Kalman.variancePesee(7), 0.0)
        assertEquals(Kalman.VAR_APRES_MIDI, Kalman.variancePesee(15), 0.0)
        assertEquals(Kalman.VAR_MATIN, Kalman.variancePesee(null), 0.0)
    }
}
