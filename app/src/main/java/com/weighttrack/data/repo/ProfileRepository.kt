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
    /** The body this person's figures are worked out from. Blank until they say. */
    val demographics: com.weighttrack.core.model.UserProfile =
        com.weighttrack.core.model.UserProfile(),
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
    // The dao rather than WeightRepository, which depends on this one.
    private val weights: com.weighttrack.data.db.WeightEntryDao,
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

    /** One person, read straight off the row. Used after an undo puts them back. */
    suspend fun byId(id: Long): Profile? = dao.byId(id)?.toDomain()

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
     * A deleted profile: which images went with it, and how to bring the whole person back.
     *
     * The rows and the files are separated because only the photo repository knows where an
     * image lives, and only the database knows what pointed at it.
     */
    class ProfileDeletion internal constructor(
        val photoFileNames: List<String>,
        val restore: suspend () -> Unit,
    )

    /**
     * Removes a profile and hands back the photo files it owned, with a way to put it all back.
     *
     * The rows go in one transaction, but the images live on disk and only the photo repository
     * knows where. Returning the names is what lets the caller move them aside, rather than
     * leaving a deleted person's pictures on the phone while the app says they are gone.
     */
    suspend fun deleteReturningPhotos(id: Long): ProfileDeletion? {
        if (dao.count() <= 1) return null
        val existing = dao.byId(id) ?: return null
        val photos = dao.photoFileNames(id)
        // Read before the transaction: whether this person is the one on screen comes out of a
        // preferences flow, and the answer cannot change while the rows are going.
        val wasActive = settingsRepository.settings.first().activeProfileId == id
        val (owned, data) = deletions.asOne {
            // Everything this person owned, named before it is gone. One tombstone for the
            // profile is not enough: the other device holds their weigh-ins too, and with nothing
            // to say those are deleted it hands the whole history back and the deleted person
            // reappears.
            val names = deletions.namesOwnedBy(id)
            // The rows themselves, for the undo. Read here rather than by the caller afterwards,
            // because afterwards there is nothing left to read.
            val rows = dao.dataOf(id)
            dao.deleteWithData(existing)
            deletions.record(SyncKind.PROFILE, existing.syncId)
            // Named from the row read a moment ago. By now the profile is gone, so there is
            // nothing left to look its name up from.
            names.forEach { (kind, owned) -> deletions.recordOwned(kind, owned, existing.syncId) }
            names to rows
        }
        if (wasActive) {
            dao.all().firstOrNull()?.let { settingsRepository.setActiveProfile(it.id) }
        }
        // The names are handed back only now, after the rows are committed. Moving the files
        // first and then failing would leave a person's history pointing at pictures that are
        // not where the rows say they are.
        return ProfileDeletion(
            photoFileNames = photos,
            restore = {
                deletions.asOne {
                    dao.restoreWithData(existing, data)
                    deletions.forgetOwned(SyncKind.PROFILE, listOf(existing.syncId), "")
                    owned.forEach { (kind, names) ->
                        deletions.forgetOwned(kind, names, existing.syncId)
                    }
                }
                if (wasActive) settingsRepository.setActiveProfile(existing.id)
            },
        )
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

    /**
     * The progress-photo files this profile owns.
     *
     * Photos are not in the sync document and never have been, so nothing that reasons about a
     * profile from its sync snapshot can see them. Anybody asking whether a profile is untouched
     * has to ask this as well or it will decide an album of photographs is nothing at all.
     */
    suspend fun photoFileNamesOf(id: Long): List<String> = dao.photoFileNames(id)

    /**
     * Records the body one person's figures are worked out from.
     *
     * Stamped, or the change looks to sync exactly like the row the other device already has and
     * never leaves the phone.
     */
    suspend fun setDemographics(id: Long, demographics: com.weighttrack.core.model.UserProfile) {
        val existing = dao.byId(id) ?: return
        dao.update(
            existing.copy(
                heightMm = demographics.heightMm,
                sex = demographics.sex.name,
                birthYear = demographics.birthYear,
                activityLevel = demographics.activityLevel.name,
                updatedAtUtcMillis = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Moves the height, sex, year of birth and activity level that existed before profiles onto
     * one profile.
     *
     * Runs once, and only onto whoever was active at the time. Handing them to every profile
     * would tell a household that everybody is the same height, and leaving them behind would
     * lose figures somebody typed in.
     */
    suspend fun adoptLegacyDemographics() {
        val settings = settingsRepository.settings.first()
        if (settings.legacyDemographicsAdopted) return
        val active = dao.byId(activeId())
        // Nothing to move, and nothing to overwrite if this profile has already been filled in.
        val worthMoving = active != null &&
            (settings.profile.heightMm > 0 || settings.profile.birthYear > 0) &&
            active.heightMm <= 0 && active.birthYear <= 0
        if (worthMoving) setDemographics(active.id, settings.profile)
        // Marked afterwards. Written first, a kill in between would lose the values for good:
        // the flag says it has been done and the row still says nothing.
        settingsRepository.setLegacyDemographicsAdopted()
    }

    /** The name a profile travels under, which is what a tombstone names it by. */
    suspend fun syncIdOf(id: Long): String? = dao.byId(id)?.syncId

    /** The profile Health Connect exchanges weights with, or null when nobody has claimed it. */
    val healthConnectProfileId: Flow<Long?> =
        dao.observeAll().map { rows -> rows.firstOrNull { it.healthConnectEnabled }?.id }
            .distinctUntilChanged()

    suspend fun healthConnectId(): Long? = healthConnectProfileId.first()

    /**
     * Whose readings Health Connect exchanges, deciding it once and for all if nobody has.
     *
     * Following the active profile whenever nobody had claimed it looked harmless while there
     * was only one, which is every install until the day somebody adds a second. From that day
     * on, switching person silently pointed Health Connect at them: their scale readings landed
     * on the other person's history, and the first person's weigh-ins were written into a Health
     * Connect the phone's owner reads as their own. Nothing said it had happened.
     *
     * So the first thing that needs an answer gets one and it is written down. Whoever was
     * active at that moment is the person Health Connect has been exchanging with all along,
     * which makes this the answer that changes nothing for anybody already using it.
     */
    suspend fun claimHealthConnect(): Long? {
        healthConnectId()?.let { return it }
        // Nobody holds it, and somebody has already answered the question: they turned it off.
        // Claiming here would make switching it off an hour-long pause that then points Health
        // Connect at whoever is on screen and pours one person's history into another's.
        if (settingsRepository.healthConnectDecided()) return null
        val active = activeId()
        setHealthConnect(active, enabled = true)
        return active
    }

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
        // Either way, the question has now been answered by a person rather than by the app.
        settingsRepository.setHealthConnectDecided()
        if (enabled) {
            dao.all().filter { it.healthConnectEnabled && it.id != id }
                .forEach { losing ->
                    dao.update(losing.copy(healthConnectEnabled = false))
                    // Only the claiming profile is exchanged with Health Connect, so the links
                    // this person's readings carry now point at records nothing will ever look
                    // up again. The readings stay; the dead pointer goes.
                    weights.clearHealthConnectLinks(losing.id)
                }
        } else {
            weights.clearHealthConnectLinks(id)
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
        demographics = com.weighttrack.core.model.UserProfile(
            heightMm = heightMm,
            // Empty means nobody has said. The default stands in for the arithmetic rather than
            // claiming to be an answer, which is what `hasDemographics` is for.
            sex = com.weighttrack.core.model.Sex.entries.firstOrNull { it.name == sex }
                ?: com.weighttrack.core.model.Sex.MALE,
            birthYear = birthYear,
            activityLevel = com.weighttrack.core.model.ActivityLevel.entries
                .firstOrNull { it.name == activityLevel }
                ?: com.weighttrack.core.model.ActivityLevel.LIGHT,
        ),
    )
}
