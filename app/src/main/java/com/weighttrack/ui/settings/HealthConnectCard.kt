package com.weighttrack.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.weighttrack.R
import com.weighttrack.core.model.HealthDirection
import com.weighttrack.health.HealthConnectAvailability
import com.weighttrack.ui.components.SectionHeading
import com.weighttrack.ui.format.OriginNames

@Composable
internal fun HealthConnectCard(
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
                    ConnectedHealthConnect(
                        state = state,
                        lowestOfDay = lowestOfDay,
                        onLowestOfDayChange = onLowestOfDayChange,
                        onDirectionChange = onDirectionChange,
                        onOriginExcludedChange = onOriginExcludedChange,
                        onRequestPermissions = onRequestPermissions,
                        onSync = onSync,
                    )
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

/** Everything below the heading once the permissions are in hand. */
@Composable
private fun ConnectedHealthConnect(
    state: HealthConnectState,
    lowestOfDay: Boolean,
    onLowestOfDayChange: (Boolean) -> Unit,
    onDirectionChange: (HealthDirection) -> Unit,
    onOriginExcludedChange: (String, Boolean) -> Unit,
    onRequestPermissions: () -> Unit,
    onSync: () -> Unit,
) {
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
}
