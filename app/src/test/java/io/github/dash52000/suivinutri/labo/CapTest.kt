package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.Boisson
import io.github.dash52000.suivinutri.data.EntreeJour
import io.github.dash52000.suivinutri.data.EtatObjectif
import io.github.dash52000.suivinutri.data.PlanWeekEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** Objectif de perte (v1.9). Pentes de référence : lisseur réécrit en numpy (voir KalmanTest). */
class CapTest {

    private val bas = EtatObjectif.COULOIR_BAS
    private val haut = EtatObjectif.COULOIR_HAUT

    @Test
    fun verdictSelonLIntervalle() {
        assertEquals(Verdict.DANS_LE_COULOIR, Cap.verdict(Intervalle(-0.30, -0.55, -0.05), 5, bas, haut))
        assertEquals(Verdict.AU_DESSUS, Cap.verdict(Intervalle(0.10, -0.20, 0.40), 5, bas, haut))
        assertEquals(Verdict.PLUS_VITE, Cap.verdict(Intervalle(-0.80, -1.00, -0.50), 5, bas, haut))
        // Estimation hors couloir mais intervalle qui le touche : on ne tranche pas.
        assertEquals(Verdict.PAS_LISIBLE, Cap.verdict(Intervalle(-0.10, -0.35, 0.15), 5, bas, haut))
        assertEquals(Verdict.PAS_LISIBLE, Cap.verdict(Intervalle(-0.30, -0.35, -0.25), 2, bas, haut))
        assertEquals(Verdict.PAS_LISIBLE, Cap.verdict(null, 7, bas, haut))
    }

    @Test
    fun budgetDeuxSemainesDeSuite() {
        val a = Verdict.AU_DESSUS
        val d = Verdict.DANS_LE_COULOIR
        val p = Verdict.PAS_LISIBLE
        assertEquals(listOf(3, 3, 2, 2, 1, 1, 2, 2, 2, 3), Cap.budgets(listOf(a, a, a, a, d, d, p, d, d), 3))
        // Jamais au-dessus du budget de départ, jamais sous zéro.
        assertEquals(listOf(3, 3, 3, 3, 3), Cap.budgets(listOf(d, d, Verdict.PLUS_VITE, d), 3))
        assertEquals(listOf(1, 1, 0, 0, 0), Cap.budgets(listOf(a, a, a, a), 1))
        // Une semaine pas lisible coupe la série.
        assertEquals(listOf(3, 3, 3, 3), Cap.budgets(listOf(a, p, a), 3))
    }

    @Test
    fun bieresEnEquivalent33cl() {
        val d = LocalDate.of(2026, 11, 13)
        val e = EntreeJour(d, mapOf("alcool" to "oui"), listOf(Boisson("tripel", 33.0, 8.5), Boisson("vin", 12.0, 12.5)))
        assertEquals(1.0, Cap.bieresEq33(e)!!, 1e-9)
        val grande = EntreeJour(d, mapOf("alcool" to "oui"), listOf(Boisson("quadrupel", 75.0, 10.0)))
        assertEquals(75.0 / 33.0, Cap.bieresEq33(grande)!!, 1e-9)
        assertEquals(0.0, Cap.bieresEq33(EntreeJour(d, mapOf("alcool" to "aucun")))!!, 1e-9)
        assertNull(Cap.bieresEq33(EntreeJour(d, mapOf("tupp" to "oui"))))
        assertNull(Cap.bieresEq33(null))
    }

    @Test
    fun propositionsLesMoinsTenus() {
        val auj = LocalDate.of(2026, 11, 10)
        val entrees = (0L until 14L).associate { k ->
            val d = auj.minusDays(k)
            val r = mutableMapOf("resservi" to "oui", "temps" to "oui", "dessert" to "petite")
            if (k < 2) r["boites"] = "non" // 2 réponses seulement : pas proposé
            d to EntreeJour(d, r)
        }
        assertEquals(
            listOf("Soir · pas de rab", "Soir · dessert évité ou gardé", "Soir · lentement"),
            Cap.propositions(entrees, auj, Labo.reflexes),
        )
    }

    @Test
    fun soirQuiDerape() {
        val auj = LocalDate.of(2026, 11, 11) // un mercredi
        fun entrees(jourRab: DayOfWeek) = (1L..56L).associate { k ->
            val d = auj.minusDays(k)
            d to EntreeJour(d, mapOf("resservi" to if (d.dayOfWeek == jourRab) "oui" else "non"))
        }
        val ven = Cap.momentQuiDerape(entrees(DayOfWeek.FRIDAY), auj)
        assertEquals(DayOfWeek.FRIDAY, ven.jour)
        assertFalse(ven.parDefaut)
        assertEquals(8, ven.derapages)
        assertEquals(8, ven.soirs)
        assertEquals(DayOfWeek.SATURDAY, Cap.momentQuiDerape(entrees(DayOfWeek.SATURDAY), auj).jour)

        val defaut = Cap.momentQuiDerape(emptyMap(), auj)
        assertEquals(DayOfWeek.FRIDAY, defaut.jour)
        assertTrue(defaut.parDefaut)

        // L'alcool compte à partir de 2 unités (une Tripel de 33 cl : 2,24 u).
        val d = auj.minusDays(1)
        assertEquals(true, Cap.derapage(EntreeJour(d, mapOf("alcool" to "oui"), listOf(Boisson("tripel", 33.0, 8.5)))))
        assertEquals(false, Cap.derapage(EntreeJour(d, mapOf("alcool" to "oui"), listOf(Boisson("pils", 25.0, 5.0)))))
        assertNull(Cap.derapage(EntreeJour(d, mapOf("tupp" to "oui"))))
    }

    @Test
    fun planDuWeekEndFaceAuRealise() {
        val ven = LocalDate.of(2026, 11, 13)
        val plan = PlanWeekEnd(
            ven,
            repas = mapOf("ven_soir" to PlanWeekEnd.DEHORS, "sam_soir" to PlanWeekEnd.NORMAL),
            bieres = mapOf("ven" to 2, "sam" to 1),
        )
        val entrees = mapOf(
            ven to EntreeJour(ven, mapOf("diner" to "ailleurs", "alcool" to "oui"), listOf(Boisson("tripel", 33.0, 8.5))),
            ven.plusDays(1) to EntreeJour(
                ven.plusDays(1), mapOf("diner" to "beaux_parents", "alcool" to "oui"),
                listOf(Boisson("pils", 25.0, 5.0), Boisson("pils", 25.0, 5.0)),
            ),
            ven.plusDays(2) to EntreeJour(ven.plusDays(2), mapOf("midi_lieu" to "maison", "alcool" to "aucun")),
        )
        val b = Cap.bilanPlan(plan, entrees)
        assertEquals(3, b.bieresPrevues)
        assertEquals(1.0 + 50.0 / 33.0, b.bieresBues!!, 1e-9)
        assertEquals(1, b.dehorsPrevus)
        assertEquals(2, b.dehorsFaits)
        assertEquals(3, b.repasRenseignes)
        assertEquals(listOf("Sam soir"), b.ecarts)

        assertEquals(2, plan.bieresPrevues(ven))
        assertNull(plan.bieresPrevues(ven.plusDays(2)))
        assertEquals(1, plan.repasDehorsPrevus(ven))
        assertNull(plan.repasDehorsPrevus(ven.plusDays(2)))
        assertEquals(ven, PlanWeekEnd.vendrediDe(ven.plusDays(2)))
        assertNull(PlanWeekEnd.vendrediDe(ven.plusDays(3)))
    }

    private fun lineaire(penteSemaine: Double): Pair<List<JourLabo>, LocalDate> {
        val lundi = LocalDate.of(2026, 8, 3) // un lundi
        val jours = (0L until 71L).map { k ->
            JourLabo(date = lundi.plusDays(k), poids = 85.0 + penteSemaine / 7 * k, kcal = 2500.0, nbSeances = 1, minutesSeances = 45)
        }
        return jours to lundi
    }

    /** −0,35 kg/semaine : chaque semaine dans le couloir (référence numpy −0,3521, IC ± 0,24), budget intact. */
    @Test
    fun perteReguliereDansLeCouloir() {
        val (jours, lundi) = lineaire(-0.35)
        val auj = lundi.plusDays(70)
        val r = Cap.analyser(jours, Labo.lissage(jours, auj), EtatObjectif(debut = lundi.plusDays(30)), auj, Labo.reflexes)
        assertEquals(lundi.plusDays(28), r.lundiDepart)
        assertEquals(7, r.semaines.size)
        r.semainesFinies.forEach { s ->
            assertEquals(Verdict.DANS_LE_COULOIR, s.verdict)
            assertEquals(-0.35, s.pente!!.estimation, 0.01)
            assertEquals(8, s.nbPesees)
            assertEquals(3, s.budget)
            assertEquals(7, s.nbSeances)
            assertEquals(2500.0, s.depenseMoyenne!!, 1e-9)
        }
        assertEquals(-0.3521, r.semainesFinies.first().pente!!.estimation, 1e-3)
        assertEquals(0.1231 * Stats.Z975, r.semainesFinies.first().pente!!.haut - r.semainesFinies.first().pente!!.estimation, 2e-3)
        assertTrue(r.semaineEnCours.enCours)
        assertNull(r.semaineEnCours.pente)
        assertEquals(Verdict.PAS_LISIBLE, r.semaineEnCours.verdict)
        assertEquals(3, r.budgetCourant)
        assertNotNull(r.penteDepuisDebut)
        assertEquals(-0.3514, r.penteDepuisDebut!!.estimation, 1e-3)
    }

    /** +0,35 kg/semaine : au-dessus du couloir toutes les semaines, une bière de moins toutes les 2 semaines. */
    @Test
    fun priseDePoidsBaisseLeBudget() {
        val (jours, lundi) = lineaire(0.35)
        val auj = lundi.plusDays(70)
        val r = Cap.analyser(jours, Labo.lissage(jours, auj), EtatObjectif(debut = lundi.plusDays(28)), auj, Labo.reflexes)
        assertTrue(r.semainesFinies.all { it.verdict == Verdict.AU_DESSUS })
        assertEquals(listOf(3, 3, 2, 2, 1, 1), r.semainesFinies.map { it.budget })
        assertEquals(0, r.budgetCourant)
    }

    /** Sans tendance du poids (pas de Health Connect), bières et levier restent calculés. */
    @Test
    fun sansPoidsLeResteFonctionne() {
        val auj = LocalDate.of(2026, 11, 12) // jeudi
        val lundi = Cap.lundiDe(auj)
        val jours = (0L..3L).map { k ->
            val d = lundi.plusDays(k)
            JourLabo(d, entree = EntreeJour(d, mapOf("resservi" to if (k % 2 == 0L) "non" else "oui", "alcool" to "oui"), listOf(Boisson("blonde", 33.0, 6.5))))
        }
        val etat = EtatObjectif(debut = lundi, leviers = mapOf(lundi to "Soir · pas de rab"))
        val r = Cap.analyser(jours, null, etat, auj, Labo.reflexes)
        val s = r.semaineEnCours
        assertEquals(Verdict.PAS_LISIBLE, s.verdict)
        assertEquals(4.0, s.bieres!!, 1e-9)
        assertEquals(4, s.joursAlcool)
        assertEquals(Adherence(2.0, 4), s.adherence)
        assertEquals(3, s.budget)
    }
}
