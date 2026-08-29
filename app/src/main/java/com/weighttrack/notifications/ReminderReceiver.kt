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

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var weightRepository: WeightRepository

    @Inject lateinit var scheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val settings = settingsRepository.settings.first()
                scheduler.reschedule(settings)

                if (!settings.reminderEnabled) return@launch
                // Nobody needs telling to weigh themselves after they already have.
                val latest = weightRepository.latest()
                val today = LocalDate.now(ZoneId.systemDefault())
                if (latest?.localDate == today) return@launch

                showReminder(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showReminder(context: Context) {
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
            .setContentTitle("Time to weigh in")
            .setContentText("One reading keeps the trend line honest.")
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(Notifications.REMINDER_NOTIFICATION_ID, notification)
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
                .setContentTitle("Reminders are working")
                .setContentText("This is what your weigh-in reminder will look like.")
                .setAutoCancel(true)
                .build()
            return runCatching {
                NotificationManagerCompat.from(context)
                    .notify(Notifications.REMINDER_NOTIFICATION_ID + 1, notification)
                true
            }.getOrDefault(false)
        }
    }
}

/**
 * Alarms do not survive a reboot, so they are booked again once the device is up. Without
 * this, reminders stop the first time the phone restarts and never come back.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var scheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                scheduler.reschedule(settingsRepository.settings.first())
            } finally {
                pendingResult.finish()
            }
        }
    }
}
