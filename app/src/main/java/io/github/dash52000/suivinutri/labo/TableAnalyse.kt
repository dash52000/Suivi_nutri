package io.github.dash52000.suivinutri.labo

import java.time.LocalDate
import java.util.Locale
import kotlin.math.sqrt

/**
 * Table d'analyse pour R : une ligne par jour, de la première donnée à aujourd'hui, tout fusionné
 * (Health Connect, tendance de Kalman, réponses). « NA » pour une valeur absente, point décimal,
 * booléens TRUE / FALSE : se lit directement avec read.csv().
 */
object TableAnalyse {

    private val COLONNES_FIXES = listOf(
        "date", "poids_kg", "poids_heure", "poids_ecartee", "tendance_kg", "tendance_se_kg",
        "pente_kg_semaine", "residu_kg", "kcal_total", "sommeil_min", "minutes_seances", "seance_midi",
        "longue_sortie", "poids_source", "graisse_pct", "masse_grasse_kg", "masse_maigre_kg", "masse_maigre_hc_kg",
        "eau_kg", "os_kg", "tendance_graisse_kg", "tendance_graisse_se_kg", "tendance_maigre_kg", "tendance_maigre_se_kg",
        // v1.7 : dépense retenue (NA les jours sans montre) et correction par l'eau de la balance.
        "kcal_retenue", "tendance_eau_kg", "ecart_eau_kg", "poids_corrige_kg", "masse_grasse_corrigee_kg",
    )

    private fun nombre(x: Double?, decimales: Int): String =
        x?.let { String.format(Locale.US, "%.${decimales}f", it) } ?: "NA"

    private fun booleen(b: Boolean?): String = when (b) {
        true -> "TRUE"; false -> "FALSE"; null -> "NA"
    }

    /** [idsQuestions] : identifiants des questions (intégrées, perso et « alcool »), une colonne chacune. */
    fun csv(jours: List<JourLabo>, aujourdHui: LocalDate, idsQuestions: List<String>): String {
        val entete = (COLONNES_FIXES + idsQuestions + listOf("nb_boissons", "unites_alcool")).joinToString(",")
        val parDate = jours.associateBy { it.date }
        val eau = Labo.lissageEau(jours, aujourdHui)
        val ecarts = Labo.ecartsEau(jours, aujourdHui, eau)
        val lis = Labo.lissage(jours, aujourdHui, ecarts)

        val avecDonnees = jours.filter {
            it.poids != null || it.kcal != null || it.sommeilMin != null || it.entree?.vide == false
        }
        val debut = (avecDonnees.map { it.date } + listOfNotNull(lis?.debut)).minOrNull()
            ?: return entete + "\n"

        val l = lis
        val c = Labo.compartiments(jours, aujourdHui, ecarts)
        val lignes = generateSequence(debut) { it.plusDays(1) }
            .takeWhile { !it.isAfter(aujourdHui) }
            .map { d ->
                val j = parDate[d]
                val i = l?.index(d)
                val ecartee = if (l != null && i != null && j?.poids != null) i in l.ecartees else null
                val tendance = if (l != null && i != null) l.niveau(i) else null
                val se = if (l != null && i != null) sqrt(l.varNiveau(i)) else null
                val pente = if (l != null && i != null) l.pente(i) * 7 else null
                val pj = j?.poids
                val residu = if (pj != null && tendance != null && ecartee == false) pj - tendance else null
                val e = j?.entree
                val alcoolRepondu = e?.reponses?.get("alcool") != null
                val cellules = mutableListOf(
                    d.toString(),
                    nombre(j?.poids, 2),
                    j?.heurePesee?.toString() ?: "NA",
                    booleen(ecartee),
                    nombre(tendance, 3),
                    nombre(se, 3),
                    nombre(pente, 3),
                    nombre(residu, 3),
                    nombre(j?.kcal, 0),
                    j?.sommeilMin?.toString() ?: "NA",
                    j?.minutesSeances?.toString() ?: "NA",
                    booleen(j?.seanceMidi),
                    booleen(j?.longueSortie),
                    j?.sourcePoids ?: "NA",
                    nombre(j?.graissePct, 1),
                    nombre(j?.masseGrasse, 2),
                    nombre(j?.masseMaigre, 2),
                    nombre(j?.masseMaigreHcKg, 2),
                    nombre(j?.eauKg, 2),
                    nombre(j?.osKg, 2),
                )
                val ic = c?.graisse?.index(d)
                cellules += if (c != null && ic != null) nombre(c.graisse.niveau(ic), 3) else "NA"
                cellules += if (c != null && ic != null) nombre(sqrt(c.graisse.varNiveau(ic)), 3) else "NA"
                cellules += if (c != null && ic != null) nombre(c.maigre.niveau(ic), 3) else "NA"
                cellules += if (c != null && ic != null) nombre(sqrt(c.maigre.varNiveau(ic)), 3) else "NA"
                val ie = eau?.index(d)
                val ecart = ecarts[d]
                cellules += nombre(j?.let(Labo::kcalRetenue), 0)
                cellules += if (eau != null && ie != null) nombre(eau.niveau(ie), 3) else "NA"
                cellules += nombre(ecart, 3)
                cellules += nombre(j?.let { Labo.poidsCorrige(it, ecart) }, 3)
                cellules += nombre(j?.let { Labo.graisseCorrigee(it, ecart) }, 3)
                idsQuestions.forEach { cellules += e?.reponses?.get(it) ?: "NA" }
                cellules += if (alcoolRepondu) e!!.boissons.size.toString() else "NA"
                cellules += if (alcoolRepondu) nombre(e!!.unitesAlcool, 2) else "NA"
                cellules.joinToString(",")
            }
            .toList()
        return (listOf(entete) + lignes).joinToString("\n", postfix = "\n")
    }
}
