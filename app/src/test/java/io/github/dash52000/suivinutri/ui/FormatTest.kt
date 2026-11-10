package io.github.dash52000.suivinutri.ui

import io.github.dash52000.suivinutri.labo.PointPoids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class FormatTest {

    private val d = LocalDate.of(2026, 11, 10)

    private fun point(brut: Double?, corrige: Double?, ecartee: Boolean = false) =
        PointPoids(d, brut, tendance = 80.0, seTendance = 0.1, ecartee = ecartee, apresMidi = false, corrige = corrige)

    @Test
    fun decompositionArrondieQuiTombeJuste() {
        // Écart 0,84 → 0,8 ; eau 0,56 → 0,6 ; reste calculé sur les arrondis : 0,2.
        assertEquals("écart à la tendance +0,8 kg : eau +0,6 kg, reste +0,2 kg", texteEcartPesee(point(80.84, 80.28)))
        assertEquals("écart à la tendance −0,3 kg : eau −0,5 kg, reste +0,2 kg", texteEcartPesee(point(79.7, 80.2)))
    }

    @Test
    fun sansEauOuSansPesee() {
        assertEquals("écart à la tendance −0,3 kg (eau non mesurée)", texteEcartPesee(point(79.7, null)))
        assertNull(texteEcartPesee(point(null, null)))
        assertNull(texteEcartPesee(point(83.0, 82.0, ecartee = true)))
    }

    @Test
    fun pasDeMoinsZero() {
        assertEquals("écart à la tendance +0,0 kg : eau +0,0 kg, reste +0,0 kg", texteEcartPesee(point(80.0, 80.0)))
        assertEquals("écart à la tendance +0,2 kg : eau +0,2 kg, reste +0,0 kg", texteEcartPesee(point(80.2, 80.0)))
    }
}
