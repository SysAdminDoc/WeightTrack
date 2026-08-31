package com.weighttrack.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.weighttrack.R
import com.weighttrack.data.repo.Profile
import com.weighttrack.ui.components.SectionHeading
import com.weighttrack.ui.components.SegmentButton
import java.time.DayOfWeek
import java.time.format.TextStyle

@Composable
internal fun ReminderCard(
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
