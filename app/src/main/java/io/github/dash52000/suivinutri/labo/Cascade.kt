package io.github.dash52000.suivinutri.labo

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Une barre de la cascade : contribution d'une action à l'effort, en kcal/j et en kg sur la période (Hall).
 * [marge] : demi-largeur à 95 %, en kcal/j. Elle vient surtout de la référence (l'habitude d'avant le départ, connue
 * sur peu de jours) et ne diminue donc pas avec le temps.
 */
data class BarreCascade(val libelle: String, val kcalJour: Double, val kg: Double, val marge: Double)

/**
 * La cascade d'une période (v1.15) : l'effort mesuré par la balance (modèle de Hall, écart d'apports au départ moins
 * l'activité en plus), découpé en ce qu'expliquent le questionnaire (coûts de table) et la montre, et le reste.
 * Négatif = moins d'apports ou plus de dépense qu'avant le départ.
 */
data class Cascade(
    val debut: LocalDate,
    val fin: LocalDate,
    val jours: Int,
    /** Variation de la tendance du poids sur la période, kg. */
    val variationKg: Intervalle,
    /** Effort mesuré, kcal/j. */
    val mesure: Intervalle,
    val barres: List<BarreCascade>,
    /** Barres sans assez de données (rab, dessert, alcool, activité). */
    val manquantes: List<String>,
    /**
     * Mesuré − somme des barres : ce que ni le questionnaire ni la montre ne voient. Marge : celle du mesuré et celles
     * des barres réunies.
     */
    val nonExplique: Intervalle,
    val nonExpliqueKg: Double,
) {
    /** Le non-expliqué sort de sa marge. */
    val lisible: Boolean get() = nonExplique.bas > 0 || nonExplique.haut < 0
}

object CalculCascade {

    /** Une cascade demande au moins 4 semaines : avant, la marge du mesuré dépasse ±190 kcal/j. */
    const val JOURS_MIN = 28

    /** Référence du questionnaire : les 4 semaines avant le départ (ce qui existe). */
    const val JOURS_REFERENCE = 28L

    /** Référence de la montre : 12 semaines avant le départ (un an d'historique), une séance pesant 600 kcal. */
    const val JOURS_REFERENCE_MONTRE = 84L

    /** Incertitude sur [EPS_MONTRE] (0 à 15 kcal/j par kg), en écart-type. */
    private const val SD_EPS_MONTRE = 4.3

    private const val MIN_REPONSES = 7
    private const val MIN_REPONSES_REFERENCE = 5

    /**
     * Sensibilité de la dépense de la montre au poids, kcal/j par kg : le métabolisme de base de Mifflin compte
     * 10 kcal/kg. Ce n'est pas le ε de Hall (25,8), qui comprend l'adaptation du corps, que la montre ne voit pas.
     * Inconnue entre 0 (la montre ne reçoit pas le poids) et environ 15 : voir SimulationCascadeTest.
     */
    const val EPS_MONTRE = 10.0

    /** Barre de la montre : dépense en plus (séances et pas compris), jours sans montre écartés. */
    const val ACTIVITE = "Activité (montre)"

    /**
     * Cascade entre le matin de [debut] et le matin de [fin] (comportements de [debut] à la veille de [fin]),
     * par rapport aux [JOURS_REFERENCE] jours avant [depart]. Null avant [JOURS_MIN] jours ou sans tendance.
     */
    fun calculer(
        parDate: Map<LocalDate, JourLabo>,
        lis: Kalman.Lissage,
        depart: LocalDate,
        debut: LocalDate,
        fin: LocalDate,
    ): Cascade? {
        val jours = ChronoUnit.DAYS.between(debut, fin).toInt()
        if (jours < JOURS_MIN || debut.isBefore(depart)) return null
        val m = Cap.mesure(lis, depart, debut, jours) ?: return null
        val dates = (0 until jours).map { debut.plusDays(it.toLong()) }
        val reference = (1L..JOURS_REFERENCE).map { depart.minusDays(it) }

        val barres = mutableListOf<BarreCascade>()
        val manquantes = mutableListOf<String>()
        fun barre(libelle: String, d: Ecart?) {
            if (d == null) manquantes += libelle
            else barres += BarreCascade(libelle, d.valeur, Hall.variation(d.valeur, jours.toDouble()), Stats.Z975 * d.se)
        }
        fun questionnaire(f: (io.github.dash52000.suivinutri.data.EntreeJour) -> Double?): Ecart? {
            fun valeurs(ds: List<LocalDate>) = ds.mapNotNull { d -> parDate[d]?.entree?.let(f)?.let { d to it } }
            return ecart(valeurs(dates), valeurs(reference), MIN_REPONSES, MIN_REPONSES_REFERENCE)
        }
        barre("Rab", questionnaire(BilanDimanche::kcalRab))
        barre("Dessert", questionnaire(BilanDimanche::kcalDessert))
        barre("Alcool", questionnaire(BilanDimanche::kcalAlcool))
        val referenceMontre = (1L..JOURS_REFERENCE_MONTRE).map { depart.minusDays(it) }
        barre(ACTIVITE, activite(parDate, lis, depart, dates, referenceMontre)?.let { Ecart(-it.valeur, it.se) })

        val somme = barres.sumOf { it.kcalJour }
        val seMesure = (m.effort.haut - m.effort.bas) / (2 * Stats.Z975)
        val seNe = kotlin.math.sqrt(seMesure * seMesure + barres.sumOf { (it.marge / Stats.Z975).let { s -> s * s } })
        val estimation = m.effort.estimation - somme
        val ne = Intervalle(estimation, estimation - Stats.Z975 * seNe, estimation + Stats.Z975 * seNe)
        return Cascade(
            debut = debut,
            fin = fin,
            jours = jours,
            variationKg = m.pente.fois(jours / 7.0),
            mesure = m.effort,
            barres = barres,
            manquantes = manquantes,
            nonExplique = ne,
            nonExpliqueKg = Hall.variation(ne.estimation, jours.toDouble()),
        )
    }

    /** Écart de deux moyennes et son erreur-type. */
    class Ecart(val valeur: Double, val se: Double)

    /**
     * Moyenne de [periode] − moyenne de [reference], chacune calculée jour de semaine par jour de semaine (moyenne
     * des 7 moyennes) : 12 jours de référence avec 4 vendredis-samedis au lieu de 2 sur 7 surestimaient l'alcool
     * d'avant le départ (biais d'environ 35 kcal/j en simulation). Null sous les minimums ou s'il manque un jour.
     *
     * Erreur-type : celle de la référence seulement. Ce qui s'est passé pendant la période est connu (c'est ce qui a
     * compté pour le poids) ; ce qui est incertain, c'est l'habitude d'avant le départ, vue sur peu de jours.
     */
    fun ecart(periode: List<Pair<LocalDate, Double>>, reference: List<Pair<LocalDate, Double>>, min: Int, minReference: Int): Ecart? {
        if (periode.size < min || reference.size < minReference) return null
        val a = moyenneSemaine(periode) ?: return null
        val b = moyenneSemaine(reference) ?: return null
        return Ecart(a.first - b.first, kotlin.math.sqrt(b.second))
    }

    /**
     * Moyenne des 7 moyennes par jour de semaine, et sa variance : variance résiduelle autour des moyennes du jour
     * (mise en commun), × Σ (1/7)² / n du jour. Null s'il manque un jour de la semaine.
     */
    fun moyenneSemaine(x: List<Pair<LocalDate, Double>>): Pair<Double, Double>? {
        val parJour = x.groupBy({ it.first.dayOfWeek }, { it.second })
        if (parJour.size < 7) return null
        val moyennes = parJour.mapValues { (_, v) -> v.average() }
        val ddl = x.size - 7
        val residus = x.sumOf { (d, v) -> (v - moyennes.getValue(d.dayOfWeek)).let { it * it } }
        val s2 = if (ddl >= 3) residus / ddl else {
            val m = x.map { it.second }.average()
            x.sumOf { (it.second - m) * (it.second - m) } / (x.size - 1)
        }
        val variance = parJour.values.sumOf { s2 / it.size } / 49.0
        return moyennes.values.average() to variance
    }

    /**
     * Dépense en plus due à l'activité, kcal/j : dépense de la montre moins la part qui suit le poids ([EPS_MONTRE] ×
     * écart de tendance au départ), moyenne sur [dates] moins moyenne sur [reference]. Erreur-type : celle des deux
     * moyennes, plus l'incertitude sur [EPS_MONTRE]. Null sans assez de jours avec la montre (la moitié de chaque
     * période, 14 au moins).
     */
    fun activite(
        parDate: Map<LocalDate, JourLabo>,
        lis: Kalman.Lissage,
        depart: LocalDate,
        dates: List<LocalDate>,
        reference: List<LocalDate>,
    ): Ecart? {
        val n0 = lis.index(depart)?.let { lis.niveau(it) } ?: return null
        // (date, dépense corrigée du poids, écart de poids au départ)
        fun valeurs(ds: List<LocalDate>): List<Triple<LocalDate, Double, Double>> = ds.mapNotNull { d ->
            val k = parDate[d]?.let { Labo.kcalRetenue(it) } ?: return@mapNotNull null
            val i = lis.index(d) ?: return@mapNotNull null
            val ecartPoids = lis.niveau(i) - n0
            Triple(d, k - EPS_MONTRE * ecartPoids, ecartPoids)
        }
        val w = valeurs(dates)
        val r = valeurs(reference)
        val e = ecart(w.map { it.first to it.second }, r.map { it.first to it.second }, maxOf(14, dates.size / 2), maxOf(14, reference.size / 2))
            ?: return null
        val dPoids = w.map { it.third }.average() - r.map { it.third }.average()
        val seEps = SD_EPS_MONTRE * kotlin.math.abs(dPoids)
        // La montre se trompe surtout d'un facteur (± 10 % de l'activité en plus), pas au hasard d'un jour à l'autre.
        val seFacteur = 0.1 * kotlin.math.abs(e.valeur)
        return Ecart(e.valeur, kotlin.math.sqrt(e.se * e.se + seEps * seEps + seFacteur * seFacteur))
    }
}
