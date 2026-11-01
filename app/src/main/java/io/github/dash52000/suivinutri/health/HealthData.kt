package io.github.dash52000.suivinutri.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId

/** Une séance lue dans Health Connect. */
data class Activite(
    val debut: LocalDateTime,
    val dureeMin: Long,
    val type: String,
    /** Course, rando, vélo, natation : ce qui vide vraiment le glycogène sur une longue durée. */
    val endurance: Boolean = false,
)

/** Résumé d'une journée, tel que lu dans Health Connect. Null = pas de donnée. */
data class Journee(
    val date: LocalDate,
    val poidsKg: Double?,
    /** Heure locale de la pesée retenue (Withings en priorité, sinon la première du jour). */
    val poidsHeure: Int? = null,
    val kcalTotal: Double?,
    val sommeilMin: Long?,
    val activites: List<Activite>,
    /** Source de la pesée retenue : withings, garmin… */
    val poidsSource: String? = null,
    /** Composition de la balance (bio-impédance), mesure retenue du jour. */
    val graissePct: Double? = null,
    val masseMaigreKg: Double? = null,
    val eauKg: Double? = null,
    val osKg: Double? = null,
)

/**
 * Sert à vérifier le risque de double comptage des calories :
 * on compare le total agrégé par Health Connect à la somme naïve des enregistrements bruts.
 */
data class DiagnosticCalories(
    val date: LocalDate,
    val totalAgrege: Double?,
    val nbEnregistrements: Int,
    val sommeNaive: Double,
    val sources: List<String>,
)

class HealthData(context: Context) {

    private val appContext = context.applicationContext
    private val zone: ZoneId get() = ZoneId.systemDefault()

    val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(appContext) }

    companion object {
        /** Permissions indispensables : sans elles, l'appli ne lit rien. */
        val PERMISSIONS_BASE: Set<String> = setOf(
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
        )

        val PERMISSION_HISTORIQUE: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

        /** Composition corporelle (balance Withings) : facultative, l'appli fonctionne sans. */
        val PERMISSIONS_COMPOSITION: Set<String> = setOf(
            HealthPermission.getReadPermission(BodyFatRecord::class),
            HealthPermission.getReadPermission(LeanBodyMassRecord::class),
            HealthPermission.getReadPermission(BodyWaterMassRecord::class),
            HealthPermission.getReadPermission(BoneMassRecord::class),
        )

        fun statut(context: Context): Int = HealthConnectClient.getSdkStatus(context)
    }

    /** L'accès à l'historique au-delà de 30 jours n'existe pas sur toutes les versions de Health Connect. */
    suspend fun historiqueDisponible(): Boolean =
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    suspend fun permissionsADemander(): Set<String> =
        PERMISSIONS_BASE + PERMISSIONS_COMPOSITION + if (historiqueDisponible()) setOf(PERMISSION_HISTORIQUE) else emptySet()

    suspend fun permissionsAccordees(): Set<String> =
        client.permissionController.getGrantedPermissions()

    /** Lit toutes les pages d'un type d'enregistrement sur une période. */
    private suspend inline fun <reified T : Record> lireTout(debut: Instant, fin: Instant): List<T> {
        val resultat = mutableListOf<T>()
        var page: String? = null
        do {
            val reponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = T::class,
                    timeRangeFilter = TimeRangeFilter.between(debut, fin),
                    pageToken = page,
                )
            )
            resultat += reponse.records
            page = reponse.pageToken
        } while (page != null)
        return resultat
    }

    private fun LocalDate.debutInstant(): Instant = atStartOfDay(zone).toInstant()

    /**
     * Calories totales par jour, agrégées par Health Connect (qui gère les doublons entre sources).
     * Par tranches de 60 jours, pour rester sous les limites de l'API sur les longues périodes.
     */
    private suspend fun kcalParJour(debut: LocalDate, fin: LocalDate): Map<LocalDate, Double?> {
        val resultat = mutableMapOf<LocalDate, Double?>()
        var tranche = debut
        while (!tranche.isAfter(fin)) {
            val finTranche = minOf(tranche.plusDays(59), fin)
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(
                        tranche.atStartOfDay(),
                        finTranche.plusDays(1).atStartOfDay(),
                    ),
                    timeRangeSlicer = Period.ofDays(1),
                )
            ).forEach { groupe ->
                resultat[groupe.startTime.toLocalDate()] =
                    groupe.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
            }
            tranche = finTranche.plusDays(1)
        }
        return resultat
    }

    /** Durée de sommeil d'une nuit, sans les phases d'éveil quand Garmin fournit les phases. */
    private fun dureeSommeilMin(nuit: SleepSessionRecord): Long {
        val eveil = setOf(
            SleepSessionRecord.STAGE_TYPE_AWAKE,
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
        )
        return if (nuit.stages.isEmpty()) {
            Duration.between(nuit.startTime, nuit.endTime).toMinutes()
        } else {
            nuit.stages
                .filter { it.stage !in eveil }
                .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
        }
    }

    /** Résumé jour par jour, du plus récent au plus ancien. */
    suspend fun journees(debut: LocalDate, fin: LocalDate): List<Journee> {
        val t0 = debut.debutInstant()
        val t1 = fin.plusDays(1).debutInstant()

        // Poids : la balance Withings en priorité, sinon la première pesée du jour.
        val poids = lireTout<WeightRecord>(t0, t1)
            .groupBy { it.time.atZone(zone).toLocalDate() }
            .mapValues { (_, pesees) -> Sources.preferer(pesees, { it.metadata.dataOrigin.packageName }, { it.time })!! }

        // Composition : seulement si l'accès a été accordé (facultatif).
        val accordees = permissionsAccordees()
        suspend fun <T> parJour(
            permission: String,
            lire: suspend () -> List<T>,
            instant: (T) -> Instant,
            paquet: (T) -> String,
        ): Map<LocalDate, T> =
            if (permission !in accordees) emptyMap()
            else lire()
                .groupBy { instant(it).atZone(zone).toLocalDate() }
                .mapValues { (_, mesures) -> Sources.preferer(mesures, paquet, instant)!! }

        val graisse = parJour(
            HealthPermission.getReadPermission(BodyFatRecord::class),
            { lireTout<BodyFatRecord>(t0, t1) }, { it.time }, { it.metadata.dataOrigin.packageName },
        )
        val maigre = parJour(
            HealthPermission.getReadPermission(LeanBodyMassRecord::class),
            { lireTout<LeanBodyMassRecord>(t0, t1) }, { it.time }, { it.metadata.dataOrigin.packageName },
        )
        val eau = parJour(
            HealthPermission.getReadPermission(BodyWaterMassRecord::class),
            { lireTout<BodyWaterMassRecord>(t0, t1) }, { it.time }, { it.metadata.dataOrigin.packageName },
        )
        val os = parJour(
            HealthPermission.getReadPermission(BoneMassRecord::class),
            { lireTout<BoneMassRecord>(t0, t1) }, { it.time }, { it.metadata.dataOrigin.packageName },
        )

        // Sommeil : rattaché au jour du réveil, Garmin seulement (une autre appli le recopie, d'où des doubles).
        val sommeil = Sources.garminSeulement(lireTout<SleepSessionRecord>(t0.minus(Duration.ofHours(12)), t1)) {
            it.metadata.dataOrigin.packageName
        }
            .groupBy { it.endTime.atZone(zone).toLocalDate() }
            .mapValues { (_, nuits) -> nuits.sumOf { dureeSommeilMin(it) } }

        // Séances : Garmin seulement, pour la même raison.
        val activites = Sources.garminSeulement(lireTout<ExerciseSessionRecord>(t0, t1)) { it.metadata.dataOrigin.packageName }
            .map {
                Activite(
                    debut = it.startTime.atZone(zone).toLocalDateTime(),
                    dureeMin = Duration.between(it.startTime, it.endTime).toMinutes(),
                    type = libelleActivite(it.exerciseType, it.title),
                    endurance = it.exerciseType in TYPES_ENDURANCE,
                )
            }
            .groupBy { it.debut.toLocalDate() }

        val kcal = kcalParJour(debut, fin)

        return generateSequence(fin) { it.minusDays(1) }
            .takeWhile { !it.isBefore(debut) }
            .map { jour ->
                Journee(
                    date = jour,
                    poidsKg = poids[jour]?.weight?.inKilograms,
                    poidsHeure = poids[jour]?.time?.atZone(zone)?.hour,
                    poidsSource = poids[jour]?.metadata?.dataOrigin?.packageName?.let { Sources.libelle(it) },
                    graissePct = graisse[jour]?.percentage?.value,
                    masseMaigreKg = maigre[jour]?.mass?.inKilograms,
                    eauKg = eau[jour]?.mass?.inKilograms,
                    osKg = os[jour]?.mass?.inKilograms,
                    kcalTotal = kcal[jour],
                    sommeilMin = sommeil[jour],
                    activites = activites[jour].orEmpty().sortedBy { it.debut },
                )
            }
            .toList()
    }

    suspend fun diagnosticCalories(jour: LocalDate): DiagnosticCalories {
        val bruts = lireTout<TotalCaloriesBurnedRecord>(
            jour.debutInstant(),
            jour.plusDays(1).debutInstant(),
        )
        return DiagnosticCalories(
            date = jour,
            totalAgrege = kcalParJour(jour, jour)[jour],
            nbEnregistrements = bruts.size,
            sommeNaive = bruts.sumOf { it.energy.inKilocalories },
            sources = bruts.map { it.metadata.dataOrigin.packageName }.distinct(),
        )
    }
}

private val TYPES_ENDURANCE = setOf(
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING,
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
)

fun libelleActivite(type: Int, titre: String?): String = when (type) {
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Course"
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Marche"
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Rando"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Vélo"
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Renfo"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Natation"
    ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "Foot"
    ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
    else -> titre?.takeIf { it.isNotBlank() } ?: "Activité"
}
