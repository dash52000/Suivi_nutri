package io.github.dash52000.suivinutri.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Un repas ou un jour du plan du week-end, à partir du vendredi. */
data class CreneauPlan(val cle: String, val libelle: String, val decalage: Long, val midi: Boolean)

/**
 * Plan du week-end, saisi du vendredi au dimanche (v1.9) : repas « normal » ou « dehors », bières prévues par jour.
 * Une clé absente = pas encore prévu.
 */
data class PlanWeekEnd(
    val vendredi: LocalDate,
    val repas: Map<String, String> = emptyMap(),
    val bieres: Map<String, Int> = emptyMap(),
) {
    val vide: Boolean get() = repas.isEmpty() && bieres.isEmpty()

    /** Bières prévues un jour donné, null hors plan ou sans prévision ce jour-là. */
    fun bieresPrevues(date: LocalDate): Int? = PlanWeekEnd.JOURS_BIERES
        .firstOrNull { vendredi.plusDays(it.decalage) == date }
        ?.let { bieres[it.cle] }

    /** Repas prévus dehors un jour donné, null si aucun repas de ce jour n'est prévu. */
    fun repasDehorsPrevus(date: LocalDate): Int? {
        val duJour = REPAS.filter { vendredi.plusDays(it.decalage) == date && repas[it.cle] != null }
        return if (duJour.isEmpty()) null else duJour.count { repas[it.cle] == DEHORS }
    }

    companion object {
        const val NORMAL = "normal"
        const val DEHORS = "dehors"

        val REPAS: List<CreneauPlan> = listOf(
            CreneauPlan("ven_soir", "Ven soir", 0, midi = false),
            CreneauPlan("sam_midi", "Sam midi", 1, midi = true),
            CreneauPlan("sam_soir", "Sam soir", 1, midi = false),
            CreneauPlan("dim_midi", "Dim midi", 2, midi = true),
            CreneauPlan("dim_soir", "Dim soir", 2, midi = false),
        )

        val JOURS_BIERES: List<CreneauPlan> = listOf(
            CreneauPlan("ven", "Ven", 0, midi = false),
            CreneauPlan("sam", "Sam", 1, midi = false),
            CreneauPlan("dim", "Dim", 2, midi = false),
        )

        /** Vendredi du week-end auquel appartient [date] (vendredi, samedi ou dimanche), null les autres jours. */
        fun vendrediDe(date: LocalDate): LocalDate? = when (date.dayOfWeek) {
            DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY ->
                date.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY))
            else -> null
        }
    }
}

/** Tout ce que l'utilisateur a réglé ou choisi pour l'objectif de perte (v1.9). */
data class EtatObjectif(
    /** Date de départ ; les semaines comptent à partir du lundi de cette date. */
    val debut: LocalDate,
    /** Couloir de perte, en kg par semaine (négatif = perte). */
    val couloirBas: Double = COULOIR_BAS,
    val couloirHaut: Double = COULOIR_HAUT,
    /** Budget bière de départ, en équivalent bouteille de 33 cl par semaine. */
    val budgetInitial: Int = BUDGET_INITIAL,
    /** Levier choisi, par semaine (dimanche qui l'ouvre depuis la v1.11 ; libellé du réflexe). */
    val leviers: Map<LocalDate, String> = emptyMap(),
    /** Plans du week-end, par vendredi. */
    val plans: Map<LocalDate, PlanWeekEnd> = emptyMap(),
    val signalActif: Boolean = true,
) {
    companion object {
        const val COULOIR_BAS = -0.40
        const val COULOIR_HAUT = -0.25
        const val BUDGET_INITIAL = 3
    }
}
