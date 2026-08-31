package com.weighttrack.notifications

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A reminder is a wall-clock time, and the wall clock moves.
 *
 * It is booked as one alarm at a moment in absolute time, worked out from "07:30 in this zone".
 * Everything that moves the clock under it therefore moves the reminder: setting the time by
 * hand, flying somewhere, and a government changing the seasonal offset. Only a reboot used to
 * be listened for, so a morning reminder could quietly start arriving in the middle of the night
 * and go on doing it until the phone was next restarted.
 *
 * The two awkward days of the year are here in two zones: the morning that has no half past one
 * because the clocks went forward, and the morning that has two because they went back.
 */
class ReminderClockChangeTest {

    private val everyDay = DayOfWeek.entries.toSet()
    private val london = ZoneId.of("Europe/London")
    private val newYork = ZoneId.of("America/New_York")

    private fun next(zone: ZoneId, now: ZonedDateTime, hour: Int, minute: Int) =
        ReminderSchedule.nextTrigger(now, hour, minute, everyDay)

    @Test
    fun `the app is told when the clock is set, not only when the phone restarts`() {
        // Setting the time by hand, flying, and a seasonal offset change all move a wall-clock
        // reminder to a different moment. None of them is a reboot.
        assertThat(BootReceiver.REBOOKING_ACTIONS).containsAtLeast(
            "android.intent.action.TIME_SET",
            "android.intent.action.TIMEZONE_CHANGED",
            "android.intent.action.TIMEZONE_OFFSET_CHANGED",
        )
        // And the manifest asks to hear them, or the receiver is never called at all.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        BootReceiver.REBOOKING_ACTIONS.forEach { assertThat(manifest).contains(it) }
    }

    @Test
    fun `a reminder in the hour that does not exist still lands on that morning`() {
        // Britain, the last Sunday in March: 01:00 becomes 02:00, so 01:30 never happens.
        val beforeTheGap = ZonedDateTime.of(
            LocalDate.of(2027, 3, 28),
            LocalTime.of(0, 30),
            london,
        )

        val trigger = checkNotNull(next(london, beforeTheGap, hour = 1, minute = 30))

        // Java moves a time in the gap forward by the size of it rather than refusing. What
        // matters is that it is that morning and it is in the future, not the following day.
        assertThat(trigger.toLocalDate()).isEqualTo(LocalDate.of(2027, 3, 28))
        assertThat(trigger.isAfter(beforeTheGap)).isTrue()
    }

    @Test
    fun `a reminder in the hour that happens twice fires once, on the first of them`() {
        // Britain, the last Sunday in October: 02:00 becomes 01:00, so 01:30 comes round twice.
        val beforeTheOverlap = ZonedDateTime.of(
            LocalDate.of(2027, 10, 31),
            LocalTime.of(0, 30),
            london,
        )

        val trigger = checkNotNull(next(london, beforeTheOverlap, hour = 1, minute = 30))

        assertThat(trigger.toLocalDate()).isEqualTo(LocalDate.of(2027, 10, 31))
        // The earlier of the two, which is the one that is actually next. Booking the later one
        // would mean sitting through an hour and a half of the morning with no reminder.
        assertThat(trigger.offset).isEqualTo(java.time.ZoneOffset.ofHours(1))
    }

    @Test
    fun `the same two days in another zone behave the same way`() {
        // New York, the second Sunday in March and the first in November, an hour of the
        // morning earlier than Britain's and on different dates.
        val springForward = ZonedDateTime.of(
            LocalDate.of(2027, 3, 14),
            LocalTime.of(1, 30),
            newYork,
        )
        val fallBack = ZonedDateTime.of(LocalDate.of(2027, 11, 7), LocalTime.of(0, 30), newYork)

        val spring = checkNotNull(next(newYork, springForward, hour = 2, minute = 30))
        val autumn = checkNotNull(next(newYork, fallBack, hour = 1, minute = 30))

        assertThat(spring.toLocalDate()).isEqualTo(LocalDate.of(2027, 3, 14))
        assertThat(spring.isAfter(springForward)).isTrue()
        assertThat(autumn.toLocalDate()).isEqualTo(LocalDate.of(2027, 11, 7))
        assertThat(autumn.isAfter(fallBack)).isTrue()
    }

    @Test
    fun `a reminder booked before a zone change is a different moment after it`() {
        // Which is the whole reason the app has to hear about it: the alarm holds a moment in
        // absolute time, and half past seven in London is not half past seven in New York.
        val morning = LocalTime.of(7, 30)
        val date = LocalDate.of(2027, 6, 1)
        val here = ZonedDateTime.of(date, LocalTime.of(6, 0), london)
        val there = ZonedDateTime.of(date, LocalTime.of(6, 0), newYork)

        val booked = checkNotNull(next(london, here, morning.hour, morning.minute))
        val rebooked = checkNotNull(next(newYork, there, morning.hour, morning.minute))

        assertThat(booked.toInstant()).isNotEqualTo(rebooked.toInstant())
        // Both are half past seven where the person is, which is what they asked for.
        assertThat(booked.toLocalTime()).isEqualTo(morning)
        assertThat(rebooked.toLocalTime()).isEqualTo(morning)
    }

    @Test
    fun `nothing asks for the privilege an alarm clock needs`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val scheduler = File(
            "src/main/java/com/weighttrack/notifications/ReminderScheduler.kt",
        ).readText()

        // A daily weigh-in reminder is not an alarm clock, and asking for a privileged
        // permission for something that does not need it is how an app ends up on a list of
        // apps that ask for too much.
        assertThat(manifest).doesNotContain("SCHEDULE_EXACT_ALARM")
        assertThat(scheduler).doesNotContain("setExactAndAllowWhileIdle")
        // But it must still be allowed to fire in Doze, which is the part that matters on a
        // phone left on a bedside table all night.
        assertThat(scheduler).contains("setAndAllowWhileIdle")
    }

    @Test
    fun `the receiver is not something another app can poke`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val receiver = manifest.substringAfter(".notifications.ReminderReceiver")
            .substringBefore("/>")

        // A receiver that acts on an intent anybody can send is a receiver anybody can use to
        // make the app do work. The reminder one has no filter and is not exported at all.
        assertThat(receiver).contains("android:exported=\"false\"")
    }
}
