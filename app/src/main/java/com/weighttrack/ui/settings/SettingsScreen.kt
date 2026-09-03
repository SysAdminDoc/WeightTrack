package com.weighttrack.ui.settings

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weighttrack.R
import com.weighttrack.core.sync.SyncAddress
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.data.repo.Profile
import com.weighttrack.data.sync.LocalNetworkPermission
import com.weighttrack.data.sync.SyncMode
import com.weighttrack.notifications.ReminderReceiver
import com.weighttrack.ui.components.ResumeEffect

@Composable
fun SettingsScreen(
    settings: AppSettings,
    profiles: List<Profile>,
    activeProfileId: Long,
    entryCount: Int,
    healthConnectState: HealthConnectState,
    busy: Boolean,
    viewModel: SettingsViewModel,
    onOpenCrashLogs: () -> Unit,
    onOpenHealthRationale: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Someone can set a screen lock, or clear crash reports, and come straight back here
    // while this composition is still alive. The counter is what forces a recomposition:
    // refreshing a StateFlow with an equal value is conflated and changes nothing on screen.
    var resumeCount by remember { mutableIntStateOf(0) }
    ResumeEffect {
        resumeCount++
        viewModel.onScreenResumed()
    }
    var showReminderTime by remember { mutableStateOf(false) }
    var namingProfile by remember { mutableStateOf<Profile?>(null) }
    var addingProfile by remember { mutableStateOf(false) }
    val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: Profile(0, "", 0)
    val demographics by viewModel.demographics.collectAsStateWithLifecycle()
    val pickers = rememberSettingsPickers(viewModel)
    // Above the list, so scrolling past the section does not throw away half-typed text.
    val bodyFields = rememberBodyFields(demographics, settings.lengthUnit)

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            // The active profile's own times. Using the settings the app kept before profiles
            // existed would overwrite them with 07:30 every day on the first grant.
            viewModel.setReminder(
                enabled = true,
                hour = activeProfile.reminderHour,
                minute = activeProfile.reminderMinute,
                days = activeProfile.reminderDays,
            )
        } else {
            viewModel.notifyTestSent(false)
        }
    }

    // Android 17 asks before an app may reach another machine on the same network, which is what
    // a WebDAV server in the house is. Only offered when the address really is a local one.
    var localNetworkGranted by remember {
        mutableStateOf(LocalNetworkPermission.isGranted(context))
    }
    val localNetworkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { localNetworkGranted = LocalNetworkPermission.isGranted(context) }

    val healthConnectLauncher = rememberLauncherForActivityResult(
        viewModel.healthConnect.permissionContract(),
    ) { granted -> viewModel.onHealthConnectPermissionResult(granted) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (busy) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        unitsSection(settings, viewModel)
        appearanceSection(settings, viewModel)
        trendSmoothingSection(settings, viewModel)
        bodySection(settings, demographics, viewModel, bodyFields)
        peopleSection(
            profiles = profiles,
            activeProfileId = activeProfileId,
            activeProfile = activeProfile,
            viewModel = viewModel,
            onRename = { namingProfile = it },
            onAdd = { addingProfile = true },
        )
        foodLoggingSection(settings, viewModel)
        medicationSection(settings, viewModel)
        glanceSection(settings, viewModel)
        item {
            ReminderCard(
                profile = activeProfile,
                who = activeProfile.name.takeIf { profiles.size > 1 },
                onToggle = { enabled ->
                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !ReminderReceiver.hasNotificationPermission(context)
                    ) {
                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.setReminder(
                            enabled,
                            activeProfile.reminderHour,
                            activeProfile.reminderMinute,
                            activeProfile.reminderDays,
                        )
                    }
                },
                onEditTime = { showReminderTime = true },
                onToggleDay = { day ->
                    val days = if (day in activeProfile.reminderDays) {
                        activeProfile.reminderDays - day
                    } else {
                        activeProfile.reminderDays + day
                    }
                    viewModel.setReminder(
                        activeProfile.reminderEnabled,
                        activeProfile.reminderHour,
                        activeProfile.reminderMinute,
                        days,
                    )
                },
                onTest = { viewModel.notifyTestSent(ReminderReceiver.showTestNotification(context)) },
            )
        }
        weekStartSection(settings, viewModel)
        weeklySummarySection(settings, viewModel)
        item {
            val syncSettings by viewModel.syncSettings.collectAsStateWithLifecycle()
            val syncing by viewModel.syncing.collectAsStateWithLifecycle()
            val syncDevices by viewModel.syncDevices.collectAsStateWithLifecycle()
            SyncCard(
                settings = syncSettings,
                folderName = syncSettings.folderUri
                    ?.let { Uri.parse(it).lastPathSegment?.substringAfterLast(':') },
                syncing = syncing,
                onPickFolder = pickers.syncFolder,
                onUseWebDav = viewModel::useWebDav,
                onPickCertificate = pickers.syncCertificate,
                onForgetCertificate = viewModel::forgetSyncCertificate,
                onSyncNow = viewModel::syncNow,
                onTurnOff = viewModel::turnSyncOff,
                onBackgroundChange = viewModel::setSyncInBackground,
                needsLocalNetwork = syncSettings.mode == SyncMode.WEBDAV &&
                    !localNetworkGranted &&
                    SyncAddress.isOnLocalNetwork(syncSettings.webDavUrl.orEmpty()),
                onAllowLocalNetwork = { localNetworkLauncher.launch(LocalNetworkPermission.NAME) },
                devices = syncDevices,
                onDeviceRetiredChange = viewModel::setSyncDeviceRetired,
            )
        }
        item {
            HealthConnectCard(
                state = healthConnectState,
                lowestOfDay = settings.importLowestOfDay,
                onLowestOfDayChange = viewModel::setImportLowestOfDay,
                onDirectionChange = viewModel::setHealthDirection,
                onOriginExcludedChange = viewModel::setHealthOriginExcluded,
                onRequestPermissions = {
                    // Only what this phone can actually grant, or an older Health Connect
                    // leaves the offer on screen for ever.
                    healthConnectLauncher.launch(
                        viewModel.healthConnect.grantablePermissions(healthConnectState.direction),
                    )
                },
                onSync = viewModel::syncHealthConnect,
                onInstall = { openHealthConnectListing(context) },
                onExplain = onOpenHealthRationale,
            )
        }
        yourDataSection(entryCount, viewModel, pickers)
        privacySection(settings, resumeCount, viewModel)
        diagnosticsSection(viewModel, onOpenCrashLogs)
        aboutSection()
    }

    pickers.archiveDestination.value?.let { destination ->
        ArchivePasswordDialog(
            confirming = true,
            onDismiss = { pickers.archiveDestination.value = null },
            onConfirm = { password ->
                pickers.archiveDestination.value = null
                viewModel.exportArchive(destination, password)
            },
        )
    }

    pickers.archiveSource.value?.let { source ->
        ArchivePasswordDialog(
            confirming = false,
            onDismiss = { pickers.archiveSource.value = null },
            onConfirm = { password ->
                pickers.archiveSource.value = null
                viewModel.importArchive(source, password)
            },
        )
    }

    namingProfile?.let { profile ->
        NameDialog(
            title = stringResource(R.string.settings_rename_somebody, profile.name),
            initial = profile.name,
            onCancel = { namingProfile = null },
            onConfirm = { name ->
                viewModel.renameProfile(profile.id, name)
                namingProfile = null
            },
        )
    }

    if (addingProfile) {
        NameDialog(
            title = stringResource(R.string.settings_add_someone),
            initial = "",
            onCancel = { addingProfile = false },
            onConfirm = { name ->
                viewModel.addProfile(name)
                addingProfile = false
            },
        )
    }

    val pendingRestore by viewModel.pendingRestore.collectAsStateWithLifecycle()
    pendingRestore?.let { pending ->
        RestoreDialog(
            preview = pending.preview,
            onConfirm = viewModel::confirmRestore,
            onDismiss = viewModel::cancelRestore,
        )
    }

    if (showReminderTime) {
        ReminderTimeDialog(
            profile = activeProfile,
            onDismiss = { showReminderTime = false },
            onSet = { hour, minute ->
                viewModel.setReminder(
                    activeProfile.reminderEnabled,
                    hour,
                    minute,
                    activeProfile.reminderDays,
                )
                showReminderTime = false
            },
        )
    }
}
