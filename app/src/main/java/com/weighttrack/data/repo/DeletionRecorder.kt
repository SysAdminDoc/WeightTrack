package com.weighttrack.data.repo

import com.weighttrack.core.sync.SyncKind
import com.weighttrack.data.db.DeletionDao
import com.weighttrack.data.db.DeletionEntity
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
    private val dao: DeletionDao,
) {
    suspend fun record(kind: SyncKind, syncId: String, at: Long = System.currentTimeMillis()) {
        if (syncId.isBlank()) return
        dao.record(DeletionEntity(kind = kind.name, syncId = syncId, deletedAtUtcMillis = at))
    }

    suspend fun record(kind: SyncKind, syncIds: List<String>, at: Long = System.currentTimeMillis()) {
        val usable = syncIds.filter { it.isNotBlank() }
        if (usable.isEmpty()) return
        dao.recordAll(
            usable.map { DeletionEntity(kind = kind.name, syncId = it, deletedAtUtcMillis = at) },
        )
    }

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
