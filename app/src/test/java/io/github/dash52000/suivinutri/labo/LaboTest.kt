package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.Boisson
import io.github.dash52000.suivinutri.data.EntreeJour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/** Valeurs attendues calculées avec l'implémentation de référence numpy (voir KalmanTest). */
class LaboTest {

    private val debut = LocalDate.of(2026, 9, 1)

    /** Rab un soir sur deux, +0,5 kg le lendemain d'un rab : écart à la tendance de +0,5 kg (référence 0,499115). */
    @Test
    fun effetLendemainConnu() {
        val jours = (0L until 40L).map { k ->
            JourLabo(
                date = debut.plusDays(k),
                poids = 80.0 + if (k % 2 == 1L) 0.5 else 0.0,
                entree = EntreeJour(debut.plusDays(k), mapOf("resservi" to if (k % 2 == 0L) "oui" else "non")),
            )
        }
        val r = Labo.analyser(jours, debut.plusDays(39))
        val rab = r.lendemain.first { it.libelle == "Soir avec rab" }
        assertNotNull(rab.j1)
        assertEquals(0.499115, rab.j1!!.estimation, 1e-4)
        val dessert = r.lendemain.first { it.libelle == "Dessert pris" }
        assertNull(dessert.j1)
        assertEquals(0, dessert.n1)
    }

    /**
     * Rab et dessert arrivent souvent ensemble (corrélation 0,58), mais seul le dessert fait monter le poids
     * (+0,6 kg). La régression jointe attribue l'effet au dessert ; une comparaison rab / sans rab, elle,
     * donnerait +0,26 kg au rab. Référence numpy : rab −0,103316, dessert 0,658245.
     */
    @Test
    fun regressionJointeSepareLesExpositions() {
        val n = 60
        val dessert = (0 until n).map { it % 3 == 0 }
        val rab = (0 until n).map { t -> if (t % 5 == 0) !dessert[t] else dessert[t] }
        val jours = (0 until n).map { t ->
            JourLabo(
                date = debut.plusDays(t.toLong()),
                poids = 80 + 0.2 * kotlin.math.sin(1.7 * t) + if (t > 0 && dessert[t - 1]) 0.6 else 0.0,
                entree = EntreeJour(
                    debut.plusDays(t.toLong()),
                    mapOf(
                        "resservi" to if (rab[t]) "oui" else "non",
                        "dessert" to if (dessert[t]) "normale" else "non",
                    ),
                ),
            )
        }
        val r = Labo.analyser(jours, debut.plusDays((n - 1).toLong()))
        val effetRab = r.lendemain.first { it.libelle == "Soir avec rab" }.j1!!
        val effetDessert = r.lendemain.first { it.libelle == "Dessert pris" }.j1!!
        assertEquals(-0.103316, effetRab.estimation, 1e-4)
        assertEquals(0.658245, effetDessert.estimation, 1e-4)
        assertTrue(abs(effetRab.estimation) < 0.2)
        assertNotNull(effetDessert.p)
    }

    /**
     * Dépense 2 500 kcal/j, perte de 0,1 kg/j. Références numpy avec 7 700 kcal/kg (1 725,39 en semaine 5,
     * écart-type 135,409 ; 7 derniers jours 1 728,567), ramenées à ρ = 8 840 (v1.13) : l'énergie stockée et son
     * écart-type sont proportionnels à la densité.
     */
    @Test
    fun apportsEstimesAvecIntervalle() {
        val k = Hall.RHO / 7700.0
        val lundi = LocalDate.of(2026, 8, 2) // un dimanche
        val jours = (0L until 71L).map { k -> JourLabo(date = lundi.plusDays(k), poids = 85.0 - 0.1 * k, kcal = 2500.0) }
        val r = Labo.analyser(jours, lundi.plusDays(70))
        val s5 = r.semaines.first { it.debut == lundi.plusWeeks(5) }
        assertFalse(s5.enCours)
        assertEquals(Methode.POIDS, s5.methode)
        assertEquals(2500.0, s5.depenseMoyenne, 1e-9)
        assertEquals(2500 - (2500 - 1725.39) * k, s5.apportsEstimes, 0.06)
        assertEquals(1.959964 * 135.409 * k, (s5.apports.haut - s5.apports.bas) / 2, 0.06)

        // 7 derniers jours : J63 → J70 ; aujourd'hui est un dimanche, ils reproduisent la semaine 9.
        val sept = r.septDerniersJours!!
        assertEquals(lundi.plusDays(63), sept.debut)
        assertTrue(sept.enCours)
        assertEquals(2500 - (2500 - 1728.567) * k, sept.apportsEstimes, 0.012)
        assertEquals(r.semaines.first { it.debut == lundi.plusWeeks(9) }.apportsEstimes, sept.apportsEstimes, 1e-9)
    }

    /**
     * Graisse −0,05 kg/j et maigre −0,015 kg/j, dépense 2 500 : bilan vrai 2 500 − (9 440 × 0,35 + 1 816 × 0,105) ÷ 7
     * ≈ 2 000,8 kcal/j. Référence numpy (mêmes paramètres de lissage) : 2 001,256 ; écart-type 98,738 sans la
     * corrélation des erreurs, 54,058 avec ρ = −0,75 (balance sans mesure d'eau).
     */
    @Test
    fun apportsParCompartiments() {
        val lundi = LocalDate.of(2026, 8, 2) // un dimanche
        val jours = (0L until 71L).map { k ->
            val fm = 22 - 0.05 * k
            val ffm = 66 - 0.015 * k
            JourLabo(date = lundi.plusDays(k), poids = fm + ffm, graissePct = 100 * fm / (fm + ffm), kcal = 2500.0)
        }
        val r = Labo.analyser(jours, lundi.plusDays(70))
        val s5 = r.semaines.first { it.debut == lundi.plusWeeks(5) }
        assertEquals(Methode.COMPOSITION, s5.methode)
        assertEquals(-0.349602, s5.deltaGraisseKg!!, 1e-5)
        assertEquals(-0.105158, s5.deltaMaigreKg!!, 1e-5)
        assertEquals(2001.256, s5.apportsEstimes, 0.01)
        assertEquals(1.959964 * 54.0580, (s5.apports.haut - s5.apports.bas) / 2, 0.01)
        assertNotNull(r.composition)
        assertEquals(25.0, 100 * 22 / 88.0, 1e-9)
    }

    /**
     * Même cas avec l'eau mesurée (constante : écart nul) : paramètres des mesures corrigées et ρ = −0,15.
     * Référence numpy : graisse −0,350343, maigre −0,105073, apports 2 000,279, écart-type 82,886.
     */
    @Test
    fun apportsParCompartimentsCorrigesParLEau() {
        val lundi = LocalDate.of(2026, 8, 2) // un dimanche
        val jours = (0L until 71L).map { k ->
            val fm = 22 - 0.05 * k
            val ffm = 66 - 0.015 * k
            JourLabo(
                date = lundi.plusDays(k), poids = fm + ffm, graissePct = 100 * fm / (fm + ffm), eauKg = 46.0,
                kcal = 2500.0,
            )
        }
        val r = Labo.analyser(jours, lundi.plusDays(70))
        val s5 = r.semaines.first { it.debut == lundi.plusWeeks(5) }
        assertEquals(Methode.COMPOSITION, s5.methode)
        assertEquals(-0.350343, s5.deltaGraisseKg!!, 1e-5)
        assertEquals(-0.105073, s5.deltaMaigreKg!!, 1e-5)
        assertEquals(2000.279, s5.apportsEstimes, 0.01)
        assertEquals(1.959964 * 82.8864, (s5.apports.haut - s5.apports.bas) / 2, 0.01)
    }

    /**
     * Poids vrai 80 kg, graisse vraie 20 kg ; l'eau varie chaque jour, le poids suit (× 0,73) et la balance
     * déplace la graisse (× −0,8). Les écarts d'eau sont retrouvés à 0,05 kg près et les tendances corrigées
     * restent plus près de la vérité que sans la correction (références numpy : 0,029 contre 0,044 kg pour le
     * poids, 0,030 contre 0,034 kg pour la graisse).
     */
    @Test
    fun correctionParLEau() {
        val n = 60
        val ecart = (0 until n).map { t -> 0.6 * kotlin.math.sin(1.7 * t) + 0.3 * kotlin.math.cos(2.9 * t) }
        fun jours(avecEau: Boolean) = (0 until n).map { t ->
            val poids = 80 + 0.73 * ecart[t]
            JourLabo(
                date = debut.plusDays(t.toLong()),
                poids = poids,
                graissePct = 100 * (20 - 0.8 * ecart[t]) / poids,
                eauKg = if (avecEau) 46 + ecart[t] else null,
            )
        }
        val aujourdHui = debut.plusDays((n - 1).toLong())
        val ecarts = Labo.ecartsEau(jours(true), aujourdHui)
        assertEquals(n, ecarts.size)
        (0 until n).forEach { t -> assertEquals(ecart[t], ecarts.getValue(debut.plusDays(t.toLong())), 0.05) }

        fun erreurs(avecEau: Boolean): Pair<Double, Double> {
            val j = jours(avecEau)
            val lis = Labo.lissage(j, aujourdHui)!!
            val comp = Labo.compartiments(j, aujourdHui)!!
            return (0 until n).maxOf { abs(lis.niveau(it) - 80) } to (0 until n).maxOf { abs(comp.graisse.niveau(it) - 20) }
        }
        val (poidsCorrige, graisseCorrigee) = erreurs(true)
        val (poidsBrut, graisseBrute) = erreurs(false)
        assertEquals(0.0294, poidsCorrige, 1e-3)
        assertEquals(0.0444, poidsBrut, 1e-3)
        assertEquals(0.0303, graisseCorrigee, 1e-3)
        assertEquals(0.0340, graisseBrute, 1e-3)
        assertEquals(n, Labo.compartiments(jours(true), aujourdHui)!!.corriges.size)

        // Décomposition de chaque pesée : part de l'eau = 0,73 × écart de l'eau, le reste = corrigée − tendance.
        val r = Labo.analyser(jours(true), aujourdHui)
        r.poids.forEach { p ->
            val e = ecarts.getValue(p.date)
            assertEquals(Labo.EAU_VERS_POIDS * e, p.partEau!!, 1e-9)
            assertEquals(p.corrige!! - p.tendance, p.ecart!! - p.partEau!!, 1e-9)
        }
        assertNull(Labo.analyser(jours(false), aujourdHui).poids.first().partEau)
    }

    /** Jours sans montre : ni sommeil ni séance et moins de 2 000 kcal (Garmin ne compte que le métabolisme de base). */
    @Test
    fun depenseSansMontreEcartee() {
        fun j(kcal: Double?, sommeil: Long? = null, seances: Long? = null) =
            JourLabo(debut, kcal = kcal, sommeilMin = sommeil, minutesSeances = seances)
        assertNull(Labo.kcalRetenue(j(1565.0)))
        assertNull(Labo.kcalRetenue(j(1675.0, seances = 0)))
        assertEquals(1565.0, Labo.kcalRetenue(j(1565.0, sommeil = 420))!!, 0.0)
        assertEquals(1900.0, Labo.kcalRetenue(j(1900.0, seances = 45))!!, 0.0)
        assertEquals(2283.0, Labo.kcalRetenue(j(2283.0))!!, 0.0)
        assertNull(Labo.kcalRetenue(j(700.0, sommeil = 420)))
        assertNull(Labo.kcalRetenue(j(null, sommeil = 420)))

        // Une semaine avec deux jours sans montre : la dépense moyenne ne compte que les cinq autres.
        val lundi = LocalDate.of(2026, 8, 2) // un dimanche
        val jours = (0L until 21L).map { k ->
            val sansMontre = k in 7L..8L
            JourLabo(
                date = lundi.plusDays(k), poids = 85.0, kcal = if (sansMontre) 1565.0 else 2600.0,
                sommeilMin = if (sansMontre) null else 420L,
            )
        }
        val r = Labo.analyser(jours, lundi.plusDays(20))
        val s1 = r.semaines.first { it.debut == lundi.plusWeeks(1) }
        assertEquals(5, s1.nbJoursDepense)
        assertEquals(2600.0, s1.depenseMoyenne, 1e-9)
    }

    @Test
    fun semaineEnCoursApresDeuxJours() {
        val lundi = LocalDate.of(2026, 10, 25) // un dimanche
        val aujourdHui = lundi.plusWeeks(1).plusDays(3) // mercredi
        val jours = (0L..10L).map { k -> JourLabo(date = lundi.plusDays(k), poids = 88.0, kcal = 2600.0) }
        val r = Labo.analyser(jours, aujourdHui)
        val enCours = r.semaines.first()
        assertTrue(enCours.enCours)
        assertEquals(3, enCours.nbJours)
        assertEquals(2600.0, enCours.depenseMoyenne, 1e-9)
        assertEquals(2600.0, enCours.apportsEstimes, 1.0)
    }

    @Test
    fun peseeAberranteEcartee() {
        val jours = (0L until 10L).map { k ->
            JourLabo(date = debut.plusDays(k), poids = if (k == 6L) 93.0 else 80.0)
        }
        val r = Labo.analyser(jours, debut.plusDays(9))
        assertTrue(r.poids.first { it.date == debut.plusDays(6) }.ecartee)
        assertEquals(80.0, r.poids.last().tendance, 0.01)
    }

    /** +0,15 kg le lendemain par unité d'alcool ; référence numpy (même pipeline) : 0,139256. */
    @Test
    fun alcoolParUnite() {
        val motif = listOf(0, 0, 2, 0, 1, 0, 3, 0, 0, 1)
        val n = 60
        val jours = (0 until n).map { t ->
            val u = motif[t % 10]
            val veille = if (t > 0) motif[(t - 1) % 10] else 0
            val d = debut.plusDays(t.toLong())
            JourLabo(
                date = d,
                poids = 80 + 0.2 * kotlin.math.sin(1.7 * t) + 0.15 * veille,
                entree = EntreeJour(
                    d,
                    mapOf("alcool" to if (u > 0) "oui" else "aucun"),
                    if (u > 0) listOf(Boisson("pils", 25.0 * u, 5.0)) else emptyList(),
                ),
            )
        }
        val r = Labo.analyser(jours, debut.plusDays((n - 1).toLong()))
        val alcool = r.lendemain.first { it.libelle == "Alcool, par unité" }
        assertEquals(0.139256, alcool.j1!!.estimation, 1e-4)
        assertEquals(23, alcool.n1)
        assertEquals(36, alcool.n0)
    }

    /**
     * Les soirs « pris ton temps » n'ont jamais de rab, les autres toujours. Depuis la v1.12, le Labo le lit en
     * probabilités bayésiennes (Mecanismes), plus en différence de proportions parmi les effets croisés.
     */
    @Test
    fun mecanismePrisTonTempsContreRab() {
        val jours = (0L until 20L).map { k ->
            val lent = k % 2 == 0L
            JourLabo(
                date = debut.plusDays(k),
                entree = EntreeJour(
                    debut.plusDays(k),
                    mapOf("temps" to if (lent) "oui" else "non", "resservi" to if (lent) "non" else "oui"),
                ),
            )
        }
        val r = Labo.analyser(jours, debut.plusDays(19))
        val effet = r.mecanismes.first { it.libelle == "Pris ton temps" }
        assertEquals(10, effet.n1)
        assertEquals(10, effet.n0)
        assertEquals(0.0, effet.p1!!, 0.0)
        assertEquals(1.0, effet.p0!!, 0.0)
        assertTrue(effet.ecart!!.estimation < -0.5) // tiré vers zéro par l'a priori sceptique
        assertEquals(Niveau.SOLIDE, effet.niveau)
        assertEquals(0, r.croises.count { it.mecanisme })
        assertEquals(Mecanismes.liste.size, r.mecanismes.size)
    }

    @Test
    fun qualiteCompteLesJoursRenseignes() {
        val aujourdHui = debut.plusDays(30)
        val jours = (1L..28L).map { k ->
            val d = aujourdHui.minusDays(k)
            JourLabo(d, entree = if (k % 2 == 0L) EntreeJour(d, mapOf("diner" to "maison")) else null)
        }
        assertEquals(14, Labo.analyser(jours, aujourdHui).qualite.joursRenseignes28)
    }
}
