package io.github.dash52000.suivinutri.data

data class Option(val code: String, val libelle: String)

/** Moment de la journée : le questionnaire suit cet ordre, pour faciliter le rappel. */
enum class Moment(val libelle: String) {
    MATIN("Matin"),
    MIDI("Midi"),
    SOIR("Soir"),
    JOURNEE("Journée"),
}

/** Quand une question s'affiche. Une question masquée n'est jamais comptée : NA. */
enum class Condition {
    TOUJOURS,

    /** Seulement les jours avec une séance (lue dans Health Connect). */
    SEANCE,

    /** Seulement les jours avec une séance qui démarre entre 10 h et 14 h 59. */
    SEANCE_MIDI,

    /** Seulement les soirs où le dîner est chez les beaux-parents. */
    BEAUX_PARENTS,

    /** Seulement si le déjeuner n'a pas été pris à l'extérieur (et que le lieu est renseigné). */
    MIDI_PAS_EXTERIEUR,
}

data class Question(
    val id: String,
    val libelle: String,
    val options: List<Option>,
    val condition: Condition = Condition.TOUJOURS,
    val moment: Moment = Moment.SOIR,
    /** Ressentis : section repliée, purement facultative. */
    val ressenti: Boolean = false,
)

object Questionnaire {

    private fun options(vararg paires: Pair<String, String>) = paires.map { Option(it.first, it.second) }

    private val OUI_NON = options("oui" to "Oui", "non" to "Non")
    private val TEMPS = options("oui" to "Oui", "a_moitie" to "À moitié", "non" to "Non")
    private val ASSIETTE = options("oui" to "Oui", "en_partie" to "En partie", "non" to "Non", "pas_possible" to "Pas possible")

    const val ID_DINER = "diner"
    const val DINER_BEAUX_PARENTS = "beaux_parents"
    const val ID_MIDI_LIEU = "midi_lieu"
    const val MIDI_EXTERIEUR = "exterieur"
    const val ID_ALCOOL = "alcool"

    /** Dans l'ordre de la journée. */
    val questions: List<Question> = listOf(
        Question("collation", "Collation ce matin ?", OUI_NON, Condition.SEANCE_MIDI, Moment.MATIN),

        Question(
            ID_MIDI_LIEU, "Déjeuner où ?",
            options("maison" to "Maison", "boulot" to "Boulot", MIDI_EXTERIEUR to "Extérieur"),
            moment = Moment.MIDI,
        ),
        Question(
            "midi_quoi", "Mangé quoi ?",
            options("tupp" to "Tupp", "prepare" to "Préparé", "achete" to "Acheté / resto"),
            moment = Moment.MIDI,
        ),
        Question("midi_temps", "Pris ton temps ?", TEMPS, moment = Moment.MIDI),
        Question("midi_ecran", "Devant un écran ?", OUI_NON, Condition.MIDI_PAS_EXTERIEUR, Moment.MIDI),
        Question("midi_assiette", "Assiette ½ légumes, ¼ féculents, ¼ protéines ?", ASSIETTE, moment = Moment.MIDI),
        Question("midi_creux", "Petit creux dans l'après-midi ?", OUI_NON, moment = Moment.MIDI),

        Question(
            ID_DINER, "Dîner où ?",
            options(DINER_BEAUX_PARENTS to "Beaux-parents", "maison" to "Maison", "ailleurs" to "Ailleurs"),
        ),
        Question("boites", "Boîtes posées sur la table ?", OUI_NON, Condition.BEAUX_PARENTS),
        Question("assiette", "Assiette ½ légumes, ¼ féculents, ¼ protéines ?", ASSIETTE),
        Question("temps", "Pris ton temps sur la première assiette ?", TEMPS),
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

        Question("cafes", "Cafés aujourd'hui", options("0" to "0", "1" to "1", "2" to "2", "3+" to "3+"), moment = Moment.JOURNEE),

        Question("fringale", "Fringale en séance ?", OUI_NON, Condition.SEANCE, Moment.JOURNEE, ressenti = true),
        Question(
            "energie", "Énergie aujourd'hui",
            options("basse" to "Basse", "moyenne" to "Moyenne", "bonne" to "Bonne"),
            moment = Moment.JOURNEE,
            ressenti = true,
        ),
        Question("faim_coucher", "Faim au coucher ?", OUI_NON, moment = Moment.JOURNEE, ressenti = true),
    )

    /**
     * Conditions qui dépendent d'une autre réponse : si elle devient fausse, la réponse de la
     * question conditionnée est effacée (sinon elle resterait comptée alors qu'elle est masquée).
     */
    fun conditionRemplie(condition: Condition, reponses: Map<String, String>): Boolean? = when (condition) {
        Condition.BEAUX_PARENTS -> reponses[ID_DINER] == DINER_BEAUX_PARENTS
        Condition.MIDI_PAS_EXTERIEUR -> reponses[ID_MIDI_LIEU].let { it != null && it != MIDI_EXTERIEUR }
        else -> null // ne dépend pas des réponses
    }
}
