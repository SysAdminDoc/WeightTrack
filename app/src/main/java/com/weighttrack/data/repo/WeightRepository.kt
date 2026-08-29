package com.weighttrack.data.repo

import com.weighttrack.core.math.DailyWeight
import com.weighttrack.core.model.EntrySource
import com.weighttrack.core.model.EntryTag
import com.weighttrack.core.model.WeightEntry
import com.weighttrack.data.db.WeightEntryDao
import com.weighttrack.data.db.toDomain
import com.weighttrack.data.db.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class WeightRepository @Inject constructor(
    private val dao: WeightEntryDao,
) {
    fun observeEntries(): Flow<List<WeightEntry>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun search(query: String): Flow<List<WeightEntry>> =
        dao.search(query.trim()).map { rows -> rows.map { it.toDomain() } }

    fun observeLatest(): Flow<WeightEntry?> =
        dao.observeLatest().map { it?.toDomain() }

    fun observeCount(): Flow<Int> = dao.observeCount()

    /** Daily means, aggregated in SQL, ready to feed straight into the trend engine. */
    fun observeDailyWeights(): Flow<List<DailyWeight>> =
        dao.observeDailyAverages().map { rows ->
            rows.mapNotNull { row ->
                val date = runCatching { LocalDate.parse(row.localDate) }.getOrNull()
                date?.let { DailyWeight(it, row.grams.roundToInt()) }
            }
        }

    suspend fun latest(): WeightEntry? = dao.latest()?.toDomain()

    suspend fun earliest(): WeightEntry? = dao.earliest()?.toDomain()

    /**
     * The last reading recorded at or before an instant.
     *
     * Null when nothing had been recorded yet, which is honest: a photo from before you started
     * weighing yourself has no weight to show, and the first reading you ever took is not it.
     */
    suspend fun latestAtOrBefore(at: Instant): WeightEntry? =
        dao.latestAtOrBefore(at.toEpochMilli())?.toDomain()

    suspend fun byId(id: Long): WeightEntry? = dao.byId(id)?.toDomain()

    suspend fun byClientRecordId(clientRecordId: String): WeightEntry? =
        dao.byClientRecordId(clientRecordId)?.toDomain()

    suspend fun latestBodyFatPercent(): Double? = dao.latestWithBodyFat()?.bodyFatPercent

    suspend fun count(): Int = dao.count()

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
        return dao.upsertByIdentity(entry.toEntity())
    }

    suspend fun update(entry: WeightEntry) {
        dao.update(entry.toEntity())
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
        dao.delete(entry.toEntity())
    }

    suspend fun deleteByIds(ids: List<Long>) {
        if (ids.isNotEmpty()) dao.deleteByIds(ids)
    }

    suspend fun deleteAll() = dao.deleteAll()

    /** Bulk path for import and sync. Existing rows are matched by identity, never duplicated. */
    suspend fun upsertAll(entries: List<WeightEntry>) {
        entries.forEach { dao.upsertByIdentity(it.toEntity()) }
    }

    suspend fun entriesBetween(from: Instant, to: Instant): List<WeightEntry> =
        dao.between(from.toEpochMilli(), to.toEpochMilli()).map { it.toDomain() }
}
