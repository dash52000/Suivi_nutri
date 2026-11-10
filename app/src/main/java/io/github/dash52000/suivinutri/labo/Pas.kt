package io.github.dash52000.suivinutri.labo

import java.time.Duration
import java.time.Instant

/** Une tranche de pas telle qu'envoyée par la montre à Health Connect. */
data class TranchePas(val debut: Instant, val fin: Instant, val pas: Long)

/** Une séance de sport, du début à la fin. */
data class Plage(val debut: Instant, val fin: Instant)

/** Taille des tranches de pas reçues un jour donné : dit si la soustraction des séances est propre. */
data class DiagnosticPas(
    val nbTranches: Int,
    val dureeMedianeMin: Double?,
    val dureeMaxMin: Double?,
    val total: Long,
    val horsSport: Long,
    val sources: List<String>,
)

/**
 * Pas hors sport (v1.10) : pas du jour moins les pas faits pendant les séances. Une tranche à cheval sur une
 * séance est coupée au prorata du temps passé dedans ; une tranche instantanée compte entière si elle tombe
 * dans une séance.
 */
object Pas {

    /** Base et cible : médiane des pas hors sport sur les 8 semaines avant le départ, + 2 000. */
    const val SEMAINES_BASE = 8L
    const val MIN_JOURS_BASE = 14
    const val PAS_EN_PLUS = 2000.0

    /** Sous ce total, la montre n'a sans doute pas été portée toute la journée : le jour ne compte pas. */
    const val MIN_PAS_JOUR = 1000L

    fun mediane(x: List<Double>): Double? {
        if (x.isEmpty()) return null
        val t = x.sorted()
        val m = t.size / 2
        return if (t.size % 2 == 1) t[m] else (t[m - 1] + t[m]) / 2
    }

    /** Durée de [t] passée dans l'une des [seances], qui peuvent se chevaucher. */
    private fun recouvrement(t: TranchePas, seances: List<Plage>): Duration {
        // Union des recouvrements, pour ne pas compter deux fois deux séances qui se chevauchent.
        val morceaux = seances
            .mapNotNull { s ->
                val d = maxOf(t.debut, s.debut)
                val f = minOf(t.fin, s.fin)
                if (f.isAfter(d)) d to f else null
            }
            .sortedBy { it.first }
        var total = Duration.ZERO
        var courant: Pair<Instant, Instant>? = null
        morceaux.forEach { m ->
            val c = courant
            courant = when {
                c == null -> m
                !m.first.isAfter(c.second) -> c.first to maxOf(c.second, m.second)
                else -> { total += Duration.between(c.first, c.second); m }
            }
        }
        courant?.let { total += Duration.between(it.first, it.second) }
        return total
    }

    /** Pas de la tranche faits pendant les séances. */
    fun pendantSeances(t: TranchePas, seances: List<Plage>): Double {
        val duree = Duration.between(t.debut, t.fin)
        if (duree.isZero || duree.isNegative) {
            val dedans = seances.any { !t.debut.isBefore(it.debut) && !t.debut.isAfter(it.fin) }
            return if (dedans) t.pas.toDouble() else 0.0
        }
        val part = recouvrement(t, seances).toMillis().toDouble() / duree.toMillis()
        return t.pas * part.coerceIn(0.0, 1.0)
    }

    fun horsSeances(tranches: List<TranchePas>, seances: List<Plage>): Long =
        Math.round(tranches.sumOf { it.pas - pendantSeances(it, seances) })

    fun diagnostic(tranches: List<TranchePas>, seances: List<Plage>, sources: List<String>): DiagnosticPas {
        val durees = tranches.map { Duration.between(it.debut, it.fin).seconds / 60.0 }.sorted()
        return DiagnosticPas(
            nbTranches = tranches.size,
            dureeMedianeMin = mediane(durees),
            dureeMaxMin = durees.maxOrNull(),
            total = tranches.sumOf { it.pas },
            horsSport = horsSeances(tranches, seances),
            sources = sources,
        )
    }
}
