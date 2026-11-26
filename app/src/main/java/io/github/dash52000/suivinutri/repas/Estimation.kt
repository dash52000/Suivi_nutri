package io.github.dash52000.suivinutri.repas

/**
 * De la lecture d'une phrase aux kcal d'un repas (fiche SPEC sections 6 et 7 ; `suivi-nutri.md` 3.1 B, 4.8).
 *
 * Le LLM ne donne que des noms, des grammes, des formats de boisson et des étiquettes de précision. Ici, et seulement ici :
 *  - un aliment est rapproché de CIQUAL par son nom, kcal = kcal pour 100 g × grammes ÷ 100 ;
 *  - une boisson est convertie en volume, puis en kcal (la bière et l'alcool suivent le degré) ;
 *  - chaque ligne reçoit sa marge σ (`Marges`) et le repas la marge combinée de ses lignes (`Marges.sigmaRepas`) ;
 *  - un nom que la table ne reconnaît pas reste NA (kcal = null) : il est signalé, jamais inventé ni remplacé par une moyenne.
 *
 * Pas de marge supplémentaire pour l'incertitude du rapprochement lui-même (un « pain » qui n'est pas la baguette de la
 * table) : le biais du contexte, appris par la balance, l'absorbe (SPEC 4.8). Limite connue, mesurée dans `CiqualTest`.
 */
object Estimation {

    /** Un aliment lu → un [AlimentExtrait]. [nComparables] et [ecartDetecte] viennent du modèle de biais (marge des spontanées). */
    fun aliment(
        table: TableCiqual,
        nom: String,
        grammes: Double,
        portion: PortionSource,
        confiance: Confiance,
        origine: Origine,
        moment: String?,
        rab: Boolean,
        nComparables: Int = 0,
        ecartDetecte: Boolean = false,
    ): AlimentExtrait {
        require(grammes > 0.0 && grammes.isFinite()) { "Des grammes positifs sont attendus." }
        val trouve = table.meilleur(nom)?.aliment
        return AlimentExtrait(
            nom = nom,
            codeCiqual = trouve?.code,
            grammes = grammes,
            portionSource = portion,
            moment = moment,
            rab = rab,
            kcal = trouve?.let { it.kcal * grammes / 100.0 },
            sigma = Marges.sigma(portion, confiance, origine, nComparables, ecartDetecte),
        )
    }

    /** Une boisson lue → un [BoissonExtraite] avec ses kcal. Voir [Boissons]. */
    fun boisson(
        table: TableCiqual,
        nom: String,
        format: String?,
        degre: Double?,
        confiance: Confiance,
        origine: Origine,
        nComparables: Int = 0,
        ecartDetecte: Boolean = false,
    ): BoissonExtraite {
        val e = Boissons.estimer(table, nom, format, degre)
        val portion = if (e?.volumeExplicite == true) PortionSource.EXPLICITE else PortionSource.STANDARD
        return BoissonExtraite(
            nom = nom,
            format = format,
            degre = degre,
            codeCiqual = e?.code,
            millilitres = e?.millilitres,
            kcal = e?.kcal,
            sigma = Marges.sigma(portion, confiance, origine, nComparables, ecartDetecte),
        )
    }

    /** kcal et σ relatif d'un repas décrit : somme des lignes reconnues avec des kcal > 0 ; null si aucune (le repas reste alors « non décrit »). */
    fun repas(aliments: List<AlimentExtrait>, boissons: List<BoissonExtraite>): Pair<Double, Double>? {
        val lignes = aliments.mapNotNull { a -> a.kcal?.takeIf { it > 0.0 }?.let { it to a.sigma } } +
            boissons.mapNotNull { b -> b.kcal?.takeIf { it > 0.0 }?.let { it to b.sigma } }
        if (lignes.isEmpty()) return null
        return lignes.sumOf { it.first } to Marges.sigmaRepas(lignes)
    }

    /** Aliments et boissons que la table n'a pas reconnus, pour le signalement dans Données. */
    fun nonReconnus(aliments: List<AlimentExtrait>, boissons: List<BoissonExtraite>): List<String> =
        aliments.filter { it.kcal == null }.map { it.nom } + boissons.filter { it.kcal == null }.map { it.nom }
}

/**
 * Boissons : volume et kcal.
 *
 *  - Volume : « 33 cl », « 0,5 l », « 2 bouteilles de 33 cl » sont lus tels quels (marge explicite) ; « verre », « bouteille »,
 *    « demi », « pinte »… prennent un volume usuel selon le type de boisson ; sans format, volume de départ, marge standard.
 *  - Kcal : CIQUAL pour 100 g (1 mL = 1 g) ; pour une boisson alcoolisée dont le degré est connu, on corrige de l'écart
 *    d'alcool avec l'aliment de la table à 7 kcal par g d'alcool (6,99 en régression d'Atwater sur la table 2025, R² 0,999).
 *  - Bière : l'aliment de la table est choisi par le degré (la table classe par degré : faible, cœur de marché, spéciale, forte),
 *    puis corrigé ; sans degré, aliment « de spécialités ou d'abbaye » ; les marques (« Orval », « Chimay ») sont reconnues
 *    comme bières par une courte liste de mots, à compléter.
 */
object Boissons {

    enum class Type { BIERE, VIN, FORT, AUTRE }

    class Estimee(val code: String, val millilitres: Double, val volumeExplicite: Boolean, val kcal: Double)

    const val KCAL_PAR_G_ALCOOL = 7.0
    private const val G_ALCOOL_PAR_ML_ETHANOL = 0.789

    private val MOTS_BIERE = setOf(
        "biere", "pils", "pilsner", "lager", "trappiste", "abbaye", "dubbel", "tripel", "triple", "quadrupel", "blonde", "brune", "ambree", "ipa",
        "saison", "lambic", "gueuze", "kriek", "stout", "porter", "orval", "chimay", "rochefort", "westmalle", "westvleteren", "achel", "duvel",
        "leffe", "kwak", "karmeliet", "delirium", "carolus", "maredsous", "grimbergen", "affligem", "jupiler", "stella", "hoegaarden",
    )
    private val MOTS_VIN = setOf("vin", "champagne", "prosecco", "cremant", "cava", "rose", "porto", "sangria")
    private val MOTS_FORT = setOf("whisky", "vodka", "rhum", "gin", "cognac", "armagnac", "pastis", "liqueur", "digestif", "tequila", "calvados", "alcool")

    fun type(nom: String): Type {
        val mots = Texte.brut(nom)
        return when {
            mots.any { it in MOTS_BIERE } -> Type.BIERE
            mots.any { it in MOTS_VIN } -> Type.VIN
            mots.any { it in MOTS_FORT } -> Type.FORT
            else -> Type.AUTRE
        }
    }

    fun estimer(table: TableCiqual, nom: String, format: String?, degre: Double?): Estimee? {
        val type = type(nom)
        val base = base(table, nom, type, degre) ?: return null
        val volume = volume(format, type)
        var kcal100 = base.kcal
        val alcoolBase = base.alcoolG
        if (degre != null && alcoolBase != null && base.groupe.startsWith("0603")) {
            val cible = degre * G_ALCOOL_PAR_ML_ETHANOL
            kcal100 = maxOf(0.0, kcal100 + KCAL_PAR_G_ALCOOL * (cible - alcoolBase))
        }
        return Estimee(base.code, volume.ml, volume.explicite, kcal100 * volume.ml / 100.0)
    }

    private fun base(table: TableCiqual, nom: String, type: Type, degre: Double?): AlimentCiqual? {
        if (type == Type.BIERE) {
            val bieres = table.aliments.filter { it.groupe == "060302" && it.nom.startsWith("Bière") && it.alcoolG != null && !it.nom.startsWith("Bière sans alcool, sucrée") }
            if (bieres.isNotEmpty() && degre != null) {
                val cible = degre * G_ALCOOL_PAR_ML_ETHANOL
                return bieres.minWith(compareBy<AlimentCiqual> { kotlin.math.abs(it.alcoolG!! - cible) }.thenBy { it.code.toInt() })
            }
            return bieres.firstOrNull { it.nom.contains("d'abbaye") } ?: table.meilleur(nom, TableCiqual.Portee.BOISSON)?.aliment
        }
        return table.meilleur(nom, TableCiqual.Portee.BOISSON)?.aliment
    }

    class Volume(val ml: Double, val explicite: Boolean)

    private val volumeExplicite = Regex("(\\d+(?:[.,]\\d+)?)\\s*(ml|cl|dl|l|litres?)(?![a-z])")
    private val nombreAvant = Regex("^\\s*(\\d+)\\s*(?:x|×)?\\s*[\\p{L}]*\\s*(?:de\\s+)?$")

    fun volume(format: String?, type: Type): Volume {
        val t = format?.trim()?.lowercase().orEmpty()
        val m = volumeExplicite.find(t)
        if (m != null) {
            val valeur = m.groupValues[1].replace(',', '.').toDouble()
            val ml = when (m.groupValues[2]) {
                "ml" -> valeur
                "cl" -> valeur * 10.0
                "dl" -> valeur * 100.0
                else -> valeur * 1000.0
            }
            // « 2 bouteilles de 33 cl », « 2 x 33 cl » : le nombre qui précède le volume le multiplie.
            val fois = nombreAvant.find(t.substring(0, m.range.first))?.groupValues?.get(1)?.toIntOrNull() ?: 1
            return Volume(ml * fois.coerceIn(1, 20), true)
        }
        val compte = Regex("^\\s*(\\d+)\\s").find(t)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 20) ?: 1
        val unite = usuel(t, type)
        return Volume(unite * compte, false)
    }

    /** Volume usuel (mL) d'un mot de contenant, selon la boisson ; à défaut le verre ou la bouteille du type. */
    private fun usuel(t: String, type: Type): Double {
        fun a(vararg mots: String) = mots.any { t.contains(it) }
        return when (type) {
            Type.BIERE -> when {
                a("pinte") -> 500.0
                a("demi") -> 250.0
                a("verre") -> 250.0
                else -> 330.0 // bouteille, canette, ou rien de dit
            }
            Type.VIN -> when {
                a("bouteille") -> 750.0
                a("ballon") -> 150.0
                else -> 120.0 // verre, flûte, coupe, ou rien de dit
            }
            Type.FORT -> if (a("bouteille")) 700.0 else 40.0 // dose, shot, verre
            Type.AUTRE -> when {
                a("bouteille") -> 500.0
                a("canette") -> 330.0
                a("tasse") -> 150.0
                else -> 250.0
            }
        }
    }
}
