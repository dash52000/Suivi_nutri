package io.github.dash52000.suivinutri

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Compteur d'ouvertures, stocké sur le téléphone.
        // Sert à vérifier qu'une mise à jour s'installe sans effacer les données.
        val prefs = getSharedPreferences("test_maj", Context.MODE_PRIVATE)
        if (savedInstanceState == null) {
            prefs.edit().putInt("ouvertures", prefs.getInt("ouvertures", 0) + 1).apply()
        }
        val ouvertures = prefs.getInt("ouvertures", 1)

        setContent {
            SuiviNutriTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EcranTest(version = BuildConfig.VERSION_NAME, ouvertures = ouvertures)
                }
            }
        }
    }
}

@Composable
fun EcranTest(version: String, ouvertures: Int) {
    Column(
        modifier = Modifier
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Suivi nutri", style = MaterialTheme.typography.headlineMedium)
        Text("Version $version", style = MaterialTheme.typography.titleMedium)
        Text("Ouvertures de l'appli : $ouvertures", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Test de mise à jour : installe la version suivante par-dessus celle-ci, " +
                "sans désinstaller. Si le compteur continue au lieu de repartir à 1, " +
                "tes données survivent aux mises à jour.",
            style = MaterialTheme.typography.bodyMedium
        )
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
