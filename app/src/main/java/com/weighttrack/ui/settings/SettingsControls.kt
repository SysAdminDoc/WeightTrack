package com.weighttrack.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.weighttrack.R
import com.weighttrack.core.math.SmoothingMode
import com.weighttrack.core.model.ActivityLevel
import com.weighttrack.core.model.LengthUnit
import com.weighttrack.core.model.Sex
import com.weighttrack.core.model.ThemeMode
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.ui.components.SegmentButton

@Composable
internal fun SettingsSection(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), content = content)
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
        )
    }
}

@Composable
internal fun <T> ChipRow(
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
internal fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

internal fun openHealthConnectListing(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setData(Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
internal fun weightUnitLabel(unit: WeightUnit): String = when (unit) {
    WeightUnit.KG -> stringResource(R.string.onboarding_kilograms)
    WeightUnit.LB -> stringResource(R.string.onboarding_pounds)
    WeightUnit.ST_LB -> stringResource(R.string.onboarding_stones)
}

@Composable
internal fun lengthUnitLabel(unit: LengthUnit): String = when (unit) {
    LengthUnit.CM -> stringResource(R.string.settings_centimetres)
    LengthUnit.IN -> stringResource(R.string.settings_inches)
}

@Composable
internal fun smoothingModeLabel(mode: SmoothingMode): String = when (mode) {
    SmoothingMode.EMA -> stringResource(R.string.settings_smoothing_average)
    SmoothingMode.HOLT -> stringResource(R.string.settings_smoothing_slope)
}

@Composable
internal fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(R.string.settings_follow_system)
    ThemeMode.LIGHT -> stringResource(R.string.onboarding_light)
    ThemeMode.DARK -> stringResource(R.string.settings_dark)
    ThemeMode.AMOLED -> stringResource(R.string.settings_black)
}

@Composable
internal fun sexLabel(sex: Sex): String = when (sex) {
    Sex.MALE -> stringResource(R.string.settings_male)
    Sex.FEMALE -> stringResource(R.string.settings_female)
}

@Composable
internal fun activityLabel(level: ActivityLevel): String = when (level) {
    ActivityLevel.SEDENTARY -> stringResource(R.string.onboarding_sedentary)
    ActivityLevel.LIGHT -> stringResource(R.string.settings_lightly_active)
    ActivityLevel.MODERATE -> stringResource(R.string.settings_moderately_active)
    ActivityLevel.ACTIVE -> stringResource(R.string.onboarding_active)
    ActivityLevel.VERY_ACTIVE -> stringResource(R.string.onboarding_very_active)
}
