package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.EntreeJour
import io.github.dash52000.suivinutri.data.Journal
import io.github.dash52000.suivinutri.health.HealthData
import io.github.dash52000.suivinutri.health.Journee
import java.time.LocalDate

/** Rassemble Health Connect (un an) et le journal du soir, jour par jour. Lève SecurityException sans accès. */
suspend fun collecterJours(
    health: HealthData,
    journal: Journal,
    aujourdHui: LocalDate,
    historiqueJours: Long = 364,
): List<JourLabo> {
    val sante = health.journees(aujourdHui.minusDays(historiqueJours), aujourdHui).associateBy { it.date }
    val entrees = journal.toutes()
    return (sante.keys + entrees.keys).map { d -> versJourLabo(d, sante[d], entrees[d]) }
}

/** Repli sans Health Connect : seulement les réponses du journal. */
fun joursSansSante(entrees: Map<LocalDate, EntreeJour>): List<JourLabo> =
    entrees.map { (d, e) -> JourLabo(d, entree = e) }

fun versJourLabo(date: LocalDate, s: Journee?, e: EntreeJour?): JourLabo = JourLabo(
    date = date,
    poids = s?.poidsKg,
    kcal = s?.kcalTotal,
    sommeilMin = s?.sommeilMin,
    entree = e,
    seanceMidi = s?.activites?.any { it.debut.hour in 10..14 },
    heurePesee = s?.poidsHeure,
    longueSortie = s?.activites?.any { it.endurance && it.dureeMin >= Labo.LONGUE_SORTIE_MIN },
    minutesSeances = s?.activites?.sumOf { it.dureeMin },
    nbSeances = s?.activites?.size,
    pasTotal = s?.pasTotal,
    pasHorsSport = s?.pasHorsSport,
    graissePct = s?.graissePct,
    masseMaigreHcKg = s?.masseMaigreKg,
    eauKg = s?.eauKg,
    osKg = s?.osKg,
    sourcePoids = s?.poidsSource,
)
