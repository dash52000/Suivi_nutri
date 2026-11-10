package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.Boisson
import io.github.dash52000.suivinutri.data.EntreeJour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TableAnalyseTest {

    private val d0 = LocalDate.of(2026, 10, 29)

    @Test
    fun uneLigneParJourAvecNA() {
        val jours = listOf(
            JourLabo(d0, poids = 88.0, heurePesee = 7, kcal = 2650.0, longueSortie = false),
            JourLabo(
                d0.plusDays(1),
                entree = EntreeJour(
                    d0.plusDays(1),
                    mapOf("tupp" to "oui", "alcool" to "oui"),
                    listOf(Boisson("tripel", 33.0, 8.5)),
                ),
            ),
            JourLabo(d0.plusDays(2), poids = 87.7, heurePesee = 8),
        )
        val csv = TableAnalyse.csv(jours, d0.plusDays(2), listOf("tupp", "dessert", "alcool"))
        val lignes = csv.trim().lines()
        val entete = lignes[0].split(",")
        assertEquals(4, lignes.size)
        assertEquals("date", entete[0])
        assertTrue(entete.containsAll(listOf("tendance_kg", "tendance_se_kg", "residu_kg", "longue_sortie", "unites_alcool")))
        assertTrue(entete.containsAll(listOf("poids_source", "graisse_pct", "masse_grasse_kg", "tendance_graisse_kg", "eau_kg")))

        fun cellule(ligne: Int, colonne: String) = lignes[ligne].split(",")[entete.indexOf(colonne)]
        assertEquals("88.00", cellule(1, "poids_kg"))
        assertEquals("FALSE", cellule(1, "poids_ecartee"))
        assertEquals("FALSE", cellule(1, "longue_sortie"))
        assertEquals("NA", cellule(2, "poids_kg"))
        assertEquals("NA", cellule(2, "poids_ecartee"))
        assertEquals("oui", cellule(2, "tupp"))
        assertEquals("NA", cellule(2, "dessert"))
        assertEquals("1", cellule(2, "nb_boissons"))
        assertEquals("2.24", cellule(2, "unites_alcool"))
        assertEquals("NA", cellule(1, "unites_alcool"))
        // La tendance existe aussi les jours sans pesée.
        assertTrue(cellule(2, "tendance_kg") != "NA")
        // v1.7 : dépense retenue, eau et valeurs corrigées.
        assertTrue(entete.containsAll(listOf("kcal_retenue", "tendance_eau_kg", "ecart_eau_kg", "poids_corrige_kg", "masse_grasse_corrigee_kg")))
        assertEquals("2650", cellule(1, "kcal_retenue"))
        assertEquals("88.000", cellule(1, "poids_corrige_kg"))
        assertEquals("NA", cellule(1, "ecart_eau_kg"))
    }

    @Test
    fun jourSansMontreEtEauCorrigee() {
        val jours = (0L..3L).map { k ->
            JourLabo(
                d0.plusDays(k), poids = 88.0, heurePesee = 7, graissePct = 25.0, eauKg = 46.0,
                kcal = if (k == 1L) 1565.0 else 2600.0, sommeilMin = if (k == 1L) null else 420L,
            )
        }
        val csv = TableAnalyse.csv(jours, d0.plusDays(3), emptyList())
        val lignes = csv.trim().lines()
        val entete = lignes[0].split(",")
        fun cellule(ligne: Int, colonne: String) = lignes[ligne].split(",")[entete.indexOf(colonne)]
        assertEquals("1565", cellule(2, "kcal_total"))
        assertEquals("NA", cellule(2, "kcal_retenue"))
        assertEquals("2600", cellule(1, "kcal_retenue"))
        // Eau constante : écart nul, poids et graisse corrigés égaux aux mesures.
        assertEquals("46.000", cellule(1, "tendance_eau_kg"))
        assertEquals("0.000", cellule(1, "ecart_eau_kg"))
        assertEquals("88.000", cellule(1, "poids_corrige_kg"))
        assertEquals("22.000", cellule(1, "masse_grasse_corrigee_kg"))
    }
}
