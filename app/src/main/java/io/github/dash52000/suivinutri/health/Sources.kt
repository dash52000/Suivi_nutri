package io.github.dash52000.suivinutri.health

import java.time.Instant

/** Sources de mesure du poids et de la composition. */
object Sources {

    /** Balance connectée Withings (appli com.withings.wiscale2). */
    fun estWithings(paquet: String): Boolean = paquet.contains("withings", ignoreCase = true)

    /** Libellé court pour l'export : withings, garmin ou le nom du paquet. */
    fun libelle(paquet: String): String = when {
        estWithings(paquet) -> "withings"
        paquet.contains("garmin", ignoreCase = true) -> "garmin"
        else -> paquet
    }

    /**
     * Mesure retenue pour un jour : d'abord la balance Withings (mesure automatique, heure exacte),
     * sinon une autre source ; à source égale, la plus matinale. Les doublons disparaissent avec.
     */
    fun <T> preferer(mesures: List<T>, paquet: (T) -> String, instant: (T) -> Instant): T? =
        mesures.minWithOrNull(compareBy<T>({ !estWithings(paquet(it)) }, { instant(it) }))
}
