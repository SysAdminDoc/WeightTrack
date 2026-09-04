package com.weighttrack.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.weighttrack.MainActivity
import com.weighttrack.R
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.ProfileRepository
import com.weighttrack.data.repo.WeightRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * Fires the weigh-in reminder, then books the next one.
 *
 * Alarms are one-shot, so rescheduling here is what keeps a daily reminder daily. If this step
 * is missed the reminder works exactly once and then goes quiet, which is the failure people
 * report about other trackers.
 */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var profileRepository: ProfileRepository

    @Inject lateinit var weightRepository: WeightRepository

    @Inject lateinit var scheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val profiles = profileRepository.observeAll().first()
                val profileId = ReminderScheduler.profileIdOf(intent)
                val profile = profiles.firstOrNull { it.id == profileId }
                    // An alarm booked before profiles existed carries no identifier, so it
                    // belongs to whoever was there first.
                    ?: profiles.firstOrNull()
                    ?: return@launch

                scheduler.reschedule(profile)
                if (!profile.reminderEnabled) return@launch

                // Nobody needs telling to weigh themselves after they already have, and the
                // question is whether this person has, not whether anybody has.
                val latest = weightRepository.latestFor(profile.id)
                val today = LocalDate.now(ZoneId.systemDefault())
                if (latest?.localDate == today) return@launch

                showReminder(context, profile.id, profile.name.takeIf { profiles.size > 1 })
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showReminder(context: Context, profileId: Long, who: String?) {
        Notifications.ensureChannel(context)
        if (!hasNotificationPermission(context)) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                who?.let { context.getString(R.string.reminder_title_for, it) }
                    ?: context.getString(R.string.reminder_title),
            )
            .setContentText(context.getString(R.string.reminder_body))
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(Notifications.reminderIdFor(profileId), notification)
        }
    }

    companion object {
        fun hasNotificationPermission(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }

        /**
         * Posts the same notification immediately.
         *
         * The settings screen offers this because "did my reminder actually get through?" is
         * otherwise unanswerable until the next morning, and on Samsung or Xiaomi the answer
         * is often no until battery optimisation is turned off for the app.
         */
        fun showTestNotification(context: Context): Boolean {
            Notifications.ensureChannel(context)
            if (!hasNotificationPermission(context)) return false
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return false
            val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_REMINDERS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.reminder_test_title))
                .setContentText(context.getString(R.string.reminder_test_body))
                .setAutoCancel(true)
                .build()
            return runCatching {
                NotificationManagerCompat.from(context)
                    .notify(Notifications.TEST_NOTIFICATION_ID, notification)
                true
            }.getOrDefault(false)
        }
    }
}

/**
 * Books every alarm again whenever the answer to "when is 07:30 tomorrow" may have changed.
 *
 * A reminder is one alarm at a moment in absolute time, worked out from a wall-clock time in a
 * zone. Everything that moves the wall clock under it therefore moves the reminder: a reboot,
 * because alarms do not survive one; but also somebody setting the clock by hand, flying
 * somewhere, or a government moving the seasonal offset. Only the reboot used to be listened
 * for, so a reminder set for the morning could quietly start arriving in the middle of the
 * night and go on doing it until the phone was next restarted.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var profileRepository: ProfileRepository

    @Inject lateinit var scheduler: ReminderScheduler

    @Inject lateinit var weeklyScheduler: WeeklySummaryScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in REBOOKING_ACTIONS) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Booked from scratch: the times on the profiles are wall-clock times, so the
                // moment each one lands at has to be worked out again in whatever the zone and
                // offset now are.
                scheduler.reschedule(profileRepository.observeAll().first())
                weeklyScheduler.reschedule(settingsRepository.settings.first())
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        /**
         * Everything that can move a wall-clock time to a different moment.
         *
         * The offset one is Android 16 and later, where a seasonal change no longer arrives as
         * a timezone change. Naming it costs nothing on an older phone, which simply never
         * sends it, and leaving it out costs an hour twice a year on a newer one.
         */
        val REBOOKING_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.TIMEZONE_OFFSET_CHANGED",
        )
    }
}
