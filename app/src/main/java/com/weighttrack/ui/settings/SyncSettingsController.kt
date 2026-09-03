package com.weighttrack.ui.settings

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.weighttrack.R
import com.weighttrack.core.sync.AddressProblem
import com.weighttrack.core.sync.SyncAddress
import com.weighttrack.data.sync.PinnedTrust
import com.weighttrack.data.sync.SyncEngine
import com.weighttrack.data.sync.SyncPreferences
import com.weighttrack.data.sync.SyncResult
import com.weighttrack.data.sync.SyncSettings
import com.weighttrack.sync.SyncScheduler
import com.weighttrack.ui.AppStrings
import com.weighttrack.ui.format.DateFormatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * One of the devices sharing this person's sync folder, as the settings screen shows it.
 */
data class SyncDevice(
    val deviceId: String,
    val lastSeenAtUtcMillis: Long,
    val retired: Boolean,
    val isThisDevice: Boolean,
)

/**
 * Everything the settings screen does to sync, off the view model.
 *
 * Built by [SettingsViewModel] with its own scope rather than injected, because the flow below is
 * kept warm for as long as the screen that reads it and no longer.
 */
internal class SyncSettingsController(
    private val scope: CoroutineScope,
    private val preferences: SyncPreferences,
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
    private val peers: com.weighttrack.data.db.SyncPeerDao,
    private val strings: AppStrings,
    private val context: Context,
    /** Null clears whatever is on screen, which is what a sync with nothing to report does. */
    private val onMessage: (String?) -> Unit,
) {

    val settings: StateFlow<SyncSettings> = preferences.settings.stateIn(
        scope,
        SharingStarted.WhileSubscribed(5_000),
        SyncSettings(),
    )

    /**
     * The devices this one syncs with.
     *
     * On screen because retiring one is the only thing that lets the others stop waiting for it
     * before they forget a deletion, and nobody can retire a device they cannot see.
     */
    val devices: StateFlow<List<SyncDevice>> = peers.observeAll()
        .map { rows ->
            val self = preferences.deviceId()
            rows.map {
                SyncDevice(
                    deviceId = it.deviceId,
                    lastSeenAtUtcMillis = it.lastSeenAtUtcMillis,
                    retired = it.retiredAtUtcMillis > 0,
                    isThisDevice = it.deviceId == self,
                )
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Says a device is gone, or takes it back.
     *
     * Immediate either way, and reversible, so nothing here needs a dialog asking whether
     * somebody meant it. Retiring never touches that device's readings.
     */
    fun setDeviceRetired(deviceId: String, retired: Boolean) {
        scope.launch {
            val now = System.currentTimeMillis()
            peers.setRetired(deviceId, retiredAt = if (retired) now else 0, decidedAt = now)
            onMessage(
                strings[
                    if (retired) R.string.sync_device_retired else R.string.sync_device_restored,
                ],
            )
        }
    }

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /**
     * Takes a folder the person picked and holds on to the right to read it.
     *
     * Without taking the permission the address stops working the next time the app starts, and
     * background syncing would fail forever with nothing on screen to explain why.
     */
    fun useFolder(uri: Uri, holdOnTo: (Uri) -> Unit) {
        scope.launch {
            holdOnTo(uri)
            preferences.useFolder(uri.toString())
            scheduler.reschedule()
            syncNow()
        }
    }

    fun useWebDav(url: String, user: String, password: String) {
        scope.launch {
            // Read before anything is stored. A plain http address used to be accepted and then
            // fail an hour later in a background job nobody was watching, with a message that
            // said nothing about why, and the app blocks cleartext at the platform level so it
            // was never going to work.
            SyncAddress.problemWith(url)?.let { problem ->
                onMessage(strings[addressMessage(problem)])
                return@launch
            }
            // Nothing is stored when the phone will not encrypt the password, so sync stays off
            // and is not scheduled. Saying so is the whole point: the alternative was writing the
            // password down in the clear and letting somebody believe otherwise.
            if (!preferences.useWebDav(url, user, password)) {
                onMessage(strings[R.string.secret_not_stored_password])
                return@launch
            }
            scheduler.reschedule()
            syncNow()
        }
    }

    /**
     * Remembers the certificate a server signed itself with, having read it first.
     *
     * Read here rather than at connect time, so somebody who picks the wrong file is told now
     * instead of an hour later by a background job.
     */
    fun useCertificate(uri: Uri) {
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream -> stream.readBytes() }
                }.getOrNull()
            }
            val certificate = bytes?.let { raw -> PinnedTrust.certificateFrom(raw) }
            if (bytes == null || certificate == null) {
                onMessage(strings[R.string.sync_certificate_unreadable])
                return@launch
            }
            // Checked now rather than an hour later. A pin whose certificate has expired is
            // refused at the socket, and being told that here is the difference between renewing
            // it and wondering why sync stopped.
            if (runCatching { certificate.checkValidity() }.isFailure) {
                onMessage(
                    strings[
                        R.string.sync_certificate_expired,
                        DateFormatters.fullDate(
                            certificate.notAfter.toInstant()
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate(),
                        ),
                    ],
                )
                return@launch
            }
            preferences.setWebDavCertificate(
                Base64.encodeToString(certificate.encoded, Base64.NO_WRAP),
            )
            onMessage(
                strings[R.string.sync_certificate_trusted, certificate.subjectX500Principal.name],
            )
        }
    }

    fun forgetCertificate() {
        scope.launch { preferences.setWebDavCertificate(null) }
    }

    fun turnOff() {
        scope.launch {
            preferences.turnOff()
            scheduler.reschedule()
            onMessage(strings[R.string.settings_sync_turned_off_nothing_was_deleted])
        }
    }

    fun setInBackground(enabled: Boolean) {
        scope.launch {
            preferences.setBackground(enabled)
            scheduler.reschedule()
        }
    }

    fun syncNow() {
        if (_syncing.value) return
        scope.launch {
            _syncing.value = true
            try {
                when (val result = engine.syncNow()) {
                    is SyncResult.Done -> onMessage(preferences.current().lastSyncMessage)
                    is SyncResult.Refused -> onMessage(result.reason)
                    is SyncResult.Unreachable -> onMessage(result.reason)
                    SyncResult.NotSetUp ->
                        onMessage(strings[R.string.settings_pick_somewhere_to_sync_to_first])
                }
            } catch (error: Exception) {
                // Something in a file from another device, or a row that will not go where it
                // is told. Whatever it is, it must not take the app down: the button is a thing
                // somebody pressed, not a promise that every other device is well behaved.
                onMessage(strings[R.string.settings_sync_could_not_finish_nothing_was])
            } finally {
                _syncing.value = false
            }
        }
    }

    private fun addressMessage(problem: AddressProblem): Int = when (problem) {
        AddressProblem.EMPTY -> R.string.sync_address_empty
        AddressProblem.UNREADABLE -> R.string.sync_address_unreadable
        AddressProblem.NOT_ENCRYPTED -> R.string.sync_address_not_encrypted
        AddressProblem.NOT_WEB -> R.string.sync_address_not_web
        AddressProblem.CREDENTIALS_IN_ADDRESS -> R.string.sync_address_credentials
    }
}
