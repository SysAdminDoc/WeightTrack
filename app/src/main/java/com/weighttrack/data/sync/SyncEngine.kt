package com.weighttrack.data.sync

import android.content.Context
import android.net.Uri
import com.weighttrack.core.math.SmoothingMode
import com.weighttrack.core.model.LengthUnit
import com.weighttrack.core.model.ThemeMode
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.core.sync.SyncAddress
import com.weighttrack.core.sync.SyncDocument
import com.weighttrack.core.sync.SyncMerge
import com.weighttrack.core.sync.SyncSettings as SyncedSettings
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.diagnostics.LogArea
import com.weighttrack.diagnostics.LogEvent
import com.weighttrack.diagnostics.RuntimeLog
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
    private val runtimeLog: RuntimeLog,
    private val targets: SyncTargets,
) {
    // Two syncs at once, one from the button and one from the background job, would each write a
    // file the other had not read.
    private val running = Mutex()

    suspend fun syncNow(now: Long = System.currentTimeMillis()): SyncResult = running.withLock {
        val settings = preferences.current()
        if (!settings.isOn || !settings.isReady) return@withLock SyncResult.NotSetUp
        val target = targets.forSettings(settings) ?: return@withLock SyncResult.NotSetUp
        val deviceId = preferences.deviceId()
        // Before anything else: a settings write an earlier run did not finish.
        replayPendingSettings()

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
        // Anything a peer sent that this phone would not hold. Reported once the run is over
        // rather than stopping it: one bad file must not stop every other device syncing.
        val refused = mutableListOf<String>()
        for (name in peers) {
            when (val read = target.read(name)) {
                is SyncOutcome.Ok -> read.value?.let { text ->
                    // A file that will not parse is skipped rather than fatal. Something being
                    // written at this moment by a folder sync tool is a normal thing to meet.
                    SyncDocument.decode(text)?.let { document ->
                        // And one that parses can still be absurd: a quarter of a million
                        // readings, or a note the length of a book. Skipped the same way, with
                        // the file said out loud, and everything already read left alone.
                        val problem = com.weighttrack.core.sync.SyncBudget.problemWith(document)
                        if (problem == null) {
                            documents += document
                        } else {
                            runtimeLog.write(LogArea.SYNC, LogEvent.SYNC_DOCUMENT_REFUSED)
                            refused += strings[
                                com.weighttrack.R.string.sync_document_refused,
                                name,
                                problem,
                            ]
                        }
                    }
                }
                // One peer's file, not the sync. A file too large to read, or one this app did
                // not write, is skipped and named: aborting here meant a single stray file in a
                // shared folder killed the whole household's sync for good, because this phone
                // then never merged anybody else's data and never republished its own either,
                // and the file would be the same size in an hour.
                is SyncOutcome.Refused -> {
                    runtimeLog.write(LogArea.SYNC, LogEvent.SYNC_DOCUMENT_REFUSED)
                    refused += read.reason
                }
                // A peer that cannot be reached is different: the folder or the server is having
                // a problem, not the file, and there is nothing to be gained by walking the rest.
                is SyncOutcome.Unreachable ->
                    return@withLock finish(now, SyncResult.Unreachable(read.reason))
            }
        }

        val merged = SyncMerge.merge(documents, deviceId, now)
        // One commit for the rows, and nothing published until it has landed. A half-applied
        // merge followed by this device republishing its own view would hand that half back to
        // everybody else as though it were the whole answer.
        val changes = runCatching {
            // The merge has seen every device's file, so its list of deletions is the whole
            // truth and replaces what this phone was holding. That is what actually lets a
            // tombstone everybody has acknowledged be forgotten.
            store.apply(merged, now, replaceDeletions = true)
        }.getOrElse { failure ->
            runtimeLog.write(LogArea.SYNC, LogEvent.SYNC_APPLY_FAILED, cause = failure)
            return@withLock finish(now, SyncResult.Unreachable(strings[com.weighttrack.R.string.sync_apply_failed]))
        }
        applySettingsDurably(merged.settings)

        // Written from the database afterwards, not from the merge. Applying can refuse things,
        // and the file has to say what this device actually holds.
        val published = snapshot(deviceId, now).copy(deletions = merged.deletions)
        when (val written = target.write(SyncDocument.fileName(deviceId), SyncDocument.encode(published))) {
            is SyncOutcome.Ok -> Unit
            is SyncOutcome.Refused -> return@withLock finish(now, SyncResult.Refused(written.reason))
            is SyncOutcome.Unreachable ->
                return@withLock finish(now, SyncResult.Unreachable(written.reason))
        }

        finish(now, SyncResult.Done(changes, devices = documents.size), refused)
    }

    private suspend fun snapshot(deviceId: String, now: Long): SyncDocument {
        val local = settingsRepository.settings.first()
        return store.snapshot(deviceId, now).copy(
            settings = SyncedSettings(
                weightUnit = local.weightUnit.name,
                lengthUnit = local.lengthUnit.name,
                themeMode = local.themeMode.name,
                // Height, sex, year of birth and activity are deliberately absent. They belong to
                // a profile and travel on its row; the app-level copy they used to be read from
                // has not been written since profiles arrived.
                trendWindowDays = local.trendWindowDays,
                milestoneStepGrams = local.milestoneStepGrams,
                smoothingMode = local.smoothingMode.name,
                updatedAtUtcMillis = local.updatedAtUtcMillis,
            ),
        )
    }

    /**
     * Writes the merged settings down, and keeps a note until they are actually written.
     *
     * The rows live in the database and the settings live in a preferences file, and no
     * transaction covers both. The note is what closes that gap: written before the attempt,
     * torn up only once the stored settings are at least as new as the ones that arrived. A run
     * that dies in between leaves it, and the next sync replays it. Replaying is safe because
     * applying the same settings twice writes the same values, and the merge itself is
     * idempotent, so nothing needs the document a second time.
     */
    private suspend fun applySettingsDurably(remote: SyncedSettings?) {
        if (remote == null) return
        preferences.setPendingSettings(
            SyncDocument.json.encodeToString(SyncedSettings.serializer(), remote),
        )
        // A failure here must not undo the rows that are already committed, and must not stop
        // this device publishing what it now holds. The note is what makes that safe.
        runCatching { applySettings(remote) }
        if (settingsRepository.settings.first().updatedAtUtcMillis >= remote.updatedAtUtcMillis) {
            preferences.clearPendingSettings()
        }
    }

    /** Finishes a settings write that an earlier run started and did not get to the end of. */
    private suspend fun replayPendingSettings() {
        val note = preferences.pendingSettings() ?: return
        val remote = runCatching {
            SyncDocument.json.decodeFromString(SyncedSettings.serializer(), note)
        }.getOrNull()
        // A note nothing can read is worse than no note: it would be retried for ever.
        if (remote == null) {
            preferences.clearPendingSettings()
            return
        }
        applySettingsDurably(remote)
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
            trendWindowDays = remote.trendWindowDays,
            milestoneStepGrams = remote.milestoneStepGrams,
            smoothingMode = decode(remote.smoothingMode, SmoothingMode.entries, local.smoothingMode),
            updatedAtUtcMillis = remote.updatedAtUtcMillis,
        )
    }

    private fun <T : Enum<T>> decode(name: String, values: List<T>, fallback: T): T =
        values.firstOrNull { it.name == name } ?: fallback

    private suspend fun finish(
        now: Long,
        incoming: SyncResult,
        /** Peers whose file this phone would not hold, named so somebody can go and look. */
        refused: List<String> = emptyList(),
    ): SyncResult {
        val result = explainLocalNetwork(incoming)
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
        }?.let { said ->
            // Said on the settings row rather than only in the log. A device quietly left out of
            // every sync is exactly the failure somebody needs to be told about.
            if (refused.isEmpty()) said else said + " " + refused.first()
        }
        when (result) {
            is SyncResult.Done -> runtimeLog.write(
                LogArea.SYNC,
                LogEvent.SYNC_FINISHED,
                code = result.changes.touched,
            )
            is SyncResult.Refused -> runtimeLog.write(LogArea.SYNC, LogEvent.SYNC_REFUSED)
            is SyncResult.Unreachable -> runtimeLog.write(LogArea.SYNC, LogEvent.SYNC_UNREACHABLE)
            SyncResult.NotSetUp -> Unit
        }
        // Only a sync that got all the way through counts as a sync. Recording the time on a
        // failure would make the settings row claim it worked.
        val at = if (result is SyncResult.Done) now else preferences.current().lastSyncAtUtcMillis
        preferences.recordSync(at, message)
        return result
    }

    /**
     * Says what a failure to reach a server in the house most likely was.
     *
     * On Android 17 an app that has not been asked cannot open the socket at all, and what comes
     * back is a timeout indistinguishable from the server being switched off.
     *
     * Deliberately an explanation of a failure rather than a check before one. Guessing "this
     * address is on your network" from a name is a guess: a machine reached over a VPN or through
     * a corporate DNS suffix looks identical. Refusing up front would have stopped a sync that
     * works, permanently and with no retry, on nothing more than a hostname with no dot in it.
     * Explaining afterwards costs one attempt that was going to fail anyway, and it keeps the
     * result [SyncResult.Unreachable], so the hourly job keeps trying and heals itself the moment
     * the permission is granted.
     */
    private suspend fun explainLocalNetwork(result: SyncResult): SyncResult {
        val settings = preferences.current()
        val explained = explainLocalNetwork(
            result = result,
            mode = settings.mode,
            url = settings.webDavUrl,
            permissionGranted = LocalNetworkPermission.isGranted(context),
            message = strings[com.weighttrack.R.string.sync_needs_local_network],
        )
        if (explained !== result) runtimeLog.write(LogArea.SYNC, LogEvent.LOCAL_NETWORK_NOT_ALLOWED)
        return explained
    }

}

/**
 * Builds the place a sync reads from and writes to.
 *
 * Its own class so that a test can hand the engine somewhere to write. A folder target needs a
 * document tree and a WebDAV one needs a server, and neither exists in a unit test, so without a
 * seam here nothing the engine does with what it reads could be covered at all.
 */
open class SyncTargets @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val strings: com.weighttrack.ui.AppStrings,
    private val runtimeLog: RuntimeLog,
) {
    open fun forSettings(settings: SyncSettings): SyncTarget? = when (settings.mode) {
        SyncMode.OFF -> null
        SyncMode.FOLDER -> settings.folderUri?.let {
            FolderSyncTarget(context, Uri.parse(it))
        }
        SyncMode.WEBDAV -> {
            val url = settings.webDavUrl
            val user = settings.webDavUser
            if (url.isNullOrBlank() || user.isNullOrBlank()) {
                null
            } else if (!com.weighttrack.core.sync.SyncAddress.isUsable(url)) {
                // Stored before the address was checked at all, so an http:// server from an
                // older install is still in there. It cannot work and it never could, and
                // failing silently every hour says nothing about why.
                runtimeLog.write(LogArea.SYNC, LogEvent.SYNC_ADDRESS_REFUSED)
                null
            } else {
                WebDavSyncTarget(
                    baseUrl = url,
                    username = user,
                    password = settings.webDavPassword.orEmpty(),
                    runtimeLog = runtimeLog,
                    // Only when the person picked one. A stored value that has stopped being a
                    // certificate leaves the phone's own trust store deciding, which is not
                    // what the settings screen is saying, so it is recorded.
                    pinnedCertificate = settings.webDavCertificate?.let { stored ->
                        val bytes = runCatching {
                            android.util.Base64.decode(stored, android.util.Base64.NO_WRAP)
                        }.getOrNull()
                        val certificate = bytes?.let { PinnedTrust.certificateFrom(it) }
                        if (certificate == null) {
                            runtimeLog.write(LogArea.SYNC, LogEvent.SYNC_CERTIFICATE_UNREADABLE)
                        }
                        certificate
                    },
                ) { id, arguments -> strings.get(id, *arguments) }
            }
        }
    }
}

/**
 * Says what a failure to reach a server in the house most likely was.
 *
 * On Android 17 an app that has not been asked cannot open the socket at all, and what comes back
 * is a timeout indistinguishable from the server being switched off.
 *
 * Deliberately an explanation of a failure rather than a check before one. Guessing "this address
 * is on your network" from a name is a guess: a machine reached over a VPN, or through a corporate
 * DNS suffix, looks identical to one in the spare room. Refusing up front would have killed a sync
 * that works, permanently and with no retry, on nothing more than a hostname with no dot in it.
 * Explaining afterwards costs one attempt that was going to fail anyway, and the result stays
 * [SyncResult.Unreachable], so the hourly job keeps trying and heals itself the moment the
 * permission is granted.
 */
internal fun explainLocalNetwork(
    result: SyncResult,
    mode: SyncMode,
    url: String?,
    permissionGranted: Boolean,
    message: String,
): SyncResult {
    if (result !is SyncResult.Unreachable) return result
    if (mode != SyncMode.WEBDAV || url == null || permissionGranted) return result
    if (!SyncAddress.isOnLocalNetwork(url)) return result
    return SyncResult.Unreachable(message)
}
