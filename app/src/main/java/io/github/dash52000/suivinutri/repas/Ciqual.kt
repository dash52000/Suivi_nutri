package io.github.dash52000.suivinutri.repas

import java.text.Normalizer
import kotlin.math.ln

/**
 * Table CIQUAL embarquée (fiche SPEC sections 6, 7 et 9.4 ; `suivi-nutri.md` 3.1 B « Rapprochement avec CIQUAL »).
 *
 * Le LLM ne donne jamais de kcal : il donne un nom générique (« lasagnes », « pain »), l'appli le rapproche d'un aliment
 * de la table et calcule kcal = kcal pour 100 g × grammes ÷ 100. Aucun accès Android ici : la table se construit depuis
 * le texte du fichier `ciqual.tsv` (voir `tools/ciqual_xml_vers_tsv.py`), donc elle se teste hors appareil.
 */

/** Un aliment de la table, pour 100 g. Un aliment sans kcal n'est pas embarqué. */
data class AlimentCiqual(
    val code: String,
    val nom: String,
    /** Code de groupe le plus précis : sous-sous-groupe (6 chiffres), sinon sous-groupe (4), sinon groupe (2). */
    val groupe: String,
    val kcal: Double,
    val proteinesG: Double?,
    val glucidesG: Double?,
    val lipidesG: Double?,
    val selG: Double?,
    val alcoolG: Double?,
)

/** Résultat d'un rapprochement : l'aliment retenu et son score (plus haut = plus sûr ; 1,0 = tous les mots, aliment générique). */
data class Rapprochement(val aliment: AlimentCiqual, val score: Double)

class TableCiqual private constructor(
    /** Millésime de la table (« 2025-11-03 ») : stocké avec chaque extraction pour tout recalculer si elle change. */
    val version: String,
    val aliments: List<AlimentCiqual>,
) {
    private class Entree(val aliment: AlimentCiqual, val mots: List<Mot>, val poids: DoubleArray, val poidsTotal: Double, val brut: Set<String>)

    private val parCode: Map<String, AlimentCiqual> = aliments.associateBy { it.code }
    private val entrees: List<Entree>
    private val idf: Map<String, Double>
    private val idfMax: Double

    init {
        val mots = aliments.map { a -> Texte.mots(a.nom) }
        val n = aliments.size.toDouble()
        val df = HashMap<String, Int>()
        mots.forEach { ms -> ms.map { it.racine }.toSet().forEach { df.merge(it, 1, Int::plus) } }
        idf = df.mapValues { (_, d) -> ln(1.0 + n / d) }
        idfMax = ln(1.0 + n)
        entrees = aliments.mapIndexed { i, a ->
            val ms = mots[i]
            val poids = DoubleArray(ms.size) { idf.getValue(ms[it].racine) }
            Entree(a, ms, poids, poids.sum(), Texte.brut(a.nom))
        }
    }

    fun parCode(code: String): AlimentCiqual? = parCode[code]

    /**
     * Les [n] meilleurs aliments pour un nom générique, du meilleur au moins bon ; vide si aucun ne couvre assez la demande.
     *
     * Règles (toutes testées, `CiqualTest`) :
     *  - tous les mots utiles de la demande doivent se retrouver dans le nom (un seul peut manquer à partir de 3 mots, jamais
     *    le plus rare) : mieux vaut « non reconnu » (NA) qu'un mauvais aliment ;
     *  - un nom avec peu de mots en plus vaut mieux qu'un nom très précis (« pain » → « Pain (aliment moyen) », pas « Pain au levain ») ;
     *  - à égalité, ce qui est mangé tel quel : cuit pour les féculents, légumineuses, viandes, poissons, œufs (« pâtes » → pâtes cuites,
     *    dont le poids est celui de l'assiette), sauf si la demande dit « cru » ; aliments infantiles, produits d'outre-mer et
     *    alternatives végétales passent après, sauf si la demande les nomme.
     */
    fun chercher(nom: String, n: Int = 5, portee: Portee = Portee.ALIMENT): List<Rapprochement> {
        val requete = Texte.mots(Synonymes.appliquer(nom)).distinctBy { it.racine }
        if (requete.isEmpty()) return emptyList()
        val poidsRequete = requete.map { idf[it.racine] ?: idfMax }
        val manquesPermis = if (requete.size >= 3) 1 else 0
        val plusRare = poidsRequete.indices.maxByOrNull { poidsRequete[it] }!!
        val brutRequete = requete.map { Texte.sansAccent(it.brut) }.toSet()
        val veutCru = requete.any { it.racine == "cru" }
        val veutCuit = requete.any { it.racine == "cuit" }

        val resultats = ArrayList<Rapprochement>()
        for (e in entrees) {
            if (!portee.admet(e.aliment.groupe)) continue
            var couvert = 0.0
            var manques = 0
            var manquePlusRare = false
            val utilises = BooleanArray(e.mots.size)
            for ((i, q) in requete.withIndex()) {
                var meilleur = 0.0
                var meilleurIndex = -1
                for ((j, m) in e.mots.withIndex()) {
                    val v = q.accord(m)
                    if (v > meilleur) { meilleur = v; meilleurIndex = j }
                }
                if (meilleur == 0.0) {
                    manques++
                    if (i == plusRare) manquePlusRare = true
                } else {
                    utilises[meilleurIndex] = true
                    couvert += poidsRequete[i] * meilleur
                }
            }
            if (manques > manquesPermis || manquePlusRare) continue
            val couverture = couvert / poidsRequete.sum()
            // Les mots très courants dans la table (cru, cuit, sans, sel, peau, chair…) ne rendent pas un nom plus « précis » :
            // seul compte ce qui dépasse le plancher PLANCHER_IDF.
            var excedent = 0.0
            for (j in e.mots.indices) if (!utilises[j]) excedent += maxOf(0.0, e.poids[j] - PLANCHER_IDF)
            val specificite = 1.0 - 0.5 * (excedent / e.poidsTotal)
            val tete = if (e.mots.first().racine == requete.first().racine) 1.1 else 1.0
            val score = couverture * specificite * tete * prior(e, brutRequete, veutCru, veutCuit)
            resultats += Rapprochement(e.aliment, score)
        }
        return resultats
            .sortedWith(compareByDescending<Rapprochement> { it.score }.thenBy { it.aliment.nom.length }.thenBy { it.aliment.code.toInt() })
            .take(n)
    }

    /**
     * L'aliment le plus représentatif de la demande. Quand plusieurs noms se valent (scores à moins de 8 % du meilleur : « lasagnes »
     * → à la viande, au poisson, aux légumes…), on prend celui dont les kcal sont au milieu de ces noms plutôt que le premier
     * du classement, arbitraire ; la marge de la phrase (σ) couvre l'écart qui reste.
     */
    fun meilleur(nom: String, portee: Portee = Portee.ALIMENT): Rapprochement? {
        val tete = chercher(nom, 8, portee)
        if (tete.isEmpty()) return null
        // Des noms qui se valent portent le même mot de tête (« lasagnes ou… à la viande / au poisson ») : « huile d'olive » ne
        // se mélange pas avec « olive noire, à l'huile ».
        val motTete = Texte.mots(tete[0].aliment.nom).first().racine
        val proches = tete.filter { it.score >= SEUIL_EX_AEQUO * tete[0].score && Texte.mots(it.aliment.nom).first().racine == motTete }
        if (proches.size == 1) return tete[0]
        return proches.sortedWith(compareBy<Rapprochement> { it.aliment.kcal }.thenByDescending { it.score }.thenBy { it.aliment.code.toInt() })[(proches.size - 1) / 2]
    }

    private fun prior(e: Entree, demande: Set<String>, veutCru: Boolean, veutCuit: Boolean): Double {
        var p = 1.0
        val racines = e.mots.map { it.racine }
        val groupe = e.aliment.groupe
        if ("aliment" in racines && "moyen" in racines) p *= 1.25
        if (ABATS.any { groupe.startsWith(it) } && demande.none { it in MOTS_ABATS }) p *= 0.6
        if ("standard" in racines) p *= 1.1
        if (EXOTIQUES.any { it in e.brut } && demande.none { it in EXOTIQUES }) p *= 0.6
        if (groupe.startsWith("11") && "infantile" !in demande && "bebe" !in demande) p *= 0.5
        if (ALTERNATIVES_VEGETALES.any { groupe.startsWith(it) } && demande.none { it.startsWith("vegetal") || it in setOf("soja", "vegetarien", "vegan") }) p *= 0.7
        val cru = "cru" in racines
        val cuit = "cuit" in racines
        val dejaCuit = DEJA_CUITS.any { groupe.startsWith(it) }
        val groupeCru = CRUS.any { groupe.startsWith(it) }
        when {
            veutCru -> if (cru || groupeCru) p *= 1.15 else if (cuit || dejaCuit) p *= 0.8
            veutCuit -> if (cuit || dejaCuit) p *= 1.15 else if (cru || groupeCru) p *= 0.8
            dejaCuit || groupeCru -> if (cuit || dejaCuit) p *= 1.15 else p *= 0.8
        }
        return p
    }

    /** Où chercher : les aliments (tout sauf les boissons), ou les boissons (groupe 06 et le lait). */
    enum class Portee(private val prefixes: List<String>, private val inverse: Boolean) {
        ALIMENT(listOf("06"), true),
        BOISSON(listOf("06", "0501"), false),
        TOUT(emptyList(), true);

        fun admet(groupe: String): Boolean = prefixes.any { groupe.startsWith(it) } != inverse
    }

    companion object {
        const val SEUIL_EX_AEQUO = 0.92

        /** IDF au-dessous duquel un mot en plus ne coûte rien (≈ un mot présent dans plus de 6 % des noms de la table). */
        const val PLANCHER_IDF = 2.9

        private val ABATS = listOf("040108", "040208")
        private val MOTS_ABATS = setOf("foie", "coeur", "rognon", "abat", "gesier", "langue", "tripe", "cervelle", "boudin")

        /** Lit le contenu de `ciqual.tsv`. Lève [IllegalArgumentException] si l'entête manque ou si une ligne est mal formée. */
        fun depuisTsv(lignes: Sequence<String>): TableCiqual {
            var version: String? = null
            val aliments = ArrayList<AlimentCiqual>()
            for ((i, ligne) in lignes.withIndex()) {
                if (ligne.isBlank()) continue
                if (ligne.startsWith("#ciqual\t")) { version = ligne.substringAfter('\t').trim(); continue }
                if (ligne.startsWith("#")) continue
                val c = ligne.split('\t')
                require(c.size == 9) { "ciqual.tsv ligne ${i + 1} : 9 colonnes attendues, ${c.size} lues." }
                fun x(k: Int): Double? = c[k].ifEmpty { null }?.toDouble()
                aliments += AlimentCiqual(c[0], c[1], c[2], x(3) ?: error("ciqual.tsv ligne ${i + 1} : kcal manquantes."), x(4), x(5), x(6), x(7), x(8))
            }
            require(version != null) { "ciqual.tsv : ligne « #ciqual<TAB>version » absente." }
            require(aliments.isNotEmpty()) { "ciqual.tsv : aucun aliment." }
            require(aliments.map { it.code }.toSet().size == aliments.size) { "ciqual.tsv : codes en double." }
            return TableCiqual(version, aliments)
        }

        fun depuisTexte(texte: String): TableCiqual = depuisTsv(texte.lineSequence())

        private val EXOTIQUES = setOf("martinique", "reunion", "guadeloupe", "guyane", "mayotte", "polynesie", "caledonie")
        // Préfixes de groupes (code le plus précis de l'aliment).
        private val ALTERNATIVES_VEGETALES = listOf("040309", "0411", "050205", "050306", "060205", "1009", "1010")

        // Groupes dont on mange le plus souvent la version cuite, et son poids cuit : féculents, légumineuses, viandes, poissons, œufs.
        private val DEJA_CUITS = listOf("030101", "0401", "0405", "0407", "041001", "020301")
        private val CRUS = listOf("030102", "0402", "0406", "0408", "041002", "020302", "020303")
    }
}

/** Un mot de la demande ou d'un nom d'aliment : [brut] (accents gardés, pluriel retiré), [racine] (sans accent, sans marque du féminin). */
internal data class Mot(val surface: String, val brut: String, val racine: String) {
    private val accentue = brut != Texte.sansAccent(brut)

    /**
     * 1,0 même mot, même nombre (« pâtes » / « pâtes ») ; 0,95 même mot, singulier ou pluriel (« pâtes » / « pâte ») ; 0,85 mots
     * identiques à l'accent près, seulement si la demande n'a pas d'accent (« pates » peut être « pâtes », mais « pâtes » n'est pas
     * « pâté ») ; 0,8 préfixe de 5 lettres ou plus ; 0 sinon.
     */
    fun accord(autre: Mot): Double = when {
        surface == autre.surface -> 1.0
        brut == autre.brut -> 0.95
        !accentue && racine == autre.racine -> 0.85
        racine.length >= 5 && autre.racine.startsWith(racine) -> 0.8
        else -> 0.0
    }
}

internal object Texte {
    private val separateurs = Regex("[^\\p{L}\\p{N}]+")
    private val accents = Regex("\\p{Mn}+")

    // Mots sans contenu pour le rapprochement (articles, prépositions) ; « sans », « avec », « non » restent : ils changent le sens.
    private val vides = setOf("le", "la", "les", "l", "un", "une", "des", "du", "de", "d", "au", "aux", "a", "et", "en", "ou", "par", "pour", "sur", "dans", "ex", "type")

    fun sansAccent(s: String): String = accents.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")

    // « pomme de terre » est un seul mot : sinon « pomme » (le fruit) ramène des pommes de terre.
    private val composes = Regex("pommes? de terre")

    private fun minuscules(s: String): String =
        composes.replace(s.lowercase().replace("œ", "oe").replace("æ", "ae").replace('’', '\''), "pommedeterre")

    /** Mots utiles d'un texte, dans l'ordre. */
    fun mots(texte: String): List<Mot> =
        separateurs.split(minuscules(texte)).filter { it.isNotEmpty() && it !in vides && it !in sansAccentVides }.map { mot ->
            val brut = pluriel(mot)
            Mot(mot, brut, feminin(sansAccent(brut)))
        }

    private val sansAccentVides = vides.map { sansAccent(it) }.toSet()

    /** Mots bruts (sans accent, au singulier) d'un texte : pour tester la présence d'un mot précis (« martinique »). */
    fun brut(texte: String): Set<String> = separateurs.split(minuscules(texte)).filter { it.isNotEmpty() }.map { sansAccent(pluriel(it)) }.toSet()

    private fun pluriel(m: String): String = if (m.length > 3 && (m.endsWith("s") || m.endsWith("x"))) m.dropLast(1) else m

    private fun feminin(m: String): String = if (m.length >= 4 && m.endsWith("e")) m.dropLast(1) else m
}

/**
 * Noms courants que la table n'emploie pas. Courte, à compléter avec les « non reconnus » du journal d'usage
 * (un aliment non rapproché reste NA : rien n'est inventé).
 */
internal object Synonymes {
    private val tableau = mapOf(
        "patate" to "pomme de terre",
        "patates" to "pomme de terre",
        "yogourt" to "yaourt",
        "yogourts" to "yaourt",
        "frites" to "pommes de terre frites",
        "baguette" to "pain",
        "tartine" to "pain",
        "salade" to "salade verte",
        "jambon" to "jambon cuit",
        "oeuf" to "oeuf dur",
        "oeufs" to "oeuf dur",
        "œuf" to "oeuf dur",
        "œufs" to "oeuf dur",
        "saucisson" to "saucisson sec",
        "coca" to "cola",
        "legumes" to "légume cuit",
        "légumes" to "légume cuit",
        "spaghetti" to "pâtes",
        "spaghettis" to "pâtes",
        "tagliatelles" to "pâtes",
        "macaronis" to "pâtes",
        "penne" to "pâtes",
        "fusilli" to "pâtes",
        "coquillettes" to "pâtes",
    )

    fun appliquer(nom: String): String {
        var t = nom.trim().lowercase()
        for ((mot, remplace) in tableau) t = t.replace(Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(mot) + "(?![\\p{L}\\p{N}])"), remplace)
        return t
    }
}
