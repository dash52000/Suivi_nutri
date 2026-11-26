package io.github.dash52000.suivinutri.repas

import io.github.dash52000.suivinutri.llm.ExtractionLue
import io.github.dash52000.suivinutri.llm.Lecture
import io.github.dash52000.suivinutri.llm.LectureJson
import io.github.dash52000.suivinutri.llm.TagsLus

/**
 * Ce qu'une phrase donne une fois lue (étape 6). Recalculé à chaque appel depuis le JSON brut de l'extraction, la table
 * CIQUAL et le tap de quantité : jamais stocké (SPEC section 6), donc tout suit si le prompt ou la table change.
 *
 * [kcal] et [sigma] : somme des lignes reconnues et marge du repas (`Estimation.repas`) ; null = aucune ligne reconnue,
 * le repas reste « non décrit ». Les kcal n'atteignent jamais l'écran Ce soir (décision du 29/10) : elles servent au
 * petit-déj habituel ici, au biais, au bilan et au Labo ensuite.
 * [ligneStandard] : au moins un aliment est revenu en portion « standard » (le LLM n'a pas lu de quantité) : l'écran
 * propose alors petite · normale · grosse. Calculé sur ce que le LLM a écrit, donc il reste vrai après le tap.
 */
data class RepasLu(
    val aliments: List<AlimentExtrait>,
    val boissons: List<BoissonExtraite>,
    val kcal: Double?,
    val sigma: Double?,
    val nonReconnus: List<String>,
    val ligneStandard: Boolean,
    val tags: TagsLus,
    val confiance: Confiance,
)

object LectureRepas {

    /**
     * Phrase + extraction + table → [RepasLu], ou null si le JSON brut n'est plus lisible (ne devrait pas arriver : il a
     * été validé avant d'être stocké ; une version future du schéma pourrait le rendre illisible).
     * [nComparables] et [ecartDetecte] viennent du modèle de biais (marge des phrases spontanées) ; sans effet sur une
     * phrase tirée ou chargée.
     */
    fun lire(
        phrase: PhraseRepas,
        extraction: Extraction,
        table: TableCiqual,
        nComparables: Int = 0,
        ecartDetecte: Boolean = false,
    ): RepasLu? {
        val lue = lueDe(extraction) ?: return null
        val confiance = Confiance.depuisCode(lue.confiance)
        val quantite = phrase.quantite

        val aliments = lue.aliments.map { a ->
            var portion = PortionSource.depuisCode(a.portionSource)
            var grammes = a.grammes
            // Le tap ne touche que les lignes « standard » (voir Quantite) ; « normale » ne change rien.
            if (portion == PortionSource.STANDARD && quantite != null && quantite != Quantite.NORMALE) {
                grammes *= quantite.facteur
                portion = if (quantite == Quantite.PETITE) PortionSource.PETITE else PortionSource.GROSSE
            }
            Estimation.aliment(table, a.nom, grammes, portion, confiance, phrase.origine, a.moment, a.rab, nComparables, ecartDetecte)
        }
        val boissons = lue.boissons.map { b ->
            Estimation.boisson(table, b.nom, b.format, b.degre, confiance, phrase.origine, nComparables, ecartDetecte)
        }
        val repas = Estimation.repas(aliments, boissons)
        return RepasLu(
            aliments = aliments,
            boissons = boissons,
            kcal = repas?.first,
            sigma = repas?.second,
            nonReconnus = Estimation.nonReconnus(aliments, boissons),
            ligneStandard = lue.aliments.any { it.portionSource == "standard" },
            tags = lue.tags,
            confiance = confiance,
        )
    }

    /** L'aliment le plus lourd de la phrase (« lasagnes ») : sert au midi pré-rempli « Restes de … ». Null sans aliment. */
    fun platPrincipal(extraction: Extraction): String? =
        lueDe(extraction)?.aliments?.maxByOrNull { it.grammes }?.nom?.trim()?.takeIf { it.isNotEmpty() }

    private fun lueDe(extraction: Extraction): ExtractionLue? =
        (LectureJson.lire(extraction.jsonBrut) as? Lecture.Valide)?.extraction
}
