package io.github.dash52000.suivinutri.repas

import io.github.dash52000.suivinutri.llm.Schema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/** Codes alignés sur le schéma LLM, strates, invariants des modèles. */
class ModelesTest {

    private val jour = LocalDate.of(2026, 12, 7) // un lundi

    @Test
    fun codesAlignesSurLeSchemaDuLLM() {
        assertEquals(Schema.MOMENTS_SAISIE, Moment.entries.map { it.code })
        assertEquals(Schema.PORTIONS, PortionSource.entries.map { it.code })
        assertEquals(Schema.CONFIANCES, Confiance.entries.map { it.code })
    }

    @Test
    fun codesUniquesEtAllerRetour() {
        for (m in Moment.entries) assertEquals(m, Moment.depuisCode(m.code))
        for (o in Origine.entries) assertEquals(o, Origine.depuisCode(o.code))
        for (r in Reponse.entries) assertEquals(r, Reponse.depuisCode(r.code))
        for (p in PortionSource.entries) assertEquals(p, PortionSource.depuisCode(p.code))
        for (c in Confiance.entries) assertEquals(c, Confiance.depuisCode(c.code))
        for (t in TypeJour.entries) assertEquals(t, TypeJour.depuisCode(t.code))
        assertEquals(Moment.entries.size, Moment.entries.map { it.code }.toSet().size)
        assertEquals(Reponse.entries.size, Reponse.entries.map { it.code }.toSet().size)
        assertThrows(IllegalArgumentException::class.java) { Moment.depuisCode("diner") }
        assertThrows(IllegalArgumentException::class.java) { Reponse.depuisCode("tardif") } // « tardif » est un drapeau, pas une réponse
        assertThrows(IllegalArgumentException::class.java) { Origine.depuisCode("") }
    }

    @Test
    fun weekEndEstVendrediSoirSamediDimanche() {
        val lundi = LocalDate.of(2026, 12, 7)
        val attendu = mapOf(
            DayOfWeek.MONDAY to false, DayOfWeek.TUESDAY to false, DayOfWeek.WEDNESDAY to false, DayOfWeek.THURSDAY to false,
            DayOfWeek.FRIDAY to false, DayOfWeek.SATURDAY to true, DayOfWeek.SUNDAY to true,
        )
        for (d in 0L..6L) {
            val j = lundi.plusDays(d)
            for (m in Moment.entries) {
                val weekEnd = attendu.getValue(j.dayOfWeek) || (j.dayOfWeek == DayOfWeek.FRIDAY && m == Moment.SOIR)
                assertEquals("${j.dayOfWeek} ${m.code}", if (weekEnd) TypeJour.WEEK_END else TypeJour.SEMAINE, TypeJour.de(j, m))
            }
        }
    }

    @Test
    fun dixStratesEtCodesAllerRetour() {
        assertEquals(10, Strate.TOUTES.size)
        assertEquals(10, Strate.TOUTES.map { it.code }.toSet().size)
        for (s in Strate.TOUTES) assertEquals(s, Strate.depuisCode(s.code))
        assertEquals("soir|semaine|charge", Strate(Moment.SOIR, TypeJour.SEMAINE, true).code)
        assertEquals("soir|weekend|normal", Strate(Moment.SOIR, TypeJour.WEEK_END, false).code)
        assertEquals("midi|weekend", Strate(Moment.MIDI, TypeJour.WEEK_END).code)
        for (mauvais in listOf("", "midi", "soir|semaine", "soir|semaine|x", "midi|semaine|charge", "midi|lundi", "nuit|semaine")) {
            assertThrows("« $mauvais »", IllegalArgumentException::class.java) { Strate.depuisCode(mauvais) }
        }
    }

    @Test
    fun seulUnSoirPeutEtreCharge() {
        assertThrows(IllegalArgumentException::class.java) { Strate(Moment.MIDI, TypeJour.SEMAINE, charge = true) }
        assertEquals(false, Strate.de(jour, Moment.MIDI, soirCharge = true).charge) // le tap n'agit que sur le soir
        assertEquals(true, Strate.de(jour, Moment.SOIR, soirCharge = true).charge)
        assertEquals(Strate(Moment.SOIR, TypeJour.WEEK_END, true), Strate.de(LocalDate.of(2026, 12, 11), Moment.SOIR, true)) // vendredi
        assertEquals(Strate(Moment.MIDI, TypeJour.SEMAINE), Strate.de(LocalDate.of(2026, 12, 11), Moment.MIDI, false)) // vendredi midi
    }

    @Test
    fun unTirageRefuseUnPiHorsDeZeroUnEtUneStrateDUnAutreMoment() {
        val s = Strate(Moment.MIDI, TypeJour.SEMAINE)
        TirageMoment(jour, Moment.MIDI, s, 1.0, 1L)
        TirageMoment(jour, Moment.MIDI, s, 1e-6, 1L)
        for (pi in listOf(0.0, -0.1, 1.0000001, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { TirageMoment(jour, Moment.MIDI, s, pi, 1L) }
        }
        assertThrows(IllegalArgumentException::class.java) { TirageMoment(jour, Moment.SOIR, s, 0.5, 1L) }
    }

    @Test
    fun invariantsDeRepasObserve() {
        val d = RepasObserve.decrit(jour, Moment.MIDI, "midi|tupp|boulot", 650.0, 0.25, Origine.TIREE, 0.6)
        assertEquals(Observation.DECRIT, d.observation)
        val n = RepasObserve.nonDecrit(jour, Moment.SOIR, "soir|maison")
        assertTrue(n.kcal == null && n.sigma == null && n.pi == null)
        val r = RepasObserve.rien(jour, Moment.GRIGNOTAGE, "grignotage", 0.4)
        assertEquals(0.0, r.kcal!!, 0.0)
        assertEquals(Origine.TIREE, r.origine)
        val c = RepasObserve.commeDhab(jour, Moment.MATIN, "matin", 310.0, 0.12, 0.15)
        assertEquals(310.0, c.kcal!!, 0.0)
        // Spontanée : décrite sans π.
        assertEquals(null, RepasObserve.decrit(jour, Moment.GRIGNOTAGE, "grignotage", 200.0, 0.4, Origine.SPONTANEE, null).pi)

        assertThrows(IllegalArgumentException::class.java) { RepasObserve.decrit(jour, Moment.MIDI, "x", 0.0, 0.2, Origine.TIREE, 0.6) }
        assertThrows(IllegalArgumentException::class.java) { RepasObserve.decrit(jour, Moment.MIDI, "x", 500.0, -0.1, Origine.TIREE, 0.6) }
        assertThrows(IllegalArgumentException::class.java) { RepasObserve.decrit(jour, Moment.MIDI, "x", 500.0, 0.2, Origine.TIREE, 1.5) }
        assertThrows(IllegalArgumentException::class.java) { RepasObserve(jour, Moment.MIDI, "x", Observation.NON_DECRIT, 100.0, null, null, null) }
        assertThrows(IllegalArgumentException::class.java) { RepasObserve(jour, Moment.MIDI, "x", Observation.RIEN, 50.0, 0.0, Origine.TIREE, 0.5) }
        assertThrows(IllegalArgumentException::class.java) { RepasObserve.commeDhab(jour, Moment.MATIN, "x", 0.0, 0.1, 0.15) }
    }

    @Test
    fun phraseEtExtractionSontDesDonneesPures() {
        val h = Instant.parse("2026-12-07T12:00:00Z")
        val a = PhraseRepas("id", jour, Moment.MIDI, "salade", Origine.TIREE, false, h)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        val e = Extraction("id", "{}", "m", 1, "2020", emptyList(), emptyList(), h)
        assertEquals(e, e.copy())
    }
}
