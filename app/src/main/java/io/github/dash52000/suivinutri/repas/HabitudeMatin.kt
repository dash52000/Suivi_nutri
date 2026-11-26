package io.github.dash52000.suivinutri.repas

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** Une phrase du matin déjà lue : kcal estimées ĉ, marge σ (log-normale), π du tirage, origine. */
data class PhraseMatin(val jour: LocalDate, val kcal: Double, val sigma: Double, val pi: Double, val origine: Origine)

/**
 * Petit-déj habituel (fiche SPEC section 5) : le champ Matin vide = « comme d'habitude », appris des phrases passées.
 *
 *  - fenêtre glissante de 8 semaines (56 jours, aujourd'hui compris) ;
 *  - appris des seules phrases **tirées** (une phrase spontanée du matin est un écart par construction), pondérées
 *    1/π ; un « Comme d'hab » confirme sans apprendre (il n'entre pas ici) ;
 *  - au moins 3 phrases ; en dessous, pas d'habitude : « Comme d'hab ? » n'est pas proposé et π du matin reste à 0,5 ;
 *  - nouvelle habitude : si les 3 dernières phrases tirées s'écartent **toutes au-delà de 1,5 fois leur marge, dans le même
 *    sens**, de l'habitude apprise avant elles, l'appli repart de ces 3 phrases (et le signale dans le Labo).
 *    Le « même sens » est une précision du 26/11 : trois écarts de sens opposés sont du bruit, pas un changement ;
 *    la simulation 11 l'a vérifié (26/11) : à 1 fois la marge, 17 % de faux changements en 24 semaines ; à 1,5 fois, 7 %.
 *
 * [kcal] = moyenne pondérée des kcal ; [sigma] = marge log-normale de l'habitude (dispersion d'un matin à l'autre
 * et marges des phrases, divisée par le nombre de phrases). Recalculé à chaque ouverture, jamais stocké.
 */
data class HabitudeMatin(
    val kcal: Double,
    val sigma: Double,
    val fenetreDebut: LocalDate,
    val fenetreFin: LocalDate,
    val nPhrases: Int,
    /** Premier jour de la nouvelle habitude si un changement a été détecté dans la fenêtre, sinon null. */
    val changementLe: LocalDate?,
) {
    companion object {
        const val FENETRE_JOURS = 56
        const val PHRASES_MIN = 3

        /** Un écart ne compte que s'il dépasse cette fois la marge de la phrase (1 : 17 % de faux changements en 24 semaines, simulation 11). */
        const val ECART_EN_MARGES = 1.5

        /** Habitude apprise à la date [aujourdhui], ou null avec moins de [PHRASES_MIN] phrases tirées dans la fenêtre. */
        fun apprendre(phrases: List<PhraseMatin>, aujourdhui: LocalDate): HabitudeMatin? {
            val debut = aujourdhui.minusDays((FENETRE_JOURS - 1).toLong())
            val obs = phrases
                .filter { it.origine == Origine.TIREE && it.kcal > 0.0 && it.sigma >= 0.0 && it.pi > 0.0 && it.pi <= 1.0 }
                .filter { !it.jour.isBefore(debut) && !it.jour.isAfter(aujourdhui) }
                .sortedBy { it.jour }
            if (obs.size < PHRASES_MIN) return null

            // Détection d'un changement : à chaque phrase, les 3 dernières face à l'habitude apprise avant elles.
            var depart = 0
            var changement: LocalDate? = null
            for (i in obs.indices) {
                val finBase = i - 3 // dernière phrase de la base (incluse)
                if (finBase - depart + 1 < PHRASES_MIN) continue
                val base = obs.subList(depart, finBase + 1)
                val centre = moyennePonderee(base.map { ln(it.kcal) }, base.map { 1.0 / it.pi })
                val trio = obs.subList(i - 2, i + 1)
                if (ecartDeMemeSens(centre, trio)) {
                    depart = i - 2
                    changement = obs[i - 2].jour
                }
            }

            val utilisees = obs.subList(depart, obs.size)
            val poids = utilisees.map { 1.0 / it.pi }
            val kcal = moyennePonderee(utilisees.map { it.kcal }, poids)
            val logs = utilisees.map { ln(it.kcal) }
            val centreLog = moyennePonderee(logs, poids)
            val sw = poids.sum()
            val sw2 = poids.sumOf { it * it }
            val variance = logs.indices.sumOf { poids[it] * (logs[it] - centreLog) * (logs[it] - centreLog) } / (sw - sw2 / sw)
            val margeMoyenne = utilisees.sumOf { it.sigma * it.sigma } / utilisees.size
            val sigma = sqrt(variance + margeMoyenne / utilisees.size)
            return HabitudeMatin(kcal, sigma, debut, aujourdhui, utilisees.size, changement)
        }

        /** « Comme d'hab ? » n'est proposé que si une habitude existe (3 phrases tirées ou plus dans la fenêtre). */
        fun proposable(habitude: HabitudeMatin?): Boolean = habitude != null

        private fun moyennePonderee(x: List<Double>, w: List<Double>): Double =
            x.indices.sumOf { x[it] * w[it] } / w.sum()

        private fun ecartDeMemeSens(centreLog: Double, trio: List<PhraseMatin>): Boolean {
            val ecarts = trio.map { ln(it.kcal) - centreLog }
            if (!trio.indices.all { abs(ecarts[it]) > ECART_EN_MARGES * trio[it].sigma }) return false
            return ecarts.all { it > 0.0 } || ecarts.all { it < 0.0 }
        }
    }
}
