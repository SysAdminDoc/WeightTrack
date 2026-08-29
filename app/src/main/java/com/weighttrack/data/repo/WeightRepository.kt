package com.weighttrack.data.repo

import com.weighttrack.core.math.DailyWeight
import com.weighttrack.core.model.EntrySource
import com.weighttrack.core.model.EntryTag
import com.weighttrack.core.model.WeightEntry
import com.weighttrack.data.db.WeightEntryDao
import com.weighttrack.data.db.toDomain
import com.weighttrack.data.db.toEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt


/**
 * Everything here is scoped to whichever profile is active.
 *
 * The profile is asked for rather than passed in, so no screen has to thread an identifier
 * through and none of them can forget to. Switching profile re-runs every query on its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class WeightRepository @Inject constructor(
    private val dao: WeightEntryDao,
    private val profiles: ProfileRepository,
) {
    private fun <T> scoped(query: (Long) -> Flow<T>): Flow<T> =
        profiles.activeProfileId.flatMapLatest(query)

    fun observeEntries(): Flow<List<WeightEntry>> =
        scoped { dao.observeAll(it) }.map { rows -> rows.map { it.toDomain() } }

    fun search(query: String): Flow<List<WeightEntry>> =
        scoped { dao.search(it, query.trim()) }.map { rows -> rows.map { it.toDomain() } }

    fun observeLatest(): Flow<WeightEntry?> =
        scoped { dao.observeLatest(it) }.map { it?.toDomain() }

    fun observeCount(): Flow<Int> = scoped { dao.observeCount(it) }

    /** Daily means, aggregated in SQL, ready to feed straight into the trend engine. */
    fun observeDailyWeights(): Flow<List<DailyWeight>> =
        scoped { dao.observeDailyAverages(it) }.map { rows ->
            rows.mapNotNull { row ->
                val date = runCatching { LocalDate.parse(row.localDate) }.getOrNull()
                date?.let { DailyWeight(it, row.grams.roundToInt()) }
            }
        }

    suspend fun latest(): WeightEntry? = dao.latest(profiles.activeId())?.toDomain()

    suspend fun earliest(): WeightEntry? = dao.earliest(profiles.activeId())?.toDomain()

    /** A named profile's last reading, for the reminder, which fires for somebody in particular. */
    suspend fun latestFor(profileId: Long): WeightEntry? = dao.latest(profileId)?.toDomain()

    /**
     * Every reading of a named profile.
     *
     * Health Connect belongs to one person, who is not necessarily the one on screen, so its
     * sync says which profile it means instead of taking whoever happens to be active.
     */
    suspend fun entriesFor(profileId: Long): List<WeightEntry> =
        dao.observeAll(profileId).first().map { it.toDomain() }

    suspend fun addFor(
        profileId: Long,
        grams: Int,
        timestamp: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
        bodyFatPercent: Double? = null,
        source: EntrySource = EntrySource.MANUAL,
        healthConnectId: String? = null,
        clientRecordId: String = UUID.randomUUID().toString(),
    ): Long {
        val offset = zone.rules.getOffset(timestamp)
        return dao.upsertByIdentity(
            WeightEntry(
                timestamp = timestamp,
                zoneOffset = offset,
                localDate = timestamp.atZone(zone).toLocalDate(),
                grams = grams,
                bodyFatPercent = bodyFatPercent,
                source = source,
                clientRecordId = clientRecordId,
                healthConnectId = healthConnectId,
            ).toEntity(profileId = profileId),
        )
    }

    /**
     * The most recent reading of every profile.
     *
     * This is the one query that deliberately looks past the active profile: a weight that
     * arrived off a shared scale has to be offered to whoever it fits.
     */
    suspend fun latestPerProfile(): Map<Long, WeightEntry> =
        dao.latestPerProfile().associate { it.profileId to it.toDomain() }

    /**
     * The last reading recorded at or before an instant.
     *
     * Null when nothing had been recorded yet, which is honest: a photo from before you started
     * weighing yourself has no weight to show, and the first reading you ever took is not it.
     */
    suspend fun latestAtOrBefore(at: Instant): WeightEntry? =
        dao.latestAtOrBefore(profiles.activeId(), at.toEpochMilli())?.toDomain()

    suspend fun byId(id: Long): WeightEntry? = dao.byId(id)?.toDomain()

    suspend fun byClientRecordId(clientRecordId: String): WeightEntry? =
        dao.byClientRecordId(clientRecordId)?.toDomain()

    suspend fun latestBodyFatPercent(): Double? =
        dao.latestWithBodyFat(profiles.activeId())?.bodyFatPercent

    suspend fun count(): Int = dao.count(profiles.activeId())

    /**
     * Records a reading.
     *
     * The local date is taken from the offset in force where the person is standing, not from
     * UTC, so a late-night weigh-in stays on the day it happened rather than jumping forward.
     */
    suspend fun add(
        grams: Int,
        timestamp: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        bodyFatPercent: Double? = null,
        note: String? = null,
        tags: Set<EntryTag> = emptySet(),
        source: EntrySource = EntrySource.MANUAL,
        healthConnectId: String? = null,
        clientRecordId: String = UUID.randomUUID().toString(),
    ): Long {
        val offset = zone.rules.getOffset(timestamp)
        val entry = WeightEntry(
            timestamp = timestamp,
            zoneOffset = offset,
            localDate = timestamp.atZone(zone).toLocalDate(),
            grams = grams,
            bodyFatPercent = bodyFatPercent,
            note = note,
            tags = tags,
            source = source,
            clientRecordId = clientRecordId,
            healthConnectId = healthConnectId,
        )
        return dao.upsertByIdentity(entry.toEntity(profileId = profiles.activeId()))
    }

    suspend fun update(entry: WeightEntry) {
        // Read back off the stored row rather than assumed. Editing a reading while a different
        // profile is active must not quietly move it to that profile.
        dao.update(entry.toEntity(profileId = profileOf(entry.id)))
    }

    /** Moves an existing reading to a new instant, keeping its local date consistent. */
    suspend fun reschedule(entry: WeightEntry, timestamp: Instant, zone: ZoneId = ZoneId.systemDefault()) {
        val offset: ZoneOffset = zone.rules.getOffset(timestamp)
        update(
            entry.copy(
                timestamp = timestamp,
                zoneOffset = offset,
                localDate = timestamp.atZone(zone).toLocalDate(),
            ),
        )
    }

    suspend fun delete(entry: WeightEntry) {
        dao.delete(entry.toEntity(profileId = profileOf(entry.id)))
    }

    private suspend fun profileOf(id: Long): Long =
        dao.byId(id)?.profileId ?: profiles.activeId()

    suspend fun deleteByIds(ids: List<Long>) {
        if (ids.isNotEmpty()) dao.deleteByIds(ids)
    }

    suspend fun deleteAll() = dao.deleteAll()

    /** Bulk path for import and sync. Existing rows are matched by identity, never duplicated. */
    suspend fun upsertAll(entries: List<WeightEntry>) {
        val profileId = profiles.activeId()
        entries.forEach { dao.upsertByIdentity(it.toEntity(profileId = profileId)) }
    }

    suspend fun entriesBetween(from: Instant, to: Instant): List<WeightEntry> =
        dao.between(profiles.activeId(), from.toEpochMilli(), to.toEpochMilli()).map { it.toDomain() }
}
