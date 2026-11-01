package io.github.dash52000.suivinutri.health

import java.time.Instant

/** Sources de mesure du poids et de la composition. */
object Sources {

    /** Balance connectée Withings (appli com.withings.wiscale2). */
    fun estWithings(paquet: String): Boolean = paquet.contains("withings", ignoreCase = true)

    /** Garmin Connect : seule source retenue pour les séances et le sommeil (sinon doublons Strava, etc.). */
    fun estGarmin(paquet: String): Boolean = paquet.contains("garmin", ignoreCase = true)

    fun <T> garminSeulement(mesures: List<T>, paquet: (T) -> String): List<T> = mesures.filter { estGarmin(paquet(it)) }

    /** Libellé court pour l'export : withings, garmin ou le nom du paquet. */
    fun libelle(paquet: String): String = when {
        estWithings(paquet) -> "withings"
        estGarmin(paquet) -> "garmin"
        else -> paquet
    }

    /**
     * Mesure retenue pour un jour : d'abord la balance Withings (mesure automatique, heure exacte),
     * sinon une autre source ; à source égale, la plus matinale. Les doublons disparaissent avec.
     */
    fun <T> preferer(mesures: List<T>, paquet: (T) -> String, instant: (T) -> Instant): T? =
        mesures.minWithOrNull(compareBy<T>({ !estWithings(paquet(it)) }, { instant(it) }))
}
