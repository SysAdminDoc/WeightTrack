package com.weighttrack.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.nutrition.MacroBasis
import com.weighttrack.core.nutrition.MacroTarget
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.testSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek

@RunWith(RobolectricTestRunner::class)
class MacroTargetRepositoryTest {

    private lateinit var database: WeightTrackDatabase
    private lateinit var profiles: ProfileRepository
    private lateinit var targets: MacroTargetRepository

    private val everyday = MacroTarget(kcal = 2_000.0, proteinG = 150.0, basis = MacroBasis.GRAMS)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()
        profiles = ProfileRepository(database.profileDao(), testSettingsRepository(), DeletionRecorder(database.deletionDao(), database.syncDao()))
        targets = MacroTargetRepository(database.macroTargetDao(), profiles, DeletionRecorder(database.deletionDao(), database.syncDao()))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a target applies to every day that has not got one of its own`() = runTest {
        targets.set(everyday)

        val stored = targets.observe().first()
        assertThat(stored.forDay(DayOfWeek.MONDAY)!!.kcal).isWithin(1e-9).of(2_000.0)
        assertThat(stored.forDay(DayOfWeek.SUNDAY)!!.kcal).isWithin(1e-9).of(2_000.0)
    }

    @Test
    fun `a day with its own target keeps it, and the rest are untouched`() = runTest {
        targets.set(everyday)
        targets.set(everyday.copy(kcal = 2_600.0), day = DayOfWeek.SATURDAY)

        val stored = targets.observe().first()
        assertThat(stored.forDay(DayOfWeek.SATURDAY)!!.kcal).isWithin(1e-9).of(2_600.0)
        assertThat(stored.forDay(DayOfWeek.FRIDAY)!!.kcal).isWithin(1e-9).of(2_000.0)
    }

    @Test
    fun `setting the same day twice replaces it rather than adding a second`() = runTest {
        targets.set(everyday, day = DayOfWeek.SATURDAY)
        targets.set(everyday.copy(kcal = 2_600.0), day = DayOfWeek.SATURDAY)

        assertThat(targets.observe().first().byDay).hasSize(1)
        assertThat(targets.current().forDay(DayOfWeek.SATURDAY)!!.kcal).isWithin(1e-9).of(2_600.0)
    }

    @Test
    fun `clearing a day puts it back on the everyday target`() = runTest {
        targets.set(everyday)
        targets.set(everyday.copy(kcal = 2_600.0), day = DayOfWeek.SATURDAY)

        targets.clear(DayOfWeek.SATURDAY)

        assertThat(targets.current().forDay(DayOfWeek.SATURDAY)!!.kcal).isWithin(1e-9).of(2_000.0)
    }

    @Test
    fun `two people aim at different things`() = runTest {
        profiles.ensureDefault()
        val me = profiles.activeId()
        targets.set(everyday)

        profiles.add("Sam")
        targets.set(everyday.copy(kcal = 1_600.0))

        assertThat(targets.current().default!!.kcal).isWithin(1e-9).of(1_600.0)
        profiles.setActive(me)
        assertThat(targets.current().default!!.kcal).isWithin(1e-9).of(2_000.0)
    }

    @Test
    fun `how it was typed is remembered, but grams are what is stored`() = runTest {
        // A share of a calorie figure that later changes would silently mean something else.
        targets.set(everyday.copy(basis = MacroBasis.PERCENT))

        val stored = targets.current().default!!
        assertThat(stored.basis).isEqualTo(MacroBasis.PERCENT)
        assertThat(stored.proteinG!!).isWithin(1e-9).of(150.0)
    }

    @Test
    fun `no target at all is a state, not a crash`() = runTest {
        assertThat(targets.current().hasAny).isFalse()
        assertThat(targets.current().forDay(DayOfWeek.MONDAY)).isNull()
    }
}
