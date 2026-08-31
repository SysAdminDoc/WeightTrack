package com.weighttrack.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.os.Build
import com.weighttrack.R
import com.weighttrack.core.math.SmoothingMode
import com.weighttrack.core.math.TrendEngine
import com.weighttrack.core.model.LengthUnit
import com.weighttrack.core.model.ThemeMode
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.ui.components.SectionHeading
import java.time.DayOfWeek
import java.time.format.TextStyle

/** Which units every figure in the app is shown in. */
internal fun LazyListScope.unitsSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) = item {
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

/** Theme, and the wallpaper colours where the phone offers them. */
internal fun LazyListScope.appearanceSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) = item {
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

/** How many days the trend line averages over. */
internal fun LazyListScope.trendSmoothingSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) = item {
    SettingsSection {
        SectionHeading(stringResource(R.string.settings_trend_smoothing))
        Spacer(Modifier.height(4.dp))
        ChipRow(
            options = SmoothingMode.entries.map { it to smoothingModeLabel(it) },
            selected = settings.smoothingMode,
            onSelect = viewModel::setSmoothingMode,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                when (settings.smoothingMode) {
                    SmoothingMode.EMA -> R.string.settings_smoothing_average_explained
                    SmoothingMode.HOLT -> R.string.settings_smoothing_slope_explained
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
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

/** Which day a week is counted from, everywhere a week appears. */
internal fun LazyListScope.weekStartSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) = item {
    val locale = LocalConfiguration.current.locales[0]
    SettingsSection {
        SectionHeading(stringResource(R.string.settings_week_starts_on))
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_week_starts_on_explained),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        ChipRow(
            options = listOf(
                null to stringResource(R.string.settings_week_follow_region),
                DayOfWeek.MONDAY to
                    DayOfWeek.MONDAY.getDisplayName(TextStyle.SHORT, locale),
                DayOfWeek.SATURDAY to
                    DayOfWeek.SATURDAY.getDisplayName(TextStyle.SHORT, locale),
                DayOfWeek.SUNDAY to
                    DayOfWeek.SUNDAY.getDisplayName(TextStyle.SHORT, locale),
            ),
            selected = settings.firstDayOfWeek,
            onSelect = viewModel::setFirstDayOfWeek,
        )
    }
}

/** The once-a-week notification, and when it arrives. */
internal fun LazyListScope.weeklySummarySection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) = item {
    val locale = LocalConfiguration.current.locales[0]
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
