package com.weighttrack.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Volume
import com.weighttrack.core.model.EntrySource
import com.weighttrack.core.model.WeightEntry
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.ProfileRepository
import com.weighttrack.data.repo.WeightRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class HealthConnectAvailability {
    INSTALLED,
    UPDATE_REQUIRED,
    NOT_SUPPORTED,
}

/** One day of movement, as Health Connect reported it. Nulls mean "not recorded". */
data class DailyActivity(
    val date: LocalDate,
    val steps: Long?,
    val activeKilocalories: Double?,
    /**
     * Hours asleep on the night ending that morning.
     *
     * Filed under the morning rather than the evening, so it lines up with the weight recorded
     * after it. A night's sleep and the weigh-in that follows belong to the same day as far as
     * anybody looking for a pattern is concerned.
     */
    val sleepHours: Double? = null,
)

data class HealthConnectSyncResult(
    val imported: Int,
    val exported: Int,
    val skipped: Int,
)

/**
 * Two-way sync with Health Connect.
 *
 * This is how a Withings, Renpho or Samsung scale reaches the app without WeightTrack ever
 * touching Bluetooth. The hard part is not reading records, it is not creating duplicates:
 * every record written carries a client record id, and every record read is matched against
 * both that and the Health Connect id before anything is inserted.
 */
@Singleton
class HealthConnectSync @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val weightRepository: WeightRepository,
    private val settingsRepository: SettingsRepository,
    private val profileRepository: ProfileRepository,
) {

    /**
     * Whose readings Health Connect exchanges.
     *
     * A household that has claimed it for one person syncs that person, whoever is on screen.
     * With nobody claiming it, which is every single-profile install, it follows the active
     * profile exactly as it did before profiles existed.
     */
    private suspend fun syncProfileId(): Long =
        profileRepository.healthConnectId() ?: profileRepository.activeId()

    /**
     * What weight sync itself needs. Kept separate from the full set so that adding a new
     * optional permission later cannot make an existing user's working sync report itself as
     * unauthorised until they re-grant everything.
     */
    val corePermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(HeightRecord::class),
    )

    /** Writing water. Refusing it costs the hydration records and nothing else. */
    val hydrationPermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(HydrationRecord::class),
    )

    /**
     * Writing food. Refusing it costs the nutrition records and nothing else.
     *
     * Only write. Reading other apps' meals back would double every day for anybody who logs in
     * two places, and there is no way to tell a duplicate from a second helping.
     */
    val nutritionPermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(NutritionRecord::class),
    )

    /** Read-only extras. Nothing breaks when these are refused. */
    val activityPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    )

    /**
     * Sleep, read only, and its own grant.
     *
     * Asked for separately because plenty of people are happy to share step counts and not the
     * hours they were in bed. Refusing it costs one card and nothing else.
     */
    val sleepPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )

    val permissions: Set<String> =
        corePermissions + hydrationPermissions + nutritionPermissions + activityPermissions +
            sleepPermissions

    suspend fun hasNutritionPermission(): Boolean = runCatching {
        clientOrNull()?.permissionController?.getGrantedPermissions()
            ?.containsAll(nutritionPermissions) == true
    }.getOrDefault(false)

    /**
     * Writes one logged meal.
     *
     * The record identifier is the log row's own, so the same meal edited and written again
     * replaces its record rather than adding a second one. Health Connect deduplicates on it,
     * which is the only reason this is safe to call more than once.
     */
    suspend fun writeNutrition(
        instant: java.time.Instant,
        kcal: Double,
        proteinG: Double?,
        carbsG: Double?,
        fatG: Double?,
        name: String?,
        mealType: Int,
        clientRecordId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val client = clientOrNull() ?: return@runCatching false
            // Only the nutrition grant matters here. Gating on the whole set would drop meals
            // for somebody who allowed food but not, say, the height read.
            if (!hasNutritionPermission()) return@runCatching false
            val zone = ZoneId.systemDefault()
            client.insertRecords(
                listOf(
                    NutritionRecord(
                        startTime = instant,
                        startZoneOffset = zone.rules.getOffset(instant),
                        endTime = instant.plusSeconds(1),
                        endZoneOffset = zone.rules.getOffset(instant),
                        energy = Energy.kilocalories(kcal),
                        protein = proteinG?.let { Mass.grams(it) },
                        totalCarbohydrate = carbsG?.let { Mass.grams(it) },
                        totalFat = fatG?.let { Mass.grams(it) },
                        name = name,
                        mealType = mealType,
                        metadata = Metadata.manualEntry(
                            device = Device(type = Device.TYPE_PHONE),
                            clientRecordId = clientRecordId,
                        ),
                    ),
                ),
            )
            true
        }.getOrDefault(false)
    }

    /** Removes a record when its meal is deleted, so the two do not drift apart. */
    suspend fun deleteNutrition(clientRecordId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val client = clientOrNull() ?: return@runCatching false
            if (!hasNutritionPermission()) return@runCatching false
            client.deleteRecords(
                NutritionRecord::class,
                recordIdsList = emptyList(),
                clientRecordIdsList = listOf(clientRecordId),
            )
            true
        }.getOrDefault(false)
    }

    fun availability(): HealthConnectAvailability =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.INSTALLED
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthConnectAvailability.UPDATE_REQUIRED
            else -> HealthConnectAvailability.NOT_SUPPORTED
        }

    private fun clientOrNull(): HealthConnectClient? =
        if (availability() == HealthConnectAvailability.INSTALLED) {
            runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
        } else {
            null
        }

    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun hasPermissions(): Boolean = hasGranted(corePermissions)

    /**
     * Whether everything the app can use has been allowed.
     *
     * Separate from [hasPermissions] on purpose. Weight is what Health Connect is for here and
     * the app works with only that, but somebody who connected before food and water existed has
     * granted the core set and nothing else, and asking only about the core set would mean the
     * screen never offered them the rest.
     */
    suspend fun hasEverything(): Boolean = hasGranted(permissions)

    suspend fun hasActivityPermissions(): Boolean = hasGranted(activityPermissions)

    suspend fun hasSleepPermission(): Boolean = hasGranted(sleepPermissions)

    /**
     * Hours asleep per morning, over the last [days].
     *
     * Read as sessions rather than aggregated by the platform, because a session that starts at
     * eleven at night belongs to the next morning's weigh-in and an aggregate sliced by calendar
     * day would file most of it under the evening before.
     */
    suspend fun readSleepHours(days: Long = 120): Map<LocalDate, Double> =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = clientOrNull() ?: return@runCatching emptyMap()
                if (!hasSleepPermission()) return@runCatching emptyMap()
                val zone = ZoneId.systemDefault()
                val end = LocalDate.now().plusDays(1).atStartOfDay()
                val start = end.minusDays(days)
                val sessions = readAllPages { token ->
                    val page = client.readRecords(
                        ReadRecordsRequest(
                            recordType = SleepSessionRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(start, end),
                            pageToken = token,
                        ),
                    )
                    page.records to page.pageToken
                }
                val byMorning = mutableMapOf<LocalDate, Double>()
                for (session in sessions) {
                    val hours =
                        (session.endTime.toEpochMilli() - session.startTime.toEpochMilli()) /
                            3_600_000.0
                    // A nap is not a night. Counting them would make a restless day look like a
                    // long sleep.
                    if (hours < MINIMUM_SLEEP_HOURS) continue
                    val morning = session.endTime.atZone(zone).toLocalDate()
                    byMorning[morning] = (byMorning[morning] ?: 0.0) + hours
                }
                byMorning.toMap()
            }.getOrDefault(emptyMap())
        }

    suspend fun hasHydrationPermission(): Boolean = hasGranted(hydrationPermissions)

    private suspend fun hasGranted(required: Set<String>): Boolean {
        val client = clientOrNull() ?: return false
        return runCatching {
            client.permissionController.getGrantedPermissions().containsAll(required)
        }.getOrDefault(false)
    }

    /**
     * Daily steps and active calories, so movement can be read against the weight trend.
     *
     * Days with no record are left out rather than reported as zero: "you did not wear the
     * watch" and "you did not move" are different things, and showing the second when the
     * first happened makes the whole card a lie.
     */
    suspend fun readDailyActivity(days: Long = 30): List<DailyActivity> = withContext(Dispatchers.IO) {
        runCatching {
            val client = clientOrNull() ?: return@runCatching emptyList()
            if (!hasActivityPermissions()) return@runCatching emptyList()
            val end = LocalDate.now().plusDays(1).atStartOfDay()
            val start = end.minusDays(days)
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(
                        StepsRecord.COUNT_TOTAL,
                        ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                    ),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    timeRangeSlicer = Period.ofDays(1),
                ),
            ).mapNotNull { bucket ->
                val steps = bucket.result[StepsRecord.COUNT_TOTAL]
                val kcal = bucket.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]
                    ?.inKilocalories
                if (steps == null && kcal == null) return@mapNotNull null
                DailyActivity(
                    date = bucket.startTime.toLocalDate(),
                    steps = steps,
                    activeKilocalories = kcal,
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Pulls new readings in, then pushes ours out.
     *
     * The read window reaches back years rather than days because the whole point for a new
     * user is to inherit the history their scale app already holds. Reading beyond thirty days
     * needs the history permission; without it Health Connect simply returns less, which is
     * why this does not fail when the grant is missing.
     */
    suspend fun sync(sinceDays: Long = 365 * 5): Result<HealthConnectSyncResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = clientOrNull() ?: error("Health Connect is not available on this device.")
                if (!hasPermissions()) error("WeightTrack has not been granted Health Connect access.")

                val zone = ZoneId.systemDefault()
                val start = Instant.now().minus(sinceDays, ChronoUnit.DAYS)
                val imported = importWeights(client, start, zone)
                val exported = exportWeights(client, zone)
                HealthConnectSyncResult(
                    imported = imported.first,
                    exported = exported,
                    skipped = imported.second,
                )
            }
        }

    private suspend fun importWeights(
        client: HealthConnectClient,
        start: Instant,
        zone: ZoneId,
    ): Pair<Int, Int> {
        val now = Instant.now()
        val records = readAllPages { token ->
            val page = client.readRecords(
                ReadRecordsRequest(
                    recordType = WeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, now),
                    pageToken = token,
                ),
            )
            page.records to page.pageToken
        }
        var imported = 0
        var skipped = 0
        records.forEach { record ->
            val grams = (record.weight.inKilograms * 1000).toInt()
            if (grams <= 0) {
                skipped++
                return@forEach
            }
            // A record we wrote comes back carrying our own client id. Re-importing it would
            // be harmless thanks to the upsert, but skipping keeps the counts honest.
            val ourClientId = record.metadata.clientRecordId
            if (
                ourClientId != null &&
                weightRepository.byClientRecordIdFor(syncProfileId(), ourClientId) != null
            ) {
                skipped++
                return@forEach
            }
            weightRepository.addFor(
                profileId = syncProfileId(),
                grams = grams,
                timestamp = record.time,
                zone = zone,
                source = EntrySource.HEALTH_CONNECT,
                healthConnectId = record.metadata.id,
                clientRecordId = ourClientId ?: "hc:${record.metadata.id}",
            )
            imported++
        }
        return imported to skipped
    }

    private suspend fun exportWeights(client: HealthConnectClient, zone: ZoneId): Int {
        val settings = settingsRepository.settings.first()
        val entries = weightRepository.entriesFor(syncProfileId())
            .filter { it.source != EntrySource.HEALTH_CONNECT }
        if (entries.isEmpty()) return 0

        val records = entries.map { entry -> entry.toWeightRecord(zone) }
        // Insert is an upsert when the client record id matches, so pushing the same log twice
        // updates rather than duplicates. Batched because a first sync can be thousands of rows.
        var written = 0
        records.chunked(BATCH_SIZE).forEach { batch ->
            runCatching { client.insertRecords(batch) }
                .onSuccess { written += batch.size }
        }
        // Height is written once, not per reading, so it never floods the other app's log.
        settings.profile.heightMm.takeIf { it > 0 }?.let { heightMm ->
            runCatching {
                client.insertRecords(
                    listOf(
                        HeightRecord(
                            time = Instant.now(),
                            zoneOffset = zone.rules.getOffset(Instant.now()),
                            height = androidx.health.connect.client.units.Length.meters(heightMm / 1000.0),
                            metadata = Metadata.manualEntry(device = Device(type = Device.TYPE_PHONE)),
                        ),
                    ),
                )
            }
        }
        return written
    }

    private fun WeightEntry.toWeightRecord(zone: ZoneId): WeightRecord = WeightRecord(
        time = timestamp,
        zoneOffset = zone.rules.getOffset(timestamp),
        weight = Mass.kilograms(grams / 1000.0),
        metadata = Metadata.manualEntry(
            device = Device(type = Device.TYPE_PHONE),
            clientRecordId = clientRecordId,
        ),
    )

    /**
     * Sends one drink to Health Connect.
     *
     * Written as it happens rather than in a batch, because hydration is the one figure other
     * apps read live. A failure is swallowed: the drink is already saved locally, and a
     * blocking error over a glass of water would be worse than a missing record.
     */
    suspend fun writeHydration(
        millilitres: Int,
        instant: Instant,
        clientRecordId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val client = clientOrNull() ?: return@runCatching false
            // Only the hydration grant matters here. Gating on the whole set would drop water
            // records for someone who allowed water but not, say, the height read.
            if (!hasHydrationPermission()) return@runCatching false
            val zone = ZoneId.systemDefault()
            client.insertRecords(
                listOf(
                    HydrationRecord(
                        startTime = instant,
                        startZoneOffset = zone.rules.getOffset(instant),
                        endTime = instant.plusSeconds(1),
                        endZoneOffset = zone.rules.getOffset(instant),
                        volume = Volume.milliliters(millilitres.toDouble()),
                        metadata = Metadata.manualEntry(
                            device = Device(type = Device.TYPE_PHONE),
                            clientRecordId = clientRecordId,
                        ),
                    ),
                ),
            )
            true
        }.getOrDefault(false)
    }

    /** Body fat is written separately because Health Connect models it as its own record. */
    suspend fun exportBodyFat(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val client = clientOrNull() ?: return@runCatching 0
            val zone = ZoneId.systemDefault()
            val records = weightRepository.entriesFor(syncProfileId())
                .filter { it.bodyFatPercent != null && it.source != EntrySource.HEALTH_CONNECT }
                .map { entry ->
                    BodyFatRecord(
                        time = entry.timestamp,
                        zoneOffset = zone.rules.getOffset(entry.timestamp),
                        percentage = Percentage(entry.bodyFatPercent!!),
                        metadata = Metadata.manualEntry(
                            device = Device(type = Device.TYPE_PHONE),
                            clientRecordId = "bf:${entry.clientRecordId}",
                        ),
                    )
                }
            if (records.isEmpty()) return@runCatching 0
            records.chunked(BATCH_SIZE).forEach { client.insertRecords(it) }
            records.size
        }
    }

    companion object {
        private const val BATCH_SIZE = 200

        /** Shorter than this is a nap, and a nap is not a night's sleep. */
        private const val MINIMUM_SLEEP_HOURS = 3.0

        /** How Health Connect names the meals this app splits a day into. */
        fun mealTypeFor(meal: com.weighttrack.core.nutrition.Meal): Int = when (meal) {
            com.weighttrack.core.nutrition.Meal.BREAKFAST -> MealType.MEAL_TYPE_BREAKFAST
            com.weighttrack.core.nutrition.Meal.LUNCH -> MealType.MEAL_TYPE_LUNCH
            com.weighttrack.core.nutrition.Meal.DINNER -> MealType.MEAL_TYPE_DINNER
            com.weighttrack.core.nutrition.Meal.SNACK -> MealType.MEAL_TYPE_SNACK
        }

        /** One identifier per log row, so writing the same meal twice replaces it. */
        fun nutritionRecordId(logEntryId: Long): String = "food:$logEntryId"

        /**
         * The moment a meal belongs to: its own day, at the time of day it was entered.
         *
         * A meal carries the day it counts towards and, separately, the moment somebody typed it
         * in. Those are the same thing only when the entry is for today. Health Connect files by
         * instant, so the day has to win.
         */
        fun instantFor(
            date: java.time.LocalDate,
            enteredAtUtcMillis: Long,
            zone: ZoneId = ZoneId.systemDefault(),
        ): java.time.Instant {
            val entered = java.time.Instant.ofEpochMilli(enteredAtUtcMillis).atZone(zone)
            return if (entered.toLocalDate() == date) {
                entered.toInstant()
            } else {
                date.atTime(entered.toLocalTime()).atZone(zone).toInstant()
            }
        }
    }
}

/**
 * How many pages one query will walk before giving up.
 *
 * A thousand records a page, so this reaches a hundred thousand readings. It exists because a
 * provider that keeps handing back a token would otherwise loop for ever.
 */
internal const val MAX_RECORD_PAGES = 100

/**
 * Reads every page of a Health Connect query.
 *
 * One `readRecords` call answers with a single page and a token for the next. Reading once and
 * stopping quietly truncated a long history: somebody arriving with five years of weigh-ins in
 * their scale's app got the first page and no sign the rest existed.
 *
 * Two things end the walk besides running out of records. An empty string is a real answer from
 * Health Connect and means the same as no token, and a provider that returns the token it was
 * just given is making no progress, so re-reading that page would only duplicate it.
 */
internal suspend fun <T> readAllPages(
    pageLimit: Int = MAX_RECORD_PAGES,
    read: suspend (pageToken: String?) -> Pair<List<T>, String?>,
): List<T> {
    val all = mutableListOf<T>()
    var token: String? = null
    var pages = 0
    while (pages < pageLimit) {
        val (records, next) = read(token)
        all += records
        pages++
        val following = next?.takeIf { it.isNotEmpty() }
        if (following == null || following == token) break
        token = following
    }
    return all
}
