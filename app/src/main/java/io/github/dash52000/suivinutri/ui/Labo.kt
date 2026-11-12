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
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.dash52000.suivinutri.data.Affichage
import io.github.dash52000.suivinutri.data.Configuration
import io.github.dash52000.suivinutri.data.Journal
import io.github.dash52000.suivinutri.health.HealthData
import io.github.dash52000.suivinutri.labo.CalculCascade
import io.github.dash52000.suivinutri.labo.EffetCroise
import io.github.dash52000.suivinutri.labo.EffetLendemain
import io.github.dash52000.suivinutri.labo.JourType
import io.github.dash52000.suivinutri.labo.Labo
import io.github.dash52000.suivinutri.labo.Liens
import io.github.dash52000.suivinutri.labo.SemaineType
import io.github.dash52000.suivinutri.labo.SoirType
import io.github.dash52000.suivinutri.labo.Niveau
import io.github.dash52000.suivinutri.labo.ResultatMecanisme
import io.github.dash52000.suivinutri.labo.LigneCalendrier
import io.github.dash52000.suivinutri.labo.PointPoids
import io.github.dash52000.suivinutri.labo.Stats
import io.github.dash52000.suivinutri.labo.Qualite
import io.github.dash52000.suivinutri.labo.ResultatLabo
import java.time.DayOfWeek
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

/** Titre de partie, avec son explication repliée derrière « ? » (v1.14). */
@Composable
private fun Section(titre: String, aide: String?, petit: Boolean = false, contenu: @Composable () -> Unit) {
    var ouvert by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                titre,
                style = if (petit) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (aide != null) TextButton(onClick = { ouvert = !ouvert }) { Text(if (ouvert) "×" else "?") }
        }
        if (aide != null && ouvert) {
            Text(aide, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private val AIDE_PREUVE =
    "Chaque lien a une probabilité, calculée avec un a priori sceptique (il faut des données pour s'éloigner de « pas " +
        "d'effet ») : piste à 70 %, probable à 90 %, solide à 97,5 %, avec assez de jours de chaque côté. Une piste se " +
        "trompe une fois sur trois. Un effet dans le sens inattendu (un réflexe qui augmente le rab…) n'est annoncé " +
        "que s'il est solide."

@Composable
private fun ContenuLabo(etat: EtatLabo.Pret) {
    val r = etat.resultat
    val masquerPesee = Affichage.masquerPesee(LocalContext.current)

    // ---------- Ce qu'on sait ----------
    Section(
        "Ce qu'on sait",
        "Les liens probables ou solides, toutes questions confondues, les plus sûrs d'abord. Une quarantaine de liens " +
            "sont suivis : de temps en temps, un « probable » est un faux positif. S'il est vrai, il finit solide ; " +
            "sinon il redescend, et le bilan du dimanche le dit. $AIDE_PREUVE",
    ) {
        val sus = Liens.annonces(r)
        if (sus.isEmpty()) {
            val pistes = Liens.pistes(r)
            Ligne(
                "Rien de probable pour l'instant" +
                    (if (pistes > 0) " : $pistes piste${if (pistes > 1) "s" else ""} en cours, à suivre plus bas." else ".")
            )
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sus.forEach { l ->
                        Column {
                            Text(l.phrase, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${l.chiffre} · ${l.niveau.libelle} (${pourcent(l.prob)})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- 1. La balance le lendemain ----------
    Section(
        "Qu'est-ce qui fait bouger ta balance le lendemain ?",
        "La pesée du matin comparée à la tendance, expliquée par tout ce qui s'est passé la veille à la fois : chaque " +
            "effet est estimé à reste égal. Rab et dessert : effet d'un oui franc (« un peu » et « petite part » comptent " +
            "pour moitié) ; alcool : effet par unité. C'est surtout de l'eau (sel, glycogène, alcool) : ce qui compte, " +
            "c'est que l'écart retombe en 2 ou 3 jours. $AIDE_PREUVE",
    ) {
        ForestPlot(r.lendemain.map { itemLendemain(it) }, "kg", 1)
        GrapheDecroissance(r.lendemain)
    }
    if (!masquerPesee) {
        Section(
            "Ta semaine type sur la balance",
            "Écart moyen de la pesée à la tendance selon le jour, sur les 12 dernières semaines, avec un intervalle à 95 %. " +
                "La pesée du lundi reflète le dimanche. C'est une description, pas un lien : elle dit quand la balance " +
                "monte, le bloc du dessus dit pourquoi.",
            petit = true,
        ) {
            val jours = r.balanceParJour.sortedBy { it.jour.value }
            if (jours.all { it.ecart == null }) {
                Ligne("Il faut au moins ${SemaineType.MIN_PAR_JOUR} pesées par jour de la semaine.")
            } else {
                ForestPlot(jours.map { itemJour(it) }, "kg", 1)
            }
        }
        Section(
            "D'où vient l'écart du jour",
            "Sur les 4 dernières semaines, l'écart des pesées à la tendance découpé dans l'ordre : d'abord ce qu'explique " +
                "la veille (effets du bloc du dessus), puis l'eau du jour mesurée par la balance et pas expliquée par la " +
                "veille, puis le reste (bruit de la balance, ce que l'appli ne voit pas). En simulation, sans aucun " +
                "effet réel, la veille « explique » environ 5 % par hasard : sous 10 %, elle n'explique rien de net.",
            petit = true,
        ) {
            val o = r.origineEcart
            if (o == null) Ligne("Il faut au moins ${Labo.MIN_JOURS_ORIGINE} pesées sur les 4 dernières semaines.")
            else BarreOrigine(o)
        }
    }

    // ---------- 2. Les réflexes et ce qui pousse au rab ----------
    Section(
        "Tes réflexes marchent-ils, et qu'est-ce qui pousse au rab ?",
        "Chaque réflexe testé sur ce pour quoi il a été pensé, le soir même : fréquence du rab selon qu'il a été tenu ou " +
            "non, à lieu de dîner égal. Puis le contexte (nuit, alcool la veille, séance…) face à ce qui suit. Pas de " +
            "bruit de pesée ici : c'est là que les résultats arrivent en premier. $AIDE_PREUVE",
    ) {
        if (etat.calendrier.isEmpty()) {
            Ligne("Le calendrier se remplira avec tes réponses du soir.")
        } else {
            CalendrierLong(etat.calendrier, etat.dates28)
        }
        ForestPlot(r.mecanismes.map { itemMecanisme(it) }, "pts", 0)
        ForestPlot(r.croises.filter { !it.mecanisme }.map { itemCroise(it) }, "pts", 0)
    }
    Section(
        "Tes soirs",
        "Sur les 12 dernières semaines, parmi les soirs renseignés : part des soirs avec rab (« un peu » compte), avec " +
            "alcool, avec dessert, chez les beaux-parents. Au moins ${SemaineType.MIN_PAR_JOUR} soirs par case.",
        petit = true,
    ) {
        TableauSoirs(r.soirsParJour)
    }

    // ---------- 3. La graisse ----------
    Section(
        "Où en est ta graisse ?",
        "La cascade (v1.15) part de l'effort mesuré par la balance depuis le départ (modèle de Hall : de combien tu " +
            "manges moins qu'avant, moins l'activité en plus), puis le découpe : rab, dessert et alcool d'après le " +
            "questionnaire et une table de coûts (rab 300 kcal, dessert 250, alcool 70 par unité, plus les glucides), " +
            "l'activité d'après la montre, et le reste, non expliqué. Tout est comparé aux habitudes d'avant le départ. " +
            "Chaque barre a sa marge à 95 % : elle vient surtout de ces habitudes, vues sur peu de jours, et ne diminue " +
            "pas avec le temps. L'effort mesuré, lui, se précise : environ ±65 kcal/j à 3 mois, ±40 à 6. Les kg " +
            "traduisent chaque barre par le modèle de Hall. En simulation, sans rien de caché, le non-expliqué sort " +
            "à tort environ une fois sur dix ; des portions réduites de 150 kcal/j sans le dire ressortent une fois " +
            "sur deux.",
    ) {
        val c = r.cascadeDepart
        if (c == null) {
            Ligne("La cascade arrive 4 semaines après le départ de l'objectif, avec assez de pesées et de réponses.")
        } else {
            CarteCascade(c, "Depuis le départ")
            val m = r.cascadeMois
            if (m != null && c.jours >= 2 * CalculCascade.JOURS_MIN) CarteCascade(m, "4 dernières semaines", graphe = false)
        }
    }
    Section(
        "Ton poids",
        (if (masquerPesee) "La tendance, sans les pesées du jour. "
        else "Des points gris pour les pesées (vides : écartées), un trait pour la tendance. Quand la balance " +
            "mesure l'eau, un point en couleur donne la pesée corrigée de l'eau du jour (repas salé, glycogène), " +
            "relié à la pesée brute : la longueur du trait, c'est l'eau. ") +
            "La bande donne l'incertitude de la tendance à 95 % : elle s'élargit quand tu ne te pèses pas.",
        petit = true,
    ) {
        GraphePoids(r.poids, masquerPesee)
        val derniere = r.poids.lastOrNull { it.ecart != null }
        if (derniere != null && !masquerPesee) {
            Text(
                "Pesée du ${derniere.date.format(formatCourt)} : ${texteEcartPesee(derniere)}.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (!masquerPesee && r.serieComposition.size >= 2) {
        Section(
            "Graisse et maigre",
            "Tendances de la balance corrigées de l'eau, en variation depuis le début du graphe, avec leur bande à 95 %. " +
                "Le « maigre » de la balance comprend l'eau, le glycogène et les organes : ce n'est pas du muscle, et sa " +
                "variation n'est lisible qu'au bout de 10 à 12 semaines.",
            petit = true,
        ) {
            GrapheComposition(r.serieComposition)
            r.composition?.let { c ->
                Text(
                    "Aujourd'hui : graisse ${nombre(c.graisseKg, 1)} ± ${nombre(Stats.Z975 * c.graisseSe, 1)} kg " +
                        "(${nombre(c.graissePct, 1)} %), maigre ${nombre(c.maigreKg, 1)} ± ${nombre(Stats.Z975 * c.maigreSe, 1)} kg.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    Section(
        "Apports estimés",
        "Dépense moyenne + énergie stockée ou déstockée. Avec la balance : masse grasse × 9 440 kcal/kg + masse " +
            "maigre × 1 816 kcal/kg (l'eau et le glycogène ne comptent presque plus comme de la graisse). " +
            "Sans composition : variation du poids × 8 840 kcal/kg (modèle de Hall, comme le Cap). " +
            "Intervalle à 95 % lié aux mesures. " +
            "Les jours sans montre (ni sommeil ni séance, moins de 2 000 kcal) sont écartés de la dépense. " +
            "Une barre par semaine complète (dimanche matin → dimanche matin, comme le Cap), la dernière en creux pour les 7 derniers " +
            "jours (pesée d'il y a 7 jours → pesée de ce matin). " +
            "Le niveau absolu dépend de la précision de la montre, qu'on ne peut pas vérifier : " +
            "fie-toi aux variations, pas au chiffre brut.",
        petit = true,
    ) {
        val aujourdHui = etat.dates28.last()
        // Semaines complètes finies avant aujourd'hui (le dimanche, la dernière est déjà les 7 derniers jours).
        val serie = r.semaines.filter { !it.enCours && it.debut.plusDays(7).isBefore(aujourdHui) } +
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

    Section("Qualité des données", null) {
        LigneQualite(r.qualite)
    }
}

/** Texte du niveau de preuve : sens attendu, sinon sens inverse s'il atteint au moins une piste. */
private fun texteNiveau(prob: Double, niveau: Niveau, niveauInverse: Niveau, sens: String, sensInverse: String): String = when {
    niveau != Niveau.AUCUN -> "probabilité que $sens : ${pourcent(prob)} (${niveau.libelle})"
    niveauInverse != Niveau.AUCUN -> "probabilité que $sensInverse : ${pourcent(1 - prob)} (${niveauInverse.libelle})"
    else -> "probabilité que $sens : ${pourcent(prob)} (pas encore de piste)"
}

private fun itemLendemain(e: EffetLendemain): ItemForest {
    val post = e.post1
    val prob = e.probMonte
    if (post == null || prob == null) {
        return ItemForest(
            e.libelle, null,
            "Pas encore assez de données : ${e.n1} jour(s) concerné(s), ${e.n0} autre(s), avec une pesée le lendemain " +
                "(il en faut ${Labo.MIN_GROUPE} de chaque et ${Labo.MIN_JOURS_REGRESSION} jours complets).",
        )
    }
    val detail = "${signe(post.estimation, 1, "kg")} le lendemain · ${e.n1} jours contre ${e.n0} · " +
        texteNiveau(prob, e.niveau, e.niveauInverse, "ça fasse monter", "ça fasse baisser")
    return ItemForest(e.libelle, post, detail, Liens.annonce(e.niveau, false) || Liens.annonce(e.niveauInverse, true))
}

private fun itemMecanisme(m: ResultatMecanisme): ItemForest {
    val libelle = "${m.libelle} → ${m.issue}"
    val ecart = m.ecart
    if (ecart == null || m.probReduit == null || m.p1 == null || m.p0 == null) {
        return ItemForest(
            libelle, null,
            "Pas encore assez de soirs à comparer : ${m.n1} tenu(s), ${m.n0} pas tenu(s), " +
                "à lieu de dîner égal (${Niveau.PISTE.minParGroupe} de chaque au minimum).",
        )
    }
    val detail = "${pourcent(m.p1)} des soirs contre ${pourcent(m.p0)} quand il n'est pas tenu · " +
        texteNiveau(m.probReduit, m.niveau, m.niveauInverse, "ça réduise le ${m.issue}", "ça l'augmente")
    return ItemForest(libelle, ecart.fois(100.0), detail, Liens.annonce(m.niveau, false) || Liens.annonce(m.niveauInverse, true))
}

private fun itemCroise(e: EffetCroise): ItemForest {
    val ecart = e.ecart
    val libelle = "${e.exposition} : ${e.issue}"
    val prob = e.probAugmente
    if (ecart == null || prob == null || e.p1 == null || e.p0 == null) {
        return ItemForest(
            libelle, null,
            "Pas encore assez de données : ${e.n1} jour(s) concerné(s), ${e.n0} autre(s) " +
                "(${Niveau.PISTE.minParGroupe} de chaque au minimum).",
        )
    }
    val detail = "${pourcent(e.p1)} des jours contre ${pourcent(e.p0)} ${e.reference} · " +
        texteNiveau(prob, e.niveau, e.niveauInverse, "ça l'augmente", "ça le réduise")
    return ItemForest(libelle, ecart.fois(100.0), detail, Liens.annonce(e.niveau, false) || Liens.annonce(e.niveauInverse, true))
}

private val nomsJours = mapOf(
    DayOfWeek.MONDAY to "lundi", DayOfWeek.TUESDAY to "mardi", DayOfWeek.WEDNESDAY to "mercredi",
    DayOfWeek.THURSDAY to "jeudi", DayOfWeek.FRIDAY to "vendredi", DayOfWeek.SATURDAY to "samedi",
    DayOfWeek.SUNDAY to "dimanche",
)

private fun itemJour(j: JourType): ItemForest {
    val veille = nomsJours.getValue(j.jour.minus(1))
    val libelle = "Pesée du ${nomsJours.getValue(j.jour)} (après le $veille)"
    val e = j.ecart ?: return ItemForest(libelle, null, "${j.n} pesée(s) : il en faut ${SemaineType.MIN_PAR_JOUR}.")
    return ItemForest(
        libelle, e, "${signe(e.estimation, 1, "kg")} en moyenne (${ic(e, 1, "kg")}) · ${j.n} pesées",
        net = e.bas > 0 || e.haut < 0,
    )
}

@Composable
private fun TableauSoirs(soirs: List<SoirType>) {
    // La semaine commence le dimanche, comme le Cap.
    val ordre = listOf(DayOfWeek.SUNDAY) + DayOfWeek.values().filter { it != DayOfWeek.SUNDAY }
    val parJour = soirs.associateBy { it.jour }
    fun case(x: Double?) = x?.let { pourcent(it) } ?: "—"
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth()) {
                listOf("", "Rab", "Alcool", "Dessert", "B.-parents").forEachIndexed { i, t ->
                    Text(t, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(if (i == 0) 1.3f else 1f))
                }
            }
            ordre.forEach { d ->
                val s = parJour[d]
                Row(Modifier.fillMaxWidth()) {
                    Text(nomsJours.getValue(d).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.3f))
                    listOf(s?.rab, s?.alcool, s?.dessert, s?.beauxParents).forEach { v ->
                        Text(case(v), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
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
