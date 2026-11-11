package io.github.dash52000.suivinutri.data

import java.time.LocalDate

/**
 * Les réponses d'une journée. Une question absente de [reponses] = pas de réponse (NA),
 * jamais un « non ».
 */
data class EntreeJour(
    val date: LocalDate,
    val reponses: Map<String, String> = emptyMap(),
    val boissons: List<Boisson> = emptyList(),
) {
    val vide: Boolean get() = reponses.isEmpty() && boissons.isEmpty()
    val unitesAlcool: Double get() = boissons.sumOf { it.unites }
}
