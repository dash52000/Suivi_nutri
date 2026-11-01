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
        "longue_sortie",
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
        val lis = Labo.lissage(jours, aujourdHui)

        val avecDonnees = jours.filter {
            it.poids != null || it.kcal != null || it.sommeilMin != null || it.entree?.vide == false
        }
        val debut = (avecDonnees.map { it.date } + listOfNotNull(lis?.debut)).minOrNull()
            ?: return entete + "\n"

        val l = lis
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
                )
                idsQuestions.forEach { cellules += e?.reponses?.get(it) ?: "NA" }
                cellules += if (alcoolRepondu) e!!.boissons.size.toString() else "NA"
                cellules += if (alcoolRepondu) nombre(e!!.unitesAlcool, 2) else "NA"
                cellules.joinToString(",")
            }
            .toList()
        return (listOf(entete) + lignes).joinToString("\n", postfix = "\n")
    }
}
