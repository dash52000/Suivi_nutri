package io.github.dash52000.suivinutri.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.dash52000.suivinutri.data.Alcool
import io.github.dash52000.suivinutri.data.Boisson
import io.github.dash52000.suivinutri.data.Condition
import io.github.dash52000.suivinutri.data.EntreeJour
import io.github.dash52000.suivinutri.data.Journal
import io.github.dash52000.suivinutri.data.Question
import io.github.dash52000.suivinutri.data.Questionnaire
import io.github.dash52000.suivinutri.data.StyleBoisson
import io.github.dash52000.suivinutri.health.HealthData
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val formatDate = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

private fun libelleDate(date: LocalDate): String {
    val aujourdHui = LocalDate.now()
    return when (date) {
        aujourdHui -> "Ce soir"
        aujourdHui.minusDays(1) -> "Hier"
        else -> date.format(formatDate).replaceFirstChar { it.uppercase() }
    }
}

private fun fmt(x: Double, decimales: Int): String =
    String.format(Locale.FRENCH, "%.${decimales}f", x)

private fun fmtDegre(d: Double): String = if (d % 1.0 == 0.0) fmt(d, 0) else fmt(d, 1)

@Composable
fun EcranSoir(journal: Journal, health: HealthData, modifier: Modifier = Modifier) {
    var date by remember { mutableStateOf(LocalDate.now()) }
    var entree by remember(date) { mutableStateOf(journal.entree(date)) }
    var seance by remember(date) { mutableStateOf<Boolean?>(null) }
    var seanceMidi by remember(date) { mutableStateOf<Boolean?>(null) }
    var ressentisOuverts by remember { mutableStateOf(false) }

    // Séances du jour, pour n'afficher que les questions utiles. En cas d'échec : on affiche tout.
    LaunchedEffect(date) {
        try {
            val activites = health.journees(date, date).firstOrNull()?.activites.orEmpty()
            seance = activites.isNotEmpty()
            seanceMidi = activites.any { it.debut.hour in 10..14 }
        } catch (_: Exception) {
            seance = null
            seanceMidi = null
        }
    }

    fun mettreAJour(nouvelle: EntreeJour) {
        entree = nouvelle
        journal.enregistrer(nouvelle)
    }

    // Retaper la réponse choisie l'efface : retour à « pas de réponse ».
    fun repondre(id: String, code: String) {
        val reponses = entree.reponses.toMutableMap()
        if (reponses[id] == code) reponses.remove(id) else reponses[id] = code
        if (id == Questionnaire.ID_DINER && reponses[id] != Questionnaire.DINER_BEAUX_PARENTS) {
            Questionnaire.idsBeauxParents.forEach { reponses.remove(it) }
        }
        val boissons = if (id == Questionnaire.ID_ALCOOL && reponses[id] != "oui") emptyList() else entree.boissons
        mettreAJour(entree.copy(reponses = reponses, boissons = boissons))
    }

    fun visible(q: Question): Boolean = when (q.condition) {
        Condition.TOUJOURS -> true
        Condition.SEANCE -> seance != false
        Condition.SEANCE_MIDI -> seanceMidi != false
        Condition.BEAUX_PARENTS -> entree.reponses[Questionnaire.ID_DINER] == Questionnaire.DINER_BEAUX_PARENTS
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { date = date.minusDays(1) }) {
                Text("‹", style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                libelleDate(date),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { date = date.plusDays(1) }, enabled = date.isBefore(LocalDate.now())) {
                Text("›", style = MaterialTheme.typography.headlineSmall)
            }
        }
        Text(
            "Chaque tap est enregistré. Retape une réponse pour l'effacer : une question sans réponse ne compte pas.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Questionnaire.questions.filter { !it.ressenti && visible(it) }.forEach { q ->
            BlocQuestion(q, entree.reponses[q.id]) { repondre(q.id, it) }
        }

        BlocAlcool(
            entree = entree,
            onRepondre = { repondre(Questionnaire.ID_ALCOOL, it) },
            onBoissons = { mettreAJour(entree.copy(boissons = it)) },
        )

        TextButton(onClick = { ressentisOuverts = !ressentisOuverts }) {
            Text(if (ressentisOuverts) "Masquer les ressentis" else "Ressentis (en option)")
        }
        if (ressentisOuverts) {
            Questionnaire.questions.filter { it.ressenti && visible(it) }.forEach { q ->
                BlocQuestion(q, entree.reponses[q.id]) { repondre(q.id, it) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlocQuestion(q: Question, reponse: String?, onChoix: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(q.libelle, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            q.options.forEach { o ->
                FilterChip(
                    selected = reponse == o.code,
                    onClick = { onChoix(o.code) },
                    label = { Text(o.libelle) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlocAlcool(
    entree: EntreeJour,
    onRepondre: (String) -> Unit,
    onBoissons: (List<Boisson>) -> Unit,
) {
    val reponse = entree.reponses[Questionnaire.ID_ALCOOL]
    var formulaireOuvert by remember(entree.date) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Alcool aujourd'hui", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = reponse == "aucun",
                onClick = { onRepondre("aucun") },
                label = { Text("Aucun") },
            )
            FilterChip(
                selected = reponse == "oui",
                onClick = {
                    formulaireOuvert = reponse != "oui"
                    onRepondre("oui")
                },
                label = { Text("J'en ai bu") },
            )
        }

        if (reponse == "oui") {
            entree.boissons.forEachIndexed { i, b ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${b.libelle} · ${fmt(b.cl, 0)} cl à ${fmtDegre(b.degre)} % → ${fmt(b.unites, 1)} u",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onBoissons(entree.boissons.filterIndexed { j, _ -> j != i }) }) {
                        Text("Retirer")
                    }
                }
            }
            if (entree.boissons.isNotEmpty()) {
                Text(
                    "Total : ${fmt(entree.unitesAlcool, 1)} unités",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (formulaireOuvert) {
                FormulaireBoisson(
                    onAjouter = {
                        onBoissons(entree.boissons + it)
                        formulaireOuvert = false
                    },
                    onAnnuler = { formulaireOuvert = false },
                )
            } else {
                TextButton(onClick = { formulaireOuvert = true }) { Text("+ Ajouter une boisson") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormulaireBoisson(onAjouter: (Boisson) -> Unit, onAnnuler: () -> Unit) {
    var style by remember { mutableStateOf<StyleBoisson>(Alcool.styles.first()) }
    var cl by remember { mutableDoubleStateOf(style.clParDefaut.toDouble()) }
    var degreTexte by remember { mutableStateOf(fmtDegre(style.degre)) }
    val degre = degreTexte.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it < 100 }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Style", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Alcool.styles.forEach { s ->
                    FilterChip(
                        selected = s == style,
                        onClick = {
                            style = s
                            cl = s.clParDefaut.toDouble()
                            degreTexte = fmtDegre(s.degre)
                        },
                        label = { Text(s.libelle) },
                    )
                }
            }
            Text("Format", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Alcool.formatsCl.forEach { f ->
                    FilterChip(
                        selected = cl == f.toDouble(),
                        onClick = { cl = f.toDouble() },
                        label = { Text("$f cl") },
                    )
                }
            }
            OutlinedTextField(
                value = degreTexte,
                onValueChange = { degreTexte = it },
                label = { Text("Degré (%)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Text(
                if (degre != null) "= ${fmt(Alcool.unites(cl, degre), 1)} unité(s)" else "Degré invalide",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = degre != null,
                    onClick = { degre?.let { onAjouter(Boisson(style.code, cl, it)) } },
                ) { Text("Ajouter") }
                TextButton(onClick = onAnnuler) { Text("Annuler") }
            }
        }
    }
}
