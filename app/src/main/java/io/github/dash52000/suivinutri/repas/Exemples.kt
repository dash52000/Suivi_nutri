package io.github.dash52000.suivinutri.repas

/**
 * Taps du questionnaire utiles aux champs de phrase (étape 6). Les codes sont ceux de `Questionnaire`
 * (`midi_quoi`, `diner`, `resservi`, `alcool`) ; tout est null tant que le tap n'est pas donné.
 */
data class ContexteSaisie(
    val midiQuoi: String? = null,
    val diner: String? = null,
    val resservi: String? = null,
    val alcool: String? = null,
) {
    val resservisOuAlcool: Boolean get() = resservi == "un_peu" || resservi == "oui" || alcool == "oui"

    companion object {
        fun depuis(reponses: Map<String, String>) = ContexteSaisie(
            midiQuoi = reponses["midi_quoi"],
            diner = reponses["diner"],
            resservi = reponses["resservi"],
            alcool = reponses["alcool"],
        )
    }
}

/**
 * Exemple grisé d'un champ, selon le moment et les taps déjà donnés (3.1 B « Exemple grisé selon le contexte »).
 * Il montre le niveau de détail utile (quantité, rab, boissons) sans rien imposer. Texte pur : jamais de kcal.
 */
object Exemples {

    fun pour(moment: Moment, contexte: ContexteSaisie = ContexteSaisie()): String = when (moment) {
        Moment.MATIN -> "ex. : 2 tartines beurre confiture, un café"
        Moment.MIDI -> when (contexte.midiQuoi) {
            "tupp" -> "ex. : restes de lasagnes, assiette normale, une pomme"
            "achete" -> "ex. : sandwich jambon-beurre, chips, une pomme"
            "prepare" -> "ex. : pâtes au thon, un yaourt"
            else -> "ex. : restes de lasagnes, assiette normale, une pomme"
        }
        Moment.SOIR -> when {
            contexte.diner == "beaux_parents" && contexte.resservisOuAlcool ->
                "ex. : lasagnes, 1 grosse assiette + ½ en rab, tarte, 2 Orval"
            contexte.diner == "beaux_parents" -> "ex. : lasagnes, 1 assiette, tarte, 1 verre de vin"
            contexte.diner == "ailleurs" -> "ex. : burger frites, un verre de vin"
            else -> "ex. : omelette 3 œufs, salade, pain"
        }
        Moment.GRIGNOTAGE -> "ex. : 16h paquet de chips"
    }
}
