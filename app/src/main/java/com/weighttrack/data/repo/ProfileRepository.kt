package com.weighttrack.data.repo

import com.weighttrack.core.sync.SyncKind
import com.weighttrack.data.db.ProfileDao
import com.weighttrack.data.db.ProfileEntity
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton

/** One person the app keeps readings for. */
data class Profile(
    val id: Long,
    val name: String,
    val position: Int,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 7,
    val reminderMinute: Int = 30,
    /** Empty means every day. */
    val reminderDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val healthConnectEnabled: Boolean = false,
)

/**
 * Who the app is showing.
 *
 * A household shares a scale far more often than it shares a phone. Everything else in the data
 * layer asks this which profile is active rather than being told, so a screen never has to
 * thread an identifier through and can never forget to.
 *
 * There is always at least one profile. The last one cannot be deleted, because every row in
 * every other table points at one and a row with nowhere to belong is a row nobody can see
 * again.
 */
@Singleton
class ProfileRepository @Inject constructor(
    private val dao: ProfileDao,
    private val settingsRepository: SettingsRepository,
    private val deletions: DeletionRecorder,
) {
    fun observeAll(): Flow<List<Profile>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    /**
     * The active profile.
     *
     * The stored choice is checked against what actually exists: a profile deleted on another
     * screen, or a database restored from a backup that never had it, would otherwise leave
     * every screen empty with no way back.
     */
    val activeProfileId: Flow<Long> = combine(
        settingsRepository.settings,
        dao.observeAll(),
    ) { settings, profiles ->
        settings.activeProfileId.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.firstOrNull()?.id
            ?: WeightTrackDatabase.DEFAULT_PROFILE_ID
    }.distinctUntilChanged()

    val activeProfile: Flow<Profile?> = combine(
        activeProfileId,
        dao.observeAll(),
    ) { id, profiles -> profiles.firstOrNull { it.id == id }?.toDomain() }

    suspend fun activeId(): Long = activeProfileId.first()

    suspend fun setActive(id: Long) {
        if (dao.byId(id) == null) return
        settingsRepository.setActiveProfile(id)
    }

    /** Adds a profile and switches to it, because nobody adds one to leave it alone. */
    suspend fun add(name: String): Long {
        val trimmed = name.trim().ifBlank { "Someone" }
        val id = dao.insert(
            ProfileEntity(
                name = trimmed,
                position = dao.highestPosition() + 1,
                createdAtUtcMillis = System.currentTimeMillis(),
            ),
        )
        settingsRepository.setActiveProfile(id)
        return id
    }

    suspend fun rename(id: Long, name: String) {
        val existing = dao.byId(id) ?: return
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        // Stamped, or sync has no way to tell this apart from the row it already has and the new
        // name never leaves the phone.
        dao.update(existing.copy(name = trimmed, updatedAtUtcMillis = System.currentTimeMillis()))
    }

    /**
     * Removes a profile and everything recorded against it.
     *
     * Refuses the last one. An app with no profile has nowhere to put the next reading, and the
     * person would be left staring at a screen with no way to make one.
     */
    suspend fun delete(id: Long): Boolean = deleteReturningPhotos(id) != null

    /**
     * Removes a profile and hands back the photo files it owned.
     *
     * The rows go in one transaction, but the images live on disk and only the photo repository
     * knows where. Returning the names is what lets the caller unlink them, rather than leaving
     * a deleted person's pictures on the phone while the app says they are gone.
     */
    suspend fun deleteReturningPhotos(id: Long): List<String>? {
        if (dao.count() <= 1) return null
        val existing = dao.byId(id) ?: return null
        val photos = dao.photoFileNames(id)
        // Everything this person owned, named before it is gone. One tombstone for the profile is
        // not enough: the other device holds their weigh-ins too, and with nothing to say those
        // are deleted it hands the whole history back and the deleted person reappears.
        val owned = deletions.namesOwnedBy(id)
        dao.deleteWithData(existing)
        deletions.record(SyncKind.PROFILE, existing.syncId)
        // Named from the row read a moment ago. By now the profile is gone, so there is nothing
        // left to look its name up from.
        owned.forEach { (kind, names) -> deletions.recordOwned(kind, names, existing.syncId) }
        if (settingsRepository.settings.first().activeProfileId == id) {
            dao.all().firstOrNull()?.let { settingsRepository.setActiveProfile(it.id) }
        }
        return photos
    }

    /**
     * Makes sure a profile exists.
     *
     * A database created fresh rather than migrated has no rows at all, and the migration only
     * seeds one for an upgrade.
     */
    suspend fun ensureDefault() {
        if (dao.count() > 0) return
        runCatching {
            dao.insert(
                ProfileEntity(
                    id = WeightTrackDatabase.DEFAULT_PROFILE_ID,
                    name = WeightTrackDatabase.DEFAULT_PROFILE_NAME,
                    position = 0,
                    createdAtUtcMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** The profile Health Connect exchanges weights with, or null when nobody has claimed it. */
    val healthConnectProfileId: Flow<Long?> =
        dao.observeAll().map { rows -> rows.firstOrNull { it.healthConnectEnabled }?.id }
            .distinctUntilChanged()

    suspend fun healthConnectId(): Long? = healthConnectProfileId.first()

    suspend fun setReminder(
        id: Long,
        enabled: Boolean,
        hour: Int,
        minute: Int,
        days: Set<DayOfWeek>,
    ) {
        val existing = dao.byId(id) ?: return
        dao.update(
            existing.copy(
                reminderEnabled = enabled,
                reminderHour = hour,
                reminderMinute = minute,
                reminderDays = days.joinToString(",") { it.name },
                updatedAtUtcMillis = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Hands Health Connect to one profile, or to nobody.
     *
     * Exclusive on purpose. Health Connect stores weights for the person the phone belongs to
     * and has no idea a household exists, so two profiles writing to it would interleave two
     * people's readings and each would read the other's back.
     */
    suspend fun setHealthConnect(id: Long, enabled: Boolean) {
        val existing = dao.byId(id) ?: return
        if (enabled) {
            dao.all().filter { it.healthConnectEnabled && it.id != id }
                .forEach { dao.update(it.copy(healthConnectEnabled = false)) }
        }
        dao.update(existing.copy(healthConnectEnabled = enabled))
    }

    /**
     * Moves the reminder that existed before profiles onto the first one.
     *
     * Runs once. Without it, anyone who had a daily reminder set loses it on the update and
     * finds out by not being reminded, which is the worst way to find out.
     */
    suspend fun adoptLegacyReminder() {
        val settings = settingsRepository.settings.first()
        if (settings.legacyReminderAdopted) return
        settingsRepository.setLegacyReminderAdopted()
        if (!settings.reminderEnabled) return
        val first = dao.all().firstOrNull() ?: return
        if (first.reminderEnabled) return
        setReminder(
            id = first.id,
            enabled = true,
            hour = settings.reminderHour,
            minute = settings.reminderMinute,
            days = settings.reminderDays,
        )
    }

    private fun ProfileEntity.toDomain(): Profile = Profile(
        id = id,
        name = name,
        position = position,
        reminderEnabled = reminderEnabled,
        reminderHour = reminderHour,
        reminderMinute = reminderMinute,
        reminderDays = reminderDays.split(",")
            .mapNotNull { name -> runCatching { DayOfWeek.valueOf(name.trim()) }.getOrNull() }
            .toSet()
            .ifEmpty { DayOfWeek.entries.toSet() },
        healthConnectEnabled = healthConnectEnabled,
    )
}
