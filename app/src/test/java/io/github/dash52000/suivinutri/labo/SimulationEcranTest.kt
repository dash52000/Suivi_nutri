package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.EntreeJour
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Random

/**
 * Garde-fou (v1.17) : effet croisé « midi devant un écran → rab le soir », simulé de bout en bout avec le vrai code.
 *
 * Semaines réalistes, avec un piège : l'écran du midi est surtout un midi de semaine au boulot, alors que le rab est
 * plus fréquent le week-end (beaux-parents le dimanche, dîners dehors, et un peu plus de rab à la maison le vendredi
 * et le samedi). La comparaison se fait à lieu de dîner égal, mais le week-end n'est pas une strate : sans effet réel,
 * l'écran a l'air de protéger du rab. Le test vérifie que ce biais ne fabrique pas de « probable ».
 *
 * Résultats du 15/11/2026 (60 tirages, 90 % de soirs renseignés) :
 * - sans effet réel, « probable » 8 à 10 % (comme les autres liens) ; sens inverse « probable » 7 à 15 %, à cause du
 *   week-end, mais le sens inverse n'est annoncé qu'à « solide » ;
 * - écran → +15 points de rab : « probable » 23 % à 6 semaines, 43 % à 12, 55 % à 24 ;
 * - écran → +25 points : 35 %, 70 %, 92 %.
 */
class SimulationEcranTest {

    class Taux(val probable: Double, val inverseProbable: Double, val n: Int) {
        override fun toString() = "probable %.2f · inverse probable %.2f (n = %d)".format(probable, inverseProbable, n)
    }

    private val depart = LocalDate.of(2026, 3, 1)

    /** [effet] : hausse absolue de la probabilité de rab le soir d'un midi devant un écran. */
    fun simuler(alea: Random, jours: Int, effet: Double, pReponse: Double = 0.9): List<JourLabo> = (0 until jours).map { k ->
        val date = depart.plusDays(k.toLong())
        val dow = date.dayOfWeek
        val weekEnd = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY
        val vendrediSamedi = dow == DayOfWeek.FRIDAY || dow == DayOfWeek.SATURDAY
        val midiLieu = when {
            weekEnd -> if (alea.nextDouble() < 0.3) "exterieur" else "maison"
            else -> if (alea.nextDouble() < 0.15) "exterieur" else "boulot"
        }
        val ecran = when (midiLieu) {
            "boulot" -> alea.nextDouble() < 0.7
            "maison" -> alea.nextDouble() < 0.3
            else -> null
        }
        val diner = when {
            dow == DayOfWeek.SUNDAY && alea.nextDouble() < 0.8 -> "beaux_parents"
            alea.nextDouble() < (if (vendrediSamedi) 0.3 else 0.1) -> "ailleurs"
            else -> "maison"
        }
        val base = when (diner) {
            "beaux_parents" -> 0.5
            "ailleurs" -> 0.35
            else -> if (vendrediSamedi) 0.3 else 0.2
        }
        val pRab = (base + if (ecran == true) effet else 0.0).coerceIn(0.0, 1.0)
        val rab = if (alea.nextDouble() < pRab) "oui" else "non"
        val reponses = mutableMapOf("midi_lieu" to midiLieu, "diner" to diner, "resservi" to rab)
        if (ecran != null) reponses["midi_ecran"] = if (ecran) "oui" else "non"
        // Soirs sans réponse : NA partout.
        val entree = if (alea.nextDouble() < pReponse) EntreeJour(date, reponses) else null
        JourLabo(date = date, poids = 80.0 + 0.4 * alea.nextGaussian(), kcal = 2600.0, sommeilMin = 420, entree = entree, heurePesee = 7)
    }

    fun taux(graine: Long, jours: Int, effet: Double, tirages: Int = 60): Taux {
        val alea = Random(graine)
        var probable = 0; var inverse = 0; var n = 0
        repeat(tirages) {
            val d = simuler(alea, jours, effet)
            val e = Labo.analyser(d, d.last().date).croises
                .first { it.exposition == "Un midi devant un écran" && it.issue == "rab le soir" }
            if (e.ecart == null) return@repeat
            n++
            if (e.niveau >= Niveau.PROBABLE) probable++
            if (e.niveauInverse >= Niveau.PROBABLE) inverse++
        }
        val t = n.coerceAtLeast(1).toDouble()
        return Taux(probable / t, inverse / t, n)
    }

    @Test
    fun sansEffet() {
        val t = taux(800L, 84, 0.0, tirages = 40)
        assertTrue("écran → rab sans effet, 12 semaines : $t", t.probable <= 0.12 && t.inverseProbable <= 0.15)
    }

    @Test
    fun avecEffet() {
        val t = taux(801L, 168, 0.25, tirages = 40)
        assertTrue("écran → rab +25 points, 24 semaines : $t", t.probable >= 0.6)
    }

    @Test
    fun diagnostic() {
        if (System.getenv("SIMUL_ECRAN") == null) return
        for (jours in listOf(42, 84, 168)) {
            println("Sans effet, $jours j : ${taux(100L, jours, 0.0)}")
            println("+0,15, $jours j : ${taux(101L, jours, 0.15)}")
            println("+0,25, $jours j : ${taux(102L, jours, 0.25)}")
        }
    }
}
