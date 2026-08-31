package com.weighttrack.data.repo


import androidx.room.withTransaction
import com.weighttrack.core.sync.SyncKind
import com.weighttrack.data.db.DeletionDao
import com.weighttrack.data.db.DeletionEntity
import com.weighttrack.data.db.SyncDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers what has been deleted.
 *
 * Every repository that deletes something a person can see calls this. It is not conditional on
 * sync being switched on: somebody who turns sync on next year would otherwise have everything
 * they ever deleted handed back to them by their other phone.
 *
 * Erasing all data is the one exception. That is a local act, and turning it into a hundred
 * tombstones would carry the wipe to every other device the person owns.
 */
@Singleton
class DeletionRecorder @Inject constructor(
    private val database: com.weighttrack.data.db.WeightTrackDatabase,
    private val dao: DeletionDao,
    private val syncDao: SyncDao,
) {

    /**
     * Removes a row and remembers it, or does neither.
     *
     * Every delete path used to take the row out first and write the tombstone afterwards. The
     * gap between those two writes is small and it is real: the process can be killed in it, and
     * what is left is a row gone from this phone with nothing to say so. The other device still
     * holds it, sees no reason to drop it, and hands it back on the next sync. A deletion that
     * comes home is the single most irritating way for sync to be wrong, and it is the failure
     * this cannot be allowed to produce.
     *
     * Anything that reads a flow, the active profile in particular, has to be done before the
     * block: a Room flow collected inside a write transaction waits for a connection the
     * transaction is holding.
     */
    suspend fun <T> asOne(block: suspend () -> T): T = database.withTransaction { block() }

    /**
     * What a profile owns, by the names its rows travel under.
     *
     * Read before the profile is deleted. Afterwards the rows are gone and nothing is left to say
     * what they were called, so their deletion could never travel and the other device would hand
     * the whole history back.
     */
    suspend fun namesOwnedBy(profileId: Long): List<Pair<SyncKind, List<String>>> = listOf(
        SyncKind.WEIGHT to syncDao.weightNames(profileId),
        SyncKind.MEASUREMENT to syncDao.measurementNames(profileId),
        SyncKind.WATER to syncDao.waterNames(profileId),
        SyncKind.FAST to syncDao.fastNames(profileId),
        SyncKind.GOAL to syncDao.goalNames(profileId),
        SyncKind.MACRO_TARGET to syncDao.macroTargetNames(profileId),
        // The diary belongs to a person too, and deleting a profile takes it with it. Left out,
        // the other device goes on offering days of eating for somebody who no longer exists,
        // and every sync afterwards reports them as records with nowhere to belong.
        SyncKind.FOOD_LOG to syncDao.foodLogNames(profileId),
    )

    /**
     * Remembers one deleted row.
     *
     * [profileId] is whose it was. A record's name is only unique within a profile, so a deletion
     * that does not say which one takes another person's identically named row out with it. A
     * profile's own deletion passes nothing, because a profile belongs to nobody but itself.
     */
    suspend fun record(
        kind: SyncKind,
        syncId: String,
        at: Long = System.currentTimeMillis(),
        profileId: Long? = null,
    ) {
        if (syncId.isBlank()) return
        dao.record(
            DeletionEntity(
                kind = kind.name,
                syncId = syncId,
                deletedAtUtcMillis = at,
                profileSyncId = profileNameOf(profileId),
            ),
        )
    }

    suspend fun record(
        kind: SyncKind,
        syncIds: List<String>,
        at: Long = System.currentTimeMillis(),
        profileId: Long? = null,
    ) {
        val usable = syncIds.filter { it.isNotBlank() }
        if (usable.isEmpty()) return
        val owner = profileNameOf(profileId)
        dao.recordAll(
            usable.map {
                DeletionEntity(
                    kind = kind.name,
                    syncId = it,
                    deletedAtUtcMillis = at,
                    profileSyncId = owner,
                )
            },
        )
    }

    /**
     * Remembers rows whose owner is named directly.
     *
     * For deleting a profile: by the time its rows are gone the profile is gone too, so there is
     * nothing left to look the name up from. It has to be read first and handed in.
     */
    suspend fun recordOwned(
        kind: SyncKind,
        syncIds: List<String>,
        profileSyncId: String,
        at: Long = System.currentTimeMillis(),
    ) {
        val usable = syncIds.filter { it.isNotBlank() }
        if (usable.isEmpty()) return
        dao.recordAll(
            usable.map {
                DeletionEntity(
                    kind = kind.name,
                    syncId = it,
                    deletedAtUtcMillis = at,
                    profileSyncId = profileSyncId,
                )
            },
        )
    }

    private suspend fun profileNameOf(profileId: Long?): String {
        if (profileId == null) return ""
        return syncDao.profiles().firstOrNull { it.id == profileId }?.syncId.orEmpty()
    }

    /**
     * What was deleted for one person since a moment, of one kind.
     *
     * For the Health Connect export: a reading deleted here has to be deleted there too, and
     * the tombstone is the only record of it once the row is gone.
     */
    suspend fun since(kind: SyncKind, profileSyncId: String, since: Long): List<String> =
        dao.since(since)
            .filter { it.kind == kind.name && it.profileSyncId == profileSyncId }
            .map { it.syncId }

    /**
     * Forgets that a row was ever deleted.
     *
     * Used when a record arrives from another device having been edited since the deletion, so
     * the tombstone no longer describes anything true. Leaving it would delete the row again on
     * the next pass and the two devices would take turns undoing each other.
     */
    suspend fun forget(kind: SyncKind, syncIds: List<String>) {
        if (syncIds.isEmpty()) return
        dao.forget(kind.name, syncIds)
    }
}
