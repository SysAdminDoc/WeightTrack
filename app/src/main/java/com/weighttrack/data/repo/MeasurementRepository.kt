package com.weighttrack.data.repo

import com.weighttrack.core.model.BodyMeasurement
import com.weighttrack.core.model.MeasurementType
import com.weighttrack.data.db.MeasurementDao
import com.weighttrack.data.db.toDomain
import com.weighttrack.data.db.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeasurementRepository @Inject constructor(
    private val dao: MeasurementDao,
) {
    fun observeAll(): Flow<List<BodyMeasurement>> =
        dao.observeAll().map { rows -> rows.mapNotNull { it.toDomain() } }

    fun observeByType(type: MeasurementType): Flow<List<BodyMeasurement>> =
        dao.observeByType(type.name).map { rows -> rows.mapNotNull { it.toDomain() } }

    /** Newest value per measurement type, keyed for direct lookup by the body fat estimate. */
    fun observeLatestPerType(): Flow<Map<MeasurementType, BodyMeasurement>> =
        dao.observeLatestPerType().map { rows ->
            rows.mapNotNull { it.toDomain() }.associateBy { it.type }
        }

    suspend fun latestPerType(): Map<MeasurementType, BodyMeasurement> =
        dao.latestPerType().mapNotNull { it.toDomain() }.associateBy { it.type }

    suspend fun add(
        type: MeasurementType,
        valueMm: Int,
        timestamp: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        note: String? = null,
    ): Long {
        val measurement = BodyMeasurement(
            timestamp = timestamp,
            localDate = timestamp.atZone(zone).toLocalDate(),
            type = type,
            valueMm = valueMm,
            note = note,
        )
        return dao.insert(measurement.toEntity())
    }

    suspend fun update(measurement: BodyMeasurement) = dao.update(measurement.toEntity())

    suspend fun delete(measurement: BodyMeasurement) = dao.delete(measurement.toEntity())

    suspend fun deleteByIds(ids: List<Long>) {
        if (ids.isNotEmpty()) dao.deleteByIds(ids)
    }

    suspend fun upsertAll(measurements: List<BodyMeasurement>) {
        if (measurements.isNotEmpty()) dao.insertAll(measurements.map { it.toEntity() })
    }

    suspend fun deleteAll() = dao.deleteAll()
}
