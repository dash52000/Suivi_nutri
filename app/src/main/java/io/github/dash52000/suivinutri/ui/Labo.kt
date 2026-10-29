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
import io.github.dash52000.suivinutri.data.Journal
import io.github.dash52000.suivinutri.health.HealthData
import io.github.dash52000.suivinutri.labo.EffetCroise
import io.github.dash52000.suivinutri.labo.EffetLendemain
import io.github.dash52000.suivinutri.labo.EffetSemaine
import io.github.dash52000.suivinutri.labo.Intervalle
import io.github.dash52000.suivinutri.labo.JourLabo
import io.github.dash52000.suivinutri.labo.Labo
import io.github.dash52000.suivinutri.labo.PointPoids
import io.github.dash52000.suivinutri.labo.Qualite
import io.github.dash52000.suivinutri.labo.ResultatLabo
import io.github.dash52000.suivinutri.labo.SemaineApports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private sealed interface EtatLabo {
    data object Chargement : EtatLabo
    data class Pret(val resultat: ResultatLabo) : EtatLabo
    data class Erreur(val message: String) : EtatLabo
}

// ---------- Mise en forme ----------

private fun signe(x: Double, decimales: Int, unite: String): String =
    String.format(Locale.FRENCH, "%+,.${decimales}f", x).replace('-', '−') + " " + unite

private fun nombre(x: Double, decimales: Int): String =
    String.format(Locale.FRENCH, "%,.${decimales}f", x).replace('-', '−')

private fun ic(i: Intervalle, decimales: Int, unite: String): String =
    "IC 95 % : ${signe(i.bas, decimales, unite)} à ${signe(i.haut, decimales, unite)}"

private fun pourcent(p: Double): String = String.format(Locale.FRENCH, "%.0f %%", p * 100)

private val formatCourt = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)

// ---------- Écran ----------

@Composable
fun EcranLabo(health: HealthData, journal: Journal, modifier: Modifier = Modifier) {
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
            EtatLabo.Pret(withContext(Dispatchers.Default) { Labo.analyser(jours, aujourdHui) })
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
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (val e = etat) {
            EtatLabo.Chargement -> {
                Text("Lecture d'un an de données…", style = MaterialTheme.typography.bodyMedium)
                CircularProgressIndicator()
            }
            is EtatLabo.Erreur -> Text(e.message, style = MaterialTheme.typography.bodyLarge)
            is EtatLabo.Pret -> ContenuLabo(e.resultat)
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
private fun ContenuLabo(r: ResultatLabo) {
    Section("Poids", "La courbe lissée compte ; la pesée du jour, beaucoup moins.") {
        GraphePoids(r.poids)
    }

    Section(
        "Apports estimés",
        "Dépense moyenne + variation de la tendance du poids × 7 700 kcal/kg. Approximatif : la montre et la " +
            "règle se trompent toutes les deux, mais la tendance sur plusieurs semaines reste parlante.",
    ) {
        if (r.semaines.isEmpty()) {
            Ligne(
                "Pas encore de semaine exploitable : il faut une semaine complète avec au moins 3 pesées " +
                    "et 5 jours de dépense, après une dizaine de jours de pesées."
            )
        } else {
            r.semaines.take(12).forEach { LigneSemaine(it) }
        }
    }

    Section(
        "Le lendemain",
        "Le poids du lendemain bouge surtout avec l'eau, le sel et le glycogène. " +
            "Ce qui compte, c'est que l'écart retombe en 2 ou 3 jours.",
    ) {
        r.lendemain.forEach { LigneLendemain(it) }
    }

    Section(
        "Sur plusieurs semaines",
        "Les semaines comparées entre elles, un réflexe à la fois. Corrélation, pas causalité : une semaine " +
            "chargée peut à la fois faire sauter des réflexes et changer tes apports.",
    ) {
        r.effetsSemaine.forEach { LigneEffetSemaine(it) }
    }

    Section("Effets croisés", null) {
        r.croises.forEach { LigneCroise(it) }
    }

    Section("Qualité des données", null) {
        LigneQualite(r.qualite)
    }
}

@Composable
private fun LigneSemaine(s: SemaineApports) {
    Ligne(
        "Semaine du ${s.lundi.format(formatCourt)} : apports ≈ ${nombre(Math.round(s.apportsEstimes / 50.0) * 50.0, 0)} kcal/j " +
            "· dépense ${nombre(s.depenseMoyenne, 0)} · tendance ${signe(s.deltaTendanceKg, 2, "kg")}"
    )
}

@Composable
private fun LigneLendemain(e: EffetLendemain) {
    val j1 = e.j1
    val texte = if (j1 == null) {
        "${e.libelle} : pas encore assez de données (${e.n1} soir(s) concerné(s), ${e.n0} autre(s) ; " +
            "il en faut ${Labo.MIN_GROUPE} de chaque, avec une pesée le matin et le lendemain)."
    } else buildString {
        append("${e.libelle} : poids du lendemain ${signe(j1.estimation, 1, "kg")} par rapport aux autres soirs ")
        append("(${ic(j1, 1, "kg")} ; ${e.n1} soirs contre ${e.n0}).")
        if (j1.contientZero) append(" Pas d'écart net visible pour l'instant.")
        if (e.j2 != null && e.j3 != null) {
            append(" À J+2 : ${signe(e.j2, 1, "kg")}, à J+3 : ${signe(e.j3, 1, "kg")}.")
        }
    }
    Ligne(texte)
}

@Composable
private fun LigneEffetSemaine(e: EffetSemaine) {
    val effet = e.effet
    val texte = if (effet == null) {
        "${e.libelle} : ${e.nbSemaines} semaine(s) exploitable(s) sur ${Labo.MIN_SEMAINES} nécessaires."
    } else buildString {
        append("${e.libelle} : apports estimés ${signe(effet.estimation, 0, "kcal/j")} ${e.parQuoi} ")
        append("(${ic(effet, 0, "kcal/j")} ; ${e.nbSemaines} semaines).")
        if (effet.contientZero) append(" Pas d'effet net visible pour l'instant.")
    }
    Ligne(texte)
}

@Composable
private fun LigneCroise(e: EffetCroise) {
    val ecart = e.ecart
    val texte = if (ecart == null || e.p1 == null || e.p0 == null) {
        "${e.exposition} — ${e.issue} : pas encore assez de données (${e.n1} jour(s) concerné(s), ${e.n0} autre(s))."
    } else buildString {
        append("${e.exposition} : ${e.issue} ${pourcent(e.p1)} des soirs, contre ${pourcent(e.p0)} ${e.reference} ")
        append("(écart ${signe(ecart.estimation * 100, 0, "points")}, ${ic(ecart.fois(100.0), 0, "points")}).")
        if (ecart.contientZero) append(" Pas d'écart net visible pour l'instant.")
    }
    Ligne(texte)
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
