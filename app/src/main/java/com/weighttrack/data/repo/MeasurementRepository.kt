package com.weighttrack.data.repo

import com.weighttrack.core.model.BodyMeasurement
import com.weighttrack.core.model.MeasurementType
import com.weighttrack.data.db.MeasurementDao
import com.weighttrack.data.db.MeasurementEntity
import com.weighttrack.data.db.toDomain
import com.weighttrack.core.sync.SyncKind
import com.weighttrack.data.db.toEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/** Scoped to the active profile, which it asks for rather than being told. */
@OptIn(ExperimentalCoroutinesApi::class)
class MeasurementRepository @Inject constructor(
    private val dao: MeasurementDao,
    private val profiles: ProfileRepository,
    private val deletions: DeletionRecorder,
) {
    private fun <T> scoped(query: (Long) -> Flow<T>): Flow<T> =
        profiles.activeProfileId.flatMapLatest(query)

    fun observeAll(): Flow<List<BodyMeasurement>> =
        scoped { dao.observeAll(it) }.map { rows -> rows.mapNotNull { it.toDomain() } }

    fun observeByType(type: MeasurementType): Flow<List<BodyMeasurement>> =
        scoped { dao.observeByType(it, type.name) }
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    /** Newest value per measurement type, keyed for direct lookup by the body fat estimate. */
    fun observeLatestPerType(): Flow<Map<MeasurementType, BodyMeasurement>> =
        scoped { dao.observeLatestPerType(it) }.map { rows ->
            rows.mapNotNull { it.toDomain() }.associateBy { it.type }
        }

    suspend fun latestPerType(): Map<MeasurementType, BodyMeasurement> =
        dao.latestPerType(profiles.activeId()).mapNotNull { it.toDomain() }.associateBy { it.type }

    suspend fun add(
        type: MeasurementType,
        valueMm: Int,
        timestamp: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        note: String? = null,
        carried: Boolean = false,
    ): Long {
        val measurement = BodyMeasurement(
            timestamp = timestamp,
            localDate = timestamp.atZone(zone).toLocalDate(),
            type = type,
            valueMm = valueMm,
            note = note,
            carried = carried,
        )
        return dao.insert(measurement.toEntity(profileId = profiles.activeId()))
    }

    /**
     * Records a whole set at one moment, saying which values were measured and which carried.
     *
     * Thirteen sites and almost nobody changes all thirteen, so a set typed one site at a time
     * is why people stop measuring. Writing the unchanged ones keeps each set complete, and the
     * carried flag keeps them honest: they are a fact about the last time somebody measured.
     *
     * Nothing is written when nothing was measured. A set of thirteen carried values records a
     * day on which nobody got the tape out.
     */
    suspend fun addSet(
        measured: Map<MeasurementType, Int>,
        carried: Map<MeasurementType, Int> = emptyMap(),
        timestamp: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int {
        if (measured.isEmpty()) return 0
        val date = timestamp.atZone(zone).toLocalDate()
        val owner = profiles.activeId()
        val rows = (measured.map { it to false } + carried.map { it to true })
            .filter { (entry, _) -> entry.value > 0 }
            .map { (entry, wasCarried) ->
                BodyMeasurement(
                    timestamp = timestamp,
                    localDate = date,
                    type = entry.key,
                    valueMm = entry.value,
                    carried = wasCarried,
                ).toEntity(profileId = owner)
            }
        dao.insertAll(rows)
        return rows.size
    }

    suspend fun update(measurement: BodyMeasurement) {
        val existing = dao.byId(measurement.id) ?: return
        dao.update(
            measurement.toEntity(profileId = existing.profileId, syncId = existing.syncId),
        )
    }

    suspend fun delete(measurement: BodyMeasurement): UndoableDelete? {
        val existing = dao.byId(measurement.id) ?: return null
        deletions.asOne {
            dao.delete(existing)
            deletions.record(SyncKind.MEASUREMENT, existing.syncId, profileId = existing.profileId)
        }
        return restoring(listOf(existing))
    }

    /** Read back off the stored row so an edit cannot move a measurement to another profile. */
    private suspend fun profileOf(id: Long): Long =
        dao.byId(id)?.profileId ?: profiles.activeId()

    suspend fun deleteByIds(ids: List<Long>): UndoableDelete? {
        if (ids.isEmpty()) return null
        val removed = deletions.asOne {
            // Read before deleting. Afterwards there is nothing left to say what these rows were
            // called on the person's other devices, and the deletion would not travel.
            val rows = ids.mapNotNull { dao.byId(it) }
            dao.deleteByIds(ids)
            rows.groupBy { it.profileId }.forEach { (profileId, owned) ->
                deletions.record(
                    SyncKind.MEASUREMENT,
                    owned.map { it.syncId },
                    profileId = profileId,
                )
            }
            rows
        }
        return restoring(removed)
    }

    private fun restoring(rows: List<MeasurementEntity>): UndoableDelete? {
        if (rows.isEmpty()) return null
        return UndoableDelete {
            deletions.asOne {
                dao.insertAll(rows)
                rows.groupBy { it.profileId }.forEach { (profileId, owned) ->
                    deletions.forget(
                        SyncKind.MEASUREMENT,
                        owned.map { it.syncId },
                        profileId = profileId,
                    )
                }
            }
        }
    }

    /** [owner] is for the restore. See [WeightRepository.upsertAll]. */
    suspend fun upsertAll(measurements: List<BodyMeasurement>, owner: Long? = null) {
        if (measurements.isEmpty()) return
        val profileId = owner ?: profiles.activeId()
        dao.insertAll(measurements.map { it.toEntity(profileId = profileId) })
    }

    suspend fun deleteAll() = dao.deleteAll()
}
