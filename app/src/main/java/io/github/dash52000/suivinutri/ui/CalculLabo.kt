package io.github.dash52000.suivinutri.ui

import android.content.Context
import io.github.dash52000.suivinutri.data.Configuration
import io.github.dash52000.suivinutri.data.EntreeJour
import io.github.dash52000.suivinutri.data.Journal
import io.github.dash52000.suivinutri.data.Usage
import io.github.dash52000.suivinutri.health.HealthData
import io.github.dash52000.suivinutri.labo.JourLabo
import io.github.dash52000.suivinutri.labo.Kalman
import io.github.dash52000.suivinutri.labo.Labo
import io.github.dash52000.suivinutri.labo.LigneCalendrier
import io.github.dash52000.suivinutri.labo.ResultatLabo
import io.github.dash52000.suivinutri.labo.collecterJours
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Calcul du labo partagé par les onglets Labo, Données et Cap, gardé tant que l'appli tourne : changer d'onglet ne
 * relance pas la lecture d'un an de Health Connect. Recalcul si le jour, le journal ou les questions changent,
 * si le calcul a plus de 3 heures, ou à la demande. Un seul calcul à la fois : le second onglet attend le premier.
 */
internal object CalculLabo {

    class Calcul(
        val resultat: ResultatLabo,
        val calendrier: List<LigneCalendrier>,
        val dates28: List<LocalDate>,
        val calculeLe: Instant,
        /** Jours lus (Health Connect + journal) et tendance du poids : l'onglet Cap s'en sert (v1.9). */
        val jours: List<JourLabo>,
        val lissage: Kalman.Lissage?,
        val aujourdHui: LocalDate,
    )

    const val DUREE_CACHE_HEURES = 3L

    private val verrou = Mutex()

    @Volatile private var dernier: Calcul? = null

    @Volatile private var cle: String = ""

    private fun cleCache(journal: Journal, config: Configuration): String =
        "${LocalDate.now()}|${journal.derniereModification()}|${config.signature()}"

    /** Dernier calcul s'il est encore valable, sans rien lire. */
    fun enCache(journal: Journal, config: Configuration): Calcul? {
        val c = dernier ?: return null
        val age = Duration.between(c.calculeLe, Instant.now())
        return if (cle == cleCache(journal, config) && age < Duration.ofHours(DUREE_CACHE_HEURES)) c else null
    }

    /** [origine] : onglet qui demande le calcul, pour le journal d'utilisation. */
    suspend fun obtenir(
        context: Context,
        health: HealthData,
        journal: Journal,
        config: Configuration,
        forcer: Boolean,
        origine: String,
    ): Calcul = verrou.withLock {
        if (!forcer) {
            enCache(journal, config)?.let {
                Usage.log(context, origine, "cache")
                return@withLock it
            }
        }
        Usage.log(context, origine, if (forcer) "calcul_force" else "calcul_auto")
        val nouvelleCle = cleCache(journal, config)
        val aujourdHui = LocalDate.now()
        val jours = collecterJours(health, journal, aujourdHui)
        val entrees = journal.toutes()
        val perso = config.perso().map { q -> q.libelle to { e: EntreeJour -> q.score(e.reponses[q.id]) } }
        val dates28 = (27L downTo 0L).map { aujourdHui.minusDays(it) }
        val calcul = withContext(Dispatchers.Default) {
            Calcul(
                resultat = Labo.analyser(jours, aujourdHui, perso),
                calendrier = Labo.calendrier(entrees, dates28, Labo.reflexes + perso),
                dates28 = dates28,
                calculeLe = Instant.now(),
                jours = jours,
                lissage = Labo.lissage(jours, aujourdHui),
                aujourdHui = aujourdHui,
            )
        }
        dernier = calcul
        cle = nouvelleCle
        calcul
    }
}
