package io.github.dash52000.suivinutri.repas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Fiche SPEC section 9.2 : tirage reproductible à graine fixe ; π toujours > 0 et enregistré ; soir chargé à π = 1 ;
 * environ 10 phrases par semaine. Plus : équilibre sur le jour de semaine, probabilité d'inclusion exacte, allocation de
 * Neyman, calendrier de recalcul.
 */
class TirageTest {

    private val lundi = LocalDate.of(2027, 1, 4) // un lundi
    private val avecHabitude = HabitudeMatin.PHRASES_MIN

    // ---------- Valeurs de départ ----------

    @Test
    fun valeursDeDepart() {
        val p = ProbasTirage()
        assertEquals(1.0, p.pi(Strate(Moment.SOIR, TypeJour.SEMAINE, charge = true), avecHabitude), 0.0)
        assertEquals(1.0, p.pi(Strate(Moment.SOIR, TypeJour.WEEK_END, charge = true), avecHabitude), 0.0)
        assertEquals(0.5, p.pi(Strate(Moment.SOIR, TypeJour.SEMAINE), avecHabitude), 0.0)
        assertEquals(0.6, p.pi(Strate(Moment.MIDI, TypeJour.SEMAINE), avecHabitude), 0.0)
        assertEquals(0.6, p.pi(Strate(Moment.MIDI, TypeJour.WEEK_END), avecHabitude), 0.0)
        assertEquals(0.15, p.pi(Strate(Moment.MATIN, TypeJour.SEMAINE), avecHabitude), 0.0)
        assertEquals(0.4, p.pi(Strate(Moment.GRIGNOTAGE, TypeJour.WEEK_END), avecHabitude), 0.0)
        assertEquals(0.1, p.plancher, 0.0)
    }

    @Test
    fun tableDeDepartCompleteTelleQuElleEstStockee() {
        val table = ProbasTirage().table()
        assertEquals(Strate.TOUTES.toSet(), table.keys)
        val attendu = mapOf(
            "matin|semaine" to 0.15, "matin|weekend" to 0.15,
            "midi|semaine" to 0.6, "midi|weekend" to 0.6,
            "soir|semaine|normal" to 0.5, "soir|weekend|normal" to 0.5,
            "soir|semaine|charge" to 1.0, "soir|weekend|charge" to 1.0,
            "grignotage|semaine" to 0.4, "grignotage|weekend" to 0.4,
        )
        assertEquals(attendu, table.mapKeys { it.key.code })
        for (s in Strate.TOUTES) assertEquals(s.code, ProbasDepart.pi(s), table.getValue(s), 0.0)
    }

    @Test
    fun matinDemandeALaMoitieTantQueLHabitudeNEstPasApprise() {
        val p = ProbasTirage()
        val matin = Strate(Moment.MATIN, TypeJour.SEMAINE)
        assertEquals(0.5, p.pi(matin, 0), 0.0)
        assertEquals(0.5, p.pi(matin, 2), 0.0)
        assertEquals(0.15, p.pi(matin, 3), 0.0)
        assertEquals(0.15, p.pi(matin, 20), 0.0)
    }

    @Test
    fun piToujoursStrictementPositifEtEnregistre() {
        val p = ProbasTirage()
        for (s in Strate.TOUTES) for (n in 0..5) {
            val pi = p.pi(s, n)
            assertTrue("π de ${s.code} = $pi", pi > 0.0 && pi <= 1.0)
        }
        // Une table trop basse est ramenée au plancher ; une table invalide est refusée.
        val basse = ProbasTirage(mapOf(Strate(Moment.MIDI, TypeJour.SEMAINE) to 0.01))
        assertEquals(0.1, basse.pi(Strate(Moment.MIDI, TypeJour.SEMAINE), avecHabitude), 0.0)
        for (mauvais in listOf(0.0, -0.2, 1.2, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { ProbasTirage(mapOf(Strate(Moment.MIDI, TypeJour.SEMAINE) to mauvais)) }
        }
        // Un soir chargé reste à 1 quoi que dise la table.
        val tordue = ProbasTirage(mapOf(Strate(Moment.SOIR, TypeJour.SEMAINE, charge = true) to 0.3))
        assertEquals(1.0, tordue.pi(Strate(Moment.SOIR, TypeJour.SEMAINE, charge = true), avecHabitude), 0.0)
        // La π d'un tirage est conservée dans la ligne à enregistrer.
        val d = Tirage(99).decider(lundi, Moment.MIDI, avecHabitude)
        val ligne = d.versTirage()
        assertEquals(0.6, ligne.pi, 0.0)
        assertEquals(99L, ligne.graine)
        assertEquals(Strate(Moment.MIDI, TypeJour.SEMAINE), ligne.strate)
        assertNull(ligne.reponse)
    }

    // ---------- Reproductibilité ----------

    @Test
    fun memeGraineMemesTirages() {
        val a = Tirage(2026_11_26)
        val b = Tirage(2026_11_26)
        val autre = Tirage(2026_11_27)
        var differences = 0
        for (j in 0 until 365) for (m in Moment.entries) {
            val jour = lundi.plusDays(j.toLong())
            val da = a.decider(jour, m, avecHabitude)
            assertEquals(da, b.decider(jour, m, avecHabitude))
            if (da.demande != autre.decider(jour, m, avecHabitude).demande) differences++
        }
        assertTrue("une autre graine doit donner d'autres tirages ($differences)", differences > 100)
    }

    @Test
    fun uNeChangePasQuandLesTapsChangent() {
        val t = Tirage(5)
        for (j in 0 until 60) {
            val jour = lundi.plusDays(j.toLong())
            val normal = t.decider(jour, Moment.SOIR, avecHabitude)
            val charge = t.decider(jour, Moment.SOIR, avecHabitude, TapsSoir(rab = true))
            assertEquals(normal.u, charge.u, 0.0)
            assertEquals(0.5, normal.pi, 0.0)
            assertEquals(1.0, charge.pi, 0.0)
            assertTrue(charge.demande)
        }
    }

    @Test
    fun soirChargeToujoursDemande() {
        for (graine in listOf(1L, 42L, -7L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            val t = Tirage(graine)
            for (j in 0 until 1_000) {
                val jour = lundi.plusDays(j.toLong())
                for (taps in listOf(TapsSoir(rab = true), TapsSoir(alcool = true), TapsSoir(rab = true, alcool = true))) {
                    val d = t.decider(jour, Moment.SOIR, avecHabitude, taps)
                    assertTrue(d.demande)
                    assertEquals(1.0, d.pi, 0.0)
                    assertTrue(d.strate.charge)
                }
            }
        }
    }

    @Test
    fun soirChargeSelonRabOuAlcoolSeulement() {
        assertFalse(TapsSoir().charge)
        assertFalse(TapsSoir(rab = false, alcool = false).charge)
        assertTrue(TapsSoir(rab = true).charge)
        assertTrue(TapsSoir(alcool = true).charge)
        // Les taps ne comptent que pour le soir : un midi n'est jamais chargé.
        val midi = Tirage(1).decider(lundi, Moment.MIDI, avecHabitude, TapsSoir(rab = true))
        assertFalse(midi.strate.charge)
        assertEquals(0.6, midi.pi, 0.0)
    }

    // ---------- Qualité du tirage ----------

    @Test
    fun probabiliteDInclusionExacteEtMomentsIndependants() {
        val alea = Random(2026)
        val n = 40_000
        val jour = LocalDate.of(2027, 3, 10) // un mercredi
        var midi = 0
        var soir = 0
        var les2 = 0
        repeat(n) {
            val t = Tirage(alea.nextLong())
            val m = t.decider(jour, Moment.MIDI, avecHabitude).demande
            val s = t.decider(jour, Moment.SOIR, avecHabitude).demande
            if (m) midi++
            if (s) soir++
            if (m && s) les2++
        }
        assertEquals(0.6, midi.toDouble() / n, 0.012)
        assertEquals(0.5, soir.toDouble() / n, 0.012)
        assertEquals(0.6 * 0.5, les2.toDouble() / n, 0.012)
    }

    @Test
    fun tiragesEquilibresSurLeJourDeSemaine() {
        // 52 semaines : pour chaque (moment, jour de la semaine), le nombre de moments demandés reste à ±2 de 52 × π.
        var pire = 0.0
        for (k in 1L..40L) {
            val t = Tirage(k * 7919L)
            for (m in Moment.entries) for (dow in 0L..6L) {
                val decisions = (0L until 52L).map { t.decider(lundi.plusDays(dow + 7 * it), m, avecHabitude) }
                val ecart = abs(decisions.count { it.demande } - decisions.sumOf { it.pi })
                if (ecart > pire) pire = ecart
            }
        }
        assertTrue("écart maximal $pire", pire <= 2.0)
    }

    // ---------- Budget : environ 10 phrases par semaine ----------

    /** 53 % des soirs chargés (rab ou alcool : 16 soirs sur 30 dans l'export du 26/11), motif de 15 jours. */
    private fun phrasesParSemaineSimulees(t: Tirage, semaines: Int, partCharges: Int = 8, periode: Int = 15): Double {
        var phrases = 0.0
        for (j in 0 until 7 * semaines) {
            val jour = lundi.plusDays(j.toLong())
            for (m in Moment.entries) {
                val taps = if (m == Moment.SOIR) TapsSoir(rab = j % periode < partCharges) else null
                if (t.decider(jour, m, avecHabitude, taps).demande) phrases += ProbasDepart.tauxPhrase(m)
            }
        }
        return phrases / semaines
    }

    @Test
    fun valeursDeDepartDonnentPresDeDixPhrasesParSemaine() {
        // Soirs chargés 2 sur 7 (hypothèse de la fiche) : 10,6 phrases par semaine.
        val stats = Neyman.statsDepart(2.0 / 7.0)
        val pi = Strate.TOUTES.associateWith { ProbasTirage().pi(it, avecHabitude) }
        assertEquals(10.6, Neyman.phrasesAttendues(pi, stats), 0.05)
        // Soirs chargés 8 sur 15 (export du 26/11) : 11,5, un peu au-dessus du budget, l'allocation le ramène à 10.
        val reel = Neyman.statsDepart(8.0 / 15.0)
        assertEquals(11.5, Neyman.phrasesAttendues(pi, reel), 0.1)
    }

    @Test
    fun tiragesSimulesCorrespondentAL_esperance() {
        val part = 8.0 / 15.0
        val pi = Strate.TOUTES.associateWith { ProbasTirage().pi(it, avecHabitude) }
        val attendu = Neyman.phrasesAttendues(pi, Neyman.statsDepart(part))
        for (graine in listOf(11L, 2027L, 31337L)) {
            val simule = phrasesParSemaineSimulees(Tirage(graine), semaines = 104)
            assertEquals("graine $graine", attendu, simule, 0.6)
        }
    }

    // ---------- Allocation de Neyman ----------

    private fun stats(sigma: Map<Strate, Double?> = emptyMap(), part: Double = 8.0 / 15.0): List<StatStrate> =
        Neyman.statsDepart(part).map { it.copy(ecartType = sigma[it.strate]) }

    @Test
    fun effectifsHebdoRepartissentLesSeptJours() {
        val e = Neyman.effectifsHebdo(0.4)
        assertEquals(28.0, e.values.sum(), 1e-9)
        for (m in Moment.entries) assertEquals(7.0, e.filterKeys { it.moment == m }.values.sum(), 1e-9)
        assertEquals(0.4 * 7, e.filterKeys { it.moment == Moment.SOIR && it.charge }.values.sum(), 1e-9)
        // Vendredi soir, samedi et dimanche : 3 soirs de week-end ; matin, midi, grignotage : 2 jours.
        assertEquals(3.0, e.filterKeys { it.moment == Moment.SOIR && it.typeJour == TypeJour.WEEK_END }.values.sum(), 1e-9)
        assertEquals(2.0, e.getValue(Strate(Moment.MIDI, TypeJour.WEEK_END)), 1e-9)
        assertEquals(5.0, e.getValue(Strate(Moment.MIDI, TypeJour.SEMAINE)), 1e-9)
        assertThrows(IllegalArgumentException::class.java) { Neyman.effectifsHebdo(1.2) }
    }

    @Test
    fun neymanRespecteLeBudgetLesBornesEtLesSoirsCharges() {
        val s = stats(mapOf(
            Strate(Moment.MIDI, TypeJour.SEMAINE) to 80.0,
            Strate(Moment.SOIR, TypeJour.SEMAINE) to 400.0,
            Strate(Moment.SOIR, TypeJour.WEEK_END) to 500.0,
            Strate(Moment.GRIGNOTAGE, TypeJour.SEMAINE) to 250.0,
            Strate(Moment.MATIN, TypeJour.SEMAINE) to 40.0,
        ))
        val pi = Neyman.allouer(s, ProbasDepart.BUDGET_PHRASES_PAR_SEMAINE)
        assertEquals(s.size, pi.size)
        for ((strate, v) in pi) assertTrue("${strate.code} = $v", v >= 0.1 && v <= 1.0)
        for ((strate, v) in pi) if (strate.moment == Moment.SOIR && strate.charge) assertEquals(1.0, v, 0.0)
        assertEquals(10.0, Neyman.phrasesAttendues(pi, s), 1e-6)
    }

    @Test
    fun neymanDonnePlusAuxStratesVariablesEtMoinsAuxCouteuses() {
        val sMidi = Strate(Moment.MIDI, TypeJour.SEMAINE)
        val sSoir = Strate(Moment.SOIR, TypeJour.SEMAINE)
        val s = stats(mapOf(sMidi to 80.0, sSoir to 400.0))
        val pi = Neyman.allouer(s, 10.0)
        assertTrue(pi.getValue(sSoir) > pi.getValue(sMidi))
        // Même écart-type, coût double : π divisée par √2 (hors bornes).
        val egaux = Neyman.statsDepart(0.0).map { it.copy(ecartType = 100.0, cout = 1.0) }
        val cher = egaux.map { if (it.strate == sMidi) it.copy(cout = 2.0) else it }
        val piEgaux = Neyman.allouer(egaux, 10.0)
        val piCher = Neyman.allouer(cher, 10.0)
        assertTrue(piCher.getValue(sMidi) < piEgaux.getValue(sMidi))
        assertTrue(piCher.getValue(sSoir) > piEgaux.getValue(sSoir))
        assertEquals(10.0, Neyman.phrasesAttendues(piCher, cher), 1e-6)
    }

    @Test
    fun neymanNeDependQueDesRapportsDEcartsTypes() {
        val base = mapOf(
            Strate(Moment.MIDI, TypeJour.SEMAINE) to 80.0,
            Strate(Moment.SOIR, TypeJour.SEMAINE) to 400.0,
            Strate(Moment.GRIGNOTAGE, TypeJour.WEEK_END) to 250.0,
        )
        val a = Neyman.allouer(stats(base), 10.0)
        val b = Neyman.allouer(stats(base.mapValues { it.value!! * 37.0 }), 10.0)
        for ((strate, v) in a) assertEquals(strate.code, v, b.getValue(strate), 1e-9)
    }

    @Test
    fun neymanBudgetTropSerreOuTropLarge() {
        val s = stats(emptyMap(), part = 0.0)
        val serre = Neyman.allouer(s, 0.5)
        // Budget trop serré : tout au plancher, sauf les soirs chargés qui restent à 1 (même sans poids).
        for ((strate, v) in serre) assertEquals(strate.code, if (strate.charge) 1.0 else 0.1, v, 0.0)
        val large = Neyman.allouer(s, 100.0)
        assertTrue(large.values.all { it == 1.0 })
        // Écart-type inconnu partout : strates traitées pareil, coût mis à part (grignotage).
        val inconnu = Neyman.allouer(s, 10.0)
        assertEquals(inconnu.getValue(Strate(Moment.MIDI, TypeJour.SEMAINE)), inconnu.getValue(Strate(Moment.SOIR, TypeJour.SEMAINE)), 1e-12)
        assertTrue(inconnu.getValue(Strate(Moment.GRIGNOTAGE, TypeJour.SEMAINE)) < inconnu.getValue(Strate(Moment.MIDI, TypeJour.SEMAINE)))
        assertThrows(IllegalArgumentException::class.java) { Neyman.allouer(s, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { Neyman.allouer(s + s, 10.0) }
    }

    @Test
    fun uneNouvelleTableSeSubstitueSansTouchesAuxAutresStrates() {
        val s = stats(mapOf(Strate(Moment.SOIR, TypeJour.SEMAINE) to 400.0, Strate(Moment.MIDI, TypeJour.SEMAINE) to 80.0))
        val nouvelle = ProbasTirage().avec(Neyman.allouer(s, 10.0))
        assertEquals(Strate.TOUTES.toSet(), nouvelle.table().keys)
        for (strate in Strate.TOUTES) assertTrue(nouvelle.pi(strate, avecHabitude) > 0.0)
        assertEquals(1.0, nouvelle.pi(Strate(Moment.SOIR, TypeJour.WEEK_END, charge = true), avecHabitude), 0.0)
    }

    // ---------- Calendrier de recalcul ----------

    @Test
    fun recalculSeulementUnDimancheAuMoinsQuatreSemainesApresLePremierTirage() {
        val premier = LocalDate.of(2026, 11, 29) // un dimanche
        // Pas de tirage encore : jamais.
        assertFalse(CalendrierRecalcul.autorise(LocalDate.of(2027, 1, 3), null, null))
        // Moins de 4 semaines : non, même un dimanche.
        assertFalse(CalendrierRecalcul.autorise(premier.plusWeeks(3), premier, null))
        // 4 semaines, mais pas un dimanche : non (jamais en cours de semaine).
        for (d in 1L..6L) assertFalse(CalendrierRecalcul.autorise(premier.plusWeeks(4).plusDays(d), premier, null))
        assertTrue(CalendrierRecalcul.autorise(premier.plusWeeks(4), premier, null))
        assertTrue(CalendrierRecalcul.autorise(premier.plusWeeks(9), premier, null))
        // Après un recalcul : de nouveau 4 semaines.
        val recalcule = premier.plusWeeks(4)
        assertFalse(CalendrierRecalcul.autorise(recalcule.plusWeeks(3), premier, recalcule))
        assertTrue(CalendrierRecalcul.autorise(recalcule.plusWeeks(4), premier, recalcule))
        // Un premier tirage en milieu de semaine (lundi 30/11) : le dimanche 27/12 est à 27 jours, trop tôt ; le 03/01 convient.
        val lundiPremier = LocalDate.of(2026, 11, 30)
        assertFalse(CalendrierRecalcul.autorise(LocalDate.of(2026, 12, 27), lundiPremier, null))
        assertTrue(CalendrierRecalcul.autorise(LocalDate.of(2027, 1, 3), lundiPremier, null))
        // Jeudi 26/11 : +28 jours = 24/12, le dimanche 27/12 convient.
        assertTrue(CalendrierRecalcul.autorise(LocalDate.of(2026, 12, 27), LocalDate.of(2026, 11, 26), null))
    }

    // ---------- Part de phrases observée ----------

    @Test
    fun tauxPhraseObserve() {
        fun tirage(i: Int, reponse: Reponse?) =
            TirageMoment(lundi.plusDays(i.toLong()), Moment.GRIGNOTAGE, Strate(Moment.GRIGNOTAGE, TypeJour.SEMAINE), 0.4, 1, reponse)
        val peu = (0 until 9).map { tirage(it, Reponse.PHRASE) }
        assertNull(Neyman.tauxPhraseObserve(peu, Moment.GRIGNOTAGE))
        val assez = (0 until 10).map { tirage(it, if (it < 4) Reponse.PHRASE else Reponse.RIEN) } + tirage(11, null)
        assertEquals(0.4, Neyman.tauxPhraseObserve(assez, Moment.GRIGNOTAGE)!!, 1e-12) // le moment ouvert ne compte pas
        assertNull(Neyman.tauxPhraseObserve(assez, Moment.MIDI))
    }

    @Test
    fun uResteDansZeroUnExclu() {
        val t = Tirage(Long.MIN_VALUE)
        var mini = 1.0
        var maxi = 0.0
        for (j in -2000 until 2000) for (m in Moment.entries) {
            val u = t.u(LocalDate.ofEpochDay(j.toLong()), m)
            assertTrue(u >= 0.0 && u < 1.0)
            if (u < mini) mini = u
            if (u > maxi) maxi = u
        }
        assertTrue(mini < 0.01 && maxi > 0.99)
        assertNotEquals(t.u(lundi, Moment.MIDI), t.u(lundi, Moment.SOIR), 0.0)
        assertTrue(sqrt(2.0) > 1.0) // garde le compilateur honnête sur l'import
    }
}
