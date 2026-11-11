package io.github.dash52000.suivinutri.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.dash52000.suivinutri.labo.EffetLendemain
import io.github.dash52000.suivinutri.labo.Intervalle
import io.github.dash52000.suivinutri.labo.LigneCalendrier
import io.github.dash52000.suivinutri.labo.SemaineApports
import java.time.LocalDate
import kotlin.math.abs

/** Couleurs des graphes, tirées du thème (clair ou sombre). Jamais de rouge : de l'info, pas un verdict. */
private class Palette(
    val principale: Color,
    val secondaire: Color,
    val attenuee: Color,
    val grille: Color,
)

@Composable
private fun palette() = Palette(
    principale = MaterialTheme.colorScheme.primary,
    secondaire = MaterialTheme.colorScheme.tertiary,
    attenuee = MaterialTheme.colorScheme.onSurfaceVariant,
    grille = MaterialTheme.colorScheme.outlineVariant,
)

/** Couleurs des séries du graphe de décroissance. */
@Composable
private fun couleursSeries(): List<Color> = listOf(
    MaterialTheme.colorScheme.primary,
    MaterialTheme.colorScheme.tertiary,
    MaterialTheme.colorScheme.secondary,
    MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun Legende(texte: String, couleur: Color) {
    Text(texte, style = MaterialTheme.typography.labelSmall, color = couleur)
}

// ---------- Apports estimés et dépense : semaines complètes, puis les 7 derniers jours ----------

@Composable
fun GrapheSemaines(semaines: List<SemaineApports>) {
    val serie = semaines.sortedBy { it.debut }.takeLast(12)
    if (serie.isEmpty()) return
    val p = palette()
    // Échelle sur les estimations et les intervalles des semaines complètes ; celui de la semaine en cours,
    // souvent très large en début de semaine, est coupé aux bords.
    val reference = serie.filter { !it.enCours }.ifEmpty { serie }
    val valeurs = reference.flatMap { listOf(it.depenseMoyenne, it.apports.bas, it.apports.haut) } +
        serie.flatMap { listOf(it.depenseMoyenne, it.apportsEstimes) }
    val min = valeurs.min() - 100
    val max = valeurs.max() + 100

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Legende("● Apports (barre : IC 95 %)", p.principale)
                if (serie.last().enCours) Legende("○ 7 derniers jours", p.principale)
                Legende("● Dépense", p.attenuee)
            }
            Text("${nombre(max, 0)} kcal/j", style = MaterialTheme.typography.labelSmall)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clipToBounds(),
            ) {
                val marge = 10.dp.toPx()
                val pas = if (serie.size > 1) (size.width - 2 * marge) / (serie.size - 1) else 0f
                fun x(i: Int) = if (serie.size > 1) marge + i * pas else size.width / 2
                fun y(v: Double) = (size.height * (max - v) / (max - min)).toFloat()

                // Dépense : une ligne fine.
                val chemin = Path()
                serie.forEachIndexed { i, s ->
                    if (i == 0) chemin.moveTo(x(i), y(s.depenseMoyenne)) else chemin.lineTo(x(i), y(s.depenseMoyenne))
                }
                if (serie.size > 1) drawPath(chemin, p.attenuee, style = Stroke(width = 1.5.dp.toPx()))
                serie.forEachIndexed { i, s -> drawCircle(p.attenuee, 2.5.dp.toPx(), Offset(x(i), y(s.depenseMoyenne))) }

                // Apports : point + intervalle ; semaine en cours en creux.
                serie.forEachIndexed { i, s ->
                    val trait = 2.dp.toPx()
                    val cx = x(i)
                    drawLine(p.principale, Offset(cx, y(s.apports.bas)), Offset(cx, y(s.apports.haut)), strokeWidth = trait)
                    listOf(s.apports.bas, s.apports.haut).forEach { b ->
                        drawLine(p.principale, Offset(cx - 4.dp.toPx(), y(b)), Offset(cx + 4.dp.toPx(), y(b)), strokeWidth = trait)
                    }
                    if (s.enCours) {
                        drawCircle(p.principale, 5.dp.toPx(), Offset(cx, y(s.apportsEstimes)), style = Stroke(2.dp.toPx()))
                    } else {
                        drawCircle(p.principale, 4.5.dp.toPx(), Offset(cx, y(s.apportsEstimes)))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${nombre(min, 0)} kcal/j · sem. du ${serie.first().debut.format(formatCourt)}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    if (serie.last().enCours) "7 derniers jours" else "sem. du ${serie.last().debut.format(formatCourt)}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

// ---------- Calendrier des réflexes ----------

private fun DrawScope.caseCalendrier(score: Double?, coin: Offset, cote: Size, p: Palette) {
    val rayon = CornerRadius(3.dp.toPx())
    when {
        score == null -> drawRoundRect(p.grille, coin, cote, rayon, style = Stroke(width = 1.dp.toPx()))
        score >= 1.0 -> drawRoundRect(p.principale, coin, cote, rayon)
        score > 0.0 -> drawRoundRect(p.principale.copy(alpha = 0.45f), coin, cote, rayon)
        else -> drawRoundRect(p.attenuee.copy(alpha = 0.22f), coin, cote, rayon)
    }
}

@Composable
private fun RangeeCases(scores: List<Double?>, modifier: Modifier, hauteur: Dp) {
    val p = palette()
    Canvas(modifier = modifier.height(hauteur)) {
        val ecart = 3.dp.toPx()
        val largeur = (size.width - ecart * (scores.size - 1)) / scores.size
        scores.forEachIndexed { i, score ->
            caseCalendrier(score, Offset(i * (largeur + ecart), 0f), Size(largeur, size.height), p)
        }
    }
}

@Composable
fun LegendeCalendrier() {
    val p = palette()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Legende("■ tenu", p.principale)
        Legende("■ à moitié", p.principale.copy(alpha = 0.6f))
        Legende("■ pas tenu", p.attenuee.copy(alpha = 0.6f))
        Legende("□ sans réponse", p.attenuee)
    }
}

/** Version compacte (7 jours) : libellé à gauche, cases, puis le décompte « tenu x/y ». */
@Composable
fun CalendrierCompact(lignes: List<LigneCalendrier>, dates: List<LocalDate>) {
    val initiales = mapOf(1 to "L", 2 to "M", 3 to "M", 4 to "J", 5 to "V", 6 to "S", 7 to "D")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("", modifier = Modifier.width(118.dp))
            Row(Modifier.weight(1f)) {
                dates.forEach { d ->
                    Text(
                        initiales.getValue(d.dayOfWeek.value),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text("", modifier = Modifier.width(44.dp))
        }
        lignes.forEach { l ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    l.libelle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(118.dp),
                )
                RangeeCases(l.scores, Modifier.weight(1f), 18.dp)
                Text(
                    "${nombreCourt(l.tenus)}/${l.repondus}",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(44.dp),
                )
            }
        }
        LegendeCalendrier()
    }
}

/** Version longue (labo, 28 jours) : libellé au-dessus de chaque rangée pour garder des cases lisibles. */
@Composable
fun CalendrierLong(lignes: List<LigneCalendrier>, dates: List<LocalDate>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lignes.forEach { l ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row {
                        Text(l.libelle, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(
                            "tenu ${nombreCourt(l.tenus)}/${l.repondus}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    RangeeCases(l.scores, Modifier.fillMaxWidth(), 14.dp)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(dates.first().format(formatCourt), style = MaterialTheme.typography.labelSmall)
                Text(dates.last().format(formatCourt), style = MaterialTheme.typography.labelSmall)
            }
            LegendeCalendrier()
        }
    }
}

// ---------- Forest plot ----------

/**
 * Une ligne de forest plot : un effet et son intervalle, ou null s'il manque des données.
 * [net] : l'effet résiste à la correction pour tests multiples (affiché en couleur).
 */
data class ItemForest(val libelle: String, val intervalle: Intervalle?, val detail: String, val net: Boolean = false)

/**
 * Point = estimation, barre = intervalle de confiance à 95 %, trait vertical = zéro.
 * En couleur : effet retenu après correction de Benjamini-Hochberg. Échelle commune à toutes les lignes.
 */
@Composable
fun ForestPlot(items: List<ItemForest>, unite: String, decimales: Int) {
    val p = palette()
    val bornes = items.mapNotNull { it.intervalle }.flatMap { listOf(abs(it.bas), abs(it.haut), abs(it.estimation)) }
    val etendue = (bornes.maxOrNull() ?: 0.0).let { if (it == 0.0) 1.0 else it * 1.15 }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.libelle, style = MaterialTheme.typography.bodyMedium)
                    val i = item.intervalle
                    if (i != null) {
                        val couleur = if (item.net) p.principale else p.attenuee
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(18.dp),
                        ) {
                            fun x(v: Double) = (size.width / 2 + v / etendue * size.width / 2).toFloat()
                            val cy = size.height / 2
                            val trait = 2.dp.toPx()
                            drawLine(p.grille, Offset(x(0.0), 0f), Offset(x(0.0), size.height), strokeWidth = 1.dp.toPx())
                            drawLine(couleur, Offset(x(i.bas), cy), Offset(x(i.haut), cy), strokeWidth = trait)
                            listOf(i.bas, i.haut).forEach { b ->
                                drawLine(couleur, Offset(x(b), cy - 5.dp.toPx()), Offset(x(b), cy + 5.dp.toPx()), strokeWidth = trait)
                            }
                            drawCircle(couleur, 5.dp.toPx(), Offset(x(i.estimation), cy))
                        }
                    }
                    Text(
                        item.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (items.any { it.intervalle != null }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(signe(-etendue, decimales, unite), style = MaterialTheme.typography.labelSmall)
                    Text("0", style = MaterialTheme.typography.labelSmall)
                    Text(signe(etendue, decimales, unite), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ---------- Décroissance du lendemain ----------

/** Écart moyen de poids à J+1, J+2, J+3 après chaque type de soir : le pic doit redescendre. */
@Composable
fun GrapheDecroissance(effets: List<EffetLendemain>) {
    val series = effets.mapNotNull { e ->
        val j1 = e.j1?.estimation ?: return@mapNotNull null
        val j2 = e.j2 ?: return@mapNotNull null
        val j3 = e.j3 ?: return@mapNotNull null
        e.libelle to listOf(j1, j2, j3)
    }
    if (series.isEmpty()) return
    val couleurs = couleursSeries()
    val grille = palette().grille
    val etendue = series.flatMap { it.second }.maxOf { abs(it) }.let { if (it == 0.0) 0.5 else it * 1.2 }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Écart de poids après le soir, jour par jour", style = MaterialTheme.typography.labelLarge)
            Text(signe(etendue, 1, "kg"), style = MaterialTheme.typography.labelSmall)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            ) {
                val marge = 16.dp.toPx()
                fun x(k: Int) = marge + k * (size.width - 2 * marge) / 2
                fun y(v: Double) = (size.height / 2 - v / etendue * size.height / 2).toFloat()
                drawLine(grille, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1.dp.toPx())
                series.forEachIndexed { s, (_, valeurs) ->
                    val couleur = couleurs[s % couleurs.size]
                    val chemin = Path()
                    valeurs.forEachIndexed { k, v ->
                        if (k == 0) chemin.moveTo(x(k), y(v)) else chemin.lineTo(x(k), y(v))
                        drawCircle(couleur, 3.5.dp.toPx(), Offset(x(k), y(v)))
                    }
                    drawPath(chemin, couleur, style = Stroke(width = 2.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("J+1", style = MaterialTheme.typography.labelSmall)
                Text("J+2", style = MaterialTheme.typography.labelSmall)
                Text("J+3", style = MaterialTheme.typography.labelSmall)
            }
            Text(signe(-etendue, 1, "kg"), style = MaterialTheme.typography.labelSmall)
            series.forEachIndexed { s, (libelle, _) -> Legende("● $libelle", couleurs[s % couleurs.size]) }
        }
    }
}
