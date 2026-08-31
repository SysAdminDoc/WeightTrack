package com.weighttrack.ui.settings

import com.weighttrack.R
import com.weighttrack.core.model.ActivityLevel
import com.weighttrack.core.model.Sex
import com.weighttrack.core.model.UserProfile
import com.weighttrack.data.repo.Profile
import com.weighttrack.data.repo.ProfileRepository
import com.weighttrack.data.repo.ProgressPhotoRepository
import com.weighttrack.data.repo.UndoableDelete
import com.weighttrack.health.HealthConnectSync
import com.weighttrack.notifications.ReminderScheduler
import com.weighttrack.ui.AppStrings
import com.weighttrack.ui.UndoCoordinator
import com.weighttrack.widget.SurfaceUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * Everybody the phone keeps records for, and what belongs to each of them.
 *
 * The body figures and the weigh-in reminder are on this side of the line because they belong to a
 * profile rather than to the phone. A household of two sharing one height had every BMI, healthy
 * range, body-fat estimate, basal rate and expenditure computed from whichever of them typed
 * theirs in, and nothing on any screen said so.
 */
internal class PeopleSettingsController(
    private val scope: CoroutineScope,
    private val profileRepository: ProfileRepository,
    private val progressPhotoRepository: ProgressPhotoRepository,
    private val reminderScheduler: ReminderScheduler,
    private val healthConnect: HealthConnectSync,
    private val undoOffers: UndoCoordinator,
    private val surfaces: SurfaceUpdater,
    private val strings: AppStrings,
    private val onMessage: (String?) -> Unit,
) {

    val profiles: StateFlow<List<Profile>> = profileRepository.observeAll()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeProfileId: StateFlow<Long> = profileRepository.activeProfileId
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0L)

    val demographics: StateFlow<UserProfile> = profileRepository.activeProfile
        .map { it?.demographics ?: UserProfile() }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), UserProfile())

    fun setHeightMm(mm: Int) = editDemographics { it.copy(heightMm = mm) }

    fun setProfile(profile: UserProfile) = editDemographics { profile }

    fun setSex(sex: Sex) = editDemographics { it.copy(sex = sex) }

    fun setBirthYear(year: Int) = editDemographics { it.copy(birthYear = year) }

    fun setActivityLevel(level: ActivityLevel) = editDemographics { it.copy(activityLevel = level) }

    private fun editDemographics(change: (UserProfile) -> UserProfile) = scope.launch {
        // Read from the row rather than from the state flow, so a change made in the moment
        // between the screen collecting and the tap landing is not written back over.
        val id = profileRepository.activeId()
        val current = profileRepository.observeAll().first().firstOrNull { it.id == id }
            ?.demographics
            ?: UserProfile()
        profileRepository.setDemographics(id, change(current))
    }

    /**
     * Any reminder change reschedules immediately, so the setting and the alarm cannot drift.
     *
     * The reminder belongs to the profile on screen, not to the phone: two people in a house
     * weigh themselves at different times.
     */
    fun setReminder(enabled: Boolean, hour: Int, minute: Int, days: Set<DayOfWeek>) {
        scope.launch {
            val id = activeProfileId.value
            profileRepository.setReminder(id, enabled, hour, minute, days)
            val updated = profileRepository.observeAll().first().firstOrNull { it.id == id }
                ?: return@launch
            reminderScheduler.reschedule(updated)
            onMessage(
                if (!enabled) {
                    strings[R.string.settings_reminders_turned_off_for_somebody, updated.name]
                } else {
                    reminderScheduler.nextTriggerAt(updated)
                        ?.let { next ->
                            strings[
                                R.string.settings_next_reminder_for_somebody,
                                updated.name,
                                strings.describeNext(next),
                            ]
                        }
                        ?: strings[R.string.settings_pick_at_least_one_day_for]
                },
            )
        }
    }

    /** Hands Health Connect to the profile on screen, or takes it away. */
    fun setHealthConnectProfile(enabled: Boolean) {
        scope.launch {
            // Never while a sync is in flight. Changing hands halfway through would file the
            // rest of that sync's import against the new owner and leave the export half done
            // under the old one.
            healthConnect.whileNotSyncing {
                profileRepository.setHealthConnect(activeProfileId.value, enabled)
            }
            onMessage(
                if (enabled) {
                    strings[R.string.settings_health_connect_now_exchanges_weights_for]
                } else {
                    strings[R.string.settings_health_connect_is_no_longer_tied]
                },
            )
        }
    }

    fun switchProfile(id: Long) {
        scope.launch {
            profileRepository.setActive(id)
            // Everything glanceable is showing somebody else's numbers until this runs.
            surfaces.refresh()
        }
    }

    fun addProfile(name: String) {
        scope.launch {
            profileRepository.add(name)
            surfaces.refresh()
            onMessage(strings[R.string.settings_switched_to_somebody, name.trim()])
        }
    }

    fun renameProfile(id: Long, name: String) {
        scope.launch {
            profileRepository.rename(id, name)
            surfaces.refresh()
        }
    }

    fun deleteProfile(id: Long) {
        scope.launch {
            val name = profiles.value.firstOrNull { it.id == id }?.name
            val deletion = profileRepository.deleteReturningPhotos(id)
            if (deletion != null) {
                // The alarm outlives the row it belonged to, and would go off once more under
                // somebody else's name. The pictures outlive it on disk, so they are moved aside
                // rather than unlinked while the undo is still on offer.
                reminderScheduler.cancel(id)
                val held = progressPhotoRepository.holdForUndo(deletion.photoFileNames)
                surfaces.refresh()
                undoOffers.offer(
                    UndoableDelete(release = { progressPhotoRepository.releaseHeld(held) }) {
                        // The files first. A row whose image is not yet back reads as a photo
                        // that has gone, and the screen simply does not list it.
                        progressPhotoRepository.returnFromUndo(held)
                        deletion.restore()
                    },
                    strings[R.string.settings_deleted_and_everything_recorded_for_them, name.orEmpty()],
                ) {
                    // The reminder lives on the profile row, so it comes back with the person and
                    // has to be booked again.
                    profileRepository.byId(id)?.let { reminderScheduler.reschedule(it) }
                    surfaces.refresh()
                }
            } else {
                // Refusing to delete the last one is deliberate: the app would have nowhere to
                // put the next reading and no way to make a profile to fix it.
                onMessage(strings[R.string.settings_there_has_to_be_somebody_add])
            }
        }
    }
}

/** When a scheduled thing next happens, said the way somebody would say it. */
internal fun AppStrings.describeNext(next: ZonedDateTime): String {
    val time = "%02d:%02d".format(next.hour, next.minute)
    val today = ZonedDateTime.now(next.zone).toLocalDate()
    return when (next.toLocalDate()) {
        today -> this[R.string.settings_today_at, time]
        today.plusDays(1) -> this[R.string.settings_tomorrow_at, time]
        else -> this[
            R.string.settings_on_day_at,
            next.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() },
            time,
        ]
    }
}
