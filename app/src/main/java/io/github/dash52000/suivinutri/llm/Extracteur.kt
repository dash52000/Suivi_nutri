package io.github.dash52000.suivinutri.llm

import io.github.dash52000.suivinutri.repas.Extraction
import io.github.dash52000.suivinutri.repas.PhraseRepas
import io.github.dash52000.suivinutri.repas.StockRepas
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Lit les phrases par la chaîne et range le résultat dans `repas.json` (étape 6, architecture section 2). Aucune
 * dépendance Android : la chaîne arrive sous forme de fonction, donc les tests branchent des réponses figées.
 *
 *  - Une phrase à la fois (un verrou) : deux lectures n'ouvrent pas deux appels en parallèle sur la même phrase, et les
 *    quotas gratuits restent loin.
 *  - Réussite : l'[Extraction] est stockée avec le modèle qui a réellement répondu, les maillons quittés avant lui, les
 *    erreurs rencontrées en route, la version du prompt et celle de CIQUAL. Une phrase déjà lue avec ces deux versions
 *    n'est pas relue.
 *  - Échec (hors ligne, aucune clé, budget de 60 s épuisé, tout en erreur) : rien n'est stocké, la phrase reste « en
 *    attente » (données NA) et [traiterEnAttente] la reprend à la prochaine ouverture de Ce soir.
 *  - Une phrase supprimée ou remplacée pendant l'appel (jusqu'à 60 s) n'est pas écrite après coup.
 *  - Le journal d'usage reçoit un événement par essai (fournisseur, modèle, issue, durée : jamais le texte, une clé ni un
 *    en-tête) et un résumé par phrase.
 */
class Extracteur(
    private val stock: StockRepas,
    private val versionCiqual: () -> String,
    /** (texte, moment) → résultat de la chaîne. Bloquant (jusqu'à 60 s) : hors du fil principal. */
    private val lecteur: (texte: String, moment: String) -> ResultatChaine,
    private val maintenant: () -> Instant = { Instant.now() },
    private val journal: (evenement: String, detail: String) -> Unit = { _, _ -> },
) {
    private val verrou = ReentrantLock()

    /**
     * Lit une phrase. Rend le résultat de la chaîne, ou null si rien n'était à faire (phrase inconnue ou déjà lue).
     * Bloquant.
     */
    fun lire(phraseId: String): ResultatChaine? = verrou.withLock {
        val phrase = stock.phrase(phraseId) ?: return null
        if (dejaLue(phrase)) return null

        val resultat = lecteur(phrase.texte, phrase.moment.code)
        resultat.tentatives.forEach { journal("llm_essai", it.pourJournal()) }
        when (resultat) {
            is ResultatChaine.Reussie -> {
                // La phrase a pu être corrigée ou retirée pendant l'appel : on n'écrit pas pour une phrase disparue.
                if (stock.phrase(phraseId) == null) {
                    journal("llm_lecture", "phrase=${court(phraseId)};abandon=phrase_retiree")
                } else {
                    stock.enregistrerExtraction(
                        Extraction(
                            phraseId = phraseId,
                            jsonBrut = resultat.jsonBrut,
                            modele = resultat.modeleRepondu,
                            versionPrompt = resultat.versionPrompt,
                            versionCiqual = versionCiqual(),
                            erreurs = resultat.tentatives.filter { it.issue != Issue.OK }.map(::trace),
                            bascules = resultat.bascules.map { it.modele },
                            horodatage = maintenant(),
                        ),
                    )
                    journal(
                        "llm_lecture",
                        "phrase=${court(phraseId)};moment=${phrase.moment.code};modele=${resultat.modeleRepondu};bascules=${resultat.bascules.size}",
                    )
                }
            }
            is ResultatChaine.Echouee -> journal("llm_lecture", "phrase=${court(phraseId)};echec=${resultat.raison.code}")
        }
        resultat
    }

    /**
     * Relit les phrases en attente, la plus ancienne d'abord. S'arrête à la première qui échoue : hors ligne, sans clé ou
     * budget épuisé, les suivantes échoueraient de même, et chaque essai coûte jusqu'à 60 s. Rend le nombre de phrases
     * lues.
     */
    fun traiterEnAttente(): Int {
        var lues = 0
        for (p in stock.phrasesEnAttente(Schema.VERSION_PROMPT)) {
            when (lire(p.id)) {
                is ResultatChaine.Reussie -> lues++
                is ResultatChaine.Echouee -> return lues
                null -> Unit
            }
        }
        return lues
    }

    private fun dejaLue(phrase: PhraseRepas): Boolean {
        val e = stock.extractionRetenue(phrase.id) ?: return false
        return e.versionPrompt == Schema.VERSION_PROMPT && e.versionCiqual == versionCiqual()
    }

    private fun trace(t: Tentative): String =
        "${t.maillon.modele}:${t.issue.code}" + (t.codeHttp?.let { ":$it" } ?: "")

    private fun court(id: String) = id.take(8)
}
