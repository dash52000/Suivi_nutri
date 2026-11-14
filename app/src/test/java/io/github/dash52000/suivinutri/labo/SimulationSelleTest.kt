package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.EntreeJour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Garde-fou (v1.16) : correction de la selle simulée de bout en bout avec le vrai code (Labo.modeleSelle,
 * Labo.lissage). Pesées : niveau qui fluctue (0,05 kg/j), eau AR(1) (non mesurée ici), balance (0,42 kg), soirs
 * réalistes (beaux-parents le dimanche, rab, dessert), et la selle du matin.
 *
 * Mesures : erreur de la tendance face au vrai niveau (pesée « avant la selle »), avec et sans correction ; écart
 * après / avant estimé ; niveaux de preuve sans effet réel.
 *
 * Résultats du 14/11/2026 (40 tirages, 12 semaines ; vrais effets : après −0,20 à −0,25 kg, −0,10 de plus après un
 * soir copieux, sans selle +0,10) :
 * - habitude de Damien (5 % de matins après, 15 % sans selle) : erreur de la tendance 0,135 kg avec ou sans
 *   correction. Rien à gagner, rien de dégradé ; l'écart après / avant reste à l'a priori (−0,15) et l'effet croisé
 *   n'est jamais lisible ;
 * - sans aucun effet réel : correction de −0,12 kg sur les rares matins après, sans dégât (0,135 contre 0,133) ;
 *   aucun lien « probable » sur le croisé, 0 % sans selle ;
 * - 40 % de matins après : erreur 0,137 contre 0,160 kg (0,130 contre 0,173 à 24 semaines) ; écart estimé −0,20
 *   pour −0,25 (tiré vers l'a priori), « probable » 80 % à 12 semaines, 93 % à 24 ; effet croisé de −0,1 kg
 *   « probable » 3 % à 12 semaines, 10 % à 24 : trop petit pour être vu ; sans selle +0,1 : 10 % puis 17 % ;
 * - faux positifs : croisé « probable » 3 % sans effet, sens inverse 0 %.
 */
class SimulationSelleTest {

    class Scenario(
        /** Part des matins après la selle, sans selle ; le reste avant. */
        val pApres: Double,
        val pSans: Double,
        /** Vrais effets sur la pesée, en kg. */
        val apres: Double,
        val apresCopieuxEnPlus: Double,
        val sans: Double,
    )

    private val depart = LocalDate.of(2026, 3, 1)

    fun simuler(alea: Random, jours: Int, s: Scenario): Pair<List<JourLabo>, DoubleArray> {
        var niveau = 80.0
        var eau = 0.0
        val vrai = DoubleArray(jours)
        var copieuxHier = false
        val liste = (0 until jours).map { k ->
            val date = depart.plusDays(k.toLong())
            val dow = date.dayOfWeek
            val diner = if (dow == DayOfWeek.SUNDAY && alea.nextDouble() < 0.8) "beaux_parents" else "maison"
            val rab = if (alea.nextDouble() < (if (diner == "beaux_parents") 0.5 else 0.2)) "oui" else "non"
            val dessert = if (alea.nextDouble() < 0.2) "normale" else "non"
            val u = alea.nextDouble()
            val selle = when {
                u < s.pApres -> "apres"
                u < s.pApres + s.pSans -> "sans"
                else -> "avant"
            }
            val entree = EntreeJour(date, mapOf("diner" to diner, "resservi" to rab, "dessert" to dessert, "selle" to selle))

            eau = 0.5 * eau + 0.5 * sqrt(0.75) * alea.nextGaussian()
            niveau += 0.05 * alea.nextGaussian()
            vrai[k] = niveau
            val effetSelle = when (selle) {
                "apres" -> s.apres + if (copieuxHier) s.apresCopieuxEnPlus else 0.0
                "sans" -> s.sans
                else -> 0.0
            }
            val poids = niveau + 0.73 * eau + 0.42 * alea.nextGaussian() + effetSelle
            copieuxHier = Selle.copieux(entree) == true
            JourLabo(date, poids = poids, entree = entree, heurePesee = 7)
        }
        return liste to vrai
    }

    class Bilan(
        val erreurAvec: Double,
        val erreurSans: Double,
        val ecartApres: Double,
        val sdEcartApres: Double,
        val apresProbable: Double,
        val croiseProbable: Double,
        val croiseInverseProbable: Double,
        val sansProbable: Double,
    ) {
        override fun toString() = ("erreur tendance avec %.3f / sans %.3f kg · écart après estimé %.3f (± %.3f) · " +
            "après probable %.2f · croisé probable %.2f (inverse %.2f) · sans selle probable %.2f")
            .format(erreurAvec, erreurSans, ecartApres, sdEcartApres, apresProbable, croiseProbable, croiseInverseProbable, sansProbable)
    }

    fun evaluer(s: Scenario, jours: Int, tirages: Int, graine: Long = 1): Bilan {
        val alea = Random(graine)
        var eA = 0.0; var eS = 0.0; val ecarts = mutableListOf<Double>()
        var ap = 0; var cr = 0; var crInv = 0; var sa = 0
        repeat(tirages) {
            val (j, vrai) = simuler(alea, jours, s)
            val fin = j.last().date
            val modele = Labo.modeleSelle(j, fin, emptyMap())
            val avec = Labo.lissage(j, fin, emptyMap(), Labo.correctionsSelle(j, modele))!!
            val sans = Labo.lissage(j, fin, emptyMap(), emptyMap())!!
            // Erreur moyenne de la tendance sur les 4 dernières semaines (ce que lit le Cap).
            val idx = (jours - 28 until jours)
            eA += idx.sumOf { abs(avec.niveau(it) - vrai[it]) } / idx.count()
            eS += idx.sumOf { abs(sans.niveau(it) - vrai[it]) } / idx.count()
            ecarts += modele.ecartApres(false)
            val liens = Liens.selle(modele).associateBy { it.cle }
            if ((liens["selle:apres"]?.niveau ?: Niveau.AUCUN) >= Niveau.PROBABLE) ap++
            if ((liens["selle:croise"]?.niveau ?: Niveau.AUCUN) >= Niveau.PROBABLE) cr++
            if ((liens["selle:croise:inverse"]?.niveau ?: Niveau.AUCUN) >= Niveau.PROBABLE) crInv++
            if ((liens["selle:sans"]?.niveau ?: Niveau.AUCUN) >= Niveau.PROBABLE) sa++
        }
        val m = ecarts.average()
        return Bilan(
            eA / tirages, eS / tirages, m, sqrt(ecarts.sumOf { (it - m) * (it - m) } / ecarts.size),
            ap.toDouble() / tirages, cr.toDouble() / tirages, crInv.toDouble() / tirages, sa.toDouble() / tirages,
        )
    }

    /** Toujours « avant » : aucune correction, la tendance est exactement celle d'avant la v1.16. */
    @Test
    fun toujoursAvantNeChangeRien() {
        val (j, _) = simuler(Random(3), 84, Scenario(0.0, 0.0, -0.2, 0.0, 0.0))
        val fin = j.last().date
        assertTrue(Labo.correctionsSelle(j, Labo.modeleSelle(j, fin, emptyMap())).isEmpty())
        val a = Labo.lissage(j, fin, emptyMap())!!
        val b = Labo.lissage(j, fin, emptyMap(), emptyMap())!!
        (0 until a.n).forEach { assertEquals(a.niveau(it), b.niveau(it), 1e-12) }
    }

    /** Soir copieux déduit des réponses : rab, dessert ou beaux-parents ; NA si on ne sait pas. */
    @Test
    fun copieuxDeduit() {
        fun e(vararg r: Pair<String, String>) = EntreeJour(depart, r.toMap())
        assertEquals(true, Selle.copieux(e("resservi" to "un_peu")))
        assertEquals(true, Selle.copieux(e("dessert" to "petite")))
        assertEquals(true, Selle.copieux(e("diner" to "beaux_parents", "resservi" to "non", "dessert" to "non")))
        assertEquals(false, Selle.copieux(e("diner" to "maison", "resservi" to "non", "dessert" to "tupp")))
        assertEquals(null, Selle.copieux(e("resservi" to "non")))
        assertEquals(null, Selle.copieux(null))
    }

    /** Sans donnée, la correction est l'a priori : −0,15 kg pour un matin après, 0 sans selle. */
    @Test
    fun aPriori() {
        val m = Selle.A_PRIORI
        assertEquals(Selle.PRIOR_APRES, m.correction(Selle.Etat.APRES, false)!!.kg, 1e-9)
        assertEquals(0.0, m.correction(Selle.Etat.SANS, null)!!.kg, 1e-9)
        assertTrue(m.correction(Selle.Etat.AVANT, true) == null)
        assertTrue(Liens.selle(m).isEmpty())
    }

    /** Habitude de Damien : presque toujours avant ; la correction ne doit rien dégrader. */
    @Test
    fun habitudeDamien() {
        val vrai = evaluer(Scenario(0.05, 0.15, -0.2, -0.1, 0.1), 84, 40)
        val nul = evaluer(Scenario(0.05, 0.15, 0.0, 0.0, 0.0), 84, 40)
        println("Habitude, effets réels (−0,2 / −0,1 / +0,1), 12 semaines : $vrai")
        println("Habitude, aucun effet, 12 semaines : $nul")
        assertTrue(vrai.erreurAvec <= vrai.erreurSans * 1.02)
        assertTrue(nul.erreurAvec <= nul.erreurSans * 1.05)
        assertTrue(nul.croiseProbable <= 0.15 && nul.sansProbable <= 0.15)
    }

    /** Si Damien se pèse souvent après : l'écart se cale sur les données et la tendance y gagne. */
    @Test
    fun souventApres() {
        val vrai = evaluer(Scenario(0.4, 0.1, -0.25, -0.1, 0.1), 84, 40)
        val nul = evaluer(Scenario(0.4, 0.1, -0.25, 0.0, 0.0), 84, 40)
        val long = evaluer(Scenario(0.4, 0.1, -0.25, -0.1, 0.1), 168, 30)
        println("Souvent après, effets réels, 12 semaines : $vrai")
        println("Souvent après, sans croisé ni sans selle, 12 semaines : $nul")
        println("Souvent après, effets réels, 24 semaines : $long")
        assertTrue(vrai.erreurAvec < vrai.erreurSans)
        assertTrue(abs(vrai.ecartApres - -0.25) < 0.08)
        assertTrue(vrai.apresProbable >= 0.7)
        assertTrue(nul.croiseProbable <= 0.15 && nul.croiseInverseProbable <= 0.15 && nul.sansProbable <= 0.15)
    }
}
