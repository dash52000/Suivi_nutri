package io.github.dash52000.suivinutri

import android.Manifest
import android.content.Context
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.dash52000.suivinutri.data.Journal
import io.github.dash52000.suivinutri.health.HealthData
import io.github.dash52000.suivinutri.notif.Rappel
import io.github.dash52000.suivinutri.ui.EcranDonnees
import io.github.dash52000.suivinutri.ui.EcranLabo
import io.github.dash52000.suivinutri.ui.EcranReglages
import io.github.dash52000.suivinutri.ui.EcranSoir

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Rappel.creerCanal(this)
        Rappel.programmer(this)
        val health = HealthData(this)
        val journal = Journal(this)
        setContent {
            SuiviNutriTheme {
                App(health, journal)
            }
        }
    }
}

enum class Onglet(val libelle: String, val icone: ImageVector) {
    SOIR("Ce soir", Icons.Filled.Edit),
    DONNEES("Données", Icons.Filled.DateRange),
    LABO("Labo", Icons.Filled.Build),
    REGLAGES("Réglages", Icons.Filled.Settings),
}

@Composable
fun App(health: HealthData, journal: Journal) {
    val context = LocalContext.current
    var onglet by rememberSaveable { mutableStateOf(Onglet.SOIR) }

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
            Onglet.SOIR -> EcranSoir(journal, health, m)
            Onglet.DONNEES -> EcranDonnees(health, m)
            Onglet.LABO -> EcranLabo(health, journal, m)
            Onglet.REGLAGES -> EcranReglages(journal, m)
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
