package io.github.dash52000.suivinutri.notif

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.dash52000.suivinutri.MainActivity
import io.github.dash52000.suivinutri.R
import io.github.dash52000.suivinutri.data.Journal
import io.github.dash52000.suivinutri.data.Objectif
import io.github.dash52000.suivinutri.data.Usage
import io.github.dash52000.suivinutri.labo.Cap
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Notif du bilan du dimanche (v1.12, remplace le signal de la semaine de la v1.9) : une par semaine, le dimanche à
 * 19 h. Elle annonce le bilan et le soir à surveiller dans la semaine qui vient ; elle ne lit pas le poids (le bilan
 * se calcule à l'ouverture de l'onglet Cap).
 */
object Signal {

    private const val CANAL = "signal_semaine"
    private const val ID_NOTIF = 2
    const val HEURE = 19

    /** Extra posé sur l'intent : l'appli s'ouvre sur l'onglet Cap. */
    const val EXTRA_ONGLET = "onglet"
    const val EXTRA_DEPUIS_SIGNAL = "depuis_signal"

    fun creerCanal(context: Context) {
        val canal = NotificationChannel(CANAL, "Bilan du dimanche", NotificationManager.IMPORTANCE_DEFAULT)
        canal.description = "Une notif par semaine, le dimanche soir : ton bilan est prêt."
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    private fun intentionAlarme(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 10,
            Intent(context, SignalReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Prochain dimanche à 19 h ; celui d'après si la notif de ce dimanche est déjà partie. */
    fun programmer(context: Context) {
        val alarmes = context.getSystemService(AlarmManager::class.java)
        val intention = intentionAlarme(context)
        alarmes.cancel(intention)
        val objectif = Objectif(context)
        if (!objectif.etat().signalActif) return

        val maintenant = ZonedDateTime.now()
        var dimanche = maintenant.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        if (!dimanche.atTime(HEURE, 0).atZone(maintenant.zone).isAfter(maintenant) || objectif.derniereSemaineSignal() == dimanche) {
            dimanche = dimanche.plusWeeks(1)
        }
        val cible = dimanche.atTime(HEURE, 0).atZone(maintenant.zone)
        alarmes.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cible.toInstant().toEpochMilli(), intention)
    }

    private fun nomJour(d: DayOfWeek): String = d.getDisplayName(TextStyle.FULL, Locale.FRENCH)

    fun afficher(context: Context) {
        val objectif = Objectif(context)
        if (!objectif.etat().signalActif) return
        val aujourdHui = LocalDate.now()
        val dimanche = Cap.debutSemaine(aujourdHui)
        if (objectif.derniereSemaineSignal() == dimanche) {
            Usage.log(context, "signal_non_envoye", "deja_envoye_cette_semaine")
            return
        }
        if (!Rappel.notifsAutorisees(context)) {
            Usage.log(context, "signal_non_envoye", "notifs_refusees")
            return
        }
        val moment = Cap.momentQuiDerape(Journal(context).toutes(), aujourdHui)
        val titre = "Ton bilan du dimanche"
        val texte = "Effort de la semaine, balance, ce qu'on a appris et les propositions à valider. Soir à surveiller : " +
            nomJour(moment.jour) + if (moment.parDefaut) " (par défaut, pour le plan du week-end)." else
            " (${moment.derapages} sur ${moment.soirs} ces 8 dernières semaines)."

        val ouvrir = PendingIntent.getActivity(
            context, 11,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_ONGLET, "CAP")
                .putExtra(EXTRA_DEPUIS_SIGNAL, true)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(context, CANAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(titre)
            .setContentText(texte)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texte))
            .setContentIntent(ouvrir)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_NOTIF, notif)
            objectif.noterSignal(dimanche)
            Usage.log(context, "bilan_notif_envoyee", "dimanche=$dimanche;soir_a_risque=${moment.jour};par_defaut=${moment.parDefaut}")
        } catch (_: SecurityException) {
            // Notifs refusées entre-temps : rien à faire.
        }
    }
}

class SignalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Signal.afficher(context)
        Signal.programmer(context)
    }
}
