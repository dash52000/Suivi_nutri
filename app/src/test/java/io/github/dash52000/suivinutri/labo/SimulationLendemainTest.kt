package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.Boisson
import io.github.dash52000.suivinutri.data.EntreeJour
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Random

/**
 * Garde-fou (v1.14) : niveaux de preuve du lendemain simulés de bout en bout avec le vrai code. Semaines réalistes :
 * alcool surtout vendredi et samedi, beaux-parents et longue sortie le dimanche (expositions corrélées), eau qui
 * persiste d'un jour à l'autre (AR(1), φ = 0,5, écart-type 0,5 kg), balance (0,42 kg), niveau qui fluctue
 * (0,05 kg/j).
 *
 * Résultats du 12/11/2026 (60 tirages, a priori 0,15 kg et 0,03 kg par unité d'alcool, Newey-West à 2 jours) :
 * - sans effet réel, « probable » dans 3 à 12 % des cas selon l'exposition et la durée, « solide » dans 0 à 5 % ;
 *   l'effet inverse (« fait baisser ») au même ordre de grandeur ;
 * - avec +0,1 kg par unité d'alcool : « probable » 68 % à 6 semaines, 93 % à 12 ; beaux-parents +0,4 kg : 33 %,
 *   47 %, 77 % à 24 semaines ; rab +0,3 kg : 20 %, 45 %, 80 % ;
 * - séance → rab (séances surtout le dimanche, jour des beaux-parents), sans effet : « probable » 2 à 8 %.
 * Avec a priori 0,3 / 0,1, l'alcool sortait « probable » dans 17 % des cas sans effet ; un retard de Newey-West
 * de 7 jours n'aidait pas.
 */
class SimulationLendemainTest {

    class Effets(val rab: Double = 0.0, val alcoolParUnite: Double = 0.0, val beauxParents: Double = 0.0)

    class Taux(val piste: Double, val probable: Double, val solide: Double, val inverseProbable: Double) {
        override fun toString() = "piste %.2f · probable %.2f · solide %.2f · inverse probable %.2f"
            .format(piste, probable, solide, inverseProbable)
    }

    private val depart = LocalDate.of(2026, 3, 1)

    fun simuler(alea: Random, jours: Int, effets: Effets): List<JourLabo> {
        var niveau = 80.0
        var eau = 0.0
        val effetDemain = DoubleArray(jours + 3)
        return (0 until jours).map { k ->
            val date = depart.plusDays(k.toLong())
            val dow = date.dayOfWeek
            val weekEnd = dow == DayOfWeek.FRIDAY || dow == DayOfWeek.SATURDAY
            val diner = when {
                dow == DayOfWeek.SUNDAY && alea.nextDouble() < 0.8 -> "beaux_parents"
                alea.nextDouble() < (if (weekEnd) 0.3 else 0.15) -> "ailleurs"
                else -> "maison"
            }
            val pRab = if (diner == "beaux_parents") 0.5 else 0.25
            val rab = when {
                alea.nextDouble() >= pRab -> "non"
                alea.nextDouble() < 0.67 -> "oui"
                else -> "un_peu"
            }
            val dessert = when (val u = alea.nextDouble()) {
                in 0.0..0.25 -> "normale"
                in 0.25..0.35 -> "petite"
                else -> if (u < 0.37) "tupp" else "non"
            }
            val boissons = when {
                weekEnd && alea.nextDouble() < 0.7 -> List(2 + alea.nextInt(3)) { Boisson("dubbel", 33.0, 7.0) }
                !weekEnd && alea.nextDouble() < 0.15 -> listOf(Boisson("pils", 25.0, 5.0))
                else -> emptyList()
            }
            val longue = if (dow == DayOfWeek.SUNDAY) alea.nextDouble() < 0.6 else alea.nextDouble() < 0.05
            val reponses = mapOf(
                "diner" to diner, "resservi" to rab, "dessert" to dessert,
                "alcool" to if (boissons.isEmpty()) "aucun" else "oui",
            )
            val entree = EntreeJour(date, reponses, boissons)

            // Pesée du matin : effets des jours précédents, eau, balance.
            eau = 0.5 * eau + 0.5 * kotlin.math.sqrt(0.75) * alea.nextGaussian()
            niveau += 0.05 * alea.nextGaussian()
            val poids = niveau + 0.73 * eau + 0.42 * alea.nextGaussian() + effetDemain[k]

            // Effets de ce jour sur les pesées de J+1 et J+2 (la moitié à J+2).
            val franc = when (rab) { "oui" -> 1.0; "un_peu" -> 0.5; else -> 0.0 }
            val e = effets.rab * franc + effets.alcoolParUnite * entree.unitesAlcool +
                effets.beauxParents * (if (diner == "beaux_parents") 1.0 else 0.0)
            effetDemain[k + 1] += e
            effetDemain[k + 2] += e / 2

            JourLabo(
                date = date, poids = poids, kcal = 2600.0, sommeilMin = 420, entree = entree, heurePesee = 7,
                longueSortie = longue, nbSeances = if (longue) 1 else 0, eauKg = 45.0 + eau,
            )
        }
    }

    fun taux(graine: Long, jours: Int, effets: Effets, libelle: String, tirages: Int = 60): Taux =
        tauxNiveaux(graine, jours, effets, tirages) { r ->
            r.lendemain.first { it.libelle == libelle }.let { e -> if (e.post1 == null) null else e.niveau to e.niveauInverse }
        }

    /** Compensation après le sport, sans effet réel : séances surtout le dimanche, jour des beaux-parents. */
    fun tauxSeanceRab(graine: Long, jours: Int, tirages: Int = 60): Taux =
        tauxNiveaux(graine, jours, Effets(), tirages) { r ->
            r.croises.first { it.exposition == "Un jour de séance" && it.issue == "rab le soir" }
                .let { e -> if (e.ecart == null) null else e.niveau to e.niveauInverse }
        }

    private fun tauxNiveaux(
        graine: Long, jours: Int, effets: Effets, tirages: Int, lire: (ResultatLabo) -> Pair<Niveau, Niveau>?,
    ): Taux {
        val alea = Random(graine)
        var piste = 0; var probable = 0; var solide = 0; var inverse = 0; var n = 0
        repeat(tirages) {
            val donnees = simuler(alea, jours, effets)
            val (niveau, niveauInverse) = lire(Labo.analyser(donnees, donnees.last().date)) ?: return@repeat
            n++
            if (niveau >= Niveau.PISTE) piste++
            if (niveau >= Niveau.PROBABLE) probable++
            if (niveau >= Niveau.SOLIDE) solide++
            if (niveauInverse >= Niveau.PROBABLE) inverse++
        }
        val d = n.coerceAtLeast(1).toDouble()
        return Taux(piste / d, probable / d, solide / d, inverse / d)
    }

    private val libelles = listOf(
        "Soir avec rab", "Dessert pris", "Alcool, par unité", "Dîner chez les beaux-parents", "Longue sortie (1 h 15 et plus)",
    )

    @Test
    fun lendemainSansEffet() {
        libelles.forEachIndexed { i, l ->
            val t = taux(400L + i, 84, Effets(), l, tirages = 40)
            assertTrue("$l : $t", t.probable <= 0.17 && t.solide <= 0.08)
        }
    }

    @Test
    fun lendemainAvecEffet() {
        val effets = Effets(rab = 0.3, alcoolParUnite = 0.1, beauxParents = 0.4)
        val alcool = taux(500L, 84, effets, "Alcool, par unité", tirages = 40)
        assertTrue("alcool : $alcool", alcool.probable >= 0.8)
        val bp = taux(501L, 84, effets, "Dîner chez les beaux-parents", tirages = 40)
        assertTrue("beaux-parents : $bp", bp.piste >= 0.6 && bp.inverseProbable <= 0.05)
    }

    @Test
    fun compensationSansEffet() {
        val t = tauxSeanceRab(600L, 84, tirages = 40)
        assertTrue("séance → rab : $t", t.probable <= 0.15)
    }

    /** Parts moyennes veille / eau / reste sur les 28 derniers jours. */
    fun origine(graine: Long, jours: Int, effets: Effets, tirages: Int = 30): DoubleArray {
        val alea = Random(graine)
        val somme = DoubleArray(4)
        repeat(tirages) {
            val d = simuler(alea, jours, effets)
            val o = Labo.analyser(d, d.last().date).origineEcart ?: return@repeat
            somme[0] += o.veille; somme[1] += o.eau; somme[2] += o.reste; somme[3] += o.ecartTypique
        }
        return DoubleArray(4) { somme[it] / tirages }
    }

    @Test
    fun diagnosticOrigine() {
        if (System.getenv("SIMUL_ORIGINE") == null) return
        for (jours in listOf(42, 84)) {
            println("Origine sans effet, $jours j : ${origine(700L, jours, Effets()).joinToString { "%.2f".format(it) }}")
            println("Origine avec effets, $jours j : ${origine(701L, jours, Effets(0.3, 0.1, 0.4)).joinToString { "%.2f".format(it) }}")
        }
    }

    @Test
    fun diagnostic() {
        if (System.getenv("SIMUL_LENDEMAIN") == null) return
        for (jours in listOf(42, 84, 168)) {
            libelles.forEachIndexed { i, l ->
                println("Sans effet, $jours j, $l : ${taux(100L + i, jours, Effets(), l)}")
            }
            val effets = Effets(rab = 0.3, alcoolParUnite = 0.1, beauxParents = 0.4)
            listOf("Soir avec rab", "Alcool, par unité", "Dîner chez les beaux-parents").forEachIndexed { i, l ->
                println("Avec effet, $jours j, $l : ${taux(200L + i, jours, effets, l)}")
            }
            println("Sans effet, $jours j, séance → rab : ${tauxSeanceRab(300L, jours)}")
        }
    }
}
