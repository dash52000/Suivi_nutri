package io.github.dash52000.suivinutri.data

data class Option(val code: String, val libelle: String)

/** Quand une question s'affiche. Une question masquée n'est jamais comptée : NA. */
enum class Condition {
    TOUJOURS,

    /** Seulement les jours avec une séance (lue dans Health Connect). */
    SEANCE,

    /** Seulement les jours avec une séance qui démarre entre 10 h et 14 h 59. */
    SEANCE_MIDI,

    /** Seulement les soirs où le dîner est chez les beaux-parents. */
    BEAUX_PARENTS,
}

data class Question(
    val id: String,
    val libelle: String,
    val options: List<Option>,
    val condition: Condition = Condition.TOUJOURS,
    /** Ressentis : section repliée, purement facultative. */
    val ressenti: Boolean = false,
)

object Questionnaire {

    private fun options(vararg paires: Pair<String, String>) = paires.map { Option(it.first, it.second) }

    private val OUI_NON = options("oui" to "Oui", "non" to "Non")

    const val ID_DINER = "diner"
    const val DINER_BEAUX_PARENTS = "beaux_parents"
    const val ID_ALCOOL = "alcool"

    /** Dans l'ordre de la journée, pour faciliter le rappel. */
    val questions: List<Question> = listOf(
        Question(
            ID_DINER, "Dîner où ?",
            options(DINER_BEAUX_PARENTS to "Beaux-parents", "maison" to "Maison", "ailleurs" to "Ailleurs"),
        ),
        Question("collation", "Collation ce matin ?", OUI_NON, Condition.SEANCE_MIDI),
        Question("boites", "Boîtes posées sur la table ?", OUI_NON, Condition.BEAUX_PARENTS),
        Question(
            "assiette", "Assiette ½ légumes, ¼ féculents, ¼ protéines ?",
            options("oui" to "Oui", "en_partie" to "En partie", "non" to "Non", "pas_possible" to "Pas possible"),
        ),
        Question(
            "temps", "Pris ton temps sur la première assiette ?",
            options("oui" to "Oui", "a_moitie" to "À moitié", "non" to "Non"),
        ),
        Question(
            "plaisir_baisse", "Le plaisir a baissé en fin d'assiette ?",
            options("oui" to "Oui", "non" to "Non", "pas_fait_gaffe" to "Pas fait gaffe"),
        ),
        Question("resservi", "Resservi ?", options("non" to "Non", "un_peu" to "Un peu", "oui" to "Oui")),
        Question(
            "dessert", "Dessert ?",
            options("non" to "Non", "petite" to "Petite part", "normale" to "Part normale", "tupp" to "Mis dans le tupp"),
        ),
        Question("tupp", "Tupp remplis ?", OUI_NON, Condition.BEAUX_PARENTS),
        Question("cafes", "Cafés aujourd'hui", options("0" to "0", "1" to "1", "2" to "2", "3+" to "3+")),

        Question("fringale", "Fringale en séance ?", OUI_NON, Condition.SEANCE, ressenti = true),
        Question(
            "energie", "Énergie aujourd'hui",
            options("basse" to "Basse", "moyenne" to "Moyenne", "bonne" to "Bonne"),
            ressenti = true,
        ),
        Question("faim_coucher", "Faim au coucher ?", OUI_NON, ressenti = true),
    )

    /** Questions liées au dîner chez les beaux-parents, effacées si le dîner change de lieu. */
    val idsBeauxParents: Set<String> =
        questions.filter { it.condition == Condition.BEAUX_PARENTS }.map { it.id }.toSet()
}
