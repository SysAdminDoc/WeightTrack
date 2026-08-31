package com.weighttrack.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.weighttrack.R
import com.weighttrack.core.format.LengthFormatter
import com.weighttrack.core.format.LocaleNumbers
import com.weighttrack.core.math.UnitConverter
import com.weighttrack.core.model.ActivityLevel
import com.weighttrack.core.model.Sex
import com.weighttrack.core.model.UserProfile
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.data.repo.Profile
import com.weighttrack.ui.components.SectionHeading
import java.time.LocalDate

/**
 * Height, year of birth, sex and activity level.
 *
 * The body of the person on screen, not of the phone. Reading these off the app settings is what
 * let a household of two work every figure out from one person's height.
 */
internal fun LazyListScope.bodySection(
    settings: AppSettings,
    demographics: UserProfile,
    viewModel: SettingsViewModel,
) = item {
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
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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

/** Everybody the phone keeps records for, and which of them the app is showing. */
internal fun LazyListScope.peopleSection(
    profiles: List<Profile>,
    activeProfileId: Long,
    activeProfile: Profile,
    viewModel: SettingsViewModel,
    onRename: (Profile) -> Unit,
    onAdd: () -> Unit,
) = item {
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
            ProfileRow(
                profile = profile,
                showing = profile.id == activeProfileId,
                deletable = profiles.size > 1,
                onSwitch = { viewModel.switchProfile(profile.id) },
                onRename = { onRename(profile) },
                onDelete = { viewModel.deleteProfile(profile.id) },
            )
        }
        Spacer(Modifier.height(4.dp))
        Button(onClick = onAdd) { Text(stringResource(R.string.settings_add_someone)) }
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

@Composable
private fun ProfileRow(
    profile: Profile,
    showing: Boolean,
    deletable: Boolean,
    onSwitch: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onSwitch) {
            Text(
                text = if (showing) profile.name + "  (showing)" else profile.name,
                color = if (showing) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        Row {
            TextButton(onClick = onRename) { Text(stringResource(R.string.settings_rename)) }
            if (deletable) {
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** The one switch that turns the whole food half of the app on. */
internal fun LazyListScope.foodLoggingSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) = item {
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
