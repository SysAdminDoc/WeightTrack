package com.weighttrack.data.repo

import com.weighttrack.core.math.DailyWeight
import com.weighttrack.core.model.EntrySource
import com.weighttrack.core.model.EntryTag
import com.weighttrack.core.model.WeightEntry
import com.weighttrack.data.db.WeightEntryDao
import com.weighttrack.data.db.WeightEntryEntity
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
    private val deletions: DeletionRecorder,
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

    /**
     * What Health Connect has not been told the current version of, for one person.
     *
     * The row identifier travels with the reading so the caller can mark exactly what it sent.
     */
    suspend fun awaitingHealthExport(profileId: Long): List<Pair<Long, WeightEntry>> =
        dao.awaitingHealthExport(profileId).map { it.id to it.toDomain() }

    /**
     * The oldest reading this phone holds for one person.
     *
     * What a Health Connect recovery reaches back to. A record written yesterday about a
     * weigh-in three years ago arrives through a changes token whatever its date, so a window
     * that started at the last read moment would miss it.
     */
    suspend fun earliestFor(profileId: Long): java.time.Instant? =
        dao.earliestTimestamp(profileId)?.let(java.time.Instant::ofEpochMilli)

    /** Sends every reading that carries a body-fat figure again, for a grant that arrived late. */
    suspend fun resendBodyFatToHealth(profileId: Long) =
        dao.forgetHealthExportOfBodyFat(profileId)

    /** Records that Health Connect has been told about these rows, as they stand now. */
    suspend fun markHealthExported(ids: List<Long>) {
        if (ids.isEmpty()) return
        dao.markHealthExported(ids)
    }

    suspend fun addFor(
        profileId: Long,
        grams: Int,
        timestamp: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
        bodyFatPercent: Double? = null,
        source: EntrySource = EntrySource.MANUAL,
        healthConnectId: String? = null,
        clientRecordId: String = UUID.randomUUID().toString(),
        composition: com.weighttrack.core.model.BodyComposition? = null,
        origin: com.weighttrack.core.model.RecordOrigin? = null,
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
                composition = composition,
                origin = origin,
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

    /**
     * Which apps have written readings into this profile's log.
     *
     * Read from the log rather than from a list of scale apps somebody thought of, because the
     * one writing into a person's Health Connect is whichever app they happen to use.
     */
    suspend fun origins(): List<com.weighttrack.core.model.RecordOrigin> =
        dao.origins(profiles.activeId())
            .map { com.weighttrack.core.model.RecordOrigin(it.packageName, it.device) }

    suspend fun byClientRecordId(clientRecordId: String): WeightEntry? =
        dao.byClientRecordId(profiles.activeId(), clientRecordId)?.toDomain()

    /** The same question, asked about a named profile, which is what the sync needs. */
    suspend fun byClientRecordIdFor(profileId: Long, clientRecordId: String): WeightEntry? =
        dao.byClientRecordId(profileId, clientRecordId)?.toDomain()

    /**
     * Everything one person already holds for a single local day.
     *
     * The incremental Health Connect import asks this. A change set carries the record that
     * moved and nothing else, so the day's other readings are only knowable from here.
     */
    suspend fun entriesOnDayFor(profileId: Long, date: LocalDate): List<WeightEntry> =
        dao.onLocalDate(profileId, date.toString()).map { it.toDomain() }

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
        composition: com.weighttrack.core.model.BodyComposition? = null,
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
            composition = composition,
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

    suspend fun delete(entry: WeightEntry): UndoableDelete? {
        val existing = dao.byId(entry.id)
        // Resolved before the transaction, because the fallback reads the active profile off a
        // flow, and a flow read inside a write transaction waits on the connection that
        // transaction is holding.
        val profileId = existing?.profileId ?: profiles.activeId()
        deletions.asOne {
            existing?.let {
                deletions.record(
                    com.weighttrack.core.sync.SyncKind.WEIGHT,
                    it.clientRecordId,
                    profileId = it.profileId,
                )
            }
            dao.delete(entry.toEntity(profileId = profileId))
        }
        return restoring(listOfNotNull(existing))
    }

    private suspend fun profileOf(id: Long): Long =
        dao.byId(id)?.profileId ?: profiles.activeId()

    suspend fun deleteByIds(ids: List<Long>): UndoableDelete? {
        if (ids.isEmpty()) return null
        val removed = deletions.asOne {
            // Read before deleting. Afterwards nothing says what these rows were called on the
            // person's other devices, so the deletion would not travel: the other phone still
            // holds them, has no reason to drop them, and hands them straight back.
            val rows = ids.mapNotNull { dao.byId(it) }
            dao.deleteByIds(ids)
            rows.groupBy { it.profileId }.forEach { (profileId, owned) ->
                deletions.record(
                    com.weighttrack.core.sync.SyncKind.WEIGHT,
                    owned.map { it.clientRecordId },
                    profileId = profileId,
                )
            }
            rows
        }
        return restoring(removed)
    }

    /**
     * Puts deleted readings back under the ids and names they had, and forgets the tombstones.
     *
     * Forgetting matters as much as the row does. A restored reading with its tombstone still
     * standing is published to the other device as both a live record and a deletion, and
     * whichever the merge reads second wins: the reading disappears again a sync later with
     * nothing on either phone to explain it.
     */
    private fun restoring(rows: List<WeightEntryEntity>): UndoableDelete? {
        if (rows.isEmpty()) return null
        return UndoableDelete {
            deletions.asOne {
                dao.insertAll(rows)
                rows.groupBy { it.profileId }.forEach { (profileId, owned) ->
                    deletions.forget(
                        com.weighttrack.core.sync.SyncKind.WEIGHT,
                        owned.map { it.clientRecordId },
                        profileId = profileId,
                    )
                }
            }
        }
    }

    /**
     * Removes readings that have gone from Health Connect.
     *
     * A reading deleted in the scale's own app used to stay here for ever: the import only ever
     * upserted, so the row it had created had nothing left to remove it. Goes through the same
     * path as any other delete, which is what leaves the tombstone that carries it to the
     * person's other devices.
     */
    suspend fun deleteByHealthConnectIds(profileId: Long, healthConnectIds: List<String>): Int {
        if (healthConnectIds.isEmpty()) return 0
        val ids = healthConnectIds.mapNotNull { dao.byHealthConnectId(profileId, it)?.id }
        deleteByIds(ids)
        return ids.size
    }

    suspend fun deleteAll() = dao.deleteAll()

    /** Bulk path for import and sync. Existing rows are matched by identity, never duplicated. */
    /**
     * Brings rows in, replacing any already under the same name for that person.
     *
     * [owner] is here for the restore, which has to resolve the active profile before it opens
     * its transaction: the active profile comes off a flow, and a flow read inside a write
     * transaction waits for the connection that transaction is holding.
     */
    suspend fun upsertAll(entries: List<WeightEntry>, owner: Long? = null) {
        val profileId = owner ?: profiles.activeId()
        entries.forEach { dao.upsertByIdentity(it.toEntity(profileId = profileId)) }
    }

    suspend fun entriesBetween(from: Instant, to: Instant): List<WeightEntry> =
        dao.between(profiles.activeId(), from.toEpochMilli(), to.toEpochMilli()).map { it.toDomain() }
}
