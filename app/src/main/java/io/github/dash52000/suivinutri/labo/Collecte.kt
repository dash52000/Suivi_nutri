package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.EntreeJour
import io.github.dash52000.suivinutri.data.Journal
import io.github.dash52000.suivinutri.health.Activite
import io.github.dash52000.suivinutri.health.HealthData
import io.github.dash52000.suivinutri.health.Journee
import java.time.LocalDate

/** Rassemble Health Connect (un an) et le journal du soir, jour par jour. Lève SecurityException sans accès. */
suspend fun collecterJours(
    health: HealthData,
    journal: Journal,
    aujourdHui: LocalDate,
    historiqueJours: Long = 364,
    /** Premier jour où lire les pas (v1.11 : seulement la période utile au Cap) ; null = toute la période. */
    pasDepuis: LocalDate? = null,
): List<JourLabo> {
    val sante = health.journees(aujourdHui.minusDays(historiqueJours), aujourdHui, pasDepuis).associateBy { it.date }
    val entrees = journal.toutes()
    return (sante.keys + entrees.keys).map { d -> versJourLabo(d, sante[d], entrees[d]) }
}

/** Repli sans Health Connect : seulement les réponses du journal. */
fun joursSansSante(entrees: Map<LocalDate, EntreeJour>): List<JourLabo> =
    entrees.map { (d, e) -> JourLabo(d, entree = e) }

/** Les marches enregistrées sur la montre ne sont pas des séances (v1.11) : elles comptent dans les pas hors sport. */
fun versJourLabo(date: LocalDate, s: Journee?, e: EntreeJour?): JourLabo = versJourLabo(date, s, e, s?.activites?.filter { !it.marche })

private fun versJourLabo(date: LocalDate, s: Journee?, e: EntreeJour?, seances: List<Activite>?): JourLabo = JourLabo(
    date = date,
    poids = s?.poidsKg,
    kcal = s?.kcalTotal,
    sommeilMin = s?.sommeilMin,
    entree = e,
    seanceMidi = seances?.any { it.debut.hour in 10..14 },
    heurePesee = s?.poidsHeure,
    longueSortie = seances?.any { it.endurance && it.dureeMin >= Labo.LONGUE_SORTIE_MIN },
    minutesSeances = seances?.sumOf { it.dureeMin },
    nbSeances = seances?.size,
    nbRenfos = seances?.count { it.type == "Renfo" },
    pasTotal = s?.pasTotal,
    pasHorsSport = s?.pasHorsSport,
    graissePct = s?.graissePct,
    masseMaigreHcKg = s?.masseMaigreKg,
    eauKg = s?.eauKg,
    osKg = s?.osKg,
    sourcePoids = s?.poidsSource,
)
