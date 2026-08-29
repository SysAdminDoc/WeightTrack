package com.weighttrack.data.sync

import android.content.Context
import android.net.Uri
import com.weighttrack.core.model.ActivityLevel
import com.weighttrack.core.model.LengthUnit
import com.weighttrack.core.model.Sex
import com.weighttrack.core.model.ThemeMode
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.core.sync.SyncDocument
import com.weighttrack.core.sync.SyncMerge
import com.weighttrack.core.sync.SyncSettings as SyncedSettings
import com.weighttrack.data.prefs.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** How a sync turned out, in something that can be put on a settings row. */
sealed interface SyncResult {
    data class Done(val changes: SyncChanges, val devices: Int) : SyncResult

    /** Nothing to do, because sync has not been set up. */
    data object NotSetUp : SyncResult

    /** Something the person can fix. */
    data class Refused(val reason: String) : SyncResult

    /** Something that may well work later. */
    data class Unreachable(val reason: String) : SyncResult
}

/**
 * One round of syncing.
 *
 * Read everybody's file, merge them, write the answer back into the database, then write this
 * device's own file out again. That last step is what carries a change on to the third device:
 * every phone republishes the merged view, so a deletion made on one reaches the others even if
 * they never talk to each other directly.
 */
@Singleton
class SyncEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val strings: com.weighttrack.ui.AppStrings,
    private val preferences: SyncPreferences,
    private val store: SyncStore,
    private val settingsRepository: SettingsRepository,
) {
    // Two syncs at once, one from the button and one from the background job, would each write a
    // file the other had not read.
    private val running = Mutex()

    suspend fun syncNow(now: Long = System.currentTimeMillis()): SyncResult = running.withLock {
        val settings = preferences.current()
        if (!settings.isOn || !settings.isReady) return@withLock SyncResult.NotSetUp
        val target = targetFor(settings) ?: return@withLock SyncResult.NotSetUp
        val deviceId = preferences.deviceId()

        val names = when (val listed = target.list()) {
            is SyncOutcome.Ok -> listed.value
            is SyncOutcome.Refused -> return@withLock finish(now, SyncResult.Refused(listed.reason))
            is SyncOutcome.Unreachable ->
                return@withLock finish(now, SyncResult.Unreachable(listed.reason))
        }

        // Other devices only. This one's own view is taken from the database, which is newer than
        // anything it wrote to a file.
        val peers = names.mapNotNull { name ->
            SyncDocument.deviceIdOf(name)?.takeIf { it != deviceId }?.let { name }
        }

        val documents = mutableListOf(snapshot(deviceId, now))
        for (name in peers) {
            when (val read = target.read(name)) {
                is SyncOutcome.Ok -> read.value?.let { text ->
                    // A file that will not parse is skipped rather than fatal. Something being
                    // written at this moment by a folder sync tool is a normal thing to meet.
                    SyncDocument.decode(text)?.let { documents += it }
                }
                is SyncOutcome.Refused -> return@withLock finish(now, SyncResult.Refused(read.reason))
                is SyncOutcome.Unreachable ->
                    return@withLock finish(now, SyncResult.Unreachable(read.reason))
            }
        }

        val merged = SyncMerge.merge(documents, deviceId, now)
        val changes = store.apply(merged, now)
        applySettings(merged.settings)

        // Written from the database afterwards, not from the merge. Applying can refuse things,
        // and the file has to say what this device actually holds.
        val published = snapshot(deviceId, now).copy(deletions = merged.deletions)
        when (val written = target.write(SyncDocument.fileName(deviceId), SyncDocument.encode(published))) {
            is SyncOutcome.Ok -> Unit
            is SyncOutcome.Refused -> return@withLock finish(now, SyncResult.Refused(written.reason))
            is SyncOutcome.Unreachable ->
                return@withLock finish(now, SyncResult.Unreachable(written.reason))
        }

        finish(now, SyncResult.Done(changes, devices = documents.size))
    }

    private suspend fun snapshot(deviceId: String, now: Long): SyncDocument {
        val local = settingsRepository.settings.first()
        return store.snapshot(deviceId, now).copy(
            settings = SyncedSettings(
                weightUnit = local.weightUnit.name,
                lengthUnit = local.lengthUnit.name,
                themeMode = local.themeMode.name,
                heightMm = local.profile.heightMm,
                sex = local.profile.sex.name,
                birthYear = local.profile.birthYear,
                activityLevel = local.profile.activityLevel.name,
                trendWindowDays = local.trendWindowDays,
                milestoneStepGrams = local.milestoneStepGrams,
                updatedAtUtcMillis = local.updatedAtUtcMillis,
            ),
        )
    }

    private suspend fun applySettings(remote: SyncedSettings?) {
        if (remote == null) return
        val local = settingsRepository.settings.first()
        if (remote.updatedAtUtcMillis <= local.updatedAtUtcMillis) return
        // Anything unreadable keeps what is already here rather than falling back to a default,
        // which would quietly reset somebody's units because another device sent a newer version
        // of the app's spelling of them.
        settingsRepository.applySynced(
            weightUnit = decode(remote.weightUnit, WeightUnit.entries, local.weightUnit),
            lengthUnit = decode(remote.lengthUnit, LengthUnit.entries, local.lengthUnit),
            themeMode = decode(remote.themeMode, ThemeMode.entries, local.themeMode),
            heightMm = remote.heightMm,
            sex = decode(remote.sex, Sex.entries, local.profile.sex),
            birthYear = remote.birthYear,
            activityLevel = decode(remote.activityLevel, ActivityLevel.entries, local.profile.activityLevel),
            trendWindowDays = remote.trendWindowDays,
            milestoneStepGrams = remote.milestoneStepGrams,
            updatedAtUtcMillis = remote.updatedAtUtcMillis,
        )
    }

    private fun <T : Enum<T>> decode(name: String, values: List<T>, fallback: T): T =
        values.firstOrNull { it.name == name } ?: fallback

    private suspend fun finish(now: Long, result: SyncResult): SyncResult {
        val message = when (result) {
            is SyncResult.Done -> when {
                result.changes.orphaned > 0 ->
                    strings[com.weighttrack.R.string.sync_orphaned, result.changes.orphaned]
                result.changes.touched == 0 -> strings[com.weighttrack.R.string.sync_up_to_date]
                else -> strings[
                    com.weighttrack.R.string.sync_changed,
                    result.changes.touched,
                    result.devices,
                ]
            }
            is SyncResult.Refused -> result.reason
            is SyncResult.Unreachable -> result.reason
            SyncResult.NotSetUp -> null
        }
        // Only a sync that got all the way through counts as a sync. Recording the time on a
        // failure would make the settings row claim it worked.
        val at = if (result is SyncResult.Done) now else preferences.current().lastSyncAtUtcMillis
        preferences.recordSync(at, message)
        return result
    }

    private fun targetFor(settings: SyncSettings): SyncTarget? = when (settings.mode) {
        SyncMode.OFF -> null
        SyncMode.FOLDER -> settings.folderUri?.let {
            FolderSyncTarget(context, Uri.parse(it))
        }
        SyncMode.WEBDAV -> {
            val url = settings.webDavUrl
            val user = settings.webDavUser
            if (url.isNullOrBlank() || user.isNullOrBlank()) {
                null
            } else {
                WebDavSyncTarget(url, user, settings.webDavPassword.orEmpty()) { id, arguments ->
                    strings.get(id, *arguments)
                }
            }
        }
    }
}
