package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.EntreeJour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CalendrierTest {

    private val d0 = LocalDate.of(2026, 10, 26)
    private val dates = (0L..6L).map { d0.plusDays(it) }

    @Test
    fun casesEtDecompte() {
        val entrees = mapOf(
            d0 to EntreeJour(d0, mapOf("tupp" to "oui", "assiette" to "en_partie")),
            d0.plusDays(1) to EntreeJour(d0.plusDays(1), mapOf("tupp" to "non")),
            d0.plusDays(3) to EntreeJour(d0.plusDays(3), mapOf("tupp" to "oui")),
        )
        val lignes = Labo.calendrier(entrees, dates, Labo.reflexes)

        // Seuls les réflexes avec au moins une réponse apparaissent.
        assertEquals(listOf("Tupp remplis", "Assiette équilibrée"), lignes.map { it.libelle })

        val tupp = lignes.first { it.libelle == "Tupp remplis" }
        assertEquals(1.0, tupp.scores[0]!!, 0.0)
        assertEquals(0.0, tupp.scores[1]!!, 0.0)
        assertNull(tupp.scores[2])
        assertEquals(2.0, tupp.tenus, 0.0)
        assertEquals(3, tupp.repondus)

        val assiette = lignes.first { it.libelle == "Assiette équilibrée" }
        assertEquals(0.5, assiette.tenus, 0.0)
        assertEquals(1, assiette.repondus)
    }
}
