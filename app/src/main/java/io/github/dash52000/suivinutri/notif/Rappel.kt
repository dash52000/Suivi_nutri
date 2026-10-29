package io.github.dash52000.suivinutri.notif

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.dash52000.suivinutri.MainActivity
import io.github.dash52000.suivinutri.R
import io.github.dash52000.suivinutri.data.Journal
import java.time.LocalDate
import java.time.ZonedDateTime

/** Notif quotidienne qui ouvre le questionnaire du soir. Pas de relance si elle est ignorée. */
object Rappel {

    private const val CANAL = "rappel_soir"
    private const val ID_NOTIF = 1

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("reglages", Context.MODE_PRIVATE)

    fun actif(context: Context): Boolean = prefs(context).getBoolean("rappel_actif", true)
    fun heure(context: Context): Int = prefs(context).getInt("rappel_heure", 21)
    fun minute(context: Context): Int = prefs(context).getInt("rappel_minute", 15)

    fun regler(context: Context, actif: Boolean, heure: Int, minute: Int) {
        prefs(context).edit()
            .putBoolean("rappel_actif", actif)
            .putInt("rappel_heure", heure)
            .putInt("rappel_minute", minute)
            .apply()
        programmer(context)
    }

    fun notifsAutorisees(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun creerCanal(context: Context) {
        val canal = NotificationChannel(CANAL, "Questionnaire du soir", NotificationManager.IMPORTANCE_DEFAULT)
        canal.description = "Un rappel par jour pour remplir le questionnaire."
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    private fun intentionAlarme(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, RappelReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Programme la prochaine notif (alarme inexacte, à quelques minutes près). */
    fun programmer(context: Context) {
        val alarmes = context.getSystemService(AlarmManager::class.java)
        val intention = intentionAlarme(context)
        alarmes.cancel(intention)
        if (!actif(context)) return

        val maintenant = ZonedDateTime.now()
        var cible = maintenant.toLocalDate()
            .atTime(heure(context), minute(context))
            .atZone(maintenant.zone)
        if (!cible.isAfter(maintenant)) cible = cible.plusDays(1)

        alarmes.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cible.toInstant().toEpochMilli(), intention)
    }

    fun afficher(context: Context) {
        if (!actif(context) || !notifsAutorisees(context)) return
        // Déjà rempli aujourd'hui : pas besoin de rappel.
        if (!Journal(context).entree(LocalDate.now()).vide) return

        val ouvrir = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(context, CANAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Ton point du soir")
            .setContentText("Quelques taps sur ta journée, quand tu veux.")
            .setContentIntent(ouvrir)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_NOTIF, notif)
        } catch (_: SecurityException) {
            // Notifs refusées entre-temps : rien à faire.
        }
    }
}

class RappelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Rappel.afficher(context)
        Rappel.programmer(context)
    }
}

/** Reprogramme la notif après un redémarrage du téléphone ou une mise à jour de l'appli. */
class DemarrageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Rappel.programmer(context)
        }
    }
}
