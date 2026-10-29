package io.github.dash52000.suivinutri.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.dash52000.suivinutri.data.Affichage
import io.github.dash52000.suivinutri.data.Condition
import io.github.dash52000.suivinutri.data.Configuration
import io.github.dash52000.suivinutri.data.Journal
import io.github.dash52000.suivinutri.data.Moment
import io.github.dash52000.suivinutri.data.QuestionPerso
import io.github.dash52000.suivinutri.data.Questionnaire
import io.github.dash52000.suivinutri.data.TypePerso
import io.github.dash52000.suivinutri.notif.Rappel
import org.json.JSONObject
import java.time.LocalDate

private val libellesConditions = listOf(
    Condition.TOUJOURS to "Tous les soirs",
    Condition.SEANCE to "Jours de séance",
    Condition.SEANCE_MIDI to "Séance le midi",
    Condition.BEAUX_PARENTS to "Soirs chez les beaux-parents",
    Condition.MIDI_PAS_EXTERIEUR to "Midi pas à l'extérieur",
)

@Composable
fun EcranReglages(journal: Journal, config: Configuration, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var actif by remember { mutableStateOf(Rappel.actif(context)) }
    var heure by remember { mutableIntStateOf(Rappel.heure(context)) }
    var minute by remember { mutableIntStateOf(Rappel.minute(context)) }
    var notifsOk by remember { mutableStateOf(Rappel.notifsAutorisees(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    var masquerPesee by remember { mutableStateOf(Affichage.masquerPesee(context)) }

    val demanderNotifs = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifsOk = it
    }
    val exporterJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            // La sauvegarde contient le journal et la configuration des questions.
            val racine = JSONObject(journal.exportJson()).put("configuration", config.versJson())
            message = ecrire(context, uri, racine.toString(2), "Sauvegarde enregistrée.")
        }
    }
    val exporterCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) message = ecrire(context, uri, journal.exportCsv(config.perso()), "CSV enregistré.")
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            message = try {
                val texte = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("fichier illisible")
                val jours = journal.importJson(texte)
                JSONObject(texte).optJSONObject("configuration")?.let { config.importer(it) }
                "$jours jour(s) restauré(s)."
            } catch (e: Exception) {
                "Import impossible : ${e.message}"
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Rappel du soir", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notif quotidienne", modifier = Modifier.weight(1f))
            Switch(
                checked = actif,
                onCheckedChange = {
                    actif = it
                    Rappel.regler(context, it, heure, minute)
                },
            )
        }
        OutlinedButton(
            enabled = actif,
            onClick = {
                TimePickerDialog(
                    context,
                    { _, h, m ->
                        heure = h
                        minute = m
                        Rappel.regler(context, actif, h, m)
                    },
                    heure, minute, true,
                ).show()
            },
        ) { Text("Heure : %02d:%02d".format(heure, minute)) }
        Text(
            "Pas de relance si tu l'ignores, et pas de notif si tu as déjà rempli la journée.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!notifsOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Text("Les notifications sont bloquées pour l'appli.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { demanderNotifs.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text("Autoriser les notifications")
            }
        }

        HorizontalDivider()
        Text("Affichage", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Masquer la pesée du jour")
                Text(
                    "Seule la tendance lissée s'affiche. Les calculs du labo restent identiques.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = masquerPesee,
                onCheckedChange = {
                    masquerPesee = it
                    Affichage.regler(context, it)
                },
            )
        }
        HorizontalDivider()
        SectionQuestions(config)
        HorizontalDivider()

        Text("Sauvegarde", style = MaterialTheme.typography.titleMedium)
        Text(
            "Tes réponses restent sur le téléphone. Exporte de temps en temps une sauvegarde, " +
                "par exemple dans ton Drive : c'est elle qui protège ton historique.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { exporterJson.launch("suivi-nutri-${LocalDate.now()}.json") }) {
            Text("Exporter une sauvegarde (JSON)")
        }
        OutlinedButton(onClick = { exporterCsv.launch("suivi-nutri-${LocalDate.now()}.csv") }) {
            Text("Exporter pour R (CSV)")
        }
        OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }) {
            Text("Restaurer une sauvegarde")
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SectionQuestions(config: Configuration) {
    var enPause by remember { mutableStateOf(config.enPause()) }
    var perso by remember { mutableStateOf(config.perso()) }
    var formulaireOuvert by remember { mutableStateOf(false) }
    var aSupprimer by remember { mutableStateOf<QuestionPerso?>(null) }

    fun basculer(id: String, active: Boolean) {
        config.mettreEnPause(id, !active)
        enPause = config.enPause()
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Questions du soir", style = MaterialTheme.typography.titleMedium)
        Text(
            "Mets en pause ce qui ne te sert plus. Les réponses déjà données restent dans l'historique et le labo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Questionnaire.questions.forEach { q ->
            LigneQuestion(
                libelle = "${q.moment.libelle} · ${q.libelle}" + if (q.ressenti) " (ressenti)" else "",
                active = q.id !in enPause,
                verrouillee = q.id == Questionnaire.ID_DINER,
                onBasculer = { basculer(q.id, it) },
            )
        }

        Text("Tes questions", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        if (perso.isEmpty()) {
            Text(
                "Aucune pour l'instant.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        perso.forEach { q ->
            LigneQuestion(
                libelle = "${q.moment.libelle} · ${q.libelle}",
                active = q.id !in enPause,
                verrouillee = false,
                onBasculer = { basculer(q.id, it) },
                onSupprimer = { aSupprimer = q },
            )
        }
        if (formulaireOuvert) {
            FormulaireQuestion(
                onAjouter = { libelle, type, tenuSi, condition, moment ->
                    config.ajouter(libelle, type, tenuSi, condition, moment)
                    perso = config.perso()
                    formulaireOuvert = false
                },
                onAnnuler = { formulaireOuvert = false },
            )
        } else {
            TextButton(onClick = { formulaireOuvert = true }) { Text("+ Ajouter une question") }
        }
    }

    aSupprimer?.let { q ->
        AlertDialog(
            onDismissRequest = { aSupprimer = null },
            title = { Text("Supprimer la question ?") },
            text = {
                Text("« ${q.libelle} » ne sera plus posée. Les réponses déjà données restent dans tes sauvegardes.")
            },
            confirmButton = {
                TextButton(onClick = {
                    config.supprimer(q.id)
                    perso = config.perso()
                    enPause = config.enPause()
                    aSupprimer = null
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { aSupprimer = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun LigneQuestion(
    libelle: String,
    active: Boolean,
    verrouillee: Boolean,
    onBasculer: (Boolean) -> Unit,
    onSupprimer: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            libelle,
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onSupprimer != null) TextButton(onClick = onSupprimer) { Text("Supprimer") }
        Switch(checked = active, enabled = !verrouillee, onCheckedChange = onBasculer)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormulaireQuestion(
    onAjouter: (String, TypePerso, String, Condition, Moment) -> Unit,
    onAnnuler: () -> Unit,
) {
    var libelle by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TypePerso.OUI_NON) }
    var tenuSi by remember { mutableStateOf("oui") }
    var condition by remember { mutableStateOf(Condition.TOUJOURS) }
    var moment by remember { mutableStateOf(Moment.SOIR) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = libelle,
                onValueChange = { libelle = it },
                label = { Text("Question") },
                placeholder = { Text("Ex. : Grignoté après le dîner ?") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Réponses possibles", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TypePerso.entries.forEach { t ->
                    FilterChip(selected = t == type, onClick = { type = t }, label = { Text(t.libelle) })
                }
            }
            Text("Réflexe tenu si la réponse est", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tenuSi == "oui", onClick = { tenuSi = "oui" }, label = { Text("Oui") })
                FilterChip(selected = tenuSi == "non", onClick = { tenuSi = "non" }, label = { Text("Non") })
            }
            Text("Moment de la journée", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Moment.entries.forEach { m ->
                    FilterChip(selected = m == moment, onClick = { moment = m }, label = { Text(m.libelle) })
                }
            }
            Text("Quand la poser", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                libellesConditions.forEach { (c, l) ->
                    FilterChip(selected = c == condition, onClick = { condition = c }, label = { Text(l) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = libelle.isNotBlank(),
                    onClick = { onAjouter(libelle, type, tenuSi, condition, moment) },
                ) { Text("Ajouter") }
                TextButton(onClick = onAnnuler) { Text("Annuler") }
            }
        }
    }
}

private fun ecrire(context: Context, uri: Uri, texte: String, succes: String): String =
    try {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(texte.toByteArray()) }
            ?: error("fichier inaccessible")
        succes
    } catch (e: Exception) {
        "Échec : ${e.message}"
    }
