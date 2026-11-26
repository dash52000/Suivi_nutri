package io.github.dash52000.suivinutri.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.dash52000.suivinutri.data.Usage
import io.github.dash52000.suivinutri.llm.Chaine
import io.github.dash52000.suivinutri.llm.ChaineReglee
import io.github.dash52000.suivinutri.llm.CoffreCles
import io.github.dash52000.suivinutri.llm.Fournisseur
import io.github.dash52000.suivinutri.llm.Fournisseurs
import io.github.dash52000.suivinutri.llm.HttpTransport
import io.github.dash52000.suivinutri.llm.Issue
import io.github.dash52000.suivinutri.llm.ResultatTest
import io.github.dash52000.suivinutri.llm.Schema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Réglages de la lecture des phrases (lot v1.18 B, étape 1) : clés Gemini et Groq, chaîne de modèles, « Tester les
 * modèles ». Une clé n'est jamais réaffichée après saisie : seulement « clé enregistrée · •••• 1234 ».
 */
@Composable
internal fun SectionLectureIa() {
    val context = LocalContext.current
    val coffre = remember { CoffreCles(context) }
    var version by remember { mutableStateOf(0) } // relit le coffre après un changement
    var chaine by remember { mutableStateOf(ChaineReglee.lire(context)) }
    var editionChaine by remember { mutableStateOf<String?>(null) }
    var resultats by remember { mutableStateOf<List<ResultatTest>?>(null) }
    var testEnCours by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Lecture des phrases du repas", style = MaterialTheme.typography.titleMedium)
        Text(
            "Facultatif. Sans clé, rien ne change dans l'appli. Les clés restent chiffrées sur ce téléphone : ni dans " +
                "la sauvegarde, ni dans l'export ; à ressaisir après un changement de téléphone. Seuls le texte de la " +
                "phrase et son moment sont envoyés (offre gratuite : Google peut les relire).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Fournisseur.entries.forEach { f ->
            LigneCle(coffre, f, version) {
                version++
                resultats = null
            }
        }

        Text("Chaîne de modèles", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        val enEdition = editionChaine
        if (enEdition == null) {
            Text(
                chaine.mapIndexed { i, m -> "${i + 1}. ${m.modele}" + if (m.fournisseur == Fournisseur.GROQ) " (Groq)" else "" }
                    .joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { editionChaine = Fournisseurs.chaineVersTexte(chaine) }) { Text("Modifier la chaîne") }
        } else {
            OutlinedTextField(
                value = enEdition,
                onValueChange = { editionChaine = it },
                label = { Text("Un modèle par ligne, dans l'ordre") },
                supportingText = { Text("« gemini-… » va chez Google, le reste chez Groq.") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    chaine = ChaineReglee.regler(context, enEdition)
                    Usage.log(context, "reglage", "chaine_llm=" + chaine.joinToString(">") { it.modele })
                    editionChaine = null
                    resultats = null
                }) { Text("Enregistrer") }
                TextButton(onClick = { editionChaine = Fournisseurs.chaineVersTexte(Fournisseurs.CHAINE_DEFAUT) }) {
                    Text("Chaîne de départ")
                }
                TextButton(onClick = { editionChaine = null }) { Text("Annuler") }
            }
        }

        val aucuneCle = remember(version) { coffre.aucune() }
        OutlinedButton(
            enabled = !aucuneCle && !testEnCours && editionChaine == null,
            onClick = {
                testEnCours = true
                resultats = null
                val maillons = chaine
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Chaine(HttpTransport, coffre).tester(maillons) }
                    r.forEach { Usage.log(context, "llm_test", it.tentative.pourJournal()) }
                    resultats = r
                    testEnCours = false
                }
            },
        ) { Text(if (testEnCours) "Test en cours…" else "Tester les modèles") }
        Text(
            "Envoie la phrase fixe « ${Schema.PHRASE_TEST} » à chaque modèle, une fois, jamais une de tes phrases.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        resultats?.let { liste ->
            val nbOk = liste.count { it.repond }
            Text(
                "$nbOk modèle(s) sur ${liste.size} répondent avec un JSON valide.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            liste.forEach { r -> Text(ligneTest(r), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun LigneCle(coffre: CoffreCles, fournisseur: Fournisseur, version: Int, onChange: () -> Unit) {
    val context = LocalContext.current
    val fin = remember(version) { coffre.fin(fournisseur) }
    var saisie by remember { mutableStateOf("") }
    var remplacer by remember { mutableStateOf(false) }
    var confirmerEffacer by remember { mutableStateOf(false) }
    var erreur by remember { mutableStateOf<String?>(null) }

    if (fin != null && !remplacer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${fournisseur.libelle} : clé enregistrée · •••• $fin", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { remplacer = true }) { Text("Remplacer") }
            TextButton(onClick = { confirmerEffacer = true }) { Text("Effacer") }
        }
    } else {
        OutlinedTextField(
            value = saisie,
            onValueChange = { saisie = it; erreur = null },
            label = { Text("Clé ${fournisseur.libelle}") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = erreur != null,
            supportingText = erreur?.let { message -> @Composable { Text(message) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = saisie.isNotBlank(),
                onClick = {
                    if (coffre.enregistrer(fournisseur, saisie)) {
                        Usage.log(context, "reglage", "cle_llm=${fournisseur.name.lowercase()};enregistree")
                        saisie = ""
                        remplacer = false
                        onChange()
                    } else {
                        erreur = "Le coffre du téléphone a refusé la clé ; rien n'est enregistré."
                    }
                },
            ) { Text("Enregistrer") }
            if (remplacer) TextButton(onClick = { saisie = ""; remplacer = false }) { Text("Annuler") }
        }
    }

    if (confirmerEffacer) {
        AlertDialog(
            onDismissRequest = { confirmerEffacer = false },
            title = { Text("Effacer la clé ${fournisseur.libelle} ?") },
            text = { Text("Elle est retirée de ce téléphone. Il faudra la ressaisir pour utiliser ${fournisseur.libelle}.") },
            confirmButton = {
                TextButton(onClick = {
                    coffre.effacer(fournisseur)
                    Usage.log(context, "reglage", "cle_llm=${fournisseur.name.lowercase()};effacee")
                    confirmerEffacer = false
                    onChange()
                }) { Text("Effacer") }
            },
            dismissButton = { TextButton(onClick = { confirmerEffacer = false }) { Text("Annuler") } },
        )
    }
}

private fun ligneTest(r: ResultatTest): String {
    val t = r.tentative
    val duree = if (t.dureeMs > 0) " · ${nombre(t.dureeMs / 1000.0, 1)} s" else ""
    return if (r.repond) {
        val reel = r.modeleRepondu?.takeIf { it != t.maillon.modele }?.let { " (a répondu : $it)" }.orEmpty()
        "✓ ${t.maillon.modele}$reel$duree"
    } else {
        "✗ ${t.maillon.modele} · ${libelle(t.issue)}${t.codeHttp?.let { " ($it)" }.orEmpty()}$duree"
    }
}

private fun libelle(issue: Issue): String = when (issue) {
    Issue.OK -> "répond"
    Issue.JSON_INVALIDE -> "JSON invalide"
    Issue.SURCHARGE -> "surchargé"
    Issue.QUOTA -> "quota atteint"
    Issue.DELAI -> "délai dépassé"
    Issue.ERREUR_RESEAU -> "coupure réseau"
    Issue.CLE_A_REVOIR -> "clé refusée, à revoir"
    Issue.MODELE_RETIRE -> "modèle retiré"
    Issue.REQUETE_REFUSEE -> "requête refusée"
    Issue.CODE_INATTENDU -> "code inattendu"
    Issue.HORS_LIGNE -> "hors ligne"
    Issue.SANS_CLE -> "pas de clé"
    Issue.FOURNISSEUR_SAUTE -> "sauté"
    Issue.BUDGET_EPUISE -> "temps épuisé"
}
