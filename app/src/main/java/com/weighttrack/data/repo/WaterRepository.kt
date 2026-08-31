package com.weighttrack.data.repo

import com.weighttrack.data.db.DailyWaterRow
import com.weighttrack.data.db.WaterDao
import com.weighttrack.core.sync.SyncKind
import com.weighttrack.data.db.WaterEntryEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class WaterEntry(
    val id: Long,
    val timestamp: Instant,
    val localDate: LocalDate,
    val millilitres: Int,
)

data class DailyWater(
    val date: LocalDate,
    val millilitres: Int,
)

@Singleton
/** Scoped to the active profile, which it asks for rather than being told. */
@OptIn(ExperimentalCoroutinesApi::class)
class WaterRepository @Inject constructor(
    private val dao: WaterDao,
    private val profiles: ProfileRepository,
    private val deletions: DeletionRecorder,
) {
    private fun <T> scoped(query: (Long) -> Flow<T>): Flow<T> =
        profiles.activeProfileId.flatMapLatest(query)

    fun observeForDate(date: LocalDate): Flow<List<WaterEntry>> =
        scoped { dao.observeForDate(it, date.toString()) }
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    fun observeTotalForDate(date: LocalDate): Flow<Int> =
        scoped { dao.observeTotalForDate(it, date.toString()) }

    fun observeRecentDays(days: Int = 14): Flow<List<DailyWater>> =
        scoped { dao.observeRecentDays(it, days) }.map { rows -> rows.mapNotNull { it.toDomain() } }

    suspend fun totalForDate(date: LocalDate): Int =
        dao.totalForDate(profiles.activeId(), date.toString())

    /**
     * Records a drink.
     *
     * The day it counts towards comes from the person's own zone, so a glass at 11pm belongs to
     * that evening rather than jumping to tomorrow the way a UTC date would.
     */
    suspend fun add(
        millilitres: Int,
        timestamp: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        healthConnectId: String? = null,
    ): Long {
        if (millilitres <= 0) return -1
        return dao.insert(
            WaterEntryEntity(
                profileId = profiles.activeId(),
                timestampUtcMillis = timestamp.toEpochMilli(),
                localDate = timestamp.atZone(zone).toLocalDate().toString(),
                millilitres = millilitres,
                healthConnectId = healthConnectId,
                updatedAtUtcMillis = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Records that this drink reached Health Connect, under the client record id it was
     * written with. That id is how the record is addressed there, so storing it is what
     * makes a local row and a Health Connect record matchable at all.
     */
    suspend fun markSyncedToHealthConnect(id: Long, clientRecordId: String) {
        dao.setHealthConnectId(id, clientRecordId)
    }

    suspend fun delete(entry: WaterEntry): UndoableDelete? {
        val existing = dao.byId(entry.id) ?: return null
        deletions.asOne {
            dao.delete(existing)
            deletions.record(SyncKind.WATER, existing.syncId, profileId = existing.profileId)
        }
        return restoring(listOf(existing))
    }

    /** Undoes a whole day, for the "I tapped that four times by accident" case. */
    suspend fun clearDate(date: LocalDate): UndoableDelete? {
        val profileId = profiles.activeId()
        // Read outside the transaction only to hand back; the transaction reads them again so
        // the rows written to the tombstones are the ones actually removed.
        val removed = deletions.asOne {
            val rows = dao.forDate(profileId, date.toString())
            dao.deleteForDate(profileId, date.toString())
            deletions.record(SyncKind.WATER, rows.map { it.syncId }, profileId = profileId)
            rows
        }
        return restoring(removed)
    }

    private fun restoring(rows: List<WaterEntryEntity>): UndoableDelete? {
        if (rows.isEmpty()) return null
        return UndoableDelete {
            deletions.asOne {
                dao.insertAll(rows)
                rows.groupBy { it.profileId }.forEach { (profileId, owned) ->
                    deletions.forget(
                        SyncKind.WATER,
                        owned.map { it.syncId },
                        profileId = profileId,
                    )
                }
            }
        }
    }

    suspend fun deleteAll() = dao.deleteAll()

    private fun WaterEntryEntity.toDomain(): WaterEntry? {
        val date = runCatching { LocalDate.parse(localDate) }.getOrNull() ?: return null
        return WaterEntry(
            id = id,
            timestamp = Instant.ofEpochMilli(timestampUtcMillis),
            localDate = date,
            millilitres = millilitres,
        )
    }

    private fun DailyWaterRow.toDomain(): DailyWater? {
        val date = runCatching { LocalDate.parse(localDate) }.getOrNull() ?: return null
        return DailyWater(date, millilitres)
    }
}
