package io.github.dash52000.suivinutri.ui

import io.github.dash52000.suivinutri.labo.Intervalle
import java.time.format.DateTimeFormatter
import java.util.Locale

// Mise en forme partagée par le labo et le bilan : nombres à la française, vrai signe moins.

internal fun signe(x: Double, decimales: Int, unite: String): String =
    String.format(Locale.FRENCH, "%+,.${decimales}f", x).replace('-', '−') + " " + unite

internal fun nombre(x: Double, decimales: Int): String =
    String.format(Locale.FRENCH, "%,.${decimales}f", x).replace('-', '−')

/** 3 → « 3 », 3,5 → « 3,5 ». */
internal fun nombreCourt(x: Double): String = if (x % 1.0 == 0.0) nombre(x, 0) else nombre(x, 1)

internal fun ic(i: Intervalle, decimales: Int, unite: String): String =
    "IC 95 % : ${signe(i.bas, decimales, unite)} à ${signe(i.haut, decimales, unite)}"

internal fun pourcent(p: Double): String = String.format(Locale.FRENCH, "%.0f %%", p * 100)

internal val formatCourt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)
