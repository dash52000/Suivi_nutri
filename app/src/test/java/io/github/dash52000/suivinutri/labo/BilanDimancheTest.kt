package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.Boisson
import io.github.dash52000.suivinutri.data.DecisionBudget
import io.github.dash52000.suivinutri.data.EntreeJour
import io.github.dash52000.suivinutri.data.EtatObjectif
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** Bilan du dimanche (v1.12) : budget proposé, effort déclaré, leviers, différence de différences, bilan complet. */
class BilanDimancheTest {

    private val depart = LocalDate.of(2026, 9, 6) // un dimanche

    @Test
    fun budgetSelonLesDecisions() {
        val etat = EtatObjectif(
            debut = depart,
            decisionsBudget = mapOf(
                depart.plusWeeks(4) to DecisionBudget(-1, true),
                depart.plusWeeks(6) to DecisionBudget(-1, false),
                depart.plusWeeks(8) to DecisionBudget(-1, true),
                depart.plusWeeks(10) to DecisionBudget(1, true),
            ),
        )
        assertEquals(3, etat.budget(depart.plusWeeks(3)))
        assertEquals(2, etat.budget(depart.plusWeeks(4)))
        assertEquals(2, etat.budget(depart.plusWeeks(7))) // refusée : rien ne change
        assertEquals(1, etat.budget(depart.plusWeeks(8)))
        assertEquals(2, etat.budget(depart.plusWeeks(10)))
        // Jamais au-dessus du départ.
        assertEquals(3, EtatObjectif(depart, decisionsBudget = mapOf(depart to DecisionBudget(1, true))).budget(depart))
    }

    private fun semaines(vararg v: Verdict): List<SemaineCap> = v.mapIndexed { i, x ->
        SemaineCap(depart.plusWeeks(i.toLong()), 7, false, null, 5, x, 3, null, 0, null, null, 0, 0L, null)
    }

    @Test
    fun propositionDeBudget() {
        val r = Verdict.RODAGE
        val a = Verdict.AU_DESSUS
        val d = Verdict.DANS_LE_COULOIR
        val etat = EtatObjectif(debut = depart)
        // Après 2 semaines au-dessus : −1, proposé le dimanche qui ferme la 2e.
        assertEquals(-1, Cap.propositionBudget(semaines(r, r, a, a), etat, depart.plusWeeks(4)))
        assertNull(Cap.propositionBudget(semaines(r, r, a, d), etat, depart.plusWeeks(4)))
        // Déjà répondu cette semaine : plus de proposition.
        val repondu = etat.copy(decisionsBudget = mapOf(depart.plusWeeks(4) to DecisionBudget(-1, false)))
        assertNull(Cap.propositionBudget(semaines(r, r, a, a), repondu, depart.plusWeeks(4)))
        // Après une réponse, il faut 2 nouvelles semaines.
        assertNull(Cap.propositionBudget(semaines(r, r, a, a, a), repondu, depart.plusWeeks(5)))
        assertEquals(-1, Cap.propositionBudget(semaines(r, r, a, a, a, a), repondu, depart.plusWeeks(6)))
        // +1 seulement sous le budget de départ.
        assertNull(Cap.propositionBudget(semaines(r, r, d, d), etat, depart.plusWeeks(4)))
        val baisse = etat.copy(decisionsBudget = mapOf(depart.plusWeeks(2) to DecisionBudget(-1, true)))
        assertEquals(1, Cap.propositionBudget(semaines(r, r, d, d), baisse, depart.plusWeeks(4)))
    }

    private fun jour(d: LocalDate, vararg r: Pair<String, String>, boissons: List<Boisson> = emptyList()) =
        JourLabo(d, entree = EntreeJour(d, mapOf(*r), boissons))

    @Test
    fun effortDeclareFaceALHabitude() {
        val tripel = listOf(Boisson("tripel", 33.0, 8.5))
        val reference = (1L..14L).map { depart.minusDays(it) }
        val semaine = (0L..6L).map { depart.plusDays(it) }
        val jours = reference.map { jour(it, "resservi" to "oui", "dessert" to "non", "alcool" to "oui", boissons = tripel) } +
            semaine.map { jour(it, "resservi" to "non", "dessert" to "non", "alcool" to "aucun") }
        val e = BilanDimanche.effortDeclare(jours.associateBy { it.date }, semaine, reference, depart.plusDays(7))!!
        val parLibelle = e.composantes.associateBy { it.libelle }
        assertEquals(-300.0, parLibelle.getValue("Rab").kcalJour, 1e-9)
        assertEquals(0.0, parLibelle.getValue("Dessert").kcalJour, 1e-9)
        // Tripel 33 cl à 8,5 % : 2,244 u × 70 kcal × 1,5.
        assertEquals(-2.244 * 70 * 1.5, parLibelle.getValue("Alcool").kcalJour, 1e-6)
        assertEquals(listOf("Pas hors sport"), e.manquantes)
        assertEquals(-300.0 - 2.244 * 70 * 1.5, e.total, 1e-6)
    }

    @Test
    fun leviersChiffresDAbord() {
        val dimanche = depart.plusWeeks(3)
        val dates = (1L..14L).map { dimanche.minusDays(it) }
        // Rab un soir sur deux, dessert en part normale un soir sur quatre, jamais d'écran le midi.
        val jours = dates.mapIndexed { i, d ->
            jour(d, "resservi" to if (i % 2 == 0) "oui" else "non", "dessert" to if (i % 4 == 0) "normale" else "non", "midi_ecran" to "oui")
        }
        val entrees = jours.associate { it.date to it.entree!! }
        val p = BilanDimanche.propositionsLevier(jours.associateBy { it.date }, entrees, dimanche, Labo.reflexes, emptyList(), null)
        assertEquals(listOf("Soir · pas de rab", "Soir · dessert évité ou gardé", "Midi · sans écran"), p.map { it.libelle })
        assertEquals(0.5 * 7 * 300.0, p[0].gainKcalSemaine!!, 1e-9)
        assertEquals((1 - 10.0 / 14) * 7 * 250.0, p[1].gainKcalSemaine!!, 1e-9)
        assertNull(p[2].gainKcalSemaine) // pas de lien chiffré : proposé parce que jamais tenu
        assertTrue(p[0].gainKgMois!! < 0)
    }

    @Test
    fun leLevierFaceAuxTemoins() {
        val semaine = depart.plusWeeks(2)
        val avant = (1L..14L).map { semaine.minusDays(it) }
        val pendant = (0L..6L).map { semaine.plusDays(it) }
        // Rab : tenu 2 soirs sur 14 avant, 6 sur 7 pendant. Témoin (écran du midi) : 0 sur 14 avant, 2 sur 7 pendant.
        val jours = avant.mapIndexed { i, d -> jour(d, "resservi" to if (i < 2) "non" else "oui", "midi_ecran" to "oui") } +
            pendant.mapIndexed { i, d -> jour(d, "resservi" to if (i < 6) "non" else "oui", "midi_ecran" to if (i < 2) "non" else "oui") }
        val etat = EtatObjectif(
            debut = depart,
            leviers = mapOf(semaine to "Soir · pas de rab"),
            propositionsLevier = mapOf(semaine to listOf("Soir · pas de rab", "Midi · sans écran")),
        )
        val b = BilanDimanche.bilanLevier(
            etat, semaine, jours.associate { it.date to it.entree!! }, jours.associateBy { it.date }, Labo.reflexes, null, semaine.plusDays(7),
        )!!
        assertEquals(2.0 / 14, b.avant!!, 1e-9)
        assertEquals(6.0 / 7, b.apres!!, 1e-9)
        assertEquals(1, b.nbTemoins)
        assertEquals(2.0 / 7, b.variationTemoins!!, 1e-9)
        assertEquals(6.0 / 7 - 2.0 / 14 - 2.0 / 7, b.effet!!, 1e-9)
    }

    /** Bilan complet sur 10 semaines : poids qui baisse à −400 kcal/j, eau, questionnaire, alcool. */
    @Test
    fun bilanComplet() {
        val jours = (-56L until 71L).map { k ->
            val d = depart.plusDays(k)
            val t = maxOf(0L, k).toDouble()
            val eau = if (k % 7 == 3L) 0.6 else 0.0
            JourLabo(
                date = d,
                poids = 85.0 + Hall.variation(-400.0, t) + 0.73 * eau,
                eauKg = 45.0 + eau,
                graissePct = 20.0,
                heurePesee = 7,
                kcal = 2600.0,
                nbSeances = 1,
                minutesSeances = 40,
                nbRenfos = if (d.dayOfWeek == DayOfWeek.TUESDAY) 1 else 0,
                entree = EntreeJour(
                    d,
                    mapOf(
                        "resservi" to if (k < 0) "oui" else "non", "dessert" to "non", "alcool" to "aucun",
                        "temps" to "oui", "diner" to "maison", "whey" to "1", "oeufs" to "2", "viande" to "2",
                    ),
                ),
            )
        }
        val dimanche = depart.plusWeeks(10)
        val b = BilanDimanche.calculer(jours, dimanche.plusDays(2), EtatObjectif(debut = depart), Labo.reflexes)
        assertEquals(dimanche, b.dimanche) // un mardi demandé : le bilan du dimanche d'avant
        assertEquals(10, b.semainesDepuisDepart)
        assertEquals(depart.plusWeeks(9), b.semaine!!.debut)
        assertEquals(Verdict.DANS_LE_COULOIR, b.semaine!!.verdict)
        assertEquals(7, b.balance.size)
        assertEquals(dimanche, b.balance.last().date)
        assertTrue(b.ecartDepart!! < b.attenduHaut!! + 0.5 && b.ecartDepart!! > b.attenduBas!! - 0.5)
        assertEquals(1, b.renfos)
        assertEquals(25.0 + 12.0 + 50.0, b.proteinesGkg!! * (85.0 + b.ecartDepart!!), 1.0)
        // Plus de rab depuis le départ : −300 kcal/j déclarés sur le rab.
        assertEquals(-300.0, b.effortDeclare!!.composantes.first { it.libelle == "Rab" }.kcalJour, 1e-9)
        assertNull(b.faceAFace) // 10e semaine : pas un multiple de 4
        assertNotNull(b.graisse4)
        assertEquals(3, b.budget)
        assertEquals(DayOfWeek.FRIDAY, b.soirARisque.jour)
    }
}
