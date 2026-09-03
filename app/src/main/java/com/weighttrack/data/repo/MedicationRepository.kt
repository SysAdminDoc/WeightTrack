package com.weighttrack.data.repo

import com.weighttrack.core.medication.GlpDrug
import com.weighttrack.core.medication.InjectionSite
import com.weighttrack.core.medication.SideEffectKind
import com.weighttrack.core.medication.SideEffectSeverity
import com.weighttrack.core.medication.SiteRotation
import com.weighttrack.core.sync.SyncKind
import com.weighttrack.data.db.MedicationDoseDao
import com.weighttrack.data.db.MedicationDoseEntity
import com.weighttrack.data.db.SideEffectDao
import com.weighttrack.data.db.SideEffectEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** One injection, as the rest of the app sees it. */
data class MedicationDose(
    val id: Long,
    val timestamp: Instant,
    val localDate: LocalDate,
    val drug: GlpDrug,
    val milligrams: Double,
    val site: InjectionSite,
    val note: String?,
)

/** Something somebody felt, on a day. */
data class SideEffect(
    val id: Long,
    val timestamp: Instant,
    val localDate: LocalDate,
    val kind: SideEffectKind,
    val severity: SideEffectSeverity,
    val note: String?,
)

/**
 * The injection log, scoped to whoever is active.
 *
 * Nothing here is ever read unless the medication toggle is on. It is kept as ordinary rows in the
 * ordinary database rather than anywhere special: it syncs between somebody's own devices like
 * everything else, it is in the backup like everything else, and it never leaves the phone by any
 * other route. Health Connect is deliberately not involved, because its medical records have no
 * Play policy behind them yet.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class MedicationRepository @Inject constructor(
    private val doses: MedicationDoseDao,
    private val effects: SideEffectDao,
    private val profiles: ProfileRepository,
    private val deletions: DeletionRecorder,
) {
    private fun <T> scoped(query: (Long) -> Flow<T>): Flow<T> =
        profiles.activeProfileId.flatMapLatest(query)

    fun observeDoses(): Flow<List<MedicationDose>> =
        scoped { doses.observeAll(it) }.map { rows -> rows.mapNotNull { it.toDomain() } }

    fun observeSideEffects(): Flow<List<SideEffect>> =
        scoped { effects.observeAll(it) }.map { rows -> rows.mapNotNull { it.toDomain() } }

    suspend fun dosesBetween(from: Instant, to: Instant): List<MedicationDose> =
        doses.between(profiles.activeId(), from.toEpochMilli(), to.toEpochMilli())
            .mapNotNull { it.toDomain() }

    suspend fun sideEffectsBetween(from: Instant, to: Instant): List<SideEffect> =
        effects.between(profiles.activeId(), from.toEpochMilli(), to.toEpochMilli())
            .mapNotNull { it.toDomain() }

    /**
     * Where to put the next one.
     *
     * Worked out from what is recorded rather than kept as a pointer, so it stays right after a
     * dose is deleted, after a sync brings one in from the other phone, and after a restore.
     */
    suspend fun suggestedSite(): InjectionSite = SiteRotation.next(
        doses.recentSites(profiles.activeId(), limit = RECENT_SITES)
            .mapNotNull { name -> runCatching { InjectionSite.valueOf(name) }.getOrNull() },
    )

    suspend fun addDose(
        drug: GlpDrug,
        milligrams: Double,
        site: InjectionSite,
        timestamp: Instant = Instant.now(),
        note: String? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        if (milligrams <= 0) return -1
        return doses.insert(
            MedicationDoseEntity(
                profileId = profiles.activeId(),
                timestampUtcMillis = timestamp.toEpochMilli(),
                localDate = timestamp.atZone(zone).toLocalDate().toString(),
                drug = drug.name,
                milligrams = milligrams,
                site = site.name,
                note = note?.takeIf { it.isNotBlank() },
                updatedAtUtcMillis = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun updateDose(
        id: Long,
        drug: GlpDrug,
        milligrams: Double,
        site: InjectionSite,
        timestamp: Instant,
        note: String?,
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        if (milligrams <= 0) return
        val existing = doses.byId(id) ?: return
        doses.update(
            existing.copy(
                timestampUtcMillis = timestamp.toEpochMilli(),
                localDate = timestamp.atZone(zone).toLocalDate().toString(),
                drug = drug.name,
                milligrams = milligrams,
                site = site.name,
                note = note?.takeIf { it.isNotBlank() },
                updatedAtUtcMillis = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteDose(id: Long): UndoableDelete? {
        val existing = doses.byId(id) ?: return null
        deletions.asOne {
            doses.delete(existing)
            deletions.record(
                SyncKind.MEDICATION_DOSE,
                existing.syncId,
                profileId = existing.profileId,
            )
        }
        return UndoableDelete {
            deletions.asOne {
                doses.insertAll(listOf(existing))
                deletions.forget(
                    SyncKind.MEDICATION_DOSE,
                    listOf(existing.syncId),
                    profileId = existing.profileId,
                )
            }
        }
    }

    suspend fun addSideEffect(
        kind: SideEffectKind,
        severity: SideEffectSeverity,
        timestamp: Instant = Instant.now(),
        note: String? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long = effects.insert(
        SideEffectEntity(
            profileId = profiles.activeId(),
            timestampUtcMillis = timestamp.toEpochMilli(),
            localDate = timestamp.atZone(zone).toLocalDate().toString(),
            kind = kind.name,
            severity = severity.name,
            note = note?.takeIf { it.isNotBlank() },
            updatedAtUtcMillis = System.currentTimeMillis(),
        ),
    )

    suspend fun deleteSideEffect(id: Long): UndoableDelete? {
        val existing = effects.byId(id) ?: return null
        deletions.asOne {
            effects.delete(existing)
            deletions.record(SyncKind.SIDE_EFFECT, existing.syncId, profileId = existing.profileId)
        }
        return UndoableDelete {
            deletions.asOne {
                effects.insertAll(listOf(existing))
                deletions.forget(
                    SyncKind.SIDE_EFFECT,
                    listOf(existing.syncId),
                    profileId = existing.profileId,
                )
            }
        }
    }

    private companion object {
        /**
         * How far back the rotation looks.
         *
         * Only needs to cover a lap of the rotation and a little either side. Reading the whole
         * history to decide where one injection goes would grow with the years for no gain.
         */
        const val RECENT_SITES = 20
    }

    private fun MedicationDoseEntity.toDomain(): MedicationDose? {
        val date = runCatching { LocalDate.parse(localDate) }.getOrNull() ?: return null
        val known = runCatching { GlpDrug.valueOf(drug) }.getOrNull() ?: GlpDrug.OTHER
        val where = runCatching { InjectionSite.valueOf(site) }.getOrNull() ?: return null
        return MedicationDose(
            id = id,
            timestamp = Instant.ofEpochMilli(timestampUtcMillis),
            localDate = date,
            drug = known,
            milligrams = milligrams,
            site = where,
            note = note,
        )
    }

    private fun SideEffectEntity.toDomain(): SideEffect? {
        val date = runCatching { LocalDate.parse(localDate) }.getOrNull() ?: return null
        val what = runCatching { SideEffectKind.valueOf(kind) }.getOrNull() ?: SideEffectKind.OTHER
        val how = runCatching { SideEffectSeverity.valueOf(severity) }.getOrNull() ?: return null
        return SideEffect(
            id = id,
            timestamp = Instant.ofEpochMilli(timestampUtcMillis),
            localDate = date,
            kind = what,
            severity = how,
            note = note,
        )
    }
}
