package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.EtatObjectif
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Pas hors sport (v1.10). */
class PasTest {

    private val t0 = Instant.parse("2026-11-10T11:00:00Z")
    private fun min(m: Long): Instant = t0.plusSeconds(60 * m)

    /** Tranches de 15 min ; séance de 11 h 30 à 12 h 07 : 2 tranches entières et 7/15 d'une troisième. */
    @Test
    fun soustractionAuProrata() {
        val tranches = (0L until 8L).map { k -> TranchePas(min(15 * k), min(15 * k + 15), 1500) }
        val seance = listOf(Plage(min(30), min(67)))
        assertEquals(1500.0, Pas.pendantSeances(tranches[2], seance), 1e-9)
        assertEquals(700.0, Pas.pendantSeances(tranches[4], seance), 1e-9)
        assertEquals(0.0, Pas.pendantSeances(tranches[0], seance), 1e-9)
        assertEquals(8 * 1500L - 3700L, Pas.horsSeances(tranches, seance))
    }

    /** Deux séances qui se chevauchent ne retirent pas deux fois les mêmes pas. */
    @Test
    fun seancesQuiSeChevauchent() {
        val t = TranchePas(min(0), min(60), 6000)
        val seances = listOf(Plage(min(0), min(30)), Plage(min(15), min(45)))
        assertEquals(4500.0, Pas.pendantSeances(t, seances), 1e-9)
        assertEquals(1500L, Pas.horsSeances(listOf(t), seances))
    }

    @Test
    fun trancheInstantanee() {
        val seance = listOf(Plage(min(10), min(20)))
        assertEquals(300.0, Pas.pendantSeances(TranchePas(min(15), min(15), 300), seance), 1e-9)
        assertEquals(0.0, Pas.pendantSeances(TranchePas(min(25), min(25), 300), seance), 1e-9)
    }

    @Test
    fun diagnosticDesTranches() {
        val tranches = listOf(
            TranchePas(min(0), min(15), 100), TranchePas(min(15), min(30), 200), TranchePas(min(30), min(90), 300),
        )
        val d = Pas.diagnostic(tranches, emptyList(), listOf("garmin"))
        assertEquals(3, d.nbTranches)
        assertEquals(15.0, d.dureeMedianeMin!!, 1e-9)
        assertEquals(60.0, d.dureeMaxMin!!, 1e-9)
        assertEquals(600L, d.total)
        assertEquals(600L, d.horsSport)
        assertEquals(2.0, Pas.mediane(listOf(3.0, 1.0, 2.0))!!, 1e-9)
        assertEquals(2.5, Pas.mediane(listOf(4.0, 1.0, 2.0, 3.0))!!, 1e-9)
        assertNull(Pas.mediane(emptyList()))
    }

    /**
     * Base = médiane des 8 semaines avant le départ (6 000 pas), cible 8 000. Les jours à moins de 1 000 pas
     * (montre pas portée) et aujourd'hui (journée pas finie) ne comptent pas.
     */
    @Test
    fun baseCibleEtSemaine() {
        val depart = LocalDate.of(2026, 11, 9) // un lundi
        val auj = depart.plusDays(3)
        val avant = (1L..56L).map { k ->
            val d = depart.minusDays(k)
            JourLabo(d, pasTotal = 7000, pasHorsSport = if (k % 2 == 0L) 5000 else 7000)
        }
        val semaine = listOf(
            JourLabo(depart, pasTotal = 9500, pasHorsSport = 9000),
            JourLabo(depart.plusDays(1), pasTotal = 7000, pasHorsSport = 6000),
            JourLabo(depart.plusDays(2), pasTotal = 500, pasHorsSport = 500),
            JourLabo(auj, pasTotal = 2000, pasHorsSport = 2000),
        )
        val jours = avant + semaine
        assertEquals(6000.0, Cap.basePas(jours, depart, auj)!!, 1e-9)
        val etat = EtatObjectif(debut = depart, leviers = mapOf(depart to Cap.LEVIER_PAS))
        val r = Cap.analyser(jours, null, etat, auj, Labo.reflexes)
        assertEquals(8000.0, r.ciblePas!!, 1e-9)
        val s = r.semaineEnCours
        assertEquals(2, s.joursPas)
        assertEquals(7500.0, s.pasHorsSport!!, 1e-9)
        assertEquals(1, s.joursCiblePas)
        assertEquals(Adherence(1.0, 2), s.adherence)
        // 14 derniers jours : un seul jour au-dessus de 8 000 sur 12 retenus, le levier est proposé.
        assertTrue(Cap.LEVIER_PAS in r.propositions)
    }

    /** Sans assez de jours avant le départ, la base vient des 8 premières semaines de données. */
    @Test
    fun baseSansHistorique() {
        val depart = LocalDate.of(2026, 11, 9)
        val jours = (0L until 20L).map { k -> JourLabo(depart.plusDays(k), pasTotal = 8000, pasHorsSport = 4000) }
        assertEquals(4000.0, Cap.basePas(jours, depart, depart.plusDays(30))!!, 1e-9)
        assertNull(Cap.basePas(jours.take(10), depart, depart.plusDays(30)))
    }
}
