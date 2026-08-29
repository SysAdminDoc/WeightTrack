package com.weighttrack.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.weighttrack.data.repo.Profile
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

    /**
     * Books the next reminder for everybody who wants one.
     *
     * Two people in a house weigh themselves at different times, so each profile gets its own
     * alarm. The request code is derived from the identifier, which is what stops one profile's
     * alarm replacing another's: an identical pending intent would silently overwrite it.
     */
    fun reschedule(profiles: List<Profile>, now: ZonedDateTime = ZonedDateTime.now()) {
        profiles.forEach { profile ->
            cancel(profile.id)
            if (!profile.reminderEnabled) return@forEach
            val next = nextTriggerAt(profile, now.zone) ?: return@forEach
            schedule(profile.id, next.toInstant().toEpochMilli())
        }
    }

    /** Books the next one for a single profile, which is what the receiver needs after firing. */
    fun reschedule(profile: Profile, now: ZonedDateTime = ZonedDateTime.now()) =
        reschedule(listOf(profile), now)

    fun nextTriggerAt(profile: Profile, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime? {
        if (!profile.reminderEnabled) return null
        return ReminderSchedule.nextTrigger(
            now = ZonedDateTime.now(zone),
            hour = profile.reminderHour,
            minute = profile.reminderMinute,
            days = profile.reminderDays,
        )
    }

    private fun schedule(profileId: Long, triggerAtMillis: Long) {
        val manager = alarmManager ?: return
        val pending = alarmIntent(profileId)
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

    fun cancel(profileId: Long) {
        alarmManager?.cancel(alarmIntent(profileId))
    }

    private fun alarmIntent(profileId: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE + profileId.toInt(),
        Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_REMIND)
            // In the data, not an extra: pending intents are matched ignoring extras, so two
            // profiles carrying only an extra would be the same intent and one would win.
            .setData("weighttrack://reminder/$profileId".toUri()),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val REQUEST_CODE = 4201
        const val ACTION_REMIND = "com.weighttrack.action.REMIND"

        /** Which profile an alarm was booked for, or null when it did not say. */
        fun profileIdOf(intent: Intent): Long? =
            intent.data?.lastPathSegment?.toLongOrNull()
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
            context.getString(com.weighttrack.R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(com.weighttrack.R.string.reminder_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
