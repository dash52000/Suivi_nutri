package io.github.dash52000.suivinutri.data

data class StyleBoisson(val code: String, val libelle: String, val degre: Double, val clParDefaut: Int)

data class Boisson(val style: String, val cl: Double, val degre: Double) {
    val unites: Double get() = Alcool.unites(cl, degre)
    val libelle: String get() = Alcool.style(style)?.libelle ?: style
}

object Alcool {

    /** Degrés par défaut : modifiables à chaque saisie. */
    val styles: List<StyleBoisson> = listOf(
        StyleBoisson("pils", "Pils", 5.0, 25),
        StyleBoisson("blonde", "Blonde / ambrée", 6.5, 33),
        StyleBoisson("dubbel", "Dubbel", 7.0, 33),
        StyleBoisson("tripel", "Tripel", 8.5, 33),
        StyleBoisson("quadrupel", "Quadrupel", 10.0, 33),
        StyleBoisson("vin", "Vin", 12.5, 12),
        StyleBoisson("autre", "Alcool fort", 40.0, 4),
    )

    val formatsCl: List<Int> = listOf(4, 12, 25, 33, 75)

    fun style(code: String): StyleBoisson? = styles.firstOrNull { it.code == code }

    /** Une unité = 10 g d'alcool pur. Volume en cl × degré × 0,8 (densité de l'éthanol) ÷ 100. */
    fun unites(cl: Double, degre: Double): Double = cl * degre * 0.8 / 100.0
}
