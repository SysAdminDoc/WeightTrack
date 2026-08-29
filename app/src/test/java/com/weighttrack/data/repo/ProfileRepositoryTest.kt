package com.weighttrack.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.prefs.SettingsRepository
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
class ProfileRepositoryTest {

    private lateinit var database: WeightTrackDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var profiles: ProfileRepository
    private lateinit var weights: WeightRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()
        settings = testSettingsRepository()
        profiles = ProfileRepository(database.profileDao(), settings, DeletionRecorder(database.deletionDao(), database.syncDao()))
        weights = WeightRepository(database.weightEntryDao(), profiles, DeletionRecorder(database.deletionDao(), database.syncDao()))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a fresh database is given somebody to record against`() = runTest {
        profiles.ensureDefault()

        val all = profiles.observeAll().first()
        assertThat(all).hasSize(1)
        assertThat(profiles.activeId()).isEqualTo(all.single().id)
    }

    @Test
    fun `two people keep their readings apart`() = runTest {
        profiles.ensureDefault()
        val first = profiles.activeId()
        weights.add(grams = 82_500)

        val second = profiles.add("Sam")
        weights.add(grams = 64_000)

        assertThat(weights.observeEntries().first().map { it.grams }).containsExactly(64_000)
        profiles.setActive(first)
        assertThat(weights.observeEntries().first().map { it.grams }).containsExactly(82_500)
        assertThat(second).isNotEqualTo(first)
    }

    @Test
    fun `adding somebody switches to them, because nobody adds one to leave it alone`() = runTest {
        profiles.ensureDefault()

        val id = profiles.add("Sam")

        assertThat(profiles.activeId()).isEqualTo(id)
    }

    @Test
    fun `deleting a profile takes its readings with it`() = runTest {
        profiles.ensureDefault()
        val first = profiles.activeId()
        val second = profiles.add("Sam")
        weights.add(grams = 64_000)

        assertThat(profiles.delete(second)).isTrue()

        // Rows left pointing at nothing are worse than losing them: they would still be counted
        // and still be exported, but never shown.
        assertThat(profiles.activeId()).isEqualTo(first)
        assertThat(database.weightEntryDao().count(second)).isEqualTo(0)
        assertThat(profiles.observeAll().first()).hasSize(1)
    }

    @Test
    fun `the last profile cannot be deleted`() = runTest {
        profiles.ensureDefault()
        val only = profiles.activeId()

        assertThat(profiles.delete(only)).isFalse()
        assertThat(profiles.observeAll().first()).hasSize(1)
    }

    @Test
    fun `a stored choice pointing at a profile that has gone falls back rather than emptying`() =
        runTest {
            profiles.ensureDefault()
            val first = profiles.activeId()
            settings.setActiveProfile(9_999)

            // Nothing would ever be shown again if this returned the missing identifier.
            assertThat(profiles.activeId()).isEqualTo(first)
        }

    @Test
    fun `each person keeps their own reminder`() = runTest {
        profiles.ensureDefault()
        val first = profiles.activeId()
        val second = profiles.add("Sam")

        profiles.setReminder(first, enabled = true, hour = 7, minute = 0, days = setOf(DayOfWeek.MONDAY))
        profiles.setReminder(second, enabled = true, hour = 21, minute = 30, days = setOf(DayOfWeek.FRIDAY))

        val all = profiles.observeAll().first()
        val one = all.first { it.id == first }
        val two = all.first { it.id == second }
        assertThat(one.reminderHour).isEqualTo(7)
        assertThat(one.reminderDays).containsExactly(DayOfWeek.MONDAY)
        assertThat(two.reminderHour).isEqualTo(21)
        assertThat(two.reminderDays).containsExactly(DayOfWeek.FRIDAY)
    }

    @Test
    fun `only one profile can hold Health Connect`() = runTest {
        profiles.ensureDefault()
        val first = profiles.activeId()
        val second = profiles.add("Sam")

        profiles.setHealthConnect(first, true)
        profiles.setHealthConnect(second, true)

        // Health Connect keeps one set of weights for whoever owns the phone. Two profiles
        // writing to it would interleave two people and each would read the other back.
        assertThat(profiles.healthConnectId()).isEqualTo(second)
        assertThat(profiles.observeAll().first().count { it.healthConnectEnabled }).isEqualTo(1)
    }

    @Test
    fun `a reminder set before profiles existed moves onto the first one, once`() = runTest {
        profiles.ensureDefault()
        settings.setReminder(true, 6, 45, setOf(DayOfWeek.TUESDAY))

        profiles.adoptLegacyReminder()

        val first = profiles.observeAll().first().single()
        assertThat(first.reminderEnabled).isTrue()
        assertThat(first.reminderHour).isEqualTo(6)
        assertThat(first.reminderMinute).isEqualTo(45)
        assertThat(first.reminderDays).containsExactly(DayOfWeek.TUESDAY)

        // Running again must not undo a change made since.
        profiles.setReminder(first.id, enabled = false, hour = 6, minute = 45, days = setOf(DayOfWeek.TUESDAY))
        profiles.adoptLegacyReminder()
        assertThat(profiles.observeAll().first().single().reminderEnabled).isFalse()
    }

    @Test
    fun `a reading is offered to whoever it fits`() = runTest {
        profiles.ensureDefault()
        val me = profiles.activeId()
        weights.add(grams = 82_500)
        val sam = profiles.add("Sam")
        weights.add(grams = 64_000)

        val lastKnown = weights.latestPerProfile().mapValues { it.value.grams }

        assertThat(com.weighttrack.ble.ScaleReadingRouter.owner(82_300, lastKnown)).isEqualTo(me)
        assertThat(com.weighttrack.ble.ScaleReadingRouter.owner(63_800, lastKnown)).isEqualTo(sam)
        // Nobody in the house is this weight, so there is nobody to offer it to.
        assertThat(com.weighttrack.ble.ScaleReadingRouter.owner(45_000, lastKnown)).isNull()
    }

    @Test
    fun `importing the same file for a second person does not move the first person's rows`() =
        runTest {
            profiles.ensureDefault()
            val me = profiles.activeId()
            // Both a backup restore and the CSV importer reuse a record identifier, which used
            // to match across profiles and rewrite the profile column of the row it found.
            weights.add(grams = 82_500, clientRecordId = "import:1")

            val sam = profiles.add("Sam")
            weights.add(grams = 64_000, clientRecordId = "import:1")

            assertThat(database.weightEntryDao().count(me)).isEqualTo(1)
            assertThat(database.weightEntryDao().count(sam)).isEqualTo(1)
            assertThat(database.weightEntryDao().latest(me)!!.grams).isEqualTo(82_500)
            assertThat(database.weightEntryDao().latest(sam)!!.grams).isEqualTo(64_000)
        }

    @Test
    fun `syncing the same reading twice for one person still updates rather than duplicates`() =
        runTest {
            profiles.ensureDefault()
            val me = profiles.activeId()

            weights.add(grams = 82_500, clientRecordId = "hc:1")
            weights.add(grams = 82_600, clientRecordId = "hc:1")

            // The whole point of matching on the identifier: a second pass over an overlapping
            // window must not double everything it already imported.
            assertThat(database.weightEntryDao().count(me)).isEqualTo(1)
            assertThat(database.weightEntryDao().latest(me)!!.grams).isEqualTo(82_600)
        }

    @Test
    fun `deleting a profile hands back the photos so its files can go too`() = runTest {
        profiles.ensureDefault()
        val sam = profiles.add("Sam")
        database.progressPhotoDao().insert(
            com.weighttrack.data.db.ProgressPhotoEntity(
                profileId = sam,
                timestampUtcMillis = 1_000,
                localDate = "2026-08-29",
                fileName = "sam.jpg",
                weightGrams = null,
                note = null,
            ),
        )

        val photos = profiles.deleteReturningPhotos(sam)

        // The rows go in a transaction, but only the caller knows where the images live.
        assertThat(photos).containsExactly("sam.jpg")
        assertThat(profiles.deleteReturningPhotos(profiles.activeId())).isNull()
    }
}
