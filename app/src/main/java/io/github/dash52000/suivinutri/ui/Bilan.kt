package io.github.dash52000.suivinutri.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.dash52000.suivinutri.data.EntreeJour
import io.github.dash52000.suivinutri.data.QuestionPerso
import io.github.dash52000.suivinutri.labo.Labo
import java.time.LocalDate

/**
 * Bilan des 7 jours qui finissent à [fin] : un simple constat, sans note ni verdict.
 * Les jours sans réponse ne comptent pas.
 */
@Composable
fun BilanSemaine(entrees: Map<LocalDate, EntreeJour>, perso: List<QuestionPerso>, fin: LocalDate) {
    val dates = (6L downTo 0L).map { fin.minusDays(it) }
    val reflexesPerso = perso.map { q -> q.libelle to { e: EntreeJour -> q.score(e.reponses[q.id]) } }
    val lignes = Labo.calendrier(entrees, dates, Labo.reflexes + reflexesPerso)
    val renseignes = dates.count { entrees[it]?.vide == false }
    val joursAlcool = dates.mapNotNull { entrees[it] }.filter { it.reponses["alcool"] != null }
    val unites = joursAlcool.sumOf { it.unitesAlcool }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tes 7 derniers jours", style = MaterialTheme.typography.titleSmall)
            if (renseignes == 0) {
                Text(
                    "Rien de renseigné sur ces 7 jours pour l'instant.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Text(
                buildString {
                    append("Jours renseignés : $renseignes/7")
                    if (joursAlcool.isNotEmpty()) {
                        append(" · alcool : ${nombre(unites, 1)} unité(s) sur ${joursAlcool.size} jour(s) renseigné(s)")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (lignes.isNotEmpty()) CalendrierCompact(lignes, dates)
        }
    }
}
