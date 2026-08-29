package com.weighttrack.health

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Which day a meal is filed under in Health Connect.
 *
 * A meal carries the day it counts towards and, separately, the moment it was typed in. Those are
 * only the same thing for today, and every other app reading Health Connect's daily totals will
 * disagree with the diary if the wrong one wins.
 */
class NutritionInstantTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")

    private fun enteredAt(text: String): Long =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `a meal added on the day it was eaten keeps its exact moment`() {
        val entered = enteredAt("2026-08-29T19:42:11")

        val instant = HealthConnectSync.instantFor(LocalDate.of(2026, 8, 29), entered, zone)

        assertThat(instant.toEpochMilli()).isEqualTo(entered)
    }

    @Test
    fun `a meal added later is filed under the day it was eaten`() {
        // Thursday evening, filling in Tuesday's dinner. The moment of typing says Thursday and
        // the diary says Tuesday, so Health Connect would have had Thursday's total wrong and
        // Tuesday's empty.
        val typedOnThursday = enteredAt("2026-08-27T21:05:00")

        val instant = HealthConnectSync.instantFor(LocalDate.of(2026, 8, 25), typedOnThursday, zone)

        assertThat(instant.atZone(zone).toLocalDate()).isEqualTo(LocalDate.of(2026, 8, 25))
        // The time of day is kept, so meals stay in the order they were entered within the day.
        assertThat(instant.atZone(zone).toLocalTime().toString()).isEqualTo("21:05")
    }

    @Test
    fun `copying yesterday puts the food on the day it was copied to`() {
        // Every row copied forward is stamped with the moment of copying, so this is the normal
        // case rather than the awkward one.
        val copiedThisMorning = enteredAt("2026-08-29T08:00:00")

        val instant = HealthConnectSync.instantFor(LocalDate.of(2026, 8, 29), copiedThisMorning, zone)

        assertThat(instant.atZone(zone).toLocalDate()).isEqualTo(LocalDate.of(2026, 8, 29))
    }

    @Test
    fun `a day the clocks changed on still lands on that day`() {
        // The clocks go back at 2am on 25 October 2026 in London, so 01:30 happens twice. Either
        // reading is on the right day, which is all this has to get right.
        val entered = enteredAt("2026-10-28T01:30:00")

        val instant = HealthConnectSync.instantFor(LocalDate.of(2026, 10, 25), entered, zone)

        assertThat(instant.atZone(zone).toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 25))
    }
}
