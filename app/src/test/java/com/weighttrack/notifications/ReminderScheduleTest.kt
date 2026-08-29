package com.weighttrack.notifications

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderScheduleTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    // 2026-01-01 is a Thursday.
    private fun at(day: Int, hour: Int, minute: Int = 0): ZonedDateTime =
        LocalDateTime.of(2026, 1, day, hour, minute).atZone(zone)

    private val everyDay = DayOfWeek.entries.toSet()

    @Test
    fun `a time later today fires today`() {
        val next = ReminderSchedule.nextTrigger(at(1, 6, 0), hour = 7, minute = 30, days = everyDay)!!
        assertThat(next).isEqualTo(at(1, 7, 30))
    }

    @Test
    fun `a time already gone today rolls to tomorrow`() {
        val next = ReminderSchedule.nextTrigger(at(1, 9, 0), hour = 7, minute = 30, days = everyDay)!!
        assertThat(next).isEqualTo(at(2, 7, 30))
    }

    @Test
    fun `the exact minute counts as passed rather than firing twice`() {
        // Treating "now" as still due would re-fire the alarm that just went off.
        val next = ReminderSchedule.nextTrigger(at(1, 7, 30), hour = 7, minute = 30, days = everyDay)!!
        assertThat(next).isEqualTo(at(2, 7, 30))
    }

    @Test
    fun `only scheduled weekdays are chosen`() {
        // Thursday 1 Jan, asking for Mondays only, lands on Monday 5 Jan.
        val next = ReminderSchedule.nextTrigger(
            at(1, 6, 0),
            hour = 7,
            minute = 0,
            days = setOf(DayOfWeek.MONDAY),
        )!!
        assertThat(next).isEqualTo(at(5, 7, 0))
        assertThat(next.dayOfWeek).isEqualTo(DayOfWeek.MONDAY)
    }

    @Test
    fun `a single weekly day whose time has passed rolls a full week`() {
        // This is the case a seven-day search window gets wrong: Thursday only, already 9am,
        // so the answer is next Thursday, eight days ahead of the start of the search.
        val next = ReminderSchedule.nextTrigger(
            at(1, 9, 0),
            hour = 7,
            minute = 0,
            days = setOf(DayOfWeek.THURSDAY),
        )!!
        assertThat(next).isEqualTo(at(8, 7, 0))
    }

    @Test
    fun `weekday only schedules skip the weekend`() {
        // Friday 2 Jan after the reminder time; the next weekday is Monday 5 Jan.
        val next = ReminderSchedule.nextTrigger(
            at(2, 9, 0),
            hour = 7,
            minute = 0,
            days = setOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
            ),
        )!!
        assertThat(next).isEqualTo(at(5, 7, 0))
    }

    @Test
    fun `no chosen days means no reminder`() {
        assertThat(ReminderSchedule.nextTrigger(at(1, 6, 0), 7, 0, emptySet())).isNull()
    }

    @Test
    fun `out of range times are clamped rather than throwing`() {
        val next = ReminderSchedule.nextTrigger(at(1, 6, 0), hour = 99, minute = 99, days = everyDay)
        assertThat(next).isNotNull()
        assertThat(next!!.hour).isEqualTo(23)
        assertThat(next.minute).isEqualTo(59)
    }

    @Test
    fun `midnight is a valid reminder time`() {
        val next = ReminderSchedule.nextTrigger(at(1, 6, 0), hour = 0, minute = 0, days = everyDay)!!
        assertThat(next).isEqualTo(at(2, 0, 0))
    }
}
