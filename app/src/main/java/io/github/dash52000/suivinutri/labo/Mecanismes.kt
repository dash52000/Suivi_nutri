package io.github.dash52000.suivinutri.labo

import io.github.dash52000.suivinutri.data.EntreeJour
import java.time.LocalDate
import java.util.Random

/** Niveau de preuve d'un lien (v1.12) : probabilité a posteriori que le réflexe réduise l'issue. */
enum class Niveau(val libelle: String, val seuil: Double, val minParGroupe: Int) {
    AUCUN("pas encore de piste", 0.0, 0),
    PISTE("piste", 0.70, 3),
    PROBABLE("probable", 0.90, 5),
    SOLIDE("solide", 0.975, 8),
}

/** Un réflexe testé sur ce pour quoi il a été pensé, le soir même. */
class Mecanisme(
    val libelle: String,
    /** Libellé du réflexe correspondant dans [Labo.reflexes], pour les leviers ; null si ce n'est pas un réflexe. */
    val reflexe: String?,
    val issue: String,
    /** Questions du journal dont dépend le mécanisme (masqué si l'une est en pause). */
    val ids: Set<String>,
    /** Réflexe tenu ce soir-là : vrai, faux, ou null (soir franc seulement). */
    val tenu: (EntreeJour) -> Boolean?,
    /** Issue observée ce soir-là (le rab) : vrai, faux, ou null. */
    val observe: (EntreeJour) -> Boolean?,
)

data class ResultatMecanisme(
    val libelle: String,
    val reflexe: String?,
    val issue: String,
    /** Soirs où le réflexe a été tenu / pas tenu, avec l'issue renseignée. */
    val n1: Int,
    val n0: Int,
    /** Fréquences brutes de l'issue. */
    val p1: Double?,
    val p0: Double?,
    /** Écart de fréquence (tenu − pas tenu) a posteriori, à lieu de dîner égal : médiane et intervalle crédible à 95 %. */
    val ecart: Intervalle?,
    /** Probabilité a posteriori que le réflexe réduise l'issue. */
    val probReduit: Double?,
    val niveau: Niveau,
    /** L'effet inverse est au moins une piste (probabilité ≥ 70 % que le réflexe augmente l'issue). */
    val niveauInverse: Niveau,
)

/**
 * Mécanismes des réflexes en probabilités bayésiennes (v1.12), à la place des intervalles d'Agresti-Caffo et de la
 * correction de Benjamini-Hochberg.
 *
 * Pour chaque lieu du dîner (maison, beaux-parents, ailleurs), chaque groupe (réflexe tenu ou non) a une loi a
 * priori bêta centrée sur la fréquence commune de l'issue dans ce lieu, de force [FORCE_A_PRIORI] soirs : un a priori
 * sceptique, qui tire l'écart vers zéro. L'écart est la moyenne des écarts par lieu, pondérée comme Mantel-Haenszel
 * (n1 × n0 / (n1 + n0)) : le contexte ne gonfle pas l'effet. Tirages a posteriori à graine fixe : résultat
 * reproductible.
 *
 * Simulation (11/11/2026) : sans effet réel, « piste » sort dans environ 30 % des cas (c'est ce que veut dire 70 %),
 * « probable » dans 6 à 9 %, « solide » dans environ 2 % ; avec un effet fort (20 % contre 60 % de rab), « probable »
 * est atteint 9 fois sur 10 à partir de 20 soirs par groupe.
 */
object Mecanismes {

    const val FORCE_A_PRIORI = 4.0
    const val TIRAGES = 4000
    private const val GRAINE = 20261111L

    private fun ouiNon(e: EntreeJour, id: String): Boolean? = when (e.reponses[id]) { "oui" -> true; "non" -> false; else -> null }

    /** Rab le soir (« un peu » compte) ; null sans réponse. */
    fun rab(e: EntreeJour): Boolean? = when (e.reponses["resservi"]) { "oui", "un_peu" -> true; "non" -> false; else -> null }

    val liste: List<Mecanisme> = listOf(
        Mecanisme("Pris ton temps", "Soir · lentement", "rab", setOf("temps", "resservi"), { ouiNon(it, "temps") }, ::rab),
        Mecanisme("Boîtes posées", "Soir · boîtes posées", "rab", setOf("boites", "resservi"), { ouiNon(it, "boites") }, ::rab),
        Mecanisme("Plaisir baissé en fin d'assiette", null, "rab", setOf("plaisir_baisse", "resservi"), { ouiNon(it, "plaisir_baisse") }, ::rab),
        Mecanisme(
            "Assiette ½ ¼ ¼", "Soir · assiette ½ ¼ ¼", "rab", setOf("assiette", "resservi"),
            { when (it.reponses["assiette"]) { "oui" -> true; "non" -> false; else -> null } }, ::rab,
        ),
    )

    /** Lieu du dîner, strate de comparaison (« ? » si non renseigné). */
    private fun lieu(e: EntreeJour): String = e.reponses["diner"] ?: "?"

    /** Évalue tous les mécanismes sur les soirs jusqu'à [jusquA] inclus, sauf ceux qui dépendent d'une question en pause. */
    fun evaluer(entrees: Map<LocalDate, EntreeJour>, jusquA: LocalDate, enPause: Set<String> = emptySet()): List<ResultatMecanisme> {
        val soirs = entrees.filterKeys { !it.isAfter(jusquA) }.values
        return liste.filter { m -> m.ids.none { it in enPause } }.map { m -> evaluer(m, soirs) }
    }

    fun evaluer(m: Mecanisme, soirs: Collection<EntreeJour>): ResultatMecanisme {
        // Par lieu : [succès tenus, n tenus, succès pas tenus, n pas tenus].
        val strates = mutableMapOf<String, IntArray>()
        soirs.forEach { e ->
            val t = m.tenu(e) ?: return@forEach
            val o = m.observe(e) ?: return@forEach
            val c = strates.getOrPut(lieu(e)) { IntArray(4) }
            if (t) { c[1]++; if (o) c[0]++ } else { c[3]++; if (o) c[2]++ }
        }
        val n1 = strates.values.sumOf { it[1] }
        val n0 = strates.values.sumOf { it[3] }
        val p1 = if (n1 > 0) strates.values.sumOf { it[0] }.toDouble() / n1 else null
        val p0 = if (n0 > 0) strates.values.sumOf { it[2] }.toDouble() / n0 else null
        val utiles = strates.values.filter { it[1] > 0 && it[3] > 0 }
        val m1 = utiles.sumOf { it[1] }
        val m0 = utiles.sumOf { it[3] }
        if (m1 < Niveau.PISTE.minParGroupe || m0 < Niveau.PISTE.minParGroupe) {
            return ResultatMecanisme(m.libelle, m.reflexe, m.issue, n1, n0, p1, p0, null, null, Niveau.AUCUN, Niveau.AUCUN)
        }
        val alea = Random(GRAINE)
        val poids = utiles.map { it[1].toDouble() * it[3] / (it[1] + it[3]) }
        val total = poids.sum()
        val tirages = DoubleArray(TIRAGES) {
            var d = 0.0
            utiles.forEachIndexed { k, c ->
                val q = (c[0] + c[2] + 1.0) / (c[1] + c[3] + 2.0)
                val a = FORCE_A_PRIORI * q
                val b = FORCE_A_PRIORI * (1 - q)
                val t1 = Stats.tirageBeta(alea, a + c[0], b + c[1] - c[0])
                val t0 = Stats.tirageBeta(alea, a + c[2], b + c[3] - c[2])
                d += poids[k] * (t1 - t0)
            }
            d / total
        }
        val tries = tirages.sorted()
        val prob = tirages.count { it < 0 }.toDouble() / TIRAGES
        fun quantile(x: Double) = tries[((TIRAGES - 1) * x).toInt()]
        return ResultatMecanisme(
            m.libelle, m.reflexe, m.issue, n1, n0, p1, p0,
            ecart = Intervalle(quantile(0.5), quantile(0.025), quantile(0.975)),
            probReduit = prob,
            niveau = niveau(prob, m1, m0),
            niveauInverse = niveau(1 - prob, m1, m0),
        )
    }

    /** Le plus haut niveau atteint : probabilité au-dessus du seuil et assez de soirs dans chaque groupe. */
    fun niveau(prob: Double, n1: Int, n0: Int): Niveau =
        Niveau.values().filter { it != Niveau.AUCUN && prob >= it.seuil && n1 >= it.minParGroupe && n0 >= it.minParGroupe }
            .maxByOrNull { it.ordinal } ?: Niveau.AUCUN
}
