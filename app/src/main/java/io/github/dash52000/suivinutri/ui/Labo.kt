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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.dash52000.suivinutri.data.Configuration
import io.github.dash52000.suivinutri.data.EntreeJour
import io.github.dash52000.suivinutri.data.Journal
import io.github.dash52000.suivinutri.health.HealthData
import io.github.dash52000.suivinutri.labo.EffetCroise
import io.github.dash52000.suivinutri.labo.EffetLendemain
import io.github.dash52000.suivinutri.labo.EffetSemaine
import io.github.dash52000.suivinutri.labo.JourLabo
import io.github.dash52000.suivinutri.labo.Labo
import io.github.dash52000.suivinutri.labo.LigneCalendrier
import io.github.dash52000.suivinutri.labo.PointPoids
import io.github.dash52000.suivinutri.labo.Qualite
import io.github.dash52000.suivinutri.labo.ResultatLabo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private sealed interface EtatLabo {
    data object Chargement : EtatLabo
    data class Pret(
        val resultat: ResultatLabo,
        val calendrier: List<LigneCalendrier>,
        val dates28: List<LocalDate>,
    ) : EtatLabo
    data class Erreur(val message: String) : EtatLabo
}

@Composable
fun EcranLabo(health: HealthData, journal: Journal, config: Configuration, modifier: Modifier = Modifier) {
    var etat by remember { mutableStateOf<EtatLabo>(EtatLabo.Chargement) }
    var rafraichir by remember { mutableIntStateOf(0) }

    LaunchedEffect(rafraichir) {
        etat = EtatLabo.Chargement
        etat = try {
            val aujourdHui = LocalDate.now()
            val sante = health.journees(aujourdHui.minusDays(364), aujourdHui).associateBy { it.date }
            val entrees = journal.toutes()
            val jours = (sante.keys + entrees.keys).map { d ->
                val s = sante[d]
                JourLabo(d, s?.poidsKg, s?.kcalTotal, s?.sommeilMin, entrees[d])
            }
            val perso = config.perso().map { q -> q.libelle to { e: EntreeJour -> q.score(e.reponses[q.id]) } }
            val dates28 = (27L downTo 0L).map { aujourdHui.minusDays(it) }
            withContext(Dispatchers.Default) {
                EtatLabo.Pret(
                    resultat = Labo.analyser(jours, aujourdHui, perso),
                    calendrier = Labo.calendrier(entrees, dates28, Labo.reflexes + perso),
                    dates28 = dates28,
                )
            }
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
        OutlinedButton(onClick = { rafraichir++ }) { Text("Recalculer") }
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

    Section("Poids", "Des points pour les pesées, un trait pour la tendance lissée : c'est elle qui compte.") {
        GraphePoids(r.poids)
    }

    Section(
        "Apports estimés",
        "Dépense moyenne + variation de la tendance du poids × 7 700 kcal/kg. Approximatif : la montre et la " +
            "règle se trompent toutes les deux, mais la tendance sur plusieurs semaines reste parlante.",
    ) {
        if (r.semaines.size < 2) {
            Ligne(
                "Pas encore assez de semaines exploitables : il faut des semaines complètes avec au moins 3 pesées " +
                    "et 5 jours de dépense, après une dizaine de jours de pesées."
            )
        } else {
            GrapheSemaines(r.semaines)
            val derniere = r.semaines.first()
            Text(
                "Semaine du ${derniere.lundi.format(formatCourt)} : apports ≈ " +
                    "${nombre(Math.round(derniere.apportsEstimes / 50.0) * 50.0, 0)} kcal/j pour une dépense de " +
                    "${nombre(derniere.depenseMoyenne, 0)} (tendance ${signe(derniere.deltaTendanceKg, 2, "kg")}).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    Section("Tes réflexes, 4 dernières semaines", null) {
        if (etat.calendrier.isEmpty()) {
            Ligne("Le calendrier se remplira avec tes réponses du soir.")
        } else {
            CalendrierLong(etat.calendrier, etat.dates28)
        }
    }

    Section(
        "Le lendemain",
        "Le poids du lendemain bouge surtout avec l'eau, le sel et le glycogène. " +
            "Ce qui compte, c'est que l'écart retombe en 2 ou 3 jours.",
    ) {
        ForestPlot(r.lendemain.map { itemLendemain(it) }, "kg", 1)
        GrapheDecroissance(r.lendemain)
    }

    Section(
        "Sur plusieurs semaines",
        "Effet sur les apports estimés, un réflexe à la fois. Corrélation, pas causalité : une semaine " +
            "chargée peut à la fois faire sauter des réflexes et changer tes apports.",
    ) {
        ForestPlot(r.effetsSemaine.map { itemSemaine(it) }, "kcal/j", 0)
    }

    Section("Effets croisés", "Écart de fréquence, en points de pourcentage.") {
        ForestPlot(r.croises.map { itemCroise(it) }, "pts", 0)
    }

    Section("Qualité des données", null) {
        LigneQualite(r.qualite)
    }
}

private fun itemLendemain(e: EffetLendemain): ItemForest {
    val j1 = e.j1
    val detail = if (j1 == null) {
        "Pas encore assez de données : ${e.n1} soir(s) concerné(s), ${e.n0} autre(s) " +
            "(${Labo.MIN_GROUPE} de chaque, avec une pesée le matin et le lendemain)."
    } else {
        "${signe(j1.estimation, 1, "kg")} le lendemain (${ic(j1, 1, "kg")}) · ${e.n1} soirs contre ${e.n0}" +
            if (j1.contientZero) " · pas d'écart net" else ""
    }
    return ItemForest(e.libelle, j1, detail)
}

private fun itemSemaine(e: EffetSemaine): ItemForest {
    val effet = e.effet
    val detail = if (effet == null) {
        "${e.nbSemaines} semaine(s) exploitable(s) sur ${Labo.MIN_SEMAINES} nécessaires."
    } else {
        "${signe(effet.estimation, 0, "kcal/j")} ${e.parQuoi} (${ic(effet, 0, "kcal/j")}) · ${e.nbSemaines} semaines" +
            if (effet.contientZero) " · pas d'effet net" else ""
    }
    return ItemForest(e.libelle, effet, detail)
}

private fun itemCroise(e: EffetCroise): ItemForest {
    val ecart = e.ecart
    val libelle = "${e.exposition} : ${e.issue}"
    if (ecart == null || e.p1 == null || e.p0 == null) {
        return ItemForest(
            libelle, null,
            "Pas encore assez de données : ${e.n1} jour(s) concerné(s), ${e.n0} autre(s) (${Labo.MIN_GROUPE} de chaque).",
        )
    }
    val enPoints = ecart.fois(100.0)
    val detail = "${pourcent(e.p1)} des soirs contre ${pourcent(e.p0)} ${e.reference} " +
        "(${ic(enPoints, 0, "pts")})" + if (ecart.contientZero) " · pas d'écart net" else ""
    return ItemForest(libelle, enPoints, detail)
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
private fun GraphePoids(points: List<PointPoids>) {
    if (points.size < 2) {
        Ligne("Pas encore assez de pesées pour tracer la courbe.")
        return
    }
    val valeurs = points.flatMap { listOfNotNull(it.brut, it.tendance) }
    val min = valeurs.min() - 0.3
    val max = valeurs.max() + 0.3
    val debut = points.first().date
    val fin = points.last().date
    val etendue = maxOf(1L, ChronoUnit.DAYS.between(debut, fin)).toFloat()
    val couleurTendance = MaterialTheme.colorScheme.primary
    val couleurPesees = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${nombre(max, 1)} kg", style = MaterialTheme.typography.labelSmall)
                Text(
                    "Tendance : ${nombre(points.last().tendance, 1)} kg",
                    style = MaterialTheme.typography.labelMedium,
                    color = couleurTendance,
                )
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
            ) {
                fun x(d: LocalDate) = size.width * ChronoUnit.DAYS.between(debut, d) / etendue
                fun y(v: Double) = (size.height * (max - v) / (max - min)).toFloat()
                points.forEach { p ->
                    p.brut?.let { drawCircle(couleurPesees, radius = 3.dp.toPx(), center = Offset(x(p.date), y(it))) }
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
