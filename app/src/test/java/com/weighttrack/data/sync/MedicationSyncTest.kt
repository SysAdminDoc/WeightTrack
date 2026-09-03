package com.weighttrack.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.medication.GlpDrug
import com.weighttrack.core.medication.InjectionSite
import com.weighttrack.core.medication.SideEffectKind
import com.weighttrack.core.medication.SideEffectSeverity
import com.weighttrack.core.sync.SyncDocument
import com.weighttrack.core.sync.SyncKind
import com.weighttrack.core.sync.SyncMerge
import com.weighttrack.data.db.DeletionEntity
import com.weighttrack.data.db.ProfileEntity
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.repo.DeletionRecorder
import com.weighttrack.data.repo.MedicationRepository
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
 * The injection log travelling between two phones.
 *
 * A new table has more carriers than the entity: the document, the merge, the snapshot, the
 * apply, and the tombstones. A field that survives one of those and not the rest looks perfectly
 * correct on the phone that wrote it, and this is the only place the rest shows up.
 */
@RunWith(RobolectricTestRunner::class)
class MedicationSyncTest {

    private lateinit var phone: WeightTrackDatabase
    private lateinit var tablet: WeightTrackDatabase
    private lateinit var phoneStore: SyncStore
    private lateinit var tabletStore: SyncStore

    // The real clock, because the repositories stamp a row with it and a fixed future date
    // would make every tombstone look older than the row it is about.
    private val now = System.currentTimeMillis()
    private val at = Instant.ofEpochMilli(now)

    private fun database(): WeightTrackDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        WeightTrackDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun store(db: WeightTrackDatabase) = SyncStore(
        db,
        db.syncDao(),
        db.deletionDao(),
        db.syncPeerDao(),
        db.medicationDoseDao(),
        db.sideEffectDao(),
        SyncClock.inMemory(),
    )

    private fun medication(db: WeightTrackDatabase) = MedicationRepository(
        db.medicationDoseDao(),
        db.sideEffectDao(),
        testProfileRepository(db),
        DeletionRecorder(db, db.deletionDao(), db.syncDao()),
    )

    @Before
    fun setUp() {
        phone = database()
        tablet = database()
        phoneStore = store(phone)
        tabletStore = store(tablet)
    }

    @After
    fun tearDown() {
        phone.close()
        tablet.close()
    }

    private suspend fun seedProfile(db: WeightTrackDatabase) = db.syncDao().insertProfile(
        ProfileEntity(
            name = "Me",
            position = 0,
            createdAtUtcMillis = now - 100_000,
            syncId = "p1",
            updatedAtUtcMillis = now - 10_000,
        ),
    )

    private suspend fun sync(from: SyncStore, fromId: String, to: SyncStore, toId: String) {
        val documents = listOf(from.snapshot(fromId, now), to.snapshot(toId, now))
        to.apply(SyncMerge.merge(documents, toId, now), now, replaceDeletions = true)
    }

    @Test
    fun `a dose reaches the other device with everything on it`() = runTest {
        seedProfile(phone)
        seedProfile(tablet)
        medication(phone).addDose(
            drug = GlpDrug.TIRZEPATIDE,
            milligrams = 7.5,
            site = InjectionSite.THIGH_RIGHT,
            timestamp = at,
            note = "second pen",
        )

        sync(phoneStore, "aaa", tabletStore, "bbb")

        val arrived = medication(tablet).observeDoses().first().single()
        assertThat(arrived.drug).isEqualTo(GlpDrug.TIRZEPATIDE)
        assertThat(arrived.milligrams).isEqualTo(7.5)
        assertThat(arrived.site).isEqualTo(InjectionSite.THIGH_RIGHT)
        assertThat(arrived.note).isEqualTo("second pen")
        assertThat(arrived.timestamp).isEqualTo(at)
    }

    @Test
    fun `a side effect reaches the other device`() = runTest {
        seedProfile(phone)
        seedProfile(tablet)
        medication(phone).addSideEffect(SideEffectKind.VOMITING, SideEffectSeverity.SEVERE, at)

        sync(phoneStore, "aaa", tabletStore, "bbb")

        val arrived = medication(tablet).observeSideEffects().first().single()
        assertThat(arrived.kind).isEqualTo(SideEffectKind.VOMITING)
        assertThat(arrived.severity).isEqualTo(SideEffectSeverity.SEVERE)
    }

    @Test
    fun `deleting a dose takes it off the other device too`() = runTest {
        seedProfile(phone)
        seedProfile(tablet)
        medication(phone).addDose(GlpDrug.SEMAGLUTIDE, 0.5, InjectionSite.ABDOMEN_LEFT, at)
        sync(phoneStore, "aaa", tabletStore, "bbb")
        assertThat(medication(tablet).observeDoses().first()).hasSize(1)

        val dose = medication(phone).observeDoses().first().single()
        medication(phone).deleteDose(dose.id)
        sync(phoneStore, "aaa", tabletStore, "bbb")

        // Without the tombstone the tablet still holds the row, has no reason to drop it, and
        // hands it straight back on the next pass.
        assertThat(medication(tablet).observeDoses().first()).isEmpty()
    }

    @Test
    fun `a correction made after a delete brings the dose back`() = runTest {
        seedProfile(phone)
        seedProfile(tablet)
        medication(phone).addDose(GlpDrug.SEMAGLUTIDE, 0.5, InjectionSite.ABDOMEN_LEFT, at)
        val document = phoneStore.snapshot("aaa", now)
        val dose = document.medicationDoses.single()

        // Deleted on one phone, edited afterwards on the other. What somebody who corrected a
        // mistyped dose actually meant.
        val deleted = SyncDocument(
            deviceId = "bbb",
            writtenAtUtcMillis = now,
            profiles = document.profiles,
            deletions = listOf(
                com.weighttrack.core.sync.SyncDeletion(
                    kind = SyncKind.MEDICATION_DOSE,
                    syncId = dose.syncId,
                    deletedAtUtcMillis = now,
                    profileSyncId = "p1",
                    stampDeviceId = "bbb",
                ),
            ),
        )
        val corrected = document.copy(
            medicationDoses = listOf(
                dose.copy(milligrams = 1.0, updatedAtUtcMillis = now + 1, stampDeviceId = "aaa"),
            ),
        )

        val merged = SyncMerge.merge(listOf(corrected, deleted), "aaa", now)

        assertThat(merged.medicationDoses.single().milligrams).isEqualTo(1.0)
    }

    @Test
    fun `deleting a profile here takes its injection log with it`() = runTest {
        // Nothing cascades in this schema, so rows belonging to a deleted person sit there
        // invisible and unreachable, and the other phone goes on offering them.
        seedProfile(phone)
        val second = phone.syncDao().insertProfile(
            ProfileEntity(
                name = "Them",
                position = 1,
                createdAtUtcMillis = now - 100_000,
                syncId = "p2",
                updatedAtUtcMillis = now - 10_000,
            ),
        )
        medication(phone).addDose(GlpDrug.SEMAGLUTIDE, 0.5, InjectionSite.ABDOMEN_LEFT, at)
        val doomed = phone.profileDao().all().first { it.syncId == "p1" }

        phone.profileDao().deleteWithData(doomed)

        assertThat(phone.medicationDoseDao().all()).isEmpty()
        assertThat(second).isGreaterThan(0L)
    }

    @Test
    fun `a profile deleted on the other phone takes its injection log with it`() = runTest {
        seedProfile(phone)
        phone.syncDao().insertProfile(
            ProfileEntity(
                name = "Them",
                position = 1,
                createdAtUtcMillis = now - 100_000,
                syncId = "p2",
                updatedAtUtcMillis = now - 10_000,
            ),
        )
        medication(phone).addDose(GlpDrug.SEMAGLUTIDE, 0.5, InjectionSite.ABDOMEN_LEFT, at)
        val mine = phoneStore.snapshot("aaa", now)
        val theirs = mine.copy(
            profiles = mine.profiles.filterNot { it.syncId == "p1" },
            medicationDoses = emptyList(),
            deletions = listOf(
                com.weighttrack.core.sync.SyncDeletion(
                    kind = SyncKind.PROFILE,
                    syncId = "p1",
                    deletedAtUtcMillis = System.currentTimeMillis() + 10_000,
                    stampDeviceId = "bbb",
                ),
            ),
        )

        phoneStore.apply(theirs, now, replaceDeletions = true)

        assertThat(phone.medicationDoseDao().all()).isEmpty()
    }

    @Test
    fun `a backup written here restores the injection log`() = runTest {
        seedProfile(phone)
        medication(phone).addDose(GlpDrug.LIRAGLUTIDE, 1.8, InjectionSite.UPPER_ARM_LEFT, at)
        medication(phone).addSideEffect(SideEffectKind.HEADACHE, SideEffectSeverity.MILD, at)

        val archive = SyncDocument.decode(
            SyncDocument.encode(phoneStore.snapshot("backup", now)),
        )!!
        tabletStore.apply(archive, now)

        assertThat(medication(tablet).observeDoses().first()).hasSize(1)
        assertThat(medication(tablet).observeSideEffects().first()).hasSize(1)
    }

    @Test
    fun `a tombstone with no device on it still removes the row`() = runTest {
        // What a file written by a version before stamps existed carries, and what this phone
        // writes locally before a sync has stamped it.
        seedProfile(phone)
        medication(phone).addDose(GlpDrug.SEMAGLUTIDE, 0.5, InjectionSite.ABDOMEN_LEFT, at)
        val dose = phone.medicationDoseDao().all().single()
        phone.deletionDao().record(
            DeletionEntity(
                kind = SyncKind.MEDICATION_DOSE.name,
                syncId = dose.syncId,
                deletedAtUtcMillis = System.currentTimeMillis() + 10_000,
                profileSyncId = "p1",
            ),
        )

        val merged = SyncMerge.merge(listOf(phoneStore.snapshot("aaa", now)), "aaa", now)
        phoneStore.apply(merged, now, replaceDeletions = true)

        assertThat(phone.medicationDoseDao().all()).isEmpty()
    }
}
