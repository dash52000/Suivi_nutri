package io.github.dash52000.suivinutri.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import io.github.dash52000.suivinutri.data.Affichage
import io.github.dash52000.suivinutri.health.DiagnosticCalories
import io.github.dash52000.suivinutri.health.HealthData
import io.github.dash52000.suivinutri.health.Journee
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Ce que l'écran Données doit afficher. */
sealed interface EtatDonnees {
    data object Chargement : EtatDonnees
    data class Indisponible(val majRequise: Boolean) : EtatDonnees
    data class Autorisation(val permissions: Set<String>, val dejaRefuse: Boolean) : EtatDonnees
    data class Donnees(
        val jours: List<Journee>,
        val diagnostic: DiagnosticCalories,
        val historique: Boolean,
    ) : EtatDonnees
    data class Erreur(val message: String) : EtatDonnees
}

private suspend fun charger(context: Context, health: HealthData, demandeFaite: Boolean): EtatDonnees {
    when (HealthData.statut(context)) {
        HealthConnectClient.SDK_UNAVAILABLE -> return EtatDonnees.Indisponible(majRequise = false)
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
            return EtatDonnees.Indisponible(majRequise = true)
    }
    val accordees = health.permissionsAccordees()
    if (!accordees.containsAll(HealthData.PERMISSIONS_BASE)) {
        return EtatDonnees.Autorisation(health.permissionsADemander(), dejaRefuse = demandeFaite)
    }
    val aujourdHui = LocalDate.now()
    return EtatDonnees.Donnees(
        jours = health.journees(aujourdHui.minusDays(13), aujourdHui),
        diagnostic = health.diagnosticCalories(aujourdHui.minusDays(1)),
        historique = HealthData.PERMISSION_HISTORIQUE in accordees,
    )
}

@Composable
fun EcranDonnees(health: HealthData, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var etat by remember { mutableStateOf<EtatDonnees>(EtatDonnees.Chargement) }
    var rafraichir by remember { mutableIntStateOf(0) }
    var demandeFaite by remember { mutableStateOf(false) }

    val demanderAcces = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        demandeFaite = true
        rafraichir++
    }

    LaunchedEffect(rafraichir) {
        etat = EtatDonnees.Chargement
        etat = try {
            charger(context, health, demandeFaite)
        } catch (e: Exception) {
            EtatDonnees.Erreur(e.message ?: e.toString())
        }
    }

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        when (val e = etat) {
            EtatDonnees.Chargement -> Row(Modifier.padding(top = 32.dp)) { CircularProgressIndicator() }

            is EtatDonnees.Indisponible -> Message(
                titre = if (e.majRequise) "Health Connect doit être mis à jour" else "Health Connect introuvable",
                texte = "L'appli lit ton poids, tes calories, tes activités et ton sommeil via Health Connect.",
                bouton = if (e.majRequise) "Mettre à jour" else null,
                action = { ouvrirHealthConnectSurLeStore(context) },
            )

            is EtatDonnees.Autorisation -> Message(
                titre = "Accès à Health Connect",
                texte = buildString {
                    append("L'appli a besoin de lire ton poids, tes calories dépensées, tes activités et ton sommeil, ")
                    append("envoyés par Garmin. Rien ne quitte le téléphone.")
                    if (e.dejaRefuse) {
                        append("\n\nUne partie des accès manque encore. Si la fenêtre ne s'ouvre plus, ")
                        append("accorde-les dans Paramètres > Health Connect > Autorisations des applis > Suivi nutri.")
                    }
                },
                bouton = "Autoriser l'accès",
                action = { demanderAcces.launch(e.permissions) },
            )

            is EtatDonnees.Erreur -> Message(
                titre = "Erreur de lecture",
                texte = e.message,
                bouton = "Réessayer",
                action = { rafraichir++ },
            )

            is EtatDonnees.Donnees -> ListeDonnees(
                e,
                masquerPesee = Affichage.masquerPesee(context),
                onActualiser = { rafraichir++ },
            )
        }
    }
}

@Composable
private fun Message(titre: String, texte: String, bouton: String?, action: () -> Unit) {
    Column(
        modifier = Modifier.padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(titre, style = MaterialTheme.typography.titleLarge)
        Text(texte, style = MaterialTheme.typography.bodyLarge)
        if (bouton != null) Button(onClick = action) { Text(bouton) }
    }
}

@Composable
private fun ListeDonnees(etat: EtatDonnees.Donnees, masquerPesee: Boolean, onActualiser: () -> Unit) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
    ) {
        item {
            Text(
                if (etat.historique) "Connecté à Health Connect, historique complet accessible."
                else "Connecté à Health Connect. Historique limité aux 30 jours avant l'autorisation.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { CarteDiagnostic(etat.diagnostic) }
        item {
            Text(
                "14 derniers jours",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(etat.jours, key = { it.date.toString() }) { CarteJour(it, masquerPesee) }
        item {
            OutlinedButton(onClick = onActualiser, modifier = Modifier.padding(top = 8.dp)) {
                Text("Actualiser")
            }
        }
    }
}

private val formatJour = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)
private val formatHeure = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)

private fun kcal(x: Double?): String =
    x?.let { String.format(Locale.FRENCH, "%,.0f kcal", it) } ?: "—"

private fun dureeHeures(min: Long): String = "${min / 60} h ${"%02d".format(min % 60)}"

@Composable
private fun CarteDiagnostic(d: DiagnosticCalories) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Test du double comptage — hier", style = MaterialTheme.typography.titleSmall)
            Text("Total agrégé : ${kcal(d.totalAgrege)}")
            Text("Enregistrements bruts : ${d.nbEnregistrements}, somme naïve : ${kcal(d.sommeNaive)}")
            Text(
                "Sources : " + d.sources.ifEmpty { listOf("aucune") }.joinToString(),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CarteJour(j: Journee, masquerPesee: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                j.date.format(formatJour).replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                listOf(
                    when {
                        j.poidsKg == null -> "Pas de pesée"
                        masquerPesee -> "Pesée enregistrée"
                        else -> String.format(Locale.FRENCH, "%.1f kg", j.poidsKg)
                    },
                    kcal(j.kcalTotal),
                    j.sommeilMin?.let { "Sommeil ${dureeHeures(it)}" } ?: "Sommeil —",
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
            )
            j.activites.forEach { a ->
                Text(
                    "${a.debut.format(formatHeure)}  ${a.type} · ${a.dureeMin} min",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun ouvrirHealthConnectSurLeStore(context: Context) {
    val uri = Uri.parse(
        "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"
    )
    context.startActivity(
        Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.android.vending")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
}
