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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import io.github.dash52000.suivinutri.data.Journal
import io.github.dash52000.suivinutri.notif.Rappel
import java.time.LocalDate

@Composable
fun EcranReglages(journal: Journal, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var actif by remember { mutableStateOf(Rappel.actif(context)) }
    var heure by remember { mutableIntStateOf(Rappel.heure(context)) }
    var minute by remember { mutableIntStateOf(Rappel.minute(context)) }
    var notifsOk by remember { mutableStateOf(Rappel.notifsAutorisees(context)) }
    var message by remember { mutableStateOf<String?>(null) }

    val demanderNotifs = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifsOk = it
    }
    val exporterJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) message = ecrire(context, uri, journal.exportJson(), "Sauvegarde enregistrée.")
    }
    val exporterCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) message = ecrire(context, uri, journal.exportCsv(), "CSV enregistré.")
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            message = try {
                val texte = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("fichier illisible")
                "${journal.importJson(texte)} jour(s) restauré(s)."
            } catch (e: Exception) {
                "Import impossible : ${e.message}"
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
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

private fun ecrire(context: Context, uri: Uri, texte: String, succes: String): String =
    try {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(texte.toByteArray()) }
            ?: error("fichier inaccessible")
        succes
    } catch (e: Exception) {
        "Échec : ${e.message}"
    }
