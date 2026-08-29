package com.weighttrack.notifications

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Works out when the next weigh-in reminder is due.
 *
 * Kept as pure date maths so the awkward cases can actually be tested: a reminder time that
 * has already passed today, a schedule that skips most of the week, and the moment right on
 * the boundary. Libra's most common complaint is reminders that never fire, and most of that
 * class of bug lives in exactly this calculation.
 */
object ReminderSchedule {

    fun nextTrigger(
        now: ZonedDateTime,
        hour: Int,
        minute: Int,
        days: Set<DayOfWeek>,
    ): ZonedDateTime? {
        if (days.isEmpty()) return null
        val time = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))

        // Today counts only if the time has not already gone by; otherwise start tomorrow.
        // Checking eight days rather than seven covers the case where the only scheduled day
        // is today and its time has passed, which must roll to the same weekday next week.
        for (offset in 0..7) {
            val candidateDate = now.toLocalDate().plusDays(offset.toLong())
            if (candidateDate.dayOfWeek !in days) continue
            val candidate = candidateDate.atTime(time).atZone(now.zone)
            if (candidate.isAfter(now)) return candidate
        }
        return null
    }
}
