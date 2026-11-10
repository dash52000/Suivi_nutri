package io.github.dash52000.suivinutri.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.dash52000.suivinutri.data.Configuration
import io.github.dash52000.suivinutri.data.EntreeJour
import io.github.dash52000.suivinutri.data.Journal
import io.github.dash52000.suivinutri.data.Objectif
import io.github.dash52000.suivinutri.data.PlanWeekEnd
import io.github.dash52000.suivinutri.data.Usage
import io.github.dash52000.suivinutri.health.HealthData
import io.github.dash52000.suivinutri.labo.BilanPlan
import io.github.dash52000.suivinutri.labo.Cap
import io.github.dash52000.suivinutri.labo.JourLabo
import io.github.dash52000.suivinutri.labo.Kalman
import io.github.dash52000.suivinutri.labo.Labo
import io.github.dash52000.suivinutri.labo.PointPoids
import io.github.dash52000.suivinutri.labo.ResultatCap
import io.github.dash52000.suivinutri.labo.SemaineCap
import io.github.dash52000.suivinutri.labo.Stats
import io.github.dash52000.suivinutri.labo.Verdict
import io.github.dash52000.suivinutri.labo.joursSansSante
import io.github.dash52000.suivinutri.notif.Signal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Données de l'onglet : le calcul du labo s'il a pu être fait, sinon le journal seul. */
private class DonneesCap(
    val jours: List<JourLabo>,
    val lissage: Kalman.Lissage?,
    val poids: List<PointPoids>,
    val erreur: String?,
)

@Composable
fun EcranCap(
    health: HealthData,
    journal: Journal,
    config: Configuration,
    objectif: Objectif,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var donnees by remember {
        mutableStateOf(
            CalculLabo.enCache(journal, config)?.let { DonneesCap(it.jours, it.lissage, it.resultat.poids, null) }
        )
    }
    var forcer by remember { mutableIntStateOf(0) }
    var etat by remember { mutableStateOf(objectif.etat()) }
    // Relu à chaque entrée dans l'onglet, pour suivre le questionnaire du soir.
    val entrees = remember { journal.toutes() }
    val aujourdHui = remember { LocalDate.now() }

    LaunchedEffect(forcer) {
        if (forcer > 0 || donnees == null) donnees = null
        donnees = try {
            val c = CalculLabo.obtenir(context, health, journal, config, forcer = forcer > 0, origine = "cap")
            DonneesCap(c.jours, c.lissage, c.resultat.poids, null)
        } catch (e: SecurityException) {
            DonneesCap(joursSansSante(entrees), null, emptyList(), "Sans accès à Health Connect, pas de tendance du poids : autorise-le depuis l'onglet Données.")
        } catch (e: Exception) {
            DonneesCap(joursSansSante(entrees), null, emptyList(), e.message ?: e.toString())
        }
    }

    val perso = remember { config.perso().map { q -> q.libelle to { e: EntreeJour -> q.score(e.reponses[q.id]) } } }
    val reflexes = remember { Labo.reflexes + perso }
    val d = donnees
    val resultat = remember(d, etat) {
        d?.let { Cap.analyser(it.jours, it.lissage, etat, aujourdHui, reflexes) }
    }
    LaunchedEffect(resultat?.budgetCourant) {
        resultat?.let {
            objectif.memoriserBudgetCourant(it.budgetCourant)
            Signal.programmer(context)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (d == null || resultat == null) {
            Text("Lecture de la tendance du poids…", style = MaterialTheme.typography.bodyMedium)
            CircularProgressIndicator()
            return@Column
        }
        d.erreur?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        CarteCap(resultat, d.poids)

        CarteSemaine(
            s = resultat.semaineEnCours,
            ciblePas = resultat.ciblePas,
            propositions = resultat.propositions,
            tous = reflexes.map { it.first } + Cap.LEVIER_PAS,
            onChoisir = { libelle ->
                objectif.choisirLevier(resultat.semaineEnCours.lundi, libelle)
                Usage.log(context, "levier", "semaine=${resultat.semaineEnCours.lundi};levier=${libelle ?: "retire"}")
                etat = objectif.etat()
            },
        )

        val vendredi = PlanWeekEnd.vendrediDe(aujourdHui)
        if (vendredi != null) {
            CartePlan(
                plan = etat.plans[vendredi] ?: PlanWeekEnd(vendredi),
                budget = resultat.budgetCourant,
                onChanger = { plan ->
                    objectif.enregistrerPlan(plan)
                    Usage.log(context, "plan_week_end", "vendredi=$vendredi;repas=${plan.repas};bieres=${plan.bieres}")
                    etat = objectif.etat()
                },
            )
        } else {
            // Du lundi au jeudi : le week-end écoulé, prévu face à fait.
            val dernier = aujourdHui.with(TemporalAdjusters.previous(DayOfWeek.FRIDAY))
            val plan = etat.plans[dernier]
            if (plan != null) {
                CarteBilanPlan(Cap.bilanPlan(plan, entrees))
            } else {
                Texte("Le plan du week-end s'ouvre vendredi.")
            }
        }

        val finies = resultat.semainesFinies.asReversed()
        if (finies.isNotEmpty()) {
            Text("Les semaines", style = MaterialTheme.typography.titleMedium)
            finies.forEach { CarteSemaineFinie(it, resultat.ciblePas) }
        }

        Texte(
            "Pente = variation de la tendance du poids (corrigée de l'eau) du lundi matin au lundi matin suivant, avec " +
                "son intervalle à 95 %. Dans le couloir : estimation entre ${nombre(resultat.couloirBas, 2)} et " +
                "${nombre(resultat.couloirHaut, 2)} kg. Au-dessus ou plus vite : tout l'intervalle est hors du couloir. " +
                "Sinon, pas encore lisible (et moins de ${Cap.MIN_PESEES} pesées, toujours). Bières en équivalent " +
                "bouteille de 33 cl."
        )
        OutlinedButton(onClick = { forcer++ }) { Text("Recalculer maintenant") }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Texte(t: String) {
    Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun couleurVerdict(v: Verdict): Color = when (v) {
    Verdict.DANS_LE_COULOIR -> MaterialTheme.colorScheme.primary
    Verdict.PLUS_VITE -> MaterialTheme.colorScheme.secondary
    Verdict.AU_DESSUS -> MaterialTheme.colorScheme.tertiary
    Verdict.PAS_LISIBLE -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun texteBieres(s: SemaineCap): String {
    val b = s.bieres ?: return "Bières : alcool pas renseigné (budget ${s.budget})"
    return "Bières : ${nombreCourt(Math.round(b * 10) / 10.0)} / ${s.budget}" +
        if (s.enCours) " (sur ${s.joursAlcool} jour(s) renseigné(s))" else ""
}

private fun texteActivite(s: SemaineCap): String =
    "Activité : ${s.nbSeances} séance(s), ${s.minutesSeances} min" +
        (s.depenseMoyenne?.let { " · dépense ${nombre(it, 0)} kcal/j" } ?: "")

private fun textePas(s: SemaineCap, cible: Double?): String? {
    val p = s.pasHorsSport ?: return null
    return "Pas hors sport : ${nombre(p, 0)} par jour" +
        (cible?.let { " · ${s.joursCiblePas} jour(s) sur ${s.joursPas} au-dessus de ${nombre(it, 0)}" } ?: "")
}

private fun textePente(s: SemaineCap): String {
    val p = s.pente ?: return if (s.enCours) "Pente : dès 2 jours écoulés" else "Pente : pas assez de pesées"
    return "Pente : ${signe(p.estimation, 2, "kg/sem.")} (${ic(p, 2, "kg").removePrefix("IC 95 % : ")})" +
        if (s.nbPesees < Cap.MIN_PESEES) " · ${s.nbPesees} pesée(s)" else ""
}

private fun texteLevier(s: SemaineCap): String? {
    val l = s.levier ?: return null
    val a = s.adherence
    return "Levier : $l" + if (a == null) " · pas encore de réponse" else
        " · tenu ${nombreCourt(a.tenus)} soir(s) sur ${a.repondus}"
}

@Composable
private fun CarteCap(r: ResultatCap, poids: List<PointPoids>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Le cap", style = MaterialTheme.typography.titleMedium)
            Text(
                "Couloir ${nombre(r.couloirBas, 2)} à ${nombre(r.couloirHaut, 2)} kg par semaine, depuis le lundi " +
                    "${r.lundiDepart.format(formatCourt)}.",
                style = MaterialTheme.typography.bodySmall,
            )
            GrapheCap(r, poids)
            r.basePas?.let { b ->
                Text(
                    "Pas hors sport : base ${nombre(b, 0)} par jour (8 semaines avant le départ), cible " +
                        "${nombre(r.ciblePas ?: b, 0)}.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val p = r.penteDepuisDebut
            Text(
                if (p == null) "Pente depuis le départ : au bout d'une semaine."
                else "Depuis le départ : ${signe(p.estimation, 2, "kg/sem.")} (${ic(p, 2, "kg").removePrefix("IC 95 % : ")})" +
                    (r.niveauActuel?.let { a -> r.niveauDepart?.let { d -> ", tendance ${signe(a - d, 1, "kg")}" } } ?: "") + ".",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Tendance depuis 2 semaines avant le départ, et le couloir tracé à partir du niveau du lundi de départ. */
@Composable
private fun GrapheCap(r: ResultatCap, poids: List<PointPoids>) {
    val points = poids.filter { !it.date.isBefore(r.lundiDepart.minusDays(14)) }
    val depart = r.niveauDepart
    if (points.size < 2 || depart == null) {
        Texte("Le graphe apparaîtra avec quelques pesées.")
        return
    }
    val debut = points.first().date
    val fin = maxOf(points.last().date, r.lundiDepart.plusDays(28))
    val joursCouloir = ChronoUnit.DAYS.between(r.lundiDepart, fin).toDouble()
    val bas = depart + r.couloirBas / 7 * joursCouloir
    val haut = depart + r.couloirHaut / 7 * joursCouloir
    val valeurs = points.flatMap { listOf(it.tendance + Stats.Z975 * it.seTendance, it.tendance - Stats.Z975 * it.seTendance) } +
        listOf(depart, bas, haut)
    val min = valeurs.min() - 0.3
    val max = valeurs.max() + 0.3
    val etendue = maxOf(1L, ChronoUnit.DAYS.between(debut, fin)).toFloat()
    val couleurTendance = MaterialTheme.colorScheme.primary
    val couleurCouloir = MaterialTheme.colorScheme.secondary

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${nombre(max, 1)} kg", style = MaterialTheme.typography.labelSmall)
        Text("Tendance : ${nombre(points.last().tendance, 1)} kg", style = MaterialTheme.typography.labelMedium, color = couleurTendance)
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clipToBounds(),
    ) {
        fun x(d: LocalDate) = size.width * ChronoUnit.DAYS.between(debut, d) / etendue
        fun y(v: Double) = (size.height * (max - v) / (max - min)).toFloat()

        val couloir = Path().apply {
            moveTo(x(r.lundiDepart), y(depart))
            lineTo(x(fin), y(haut))
            lineTo(x(fin), y(bas))
            close()
        }
        drawPath(couloir, couleurCouloir.copy(alpha = 0.18f))
        drawLine(
            couleurCouloir.copy(alpha = 0.6f), Offset(x(r.lundiDepart), 0f), Offset(x(r.lundiDepart), size.height),
            strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
        )

        val bande = Path()
        points.forEachIndexed { i, p ->
            val h = y(p.tendance + Stats.Z975 * p.seTendance)
            if (i == 0) bande.moveTo(x(p.date), h) else bande.lineTo(x(p.date), h)
        }
        points.asReversed().forEach { p -> bande.lineTo(x(p.date), y(p.tendance - Stats.Z975 * p.seTendance)) }
        bande.close()
        drawPath(bande, couleurTendance.copy(alpha = 0.15f))

        val chemin = Path()
        points.forEachIndexed { i, p ->
            if (i == 0) chemin.moveTo(x(p.date), y(p.tendance)) else chemin.lineTo(x(p.date), y(p.tendance))
        }
        drawPath(chemin, couleurTendance, style = Stroke(width = 2.5.dp.toPx()))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${nombre(min, 1)} kg · ${debut.format(formatCourt)}", style = MaterialTheme.typography.labelSmall)
        Text(fin.format(formatCourt), style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CarteSemaine(
    s: SemaineCap,
    ciblePas: Double?,
    propositions: List<String>,
    tous: List<String>,
    onChoisir: (String?) -> Unit,
) {
    var choix by remember(s.levier) { mutableStateOf(s.levier == null) }
    var autres by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Cette semaine", style = MaterialTheme.typography.titleMedium)
            Texte("Du lundi ${s.lundi.format(formatCourt)} · ${s.nbJours} jour(s) écoulé(s)")

            if (!choix && s.levier != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(texteLevier(s)!!, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { choix = true }) { Text("Changer") }
                }
            } else {
                Text("Ton levier de la semaine", style = MaterialTheme.typography.titleSmall)
                Texte(
                    if (propositions.isEmpty()) "Pas encore assez de réponses pour proposer : choisis dans la liste."
                    else "Les réflexes les moins tenus ces 14 derniers jours. Un seul pour la semaine."
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    propositions.forEach { l ->
                        FilterChip(selected = s.levier == l, onClick = { onChoisir(l); choix = false }, label = { Text(l) })
                    }
                }
                TextButton(onClick = { autres = !autres }) { Text(if (autres) "Masquer la liste" else "Un autre réflexe…") }
                if (autres) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tous.filter { it !in propositions }.forEach { l ->
                            FilterChip(selected = s.levier == l, onClick = { onChoisir(l); choix = false; autres = false }, label = { Text(l) })
                        }
                    }
                }
                if (s.levier != null) {
                    Row {
                        TextButton(onClick = { choix = false }) { Text("Garder « ${s.levier} »") }
                        TextButton(onClick = { onChoisir(null) }) { Text("Retirer") }
                    }
                }
            }

            Text(texteBieres(s), style = MaterialTheme.typography.bodyMedium)
            Text(texteActivite(s), style = MaterialTheme.typography.bodySmall)
            textePas(s, ciblePas)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(textePente(s), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
            if (s.pente != null) {
                Text(
                    "Provisoire : ${s.verdict.libelle}",
                    style = MaterialTheme.typography.labelMedium,
                    color = couleurVerdict(s.verdict),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CartePlan(plan: PlanWeekEnd, budget: Int, onChanger: (PlanWeekEnd) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Plan du week-end", style = MaterialTheme.typography.titleMedium)
            Texte("30 secondes : où tombent les repas dehors et les bières. Retape un choix pour l'effacer.")
            PlanWeekEnd.REPAS.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.libelle, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(80.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(PlanWeekEnd.NORMAL to "Normal", PlanWeekEnd.DEHORS to "Dehors / chez d'autres").forEach { (code, libelle) ->
                            FilterChip(
                                selected = plan.repas[r.cle] == code,
                                onClick = {
                                    val repas = plan.repas.toMutableMap()
                                    if (repas[r.cle] == code) repas.remove(r.cle) else repas[r.cle] = code
                                    onChanger(plan.copy(repas = repas))
                                },
                                label = { Text(libelle) },
                            )
                        }
                    }
                }
            }
            Text("Bières prévues", style = MaterialTheme.typography.titleSmall)
            PlanWeekEnd.JOURS_BIERES.forEach { j ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(j.libelle, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(80.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (0..3).forEach { n ->
                            FilterChip(
                                selected = plan.bieres[j.cle] == n,
                                onClick = {
                                    val bieres = plan.bieres.toMutableMap()
                                    if (bieres[j.cle] == n) bieres.remove(j.cle) else bieres[j.cle] = n
                                    onChanger(plan.copy(bieres = bieres))
                                },
                                label = { Text(n.toString()) },
                            )
                        }
                    }
                }
            }
            if (plan.bieres.isNotEmpty()) {
                Text(
                    "Prévu : ${plan.bieres.values.sum()} bière(s) sur le week-end, budget de la semaine : $budget.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CarteBilanPlan(b: BilanPlan) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Le week-end du ${b.plan.vendredi.format(formatCourt)}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Bières : ${b.bieresPrevues} prévue(s), " +
                    (b.bieresBues?.let { "${nombreCourt(Math.round(it * 10) / 10.0)} bue(s)" } ?: "alcool pas renseigné") + ".",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Repas dehors : ${b.dehorsPrevus} prévu(s), ${b.dehorsFaits} fait(s) sur ${b.repasRenseignes} repas renseigné(s).",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (b.ecarts.isNotEmpty()) {
                Texte("Pas comme prévu : ${b.ecarts.joinToString(", ").lowercase(Locale.FRENCH)}.")
            }
        }
    }
}

@Composable
private fun CarteSemaineFinie(s: SemaineCap, ciblePas: Double?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Semaine du ${s.lundi.format(formatCourt)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(s.verdict.libelle, style = MaterialTheme.typography.labelLarge, color = couleurVerdict(s.verdict))
            }
            Text(textePente(s), style = MaterialTheme.typography.bodySmall)
            texteLevier(s)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(texteBieres(s), style = MaterialTheme.typography.bodySmall)
            Text(texteActivite(s), style = MaterialTheme.typography.bodySmall)
            textePas(s, ciblePas)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
