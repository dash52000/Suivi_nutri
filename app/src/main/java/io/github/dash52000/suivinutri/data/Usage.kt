package io.github.dash52000.suivinutri.data

import android.content.Context
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Journal d'utilisation, local au téléphone (rien n'est envoyé) : sert à décider, au point
 * suivant, quelles questions alléger, si la notif tombe au bon moment, quels écrans servent.
 * Format CSV : horodatage,evenement,detail.
 */
object Usage {

    const val ENTETE = "horodatage,evenement,detail"
    private const val FICHIER = "usage.csv"

    private fun fichier(context: Context) = File(context.applicationContext.filesDir, FICHIER)

    /** Une ligne CSV sûre : pas de virgule ni de retour à la ligne dans les champs. */
    fun formaterLigne(horodatage: OffsetDateTime, evenement: String, detail: String): String {
        fun propre(s: String) = s.replace(Regex("[,\\r\\n]+"), ";").trim()
        return listOf(horodatage.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), propre(evenement), propre(detail))
            .joinToString(",")
    }

    @Synchronized
    fun log(context: Context, evenement: String, detail: String = "") {
        try {
            val f = fichier(context)
            if (!f.exists()) f.writeText(ENTETE + "\n")
            f.appendText(formaterLigne(OffsetDateTime.now(), evenement, detail) + "\n")
        } catch (_: Exception) {
            // Le journal d'utilisation ne doit jamais faire planter l'appli.
        }
    }

    fun exporter(context: Context): String {
        val f = fichier(context)
        return if (f.exists()) f.readText() else ENTETE + "\n"
    }
}
