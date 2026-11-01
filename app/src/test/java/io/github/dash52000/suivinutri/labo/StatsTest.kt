package io.github.dash52000.suivinutri.labo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Valeurs de référence calculées avec scipy 1.x et statsmodels (voir README, section Labo). */
class StatsTest {

    @Test
    fun quantilesStudent() {
        assertEquals(12.706205, Stats.t975(1.0), 1e-5)
        assertEquals(2.228139, Stats.t975(10.0), 1e-5)
        assertEquals(2.042272, Stats.t975(30.0), 1e-5)
        assertEquals(2.333040, Stats.t975(7.5), 1e-3)
        assertEquals(2.172933, Stats.t975(12.3), 1e-3)
        assertEquals(2.039513, Stats.t975(31.0), 1e-3)
        assertEquals(2.014103, Stats.t975(45.0), 1e-3)
        assertEquals(1.983972, Stats.t975(100.0), 1e-3)
        assertEquals(1.962339, Stats.t975(1000.0), 1e-3)
    }

    @Test
    fun welch() {
        val g1 = listOf(0.6, 0.2, 0.9, 0.4, 0.3, 0.7, 0.1)
        val g0 = listOf(0.1, -0.2, 0.3, 0.0, -0.1, 0.2, 0.4, -0.3, 0.1)
        val r = Stats.welch(g1, g0)
        assertNotNull(r)
        assertEquals(0.401587, r!!.estimation, 1e-5)
        assertEquals(0.109800, r.bas, 1e-3)
        assertEquals(0.693374, r.haut, 1e-3)
    }

    @Test
    fun agrestiCaffo() {
        val a = Stats.differenceProportions(9, 20, 4, 18)!!
        assertEquals(0.227778, a.estimation, 1e-5)
        assertEquals(-0.077067, a.bas, 1e-4)
        assertEquals(0.486158, a.haut, 1e-4)
        val b = Stats.differenceProportions(3, 6, 1, 7)!!
        assertEquals(0.357143, b.estimation, 1e-5)
        assertEquals(-0.162470, b.bas, 1e-4)
        assertEquals(0.718026, b.haut, 1e-4)
    }

    private val x = listOf(0.2, 0.5, 0.4, 0.8, 0.6, 0.9, 0.3, 0.7, 1.0, 0.5, 0.6, 0.85)
    private val y = listOf(2650.0, 2500.0, 2580.0, 2380.0, 2460.0, 2350.0, 2620.0, 2420.0, 2300.0, 2510.0, 2440.0, 2390.0)

    @Test
    fun neweyWestPeriodesConsecutives() {
        val r = Stats.penteNeweyWest(x, y, (0 until 12).toList())!!
        assertEquals(-436.160298, r.estimation, 1e-4)
        assertEquals(-470.696903, r.bas, 1e-3)
        assertEquals(-401.623693, r.haut, 1e-3)
    }

    @Test
    fun neweyWestAvecUnTrou() {
        val positions = listOf(0, 1, 2, 3, 4, 5, 8, 9, 10, 11, 12, 13)
        val r = Stats.penteNeweyWest(x, y, positions)!!
        assertEquals(-436.160298, r.estimation, 1e-4)
        assertEquals(-473.170267, r.bas, 1e-3)
        assertEquals(-399.150330, r.haut, 1e-3)
    }

    @Test
    fun intervalleMultiplieParUnNegatifRestOrdonne() {
        val r = Intervalle(2.0, 1.0, 3.0).fois(-1.0)
        assertEquals(-2.0, r.estimation, 0.0)
        assertEquals(-3.0, r.bas, 0.0)
        assertEquals(-1.0, r.haut, 0.0)
    }

    @Test
    fun pValeursStudent() {
        assertEquals(0.0738711962, Stats.pStudent(2.1, 7.0), 1e-8)
        assertEquals(0.6514479648, Stats.pStudent(0.5, 3.0), 1e-8)
        assertEquals(0.0057629694, Stats.pStudent(-3.2, 15.5), 1e-8)
        assertEquals(0.5, Stats.pStudent(1.0, 1.0), 1e-8)
        assertEquals(0.0002659124, Stats.pStudent(4.0, 40.0), 1e-9)
    }

    @Test
    fun pValeursNormales() {
        assertEquals(0.7641771556, Stats.pNormal(0.3), 1e-6)
        assertEquals(0.0499957903, Stats.pNormal(1.96), 1e-7)
        assertEquals(0.0124193307, Stats.pNormal(-2.5), 1e-7)
        assertEquals(0.0009668483, Stats.pNormal(3.3), 1e-8)
    }

    @Test
    fun benjaminiHochberg() {
        val q = Stats.benjaminiHochberg(listOf(0.01, 0.04, 0.03, 0.20, 0.002, 0.5))
        val attendu = listOf(0.03, 0.06, 0.06, 0.24, 0.012, 0.5)
        attendu.indices.forEach { assertEquals(attendu[it], q[it], 1e-12) }
    }

    private val x1 = listOf(0, 0, 0, 1, 1, 0, 1, 0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 1)
    private val x2 = listOf(1, 0, 1, 0, 0, 0, 1, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 0, 0, 0, 0, 0, 1, 0)
    private val yy = listOf(
        -0.064194, -0.092441, 0.500125, 0.828678, 0.240213, 0.122355, 0.573007, -0.156635, 0.104873, -0.119955,
        0.800174, 0.831557, 0.197301, -0.039058, -0.038992, -0.061819, -0.456158, -0.07379, 0.041141, 0.369629,
        0.943567, 0.202942, -0.338393, 0.794071,
    )
    private val lignes = x1.indices.map { doubleArrayOf(1.0, x1[it].toDouble(), x2[it].toDouble()) }

    /** Référence : statsmodels OLS, cov_type HAC, maxlags 2, use_correction. */
    @Test
    fun regressionMultipleHac() {
        val r = Stats.olsHac(lignes, yy, (0 until 24).toList(), 2)!!
        assertEquals(0.04955046, r.coef[0], 1e-7)
        assertEquals(0.59049024, r.coef[1], 1e-7)
        assertEquals(-0.11628544, r.coef[2], 1e-7)
        assertEquals(0.05542753, r.se[0], 1e-7)
        assertEquals(0.07915353, r.se[1], 1e-7)
        assertEquals(0.07792462, r.se[2], 1e-7)
        assertEquals(21, r.ddl)
    }

    /** Même données avec un trou de 8 jours après la 12e observation : aucun croisement à travers le trou. */
    @Test
    fun regressionMultipleHacAvecTrou() {
        val positions = (0 until 12).toList() + (20 until 32).toList()
        val r = Stats.olsHac(lignes, yy, positions, 2)!!
        assertEquals(0.06134211, r.se[0], 1e-7)
        assertEquals(0.0998808, r.se[1], 1e-7)
        assertEquals(0.08890919, r.se[2], 1e-7)
    }

    @Test
    fun matriceSinguliere() {
        val colineaires = (0 until 10).map { doubleArrayOf(1.0, it.toDouble(), 2.0 * it) }
        assertEquals(null, Stats.olsHac(colineaires, (0 until 10).map { it.toDouble() }, (0 until 10).toList(), 1))
    }
}
