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
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Signal de la semaine (v1.9) : une notif par semaine au maximum, à 18 h, le soir qui dérape le plus d'après
 * le journal (vendredi par défaut, pour le plan du week-end). Indépendant du rappel du soir.
 */
object Signal {

    private const val CANAL = "signal_semaine"
    private const val ID_NOTIF = 2
    const val HEURE = 18

    /** Extra posé sur l'intent : l'appli s'ouvre sur l'onglet Cap. */
    const val EXTRA_ONGLET = "onglet"
    const val EXTRA_DEPUIS_SIGNAL = "depuis_signal"

    fun creerCanal(context: Context) {
        val canal = NotificationChannel(CANAL, "Signal de la semaine", NotificationManager.IMPORTANCE_DEFAULT)
        canal.description = "Une notif par semaine au plus, le soir qui dérape le plus souvent."
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    private fun intentionAlarme(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 10,
            Intent(context, SignalReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Prochain soir qui dérape à 18 h ; la semaine suivante si le signal de cette semaine est déjà parti. */
    fun programmer(context: Context) {
        val alarmes = context.getSystemService(AlarmManager::class.java)
        val intention = intentionAlarme(context)
        alarmes.cancel(intention)
        val objectif = Objectif(context)
        if (!objectif.etat().signalActif) return

        val maintenant = ZonedDateTime.now()
        val aujourdHui = maintenant.toLocalDate()
        val jour = Cap.momentQuiDerape(Journal(context).toutes(), aujourdHui).jour
        var date = aujourdHui.with(TemporalAdjusters.nextOrSame(jour))
        var cible = date.atTime(HEURE, 0).atZone(maintenant.zone)
        if (!cible.isAfter(maintenant) || objectif.derniereSemaineSignal() == Cap.debutSemaine(date)) {
            date = aujourdHui.with(TemporalAdjusters.next(jour))
            if (objectif.derniereSemaineSignal() == Cap.debutSemaine(date)) date = date.plusWeeks(1)
            cible = date.atTime(HEURE, 0).atZone(maintenant.zone)
        }
        alarmes.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cible.toInstant().toEpochMilli(), intention)
    }

    private fun nomJour(d: java.time.DayOfWeek): String = d.getDisplayName(TextStyle.FULL, Locale.FRENCH)

    private fun nombre(x: Double): String = String.format(Locale.FRENCH, "%.1f", x).removeSuffix(",0")

    fun afficher(context: Context) {
        val objectif = Objectif(context)
        val etat = objectif.etat()
        if (!etat.signalActif) return
        val aujourdHui = LocalDate.now()
        val semaine = Cap.debutSemaine(aujourdHui)
        if (objectif.derniereSemaineSignal() == semaine) {
            Usage.log(context, "signal_non_envoye", "deja_envoye_cette_semaine")
            return
        }
        if (!Rappel.notifsAutorisees(context)) {
            Usage.log(context, "signal_non_envoye", "notifs_refusees")
            return
        }
        val entrees = Journal(context).toutes()
        val moment = Cap.momentQuiDerape(entrees, aujourdHui)
        val bieres = (0L..6L).map { semaine.plusDays(it) }.filter { !it.isAfter(aujourdHui) }
            .mapNotNull { Cap.bieresEq33(entrees[it]) }.sum()
        val budget = objectif.budgetCourant()
        val levier = etat.leviers[semaine]

        val titre = nomJour(aujourdHui.dayOfWeek).replaceFirstChar { it.uppercase() } + " soir"
        val texte = buildString {
            when {
                !moment.parDefaut && moment.jour == aujourdHui.dayOfWeek ->
                    append("D'habitude, ça dérape : ${moment.derapages} ${nomJour(moment.jour)}s sur ${moment.soirs}.")
                aujourdHui.dayOfWeek == java.time.DayOfWeek.FRIDAY ->
                    append("Le week-end arrive : 30 s pour ton plan.")
                else -> append("Point de la semaine.")
            }
            if (levier != null) append(" Levier : ${levier.lowercase()}.")
            append(" Bières : ${nombre(bieres)} / $budget.")
        }

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
            objectif.noterSignal(semaine)
            Usage.log(
                context, "signal_envoye",
                "jour=${aujourdHui.dayOfWeek};par_defaut=${moment.parDefaut};derapages=${moment.derapages}/${moment.soirs}",
            )
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
