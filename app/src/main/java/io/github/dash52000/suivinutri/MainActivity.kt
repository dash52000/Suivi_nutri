package io.github.dash52000.suivinutri

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.dash52000.suivinutri.data.Configuration
import io.github.dash52000.suivinutri.data.Journal
import io.github.dash52000.suivinutri.data.Objectif
import io.github.dash52000.suivinutri.data.Usage
import io.github.dash52000.suivinutri.health.HealthData
import io.github.dash52000.suivinutri.notif.Rappel
import io.github.dash52000.suivinutri.notif.Signal
import io.github.dash52000.suivinutri.ui.EcranCap
import io.github.dash52000.suivinutri.ui.EcranDonnees
import io.github.dash52000.suivinutri.ui.EcranLabo
import io.github.dash52000.suivinutri.ui.EcranReglages
import io.github.dash52000.suivinutri.ui.EcranSoir

class MainActivity : ComponentActivity() {

    /** Onglet demandé par une notif (le signal de la semaine ouvre Cap). */
    private val ongletDemande = mutableStateOf<Onglet?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        noterOuvertureParNotif(intent)
    }

    override fun onStart() {
        super.onStart()
        Usage.log(this, "premier_plan")
    }

    private fun noterOuvertureParNotif(intent: Intent?) {
        if (intent?.getBooleanExtra(Rappel.EXTRA_DEPUIS_NOTIF, false) == true) {
            intent.removeExtra(Rappel.EXTRA_DEPUIS_NOTIF)
            Usage.log(this, "ouverture_notif")
        }
        if (intent?.getBooleanExtra(Signal.EXTRA_DEPUIS_SIGNAL, false) == true) {
            intent.removeExtra(Signal.EXTRA_DEPUIS_SIGNAL)
            Usage.log(this, "ouverture_signal")
        }
        val nom = intent?.getStringExtra(Signal.EXTRA_ONGLET)
        if (intent != null && nom != null) {
            intent.removeExtra(Signal.EXTRA_ONGLET)
            Onglet.entries.firstOrNull { it.name == nom }?.let { ongletDemande.value = it }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        noterOuvertureParNotif(intent)
        Rappel.creerCanal(this)
        Rappel.programmer(this)
        Signal.creerCanal(this)
        val health = HealthData(this)
        val journal = Journal(this)
        val config = Configuration(this)
        val objectif = Objectif(this)
        Signal.programmer(this)
        setContent {
            SuiviNutriTheme {
                App(health, journal, config, objectif, ongletDemande)
            }
        }
    }
}

enum class Onglet(val libelle: String, val icone: ImageVector) {
    SOIR("Ce soir", Icons.Filled.Edit),
    CAP("Cap", Icons.Filled.Star),
    DONNEES("Données", Icons.Filled.DateRange),
    LABO("Labo", Icons.Filled.Build),
    REGLAGES("Réglages", Icons.Filled.Settings),
}

@Composable
fun App(
    health: HealthData,
    journal: Journal,
    config: Configuration,
    objectif: Objectif,
    ongletDemande: MutableState<Onglet?>,
) {
    val context = LocalContext.current
    var onglet by rememberSaveable { mutableStateOf(Onglet.SOIR) }
    LaunchedEffect(onglet) { Usage.log(context, "onglet", onglet.name.lowercase()) }
    LaunchedEffect(ongletDemande.value) {
        ongletDemande.value?.let {
            onglet = it
            ongletDemande.value = null
        }
    }

    // Demande l'autorisation des notifs une seule fois, au premier lancement.
    val demanderNotifs = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("reglages", Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !Rappel.notifsAutorisees(context) &&
            !prefs.getBoolean("notifs_demandees", false)
        ) {
            prefs.edit().putBoolean("notifs_demandees", true).apply()
            demanderNotifs.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Suivi nutri", style = MaterialTheme.typography.titleLarge)
                Text(
                    "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        bottomBar = {
            NavigationBar {
                Onglet.entries.forEach { o ->
                    NavigationBarItem(
                        selected = onglet == o,
                        onClick = { onglet = o },
                        icon = { Icon(o.icone, contentDescription = null) },
                        label = { Text(o.libelle) },
                    )
                }
            }
        },
    ) { marges ->
        val m = Modifier.padding(marges)
        when (onglet) {
            Onglet.SOIR -> EcranSoir(journal, config, health, m)
            Onglet.CAP -> EcranCap(health, journal, config, objectif, m)
            Onglet.DONNEES -> EcranDonnees(health, journal, config, m)
            Onglet.LABO -> EcranLabo(health, journal, config, m)
            Onglet.REGLAGES -> EcranReglages(journal, config, health, objectif, m)
        }
    }
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
