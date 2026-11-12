package io.github.dash52000.suivinutri.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import io.github.dash52000.suivinutri.labo.Cascade
import io.github.dash52000.suivinutri.labo.Intervalle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// La cascade (v1.15) : textes partagés par le Labo et le bilan du dimanche.

private fun marge(x: Double) = "±${nombre(x, 0)}"

/** « Depuis le 8 nov. (10 semaines) : tendance −1,1 kg ; effort mesuré −380 kcal/j (±65). » */
internal fun resumeCascade(c: Cascade, periode: String): String {
    val semaines = (c.jours / 7.0).roundToInt()
    val demi = (c.mesure.haut - c.mesure.bas) / 2
    return "$periode ($semaines semaine${if (semaines > 1) "s" else ""}) : tendance ${signe(c.variationKg.estimation, 1, "kg")} ; " +
        "effort mesuré par la balance ${signe(c.mesure.estimation, 0, "kcal/j")} (${marge(demi)})."
}

/** Une ligne par barre : « Rab −86 kcal/j (±82), ≈ −0,3 kg ». */
internal fun lignesCascade(c: Cascade): List<String> = c.barres.map { b ->
    "${b.libelle} ${signe(b.kcalJour, 0, "kcal/j")} (${marge(b.marge)}), ≈ ${signe(b.kg, 1, "kg")}"
}

/** Ce que dit la barre « non expliqué », marge comprise. */
internal fun texteNonExplique(c: Cascade): String {
    val ne = c.nonExplique
    val demi = (ne.haut - ne.bas) / 2
    val base = "Non expliqué ${signe(ne.estimation, 0, "kcal/j")} (${marge(demi)})"
    return when {
        !c.lisible -> "$base : rien de net en dehors de ce que tu déclares et de ta montre. La marge vient surtout de " +
            "l'habitude d'avant le départ, vue sur peu de jours : elle ne diminuera pas avec le temps."
        ne.estimation < 0 -> "$base : la balance voit environ ${nombre(abs(ne.estimation), 0)} kcal/j d'effort de plus " +
            "que ce que tu déclares (portions plus petites, grignotage en moins, ou coûts de table sous-estimés)."
        else -> "$base : environ ${nombre(ne.estimation, 0)} kcal/j que le questionnaire et la montre ne voient pas " +
            "(grignotage, portions, verres non notés, ou coûts de table surestimés). Une question à ajouter ?"
    }
}

/** Éléments du graphe : l'effort mesuré, chaque barre avec sa marge, le non-expliqué. */
internal fun itemsCascade(c: Cascade): List<ItemForest> =
    listOf(ItemForest("Effort mesuré par la balance", c.mesure, "tendance ${signe(c.variationKg.estimation, 1, "kg")}", net = true)) +
        c.barres.map { b ->
            ItemForest(
                b.libelle,
                Intervalle(b.kcalJour, b.kcalJour - b.marge, b.kcalJour + b.marge),
                "${signe(b.kcalJour, 0, "kcal/j")} (${marge(b.marge)}), ≈ ${signe(b.kg, 1, "kg")} sur la période",
            )
        } +
        ItemForest(
            "Non expliqué", c.nonExplique,
            "${signe(c.nonExplique.estimation, 0, "kcal/j")}, ≈ ${signe(c.nonExpliqueKg, 1, "kg")} sur la période",
            net = c.lisible,
        )

/** Une cascade dans le Labo : résumé, graphe, non-expliqué, barres manquantes. */
@Composable
internal fun CarteCascade(c: Cascade, periode: String, graphe: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(resumeCascade(c, periode), style = MaterialTheme.typography.bodyMedium)
        if (graphe) ForestPlot(itemsCascade(c), "kcal/j", 0)
        else lignesCascade(c).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(texteNonExplique(c), style = MaterialTheme.typography.bodySmall)
        if (c.manquantes.isNotEmpty()) {
            Text(
                "Sans assez de données : ${c.manquantes.joinToString(", ").lowercase(Locale.FRENCH)}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
