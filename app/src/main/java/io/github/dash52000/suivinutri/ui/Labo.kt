package io.github.dash52000.suivinutri.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.dash52000.suivinutri.data.Affichage
import io.github.dash52000.suivinutri.data.Configuration
import io.github.dash52000.suivinutri.data.Journal
import io.github.dash52000.suivinutri.health.HealthData
import io.github.dash52000.suivinutri.labo.EffetCroise
import io.github.dash52000.suivinutri.labo.EffetLendemain
import io.github.dash52000.suivinutri.labo.Labo
import io.github.dash52000.suivinutri.labo.Niveau
import io.github.dash52000.suivinutri.labo.ResultatMecanisme
import io.github.dash52000.suivinutri.labo.LigneCalendrier
import io.github.dash52000.suivinutri.labo.PointPoids
import io.github.dash52000.suivinutri.labo.Stats
import io.github.dash52000.suivinutri.labo.Qualite
import io.github.dash52000.suivinutri.labo.ResultatLabo
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private sealed interface EtatLabo {
    data object Chargement : EtatLabo
    data class Pret(val calcul: CalculLabo.Calcul) : EtatLabo {
        val resultat: ResultatLabo get() = calcul.resultat
        val calendrier: List<LigneCalendrier> get() = calcul.calendrier
        val dates28: List<LocalDate> get() = calcul.dates28
    }
    data class Erreur(val message: String) : EtatLabo
}

private val formatHeure = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)

@Composable
fun EcranLabo(health: HealthData, journal: Journal, config: Configuration, modifier: Modifier = Modifier) {
    var etat by remember {
        mutableStateOf<EtatLabo>(CalculLabo.enCache(journal, config)?.let { EtatLabo.Pret(it) } ?: EtatLabo.Chargement)
    }
    var forcer by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    LaunchedEffect(forcer) {
        if (forcer > 0 || etat !is EtatLabo.Pret) etat = EtatLabo.Chargement
        etat = try {
            EtatLabo.Pret(CalculLabo.obtenir(context, health, journal, config, forcer = forcer > 0, origine = "labo"))
        } catch (e: SecurityException) {
            EtatLabo.Erreur("Le labo a besoin de l'accès à Health Connect : autorise-le depuis l'onglet Données.")
        } catch (e: Exception) {
            EtatLabo.Erreur(e.message ?: e.toString())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        when (val e = etat) {
            EtatLabo.Chargement -> {
                Text("Lecture d'un an de données…", style = MaterialTheme.typography.bodyMedium)
                CircularProgressIndicator()
            }
            is EtatLabo.Erreur -> Text(e.message, style = MaterialTheme.typography.bodyLarge)
            is EtatLabo.Pret -> ContenuLabo(e)
        }
        if (etat is EtatLabo.Pret) {
            Text(
                "Calculé à ${(etat as EtatLabo.Pret).calcul.calculeLe.atZone(ZoneId.systemDefault()).format(formatHeure)}. " +
                    "Recalcul automatique si tu réponds au questionnaire, sinon toutes les ${CalculLabo.DUREE_CACHE_HEURES} heures.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = { forcer++ }) { Text("Recalculer maintenant") }
    }
}

@Composable
private fun Section(titre: String, intro: String?, contenu: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(titre, style = MaterialTheme.typography.titleMedium)
        if (intro != null) {
            Text(intro, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        contenu()
    }
}

@Composable
private fun Ligne(texte: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(texte, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ContenuLabo(etat: EtatLabo.Pret) {
    val r = etat.resultat

    Section("Tes réflexes, 4 dernières semaines", null) {
        if (etat.calendrier.isEmpty()) {
            Ligne("Le calendrier se remplira avec tes réponses du soir.")
        } else {
            CalendrierLong(etat.calendrier, etat.dates28)
        }
    }

    Section(
        "Tes réflexes font-ils leur effet ?",
        "Chaque réflexe testé sur ce pour quoi il a été pensé, le soir même : fréquence du rab selon que le réflexe " +
            "a été tenu ou non, à lieu de dîner égal (écart en points). La probabilité dit à quel point l'appli est sûre " +
            "que le réflexe réduit le rab : piste à 70 %, probable à 90 %, solide à 97,5 % avec assez de soirs. Une " +
            "piste se trompe une fois sur trois, c'est normal. Pas de bruit de pesée ici : c'est le premier endroit où " +
            "des résultats apparaîtront.",
    ) {
        ForestPlot(r.mecanismes.map { itemMecanisme(it) }, "pts", 0)
    }

    val masquerPesee = Affichage.masquerPesee(LocalContext.current)
    Section(
        "Poids",
        (if (masquerPesee) "La tendance, sans les pesées du jour. "
        else "Des points gris pour les pesées (vides : écartées), un trait pour la tendance. Quand la balance " +
            "mesure l'eau, un point en couleur donne la pesée corrigée de l'eau du jour (repas salé, glycogène), " +
            "relié à la pesée brute : la longueur du trait, c'est l'eau. ") +
            "La bande donne l'incertitude de la tendance à 95 % : elle s'élargit quand tu ne te pèses pas. " +
            "La tendance suit les pesées corrigées quand il y en a.",
    ) {
        GraphePoids(r.poids, masquerPesee)
        val derniere = r.poids.lastOrNull { it.ecart != null }
        if (derniere != null && !masquerPesee) {
            Text(
                "Pesée du ${derniere.date.format(formatCourt)} : ${texteEcartPesee(derniere)}.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val c = r.composition
        if (c != null && !masquerPesee) {
            Text(
                "Composition (tendances de la balance, corrigées de l'eau) : masse grasse ${nombre(c.graisseKg, 1)} ± " +
                    "${nombre(Stats.Z975 * c.graisseSe, 1)} kg (${nombre(c.graissePct, 1)} %), masse maigre " +
                    "${nombre(c.maigreKg, 1)} ± ${nombre(Stats.Z975 * c.maigreSe, 1)} kg.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    Section(
        "Apports estimés",
        "Dépense moyenne + énergie stockée ou déstockée. Avec la balance : masse grasse × 9 440 kcal/kg + masse " +
            "maigre × 1 816 kcal/kg (l'eau et le glycogène ne comptent presque plus comme de la graisse). " +
            "Sans composition : variation du poids × 7 700 kcal/kg. Intervalle à 95 % lié aux mesures. " +
            "Les jours sans montre (ni sommeil ni séance, moins de 2 000 kcal) sont écartés de la dépense. " +
            "Une barre par semaine complète (lundi matin → lundi matin), la dernière en creux pour les 7 derniers " +
            "jours (pesée d'il y a 7 jours → pesée de ce matin). " +
            "Le niveau absolu est sans doute surestimé, car les montres surestiment souvent la dépense : " +
            "fie-toi aux variations, pas au chiffre brut.",
    ) {
        val aujourdHui = etat.dates28.last()
        // Semaines complètes finies avant aujourd'hui (le lundi, la dernière est déjà les 7 derniers jours).
        val serie = r.semaines.filter { !it.enCours && it.lundi.plusDays(7).isBefore(aujourdHui) } +
            listOfNotNull(r.septDerniersJours)
        if (serie.isEmpty()) {
            Ligne(
                "Pas encore assez de données : il faut au moins 3 pesées et 5 jours de dépense avec la montre " +
                    "sur 7 jours."
            )
        } else {
            GrapheSemaines(serie)
        }
        val derniere = r.septDerniersJours
        if (derniere == null) {
            if (serie.isNotEmpty()) Ligne("7 derniers jours : pas assez de pesées ou de jours avec la montre.")
        } else {
            Text(
                "7 derniers jours : " +
                    "apports ≈ ${nombre(Math.round(derniere.apportsEstimes / 50.0) * 50.0, 0)} kcal/j " +
                    "(${ic(derniere.apports, 0, "kcal/j").replace("+", "")}) pour une dépense de " +
                    "${nombre(derniere.depenseMoyenne, 0)}. Calcul : ${derniere.methode.libelle}" +
                    (derniere.deltaGraisseKg?.let { g ->
                        " (graisse ${signe(g, 2, "kg")}, maigre ${signe(derniere.deltaMaigreKg ?: 0.0, 2, "kg")})"
                    } ?: "") + ".",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    Section(
        "Le lendemain",
        "Écart de la pesée à la tendance, expliqué par toutes les expositions de la veille à la fois : chaque " +
            "effet est estimé à expositions égales par ailleurs. Rab et dessert : effet d'un oui franc (« un peu » " +
            "et « petite part » comptent pour moitié) ; alcool : effet par unité. Le poids du lendemain bouge surtout avec l'eau, " +
            "le sel et le glycogène ; ce qui compte, c'est que l'écart retombe en 2 ou 3 jours.",
    ) {
        ForestPlot(r.lendemain.map { itemLendemain(it) }, "kg", 1)
        GrapheDecroissance(r.lendemain)
    }

    Section("Effets croisés", "Écart de fréquence, en points de pourcentage.") {
        ForestPlot(r.croises.filter { !it.mecanisme }.map { itemCroise(it) }, "pts", 0)
    }

    Text(
        "En couleur : les effets qui résistent à la correction pour tests multiples (Benjamini-Hochberg, " +
            "q < 5 %). Sur une quinzaine d'intervalles à 95 %, environ un exclurait zéro par pur hasard.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Section("Qualité des données", null) {
        LigneQualite(r.qualite)
    }
}

private fun net(q: Double?): Boolean = q != null && q < Labo.SEUIL_Q

private fun verdict(q: Double?): String = when {
    q == null -> ""
    net(q) -> " · retenu (q = ${nombre(q, 3)})"
    else -> " · pas d'effet net (q = ${nombre(q, 2)})"
}

private fun itemLendemain(e: EffetLendemain): ItemForest {
    val j1 = e.j1
    val detail = if (j1 == null) {
        "Pas encore assez de données : ${e.n1} jour(s) concerné(s), ${e.n0} autre(s), avec une pesée le lendemain " +
            "(il en faut ${Labo.MIN_GROUPE} de chaque et ${Labo.MIN_JOURS_REGRESSION} jours complets)."
    } else {
        "${signe(j1.estimation, 1, "kg")} le lendemain (${ic(j1, 1, "kg")}) · ${e.n1} jours contre ${e.n0}" + verdict(e.q)
    }
    return ItemForest(e.libelle, j1, detail, net(e.q))
}

private fun itemMecanisme(m: ResultatMecanisme): ItemForest {
    val libelle = "${m.libelle} → ${m.issue}"
    val ecart = m.ecart
    if (ecart == null || m.probReduit == null || m.p1 == null || m.p0 == null) {
        return ItemForest(
            libelle, null,
            "Pas encore assez de soirs à comparer : ${m.n1} tenu(s), ${m.n0} pas tenu(s), " +
                "à lieu de dîner égal (${Niveau.PISTE.minParGroupe} de chaque au minimum).",
            false,
        )
    }
    val niveau = when {
        m.niveau != Niveau.AUCUN -> m.niveau.libelle
        m.niveauInverse != Niveau.AUCUN -> "${m.niveauInverse.libelle} d'un effet inverse"
        else -> "pas encore de piste"
    }
    val detail = "${pourcent(m.p1)} des soirs contre ${pourcent(m.p0)} quand il n'est pas tenu · probabilité que " +
        "ça réduise le ${m.issue} : ${pourcent(m.probReduit)} ($niveau)"
    return ItemForest(libelle, ecart.fois(100.0), detail, m.niveau >= Niveau.PROBABLE)
}

private fun itemCroise(e: EffetCroise): ItemForest {
    val ecart = e.ecart
    val libelle = "${e.exposition} : ${e.issue}"
    if (ecart == null || e.p1 == null || e.p0 == null) {
        return ItemForest(
            libelle, null,
            "Pas encore assez de données : ${e.n1} jour(s) concerné(s), ${e.n0} autre(s) (${Labo.MIN_GROUPE} de chaque).",
            false,
        )
    }
    val enPoints = ecart.fois(100.0)
    val detail = "${pourcent(e.p1)} des jours contre ${pourcent(e.p0)} ${e.reference} " +
        "(${ic(enPoints, 0, "pts")})" + verdict(e.q)
    return ItemForest(libelle, enPoints, detail, net(e.q))
}

@Composable
private fun LigneQualite(q: Qualite) {
    Ligne("Jours renseignés sur les 4 dernières semaines : ${q.joursRenseignes28}/28.")
    val ecart = q.ecartSansReponse
    Ligne(
        when {
            ecart == null ->
                "Pas encore assez de données pour comparer les jours sans réponse aux autres."
            ecart.bas > 0 ->
                "Les lendemains de jours sans réponse, ton poids monte de ${signe(ecart.estimation, 1, "kg")} de plus " +
                    "(${ic(ecart, 1, "kg")}). Ces jours-là ne ressemblent pas aux autres : les écarts ci-dessus " +
                    "sont sans doute sous-estimés."
            else ->
                "Rien n'indique pour l'instant que les jours sans réponse soient différents des autres " +
                    "(écart ${signe(ecart.estimation, 1, "kg")}, ${ic(ecart, 1, "kg")})."
        }
    )
}

@Composable
private fun GraphePoids(points: List<PointPoids>, masquerPesee: Boolean) {
    if (points.size < 2) {
        Ligne("Pas encore assez de pesées pour tracer la courbe.")
        return
    }
    // L'échelle suit la tendance et les pesées ; la bande est coupée aux bords si elle déborde.
    val valeurs = points.flatMap {
        if (masquerPesee) listOf(it.tendance)
        else listOfNotNull(if (it.ecartee) null else it.brut, if (it.ecartee) null else it.corrige, it.tendance)
    }
    val min = valeurs.min() - 0.5
    val max = valeurs.max() + 0.5
    val debut = points.first().date
    val fin = points.last().date
    val etendue = maxOf(1L, ChronoUnit.DAYS.between(debut, fin)).toFloat()
    val couleurTendance = MaterialTheme.colorScheme.primary
    val couleurPesees = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val couleurCorrigees = MaterialTheme.colorScheme.tertiary

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${nombre(max, 1)} kg", style = MaterialTheme.typography.labelSmall)
                Text(
                    "Tendance : ${nombre(points.last().tendance, 1)} ± ${nombre(Stats.Z975 * points.last().seTendance, 1)} kg",
                    style = MaterialTheme.typography.labelMedium,
                    color = couleurTendance,
                )
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clipToBounds(),
            ) {
                fun x(d: LocalDate) = size.width * ChronoUnit.DAYS.between(debut, d) / etendue
                fun y(v: Double) = (size.height * (max - v) / (max - min)).toFloat()

                val bande = Path()
                points.forEachIndexed { i, p ->
                    val haut = y(p.tendance + Stats.Z975 * p.seTendance)
                    if (i == 0) bande.moveTo(x(p.date), haut) else bande.lineTo(x(p.date), haut)
                }
                points.asReversed().forEach { p -> bande.lineTo(x(p.date), y(p.tendance - Stats.Z975 * p.seTendance)) }
                bande.close()
                drawPath(bande, couleurTendance.copy(alpha = 0.15f))

                if (!masquerPesee) points.forEach { p ->
                    val v = p.brut ?: return@forEach
                    val centre = Offset(x(p.date), y(v))
                    if (p.ecartee) {
                        drawCircle(couleurPesees, radius = 3.5.dp.toPx(), center = centre, style = Stroke(1.5.dp.toPx()))
                    } else {
                        drawCircle(couleurPesees.copy(alpha = if (p.apresMidi) 0.25f else 0.5f), radius = 3.dp.toPx(), center = centre)
                    }
                    // Pesée corrigée de l'eau, reliée à la brute : la longueur du trait, c'est l'eau du jour.
                    val corrige = p.corrige
                    if (corrige != null && !p.ecartee) {
                        val c = Offset(centre.x, y(corrige))
                        drawLine(couleurCorrigees.copy(alpha = 0.4f), centre, c, strokeWidth = 1.5.dp.toPx())
                        drawCircle(couleurCorrigees.copy(alpha = 0.8f), radius = 2.5.dp.toPx(), center = c)
                    }
                }
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
    }
}
