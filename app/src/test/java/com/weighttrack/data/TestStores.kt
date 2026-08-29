package com.weighttrack.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.weighttrack.data.repo.DeletionRecorder
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Settings held in memory.
 *
 * A real datastore does its file work off the test scheduler, so advancing virtual time returns
 * while a view model is still waiting to be told the unit, and the test ends up measuring the
 * scheduler rather than the code.
 */
internal class InMemoryPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data = state
    override suspend fun updateData(
        transform: suspend (Preferences) -> Preferences,
    ): Preferences = transform(state.value).also { state.value = it }
}

internal fun testSettingsRepository(): SettingsRepository =
    SettingsRepository(InMemoryPreferences(), com.weighttrack.security.SecretStore())

/**
 * The profile repository every data repository needs.
 *
 * No profile row is inserted on purpose: with none, the active profile falls back to the one the
 * migration creates, which is exactly what a repository sees on a database that has never had
 * the switcher opened.
 */
internal fun testProfileRepository(
    database: WeightTrackDatabase,
    settings: SettingsRepository = testSettingsRepository(),
): ProfileRepository = ProfileRepository(
    database.profileDao(),
    settings,
    DeletionRecorder(database.deletionDao(), database.syncDao()),
    database.weightEntryDao(),
)
