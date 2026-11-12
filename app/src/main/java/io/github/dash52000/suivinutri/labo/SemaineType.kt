package io.github.dash52000.suivinutri.labo

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.sqrt

/** Écart moyen de la pesée à la tendance pour un jour de pesée (v1.14). Null sous [SemaineType.MIN_PAR_JOUR]. */
data class JourType(val jour: DayOfWeek, val n: Int, val ecart: Intervalle?)

/** Ce qui se passe un soir donné de la semaine (v1.14) : fréquences parmi les soirs renseignés. */
data class SoirType(
    val jour: DayOfWeek,
    val n: Int,
    val rab: Double?,
    val alcool: Double?,
    val dessert: Double?,
    val beauxParents: Double?,
)

/**
 * D'où vient l'écart de la pesée à la tendance (v1.14) : parts de la somme des carrés, dans l'ordre veille → eau du
 * jour → reste. [ecartTypique] : moyenne quadratique de l'écart, en kg.
 */
data class OrigineEcart(val nbJours: Int, val ecartTypique: Double, val veille: Double, val eau: Double, val reste: Double)

/** Graisse et maigre lissées (corrigées de l'eau) un jour donné, avec leurs erreurs-types (v1.14). */
data class PointComposition(val date: LocalDate, val graisse: Double, val seGraisse: Double, val maigre: Double, val seMaigre: Double)

/**
 * Semaine type (v1.14), sur les [JOURS] derniers jours : les habitudes changent, une moyenne sur un an les
 * mélangerait.
 */
object SemaineType {

    const val JOURS = 84L
    const val MIN_PAR_JOUR = 3

    /**
     * Écart moyen de la pesée à la tendance selon le jour de la pesée (la pesée du lundi reflète le dimanche), avec
     * intervalle à 95 % (Student). Descriptif : ce n'est pas un lien, il n'a pas de niveau de preuve.
     */
    fun balance(poids: List<PointPoids>, aujourdHui: LocalDate): List<JourType> {
        val depuis = aujourdHui.minusDays(JOURS)
        val parJour = poids.filter { it.date.isAfter(depuis) && it.ecart != null }.groupBy { it.date.dayOfWeek }
        return DayOfWeek.values().map { d ->
            val x = parJour[d].orEmpty().map { it.ecart!! }
            val ic = if (x.size < MIN_PAR_JOUR) null else {
                val m = x.average()
                val sd = sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1))
                val h = Stats.t975((x.size - 1).toDouble()) * sd / sqrt(x.size.toDouble())
                Intervalle(m, m - h, m + h)
            }
            JourType(d, x.size, ic)
        }
    }

    /** Fréquences du soir selon le jour : rab (« un peu » compte), alcool, dessert franc, dîner chez les beaux-parents. */
    fun soirs(jours: List<JourLabo>, aujourdHui: LocalDate): List<SoirType> {
        val depuis = aujourdHui.minusDays(JOURS)
        val parJour = jours.filter { it.date.isAfter(depuis) && !it.date.isAfter(aujourdHui) && it.entree?.vide == false }
            .groupBy { it.date.dayOfWeek }
        fun part(l: List<JourLabo>, f: (JourLabo) -> Boolean?): Double? {
            val r = l.mapNotNull(f)
            return if (r.size < MIN_PAR_JOUR) null else r.count { it }.toDouble() / r.size
        }
        return DayOfWeek.values().map { d ->
            val l = parJour[d].orEmpty()
            SoirType(
                jour = d,
                n = l.size,
                rab = part(l) { j -> when (j.entree?.reponses?.get("resservi")) { "oui", "un_peu" -> true; "non" -> false; else -> null } },
                alcool = part(l) { j -> Labo.unitesDuJour(j.entree)?.let { it > 0 } },
                dessert = part(l) { j -> when (j.entree?.reponses?.get("dessert")) { "normale" -> true; "petite", "non", "tupp" -> false; else -> null } },
                beauxParents = part(l) { j -> when (j.entree?.reponses?.get("diner")) { "beaux_parents" -> true; null -> null; else -> false } },
            )
        }
    }
}
