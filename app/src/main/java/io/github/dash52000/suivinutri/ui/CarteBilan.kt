package io.github.dash52000.suivinutri.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.dash52000.suivinutri.data.EntreeJour
import io.github.dash52000.suivinutri.data.EtatObjectif
import io.github.dash52000.suivinutri.labo.BilanDimanche
import io.github.dash52000.suivinutri.labo.Cap
import io.github.dash52000.suivinutri.labo.JourLabo
import io.github.dash52000.suivinutri.labo.ResultatBilan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Bilan du dimanche (v1.12), en haut de l'onglet Cap : le dernier, et les précédents en reculant semaine par semaine.
 * Les propositions ne se valident que sur le bilan de la semaine en cours.
 */
@Composable
internal fun SectionBilan(
    jours: List<JourLabo>,
    etat: EtatObjectif,
    reflexes: List<Pair<String, (EntreeJour) -> Double?>>,
    enPause: Set<String>,
    semaineDepart: LocalDate,
    aujourdHui: LocalDate,
    onVu: (LocalDate) -> Unit,
    onErreur: (String) -> Unit,
    onDecisionBudget: (dimanche: LocalDate, ecart: Int, acceptee: Boolean) -> Unit,
    onChoisirLevier: (dimanche: LocalDate, libelle: String, proposes: List<String>) -> Unit,
) {
    val courant = Cap.debutSemaine(aujourdHui)
    val premier = semaineDepart.plusWeeks(1)
    if (courant.isBefore(premier)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Bilan du dimanche", style = MaterialTheme.typography.titleMedium)
                Texte("Premier bilan le dimanche ${premier.format(formatCourt)}, après ta première semaine.")
            }
        }
        return
    }
    var dimanche by remember { mutableStateOf(courant) }
    var bilan by remember { mutableStateOf<ResultatBilan?>(null) }
    var erreur by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(jours, etat, dimanche) {
        bilan = null
        erreur = null
        val r = withContext(Dispatchers.Default) {
            runCatching { BilanDimanche.calculer(jours, dimanche, etat, reflexes, enPause) }
        }
        // Une erreur de calcul s'affiche et part dans le journal d'usage, au lieu d'un chargement sans fin.
        r.onSuccess { bilan = it }.onFailure { e -> erreur = e.message ?: e.javaClass.simpleName; onErreur(erreur!!) }
        onVu(dimanche)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { dimanche = dimanche.minusWeeks(1) }, enabled = dimanche.isAfter(premier)) { Text("‹") }
                Text(
                    "Bilan du dimanche ${dimanche.format(formatCourt)}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { dimanche = dimanche.plusWeeks(1) }, enabled = dimanche.isBefore(courant)) { Text("›") }
            }
            val b = bilan
            if (b == null) {
                erreur?.let {
                    Text("Bilan impossible à calculer : $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } ?: CircularProgressIndicator()
                return@Column
            }
            ContenuBilan(b, etat, modifiable = b.dimanche == courant, onDecisionBudget, onChoisirLevier)
        }
    }
}

@Composable
private fun Bloc(titre: String, contenu: @Composable () -> Unit) {
    HorizontalDivider()
    Text(titre, style = MaterialTheme.typography.titleSmall)
    contenu()
}

@Composable
private fun Ligne(t: String) = Text(t, style = MaterialTheme.typography.bodyMedium)

@Composable
private fun Texte(t: String) =
    Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun jourCourt(d: LocalDate): String =
    d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.FRENCH).removeSuffix(".") + " " + d.dayOfMonth

private fun nomJour(d: java.time.DayOfWeek): String = d.getDisplayName(TextStyle.FULL, Locale.FRENCH)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContenuBilan(
    b: ResultatBilan,
    etat: EtatObjectif,
    modifiable: Boolean,
    onDecisionBudget: (LocalDate, Int, Boolean) -> Unit,
    onChoisirLevier: (LocalDate, String, List<String>) -> Unit,
) {
    // 1. Le cap.
    Bloc("Le cap") {
        val s = b.semaine
        if (s == null) {
            Ligne("Pas de semaine finie à lire.")
        } else {
            val e = s.effort
            Ligne(
                (if (e == null) "Effort : pas assez de pesées." else
                    "Effort des 2 dernières semaines : ${signe(e.estimation, 0, "kcal/j")} " +
                        "(${ic(e, 0, "kcal/j").removePrefix("IC 95 % : ")})") + " · ${s.verdict.libelle}."
            )
        }
        val ecart = b.ecartDepart
        if (ecart != null && b.attenduHaut != null && b.attenduBas != null) {
            Texte(
                "Depuis le départ (${b.semainesDepuisDepart} semaine(s)) : tendance ${signe(ecart, 1, "kg")} ; attendu à " +
                    "un effort dans le couloir : entre ${signe(b.attenduHaut, 1, "kg")} et ${signe(b.attenduBas, 1, "kg")}."
            )
        }
    }

    // 2. Ta balance cette semaine.
    Bloc("Ta balance cette semaine") {
        val sortent = b.balance.filter { it.sortDuLot }
        val lues = b.balance.count { it.ecart != null }
        if (lues == 0) Ligne("Pas de pesée retenue cette semaine.")
        else if (sortent.isEmpty()) Ligne("$lues pesée(s), toutes à moins de ${nombre(BilanDimanche.SEUIL_JOUR, 1)} kg de la tendance : rien à expliquer.")
        sortent.forEach { j ->
            val eau = j.partEau?.let { ", dont ${signe(it, 1, "kg")} d'eau" } ?: ""
            Ligne("${jourCourt(j.date)} : ${signe(j.ecart!!, 1, "kg")}$eau")
            Texte(if (j.causes.isEmpty()) "Rien de noté la veille : sel, glycogène ou bruit de la balance." else j.causes.joinToString(" · "))
        }
    }

    // 3. Graisse et maigre sur 4 semaines.
    Bloc("Graisse et maigre, 4 semaines") {
        val g = b.graisse4
        val m = b.maigre4
        if (g == null || m == null) {
            Ligne("Pas assez de mesures de composition sur 4 semaines.")
        } else {
            Ligne("Graisse ${signe(g.estimation, 1, "kg")} (${ic(g, 1, "kg").removePrefix("IC 95 % : ")})")
            Ligne("Maigre ${signe(m.estimation, 1, "kg")} (${ic(m, 1, "kg").removePrefix("IC 95 % : ")})")
            Texte(
                "Le maigre de la balance mêle eau, glycogène et muscle" +
                    if (b.semainesDepuisDepart < 8) " : trop tôt pour le lire (il baisse d'abord avec le glycogène)." else "."
            )
        }
    }

    // 4. Protection du muscle.
    Bloc("Protection du muscle") {
        Ligne("Renfo : ${b.renfos} séance(s) cette semaine.")
        Ligne(
            b.proteinesGkg?.let { "Protéines : ${nombre(it, 1)} g/kg/j sur ${b.joursProteines} jour(s) (laitages et légumineuses non comptés)." }
                ?: "Protéines : pas assez de jours renseignés."
        )
        Texte(
            if (b.rythmeTropRapide) "Tu perds plus vite que le couloir : un déficit plus fort fait perdre plus de maigre."
            else "Repère : jusqu'à environ 1,9 g/kg/j associé à moins de perte de maigre en déficit (pratiquants de musculation)."
        )
    }

    // 5. Effort déclaré, puis la cascade dès 4 semaines (v1.15).
    val cascade = b.cascade
    if (cascade != null) Bloc("D'où vient ta perte") {
        Ligne(resumeCascade(cascade, "Depuis le départ"))
        lignesCascade(cascade).forEach { Texte(it) }
        Texte(texteNonExplique(cascade))
        if (cascade.manquantes.isNotEmpty()) {
            Texte("Sans assez de données : ${cascade.manquantes.joinToString(", ").lowercase(Locale.FRENCH)}.")
        }
    } else Bloc("Effort déclaré") {
        val d = b.effortDeclare
        if (d == null) {
            Ligne("Pas assez de réponses, ou pas de référence avant le départ.")
        } else {
            Ligne("${signe(d.total, 0, "kcal/j")} par rapport à tes habitudes d'avant le départ.")
            Texte(d.composantes.joinToString(" · ") { "${it.libelle} ${signe(it.kcalJour, 0, "")}".trimEnd() } +
                (if (d.manquantes.isNotEmpty()) " · sans : ${d.manquantes.joinToString(", ").lowercase(Locale.FRENCH)}" else ""))
            Texte("Coûts de table (un rab ≈ 300 kcal, une part de dessert ≈ 250, alcool ≈ 70 kcal par unité, plus les glucides) : un ordre de grandeur.")
            Texte("La cascade, qui compare à ce que mesure la balance, arrive 4 semaines après le départ.")
        }
    }

    // 6. Ce qu'on a appris.
    Bloc("Ce qu'on a appris") {
        if (b.appris.isEmpty()) {
            Ligne("Rien de nouveau au niveau « probable » ou « solide » cette semaine. Les pistes sont dans le Labo.")
        }
        b.appris.forEach { c ->
            val l = c.lien
            Ligne(
                if (c.apres > c.avant) "${l.phrase} : ${c.apres.libelle} (${c.avant.libelle} la semaine dernière)."
                else "${l.phrase} : redescendu à ${c.apres.libelle}."
            )
            Texte("${l.chiffre} · probabilité : ${pourcent(l.prob)}")
        }
    }

    // 7. À valider pour la semaine qui vient.
    Bloc("Pour la semaine qui vient") {
        b.bilanLevier?.let { l ->
            Ligne("Levier de la semaine écoulée : ${l.libelle}")
            Texte(
                buildString {
                    if (l.avant != null && l.apres != null) append("Tenu ${pourcent(l.apres)} contre ${pourcent(l.avant)} les 2 semaines d'avant")
                    else append("Pas assez de réponses pour comparer")
                    if (l.variationTemoins != null && l.effet != null) {
                        append(" ; les ${l.nbTemoins} leviers proposés mais pas choisis ont bougé de ${signe(l.variationTemoins * 100, 0, "pts")}")
                        append(" : effet propre ${signe(l.effet * 100, 0, "pts")}.")
                    } else {
                        append(" ; pas de témoin pour corriger la régression vers la moyenne.")
                    }
                }
            )
        }

        Text("Levier", style = MaterialTheme.typography.labelLarge)
        val choisi = etat.leviers[b.dimanche]
        if (b.propositionsLevier.isEmpty()) Texte("Pas encore assez de réponses pour proposer un levier.")
        b.propositionsLevier.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = choisi == p.libelle,
                    enabled = modifiable,
                    onClick = { onChoisirLevier(b.dimanche, p.libelle, b.propositionsLevier.map { it.libelle }) },
                    label = { Text(p.libelle) },
                )
            }
            Texte(
                buildString {
                    p.adherence?.let { append("tenu ${pourcent(it)} des fois sur 14 jours") }
                    if (p.gainKcalSemaine != null && p.gainKgMois != null && p.gainKcalSemaine > 0) {
                        append(" · tenu à chaque fois : ≈ ${nombre(p.gainKcalSemaine, 0)} kcal/semaine, ${signe(p.gainKgMois, 1, "kg")} en un mois")
                        p.preuve?.let { append(" (lien ${it.libelle})") }
                    } else {
                        append(" · gain pas encore chiffrable")
                    }
                }
            )
        }

        Text("Budget bière", style = MaterialTheme.typography.labelLarge)
        val decision = etat.decisionsBudget[b.dimanche]
        val p = b.propositionBudget
        when {
            decision != null -> Ligne(
                "Budget ${etat.budget(b.dimanche)} cette semaine · proposition ${if (decision.ecart < 0) "−1" else "+1"} " +
                    if (decision.acceptee) "acceptée." else "pas retenue."
            )
            p != null -> {
                Ligne(
                    if (p < 0) "Proposition : une bière de moins (${b.budget} → ${b.budget - 1}) après 2 semaines au-dessus du couloir."
                    else "Proposition : une bière de plus (${b.budget} → ${b.budget + 1}) après 2 semaines dans le couloir."
                )
                if (modifiable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onDecisionBudget(b.dimanche, p, true) }) { Text("Accepter") }
                        OutlinedButton(onClick = { onDecisionBudget(b.dimanche, p, false) }) { Text("Garder comme avant") }
                    }
                }
            }
            else -> Ligne("Budget ${b.budget} cette semaine, pas de changement proposé.")
        }

        val m = b.soirARisque
        Ligne(
            "Soir à surveiller : ${nomJour(m.jour)}" +
                if (m.parDefaut) " (par défaut, pour le plan du week-end)." else " (${m.derapages} sur ${m.soirs} ces 8 dernières semaines)."
        )
        b.bilanPlan?.let { pl ->
            Texte(
                "Plan du week-end : ${pl.bieresPrevues} bière(s) prévue(s), " +
                    (pl.bieresBues?.let { "${nombreCourt(Math.round(it * 10) / 10.0)} bue(s)" } ?: "alcool pas renseigné") +
                    " ; repas dehors : ${pl.dehorsPrevus} prévu(s), ${pl.dehorsFaits} fait(s)."
            )
        }
    }
}
