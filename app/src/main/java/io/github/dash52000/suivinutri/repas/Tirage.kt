package io.github.dash52000.suivinutri.repas

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.sqrt

/*
 * Tirage au sort par moment (fiche SPEC section 5, 3.1 B « Tirage au sort par moment »).
 *
 * Unité de tirage : un moment d'un jour (matin, midi, soir, grignotage). Tirages indépendants entre moments, graine
 * fixe, chaque tirage enregistré avec sa probabilité π (poids 1/π valides même si π est revu). Aucune dépendance
 * Android : testable avec kotlinc seul.
 */

/**
 * Taps du soir déjà connus au moment du tirage. Un soir est **chargé** (π = 1, toujours demandé) si [rab] ou [alcool]
 * vaut « oui » ([rab] = « resservi » un peu ou oui). Décision du 26/11 après lecture de l'export : la fiche y mettait
 * aussi le dessert et le dîner chez les beaux-parents, mais ils reviennent 15 et 25 soirs sur 30 (la norme, pas
 * l'exception) : tous les soirs auraient été chargés, soit ~13 phrases par semaine au lieu de 10 et plus aucun tirage.
 */
data class TapsSoir(val rab: Boolean? = null, val alcool: Boolean? = null) {
    val charge: Boolean get() = rab == true || alcool == true
}

/** Valeurs de départ décidées le 26/11 (fiche SPEC section 5). */
object ProbasDepart {
    const val SOIR_CHARGE = 1.0
    const val SOIR_NORMAL = 0.5
    const val MIDI = 0.6
    const val MATIN = 0.15

    /** π du matin tant que l'habitude n'est pas apprise (moins de 3 phrases tirées dans la fenêtre). */
    const val MATIN_SANS_HABITUDE = 0.5
    const val GRIGNOTAGE = 0.4
    const val PLANCHER = 0.1

    /** Budget : environ 10 phrases par semaine. Estimation de Damien, à confirmer par le journal d'usage. */
    const val BUDGET_PHRASES_PAR_SEMAINE = 10.0

    /**
     * Part des moments demandés qui donneront une phrase (les autres : « Comme d'hab », « Rien », refus), qui ne
     * comptent pas dans le budget. Hypothèse de départ pour le matin et le grignotage, à remplacer par la part
     * observée ([Neyman.tauxPhraseObserve]).
     */
    fun tauxPhrase(moment: Moment): Double = when (moment) {
        Moment.MATIN, Moment.GRIGNOTAGE -> 0.5
        Moment.MIDI, Moment.SOIR -> 1.0
    }

    /** Coût relatif de réponse (le grignotage demande plus d'effort). Hypothèse de départ, à recaler par le journal d'usage. */
    fun cout(moment: Moment): Double = if (moment == Moment.GRIGNOTAGE) 1.5 else 1.0

    fun pi(strate: Strate): Double = when (strate.moment) {
        Moment.MATIN -> MATIN
        Moment.MIDI -> MIDI
        Moment.SOIR -> if (strate.charge) SOIR_CHARGE else SOIR_NORMAL
        Moment.GRIGNOTAGE -> GRIGNOTAGE
    }
}

/**
 * Probabilité de tirage par strate. Toujours strictement positive : une strate jamais observée rendrait l'estimation
 * biaisée. Trois règles sur la table :
 *  - un soir chargé vaut toujours 1 ;
 *  - le matin vaut [ProbasDepart.MATIN_SANS_HABITUDE] tant que moins de 3 phrases tirées sont utilisables ;
 *  - sinon la valeur de la table, ramenée dans [plancher ; 1].
 * Une table incomplète est complétée par les valeurs de départ.
 */
class ProbasTirage(table: Map<Strate, Double> = emptyMap(), val plancher: Double = ProbasDepart.PLANCHER) {

    private val valeurs: Map<Strate, Double>

    init {
        require(plancher.isFinite() && plancher > 0.0 && plancher <= 1.0) { "Le plancher doit être dans ]0 ; 1]." }
        require(table.values.all { it.isFinite() && it > 0.0 && it <= 1.0 }) { "Chaque π doit être dans ]0 ; 1]." }
        valeurs = Strate.TOUTES.associateWith { ProbasDepart.pi(it) } + table
    }

    /** [phrasesMatinUtilisables] = phrases tirées du matin dans la fenêtre d'apprentissage (`HabitudeMatin.nPhrases`, 0 sans habitude). */
    fun pi(strate: Strate, phrasesMatinUtilisables: Int): Double = when {
        strate.moment == Moment.SOIR && strate.charge -> 1.0
        strate.moment == Moment.MATIN && phrasesMatinUtilisables < HabitudeMatin.PHRASES_MIN -> ProbasDepart.MATIN_SANS_HABITUDE
        else -> valeurs.getValue(strate).coerceIn(plancher, 1.0)
    }

    /** La table complète (dix strates), valeurs brutes : c'est ce qui est stocké dans `repas.json`. */
    fun table(): Map<Strate, Double> = valeurs

    /** Nouvelle table : les strates données remplacent les anciennes (recalcul de Neyman). */
    fun avec(nouvelles: Map<Strate, Double>): ProbasTirage = ProbasTirage(valeurs + nouvelles, plancher)
}

/** Issue d'un tirage. [u] est stable pour (graine, jour, moment) ; seule [pi] peut changer si les taps changent la strate. */
data class DecisionTirage(
    val jour: LocalDate,
    val moment: Moment,
    val strate: Strate,
    val pi: Double,
    val u: Double,
    val graine: Long,
) {
    /** Le moment est demandé si u < π. Un soir chargé (π = 1) l'est toujours, puisque u est dans [0 ; 1[. */
    val demande: Boolean get() = u < pi

    /** Ligne à enregistrer (sans réponse pour l'instant). La π enregistrée est celle en vigueur à ce moment-là. */
    fun versTirage(): TirageMoment = TirageMoment(jour, moment, strate, pi, graine)
}

/**
 * Tirage reproductible. u(graine, jour, moment) = frac(a + semaine × φ) : [a] est un uniforme tiré de
 * (graine, moment, jour de la semaine), φ une rotation irrationnelle propre au moment.
 *
 * Pourquoi pas un simple uniforme par jour : la fiche demande des tirages « équilibrés sur le jour de semaine, pour
 * qu'ils ne se concentrent pas sur quelques jours ». Pour un même (moment, jour de la semaine), les u successifs
 * forment une suite de Weyl : sur n semaines, le nombre de moments demandés s'écarte de n × π de 1 ou 2 au plus, au
 * lieu de ± √(n π (1 − π)) avec des tirages indépendants. Avec une graine tirée au hasard, chaque u reste uniforme
 * sur [0 ; 1[ et deux moments d'un même jour restent indépendants : la probabilité d'inclusion est bien π (poids 1/π).
 */
class Tirage(val graine: Long, val probas: ProbasTirage = ProbasTirage()) {

    fun u(jour: LocalDate, moment: Moment): Double {
        val rang = moment.ordinal * 7L + (jour.dayOfWeek.value - 1)
        val a = unite(melanger(graine xor ((rang + 1) * MULTIPLICATEUR)))
        // Semaines du lundi au dimanche, comptées depuis le 1er janvier 1970 (un jeudi) ; négatif avant, sans importance.
        val semaine = Math.floorDiv(jour.toEpochDay() + 3, 7L)
        val x = a + semaine * ROTATION[moment.ordinal]
        val r = x - floor(x)
        return if (r >= 1.0) 0.0 else r
    }

    /**
     * [taps] ne sert qu'au soir. [phrasesMatinUtilisables] : voir [ProbasTirage.pi].
     * Les taps peuvent changer au fil de la soirée : on rappelle `decider`, u ne change pas, π est réévaluée.
     */
    fun decider(jour: LocalDate, moment: Moment, phrasesMatinUtilisables: Int, taps: TapsSoir? = null): DecisionTirage {
        val strate = Strate.de(jour, moment, soirCharge = taps?.charge == true)
        return DecisionTirage(jour, moment, strate, probas.pi(strate, phrasesMatinUtilisables), u(jour, moment), graine)
    }

    private companion object {
        const val MULTIPLICATEUR = 0x2545F4914F6CDD1DL

        /** Rotations irrationnelles distinctes par moment (matin, midi, soir, grignotage) : parties fractionnaires de φ, √2, √3, √5. */
        val ROTATION = doubleArrayOf(0.6180339887498949, 0.4142135623730951, 0.7320508075688772, 0.2360679774997897)

        /** SplitMix64. */
        fun melanger(z0: Long): Long {
            var z = z0 + 0x9E3779B97F4A7C15uL.toLong()
            z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
            z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
            return z xor (z ushr 31)
        }

        /** 53 bits significatifs → uniforme sur [0 ; 1[. */
        fun unite(z: Long): Double = (z ushr 11).toDouble() / (1L shl 53).toDouble()
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Recalcul des probabilités (allocation de Neyman)
// ---------------------------------------------------------------------------------------------------------------

/**
 * Ce qu'il faut savoir d'une strate pour l'allouer : moments par semaine, écart-type attendu des kcal du moment
 * ([ecartType] null = encore inconnu, remplacé par la moyenne des strates connues), coût de réponse, part des moments
 * demandés qui donnent une phrase.
 */
data class StatStrate(
    val strate: Strate,
    val nParSemaine: Double,
    val ecartType: Double?,
    val cout: Double = 1.0,
    val tauxPhrase: Double = 1.0,
) {
    init {
        require(nParSemaine.isFinite() && nParSemaine >= 0.0) { "Effectif hebdomadaire ≥ 0 attendu." }
        require(cout.isFinite() && cout > 0.0) { "Coût > 0 attendu." }
        require(tauxPhrase.isFinite() && tauxPhrase > 0.0 && tauxPhrase <= 1.0) { "Part de phrases dans ]0 ; 1]." }
    }
}

object Neyman {

    /**
     * Moments par semaine et par strate, pour une semaine type. [partSoirsCharges] = part des soirs chargés
     * (observée sur le journal) répartie dans chaque type de jour.
     */
    fun effectifsHebdo(partSoirsCharges: Double): Map<Strate, Double> {
        require(partSoirsCharges in 0.0..1.0) { "La part de soirs chargés est entre 0 et 1." }
        val n = mutableMapOf<Strate, Double>().withDefault { 0.0 }
        for (moment in Moment.entries) for (jour in DayOfWeek.entries) {
            val type = TypeJour.de(jour, moment)
            if (moment == Moment.SOIR) {
                val normal = Strate(moment, type, charge = false)
                val charge = Strate(moment, type, charge = true)
                n[normal] = n.getValue(normal) + (1.0 - partSoirsCharges)
                n[charge] = n.getValue(charge) + partSoirsCharges
            } else {
                val s = Strate(moment, type)
                n[s] = n.getValue(s) + 1.0
            }
        }
        return Strate.TOUTES.associateWith { n.getValue(it) }
    }

    /** Statistiques de départ (écarts-types inconnus) : strates, effectifs, coûts et parts de phrases par défaut. */
    fun statsDepart(partSoirsCharges: Double): List<StatStrate> {
        val effectifs = effectifsHebdo(partSoirsCharges)
        return Strate.TOUTES.map {
            StatStrate(it, effectifs.getValue(it), null, ProbasDepart.cout(it.moment), ProbasDepart.tauxPhrase(it.moment))
        }
    }

    /**
     * Allocation optimale sous budget : π ∝ S / √coût (à budget de phrases fixé), ramenée dans [plancher ; 1].
     * Les soirs chargés sont à 1 et consomment leur part du budget. Budget : Σ moments × π × part de phrases.
     * Si le budget est trop serré, tout reste au plancher ; s'il est trop large, tout monte à 1.
     * Seuls les rapports entre écarts-types comptent : multiplier tous les S par une constante ne change rien.
     * Renvoie une π par strate de [stats].
     */
    fun allouer(stats: List<StatStrate>, budgetPhrases: Double, plancher: Double = ProbasDepart.PLANCHER): Map<Strate, Double> {
        require(budgetPhrases.isFinite() && budgetPhrases > 0.0) { "Budget de phrases > 0 attendu." }
        require(plancher.isFinite() && plancher > 0.0 && plancher <= 1.0) { "Le plancher doit être dans ]0 ; 1]." }
        require(stats.map { it.strate }.distinct().size == stats.size) { "Une seule ligne par strate." }

        val connus = stats.mapNotNull { it.ecartType }.filter { it.isFinite() && it > 0.0 }
        val reference = if (connus.isEmpty()) 1.0 else connus.average()
        fun ecartType(s: StatStrate) = s.ecartType?.takeIf { it.isFinite() && it > 0.0 } ?: reference

        val fixes = stats.filter { it.strate.moment == Moment.SOIR && it.strate.charge }
        val libres = stats.filter { it !in fixes }
        val resultat = mutableMapOf<Strate, Double>()
        fixes.forEach { resultat[it.strate] = 1.0 }
        if (libres.isEmpty()) return resultat

        val budgetLibre = budgetPhrases - fixes.sumOf { it.nParSemaine * it.tauxPhrase }
        val rapport = libres.associate { it.strate to ecartType(it) / sqrt(it.cout) }
        fun phrases(lambda: Double) = libres.sumOf {
            it.nParSemaine * it.tauxPhrase * (lambda * rapport.getValue(it.strate)).coerceIn(plancher, 1.0)
        }

        // phrases(λ) croît avec λ : dichotomie entre « tout au plancher » (λ = 0) et « tout à 1 ».
        val haut = 1.0 / rapport.values.min()
        val lambda = when {
            phrases(0.0) >= budgetLibre -> 0.0
            phrases(haut) <= budgetLibre -> haut
            else -> {
                var bas = 0.0
                var h = haut
                repeat(100) {
                    val milieu = (bas + h) / 2.0
                    if (phrases(milieu) < budgetLibre) bas = milieu else h = milieu
                }
                (bas + h) / 2.0
            }
        }
        libres.forEach { resultat[it.strate] = (lambda * rapport.getValue(it.strate)).coerceIn(plancher, 1.0) }
        return resultat
    }

    /** Phrases attendues par semaine avec ces probabilités : Σ moments × π × part de phrases. */
    fun phrasesAttendues(pi: Map<Strate, Double>, stats: List<StatStrate>): Double =
        stats.sumOf { it.nParSemaine * it.tauxPhrase * pi.getValue(it.strate) }

    /**
     * Part des moments demandés (et répondus) qui ont donné une phrase, ou null avec moins de [minimum] réponses :
     * l'hypothèse de départ ([ProbasDepart.tauxPhrase]) reste alors en vigueur.
     */
    fun tauxPhraseObserve(tirages: List<TirageMoment>, moment: Moment, minimum: Int = 10): Double? {
        val repondus = tirages.filter { it.moment == moment && it.reponse != null }
        if (repondus.size < minimum) return null
        return repondus.count { it.reponse == Reponse.PHRASE }.toDouble() / repondus.size
    }
}

/**
 * Quand revoir les probabilités : seulement un dimanche (début de semaine, jamais en cours de semaine), au plus tôt
 * 4 semaines après le premier tirage, puis toutes les 4 semaines (« chaque mois »).
 */
object CalendrierRecalcul {
    const val SEMAINES_ENTRE_RECALCULS = 4L

    fun autorise(jour: LocalDate, premierTirage: LocalDate?, dernierRecalcul: LocalDate?): Boolean {
        if (jour.dayOfWeek != DayOfWeek.SUNDAY) return false
        val reference = dernierRecalcul ?: premierTirage ?: return false
        return !jour.isBefore(reference.plusWeeks(SEMAINES_ENTRE_RECALCULS))
    }
}
