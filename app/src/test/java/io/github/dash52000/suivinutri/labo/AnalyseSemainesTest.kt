package io.github.dash52000.suivinutri.labo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AnalyseSemainesTest {

    private val lundi = LocalDate.of(2026, 8, 3) // un lundi

    /**
     * Perte linéaire de 0,1 kg/j et dépense constante de 2 500 kcal/j : la différence centrée
     * donne exactement −0,7 kg par semaine, donc 2 500 − 770 = 1 730 kcal/j, dès la 2e semaine
     * (pas de temps de chauffe, contrairement à la tendance lissée).
     */
    @Test
    fun differenceCentreeSansRetard() {
        val jours = (0L until 70L).map { k -> JourLabo(lundi.plusDays(k), poids = 85.0 - 0.1 * k, kcal = 2500.0) }
        val semaines = Labo.semainesAnalyse(jours, lundi.plusDays(70))
        assertTrue(semaines.isNotEmpty())
        assertEquals(lundi.plusWeeks(1), semaines.first().lundi)
        // La semaine suivante doit être complète : la dernière analysable est l'avant-dernière.
        assertEquals(lundi.plusWeeks(8), semaines.last().lundi)
        semaines.forEach { assertEquals(1730.0, it.apports, 1e-6) }
    }

    /** Un saut d'eau ponctuel (+1,5 kg un seul jour) déplace peu la moyenne d'une semaine. */
    @Test
    fun robusteAUnPicIsole() {
        val jours = (0L until 35L).map { k ->
            val pic = if (k == 17L) 1.5 else 0.0
            JourLabo(lundi.plusDays(k), poids = 80.0 + pic, kcal = 2400.0)
        }
        val semaines = Labo.semainesAnalyse(jours, lundi.plusDays(35))
        // Pic dans la semaine 2 : il influence les semaines 1 et 3 (voisines), de 1,5 ÷ 7 ÷ 2 kg.
        val attendu = 2400.0 + (1.5 / 7 / 2) * Labo.KCAL_PAR_KG / 7
        val s1 = semaines.first { it.lundi == lundi.plusWeeks(1) }
        assertEquals(attendu, s1.apports, 1e-6)
        val s2 = semaines.first { it.lundi == lundi.plusWeeks(2) }
        assertEquals(2400.0, s2.apports, 1e-6)
    }
}
