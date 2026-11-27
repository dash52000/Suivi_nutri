package io.github.dash52000.suivinutri.repas

import kotlin.math.sqrt

/**
 * Marges d'erreur des phrases (fiche SPEC sections 5 et 9.5, 3.1 B « Précision déduite de la formulation »).
 *
 * Damien n'indique jamais son degré d'incertitude : le LLM étiquette `portion_source` et `confiance`, le code applique
 * la marge. Chaque marge est l'écart-type σ d'une loi log-normale sur les kcal d'un aliment (σ = 0,15 : ±15 %).
 *
 *  - quantité explicite (un poids ou un volume est dit) : 0,15 · nombre d'unités sans poids : 0,25 · standard : 0,40 ·
 *    grosse ou petite : 0,30 ;
 *  - confiance basse : × 1,5 ;
 *  - phrase spontanée (hors tirage) : × 1,5 au départ, décroissant linéairement jusqu'à × 1 à 25 phrases comparables
 *    (même contexte), tant qu'aucun écart spontanée / tirée n'est détecté ; avec écart, elle reste à × 1,5.
 *
 * Les phrases tirées ou chargées ne reçoivent pas ce facteur. Les facteurs se multiplient (maximum 0,40 × 1,5 × 1,5 = 0,90).
 */
object Marges {

    const val SIGMA_EXPLICITE = 0.15

    /**
     * « 1 tartine », « 2 crépinettes » : le nombre est dit, les grammes sont devinés. Hypothèse de départ (27/11), non
     * mesurée : entre le poids dit (0,15) et l'absence de quantité (0,40).
     */
    const val SIGMA_UNITES = 0.25
    const val SIGMA_STANDARD = 0.40
    const val SIGMA_GROSSE_OU_PETITE = 0.30

    const val FACTEUR_CONFIANCE_BASSE = 1.5
    const val FACTEUR_SPONTANEE_DEPART = 1.5

    /** Nombre de phrases comparables à partir duquel le facteur des spontanées tombe à 1. */
    const val COMPARABLES_POUR_FACTEUR_UN = 25

    fun sigmaPortion(portion: PortionSource): Double = when (portion) {
        PortionSource.EXPLICITE -> SIGMA_EXPLICITE
        PortionSource.UNITES -> SIGMA_UNITES
        PortionSource.STANDARD -> SIGMA_STANDARD
        PortionSource.GROSSE, PortionSource.PETITE -> SIGMA_GROSSE_OU_PETITE
    }

    fun facteurConfiance(confiance: Confiance): Double =
        if (confiance == Confiance.BASSE) FACTEUR_CONFIANCE_BASSE else 1.0

    /**
     * Facteur d'une phrase selon son origine. [nComparables] = phrases du même contexte déjà lues ;
     * [ecartDetecte] = le modèle a vu un écart entre spontanées et tirées : le facteur ne décroît plus.
     */
    fun facteurOrigine(origine: Origine, nComparables: Int, ecartDetecte: Boolean = false): Double {
        if (origine != Origine.SPONTANEE) return 1.0
        if (ecartDetecte) return FACTEUR_SPONTANEE_DEPART
        val avancement = nComparables.coerceIn(0, COMPARABLES_POUR_FACTEUR_UN).toDouble() / COMPARABLES_POUR_FACTEUR_UN
        return FACTEUR_SPONTANEE_DEPART - (FACTEUR_SPONTANEE_DEPART - 1.0) * avancement
    }

    /** σ d'un aliment : portion × confiance de la phrase × origine de la phrase. */
    fun sigma(
        portion: PortionSource,
        confiance: Confiance,
        origine: Origine,
        nComparables: Int = 0,
        ecartDetecte: Boolean = false,
    ): Double = sigmaPortion(portion) * facteurConfiance(confiance) * facteurOrigine(origine, nComparables, ecartDetecte)

    /**
     * σ relatif d'un repas = somme d'aliments indépendants (méthode delta) : √Σ(kcalᵢ × σᵢ)² / Σ kcalᵢ.
     * Les aliments d'une même phrase partagent en réalité leur confiance : les traiter comme indépendants sous-estime
     * un peu la marge d'un repas à nombreux petits aliments (accepté, la balance recale le total).
     * Chaque élément = (kcal, σ). Un aliment non reconnu (kcal inconnu) n'y figure pas.
     */
    fun sigmaRepas(aliments: List<Pair<Double, Double>>): Double {
        require(aliments.isNotEmpty()) { "Un repas décrit a au moins un aliment avec ses kcal." }
        require(aliments.all { (kcal, sigma) -> kcal > 0.0 && kcal.isFinite() && sigma >= 0.0 && sigma.isFinite() }) {
            "kcal > 0 et σ ≥ 0 attendus."
        }
        val total = aliments.sumOf { it.first }
        return sqrt(aliments.sumOf { (kcal, sigma) -> (kcal * sigma) * (kcal * sigma) }) / total
    }
}
