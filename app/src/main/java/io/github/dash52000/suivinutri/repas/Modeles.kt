package io.github.dash52000.suivinutri.repas

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/*
 * Modèles de données du lot v1.18 B (fiche SPEC section 6, architecture section 2). Aucune dépendance Android :
 * compilable et testable avec kotlinc seul.
 *
 * Ce qui est stocké (repas.json, voir StockRepas) : PhraseRepas, TirageMoment, Extraction.
 * Ce qui est recalculé, jamais stocké : AlimentExtrait / BoissonExtraite (relus depuis le JSON brut + CIQUAL),
 * BiaisContexte, ApportJour (étape 3), HabitudeMatin.
 */

private fun <E : Enum<E>> trouver(valeurs: Iterable<E>, code: String, codeDe: (E) -> String, nom: String): E =
    valeurs.firstOrNull { codeDe(it) == code } ?: throw IllegalArgumentException("$nom inconnu : « $code »")

/** Les quatre moments de saisie (champs de Ce soir). [code] = valeur envoyée au LLM, stockée et exportée. */
enum class Moment(val code: String) {
    MATIN("matin"), MIDI("midi"), SOIR("soir"), GRIGNOTAGE("grignotage");

    companion object {
        fun depuisCode(code: String): Moment = trouver(entries, code, { it.code }, "moment")
    }
}

/** D'où vient une phrase : moment tiré au sort, soir chargé (π = 1, toujours demandé), ou saisie spontanée (hors tirage). */
enum class Origine(val code: String) {
    TIREE("tiree"), CHARGEE("chargee"), SPONTANEE("spontanee");

    companion object {
        fun depuisCode(code: String): Origine = trouver(entries, code, { it.code }, "origine")
    }
}

/**
 * Nature de la réponse à un moment tiré. Une réponse donnée le lendemain (rattrapage) garde sa nature et porte
 * `tardive = true` : un « Rien » tardif reste un zéro observé, il ne devient pas une valeur « tardif » sans sens.
 * `null` dans [TirageMoment.reponse] = pas encore de réponse (moment ouvert) ; IGNORE = jour clos sans réponse (NA).
 */
enum class Reponse(val code: String) {
    PHRASE("phrase"), RIEN("rien"), COMME_DHAB("comme_dhab"), REFUS("refus"), IGNORE("ignore");

    companion object {
        fun depuisCode(code: String): Reponse = trouver(entries, code, { it.code }, "réponse")
    }
}

/** Mêmes codes que `Schema.PORTIONS` (vérifié par un test). */
enum class PortionSource(val code: String) {
    EXPLICITE("explicite"), STANDARD("standard"), GROSSE("grosse"), PETITE("petite");

    companion object {
        fun depuisCode(code: String): PortionSource = trouver(entries, code, { it.code }, "portion_source")
    }
}

/** Mêmes codes que `Schema.CONFIANCES` (vérifié par un test). */
enum class Confiance(val code: String) {
    HAUTE("haute"), MOYENNE("moyenne"), BASSE("basse");

    companion object {
        fun depuisCode(code: String): Confiance = trouver(entries, code, { it.code }, "confiance")
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Strates du tirage
// ---------------------------------------------------------------------------------------------------------------

enum class TypeJour(val code: String) {
    SEMAINE("semaine"), WEEK_END("weekend");

    companion object {
        /**
         * Week-end = les repas du « plan du week-end » de l'appli : vendredi soir, samedi, dimanche.
         * Le vendredi midi, matin et grignotage restent en semaine.
         */
        fun de(jour: DayOfWeek, moment: Moment): TypeJour = when (jour) {
            DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> WEEK_END
            DayOfWeek.FRIDAY -> if (moment == Moment.SOIR) WEEK_END else SEMAINE
            else -> SEMAINE
        }

        fun de(jour: LocalDate, moment: Moment): TypeJour = de(jour.dayOfWeek, moment)

        fun depuisCode(code: String): TypeJour = trouver(entries, code, { it.code }, "type de jour")
    }
}

/**
 * Strate d'échantillonnage : moment × type de jour × (pour le soir) chargé ou non selon les taps connus.
 * Les taps du midi (tupp / préparé / acheté) ne découpent pas encore les strates : le π de départ est le même, et
 * dix strates suffisent pour ~10 phrases par semaine. Code stocké : `soir|semaine|charge`, `midi|weekend`.
 */
data class Strate(val moment: Moment, val typeJour: TypeJour, val charge: Boolean = false) {
    init {
        require(!charge || moment == Moment.SOIR) { "Seul un soir peut être chargé." }
    }

    val code: String
        get() = if (moment == Moment.SOIR) "soir|${typeJour.code}|${if (charge) "charge" else "normal"}"
        else "${moment.code}|${typeJour.code}"

    companion object {
        /** Les dix strates : matin, midi, grignotage (× 2) et soir (× 2 types de jour × 2). */
        val TOUTES: List<Strate> = buildList {
            for (m in Moment.entries) for (t in TypeJour.entries) {
                if (m == Moment.SOIR) {
                    add(Strate(m, t, charge = false))
                    add(Strate(m, t, charge = true))
                } else add(Strate(m, t))
            }
        }

        fun de(jour: LocalDate, moment: Moment, soirCharge: Boolean): Strate =
            Strate(moment, TypeJour.de(jour, moment), charge = moment == Moment.SOIR && soirCharge)

        fun depuisCode(code: String): Strate {
            val p = code.split("|")
            val moment = Moment.depuisCode(p.getOrElse(0) { "" })
            val type = TypeJour.depuisCode(p.getOrElse(1) { "" })
            return if (moment == Moment.SOIR) {
                val c = when (p.getOrNull(2)) {
                    "charge" -> true
                    "normal" -> false
                    else -> throw IllegalArgumentException("strate inconnue : « $code »")
                }
                if (p.size != 3) throw IllegalArgumentException("strate inconnue : « $code »")
                Strate(moment, type, c)
            } else {
                if (p.size != 2) throw IllegalArgumentException("strate inconnue : « $code »")
                Strate(moment, type)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Ce qui est stocké
// ---------------------------------------------------------------------------------------------------------------

/** Une phrase saisie. [id] stable (UUID). Le texte part chez le fournisseur LLM, avec le moment, rien d'autre. */
data class PhraseRepas(
    val id: String,
    val jour: LocalDate,
    val moment: Moment,
    val texte: String,
    val origine: Origine,
    val tardive: Boolean,
    val horodatage: Instant,
)

/**
 * Un moment d'un jour soumis au tirage, avec la probabilité [pi] réellement utilisée et la graine : les poids 1/π
 * restent valides même si les probabilités sont revues plus tard. Clé naturelle : (jour, moment).
 */
data class TirageMoment(
    val jour: LocalDate,
    val moment: Moment,
    val strate: Strate,
    val pi: Double,
    val graine: Long,
    val reponse: Reponse? = null,
    val tardive: Boolean = false,
) {
    init {
        require(pi.isFinite() && pi > 0.0 && pi <= 1.0) { "π doit être dans ]0 ; 1] (reçu $pi)." }
        require(strate.moment == moment) { "La strate ${strate.code} ne correspond pas au moment ${moment.code}." }
    }
}

/**
 * Résultat stocké d'une lecture LLM. Clé : (phraseId, versionPrompt, versionCiqual) ; la plus récente fait foi.
 * [erreurs] et [bascules] = traces de la chaîne (jamais d'en-tête, de clé ni de corps de réponse).
 */
data class Extraction(
    val phraseId: String,
    val jsonBrut: String,
    val modele: String,
    val versionPrompt: Int,
    val versionCiqual: String,
    val erreurs: List<String>,
    val bascules: List<String>,
    val horodatage: Instant,
)

// ---------------------------------------------------------------------------------------------------------------
// Ce qui est recalculé (jamais stocké)
// ---------------------------------------------------------------------------------------------------------------

/** Aliment relu depuis l'extraction + CIQUAL. [kcal] = null : aliment non reconnu (reste NA). [sigma] : marge log-normale. */
data class AlimentExtrait(
    val nom: String,
    val codeCiqual: String?,
    val grammes: Double,
    val portionSource: PortionSource,
    val moment: String?,
    val rab: Boolean,
    val kcal: Double?,
    val sigma: Double,
)

/**
 * Boisson relue depuis l'extraction + CIQUAL (`Estimation.boisson`). [kcal] = null : boisson non reconnue (reste NA).
 * [millilitres] : volume lu ou usuel ; [sigma] : marge log-normale, comme pour un aliment.
 */
data class BoissonExtraite(
    val nom: String,
    val format: String?,
    val degre: Double?,
    val codeCiqual: String? = null,
    val millilitres: Double? = null,
    val kcal: Double? = null,
    val sigma: Double = Marges.SIGMA_STANDARD,
)

enum class Observation { DECRIT, NON_DECRIT, RIEN, COMME_DHAB }

/**
 * Ce que le modèle de biais (étape 3) connaît d'un repas, et rien d'autre : contexte déduit des taps, moment,
 * estimation de départ ĉ (kcal) et sa marge σ, origine de la phrase, π du tirage.
 *  - DECRIT : une phrase a été lue ; [kcal] = ĉ, [sigma] = marge combinée des aliments ;
 *  - NON_DECRIT : pas de phrase ; le modèle prend la moyenne du contexte (kcal et sigma restent null) ;
 *  - RIEN : tap « Rien » sur un moment tiré, zéro observé ;
 *  - COMME_DHAB : tap « Comme d'hab », valeur observée = petit-déj habituel.
 * [pi] = probabilité du tirage ; null pour une phrase spontanée (hors tirage) ou un repas jamais soumis au tirage.
 */
data class RepasObserve(
    val jour: LocalDate,
    val moment: Moment,
    val contexte: String,
    val observation: Observation,
    val kcal: Double?,
    val sigma: Double?,
    val origine: Origine?,
    val pi: Double?,
) {
    init {
        when (observation) {
            Observation.DECRIT -> require(kcal != null && kcal > 0.0 && sigma != null && sigma >= 0.0) { "Un repas décrit a ĉ > 0 et une marge." }
            Observation.NON_DECRIT -> require(kcal == null && sigma == null) { "Un repas non décrit n'a pas d'estimation propre." }
            Observation.RIEN -> require(kcal == 0.0) { "« Rien » vaut zéro observé." }
            Observation.COMME_DHAB -> require(kcal != null && kcal > 0.0 && sigma != null && sigma >= 0.0) { "« Comme d'hab » porte l'habitude (kcal et marge)." }
        }
        require(pi == null || (pi.isFinite() && pi > 0.0 && pi <= 1.0)) { "π doit être dans ]0 ; 1]." }
    }

    companion object {
        fun decrit(jour: LocalDate, moment: Moment, contexte: String, kcal: Double, sigma: Double, origine: Origine, pi: Double?) =
            RepasObserve(jour, moment, contexte, Observation.DECRIT, kcal, sigma, origine, pi)

        fun nonDecrit(jour: LocalDate, moment: Moment, contexte: String, pi: Double? = null) =
            RepasObserve(jour, moment, contexte, Observation.NON_DECRIT, null, null, null, pi)

        fun rien(jour: LocalDate, moment: Moment, contexte: String, pi: Double) =
            RepasObserve(jour, moment, contexte, Observation.RIEN, 0.0, 0.0, Origine.TIREE, pi)

        fun commeDhab(jour: LocalDate, moment: Moment, contexte: String, kcal: Double, sigma: Double, pi: Double) =
            RepasObserve(jour, moment, contexte, Observation.COMME_DHAB, kcal, sigma, Origine.TIREE, pi)
    }
}

enum class NiveauBiais(val code: String) { GLOBAL("global"), MIDI_SOIR("midi_soir"), CONTEXTE("contexte") }

/** Biais a posteriori d'un niveau de la hiérarchie (rempli par `BiaisRepas`, étape 3) : kcal vrais = ĉ × exp(β). */
data class BiaisContexte(
    val niveau: NiveauBiais,
    val contexte: String,
    val moyenne: Double,
    val ecartType: Double,
    val nRepas: Int,
    val calculeLe: LocalDate,
)

enum class SourceMoment { DECRIT, CONTEXTE, TAP }

/** Apport d'un jour avec sa barre d'erreur à 95 % (rempli par `ApportJour`, étape 3). */
data class ApportJour(
    val jour: LocalDate,
    val kcalParMoment: Map<Moment, Double>,
    val sources: Map<Moment, SourceMoment>,
    val kcal: Double,
    val bas: Double,
    val haut: Double,
)
