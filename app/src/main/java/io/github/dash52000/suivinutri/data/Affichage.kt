package io.github.dash52000.suivinutri.data

import android.content.Context

/** Préférences d'affichage. */
object Affichage {

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("reglages", Context.MODE_PRIVATE)

    /** N'afficher que la tendance lissée, jamais le chiffre de la pesée du jour. */
    fun masquerPesee(context: Context): Boolean = prefs(context).getBoolean("masquer_pesee", false)

    fun regler(context: Context, masquer: Boolean) {
        prefs(context).edit().putBoolean("masquer_pesee", masquer).apply()
    }
}
