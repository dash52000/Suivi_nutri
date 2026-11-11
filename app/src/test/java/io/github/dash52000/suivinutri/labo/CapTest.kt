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

/** Objectif de perte : couloir d'effort (v1.11), bières, levier, plan du week-end. */
class CapTest {

    private val bas = Hall.effortPourPente(EtatObjectif.COULOIR_BAS)
    private val haut = Hall.effortPourPente(EtatObjectif.COULOIR_HAUT)

    @Test
    fun modeleDeHall() {
        // Référence : (ΔApports / ε) × (1 − exp(−ε t / ρ)), ρ = 8 840, ε = 25,8 (Sanghvi 2015).
        assertEquals(-2.71215, Hall.variation(-300.0, 91.0), 1e-4)
        assertEquals(0.0, Hall.variation(-300.0, 0.0), 1e-12)
        assertEquals(-315.71, Hall.effortPourPente(-0.25), 0.01)
        assertEquals(-505.14, Hall.effortPourPente(-0.40), 0.01)
    }

    @Test
    fun semaineDuDimanche() {
        assertEquals(LocalDate.of(2026, 11, 8), Cap.debutSemaine(LocalDate.of(2026, 11, 11))) // mercredi
        assertEquals(LocalDate.of(2026, 11, 8), Cap.debutSemaine(LocalDate.of(2026, 11, 8))) // dimanche
        assertEquals(LocalDate.of(2026, 11, 8), Cap.debutSemaine(LocalDate.of(2026, 11, 14))) // samedi
    }

    @Test
    fun verdictSelonLEffort() {
        assertEquals(Verdict.DANS_LE_COULOIR, Cap.verdict(Intervalle(-400.0, -600.0, -200.0), 5, bas, haut))
        // −100 ± 100 (écart-type) : P(effort > −316) ≈ 98 %.
        assertEquals(Verdict.AU_DESSUS, Cap.verdict(Intervalle(-100.0, -296.0, 96.0), 5, bas, haut))
        // −200 ± 100 : P ≈ 87 %, sous le seuil de 90 % ; estimation hors couloir : on ne tranche pas.
        assertEquals(Verdict.PAS_LISIBLE, Cap.verdict(Intervalle(-200.0, -396.0, -4.0), 5, bas, haut))
        assertEquals(Verdict.PLUS_VITE, Cap.verdict(Intervalle(-800.0, -900.0, -700.0), 5, bas, haut))
        assertEquals(Verdict.PAS_LISIBLE, Cap.verdict(Intervalle(-400.0, -450.0, -350.0), 2, bas, haut))
        assertEquals(Verdict.PAS_LISIBLE, Cap.verdict(null, 7, bas, haut))
    }

    @Test
    fun regleDuBudget() {
        val a = Verdict.AU_DESSUS
        val d = Verdict.DANS_LE_COULOIR
        val p = Verdict.PAS_LISIBLE
        assertEquals(listOf(3, 3, 2, 2, 1, 1, 2, 2, 2, 3), Cap.budgets(listOf(a, a, a, a, d, d, p, d, d), 3))
        assertEquals(listOf(3, 3, 3, 3, 3), Cap.budgets(listOf(d, d, Verdict.PLUS_VITE, d), 3))
        assertEquals(listOf(1, 1, 0, 0, 0), Cap.budgets(listOf(a, a, a, a), 1))
        assertEquals(listOf(3, 3, 3, 3), Cap.budgets(listOf(a, p, a), 3))
        assertEquals(2, Cap.premiereBaisse(listOf(d, a, a, a)))
        assertNull(Cap.premiereBaisse(listOf(a, p, a, d)))
    }

    @Test
    fun bieresEnEquivalent33cl() {
        val d = LocalDate.of(2026, 11, 13)
        val e = EntreeJour(d, mapOf("alcool" to "oui"), listOf(Boisson("tripel", 33.0, 8.5), Boisson("vin", 12.0, 12.5)))
        assertEquals(1.0, Cap.bieresEq33(e)!!, 1e-9)
        val grande = EntreeJour(d, mapOf("alcool" to "oui"), listOf(Boisson("quadrupel", 75.0, 10.0)))
        assertEquals(75.0 / 33.0, Cap.bieresEq33(grande)!!, 1e-9)
        assertEquals(0.0, Cap.bieresEq33(EntreeJour(d, mapOf("alcool" to "aucun")))!!, 1e-9)
        // « Oui » sans boisson saisie : on ne sait pas combien, ce n'est pas zéro (v1.11).
        assertNull(Cap.bieresEq33(EntreeJour(d, mapOf("alcool" to "oui"))))
        assertNull(Cap.bieresEq33(EntreeJour(d, mapOf("tupp" to "oui"))))
        assertNull(Cap.bieresEq33(null))
    }

    @Test
    fun proteinesEnPortions() {
        val d = LocalDate.of(2026, 11, 13)
        val e = EntreeJour(d, mapOf("whey" to "1", "oeufs" to "3", "viande" to "2"))
        assertEquals(25.0 + 3 * 6.0 + 2 * 25.0, Labo.proteinesG(e)!!, 1e-9)
        assertEquals(0.0, Labo.proteinesG(EntreeJour(d, mapOf("whey" to "0", "oeufs" to "0", "viande" to "0")))!!, 1e-9)
        // Une des trois sans réponse : pas d'estimation.
        assertNull(Labo.proteinesG(EntreeJour(d, mapOf("whey" to "1", "oeufs" to "3"))))
        assertNull(Labo.proteinesG(null))
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

    /** Poids sans bruit : 8 semaines stables à 85 kg, puis un effort constant [effort] à partir du dimanche [depart]. */
    private fun serieHall(effort: Double, semainesApres: Int): Pair<List<JourLabo>, LocalDate> {
        val depart = LocalDate.of(2026, 9, 6) // un dimanche
        val avant = 56L
        val jours = (-avant until 7L * semainesApres + 1).map { k ->
            val t = maxOf(0L, k).toDouble()
            JourLabo(date = depart.plusDays(k), poids = 85.0 + Hall.variation(effort, t), kcal = 2500.0, nbSeances = 1, minutesSeances = 45)
        }
        return jours to depart
    }

    /**
     * Effort tenu à −400 kcal/j depuis le départ. Verdicts figés : la tendance met environ 3 semaines à suivre le
     * changement (−86, −196, −292 puis −353 kcal/j) ; les 2 premières semaines sont en rodage, aucune n'est jugée
     * « au-dessus », puis toutes sont dans le couloir et l'effort est retrouvé à quelques kcal/j près.
     */
    @Test
    fun effortTenuDansLeCouloir() {
        val (jours, depart) = serieHall(-400.0, 8)
        val auj = depart.plusWeeks(8).plusDays(3) // mercredi de la 9e semaine
        val r = Cap.analyser(jours, Labo.lissage(jours, auj), EtatObjectif(debut = depart.plusDays(2)), auj, Labo.reflexes)
        assertEquals(depart, r.semaineDepart)
        assertEquals(-505.14, r.effortBas, 0.01)
        assertEquals(-315.71, r.effortHaut, 0.01)
        assertEquals(9, r.semaines.size)
        r.semainesFinies.forEachIndexed { i, s ->
            assertEquals(DayOfWeek.SUNDAY, s.debut.dayOfWeek)
            when {
                i < 2 -> assertTrue(s.verdict == Verdict.RODAGE || s.verdict == Verdict.PAS_LISIBLE)
                i == 2 -> assertTrue(s.verdict != Verdict.AU_DESSUS)
                else -> assertEquals(Verdict.DANS_LE_COULOIR, s.verdict)
            }
            if (i >= 5) assertEquals(-400.0, s.effort!!.estimation, 15.0)
            assertEquals(3, s.budget)
            assertEquals(7, s.nbSeances)
        }
        // Première semaine : fenêtre de 7 jours (rien avant le départ), ensuite 14.
        assertEquals(7, r.semainesFinies.first().mesure!!.jours)
        assertEquals(14, r.semainesFinies.last().mesure!!.jours)
        // Semaine en cours, 3 jours écoulés : pas encore d'effort provisoire.
        assertTrue(r.semaineEnCours.enCours)
        assertNull(r.semaineEnCours.mesure)
        assertEquals(Verdict.PAS_LISIBLE, r.semaineEnCours.verdict)
        assertEquals(-400.0, r.effortDepuisDebut!!.estimation, 30.0)
        assertEquals(3, r.budgetCourant)
    }

    /** Le verdict d'une semaine finie ne bouge plus quand de nouvelles pesées arrivent. */
    @Test
    fun verdictsFiges() {
        val (jours, depart) = serieHall(-250.0, 10)
        val etat = EtatObjectif(debut = depart)
        val t1 = depart.plusWeeks(6)
        val t2 = depart.plusWeeks(10)
        val avant = jours.filter { !it.date.isAfter(t1) }
        val r1 = Cap.analyser(avant, Labo.lissage(avant, t1), etat, t1, Labo.reflexes)
        val r2 = Cap.analyser(jours, Labo.lissage(jours, t2), etat, t2, Labo.reflexes)
        r1.semainesFinies.forEach { s1 ->
            val s2 = r2.semaines.first { it.debut == s1.debut }
            assertEquals(s1.effort!!.estimation, s2.effort!!.estimation, 1e-9)
            assertEquals(s1.verdict, s2.verdict)
        }
    }

    /** Prise de poids : au-dessus du couloir, la règle proposerait une baisse, mais le budget reste figé en v1.11. */
    @Test
    fun priseDePoidsBudgetFige() {
        val (jours, depart) = serieHall(200.0, 6)
        val auj = depart.plusWeeks(6)
        val r = Cap.analyser(jours, Labo.lissage(jours, auj), EtatObjectif(debut = depart), auj, Labo.reflexes)
        assertEquals(listOf(Verdict.RODAGE, Verdict.RODAGE), r.semainesFinies.take(2).map { it.verdict })
        assertTrue(r.semainesFinies.drop(2).all { it.verdict == Verdict.AU_DESSUS })
        assertEquals(3, Cap.premiereBaisse(r.semainesFinies.map { it.verdict }))
        assertTrue(r.semaines.all { it.budget == 3 })
        assertEquals(3, r.budgetCourant)
    }

    /** Sans tendance du poids (pas de Health Connect), bières, protéines et levier restent calculés. */
    @Test
    fun sansPoidsLeResteFonctionne() {
        val auj = LocalDate.of(2026, 11, 12) // jeudi
        val dimanche = Cap.debutSemaine(auj)
        val jours = (0L..4L).map { k ->
            val d = dimanche.plusDays(k)
            JourLabo(
                d,
                entree = EntreeJour(
                    d,
                    mapOf(
                        "resservi" to if (k % 2 == 0L) "non" else "oui", "alcool" to "oui",
                        "whey" to "1", "oeufs" to "2", "viande" to "1",
                    ),
                    listOf(Boisson("blonde", 33.0, 6.5)),
                ),
            )
        }
        val etat = EtatObjectif(debut = dimanche, leviers = mapOf(dimanche to "Soir · pas de rab"))
        val r = Cap.analyser(jours, null, etat, auj, Labo.reflexes)
        val s = r.semaineEnCours
        assertEquals(dimanche, s.debut)
        assertEquals(Verdict.PAS_LISIBLE, s.verdict)
        assertNull(s.mesure)
        assertEquals(5.0, s.bieres!!, 1e-9)
        assertEquals(5, s.joursAlcool)
        assertEquals(Adherence(3.0, 5), s.adherence)
        assertEquals(62.0, s.proteines!!, 1e-9)
        assertEquals(5, s.joursProteines)
        assertEquals(3, s.budget)
        assertNotNull(r.propositions)
    }
}
