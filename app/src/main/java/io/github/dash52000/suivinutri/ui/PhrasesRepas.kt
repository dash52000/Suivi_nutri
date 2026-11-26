package io.github.dash52000.suivinutri.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.dash52000.suivinutri.data.Usage
import io.github.dash52000.suivinutri.repas.PhraseRepas
import io.github.dash52000.suivinutri.repas.Quantite
import io.github.dash52000.suivinutri.repas.Reponse
import io.github.dash52000.suivinutri.repas.RepasLu
import io.github.dash52000.suivinutri.repas.ServicesRepas
import io.github.dash52000.suivinutri.repas.VueMoment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import io.github.dash52000.suivinutri.repas.Moment as MomentRepas

private val MOMENTS_REPAS = listOf(MomentRepas.MATIN, MomentRepas.MIDI, MomentRepas.SOIR, MomentRepas.GRIGNOTAGE)

private fun titre(m: MomentRepas): String = when (m) {
    MomentRepas.MATIN -> "Matin"
    MomentRepas.MIDI -> "Midi"
    MomentRepas.SOIR -> "Soir"
    MomentRepas.GRIGNOTAGE -> "Grignotage"
}

private fun libelleTap(r: Reponse): String = when (r) {
    Reponse.RIEN -> "Rien"
    Reponse.COMME_DHAB -> "Comme d'hab"
    Reponse.REFUS -> "Pas maintenant"
    else -> r.code
}

private fun libelleQuantite(q: Quantite): String = when (q) {
    Quantite.PETITE -> "Petite"
    Quantite.NORMALE -> "Normale"
    Quantite.GROSSE -> "Grosse"
}

/**
 * Les quatre champs de phrase de l'écran Ce soir (lot v1.18 B, étape 6, SPEC sections 4 et 9) : Matin, Midi, Soir,
 * Grignotage. Affichée seulement si une clé API est saisie (voir `EcranSoir`). Aucune kcal à l'écran : on montre ce que
 * tu as écrit, si la phrase est lue, et la portion à corriger.
 *
 * Toute la logique est dans `SaisieRepas` (testée) ; ici, seulement l'affichage et la dictée.
 */
@Composable
internal fun SectionPhrases(
    jour: LocalDate,
    reponses: Map<String, String>,
    reponsesVeille: Map<String, String>,
) {
    val context = LocalContext.current
    val saisie = remember { ServicesRepas.saisie(context) }
    val version by ServicesRepas.version.collectAsState()
    var local by remember { mutableIntStateOf(0) }
    var vues by remember(jour) { mutableStateOf<Map<MomentRepas, VueMoment>>(emptyMap()) }

    LaunchedEffect(jour) { Usage.log(context, "phrases_ouvert", "jour=$jour") }

    // À l'ouverture : les moments demandés restés sans réponse passent à « ignoré », les phrases en attente sont relues.
    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.IO) { saisie.fermerAnciens() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Usage.log(context, "repas_erreur", "etape=fermer;type=${e.javaClass.simpleName}")
        }
        ServicesRepas.traiterEnAttente(context)
        local++
    }

    // Relu quand le jour, les taps du questionnaire, une action ou une lecture changent. Lit des fichiers : hors fil principal.
    LaunchedEffect(jour, reponses, reponsesVeille, version, local) {
        try {
            vues = withContext(Dispatchers.IO) {
                MOMENTS_REPAS.associateWith { saisie.vue(jour, it, reponses, reponsesVeille) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Usage.log(context, "repas_erreur", "etape=vue;type=${e.javaClass.simpleName}")
            vues = emptyMap()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Ce que tu as mangé", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            "Une phrase par moment, comme tu la dirais à voix haute. Tu peux la dicter. Rien n'est chiffré ici.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MOMENTS_REPAS.forEach { m ->
            vues[m]?.let { vue -> ChampMoment(jour, m, vue, reponses) { local++ } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChampMoment(
    jour: LocalDate,
    moment: MomentRepas,
    vue: VueMoment,
    reponses: Map<String, String>,
    onChange: () -> Unit,
) {
    val context = LocalContext.current
    val saisie = remember { ServicesRepas.saisie(context) }
    val scope = rememberCoroutineScope()
    val unique = moment != MomentRepas.GRIGNOTAGE
    val phrases = vue.etat.phrases

    var edition by remember(jour, moment) { mutableStateOf(false) } // « Modifier » sur matin, midi, soir
    var ajout by remember(jour, moment) { mutableStateOf(false) } // « + Ajouter » sur grignotage
    var dicte by remember { mutableStateOf(false) }
    var texte by remember(jour, moment, vue.prerempli, edition) {
        mutableStateOf(if (edition) phrases.firstOrNull()?.texte.orEmpty() else vue.prerempli.orEmpty())
    }

    val champVisible = phrases.isEmpty() || (if (unique) edition else ajout)

    // Dictée : le résultat remplit le champ sans rien enregistrer (tu relis, tu corriges, puis tu valides).
    val intentDictee = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
    }
    val dicteeDisponible = remember { intentDictee.resolveActivity(context.packageManager) != null }
    val dictee = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { resultat ->
        dicte = false
        val mots = if (resultat.resultCode == Activity.RESULT_OK) {
            resultat.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        } else {
            null
        }
        if (!mots.isNullOrBlank()) {
            texte = if (texte.isBlank()) mots.trim() else texte.trimEnd() + " " + mots.trim()
            Usage.log(context, "phrase_dictee", "moment=${moment.code}")
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(titre(moment), style = MaterialTheme.typography.titleSmall)
            if (vue.etat.demande) {
                Text("demandé", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            }
        }

        if (!(unique && edition)) {
            phrases.forEach { p ->
                BlocPhrase(
                    phrase = p,
                    lu = vue.lectures[p.id],
                    modifiable = unique,
                    onModifier = { edition = true },
                    onRetirer = {
                        scope.launch {
                            withContext(Dispatchers.IO) { saisie.supprimerPhrase(p.id) }
                            onChange()
                        }
                    },
                    onQuantite = { q ->
                        scope.launch {
                            withContext(Dispatchers.IO) { saisie.choisirQuantite(p.id, q) }
                            onChange()
                        }
                    },
                )
            }
        }

        if (!unique && phrases.isNotEmpty() && !ajout) {
            TextButton(onClick = { ajout = true }) { Text("+ Ajouter") }
        }

        if (champVisible) {
            OutlinedTextField(
                value = texte,
                onValueChange = { texte = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(vue.exemple, style = MaterialTheme.typography.bodyMedium) },
                minLines = 1,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            if (dicte) {
                // Le champ se remplit à la fin de la dictée : l'exemple reste visible pendant que tu parles.
                Text(
                    vue.exemple,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = texte.isNotBlank(),
                    onClick = {
                        val aEnregistrer = texte
                        scope.launch {
                            val phrase = withContext(Dispatchers.IO) {
                                try {
                                    saisie.enregistrerPhrase(jour, moment, aEnregistrer, reponses)
                                } catch (e: Exception) {
                                    Usage.log(context, "repas_erreur", "etape=enregistrer;type=${e.javaClass.simpleName}")
                                    null
                                }
                            }
                            texte = ""
                            edition = false
                            ajout = false
                            if (phrase != null) ServicesRepas.lireEnArriere(context, phrase.id)
                            onChange()
                        }
                    },
                ) { Text(if (texte.trim() == vue.prerempli) "Valider" else "Enregistrer") }
                if (dicteeDisponible) {
                    OutlinedButton(onClick = {
                        dicte = true
                        try {
                            dictee.launch(intentDictee)
                        } catch (_: ActivityNotFoundException) {
                            dicte = false
                        }
                    }) { Text("Dicter") }
                }
                if (phrases.isNotEmpty()) {
                    TextButton(onClick = {
                        edition = false
                        ajout = false
                        texte = ""
                    }) { Text("Annuler") }
                }
            }
        }

        if (vue.boutons.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vue.boutons.forEach { r ->
                    FilterChip(
                        selected = vue.etat.reponse == r,
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { saisie.repondre(jour, moment, r, reponses) }
                                onChange()
                            }
                        },
                        label = { Text(libelleTap(r)) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlocPhrase(
    phrase: PhraseRepas,
    lu: RepasLu?,
    modifiable: Boolean,
    onModifier: () -> Unit,
    onRetirer: () -> Unit,
    onQuantite: (Quantite) -> Unit,
) {
    val context = LocalContext.current
    val enCours by ServicesRepas.enCours.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(phrase.texte, style = MaterialTheme.typography.bodyMedium)

            if (lu == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (phrase.id in enCours) {
                        Text(
                            "Lecture en cours…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "Pas encore lue",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { ServicesRepas.lireEnArriere(context, phrase.id) }) { Text("Relancer") }
                    }
                }
            } else if (lu.ligneStandard) {
                Text("Portion", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Quantite.entries.forEach { q ->
                        FilterChip(
                            selected = phrase.quantite == q,
                            onClick = { onQuantite(q) },
                            label = { Text(libelleQuantite(q)) },
                        )
                    }
                }
            }

            Row {
                if (modifiable) TextButton(onClick = onModifier) { Text("Modifier") }
                TextButton(onClick = onRetirer) { Text("Retirer") }
            }
        }
    }
}
