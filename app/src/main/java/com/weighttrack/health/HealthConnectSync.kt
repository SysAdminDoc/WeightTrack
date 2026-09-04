package com.weighttrack.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Volume
import com.weighttrack.core.model.EntrySource
import com.weighttrack.core.model.HealthDirection
import com.weighttrack.core.model.WeightPlausibility
import com.weighttrack.core.model.RecordOrigin
import com.weighttrack.core.model.WeightEntry
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.ProfileRepository
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.diagnostics.LogArea
import com.weighttrack.diagnostics.LogEvent
import com.weighttrack.diagnostics.RuntimeLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the Health Connect client comes from.
 *
 * An interface only so that the import, which is the part with real logic in it and the part
 * nobody can reproduce on demand, can be driven against a fake with several pages of records in
 * it. The app binds the real one.
 */
fun interface HealthConnectClientSource {
    fun client(): HealthConnectClient?
}

/**
 * What came back from Health Connect, including why nothing did.
 *
 * Every read here used to answer with an empty list whatever had happened, so a withdrawn
 * permission, a provider that fell over and a person who genuinely has no step counts were all
 * the same answer: "no data". That is the one answer nobody can argue with, and it was wrong
 * two times out of three.
 */
sealed interface HealthOutcome<out T> {
    data class Ok<T>(val value: T) : HealthOutcome<T>

    /** No Health Connect on this phone at all. */
    data object NotAvailable : HealthOutcome<Nothing>

    /** Installed, but this app may not read that. */
    data object NotAllowed : HealthOutcome<Nothing>

    /** It was asked and it went wrong. */
    data class Failed(val cause: Throwable) : HealthOutcome<Nothing>

    fun valueOrNull(): T? = (this as? Ok)?.value
}

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
    /** Readings deleted elsewhere and now deleted here too. */
    val removed: Int = 0,
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
    private val deletions: com.weighttrack.data.repo.DeletionRecorder,
    private val runtimeLog: RuntimeLog,
    private val clientSource: HealthConnectClientSource,
) {

    /**
     * Whose readings Health Connect exchanges.
     *
     * Decided once and written down. It used to follow the active profile whenever nobody had
     * claimed it, which is every single-profile install: harmless right up until the day a
     * second profile is added, and from then on switching person quietly redirected Health
     * Connect at them.
     */
    private suspend fun syncProfileId(): Long? = profileRepository.claimHealthConnect()

    /**
     * Writes down whose Health Connect this is, before anything is read from it or written to it.
     *
     * Called the moment access is granted, so the claim is on the profile the person was looking
     * at when they connected rather than on whoever happens to be active when the first
     * background sync runs an hour later.
     */
    suspend fun claimProfile(): Long? = profileRepository.claimHealthConnect()

    /**
     * Runs [block] with no sync in flight, and holds any sync off until it is done.
     *
     * Handing Health Connect to another person, or taking it away, part-way through a sync would
     * file the rest of that sync's import against the new owner and leave the export half done
     * under the old one.
     */
    suspend fun <T> whileNotSyncing(block: suspend () -> T): T = running.withLock { block() }

    /** One Health Connect exchange at a time, whoever asked for it. */
    private val running = Mutex()

    /**
     * Everything one run has decided, decided once.
     *
     * These used to be worked out again wherever they were wanted. Whose profile it is came off
     * a flow and was re-read for every single record, so a person switching profile while the
     * provider was slow to answer had the rest of that import filed against somebody else, and
     * the export that followed it read a third person's readings. The window moved as the run
     * went on, so a long import asked for a slightly different span each time it started again.
     * None of that is visible afterwards: the rows simply belong to the wrong person.
     */
    private data class Session(
        val client: HealthConnectClient,
        val profileId: Long,
        val granted: Set<String>,
        val zone: ZoneId,
        val start: Instant,
        val now: Instant,
        val lowestOfDayOnly: Boolean,
        val direction: HealthDirection = HealthDirection.TWO_WAY,
        val excludedOrigins: Set<String> = emptySet(),
    )

    /**
     * Notes that something went wrong, since almost everything here answers with a default.
     *
     * A revoked grant and a broken provider both used to look exactly like "you have no data",
     * which is the one answer a person cannot argue with.
     */
    private fun failed(event: LogEvent, cause: Throwable) {
        runtimeLog.write(LogArea.HEALTH_CONNECT, event, cause = cause)
    }

    /**
     * Whether this phone's Health Connect can read further back than thirty days at all.
     *
     * The permission only exists on a provider new enough to offer it. Somewhere older will never
     * report it as granted however many times it is asked for, so anything that treats a missing
     * grant as "not finished yet" has to check this first or it nags for ever about something the
     * person cannot give.
     */
    fun supportsHistory(): Boolean = runCatching {
        clientOrNull()?.features?.getFeatureStatus(
            HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY,
        ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)

    /**
     * Whether the older readings are actually reachable.
     *
     * False on a phone that cannot offer the permission as well as on one where it was refused,
     * because the consequence is the same either way: thirty days and no more.
     */
    suspend fun hasHistoryPermission(): Boolean = hasGranted(historyPermissions)

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
        }.onFailure { failed(LogEvent.HEALTH_WRITE_FAILED, it) }.getOrDefault(false)
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

    private fun clientOrNull(): HealthConnectClient? = clientSource.client()

    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun hasPermissions(): Boolean = hasGranted(corePermissionsFor(direction()))

    /** Which way readings are allowed to move, as it stands. */
    suspend fun direction(): HealthDirection = settingsRepository.settings.first().healthDirection

    /**
     * Whether everything the app can use has been allowed.
     *
     * Separate from [hasPermissions] on purpose. Weight is what Health Connect is for here and
     * the app works with only that, but somebody who connected before food and water existed has
     * granted the core set and nothing else, and asking only about the core set would mean the
     * screen never offered them the rest.
     */
    suspend fun hasEverything(): Boolean = hasGranted(grantablePermissions())

    /**
     * Everything worth asking this particular phone for.
     *
     * A provider too old for the history grant would otherwise leave "Allow the rest" on the
     * screen for ever, for somebody who has already allowed everything they can.
     */
    suspend fun grantablePermissions(): Set<String> = grantablePermissions(direction())

    fun grantablePermissions(direction: HealthDirection): Set<String> {
        var asking = permissionsFor(direction)
        if (!supportsHistory()) asking = asking - historyPermissions
        if (!supportsBackground()) asking = asking - backgroundPermissions
        return asking
    }

    /**
     * Whether this phone's Health Connect knows what a background read is.
     *
     * Its own feature, separate from the history one. A provider that does not have it will
     * never report the permission as granted however many times it is asked, so asking anyway
     * leaves "Allow the rest" on screen for ever for somebody who has already allowed
     * everything they can. That trap has been walked into here once before.
     */
    fun supportsBackground(): Boolean = runCatching {
        clientOrNull()?.features?.getFeatureStatus(
            HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
        ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)

    /**
     * Whether the hourly exchange is allowed to read anything.
     *
     * True on a provider too old to have the permission at all: those read in the background
     * without asking, which is how this worked before the grant existed. Requiring a grant
     * nobody can give would have cancelled the hourly job on those phones for good.
     */
    suspend fun backgroundReadIsPossible(): Boolean =
        !supportsBackground() || canSyncInBackground()

    /**
     * Whether anybody holds Health Connect.
     *
     * True while nobody has answered the question, because the first run answers it. False once
     * somebody has switched it off, which is what stops the hourly job running for ever against
     * a connection that has been given up.
     */
    suspend fun isTiedToAProfile(): Boolean =
        profileRepository.healthConnectId() != null ||
            !settingsRepository.healthConnectDecided()

    /**
     * Whether the hourly exchange can actually read anything.
     *
     * Scheduling it without this grant produces an hourly run that reads nothing and reports
     * success, which is the worst of both: no data and no complaint.
     */
    suspend fun canSyncInBackground(): Boolean = hasGranted(backgroundPermissions)

    suspend fun hasActivityPermissions(): Boolean = hasGranted(activityPermissions)

    suspend fun hasSleepPermission(): Boolean = hasGranted(sleepPermissions)

    /**
     * Hours asleep per morning, over the last [days].
     *
     * Read as sessions rather than aggregated by the platform, because a session that starts at
     * eleven at night belongs to the next morning's weigh-in and an aggregate sliced by calendar
     * day would file most of it under the evening before.
     */
    suspend fun readSleepHours(days: Long = 120): HealthOutcome<Map<LocalDate, Double>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = clientOrNull() ?: return@withContext HealthOutcome.NotAvailable
                if (!hasSleepPermission()) return@withContext HealthOutcome.NotAllowed
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
            }.onFailure { failed(LogEvent.HEALTH_READ_FAILED, it) }
                .fold({ HealthOutcome.Ok(it) }, { HealthOutcome.Failed(it) })
        }

    suspend fun hasMenstruationPermission(): Boolean = hasGranted(menstruationPermissions)

    /**
     * Every day a period covered, over the last [days].
     *
     * Expanded into whole days rather than kept as intervals, because everything that reads this
     * works a day at a time: one morning's weight, one row on the chart, one point in the fit.
     *
     * A record ending exactly at midnight belongs to the day before it, which is how Health
     * Connect writes a period that finished on the fourth: an interval closing at the fifth at
     * 00:00. Taken literally that marks a day nobody said anything about.
     */
    suspend fun readMenstruationDays(days: Long = 120): HealthOutcome<Set<LocalDate>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = clientOrNull() ?: return@withContext HealthOutcome.NotAvailable
                if (!hasMenstruationPermission()) return@withContext HealthOutcome.NotAllowed
                val zone = ZoneId.systemDefault()
                val end = LocalDate.now().plusDays(1).atStartOfDay()
                val start = end.minusDays(days)
                val records = readAllPages { token ->
                    val page = client.readRecords(
                        ReadRecordsRequest(
                            recordType = MenstruationPeriodRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(start, end),
                            pageToken = token,
                        ),
                    )
                    page.records to page.pageToken
                }
                val flagged = mutableSetOf<LocalDate>()
                for (record in records) {
                    val first = record.startTime.atZone(zone).toLocalDate()
                    val closed = record.endTime.minusNanos(1).coerceAtLeast(record.startTime)
                    // Capped, because this walks a day at a time and the record came from some
                    // other app. One with an end time in the next century would otherwise spin
                    // here for ever on a background thread nobody is watching.
                    val last = minOf(
                        closed.atZone(zone).toLocalDate(),
                        first.plusDays(MAX_PERIOD_DAYS - 1),
                    )
                    var day = first
                    while (!day.isAfter(last)) {
                        flagged += day
                        day = day.plusDays(1)
                    }
                }
                flagged.toSet()
            }.onFailure { failed(LogEvent.HEALTH_READ_FAILED, it) }
                .fold({ HealthOutcome.Ok(it) }, { HealthOutcome.Failed(it) })
        }

    suspend fun hasHydrationPermission(): Boolean = hasGranted(hydrationPermissions)

    private suspend fun hasGranted(required: Set<String>): Boolean =
        grantedPermissions().containsAll(required)

    /**
     * What this app is allowed to do right now.
     *
     * Read once at the start of a run and carried through it. A grant can be withdrawn from the
     * system settings while a sync is in flight, and asking again halfway would let one run
     * import under one answer and export under another.
     */
    private suspend fun grantedPermissions(): Set<String> {
        val client = clientOrNull() ?: return emptySet()
        return runCatching { client.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
    }

    /**
     * Daily steps and active calories, so movement can be read against the weight trend.
     *
     * Days with no record are left out rather than reported as zero: "you did not wear the
     * watch" and "you did not move" are different things, and showing the second when the
     * first happened makes the whole card a lie.
     */
    suspend fun readDailyActivity(days: Long = 30): HealthOutcome<List<DailyActivity>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = clientOrNull() ?: return@withContext HealthOutcome.NotAvailable
                if (!hasActivityPermissions()) return@withContext HealthOutcome.NotAllowed
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
            }.onFailure { failed(LogEvent.HEALTH_READ_FAILED, it) }
                .fold({ HealthOutcome.Ok(it) }, { HealthOutcome.Failed(it) })
        }

    /**
     * Pulls new readings in, then pushes ours out.
     *
     * The read window reaches back years rather than days because the whole point for a new
     * user is to inherit the history their scale app already holds. Reading beyond thirty days
     * needs the history permission; without it Health Connect simply returns less, which is
     * why this does not fail when the grant is missing.
     */
    suspend fun sync(
        sinceDays: Long = 365 * 5,
        now: Instant = Instant.now(),
    ): Result<HealthConnectSyncResult> =
        withContext(Dispatchers.IO) {
            running.withLock {
            runCatching {
                val client = clientOrNull() ?: error(context.getString(com.weighttrack.R.string.health_not_available))
                val at = now
                val stored = settingsRepository.settings.first()
                val way = stored.healthDirection
                val granted = grantedPermissions()
                if (!granted.containsAll(corePermissionsFor(way))) {
                    error(context.getString(com.weighttrack.R.string.health_not_granted))
                }
                // Nobody holds Health Connect and somebody said so, so there is nothing to sync
                // and nothing to guess at.
                val owner = syncProfileId()
                    ?: error(context.getString(com.weighttrack.R.string.health_no_profile))
                val session = Session(
                    client = client,
                    profileId = owner,
                    granted = granted,
                    zone = ZoneId.systemDefault(),
                    start = at.minus(sinceDays, ChronoUnit.DAYS),
                    now = at,
                    lowestOfDayOnly = stored.importLowestOfDay,
                    direction = way,
                    excludedOrigins = stored.excludedHealthOrigins,
                )
                val token = settingsRepository.healthChangesToken(session.profileId)
                // With a token in hand, only what has actually changed since last time. Without
                // one, the whole window, which is what a first connect needs.
                val imported = when {
                    // Publishing only. Nothing is read, so nothing moves the mark either: the
                    // day somebody turns reading back on, it picks up where reading left off
                    // rather than after everything that arrived while it was off.
                    !way.reads -> Triple(0, 0, 0)
                    token == null -> {
                        // A full read reaches everything in the window by definition.
                        importWeights(session).also {
                            rememberToken(session)
                            readToTheEnd = true
                        }
                    }
                    else -> importChanges(session, token)
                }
                // How far this run read, for the next one that loses its place. Only a run that
                // got to the end of the queue may move it: a provider having a bad week would
                // otherwise walk the mark a week forward over records nobody had seen, and when
                // the token finally expired the recovery would start after them.
                if (readToTheEnd) {
                    settingsRepository.setHealthImportedThrough(
                        session.profileId,
                        session.now.toEpochMilli(),
                    )
                }
                val exported = if (way.writes) exportWeights(session) else 0
                HealthConnectSyncResult(
                    imported = imported.first,
                    exported = exported,
                    skipped = imported.second,
                    removed = imported.third,
                )
            }.onFailure { failed(LogEvent.HEALTH_SYNC_FAILED, it) }
            }
        }

    /** Notes where Health Connect has got to, so the next sync can ask only for what changed. */
    private suspend fun rememberToken(session: Session) {
        val client = session.client
        val profileId = session.profileId
        runCatching {
            client.getChangesToken(ChangesTokenRequest(recordTypes = setOf(WeightRecord::class)))
        }.onSuccess { settingsRepository.setHealthChangesToken(profileId, it) }
            .onFailure { failed(LogEvent.HEALTH_READ_FAILED, it) }
    }

    /**
     * Only what has changed since last time, deletions included.
     *
     * The reason this exists is the deletions. A reading removed in the scale's own app used to
     * stay here for ever, because an import that only ever upserts has no way to hear about
     * something that is no longer there. It also stops every sync rereading five years against
     * a rate limit that is not generous.
     *
     * A token Health Connect no longer recognises is not an error: it means too much has happened
     * since, so the window is read in full and a fresh token taken.
     */
    /**
     * Whether the last walk of the changes got to the end of the queue.
     *
     * The mark of how far the import has read may only move when this is true. It used to move
     * on every run, including the ones that read nothing because the provider was unwell, so a
     * bad week walked the mark a week forward over records nobody had seen. When the token
     * finally expired, the recovery started after them and they were gone for good.
     */
    private var readToTheEnd = false

    private suspend fun importChanges(session: Session, token: String): Triple<Int, Int, Int> {
        readToTheEnd = false
        val client = session.client
        val profileId = session.profileId
        var next: String? = token
        var imported = 0
        var skipped = 0
        var removed = 0
        var pages = 0
        while (next != null && pages < MAX_RECORD_PAGES) {
            // A token Health Connect has forgotten comes back either as the flag below or as a
            // refusal to answer at all, and both mean the same thing. Everything else does not:
            // treating a provider having a bad minute, a rate limit, or a grant withdrawn months
            // ago as a lost cursor threw the cursor away and read five years of records again,
            // hourly, until whatever it was went away.
            val response = runCatching { client.getChanges(next) }.getOrElse { failure ->
                failed(LogEvent.HEALTH_READ_FAILED, failure)
                return when (HealthFailure.of(failure)) {
                    HealthFailure.EXPIRED_TOKEN -> startAgain(session, removed)
                    // The cursor is still good. Keeping it is what makes the next run cheap,
                    // and it is still where this one got to.
                    HealthFailure.NOT_ALLOWED,
                    HealthFailure.RATE_LIMITED,
                    HealthFailure.TRANSIENT,
                    -> Triple(imported, skipped, removed)
                }
            }
            pages++
            if (response.changesTokenExpired) return startAgain(session, removed)
            val gone = mutableListOf<String>()
            for (change in response.changes) {
                when (change) {
                    is UpsertionChange -> {
                        val record = change.record as? WeightRecord ?: continue
                        if (take(session, record)) imported++ else skipped++
                    }
                    is DeletionChange -> gone += change.recordId
                    else -> Unit
                }
            }
            removed += weightRepository.deleteByHealthConnectIds(profileId, gone)
            // An empty token is a real answer and means the same as none. Storing it would make
            // the next sync ask for changes since nowhere, which reads the whole window again.
            val following = response.nextChangesToken.takeIf { it.isNotEmpty() }
            settingsRepository.setHealthChangesToken(profileId, following)
            // A provider handing back the token it was just given is making no progress. Walking
            // it again re-reads the same page, and every row on it counts as imported a second
            // time, so the number on the screen climbs while nothing happens.
            next = if (response.hasMore && following != null && following != next) following else null
            if (next == null) readToTheEnd = true
        }
        return Triple(imported, skipped, removed)
    }

    /**
     * Forgets where it had got to and reads the window in full.
     *
     * What to do when the place in the queue is no longer any good: too much has happened since,
     * or the token means nothing to this provider any more.
     */
    private suspend fun startAgain(session: Session, removed: Int): Triple<Int, Int, Int> {
        settingsRepository.setHealthChangesToken(session.profileId, null)
        // From a little before wherever the last successful import got to, not from five years
        // ago. The overlap covers a record written just before that moment and not yet visible;
        // the upsert means seeing one twice costs nothing. Only a first connect, which has read
        // nothing yet, asks for the whole window.
        val readThrough = settingsRepository.healthImportedThrough(session.profileId)
        val oldest = session.now.minus(FULL_WINDOW_DAYS, ChronoUnit.DAYS)
        // The mark is a moment in wall time; a changes token hands over records whatever date
        // they carry. Something written yesterday about a weigh-in three years ago is inside the
        // cursor's reach and would fall outside a window that started at the mark, so the
        // recovery reaches back to the oldest thing this phone already holds. Records older than
        // everything here can only be ones this phone has never seen, and the full window is
        // what a first connect asks for anyway.
        val earliestHere = weightRepository.earliestFor(session.profileId)
        val from = when {
            readThrough <= 0 -> oldest
            earliestHere != null -> maxOf(earliestHere.minus(OVERLAP_DAYS, ChronoUnit.DAYS), oldest)
            else -> maxOf(
                Instant.ofEpochMilli(readThrough).minus(OVERLAP_DAYS, ChronoUnit.DAYS),
                oldest,
            )
        }
        val full = importWeights(session.copy(start = from))
        rememberToken(session)
        return Triple(full.first, full.second, removed)
    }

    private suspend fun importWeights(session: Session): Triple<Int, Int, Int> {
        val records = readAllPages { token ->
            val page = session.client.readRecords(
                ReadRecordsRequest(
                    recordType = WeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(session.start, session.now),
                    pageToken = token,
                ),
            )
            page.records to page.pageToken
        }
        var imported = 0
        var skipped = 0
        // Everything the app would refuse anyway goes before the day's lowest is chosen. A 5 g
        // record, or one from an app somebody has switched off, otherwise wins the comparison,
        // is refused afterwards, and takes the valid reading beside it down with it: the day
        // ends up importing nothing at all.
        val plausible = records.filter { record ->
            val accepted = accepts(session, record)
            if (!accepted) skipped++
            accepted
        }
        val wanted = if (session.lowestOfDayOnly) {
            val kept = lowestPerDay(plausible, session.zone)
            skipped += plausible.size - kept.size
            kept
        } else {
            plausible
        }
        wanted.forEach { record ->
            if (take(session, record, checkAcceptable = false)) imported++ else skipped++
        }
        return Triple(imported, skipped, 0)
    }

    /**
     * One reading a day: the lowest.
     *
     * A second weigh-in after breakfast is not a second day's worth of information, it is the
     * same morning plus a meal, and importing both drags the trend around for no reason. Which
     * one to keep is a real choice and the lowest is the conventional answer, so this is an
     * option rather than the behaviour.
     */
    internal fun lowestPerDay(records: List<WeightRecord>, zone: ZoneId): List<WeightRecord> =
        records
            .groupBy { it.time.atZone(zone).toLocalDate() }
            .values
            .mapNotNull { sameDay -> sameDay.minByOrNull { it.weight.inKilograms } }

    /**
     * Whether this reading is one the app would keep at all.
     *
     * Both reasons to refuse live here together on purpose, because both have to be settled
     * before the day's lowest is picked. A reading from an app somebody has switched off is as
     * unwanted as a corrupt one, and letting it into the comparison means it wins the day and is
     * then thrown away, leaving the day with nothing.
     */
    private fun accepts(session: Session, record: WeightRecord): Boolean {
        val grams = (record.weight.inKilograms * 1000).toInt()
        val plausibility = WeightPlausibility.problem(grams, record.time, session.now)
        if (plausibility != null) {
            runtimeLog.write(
                LogArea.HEALTH_CONNECT,
                LogEvent.HEALTH_RECORD_REFUSED,
                code = plausibility.ordinal,
            )
            return false
        }
        // A phone that also syncs a watch and a fitness tracker gets the same morning three
        // times from three writers. Saying so once is the only way to stop it.
        val origin = originOf(record)
        if (origin != null && origin.packageName in session.excludedOrigins) return false
        return true
    }

    /**
     * Files one reading from Health Connect, or decides not to.
     *
     * Shared by the first full read and by the incremental one afterwards, so a record arriving
     * through a change notification is treated exactly like one arriving through a query.
     */
    private suspend fun take(
        session: Session,
        record: WeightRecord,
        checkAcceptable: Boolean = true,
    ): Boolean {
        if (checkAcceptable && !accepts(session, record)) return false
        val grams = (record.weight.inKilograms * 1000).toInt()
        val origin = originOf(record)
        // A record we wrote comes back carrying our own client id. Re-importing it would be
        // harmless thanks to the upsert, but skipping keeps the counts honest.
        val ourClientId = record.metadata.clientRecordId
        if (
            ourClientId != null &&
            weightRepository.byClientRecordIdFor(session.profileId, ourClientId) != null
        ) {
            return false
        }
        weightRepository.addFor(
            profileId = session.profileId,
            grams = grams,
            timestamp = record.time,
            zone = session.zone,
            source = EntrySource.HEALTH_CONNECT,
            healthConnectId = record.metadata.id,
            clientRecordId = ourClientId ?: IMPORTED_PREFIX + record.metadata.id,
            origin = origin,
        )
        return true
    }

    /**
     * Which app wrote a record, and on what.
     *
     * The device is whatever the writer said, which is often nothing at all: plenty of apps fill
     * in a manufacturer and a model and plenty leave both blank, so this is a line to show when
     * there is one rather than a field to rely on.
     */
    private fun originOf(record: WeightRecord): RecordOrigin? {
        val packageName = record.metadata.dataOrigin.packageName
        val device = record.metadata.device
        val described = listOfNotNull(device?.manufacturer, device?.model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return RecordOrigin.of(packageName, described)
    }

    /**
     * Sends what has changed here, and takes back what has been deleted.
     *
     * It used to send everything, every run. An hour apart, for ever, a person with five years
     * of weigh-ins had fifteen hundred records rewritten into their health record to say nothing
     * new, and a reading they deleted here stayed there for good because the export only ever
     * added. Both are invisible from inside the app and both are somebody else's data being
     * churned.
     *
     * What has been sent is remembered against the row rather than against a clock. A wall-time
     * mark looks equivalent and is not: a history arriving from another phone carries that
     * phone's timestamps, all of them older than this one's mark, and none of it would ever be
     * exported.
     */
    private suspend fun exportWeights(session: Session): Int {
        val client = session.client
        // A grant that arrived after the first export has to reach what was already sent. Those
        // readings went across without their body-fat figure and were marked done, so allowing
        // it afterwards would otherwise reach nothing already recorded.
        val withBodyFat = session.granted.containsAll(bodyFatPermissions)
        // Follows the grant rather than latching once. Revoking body fat and allowing it again
        // is the same situation this exists for, and a flag that only ever goes true left those
        // readings in the health record with a weight and no figure, permanently.
        if (withBodyFat != settingsRepository.healthBodyFatExported(session.profileId)) {
            if (withBodyFat) weightRepository.resendBodyFatToHealth(session.profileId)
            settingsRepository.setHealthBodyFatExported(session.profileId, withBodyFat)
        }
        val waiting = weightRepository.awaitingHealthExport(session.profileId)
            .filter { (_, entry) -> entry.source != EntrySource.HEALTH_CONNECT }
        val pending = pendingDeletions(session)
        if (waiting.isEmpty() && pending.isEmpty()) return 0

        var written = 0
        val sent = mutableListOf<Long>()
        waiting.chunked(BATCH_SIZE).forEach { batch ->
            val records = buildList<androidx.health.connect.client.records.Record> {
                batch.forEach { (_, entry) ->
                    add(entry.toWeightRecord(session.zone))
                    // Body fat rides along with the reading it belongs to rather than being
                    // pushed separately, so one mark covers both and neither can be sent
                    // without the other.
                    entry.toBodyFatRecord(session)?.let(::add)
                }
            }
            runCatching { client.insertRecords(records) }
                .onSuccess {
                    written += batch.size
                    sent += batch.map { (id, _) -> id }
                }
                .onFailure { failed(LogEvent.HEALTH_WRITE_FAILED, it) }
        }
        // Marked only for what actually landed. A row left unmarked is sent again next time, and
        // an insert is an upsert on the client record id, so sending it twice costs nothing.
        weightRepository.markHealthExported(sent)

        // Deleted one at a time, and by the name this app gave the record. A reading imported
        // from somebody else's scale app was never written by this one and is left alone: its
        // name is not a name Health Connect knows this app by.
        val done = mutableSetOf<String>()
        pending.forEach { name ->
            runCatching {
                client.deleteRecords(
                    recordType = WeightRecord::class,
                    recordIdsList = emptyList(),
                    clientRecordIdsList = listOf(name),
                )
                // And the body-fat record that went with it, which carries the same name with a
                // marker in front. Left behind, a figure the person deleted here stays in their
                // health record for ever with the weight it belonged to gone.
                client.deleteRecords(
                    recordType = BodyFatRecord::class,
                    recordIdsList = emptyList(),
                    clientRecordIdsList = listOf(BODY_FAT_PREFIX + name),
                )
            }.onSuccess { done += name }
                .onFailure { failed(LogEvent.HEALTH_WRITE_FAILED, it) }
        }
        rememberDeletionsSent(session, done)
        return written
    }

    /**
     * Deletions this person has made that Health Connect has not been told about.
     *
     * Anything named the way an imported record is named is left out. Those are Health Connect's
     * own records, this app never wrote them, and asking it to delete one by a name it does not
     * know is at best noise and at worst an error that stalls everything else in the batch.
     */
    private suspend fun pendingDeletions(session: Session): List<String> {
        val owner = profileRepository.syncIdOf(session.profileId) ?: return emptyList()
        val already = settingsRepository.healthDeletionsSent(session.profileId)
        return deletions.since(com.weighttrack.core.sync.SyncKind.WEIGHT, owner, 0)
            .filterNot { it.startsWith(IMPORTED_PREFIX) }
            .filterNot { it in already }
    }

    /**
     * Notes which deletions have landed, and forgets the ones nothing remembers any more.
     *
     * The note is pruned back to the tombstones that still exist, so it cannot grow without
     * limit as the tombstones themselves are forgotten after six months.
     */
    private suspend fun rememberDeletionsSent(session: Session, done: Set<String>) {
        if (done.isEmpty()) return
        val owner = profileRepository.syncIdOf(session.profileId) ?: return
        val alive = deletions.since(com.weighttrack.core.sync.SyncKind.WEIGHT, owner, 0).toSet()
        val kept = settingsRepository.healthDeletionsSent(session.profileId) + done
        settingsRepository.setHealthDeletionsSent(session.profileId, kept intersect alive)
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
        }.onFailure { failed(LogEvent.HEALTH_WRITE_FAILED, it) }.getOrDefault(false)
    }

    /**
     * One reading's body fat, as the record Health Connect models it with.
     *
     * Written beside the weigh-in it came from rather than in a pass of its own. The separate
     * pass had no mark of what it had already sent and no permission check, so every press of
     * the sync button rewrote every body-fat figure the person had ever recorded.
     */
    private fun WeightEntry.toBodyFatRecord(session: Session): BodyFatRecord? {
        val percent = bodyFatPercent ?: return null
        if (HealthPermission.getWritePermission(BodyFatRecord::class) !in session.granted) {
            return null
        }
        return BodyFatRecord(
            time = timestamp,
            zoneOffset = session.zone.rules.getOffset(timestamp),
            percentage = Percentage(percent),
            metadata = Metadata.manualEntry(
                device = Device(type = Device.TYPE_PHONE),
                clientRecordId = BODY_FAT_PREFIX + clientRecordId,
            ),
        )
    }

    companion object {

    /**
     * What an imported reading is called here.
     *
     * Health Connect gave the record; this app did not write it and cannot name it to Health
     * Connect, so nothing prefixed this way is ever sent back to it as a deletion.
     */
    const val IMPORTED_PREFIX = "hc:"

    /** What a body-fat record is called, so it can be found again when its weigh-in goes. */
    const val BODY_FAT_PREFIX = "bf:"

    /**
     * What weight sync itself needs. Kept separate from the full set so that adding a new
     * optional permission later cannot make an existing user's working sync report itself as
     * unauthorised until they re-grant everything.
     */
    val corePermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
        // Height is deliberately not here. Nothing reads or writes one any more, and demanding
        // it meant somebody who declined an access the app does not use had weight sync refused
        // outright, with the Connect button still on screen and no way past it.
    )

    /**
     * Body fat, which rides along with the weigh-in it belongs to.
     *
     * Its own set rather than part of the core one. Plenty of scales report a body-fat figure
     * and plenty of people would rather it stayed on this phone, and refusing it should cost
     * that figure rather than the whole of weight sync.
     */
    val bodyFatPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
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

    /**
     * Periods, read only, and its own grant.
     *
     * The most private thing the app asks for, and the one people are most likely to say no to,
     * so it is asked for alone and refusing it changes nothing else. All it is read for is which
     * mornings carry water that is not tissue.
     */
    val menstruationPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(MenstruationPeriodRecord::class),
    )

    /**
     * Reading further back than the last thirty days.
     *
     * Health Connect answers with a month unless this is granted, no matter what window the
     * query asks for. For somebody arriving with four years of weigh-ins in their scale's app,
     * a month is nearly all of their history missing, and nothing about the answer says so.
     *
     * Deliberately outside [corePermissions]: refusing it leaves weight sync working on the
     * last thirty days rather than reporting itself as unauthorised.
     */
    /**
     * Reading while the app is not open.
     *
     * The hourly exchange is exactly that, and without this Health Connect answers a background
     * read with nothing rather than with an error: a reading a scale wrote would sit unnoticed
     * until the app was next opened and nothing anywhere would say why. Its own grant, because
     * refusing it should cost the hourly run and not the feature.
     */
    val backgroundPermissions: Set<String> = setOf(
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
    )

    val historyPermissions: Set<String> = setOf(
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
    )

    val permissions: Set<String> =
        corePermissions + bodyFatPermissions + backgroundPermissions + historyPermissions +
            hydrationPermissions + nutritionPermissions + activityPermissions + sleepPermissions +
            menstruationPermissions

    /**
     * What a direction actually needs, and nothing else.
     *
     * Split on what each permission says about itself rather than on a list kept alongside the
     * feature sets. A list would go stale the first time somebody adds a permission to one of
     * those sets and forgets this, and the failure would be silent and in the wrong direction:
     * a write access asked for by somebody who chose read-only. `StringsAreClassifiedTest`
     * holds every permission to naming itself one or the other.
     */
    fun permissionsFor(direction: HealthDirection): Set<String> =
        permissions.filterTo(mutableSetOf()) { direction.allows(it) }

    /** What weight sync needs for a direction. Read-only never asks to write. */
    fun corePermissionsFor(direction: HealthDirection): Set<String> =
        corePermissions.filterTo(mutableSetOf()) { direction.allows(it) }

    /** Whether a direction has any use for one permission. */
    fun HealthDirection.allows(permission: String): Boolean =
        if (permission.contains(WRITES)) writes else reads

    /** What a write permission calls itself. Everything else is a read. */
    const val WRITES = "WRITE_"

        private const val BATCH_SIZE = 200

        /** How far back a full read reaches for somebody who has never read anything. */
        private const val FULL_WINDOW_DAYS = 365L * 5

        /**
         * How far behind the last successful read a recovery starts.
         *
         * Enough to cover a record written just before that moment and not yet visible to a
         * query, and small enough that losing the cursor is cheap. Reading a record twice costs
         * nothing: the insert is keyed on its own name.
         */
        private const val OVERLAP_DAYS = 2L

        /** Shorter than this is a nap, and a nap is not a night's sleep. */
        private const val MINIMUM_SLEEP_HOURS = 3.0

        /**
         * The longest a single period record is taken at its word.
         *
         * Ten days is already beyond anything a clinician would call ordinary. This is a bound on
         * a loop over data another app wrote, not a medical opinion.
         */
        private const val MAX_PERIOD_DAYS = 10L

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
