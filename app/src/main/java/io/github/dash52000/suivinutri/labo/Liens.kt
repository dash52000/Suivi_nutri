package io.github.dash52000.suivinutri.labo

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Un lien suivi par le Labo, quelle que soit sa source (réflexe, lendemain, effet croisé), dans un sens donné
 * (v1.14). Sert à « Ce qu'on sait » dans le Labo et à « Ce qu'on a appris » dans le bilan du dimanche.
 */
data class Lien(
    /** Identifiant stable : source, libellé et sens. */
    val cle: String,
    /** Question à laquelle le lien répond : BALANCE (lendemain) ou COMPORTEMENT (réflexes, effets croisés). */
    val famille: Famille,
    /** Phrase lue dans le sens du lien : « Alcool → pesée du lendemain plus haute ». */
    val phrase: String,
    /** Le chiffre qui l'appuie : « +0,1 kg par unité », « 10 % des soirs contre 45 % ». */
    val chiffre: String,
    /** Probabilité a posteriori que le lien aille dans ce sens. */
    val prob: Double,
    val niveau: Niveau,
    /** Sens contraire à l'hypothèse de départ (un réflexe qui augmente le rab, un alcool qui fait baisser…). */
    val inverse: Boolean,
) {
    enum class Famille { BALANCE, COMPORTEMENT }
}

object Liens {

    /**
     * Un lien est annoncé (« Ce qu'on sait », bilan) s'il est probable dans le sens attendu, ou solide dans le sens
     * inverse : une quarantaine de sens sont suivis, et un sens inattendu demande plus de preuves avant d'y croire.
     */
    fun annonce(niveau: Niveau, inverse: Boolean): Boolean =
        if (inverse) niveau >= Niveau.SOLIDE else niveau >= Niveau.PROBABLE

    fun annonce(l: Lien): Boolean = annonce(l.niveau, l.inverse)

    /** Tous les liens évalués, dans les deux sens. */
    fun tous(r: ResultatLabo): List<Lien> =
        mecanismes(r.mecanismes) + lendemain(r.lendemain) + croises(r.croises) + (r.selle?.let { selle(it) } ?: emptyList())

    /** Ce qu'on sait : les liens annoncés, les plus sûrs d'abord. */
    fun annonces(r: ResultatLabo): List<Lien> = tous(r).filter { annonce(it) }.sortedByDescending { it.prob }

    /** Pistes en cours (sens attendu, piste mais pas encore probable) : pour dire où en est le Labo. */
    fun pistes(r: ResultatLabo): Int = tous(r).count { !it.inverse && it.niveau == Niveau.PISTE }

    private fun pct(x: Double) = "${(x * 100).roundToInt()} %"

    private fun kg(x: Double): String =
        (if (x >= 0) "+" else "−") + String.format(Locale.FRENCH, "%.1f", abs(x)) + " kg"

    fun mecanismes(liste: List<ResultatMecanisme>): List<Lien> = liste.flatMap { m ->
        val prob = m.probReduit ?: return@flatMap emptyList()
        val chiffre = "${pct(m.p1 ?: 0.0)} des soirs contre ${pct(m.p0 ?: 0.0)} quand il n'est pas tenu"
        listOf(
            Lien("meca:${m.libelle}", Lien.Famille.COMPORTEMENT, "${m.libelle} → moins de ${m.issue}", chiffre, prob, m.niveau, false),
            Lien("meca:${m.libelle}:inverse", Lien.Famille.COMPORTEMENT, "${m.libelle} → plus de ${m.issue}", chiffre, 1 - prob, m.niveauInverse, true),
        )
    }

    /** Libellé court d'une exposition du lendemain, et unité de son effet. */
    private fun sujet(libelle: String): Pair<String, String> = when (libelle) {
        "Alcool, par unité" -> "Alcool" to " par unité"
        else -> libelle to ""
    }

    fun lendemain(liste: List<EffetLendemain>): List<Lien> = liste.flatMap { e ->
        val post = e.post1 ?: return@flatMap emptyList()
        val prob = e.probMonte ?: return@flatMap emptyList()
        val (sujet, unite) = sujet(e.libelle)
        val chiffre = "${kg(post.estimation)}$unite le lendemain"
        listOf(
            Lien("lendemain:${e.libelle}", Lien.Famille.BALANCE, "$sujet → pesée du lendemain plus haute", chiffre, prob, e.niveau, false),
            Lien("lendemain:${e.libelle}:inverse", Lien.Famille.BALANCE, "$sujet → pesée du lendemain plus basse", chiffre, 1 - prob, e.niveauInverse, true),
        )
    }

    /**
     * Pesée et selle (v1.16), a priori sceptique : après la selle → pesée plus basse ; après un soir copieux, la selle
     * pèse plus (écart après / avant plus creusé) ; sans selle → pesée plus haute.
     */
    fun selle(m: Selle.Modele): List<Lien> {
        val l = mutableListOf<Lien>()
        fun paire(cle: String, phrase: String, inverse: String, c: Selle.Coef, probAttendu: Double, n1: Int, n0: Int, chiffre: String) {
            l += Lien("selle:$cle", Lien.Famille.BALANCE, phrase, chiffre, probAttendu, Mecanismes.niveau(probAttendu, n1, n0), false)
            l += Lien("selle:$cle:inverse", Lien.Famille.BALANCE, inverse, chiffre, 1 - probAttendu, Mecanismes.niveau(1 - probAttendu, n1, n0), true)
        }
        m.apres?.let { c ->
            paire(
                "apres", "Pesée après la selle → plus basse qu'avant", "Pesée après la selle → plus haute qu'avant",
                c, c.probNegatif, m.nApres, m.nAvant, "${kg(c.intervalle.estimation)} (${m.nApres} matins après)",
            )
        }
        m.croise?.let { c ->
            paire(
                "croise", "Après un soir copieux → la selle pèse plus", "Après un soir copieux → la selle pèse moins",
                c, c.probNegatif, m.nApresCopieux, m.nApresNormal,
                "${kg(c.intervalle.estimation)} de plus sur l'écart après / avant",
            )
        }
        m.sans?.let { c ->
            paire(
                "sans", "Pas de selle le matin → pesée plus haute", "Pas de selle le matin → pesée plus basse",
                c, 1 - c.probNegatif, m.nSans, m.nAvant, "${kg(c.intervalle.estimation)} (${m.nSans} matins sans)",
            )
        }
        return l
    }

    fun croises(liste: List<EffetCroise>): List<Lien> = liste.filter { !it.mecanisme }.flatMap { c ->
        val prob = c.probAugmente ?: return@flatMap emptyList()
        val chiffre = "${pct(c.p1 ?: 0.0)} contre ${pct(c.p0 ?: 0.0)} ${c.reference}"
        listOf(
            Lien("croise:${c.exposition}:${c.issue}", Lien.Famille.COMPORTEMENT, "${c.exposition} → plus souvent ${c.issue}", chiffre, prob, c.niveau, false),
            Lien("croise:${c.exposition}:${c.issue}:inverse", Lien.Famille.COMPORTEMENT, "${c.exposition} → moins souvent ${c.issue}", chiffre, 1 - prob, c.niveauInverse, true),
        )
    }
}
