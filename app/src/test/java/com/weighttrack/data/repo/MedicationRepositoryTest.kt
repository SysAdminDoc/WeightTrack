package com.weighttrack.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.medication.GlpDrug
import com.weighttrack.core.medication.InjectionSite
import com.weighttrack.core.medication.SideEffectKind
import com.weighttrack.core.medication.SideEffectSeverity
import com.weighttrack.core.sync.SyncKind
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.testProfileRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * The injection log against a real database.
 *
 * The rotation is the part worth driving through Room rather than through a list: it is worked
 * out from what is stored, so it has to stay right after a delete, after an undo and after a
 * sync brings a dose in from the other phone.
 */
@RunWith(RobolectricTestRunner::class)
class MedicationRepositoryTest {

    private lateinit var database: WeightTrackDatabase
    private lateinit var medication: MedicationRepository

    private val at = Instant.ofEpochMilli(1_800_000_000_000)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()
        val profiles = testProfileRepository(database)
        medication = MedicationRepository(
            database.medicationDoseDao(),
            database.sideEffectDao(),
            profiles,
            DeletionRecorder(database, database.deletionDao(), database.syncDao()),
        )
    }

    @After
    fun tearDown() = database.close()

    private suspend fun logDose(site: InjectionSite, daysAgo: Long) = medication.addDose(
        drug = GlpDrug.SEMAGLUTIDE,
        milligrams = 0.5,
        site = site,
        timestamp = at.minusMillis(daysAgo * 86_400_000),
    )

    @Test
    fun `the first suggestion is somewhere to start`() = runTest {
        assertThat(medication.suggestedSite()).isEqualTo(InjectionSite.ABDOMEN_LEFT)
    }

    @Test
    fun `a dose logged with a site moves the suggestion on`() = runTest {
        logDose(InjectionSite.ABDOMEN_LEFT, daysAgo = 0)

        val next = medication.suggestedSite()

        assertThat(next).isNotEqualTo(InjectionSite.ABDOMEN_LEFT)
    }

    @Test
    fun `the suggestion follows the newest dose and not the newest row`() = runTest {
        // Recorded out of order, which is what happens when somebody fills in a dose they forgot
        // to log, and what a sync from the other phone produces.
        logDose(InjectionSite.THIGH_RIGHT, daysAgo = 0)
        logDose(InjectionSite.ABDOMEN_LEFT, daysAgo = 7)

        assertThat(medication.suggestedSite()).isEqualTo(InjectionSite.UPPER_ARM_LEFT)
    }

    @Test
    fun `deleting a dose leaves a tombstone, and undoing it takes the tombstone back`() = runTest {
        logDose(InjectionSite.ABDOMEN_LEFT, daysAgo = 0)
        val dose = medication.observeDoses().first().single()

        val undo = medication.deleteDose(dose.id)

        val tombstones = database.deletionDao().all().filter {
            it.kind == SyncKind.MEDICATION_DOSE.name
        }
        assertThat(tombstones).hasSize(1)

        undo?.undo()
        assertThat(medication.observeDoses().first()).hasSize(1)
        assertThat(
            database.deletionDao().all().filter { it.kind == SyncKind.MEDICATION_DOSE.name },
        ).isEmpty()
    }

    @Test
    fun `deleting a dose puts its site back in the rotation`() = runTest {
        logDose(InjectionSite.ABDOMEN_LEFT, daysAgo = 7)
        logDose(InjectionSite.THIGH_RIGHT, daysAgo = 0)
        val newest = medication.observeDoses().first().first()

        medication.deleteDose(newest.id)

        // Worked out again from what is stored rather than kept as a pointer, so removing the
        // dose that moved it also moves it back.
        assertThat(medication.suggestedSite()).isEqualTo(InjectionSite.THIGH_RIGHT)
    }

    @Test
    fun `deleting a side effect leaves a tombstone`() = runTest {
        medication.addSideEffect(SideEffectKind.NAUSEA, SideEffectSeverity.MODERATE, at)
        val effect = medication.observeSideEffects().first().single()

        medication.deleteSideEffect(effect.id)

        assertThat(
            database.deletionDao().all().filter { it.kind == SyncKind.SIDE_EFFECT.name },
        ).hasSize(1)
    }

    @Test
    fun `a dose of nothing is not a dose`() = runTest {
        assertThat(medication.addDose(GlpDrug.SEMAGLUTIDE, 0.0, InjectionSite.THIGH_LEFT, at))
            .isEqualTo(-1)
        assertThat(medication.observeDoses().first()).isEmpty()
    }
}
