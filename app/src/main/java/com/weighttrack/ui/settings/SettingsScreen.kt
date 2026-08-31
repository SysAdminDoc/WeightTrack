package com.weighttrack.ui.settings

import com.weighttrack.core.format.LocaleNumbers
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import com.weighttrack.BuildConfig
import com.weighttrack.R
import com.weighttrack.core.math.TrendEngine
import com.weighttrack.core.math.UnitConverter
import com.weighttrack.core.model.ActivityLevel
import com.weighttrack.core.model.HealthDirection
import com.weighttrack.core.model.LengthUnit
import com.weighttrack.core.model.Sex
import com.weighttrack.core.model.ThemeMode
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.data.io.BackupCodec
import androidx.biometric.BiometricManager
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.data.repo.Profile
import com.weighttrack.core.sync.SyncAddress
import com.weighttrack.data.sync.SyncMode
import com.weighttrack.ui.format.OriginNames
import com.weighttrack.ui.format.DateFormatters
import com.weighttrack.health.HealthConnectAvailability
import com.weighttrack.health.HealthConnectSync
import com.weighttrack.security.AppLockAvailability
import com.weighttrack.security.AppLockSupport
import com.weighttrack.notifications.ReminderReceiver
import com.weighttrack.data.io.BackupPreview
import com.weighttrack.ui.components.LabelledValue
import com.weighttrack.ui.components.ResumeEffect
import com.weighttrack.ui.components.SectionHeading
import com.weighttrack.ui.components.SegmentButton
import com.weighttrack.core.format.LengthFormatter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
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
    val locale = LocalConfiguration.current.locales[0]
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
    val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: Profile(0, "", 0)
    var addingProfile by remember { mutableStateOf(false) }
    // The body of the person on screen, not of the phone. Reading these off the app settings is
    // what let a household of two work every figure out from one person's height.
    val demographics by viewModel.demographics.collectAsStateWithLifecycle()
    var heightText by remember(demographics.heightMm, settings.lengthUnit) {
        mutableStateOf(
            demographics.heightMm.takeIf { it > 0 }
                ?.let { LengthFormatter.value(it, settings.lengthUnit, decimals = 1) }
                .orEmpty(),
        )
    }
    var birthYearText by remember(demographics.birthYear) {
        mutableStateOf(demographics.birthYear.takeIf { it > 0 }?.toString().orEmpty())
    }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let(viewModel::exportCsv) }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportJson) }

    val exportMeasurementsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> uri?.let(viewModel::exportMeasurements) }

    // Some file pickers hand back CSV under a generic MIME type, so the filter stays wide and
    // the parser decides whether the file makes sense.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importCsv) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::previewRestore) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            // The active profile's own times. Using the settings the app kept before profiles
            // existed would overwrite them with 07:30 every day on the first grant.
            val target = profiles.firstOrNull { it.id == activeProfileId }
            viewModel.setReminder(
                enabled = true,
                hour = target?.reminderHour ?: 7,
                minute = target?.reminderMinute ?: 30,
                days = target?.reminderDays ?: DayOfWeek.entries.toSet(),
            )
        } else {
            viewModel.notifyTestSent(false)
        }
    }

    val autoBackupFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            viewModel.useAutoBackupFolder(uri) { picked ->
                context.contentResolver.takePersistableUriPermission(
                    picked,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }

    val syncFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            viewModel.useSyncFolder(uri) { picked ->
                // Held on to, or the address stops working after a restart and background syncing
                // fails forever with nothing on screen to say why.
                context.contentResolver.takePersistableUriPermission(
                    picked,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }

    val healthConnectLauncher = rememberLauncherForActivityResult(
        viewModel.healthConnect.permissionContract(),
    ) { granted -> viewModel.onHealthConnectPermissionResult(granted) }

    // Android 17 asks before an app may reach another machine on the same network, which is what
    // a WebDAV server in the house is. Only offered when the address really is a local one.
    var localNetworkGranted by remember {
        mutableStateOf(com.weighttrack.data.sync.LocalNetworkPermission.isGranted(context))
    }
    val localNetworkLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) {
        localNetworkGranted = com.weighttrack.data.sync.LocalNetworkPermission.isGranted(context)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (busy) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }

        item {
            SettingsSection {
                SectionHeading(stringResource(R.string.settings_units))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.onboarding_weight), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
                ChipRow(
                    options = WeightUnit.entries.map { it to weightUnitLabel(it) },
                    selected = settings.weightUnit,
                    onSelect = viewModel::setWeightUnit,
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.64f))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.home_measurements), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
                ChipRow(
                    options = LengthUnit.entries.map { it to lengthUnitLabel(it) },
                    selected = settings.lengthUnit,
                    onSelect = viewModel::setLengthUnit,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_weights_are_stored_in_grams_so),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsSection {
                SectionHeading(stringResource(R.string.settings_appearance))
                Spacer(Modifier.height(8.dp))
                ChipRow(
                    options = ThemeMode.entries.map { it to themeLabel(it) },
                    selected = settings.themeMode,
                    onSelect = viewModel::setThemeMode,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Spacer(Modifier.height(4.dp))
                    ToggleRow(
                        label = stringResource(R.string.settings_use_wallpaper_colours),
                        checked = settings.dynamicColor,
                        onCheckedChange = viewModel::setDynamicColor,
                    )
                }
            }
        }

        item {
            SettingsSection {
                SectionHeading(stringResource(R.string.settings_trend_smoothing))
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_a_shorter_window_follows_the_scale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_smoothing_window),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.settings_days, settings.trendWindowDays), style = MaterialTheme.typography.titleMedium)
                val sliderColors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                )
                Slider(
                    value = settings.trendWindowDays.toFloat(),
                    onValueChange = { viewModel.setTrendWindow(it.toInt()) },
                    valueRange = TrendEngine.MIN_WINDOW_DAYS.toFloat()..TrendEngine.MAX_WINDOW_DAYS.toFloat(),
                    steps = TrendEngine.MAX_WINDOW_DAYS - TrendEngine.MIN_WINDOW_DAYS - 1,
                    colors = sliderColors,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = TrendEngine.MIN_WINDOW_DAYS.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = TrendEngine.MAX_WINDOW_DAYS.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SettingsSection {
                SectionHeading(stringResource(R.string.settings_profile))
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_only_used_to_work_out_bmi),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = heightText,
                    onValueChange = { text ->
                        heightText = text
                        LocaleNumbers.decimal(text)?.takeIf { it > 0 }?.let {
                            viewModel.setHeightMm(UnitConverter.displayToMm(it, settings.lengthUnit))
                        }
                    },
                    label = { Text(stringResource(R.string.onboarding_height, LengthFormatter.unitLabel(settings.lengthUnit))) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = birthYearText,
                    onValueChange = { text ->
                        birthYearText = text.filter { it.isDigit() }.take(4)
                        LocaleNumbers.integer(birthYearText)
                            ?.takeIf { it in 1900..LocalDate.now().year }
                            ?.let(viewModel::setBirthYear)
                    },
                    label = { Text(stringResource(R.string.onboarding_year_of_birth)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.onboarding_sex), style = MaterialTheme.typography.bodySmall)
                ChipRow(
                    options = Sex.entries.map { it to sexLabel(it) },
                    selected = demographics.sex,
                    onSelect = viewModel::setSex,
                )
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.settings_activity_level), style = MaterialTheme.typography.bodySmall)
                ChipRow(
                    options = ActivityLevel.entries.map { it to activityLabel(it) },
                    selected = demographics.activityLevel,
                    onSelect = viewModel::setActivityLevel,
                )
            }
        }

        item {
            SettingsSection {
                SectionHeading(stringResource(R.string.settings_who_this_is_for))
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_a_household_shares_a_scale_more),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                profiles.forEach { profile ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { viewModel.switchProfile(profile.id) }) {
                            Text(
                                text = if (profile.id == activeProfileId) {
                                    profile.name + "  (showing)"
                                } else {
                                    profile.name
                                },
                                color = if (profile.id == activeProfileId) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                        Row {
                            TextButton(onClick = { namingProfile = profile }) { Text(stringResource(R.string.settings_rename)) }
                            if (profiles.size > 1) {
                                TextButton(onClick = { viewModel.deleteProfile(profile.id) }) {
                                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Button(onClick = { addingProfile = true }) { Text(stringResource(R.string.settings_add_someone)) }
                if (profiles.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    ToggleRow(
                        label = stringResource(R.string.settings_sync_this_profile_with_health_connect),
                        checked = activeProfile.healthConnectEnabled,
                        onCheckedChange = viewModel::setHealthConnectProfile,
                    )
                    Text(
                        // Health Connect keeps one set of weights for the phone's owner. It has
                        // no idea a household exists, so only one profile can use it without the
                        // two of them being mixed together.
                        text = stringResource(R.string.settings_health_connect_keeps_one_set_of),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SettingsSection {
                SectionHeading(stringResource(R.string.settings_food_logging))
                Spacer(Modifier.height(4.dp))
                ToggleRow(
                    label = stringResource(R.string.settings_keep_a_food_database),
                    checked = settings.nutritionEnabled,
                    onCheckedChange = viewModel::setNutritionEnabled,
                )
                Text(
                    // Off by default on purpose. Most people want a weight tracker, and a
                    // calorie counter bolted onto the front of one is why the paid apps feel
                    // like work.
                    text = stringResource(R.string.settings_off_by_default_turn_it_on),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            val active = profiles.firstOrNull { it.id == activeProfileId } ?: Profile(0, "", 0)
            ReminderCard(
                profile = active,
                who = active.name.takeIf { profiles.size > 1 },
                onToggle = { enabled ->
                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !ReminderReceiver.hasNotificationPermission(context)
                    ) {
                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.setReminder(
                            enabled,
                            active.reminderHour,
                            active.reminderMinute,
                            active.reminderDays,
                        )
                    }
                },
                onEditTime = { showReminderTime = true },
                onToggleDay = { day ->
                    val days = if (day in active.reminderDays) {
                        active.reminderDays - day
                    } else {
                        active.reminderDays + day
                    }
                    viewModel.setReminder(
                        active.reminderEnabled,
                        active.reminderHour,
                        active.reminderMinute,
                        days,
                    )
                },
                onTest = { viewModel.notifyTestSent(ReminderReceiver.showTestNotification(context)) },
            )
        }

        item {
            SettingsSection {
                SectionHeading(stringResource(R.string.settings_weekly_summary))
                Spacer(Modifier.height(6.dp))
                ToggleRow(
                    label = stringResource(R.string.settings_send_a_weekly_read),
                    checked = settings.weeklySummaryEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.setWeeklySummary(
                            enabled,
                            settings.weeklySummaryDay,
                            settings.weeklySummaryHour,
                        )
                    },
                )
                if (settings.weeklySummaryEnabled) {
                    Spacer(Modifier.height(6.dp))
                    ChipRow(
                        options = DayOfWeek.entries.map {
                            it to it.getDisplayName(TextStyle.SHORT, locale)
                        },
                        selected = settings.weeklySummaryDay,
                        onSelect = { day ->
                            viewModel.setWeeklySummary(true, day, settings.weeklySummaryHour)
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    ChipRow(
                        options = listOf(9, 12, 19, 21).map {
                            it to String.format(locale, "%02d:00", it)
                        },
                        selected = settings.weeklySummaryHour,
                        onSelect = { hour ->
                            viewModel.setWeeklySummary(true, settings.weeklySummaryDay, hour)
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_a_short_note_on_how_the),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            val syncSettings by viewModel.syncSettings.collectAsStateWithLifecycle()
            val syncing by viewModel.syncing.collectAsStateWithLifecycle()
            SyncCard(
                settings = syncSettings,
                folderName = syncSettings.folderUri
                    ?.let { android.net.Uri.parse(it).lastPathSegment?.substringAfterLast(':') },
                syncing = syncing,
                onPickFolder = { syncFolderLauncher.launch(null) },
                onUseWebDav = viewModel::useWebDav,
                onSyncNow = viewModel::syncNow,
                onTurnOff = viewModel::turnSyncOff,
                onBackgroundChange = viewModel::setSyncInBackground,
                needsLocalNetwork = syncSettings.mode == SyncMode.WEBDAV &&
                    !localNetworkGranted &&
                    SyncAddress.isOnLocalNetwork(syncSettings.webDavUrl.orEmpty()),
                onAllowLocalNetwork = {
                    localNetworkLauncher.launch(com.weighttrack.data.sync.LocalNetworkPermission.NAME)
                },
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

        item {
            val autoBackup by viewModel.autoBackup.collectAsStateWithLifecycle()
            SettingsSection {
                SectionHeading(stringResource(R.string.settings_your_data))
                Spacer(Modifier.height(4.dp))
                LabelledValue(stringResource(R.string.settingsscreen_readings_stored), entryCount.toString())
                Text(
                    text = stringResource(R.string.settings_weighttrack_has_no_account_and_no),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { exportJsonLauncher.launch(BackupCodec.suggestedFileName("json")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_back_up_everything)) }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { restoreLauncher.launch(arrayOf("application/json", "*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_restore_from_a_backup)) }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.settings_automatic_backup),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_automatic_backup_explained),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (autoBackup.problem) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_backup_folder_gone),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                autoBackup.lastAt?.let { at ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.settings_last_backup,
                            DateFormatters.fullDate(
                                java.time.Instant.ofEpochMilli(at)
                                    .atZone(java.time.ZoneId.systemDefault())
                                    .toLocalDate(),
                            ),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { autoBackupFolderLauncher.launch(null) }) {
                        Text(
                            stringResource(
                                if (autoBackup.folder == null) {
                                    R.string.settings_choose_backup_folder
                                } else {
                                    R.string.settings_change_backup_folder
                                },
                            ),
                        )
                    }
                    if (autoBackup.folder != null) {
                        TextButton(onClick = viewModel::turnOffAutoBackup) {
                            Text(stringResource(R.string.settings_turn_off_automatic_backup))
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.settings_spreadsheets_and_other_apps), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { exportCsvLauncher.launch(BackupCodec.suggestedFileName("csv")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_export_readings_as_csv)) }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        exportMeasurementsLauncher.launch("weighttrack-measurements-" + LocalDate.now() + ".csv")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_export_measurements_as_csv)) }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_import_a_csv_from_another_app)) }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_exports_from_libra_happy_scale_openscale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            val lockAvailability = remember(resumeCount) {
                AppLockSupport.availability(BiometricManager.from(context))
            }
            SettingsSection {
                SectionHeading(stringResource(R.string.settings_privacy))
                Spacer(Modifier.height(4.dp))
                // The switch is shown whenever the lock is already on, whatever the sensor
                // says today. A lock you cannot turn off is worse than no lock, and hiding the
                // switch under the working case is how someone ends up stuck with one.
                if (lockAvailability == AppLockAvailability.AVAILABLE || settings.appLockEnabled) {
                    ToggleRow(
                        label = stringResource(R.string.settings_lock_the_app),
                        checked = settings.appLockEnabled,
                        onCheckedChange = viewModel::setAppLockEnabled,
                    )
                }
                Text(
                    text = when (lockAvailability) {
                        AppLockAvailability.AVAILABLE ->
                            stringResource(R.string.settings_asks_for_your_fingerprint_face_or)
                        AppLockAvailability.NO_SCREEN_LOCK ->
                            stringResource(R.string.settings_set_a_screen_lock_in_android)
                        AppLockAvailability.UNAVAILABLE ->
                            stringResource(R.string.settings_this_device_has_no_screen_lock)
                        AppLockAvailability.NEEDS_SECURITY_UPDATE ->
                            stringResource(R.string.settings_android_has_switched_this_phone_s)
                        AppLockAvailability.TEMPORARILY_UNAVAILABLE ->
                            stringResource(R.string.settings_android_cannot_check_your_fingerprint_or)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            val crashReportCount by viewModel.crashReportCount.collectAsStateWithLifecycle()
            SettingsSection {
                SectionHeading(stringResource(R.string.settings_diagnostics))
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_if_the_app_ever_closes_unexpectedly),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onOpenCrashLogs, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when (crashReportCount) {
                            0 -> stringResource(R.string.settings_crash_reports_none)
                            1 -> stringResource(R.string.settings_crash_reports)
                            else -> stringResource(R.string.settings_crash_reports_2, crashReportCount)
                        },
                    )
                }
            }
        }

        item {
            SettingsSection {
                SectionHeading(stringResource(R.string.settings_about))
                Spacer(Modifier.height(6.dp))
                LabelledValue(stringResource(R.string.settingsscreen_version), BuildConfig.VERSION_NAME)
                LabelledValue(stringResource(R.string.settingsscreen_build), if (BuildConfig.FOSS_ONLY) "F-Droid" else "Play")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_free_and_open_source_under_the),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
        val timeState = rememberTimePickerState(
            initialHour = activeProfile.reminderHour,
            initialMinute = activeProfile.reminderMinute,
        )
        AlertDialog(
            onDismissRequest = { showReminderTime = false },
            title = { Text(stringResource(R.string.settings_reminder_time)) },
            text = {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { TimePicker(state = timeState) }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setReminder(
                        activeProfile.reminderEnabled,
                        timeState.hour,
                        timeState.minute,
                        activeProfile.reminderDays,
                    )
                    showReminderTime = false
                }) { Text(stringResource(R.string.common_set)) }
            },
            dismissButton = {
                TextButton(onClick = { showReminderTime = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * What is in the file, before anything is written.
 *
 * A restore reaches into every screen at once and there is no undo for it, so the counts are put
 * in front of somebody first. It also says what a restore does to what is already here, because
 * "restore" reads to most people as "replace" and this one merges.
 */
@Composable
private fun RestoreDialog(
    preview: BackupPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.restore_merges),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                // Zero is shown rather than hidden: a backup with no diary in it is a fact worth
                // seeing before it lands on a phone that has one.
                LabelledValue(
                    stringResource(R.string.restore_count_profiles),
                    preview.profiles.toString(),
                )
                LabelledValue(
                    stringResource(R.string.restore_count_weights),
                    preview.weights.toString(),
                )
                LabelledValue(
                    stringResource(R.string.restore_count_measurements),
                    preview.measurements.toString(),
                )
                LabelledValue(stringResource(R.string.restore_count_water), preview.water.toString())
                LabelledValue(stringResource(R.string.restore_count_fasts), preview.fasts.toString())
                LabelledValue(stringResource(R.string.restore_count_goals), preview.goals.toString())
                LabelledValue(stringResource(R.string.restore_count_foods), preview.foods.toString())
                LabelledValue(
                    stringResource(R.string.restore_count_food_log),
                    preview.foodLog.toString(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.restore_photos_excluded),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.restore_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun ReminderCard(
    profile: Profile,
    /** Null when there is only one, so the card does not shout a name at somebody alone. */
    who: String?,
    onToggle: (Boolean) -> Unit,
    onEditTime: () -> Unit,
    onToggleDay: (DayOfWeek) -> Unit,
    onTest: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    SettingsSection {
        SectionHeading(who?.let { stringResource(R.string.settingsscreen_weigh_in_reminder_for, it) } ?: stringResource(R.string.settingsscreen_weigh_in_reminder))
        Spacer(Modifier.height(6.dp))
        ToggleRow(
            label = who?.let { stringResource(R.string.settingsscreen_remind_weigh_in, it) } ?: stringResource(R.string.settingsscreen_remind_me_weigh_in),
            checked = profile.reminderEnabled,
            onCheckedChange = onToggle,
        )
        if (profile.reminderEnabled) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onEditTime) {
                Text(
                    stringResource(
                        R.string.settings_at_time,
                        profile.reminderHour,
                        profile.reminderMinute,
                    ),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { day ->
                    SegmentButton(
                        label = day.getDisplayName(TextStyle.SHORT, locale),
                        selected = day in profile.reminderDays,
                        onClick = { onToggleDay(day) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_you_will_not_be_nudged_on),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onTest, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_send_a_test_notification))
            }
            Spacer(Modifier.height(8.dp))
            // Said once, plainly, rather than offered as a thing to go and fix. A daily
            // reminder is not an alarm clock, and the app no longer asks for the privileged
            // permission that would make it exact.
            Text(
                text = stringResource(R.string.settings_reminders_are_approximate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_if_reminders_stop_arriving_check_that),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HealthConnectCard(
    state: HealthConnectState,
    lowestOfDay: Boolean,
    onLowestOfDayChange: (Boolean) -> Unit,
    onDirectionChange: (HealthDirection) -> Unit,
    onOriginExcludedChange: (String, Boolean) -> Unit,
    onRequestPermissions: () -> Unit,
    onSync: () -> Unit,
    onInstall: () -> Unit,
    onExplain: () -> Unit,
) {
    SettingsSection {
        SectionHeading(stringResource(R.string.settings_health_connect))
        Spacer(Modifier.height(6.dp))
        when (state.availability) {
            HealthConnectAvailability.NOT_SUPPORTED -> {
                Text(
                    text = stringResource(R.string.settings_health_connect_is_not_available_on),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HealthConnectAvailability.UPDATE_REQUIRED -> {
                Text(
                    text = stringResource(R.string.settings_health_connect_needs_updating_before_weighttrack),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_update)) }
            }
            HealthConnectAvailability.INSTALLED -> {
                Text(
                    text = stringResource(R.string.settings_pulls_readings_in_from_withings_renpho),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                if (state.granted) {
                    Text(
                        text = stringResource(R.string.settings_health_direction),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    ChipRow(
                        options = listOf(
                            HealthDirection.TWO_WAY to stringResource(R.string.settings_health_two_way),
                            HealthDirection.READ_ONLY to stringResource(R.string.settings_health_read_only),
                            HealthDirection.WRITE_ONLY to stringResource(R.string.settings_health_write_only),
                        ),
                        selected = state.direction,
                        onSelect = onDirectionChange,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_health_direction_explained),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    ToggleRow(
                        label = stringResource(R.string.settings_keep_lowest_of_day),
                        checked = lowestOfDay,
                        onCheckedChange = onLowestOfDayChange,
                    )
                    Text(
                        text = stringResource(R.string.settings_keep_lowest_of_day_explained),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.settings_health_origins),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_health_origins_explained),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.origins.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settings_health_origin_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.origins.forEach { origin ->
                        ToggleRow(
                            label = OriginNames.describe(
                                LocalContext.current,
                                origin.packageName,
                                origin.device,
                            ),
                            // On means "keep taking readings from this", which is the way round
                            // somebody reads a row with an app's name against it.
                            checked = !origin.excluded,
                            onCheckedChange = { wanted ->
                                onOriginExcludedChange(origin.packageName, !wanted)
                            },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onSync,
                        enabled = !state.syncing,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.syncing) stringResource(R.string.settingsscreen_syncing) else stringResource(R.string.settingsscreen_sync_now)) }
                    if (!state.grantedEverything) {
                        // Anybody who connected before food, water and steps existed granted
                        // only weight. Without this the app would keep quietly failing to write
                        // meals and never say why, because the Connect button is long gone.
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.settings_meals_drinks_and_steps_are_not),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = onRequestPermissions,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.settings_allow_the_rest)) }
                    }
                } else {
                    if (state.accessWithdrawn) {
                        Text(
                            text = stringResource(R.string.settings_health_access_withdrawn),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(
                                if (state.accessWithdrawn) {
                                    R.string.settings_health_reconnect
                                } else {
                                    R.string.settings_connect
                                },
                            ),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        // The same page Health Connect sends people to from its own settings. Somebody deciding
        // what to allow should be able to read what each access is for from here too.
        TextButton(onClick = onExplain) {
            Text(stringResource(R.string.settings_health_what_is_used))
        }
    }
}

@Composable
internal fun SettingsSection(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), content = content)
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
        )
    }
}

@Composable
private fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            SegmentButton(
                label = label,
                selected = value == selected,
                onClick = { onSelect(value) },
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun openHealthConnectListing(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setData(Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun weightUnitLabel(unit: WeightUnit): String = when (unit) {
    WeightUnit.KG -> stringResource(R.string.onboarding_kilograms)
    WeightUnit.LB -> stringResource(R.string.onboarding_pounds)
    WeightUnit.ST_LB -> stringResource(R.string.onboarding_stones)
}

@Composable
private fun lengthUnitLabel(unit: LengthUnit): String = when (unit) {
    LengthUnit.CM -> stringResource(R.string.settings_centimetres)
    LengthUnit.IN -> stringResource(R.string.settings_inches)
}

@Composable
private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(R.string.settings_follow_system)
    ThemeMode.LIGHT -> stringResource(R.string.onboarding_light)
    ThemeMode.DARK -> stringResource(R.string.settings_dark)
    ThemeMode.AMOLED -> stringResource(R.string.settings_black)
}

@Composable
private fun sexLabel(sex: Sex): String = when (sex) {
    Sex.MALE -> stringResource(R.string.settings_male)
    Sex.FEMALE -> stringResource(R.string.settings_female)
}

@Composable
private fun activityLabel(level: ActivityLevel): String = when (level) {
    ActivityLevel.SEDENTARY -> stringResource(R.string.onboarding_sedentary)
    ActivityLevel.LIGHT -> stringResource(R.string.settings_lightly_active)
    ActivityLevel.MODERATE -> stringResource(R.string.settings_moderately_active)
    ActivityLevel.ACTIVE -> stringResource(R.string.onboarding_active)
    ActivityLevel.VERY_ACTIVE -> stringResource(R.string.onboarding_very_active)
}

/** One text field and two buttons, for naming a person. */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.settings_name)) },
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text) },
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } },
    )
}
