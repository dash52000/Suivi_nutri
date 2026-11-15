package io.github.dash52000.suivinutri.notif

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.dash52000.suivinutri.MainActivity
import io.github.dash52000.suivinutri.R
import io.github.dash52000.suivinutri.data.Journal
import io.github.dash52000.suivinutri.data.Usage
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Rattrapage du lendemain matin (v1.17) : si la journée d'hier n'a reçu aucune réponse, une notif à 8 h 30 propose de
 * la remplir, sans relance. Les soirs oubliés sont souvent les soirs chargés (dehors, alcool) : les laisser vides
 * tire les effets vers zéro. La saisie différée se retrouve dans le journal d'usage (horodatage de la réponse ≠ jour).
 * Suit l'interrupteur du rappel du soir, avec le sien propre.
 */
object Rattrapage {

    private const val ID_NOTIF = 3
    const val HEURE = 8
    const val MINUTE = 30

    /** Extra posé sur l'intent : date (ISO) à ouvrir dans l'onglet Ce soir. */
    const val EXTRA_DATE = "date_soir"
    const val EXTRA_DEPUIS_RATTRAPAGE = "depuis_rattrapage"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("reglages", Context.MODE_PRIVATE)

    fun actif(context: Context): Boolean = Rappel.actif(context) && prefs(context).getBoolean("rattrapage_actif", true)
    fun reglageActif(context: Context): Boolean = prefs(context).getBoolean("rattrapage_actif", true)

    fun regler(context: Context, actif: Boolean) {
        prefs(context).edit().putBoolean("rattrapage_actif", actif).apply()
        programmer(context)
    }

    private fun intentionAlarme(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 20,
            Intent(context, RattrapageReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Prochain matin à 8 h 30 (alarme inexacte). */
    fun programmer(context: Context) {
        val alarmes = context.getSystemService(AlarmManager::class.java)
        val intention = intentionAlarme(context)
        alarmes.cancel(intention)
        if (!actif(context)) return
        val maintenant = ZonedDateTime.now()
        var cible = maintenant.toLocalDate().atTime(HEURE, MINUTE).atZone(maintenant.zone)
        if (!cible.isAfter(maintenant)) cible = cible.plusDays(1)
        alarmes.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cible.toInstant().toEpochMilli(), intention)
    }

    /** Retire la notif (hier vient d'être rempli autrement). */
    fun retirer(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_NOTIF)
    }

    fun afficher(context: Context) {
        if (!actif(context)) return
        if (!Rappel.notifsAutorisees(context)) {
            Usage.log(context, "rattrapage_non_envoye", "notifs_refusees")
            return
        }
        val hier = LocalDate.now().minusDays(1)
        if (!Journal(context).entree(hier).vide) {
            Usage.log(context, "rattrapage_non_envoye", "deja_rempli")
            return
        }
        val ouvrir = PendingIntent.getActivity(
            context, 21,
            Intent(context, MainActivity::class.java)
                .putExtra(Signal.EXTRA_ONGLET, "SOIR")
                .putExtra(EXTRA_DATE, hier.toString())
                .putExtra(EXTRA_DEPUIS_RATTRAPAGE, true)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(context, Rappel.CANAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Hier n'est pas rempli")
            .setContentText("Quelques taps si ça te revient. Sinon, ça reste vide.")
            .setContentIntent(ouvrir)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_NOTIF, notif)
            Usage.log(context, "rattrapage_envoye", "jour=$hier")
        } catch (_: SecurityException) {
            // Notifs refusées entre-temps : rien à faire.
        }
    }
}

class RattrapageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Rattrapage.afficher(context)
        Rattrapage.programmer(context)
    }
}
