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
}
