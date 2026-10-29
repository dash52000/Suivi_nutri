package io.github.dash52000.suivinutri

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
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
import io.github.dash52000.suivinutri.health.DiagnosticCalories
import io.github.dash52000.suivinutri.health.HealthData
import io.github.dash52000.suivinutri.health.Journee
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Ce que l'écran principal doit afficher. */
sealed interface Etat {
    data object Chargement : Etat
    data class Indisponible(val majRequise: Boolean) : Etat
    data class Autorisation(val permissions: Set<String>, val dejaRefuse: Boolean) : Etat
    data class Donnees(
        val jours: List<Journee>,
        val diagnostic: DiagnosticCalories,
        val historique: Boolean,
    ) : Etat
    data class Erreur(val message: String) : Etat
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val health = HealthData(this)
        setContent {
            SuiviNutriTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    App(health)
                }
            }
        }
    }
}

private suspend fun charger(context: Context, health: HealthData, demandeFaite: Boolean): Etat {
    when (HealthData.statut(context)) {
        HealthConnectClient.SDK_UNAVAILABLE -> return Etat.Indisponible(majRequise = false)
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> return Etat.Indisponible(majRequise = true)
    }
    val accordees = health.permissionsAccordees()
    if (!accordees.containsAll(HealthData.PERMISSIONS_BASE)) {
        return Etat.Autorisation(health.permissionsADemander(), dejaRefuse = demandeFaite)
    }
    val aujourdHui = LocalDate.now()
    return Etat.Donnees(
        jours = health.journees(aujourdHui.minusDays(13), aujourdHui),
        diagnostic = health.diagnosticCalories(aujourdHui.minusDays(1)),
        historique = HealthData.PERMISSION_HISTORIQUE in accordees,
    )
}

@Composable
fun App(health: HealthData) {
    val context = LocalContext.current
    var etat by remember { mutableStateOf<Etat>(Etat.Chargement) }
    var rafraichir by remember { mutableIntStateOf(0) }
    var demandeFaite by remember { mutableStateOf(false) }

    val demanderAcces = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        demandeFaite = true
        rafraichir++
    }

    LaunchedEffect(rafraichir) {
        etat = Etat.Chargement
        etat = try {
            charger(context, health, demandeFaite)
        } catch (e: Exception) {
            Etat.Erreur(e.message ?: e.toString())
        }
    }

    Column(
        modifier = Modifier
            .safeDrawingPadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Suivi nutri", style = MaterialTheme.typography.headlineSmall)
            Text(
                "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (val e = etat) {
            Etat.Chargement -> Row(Modifier.padding(top = 32.dp)) { CircularProgressIndicator() }

            is Etat.Indisponible -> Message(
                titre = if (e.majRequise) "Health Connect doit être mis à jour" else "Health Connect introuvable",
                texte = "L'appli lit ton poids, tes calories, tes activités et ton sommeil via Health Connect.",
                bouton = if (e.majRequise) "Mettre à jour" else null,
                action = { ouvrirHealthConnectSurLeStore(context) },
            )

            is Etat.Autorisation -> Message(
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

            is Etat.Erreur -> Message(
                titre = "Erreur de lecture",
                texte = e.message,
                bouton = "Réessayer",
                action = { rafraichir++ },
            )

            is Etat.Donnees -> EcranDonnees(e, onActualiser = { rafraichir++ })
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
private fun EcranDonnees(etat: Etat.Donnees, onActualiser: () -> Unit) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
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
        items(etat.jours, key = { it.date.toString() }) { CarteJour(it) }
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
            Text(
                "À comparer avec les calories totales d'hier dans Garmin Connect : " +
                    "le total agrégé doit coller, la somme naïve peut être gonflée.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CarteJour(j: Journee) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                j.date.format(formatJour).replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                listOf(
                    j.poidsKg?.let { String.format(Locale.FRENCH, "%.1f kg", it) } ?: "Pas de pesée",
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

@Composable
fun SuiviNutriTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
