package io.github.dash52000.suivinutri.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.dash52000.suivinutri.data.Usage
import io.github.dash52000.suivinutri.repas.LignePhrase
import io.github.dash52000.suivinutri.repas.PortionSource
import io.github.dash52000.suivinutri.repas.ServicesRepas
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import io.github.dash52000.suivinutri.repas.Moment as MomentRepas

private val formatJourLong: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

private fun titreMoment(m: MomentRepas): String = when (m) {
    MomentRepas.MATIN -> "Matin"
    MomentRepas.MIDI -> "Midi"
    MomentRepas.SOIR -> "Soir"
    MomentRepas.GRIGNOTAGE -> "Grignotage"
}

private fun libellePortion(p: PortionSource): String = when (p) {
    PortionSource.EXPLICITE -> "quantité dite"
    PortionSource.STANDARD -> "portion standard"
    PortionSource.PETITE -> "petite portion"
    PortionSource.GROSSE -> "grosse portion"
}

/** « ≈ 640 kcal (± 35 %) ». La marge est celle du modèle (σ log-normal), pas un intervalle à 95 %. */
private fun texteKcal(kcal: Double, sigma: Double?): String =
    "≈ ${nombre(kcal, 0)} kcal" + (sigma?.let { " (± ${nombre(it * 100, 0)} %)" } ?: "")

/**
 * « Phrases lues » (onglet Données, lot v1.18 B) : pour chaque phrase des 14 derniers jours, si elle est lue, par quel
 * modèle, ce que le code en a tiré (aliment, quantité, kcal CIQUAL) et ce qu'il n'a pas reconnu. Demandé par Damien le
 * 26/11 pour savoir si les phrases sont prises en compte. Les kcal sont une estimation, jamais un verdict, et ne
 * paraissent pas dans Ce soir (décision du 29/10). Tout est recalculé depuis le JSON brut et la table : rien n'est stocké.
 *
 * Invisible sans phrase et sans clé API (l'appli reste alors identique à la v1.17).
 */
@Composable
internal fun CartePhrasesLues() {
    val context = LocalContext.current
    val saisie = remember { ServicesRepas.saisie(context) }
    val version by ServicesRepas.version.collectAsState()
    val enCours by ServicesRepas.enCours.collectAsState()
    var lignes by remember { mutableStateOf<List<LignePhrase>?>(null) }
    var erreur by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(version) {
        try {
            lignes = withContext(Dispatchers.IO) { saisie.lignesPhrases(LocalDate.now().minusDays(14)) }
            erreur = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Usage.log(context, "repas_erreur", "etape=lues;type=${e.javaClass.simpleName}")
            erreur = e.message ?: e.javaClass.simpleName
        }
    }

    val liste = lignes.orEmpty()
    if (liste.isEmpty() && erreur == null && !ServicesRepas.disponible(context)) return

    val enAttente = liste.count { it.lu == null && it.modele == null }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Phrases lues", style = MaterialTheme.typography.titleMedium)
            Text(
                "Ce que l'appli a compris de tes phrases des 14 derniers jours. Les kcal viennent de la table CIQUAL : " +
                    "ce sont des estimations avec leur marge, jamais un verdict.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            erreur?.let {
                Text("Lecture impossible : $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (liste.isEmpty() && erreur == null) {
                Text(
                    if (lignes == null) "Chargement…" else "Aucune phrase écrite pour l'instant.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            liste.groupBy { it.phrase.jour }.forEach { (jour, duJour) ->
                val total = duJour.mapNotNull { it.lu?.kcal }.takeIf { it.isNotEmpty() }?.sum()
                Text(
                    jour.format(formatJourLong).replaceFirstChar { it.uppercase() } +
                        (total?.let { " · ${nombre(it, 0)} kcal dans les phrases lues" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                duJour.forEach { LignePhraseLue(it, lecturEnCours = it.phrase.id in enCours) }
            }
            if (liste.isNotEmpty()) {
                Text(
                    "Les kcal ci-dessus ne comptent que les phrases lues et reconnues. Un moment sans phrase n'y est pas : " +
                        "ce n'est pas ton apport du jour.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (enAttente > 0) {
                OutlinedButton(onClick = { ServicesRepas.traiterEnAttente(context) }) {
                    Text("Relire les $enAttente phrase${if (enAttente > 1) "s" else ""} en attente")
                }
            }
        }
    }
}

@Composable
private fun LignePhraseLue(ligne: LignePhrase, lecturEnCours: Boolean) {
    val lu = ligne.lu
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "${titreMoment(ligne.phrase.moment)} : « ${ligne.phrase.texte} »",
            style = MaterialTheme.typography.bodyMedium,
        )
        val etat = when {
            lu != null && lu.kcal != null -> texteKcal(lu.kcal, lu.sigma) + " · lue par ${ligne.modele ?: "?"}"
            lu != null -> "Lue par ${ligne.modele ?: "?"}, mais aucun aliment reconnu : rien n'est compté"
            lecturEnCours -> "Lecture en cours…"
            ligne.modele != null -> "Lue par ${ligne.modele}, mais la réponse est illisible"
            else -> "Pas encore lue (hors ligne, clé refusée ou appli fermée) : rien n'est compté"
        }
        Text(
            etat,
            style = MaterialTheme.typography.bodySmall,
            color = if (lu?.kcal != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (lu != null) {
            lu.aliments.forEach { a ->
                val suite = a.kcal?.let { "${nombre(it, 0)} kcal" } ?: "non reconnu"
                Text(
                    "• ${a.nom} · ${nombre(a.grammes, 0)} g (${libellePortion(a.portionSource)}) · $suite" + if (a.rab) " · rab" else "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            lu.boissons.forEach { b ->
                val volume = b.millilitres?.let { "${nombre(it, 0)} ml" } ?: (b.format ?: "volume usuel")
                val suite = b.kcal?.let { "${nombre(it, 0)} kcal" } ?: "non reconnue"
                Text("• ${b.nom} · $volume · $suite", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
