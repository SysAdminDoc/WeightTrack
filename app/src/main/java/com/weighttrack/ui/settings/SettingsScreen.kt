package com.weighttrack.ui.settings

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.weighttrack.BuildConfig
import com.weighttrack.core.math.TrendEngine
import com.weighttrack.core.math.UnitConverter
import com.weighttrack.core.model.ActivityLevel
import com.weighttrack.core.model.LengthUnit
import com.weighttrack.core.model.Sex
import com.weighttrack.core.model.ThemeMode
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.data.io.BackupCodec
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.health.HealthConnectAvailability
import com.weighttrack.notifications.ReminderReceiver
import com.weighttrack.ui.components.LabelledValue
import com.weighttrack.ui.components.SectionCard
import com.weighttrack.ui.components.SectionHeading
import com.weighttrack.ui.format.LengthFormatter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    entryCount: Int,
    healthConnectState: HealthConnectState,
    busy: Boolean,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showReminderTime by remember { mutableStateOf(false) }
    var heightText by remember(settings.profile.heightMm, settings.lengthUnit) {
        mutableStateOf(
            settings.profile.heightMm.takeIf { it > 0 }
                ?.let { LengthFormatter.value(it, settings.lengthUnit, decimals = 1) }
                .orEmpty(),
        )
    }
    var birthYearText by remember(settings.profile.birthYear) {
        mutableStateOf(settings.profile.birthYear.takeIf { it > 0 }?.toString().orEmpty())
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
    ) { uri -> uri?.let(viewModel::importJson) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.setReminder(true, settings.reminderHour, settings.reminderMinute, settings.reminderDays)
        } else {
            viewModel.notifyTestSent(false)
        }
    }

    val healthConnectLauncher = rememberLauncherForActivityResult(
        viewModel.healthConnect.permissionContract(),
    ) { granted -> viewModel.onHealthConnectPermissionResult(granted) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (busy) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }

        item {
            SectionCard {
                SectionHeading("Units")
                Spacer(Modifier.height(8.dp))
                ChipRow(
                    options = WeightUnit.entries.map { it to weightUnitLabel(it) },
                    selected = settings.weightUnit,
                    onSelect = viewModel::setWeightUnit,
                )
                Spacer(Modifier.height(8.dp))
                ChipRow(
                    options = LengthUnit.entries.map { it to lengthUnitLabel(it) },
                    selected = settings.lengthUnit,
                    onSelect = viewModel::setLengthUnit,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Weights are stored in grams, so switching units never changes a single reading.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard {
                SectionHeading("Appearance")
                Spacer(Modifier.height(8.dp))
                ChipRow(
                    options = ThemeMode.entries.map { it to themeLabel(it) },
                    selected = settings.themeMode,
                    onSelect = viewModel::setThemeMode,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Spacer(Modifier.height(4.dp))
                    ToggleRow(
                        label = "Use wallpaper colours",
                        checked = settings.dynamicColor,
                        onCheckedChange = viewModel::setDynamicColor,
                    )
                }
            }
        }

        item {
            SectionCard {
                SectionHeading("Trend smoothing")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "A shorter window follows the scale more closely. A longer one is steadier but slower to react. Ten days is the setting the Hacker's Diet uses and it suits most people.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("${settings.trendWindowDays} days", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = settings.trendWindowDays.toFloat(),
                    onValueChange = { viewModel.setTrendWindow(it.toInt()) },
                    valueRange = TrendEngine.MIN_WINDOW_DAYS.toFloat()..TrendEngine.MAX_WINDOW_DAYS.toFloat(),
                    steps = TrendEngine.MAX_WINDOW_DAYS - TrendEngine.MIN_WINDOW_DAYS - 1,
                )
            }
        }

        item {
            SectionCard {
                SectionHeading("About you")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Only used to work out BMI, body fat and how many calories you burn. It never leaves the phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = heightText,
                    onValueChange = { text ->
                        heightText = text
                        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let {
                            viewModel.setHeightMm(UnitConverter.displayToMm(it, settings.lengthUnit))
                        }
                    },
                    label = { Text("Height (${LengthFormatter.unitLabel(settings.lengthUnit)})") },
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
                        birthYearText.toIntOrNull()
                            ?.takeIf { it in 1900..LocalDate.now().year }
                            ?.let(viewModel::setBirthYear)
                    },
                    label = { Text("Year of birth") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text("Sex", style = MaterialTheme.typography.bodySmall)
                ChipRow(
                    options = Sex.entries.map { it to sexLabel(it) },
                    selected = settings.profile.sex,
                    onSelect = viewModel::setSex,
                )
                Spacer(Modifier.height(10.dp))
                Text("Activity level", style = MaterialTheme.typography.bodySmall)
                ChipRow(
                    options = ActivityLevel.entries.map { it to activityLabel(it) },
                    selected = settings.profile.activityLevel,
                    onSelect = viewModel::setActivityLevel,
                )
            }
        }

        item {
            ReminderCard(
                settings = settings,
                canScheduleExact = viewModel.canScheduleExactAlarms(),
                onToggle = { enabled ->
                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !ReminderReceiver.hasNotificationPermission(context)
                    ) {
                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.setReminder(
                            enabled,
                            settings.reminderHour,
                            settings.reminderMinute,
                            settings.reminderDays,
                        )
                    }
                },
                onEditTime = { showReminderTime = true },
                onToggleDay = { day ->
                    val days = if (day in settings.reminderDays) {
                        settings.reminderDays - day
                    } else {
                        settings.reminderDays + day
                    }
                    viewModel.setReminder(
                        settings.reminderEnabled,
                        settings.reminderHour,
                        settings.reminderMinute,
                        days,
                    )
                },
                onTest = { viewModel.notifyTestSent(ReminderReceiver.showTestNotification(context)) },
                onOpenExactAlarmSettings = { openExactAlarmSettings(context) },
            )
        }

        item {
            HealthConnectCard(
                state = healthConnectState,
                onRequestPermissions = { healthConnectLauncher.launch(viewModel.healthConnect.permissions) },
                onSync = viewModel::syncHealthConnect,
                onInstall = { openHealthConnectListing(context) },
            )
        }

        item {
            SectionCard {
                SectionHeading("Your data")
                Spacer(Modifier.height(4.dp))
                LabelledValue("Readings stored", entryCount.toString())
                Text(
                    text = "WeightTrack has no account and no server. Cloud backup is switched off on purpose, so these files are how your history moves to a new phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { exportJsonLauncher.launch(BackupCodec.suggestedFileName("json")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Back up everything") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { restoreLauncher.launch(arrayOf("application/json", "*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Restore from a backup") }

                Spacer(Modifier.height(14.dp))
                Text("Spreadsheets and other apps", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { exportCsvLauncher.launch(BackupCodec.suggestedFileName("csv")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Export readings as CSV") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        exportMeasurementsLauncher.launch("weighttrack-measurements-${LocalDate.now()}.csv")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Export measurements as CSV") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Import a CSV from another app") }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Exports from Libra, Happy Scale, openScale, MyFitnessPal, Renpho, Withings and most others are read automatically. Columns are matched by name and the date format is worked out from the file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard {
                SectionHeading("About")
                Spacer(Modifier.height(6.dp))
                LabelledValue("Version", BuildConfig.VERSION_NAME)
                LabelledValue("Build", if (BuildConfig.FOSS_ONLY) "F-Droid" else "Play")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Free and open source under the MIT licence. No ads, no subscription, no account, and nothing about you is sent anywhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showReminderTime) {
        val timeState = rememberTimePickerState(
            initialHour = settings.reminderHour,
            initialMinute = settings.reminderMinute,
        )
        AlertDialog(
            onDismissRequest = { showReminderTime = false },
            title = { Text("Reminder time") },
            text = {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { TimePicker(state = timeState) }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setReminder(
                        settings.reminderEnabled,
                        timeState.hour,
                        timeState.minute,
                        settings.reminderDays,
                    )
                    showReminderTime = false
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showReminderTime = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ReminderCard(
    settings: AppSettings,
    canScheduleExact: Boolean,
    onToggle: (Boolean) -> Unit,
    onEditTime: () -> Unit,
    onToggleDay: (DayOfWeek) -> Unit,
    onTest: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
) {
    SectionCard {
        SectionHeading("Weigh-in reminder")
        Spacer(Modifier.height(6.dp))
        ToggleRow(
            label = "Remind me to weigh in",
            checked = settings.reminderEnabled,
            onCheckedChange = onToggle,
        )
        if (settings.reminderEnabled) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onEditTime) {
                Text("At %02d:%02d".format(settings.reminderHour, settings.reminderMinute))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(
                        selected = day in settings.reminderDays,
                        onClick = { onToggleDay(day) },
                        label = {
                            Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault()))
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "You will not be nudged on a day you have already weighed in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onTest, modifier = Modifier.fillMaxWidth()) {
                Text("Send a test notification")
            }
            if (!canScheduleExact) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Android is holding WeightTrack to approximate alarms, so the reminder may arrive a little late. Allowing exact alarms fixes that.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                TextButton(onClick = onOpenExactAlarmSettings) { Text("Open alarm settings") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "If reminders stop arriving, check that battery optimisation is turned off for WeightTrack. Samsung and Xiaomi phones are the usual culprits.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HealthConnectCard(
    state: HealthConnectState,
    onRequestPermissions: () -> Unit,
    onSync: () -> Unit,
    onInstall: () -> Unit,
) {
    SectionCard {
        SectionHeading("Health Connect")
        Spacer(Modifier.height(6.dp))
        when (state.availability) {
            HealthConnectAvailability.NOT_SUPPORTED -> {
                Text(
                    text = "Health Connect is not available on this device, so scale apps cannot hand readings over automatically. Everything else works as normal.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HealthConnectAvailability.UPDATE_REQUIRED -> {
                Text(
                    text = "Health Connect needs updating before WeightTrack can talk to it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) { Text("Update") }
            }
            HealthConnectAvailability.INSTALLED -> {
                Text(
                    text = "Pulls readings in from Withings, Renpho, Samsung Health, Fitbit and anything else that writes weight, and sends yours back out. Records are matched on both sides, so nothing is ever imported twice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                if (state.granted) {
                    Button(
                        onClick = onSync,
                        enabled = !state.syncing,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.syncing) "Syncing" else "Sync now") }
                } else {
                    Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                        Text("Connect")
                    }
                }
            }
        }
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
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
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

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
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

private fun weightUnitLabel(unit: WeightUnit): String = when (unit) {
    WeightUnit.KG -> "Kilograms"
    WeightUnit.LB -> "Pounds"
    WeightUnit.ST_LB -> "Stones and pounds"
}

private fun lengthUnitLabel(unit: LengthUnit): String = when (unit) {
    LengthUnit.CM -> "Centimetres"
    LengthUnit.IN -> "Inches"
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "Follow system"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
    ThemeMode.AMOLED -> "Black"
}

private fun sexLabel(sex: Sex): String = when (sex) {
    Sex.MALE -> "Male"
    Sex.FEMALE -> "Female"
}

private fun activityLabel(level: ActivityLevel): String = when (level) {
    ActivityLevel.SEDENTARY -> "Sedentary"
    ActivityLevel.LIGHT -> "Lightly active"
    ActivityLevel.MODERATE -> "Moderately active"
    ActivityLevel.ACTIVE -> "Active"
    ActivityLevel.VERY_ACTIVE -> "Very active"
}
