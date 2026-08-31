package com.weighttrack.data.prefs

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.math.TrendEngine
import com.weighttrack.core.model.ActivityLevel
import com.weighttrack.core.model.LengthUnit
import com.weighttrack.core.model.Sex
import com.weighttrack.core.model.ThemeMode
import com.weighttrack.core.model.UserProfile
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.data.testSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.DayOfWeek

/**
 * The preferences every screen reads, which nothing tested.
 *
 * Mostly about what happens at the edges: a value out of range, a name that no longer means
 * anything, and the stamp sync compares two devices by. A settings write that does not move that
 * stamp is a change that never leaves the phone.
 */
class SettingsRepositoryTest {

    @Test
    fun `defaults are what a fresh install reads`() = runTest {
        val settings = testSettingsRepository().settings.first()

        assertThat(settings.weightUnit).isEqualTo(WeightUnit.KG)
        assertThat(settings.lengthUnit).isEqualTo(LengthUnit.CM)
        assertThat(settings.themeMode).isEqualTo(ThemeMode.AMOLED)
        assertThat(settings.trendWindowDays).isEqualTo(TrendEngine.DEFAULT_WINDOW_DAYS)
        // Null means follow the phone's region, which is the answer most people never change.
        assertThat(settings.firstDayOfWeek).isNull()
        assertThat(settings.nutritionEnabled).isFalse()
    }

    @Test
    fun `a smoothing window outside what the maths supports is brought back inside it`() = runTest {
        val repository = testSettingsRepository()

        repository.setTrendWindowDays(1)
        assertThat(repository.settings.first().trendWindowDays)
            .isEqualTo(TrendEngine.MIN_WINDOW_DAYS)

        repository.setTrendWindowDays(9_999)
        assertThat(repository.settings.first().trendWindowDays)
            .isEqualTo(TrendEngine.MAX_WINDOW_DAYS)
    }

    @Test
    fun `a change that describes the person moves the stamp sync compares by`() = runTest {
        val repository = testSettingsRepository()
        val before = repository.settings.first().updatedAtUtcMillis

        repository.setWeightUnit(WeightUnit.LB)

        // Left unstamped, the change never looks newer than the copy the other device already
        // holds, and never leaves the phone.
        assertThat(repository.settings.first().updatedAtUtcMillis).isGreaterThan(before)
    }

    @Test
    fun `the whole body is written and read back as one`() = runTest {
        val repository = testSettingsRepository()

        repository.setProfile(
            UserProfile(
                heightMm = 1_780,
                sex = Sex.FEMALE,
                birthYear = 1_988,
                activityLevel = ActivityLevel.ACTIVE,
            ),
        )

        val profile = repository.settings.first().profile
        assertThat(profile.heightMm).isEqualTo(1_780)
        assertThat(profile.sex).isEqualTo(Sex.FEMALE)
        assertThat(profile.birthYear).isEqualTo(1_988)
        assertThat(profile.activityLevel).isEqualTo(ActivityLevel.ACTIVE)
    }

    @Test
    fun `where a week begins is remembered, and can be handed back to the phone`() = runTest {
        val repository = testSettingsRepository()

        repository.setFirstDayOfWeek(DayOfWeek.SATURDAY)
        assertThat(repository.settings.first().firstDayOfWeek).isEqualTo(DayOfWeek.SATURDAY)
        assertThat(repository.settings.first().weekRule.firstDay).isEqualTo(DayOfWeek.SATURDAY)

        repository.setFirstDayOfWeek(null)
        assertThat(repository.settings.first().firstDayOfWeek).isNull()
    }

    @Test
    fun `the weekly summary hour is kept to a real hour`() = runTest {
        val repository = testSettingsRepository()

        repository.setWeeklySummary(enabled = true, day = DayOfWeek.SUNDAY, hour = 47)

        assertThat(repository.settings.first().weeklySummaryHour).isEqualTo(23)
    }

    @Test
    fun `the active profile is remembered`() = runTest {
        val repository = testSettingsRepository()

        repository.setActiveProfile(7)

        assertThat(repository.settings.first().activeProfileId).isEqualTo(7)
    }
}
