package com.weighttrack.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.sync.SyncKind
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.testSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * Every way a person can delete something, and whether the deletion is remembered.
 *
 * This is the test that was missing. A delete path with no tombstone looks perfectly correct on
 * one phone: the row goes, the screen updates, nothing complains. It only shows up on the second
 * device, which still holds the row, has no reason to drop it, and hands it straight back.
 */
@RunWith(RobolectricTestRunner::class)
class DeletionCoverageTest {

    private lateinit var database: WeightTrackDatabase
    private lateinit var deletions: DeletionRecorder
    private lateinit var profiles: ProfileRepository
    private lateinit var weights: WeightRepository
    private lateinit var fasts: FastRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()
        deletions = DeletionRecorder(database, database.deletionDao(), database.syncDao())
        profiles = ProfileRepository(database.profileDao(), testSettingsRepository(), deletions, database.weightEntryDao())
        weights = WeightRepository(database.weightEntryDao(), profiles, deletions)
        fasts = FastRepository(database.fastDao(), profiles, deletions)
    }

    @After
    fun tearDown() = database.close()

    private suspend fun tombstones(kind: SyncKind): List<String> =
        database.deletionDao().all().filter { it.kind == kind.name }.map { it.syncId }

    /**
     * Puts the one profile's recorded time an hour into the past, and answers with it.
     *
     * Making a profile now stamps the moment it was made, so an edit in the same millisecond
     * carries the same number and "the edit is newer" cannot be told from "the edit did not
     * stamp". Reading a real clock twice and hoping they differ is a test that fails on a fast
     * morning; moving the row back first asks the same question and always gets an answer.
     */
    private suspend fun ageProfile(): Long {
        val row = database.profileDao().all().single()
        val aged = row.updatedAtUtcMillis - 3_600_000
        database.profileDao().update(row.copy(updatedAtUtcMillis = aged))
        return aged
    }

    @Test
    fun `deleting one reading is remembered`() = runTest {
        profiles.ensureDefault()
        weights.add(grams = 80_000, timestamp = Instant.ofEpochMilli(1_800_000_000_000))
        val entry = weights.observeEntries().first().single()

        weights.delete(entry)

        assertThat(tombstones(SyncKind.WEIGHT)).containsExactly(entry.clientRecordId)
    }

    @Test
    fun `deleting several readings at once is remembered`() = runTest {
        profiles.ensureDefault()
        weights.add(grams = 80_000, timestamp = Instant.ofEpochMilli(1_800_000_000_000))
        weights.add(grams = 79_500, timestamp = Instant.ofEpochMilli(1_799_900_000_000))
        val all = weights.observeEntries().first()

        // Selecting several rows on the History screen and deleting them. The single-row path
        // remembered; this one did not, so everything deleted this way came back.
        weights.deleteByIds(all.map { it.id })

        assertThat(tombstones(SyncKind.WEIGHT))
            .containsExactlyElementsIn(all.map { it.clientRecordId })
    }

    @Test
    fun `a deletion says whose row it was`() = runTest {
        profiles.ensureDefault()
        weights.add(grams = 80_000, timestamp = Instant.ofEpochMilli(1_800_000_000_000))
        val entry = weights.observeEntries().first().single()

        weights.delete(entry)

        // A reading's name is only unique within a profile. Without the owner on the tombstone,
        // deleting one person's morning takes another person's identically named row with it.
        val recorded = database.deletionDao().all().single { it.kind == SyncKind.WEIGHT.name }
        assertThat(recorded.profileSyncId).isNotEmpty()
    }

    @Test
    fun `cancelling a fast is remembered`() = runTest {
        profiles.ensureDefault()
        fasts.start(targetMinutes = 960)

        // Started by mistake and cancelled. Without a tombstone the other phone starts it again
        // and tells somebody they are fasting.
        assertThat(fasts.cancelActive()).isTrue()

        assertThat(tombstones(SyncKind.FAST)).hasSize(1)
    }

    @Test
    fun `deleting a profile is remembered, and so is everything it owned`() = runTest {
        profiles.ensureDefault()
        val id = profiles.add("Them")
        profiles.setActive(id)
        weights.add(grams = 62_000, timestamp = Instant.ofEpochMilli(1_800_000_000_000))
        val theirs = weights.observeEntries().first().single()

        profiles.delete(id)

        // One tombstone for the profile is not enough. The other device still holds their
        // weigh-ins, and with nothing to say those are gone it hands the whole history back and
        // the deleted person reappears.
        assertThat(tombstones(SyncKind.PROFILE)).hasSize(1)
        assertThat(tombstones(SyncKind.WEIGHT)).containsExactly(theirs.clientRecordId)
    }

    @Test
    fun `deleting a profile is remembered for its injection log too`() = runTest {
        // The per-row tombstones are the fallback for the case where the other phone keeps the
        // profile itself. Without them a deleted person's doses go on being offered by it.
        profiles.ensureDefault()
        val id = profiles.add("Them")
        profiles.setActive(id)
        val medication = MedicationRepository(
            database.medicationDoseDao(),
            database.sideEffectDao(),
            profiles,
            deletions,
        )
        medication.addDose(
            drug = com.weighttrack.core.medication.GlpDrug.SEMAGLUTIDE,
            milligrams = 0.5,
            site = com.weighttrack.core.medication.InjectionSite.ABDOMEN_LEFT,
            timestamp = Instant.ofEpochMilli(1_800_000_000_000),
        )
        medication.addSideEffect(
            kind = com.weighttrack.core.medication.SideEffectKind.NAUSEA,
            severity = com.weighttrack.core.medication.SideEffectSeverity.MILD,
            timestamp = Instant.ofEpochMilli(1_800_000_000_000),
        )
        val dose = medication.observeDoses().first().single()
        val effect = medication.observeSideEffects().first().single()

        profiles.delete(id)

        assertThat(tombstones(SyncKind.MEDICATION_DOSE)).hasSize(1)
        assertThat(tombstones(SyncKind.SIDE_EFFECT)).hasSize(1)
        assertThat(dose.id).isGreaterThan(0L)
        assertThat(effect.id).isGreaterThan(0L)
    }

    @Test
    fun `the last profile cannot be deleted and nothing is remembered about it`() = runTest {
        profiles.ensureDefault()
        val only = profiles.observeAll().first().single()

        assertThat(profiles.delete(only.id)).isFalse()

        assertThat(database.deletionDao().all()).isEmpty()
    }

    @Test
    fun `renaming a profile records when it happened`() = runTest {
        profiles.ensureDefault()
        val before = ageProfile()

        profiles.rename(database.syncDao().profiles().single().id, "Alex")

        // Sync compares these times to decide what is newer. Without a bump the new name looks
        // exactly like the row the other device already has, and it never leaves the phone.
        val after = database.syncDao().profiles().single()
        assertThat(after.name).isEqualTo("Alex")
        assertThat(after.updatedAtUtcMillis).isGreaterThan(before)
    }

    @Test
    fun `setting a reminder records when it happened`() = runTest {
        profiles.ensureDefault()
        val id = database.syncDao().profiles().single().id
        val before = ageProfile()

        profiles.setReminder(id, enabled = true, hour = 6, minute = 15, days = emptySet())

        val after = database.syncDao().profiles().single()
        assertThat(after.reminderEnabled).isTrue()
        assertThat(after.updatedAtUtcMillis).isGreaterThan(before)
    }
}
