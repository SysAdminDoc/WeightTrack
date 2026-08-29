package com.weighttrack.data.repo

import com.weighttrack.data.db.DailyWaterRow
import com.weighttrack.data.db.WaterDao
import com.weighttrack.data.db.WaterEntryEntity
import kotlinx.coroutines.flow.Flow
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
class WaterRepository @Inject constructor(
    private val dao: WaterDao,
) {
    fun observeForDate(date: LocalDate): Flow<List<WaterEntry>> =
        dao.observeForDate(date.toString()).map { rows -> rows.mapNotNull { it.toDomain() } }

    fun observeTotalForDate(date: LocalDate): Flow<Int> = dao.observeTotalForDate(date.toString())

    fun observeRecentDays(days: Int = 14): Flow<List<DailyWater>> =
        dao.observeRecentDays(days).map { rows -> rows.mapNotNull { it.toDomain() } }

    suspend fun totalForDate(date: LocalDate): Int = dao.totalForDate(date.toString())

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
                timestampUtcMillis = timestamp.toEpochMilli(),
                localDate = timestamp.atZone(zone).toLocalDate().toString(),
                millilitres = millilitres,
                healthConnectId = healthConnectId,
                updatedAtUtcMillis = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun delete(entry: WaterEntry) {
        dao.byId(entry.id)?.let { dao.delete(it) }
    }

    /** Undoes a whole day, for the "I tapped that four times by accident" case. */
    suspend fun clearDate(date: LocalDate) = dao.deleteForDate(date.toString())

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
