package com.weighttrack.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.ActivityLevel
import com.weighttrack.core.model.Sex
import com.weighttrack.core.model.UserProfile
import com.weighttrack.core.sync.SyncDocument
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.sync.SyncStore
import com.weighttrack.data.testSettingsRepository
import com.weighttrack.domain.ProgressCalculator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

/**
 * Whose body the figures are worked out from.
 *
 * Height, sex, year of birth and activity level used to belong to the phone while every reading
 * belonged to a person. A household of two therefore had one height between them: switching
 * profile computed the other person's BMI, healthy range, body-fat estimate, basal rate and
 * expenditure from the first person's body, and every one of those numbers looked perfectly
 * ordinary on screen.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileDemographicsTest {

    private lateinit var database: WeightTrackDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var profiles: ProfileRepository
    private lateinit var weights: WeightRepository
    private lateinit var progress: ProgressCalculator

    private val today = LocalDate.of(2026, 8, 31)

    private val tall = UserProfile(
        heightMm = 1_880,
        sex = Sex.MALE,
        birthYear = 1985,
        activityLevel = ActivityLevel.ACTIVE,
    )
    private val short = UserProfile(
        heightMm = 1_600,
        sex = Sex.FEMALE,
        birthYear = 1995,
        activityLevel = ActivityLevel.SEDENTARY,
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()
        settings = testSettingsRepository()
        val deletions = DeletionRecorder(database, database.deletionDao(), database.syncDao())
        profiles = ProfileRepository(
            database.profileDao(),
            settings,
            deletions,
            database.weightEntryDao(),
        )
        weights = WeightRepository(database.weightEntryDao(), profiles, deletions)
        progress = ProgressCalculator(
            weights,
            GoalRepository(database.goalDao(), profiles, deletions),
            MeasurementRepository(database.measurementDao(), profiles, deletions),
            settings,
            profiles,
        )
    }

    @After
    fun tearDown() = database.close()

    /** Two people, two bodies, and a weigh-in each on the same day. */
    private suspend fun household(): Pair<Long, Long> {
        profiles.ensureDefault()
        val first = profiles.observeAll().first().single().id
        profiles.setDemographics(first, tall)
        val second = profiles.add("Them")
        profiles.setDemographics(second, short)
        weights.addFor(profileId = first, grams = 80_000, timestamp = Instant.now())
        weights.addFor(profileId = second, grams = 80_000, timestamp = Instant.now())
        return first to second
    }

    @Test
    fun `the same weight gives each person their own figures`() = runTest {
        val (first, second) = household()

        profiles.setActive(first)
        val tallSnapshot = progress.observe { today }.first()
        profiles.setActive(second)
        val shortSnapshot = progress.observe { today }.first()

        // Eighty kilograms is a different BMI at 1.88 m and at 1.60 m, and a different healthy
        // range, and a different basal rate. One height for the phone made all three the same.
        assertThat(tallSnapshot.bmi).isNotEqualTo(shortSnapshot.bmi)
        assertThat(tallSnapshot.healthyRangeGrams).isNotEqualTo(shortSnapshot.healthyRangeGrams)
        assertThat(tallSnapshot.basalMetabolicRate)
            .isNotEqualTo(shortSnapshot.basalMetabolicRate)
        assertThat(tallSnapshot.totalDailyEnergyExpenditure)
            .isNotEqualTo(shortSnapshot.totalDailyEnergyExpenditure)
    }

    @Test
    fun `a profile added later starts blank rather than inheriting a body`() = runTest {
        profiles.ensureDefault()
        val first = profiles.observeAll().first().single().id
        profiles.setDemographics(first, tall)

        val second = profiles.add("Them")

        val theirs = profiles.observeAll().first().single { it.id == second }.demographics
        assertThat(theirs.heightMm).isEqualTo(0)
        assertThat(theirs.birthYear).isEqualTo(0)
    }

    @Test
    fun `the demographics that belonged to the phone go to one person, once`() = runTest {
        profiles.ensureDefault()
        val first = profiles.observeAll().first().single().id
        settings.setProfile(tall)
        val second = profiles.add("Them")

        // Active is the second profile by now, because adding one switches to it. The adoption
        // has to land on whoever was active, and it runs at startup, so this is that moment.
        profiles.setActive(first)
        profiles.adoptLegacyDemographics()
        profiles.adoptLegacyDemographics()

        val all = profiles.observeAll().first()
        assertThat(all.single { it.id == first }.demographics.heightMm).isEqualTo(1_880)
        // Not handed round the household: everybody being the same height is a claim nobody made.
        assertThat(all.single { it.id == second }.demographics.heightMm).isEqualTo(0)
    }

    @Test
    fun `each person's body travels with them to the other device`() = runTest {
        val (first, second) = household()
        val other = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()

        try {
            val document = SyncStore(database, database.syncDao(), database.deletionDao())
                .snapshot("phone", System.currentTimeMillis())
            val encoded = SyncDocument.encode(document)
            SyncStore(other, other.syncDao(), other.deletionDao())
                .apply(checkNotNull(SyncDocument.decode(encoded)), System.currentTimeMillis())

            val there = other.syncDao().profiles().associateBy { it.syncId }
            val here = database.syncDao().profiles().associateBy { it.syncId }
            assertThat(there.getValue(here.values.first { it.id == first }.syncId).heightMm)
                .isEqualTo(1_880)
            assertThat(there.getValue(here.values.first { it.id == second }.syncId).heightMm)
                .isEqualTo(1_600)
        } finally {
            other.close()
        }
    }

    @Test
    fun `a device that does not carry a body does not wipe one`() = runTest {
        val (first, _) = household()
        val name = database.syncDao().profiles().single { it.id == first }.syncId
        val store = SyncStore(database, database.syncDao(), database.deletionDao())
        val now = System.currentTimeMillis()

        // What an older version writes: the profile, with none of the four fields on it.
        store.apply(
            SyncDocument(
                deviceId = "older",
                writtenAtUtcMillis = now,
                profiles = listOf(
                    com.weighttrack.core.sync.SyncProfile(
                        syncId = name,
                        name = "Me",
                        position = 0,
                        createdAtUtcMillis = now - 100_000,
                        updatedAtUtcMillis = now,
                    ),
                ),
            ),
            now,
        )

        assertThat(database.syncDao().profiles().single { it.id == first }.heightMm)
            .isEqualTo(1_880)
    }
}
