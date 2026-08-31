package com.weighttrack.ui.settings

import com.weighttrack.R
import com.weighttrack.core.model.HealthDirection
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.ProfileRepository
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.health.HealthConnectSync
import com.weighttrack.sync.SyncScheduler
import com.weighttrack.ui.AppStrings
import com.weighttrack.widget.SurfaceUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The Health Connect half of the settings screen, off the view model. */
internal class HealthConnectSettingsController(
    private val scope: CoroutineScope,
    private val healthConnect: HealthConnectSync,
    private val settingsRepository: SettingsRepository,
    private val profileRepository: ProfileRepository,
    private val weightRepository: WeightRepository,
    private val syncScheduler: SyncScheduler,
    private val surfaces: SurfaceUpdater,
    private val strings: AppStrings,
    private val onMessage: (String?) -> Unit,
) {

    private val _state = MutableStateFlow(HealthConnectState())
    val state: StateFlow<HealthConnectState> = _state.asStateFlow()

    fun refresh() {
        scope.launch {
            val granted = healthConnect.hasPermissions()
            val stored = settingsRepository.settings.first()
            _state.value = HealthConnectState(
                availability = healthConnect.availability(),
                granted = granted,
                grantedEverything = healthConnect.hasEverything(),
                // A profile holds the claim from the moment somebody connects, so a claim with
                // no permission behind it is access that was taken away rather than never given.
                accessWithdrawn = !granted && profileRepository.healthConnectId() != null,
                direction = stored.healthDirection,
                origins = weightRepository.origins().map { origin ->
                    HealthOrigin(
                        packageName = origin.packageName,
                        device = origin.device,
                        excluded = origin.packageName in stored.excludedHealthOrigins,
                    )
                },
            )
        }
    }

    fun setDirection(direction: HealthDirection) = scope.launch {
        settingsRepository.setHealthDirection(direction)
        refresh()
    }

    fun setOriginExcluded(packageName: String, excluded: Boolean) = scope.launch {
        settingsRepository.setHealthOriginExcluded(packageName, excluded)
        refresh()
    }

    fun syncNow() {
        scope.launch {
            _state.value = _state.value.copy(syncing = true)
            // Body fat travels with the weigh-in it belongs to now, so there is nothing to
            // send separately afterwards.
            val result = healthConnect.sync()
            _state.value = _state.value.copy(syncing = false)
            surfaces.refresh()
            onMessage(
                result.fold(
                    onSuccess = { summary ->
                        val message = strings[
                            R.string.settings_health_connect_brought_in_sent_out,
                            summary.imported,
                            summary.exported,
                        ]
                        // A sync that removed readings deleted elsewhere said nothing about them,
                        // so the count on screen looked like nothing had happened.
                        if (summary.removed > 0) {
                            message + strings[R.string.settings_health_connect_removed, summary.removed]
                        } else {
                            message
                        }
                    },
                    onFailure = {
                        strings[R.string.settings_health_connect_sync_failed, it.message.orEmpty()]
                    },
                ),
            )
            refresh()
        }
    }

    fun onPermissionResult(granted: Set<String>) {
        // Connecting is what starts the background job for somebody who syncs a scale through
        // Health Connect and keeps no folder.
        scope.launch { runCatching { syncScheduler.reschedule() } }
        // Weight sync only needs the core set. Treating a declined optional read as a
        // refused connection would report a working sync as unauthorised.
        val way = _state.value.direction
        val allowed = granted.containsAll(HealthConnectSync.corePermissionsFor(way))
        _state.value = _state.value.copy(
            granted = allowed,
            grantedEverything = granted.containsAll(healthConnect.grantablePermissions(way)),
        )
        if (allowed) {
            // Whose Health Connect this is, written down before a single record moves. Deciding
            // it at the first sync instead would pin it on whoever happened to be active when a
            // background job ran, which need not be the person who granted the access.
            scope.launch {
                runCatching { healthConnect.claimProfile() }
                syncNow()
            }
        } else {
            onMessage(strings[R.string.settings_health_connect_access_was_not_granted])
        }
    }
}
