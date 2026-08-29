package com.weighttrack.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import com.weighttrack.data.prefs.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val alarmManager: AlarmManager? = context.getSystemService()

    /**
     * True when the system will honour an exact alarm.
     *
     * Below Android 12 exact alarms need no permission. From 12 onward the user grants it, and
     * without it the reminder still works, just with the system free to shift it a little.
     */
    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() == true
        } else {
            true
        }

    fun reschedule(settings: AppSettings, now: ZonedDateTime = ZonedDateTime.now()) {
        cancel()
        if (!settings.reminderEnabled) return
        val next = ReminderSchedule.nextTrigger(
            now = now,
            hour = settings.reminderHour,
            minute = settings.reminderMinute,
            days = settings.reminderDays,
        ) ?: return
        schedule(next.toInstant().toEpochMilli())
    }

    fun nextTriggerAt(settings: AppSettings, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime? {
        if (!settings.reminderEnabled) return null
        return ReminderSchedule.nextTrigger(
            now = ZonedDateTime.now(zone),
            hour = settings.reminderHour,
            minute = settings.reminderMinute,
            days = settings.reminderDays,
        )
    }

    private fun schedule(triggerAtMillis: Long) {
        val manager = alarmManager ?: return
        val pending = alarmIntent()
        // "AndAllowWhileIdle" is the part that matters: without it, Doze silently holds the
        // alarm until the device next wakes, which on a phone left on a bedside table can mean
        // the reminder never arrives at all.
        runCatching {
            if (canScheduleExact()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }
        }.onFailure {
            // A SecurityException here means the exact-alarm permission was revoked between
            // the check and the call. An approximate reminder beats no reminder.
            runCatching {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }
        }
    }

    fun cancel() {
        alarmManager?.cancel(alarmIntent())
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val REQUEST_CODE = 4201
        const val ACTION_REMIND = "com.weighttrack.action.REMIND"
    }
}

object Notifications {

    const val CHANNEL_REMINDERS = "weigh_in_reminders"
    const val REMINDER_NOTIFICATION_ID = 4202

    /**
     * Creates the channel. Safe to call repeatedly; the system ignores a channel that already
     * exists, and any importance the user has changed is preserved.
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            CHANNEL_REMINDERS,
            "Weigh-in reminders",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "A daily nudge to step on the scale."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
