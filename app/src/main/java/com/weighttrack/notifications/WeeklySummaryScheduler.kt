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

/**
 * Books the weekly summary.
 *
 * Uses the same next-trigger maths as the daily reminder, with a single chosen weekday, so the
 * awkward "the time has already passed today" case is solved once rather than twice.
 */
@Singleton
class WeeklySummaryScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val alarmManager: AlarmManager? = context.getSystemService()

    fun reschedule(settings: AppSettings, now: ZonedDateTime = ZonedDateTime.now()) {
        cancel()
        if (!settings.weeklySummaryEnabled) return
        val next = ReminderSchedule.nextTrigger(
            now = now,
            hour = settings.weeklySummaryHour,
            minute = 0,
            days = setOf(settings.weeklySummaryDay),
        ) ?: return
        val manager = alarmManager ?: return
        // Inexact on purpose. A weekly summary a few minutes late is fine, and an inexact
        // alarm needs no permission and costs the battery far less than an exact one.
        runCatching {
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                next.toInstant().toEpochMilli(),
                summaryIntent(),
            )
        }
    }

    fun nextTriggerAt(settings: AppSettings, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime? {
        if (!settings.weeklySummaryEnabled) return null
        return ReminderSchedule.nextTrigger(
            now = ZonedDateTime.now(zone),
            hour = settings.weeklySummaryHour,
            minute = 0,
            days = setOf(settings.weeklySummaryDay),
        )
    }

    fun cancel() {
        alarmManager?.cancel(summaryIntent())
    }

    private fun summaryIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, WeeklySummaryReceiver::class.java).setAction(ACTION_SUMMARY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val REQUEST_CODE = 4301
        const val ACTION_SUMMARY = "com.weighttrack.action.WEEKLY_SUMMARY"
        const val CHANNEL_SUMMARY = "weekly_summary"
        const val NOTIFICATION_ID = 4302

        /** Quieter than the weigh-in reminder: this is a report, not a prompt to do something. */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService<NotificationManager>() ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SUMMARY,
                    context.getString(com.weighttrack.R.string.summary_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(com.weighttrack.R.string.summary_channel_description)
                    setShowBadge(false)
                },
            )
        }
    }
}
