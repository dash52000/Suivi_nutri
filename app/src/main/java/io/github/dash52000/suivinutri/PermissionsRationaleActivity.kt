package io.github.dash52000.suivinutri

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Écran de confidentialité exigé par Health Connect.
 * Sans lui, la demande d'accès ne s'affiche pas.
 */
class PermissionsRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SuiviNutriTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .safeDrawingPadding()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Confidentialité", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Suivi nutri lit dans Health Connect ton poids, tes calories dépensées, " +
                                "tes activités et ton sommeil.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "Ces données servent uniquement à les afficher et à les analyser sur ce " +
                                "téléphone. L'appli n'écrit rien dans Health Connect et n'envoie aucune " +
                                "donnée de santé ailleurs.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = { finish() }) { Text("Compris") }
                    }
                }
            }
        }
    }
}
