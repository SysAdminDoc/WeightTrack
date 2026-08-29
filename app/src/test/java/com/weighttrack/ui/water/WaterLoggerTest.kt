package com.weighttrack.ui.water

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.VolumeUnit
import com.weighttrack.core.model.WeightUnit
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class WaterLoggerTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val now: Instant = Instant.parse("2026-06-15T09:30:00Z")
    private val today: LocalDate = LocalDate.ofInstant(now, zone)

    @Test
    fun `a drink logged today keeps the current time`() {
        assertThat(WaterLogger.timestampFor(today, zone, now)).isEqualTo(now)
    }

    @Test
    fun `a drink added to an earlier day lands on that day`() {
        // The bug this guards: always using "now" means tapping add while viewing yesterday
        // silently files the drink under today and yesterday's total never moves.
        val yesterday = today.minusDays(1)
        val stamped = WaterLogger.timestampFor(yesterday, zone, now)
        assertThat(LocalDate.ofInstant(stamped, zone)).isEqualTo(yesterday)
    }

    @Test
    fun `an earlier day is stamped at midday so it cannot slip across the boundary`() {
        val stamped = WaterLogger.timestampFor(today.minusDays(3), zone, now)
        assertThat(stamped.atZone(zone).hour).isEqualTo(12)
    }

    @Test
    fun `the day is judged in the local zone, not UTC`() {
        // 23:30 in Auckland is still the previous day in UTC. Reading the date in UTC would
        // call this "today" wrongly and skip the midday stamping.
        val auckland = ZoneId.of("Pacific/Auckland")
        val lateEvening = Instant.parse("2026-06-15T11:30:00Z")
        val localDay = LocalDate.ofInstant(lateEvening, auckland)
        assertThat(WaterLogger.timestampFor(localDay, auckland, lateEvening)).isEqualTo(lateEvening)
    }
}

class VolumeUnitTest {

    @Test
    fun `kilograms mean millilitres`() {
        assertThat(VolumeUnit.forWeightUnit(WeightUnit.KG)).isEqualTo(VolumeUnit.ML)
    }

    @Test
    fun `pounds mean fluid ounces`() {
        assertThat(VolumeUnit.forWeightUnit(WeightUnit.LB)).isEqualTo(VolumeUnit.FL_OZ)
    }

    @Test
    fun `stones mean millilitres, not fluid ounces`() {
        // Stones are a British unit and Britain measures drinks in millilitres. Following
        // "anything that is not kilograms" would hand fluid ounces to exactly the wrong people.
        assertThat(VolumeUnit.forWeightUnit(WeightUnit.ST_LB)).isEqualTo(VolumeUnit.ML)
    }
}
