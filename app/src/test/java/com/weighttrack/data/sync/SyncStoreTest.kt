package com.weighttrack.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.sync.SyncDeletion
import com.weighttrack.core.sync.SyncDocument
import com.weighttrack.core.sync.SyncKind
import com.weighttrack.core.sync.SyncMerge
import com.weighttrack.data.db.DeletionEntity
import com.weighttrack.data.db.FastEntity
import com.weighttrack.data.db.GoalEntity
import com.weighttrack.data.db.MacroTargetEntity
import com.weighttrack.data.db.MeasurementEntity
import com.weighttrack.data.db.ProfileEntity
import com.weighttrack.data.db.WaterEntryEntity
import com.weighttrack.data.db.WeightEntryEntity
import com.weighttrack.data.db.WeightTrackDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Two databases standing in for two phones.
 *
 * Everything here is the part that can lose somebody's history: a merge applied to real rows,
 * with real unique indexes and a real schema underneath it. The merge itself is tested on its own
 * in the core module; this is about what happens when its answer meets a database.
 */
@RunWith(RobolectricTestRunner::class)
class SyncStoreTest {

    private lateinit var phone: WeightTrackDatabase
    private lateinit var tablet: WeightTrackDatabase
    private lateinit var phoneStore: SyncStore
    private lateinit var tabletStore: SyncStore

    private val now = 1_800_000_000_000L

    private fun database(): WeightTrackDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        WeightTrackDatabase::class.java,
    ).allowMainThreadQueries().build()

    @Before
    fun setUp() {
        phone = database()
        tablet = database()
        phoneStore = SyncStore(phone, phone.syncDao(), phone.deletionDao())
        tabletStore = SyncStore(tablet, tablet.syncDao(), tablet.deletionDao())
    }

    @After
    fun tearDown() {
        phone.close()
        tablet.close()
    }

    private suspend fun seedProfile(
        db: WeightTrackDatabase,
        syncId: String = "p1",
        name: String = "Me",
        updatedAt: Long = now - 10_000,
    ): Long = db.syncDao().insertProfile(
        ProfileEntity(
            name = name,
            position = 0,
            createdAtUtcMillis = now - 100_000,
            syncId = syncId,
            updatedAtUtcMillis = updatedAt,
        ),
    )

    private fun weight(
        profileId: Long,
        recordId: String,
        grams: Int,
        updatedAt: Long = now - 10_000,
        healthConnectId: String? = null,
    ) = WeightEntryEntity(
        profileId = profileId,
        timestampUtcMillis = now - 50_000,
        zoneOffsetSeconds = 0,
        localDate = "2026-08-29",
        grams = grams,
        bodyFatPercent = null,
        note = null,
        tags = "",
        source = "MANUAL",
        clientRecordId = recordId,
        healthConnectId = healthConnectId,
        updatedAtUtcMillis = updatedAt,
    )

    /** One round of syncing between the two, in the direction given. */
    private suspend fun sync(
        from: SyncStore,
        fromId: String,
        to: SyncStore,
        toId: String,
    ): SyncChanges {
        val documents = listOf(from.snapshot(fromId, now), to.snapshot(toId, now))
        return to.apply(SyncMerge.merge(documents, toId, now), now)
    }

    @Test
    fun `a carried measurement is still carried on the other phone`() = runTest {
        // A value carried forward is a fact about the last time somebody got the tape out.
        // Dropped in transit, the receiving phone records it as measured, and since the sending
        // phone never rewrites its own row the two disagree for good.
        val profileId = seedProfile(phone)
        phone.syncDao().insertMeasurements(
            listOf(
                MeasurementEntity(
                    profileId = profileId,
                    timestampUtcMillis = now,
                    localDate = "2026-08-30",
                    type = "CHEST",
                    valueMm = 1_020,
                    note = null,
                    carried = true,
                    updatedAtUtcMillis = now,
                    syncId = "m-carried",
                ),
                MeasurementEntity(
                    profileId = profileId,
                    timestampUtcMillis = now,
                    localDate = "2026-08-30",
                    type = "WAIST",
                    valueMm = 865,
                    note = null,
                    carried = false,
                    updatedAtUtcMillis = now,
                    syncId = "m-measured",
                ),
            ),
        )

        sync(from = phoneStore, fromId = "aaa", to = tabletStore, toId = "bbb")

        val landed = tablet.syncDao().measurements().associateBy { it.syncId }
        assertThat(landed.getValue("m-carried").carried).isTrue()
        assertThat(landed.getValue("m-measured").carried).isFalse()
    }
    @Test
    fun `a fresh device ends up with everything`() = runTest {
        val profileId = seedProfile(phone)
        phone.syncDao().insertWeights(
            listOf(weight(profileId, "w1", 80_000), weight(profileId, "w2", 79_500)),
        )
        phone.syncDao().insertMeasurements(
            listOf(
                MeasurementEntity(
                    profileId = profileId,
                    timestampUtcMillis = now,
                    localDate = "2026-08-29",
                    type = "WAIST",
                    valueMm = 900,
                    note = null,
                    updatedAtUtcMillis = now,
                    syncId = "m1",
                ),
            ),
        )
        phone.syncDao().insertWater(
            listOf(
                WaterEntryEntity(
                    profileId = profileId,
                    timestampUtcMillis = now,
                    localDate = "2026-08-29",
                    millilitres = 250,
                    healthConnectId = null,
                    updatedAtUtcMillis = now,
                    syncId = "h1",
                ),
            ),
        )

        val changes = sync(phoneStore, "aaa", tabletStore, "bbb")

        assertThat(tablet.syncDao().profiles()).hasSize(1)
        assertThat(tablet.syncDao().weights().map { it.grams }).containsExactly(80_000, 79_500)
        assertThat(tablet.syncDao().measurements()).hasSize(1)
        assertThat(tablet.syncDao().water()).hasSize(1)
        assertThat(changes.added).isAtLeast(4)
    }

    @Test
    fun `syncing again changes nothing`() = runTest {
        val profileId = seedProfile(phone)
        phone.syncDao().insertWeights(listOf(weight(profileId, "w1", 80_000)))
        sync(phoneStore, "aaa", tabletStore, "bbb")

        val second = sync(phoneStore, "aaa", tabletStore, "bbb")

        // The thing that makes sync liveable. A second pass that keeps finding work to do is one
        // that will keep finding it forever.
        assertThat(second.touched).isEqualTo(0)
        assertThat(tablet.syncDao().weights()).hasSize(1)
    }

    @Test
    fun `a correction made on the other device wins`() = runTest {
        val phoneProfile = seedProfile(phone)
        phone.syncDao().insertWeights(listOf(weight(phoneProfile, "w1", 80_000)))
        sync(phoneStore, "aaa", tabletStore, "bbb")

        // Corrected on the tablet, later.
        val onTablet = tablet.syncDao().weights().single()
        tablet.syncDao().updateWeights(
            listOf(onTablet.copy(grams = 81_000, updatedAtUtcMillis = now + 1_000)),
        )
        sync(tabletStore, "bbb", phoneStore, "aaa")

        assertThat(phone.syncDao().weights().single().grams).isEqualTo(81_000)
    }

    @Test
    fun `an older copy does not undo a newer one`() = runTest {
        val phoneProfile = seedProfile(phone)
        phone.syncDao().insertWeights(
            listOf(weight(phoneProfile, "w1", 81_000, updatedAt = now + 1_000)),
        )
        val tabletProfile = seedProfile(tablet)
        tablet.syncDao().insertWeights(
            listOf(weight(tabletProfile, "w1", 80_000, updatedAt = now - 50_000)),
        )

        sync(tabletStore, "bbb", phoneStore, "aaa")

        assertThat(phone.syncDao().weights().single().grams).isEqualTo(81_000)
    }

    @Test
    fun `a deletion travels and does not come back`() = runTest {
        val profileId = seedProfile(phone)
        phone.syncDao().insertWeights(listOf(weight(profileId, "w1", 80_000)))
        sync(phoneStore, "aaa", tabletStore, "bbb")
        assertThat(tablet.syncDao().weights()).hasSize(1)

        // Deleted on the phone, with a tombstone the way the repositories write one.
        phone.syncDao().deleteWeights(profileId, listOf("w1"))
        phone.deletionDao().record(DeletionEntity("WEIGHT", "w1", now + 1_000))

        sync(phoneStore, "aaa", tabletStore, "bbb")
        assertThat(tablet.syncDao().weights()).isEmpty()

        // And the tablet does not hand it straight back on the next pass.
        sync(tabletStore, "bbb", phoneStore, "aaa")
        assertThat(phone.syncDao().weights()).isEmpty()
    }

    @Test
    fun `a reading corrected after being deleted comes back`() = runTest {
        val profileId = seedProfile(phone)
        phone.syncDao().insertWeights(listOf(weight(profileId, "w1", 80_000)))
        sync(phoneStore, "aaa", tabletStore, "bbb")

        // Deleted on the phone, then corrected on the tablet afterwards.
        phone.syncDao().deleteWeights(profileId, listOf("w1"))
        phone.deletionDao().record(DeletionEntity("WEIGHT", "w1", now))
        val onTablet = tablet.syncDao().weights().single()
        tablet.syncDao().updateWeights(
            listOf(onTablet.copy(grams = 81_000, updatedAtUtcMillis = now + 5_000)),
        )

        sync(tabletStore, "bbb", phoneStore, "aaa")

        assertThat(phone.syncDao().weights().single().grams).isEqualTo(81_000)
    }

    @Test
    fun `the last profile is never deleted`() = runTest {
        seedProfile(phone)
        // Another device says the only profile is gone. Doing as it asks would leave the app with
        // nowhere to put a reading and no screen that can draw.
        val merged = SyncDocument(
            deviceId = "bbb",
            writtenAtUtcMillis = now,
            deletions = listOf(SyncDeletion(SyncKind.PROFILE, "p1", now + 1_000)),
        )

        phoneStore.apply(merged, now)

        assertThat(phone.syncDao().profiles()).hasSize(1)
    }

    @Test
    fun `deleting a profile takes its readings with it`() = runTest {
        val first = seedProfile(phone, syncId = "p1")
        val second = seedProfile(phone, syncId = "p2", name = "Them")
        phone.syncDao().insertWeights(
            listOf(weight(first, "w1", 80_000), weight(second, "w2", 60_000)),
        )
        val merged = SyncDocument(
            deviceId = "bbb",
            writtenAtUtcMillis = now,
            deletions = listOf(SyncDeletion(SyncKind.PROFILE, "p2", now + 1_000)),
        )

        phoneStore.apply(merged, now)

        // Nothing cascades in this schema, so rows left behind would sit there invisible and
        // unreachable for the life of the install.
        assertThat(phone.syncDao().profiles().map { it.syncId }).containsExactly("p1")
        assertThat(phone.syncDao().weights().map { it.clientRecordId }).containsExactly("w1")
    }

    @Test
    fun `a Health Connect identifier is never carried to another phone`() = runTest {
        val profileId = seedProfile(phone)
        phone.syncDao().insertWeights(
            listOf(weight(profileId, "w1", 80_000, healthConnectId = "weight:phone-only")),
        )

        sync(phoneStore, "aaa", tabletStore, "bbb")

        // It names a record inside one phone's Health Connect. Copying it would make the tablet
        // believe it had already written a weight it never wrote.
        assertThat(tablet.syncDao().weights().single().healthConnectId).isNull()
    }

    @Test
    fun `a target arriving for a day that already has one replaces it`() = runTest {
        val phoneProfile = seedProfile(phone, syncId = "p1")
        val tabletProfile = seedProfile(tablet, syncId = "p1")
        // The same day set up separately on each device, so the two rows carry different names.
        phone.syncDao().insertMacroTargets(
            listOf(macro(phoneProfile, "t-phone", "SATURDAY", 2_000.0, now)),
        )
        tablet.syncDao().insertMacroTargets(
            listOf(macro(tabletProfile, "t-tablet", "SATURDAY", 2_400.0, now + 1_000)),
        )

        // Only one row per profile and day is allowed, so inserting beside it would break the
        // unique index and take the whole sync down.
        sync(tabletStore, "bbb", phoneStore, "aaa")

        val targets = phone.syncDao().macroTargets()
        assertThat(targets).hasSize(1)
        assertThat(targets.single().kcal).isWithin(1e-9).of(2_400.0)
    }

    @Test
    fun `readings whose profile is nowhere are counted rather than dropped quietly`() = runTest {
        seedProfile(phone, syncId = "p1")
        val merged = SyncDocument(
            deviceId = "bbb",
            writtenAtUtcMillis = now,
            profiles = phoneStore.snapshot("aaa", now).profiles,
            weights = listOf(
                com.weighttrack.core.sync.SyncWeight(
                    syncId = "orphan",
                    profileSyncId = "nobody",
                    timestampUtcMillis = now,
                    zoneOffsetSeconds = 0,
                    localDate = "2026-08-29",
                    grams = 80_000,
                    source = "MANUAL",
                    updatedAtUtcMillis = now,
                ),
            ),
        )

        val changes = phoneStore.apply(merged, now)

        assertThat(changes.orphaned).isEqualTo(1)
        // Not written under somebody else's profile, which would be worse than not writing it.
        assertThat(phone.syncDao().weights()).isEmpty()
    }

    @Test
    fun `a goal and a fast make the trip`() = runTest {
        val profileId = seedProfile(phone)
        phone.syncDao().insertGoals(
            listOf(
                GoalEntity(
                    profileId = profileId,
                    direction = "LOSE",
                    startGrams = 85_000,
                    targetGrams = 78_000,
                    startDate = "2026-08-01",
                    targetDate = null,
                    milestoneStepGrams = 2_000,
                    active = true,
                    createdAtUtcMillis = now,
                    syncId = "g1",
                    updatedAtUtcMillis = now,
                ),
            ),
        )
        phone.syncDao().insertFasts(
            listOf(
                FastEntity(
                    profileId = profileId,
                    startUtcMillis = now - 60_000,
                    endUtcMillis = null,
                    targetMinutes = 960,
                    note = null,
                    updatedAtUtcMillis = now,
                    syncId = "f1",
                ),
            ),
        )

        sync(phoneStore, "aaa", tabletStore, "bbb")

        assertThat(tablet.syncDao().goals().single().targetGrams).isEqualTo(78_000)
        assertThat(tablet.syncDao().fasts().single().targetMinutes).isEqualTo(960)
    }

    @Test
    fun `a profile arriving from elsewhere goes at the end of the list`() = runTest {
        seedProfile(phone, syncId = "p1", name = "Me")
        seedProfile(tablet, syncId = "p2", name = "Them")

        sync(tabletStore, "bbb", phoneStore, "aaa")

        val positions = phone.syncDao().profiles().map { it.position }
        // Two devices each inventing a position produce two profiles on top of each other.
        assertThat(positions.distinct()).hasSize(positions.size)
    }

    @Test
    fun `a deletion from one device reaches a third through the second`() = runTest {
        val profileId = seedProfile(phone)
        phone.syncDao().insertWeights(listOf(weight(profileId, "w1", 80_000)))
        sync(phoneStore, "aaa", tabletStore, "bbb")

        phone.syncDao().deleteWeights(profileId, listOf("w1"))
        phone.deletionDao().record(DeletionEntity("WEIGHT", "w1", now + 1_000))
        sync(phoneStore, "aaa", tabletStore, "bbb")

        // The tablet now has to be carrying the tombstone itself, or a third device that only
        // ever sees the tablet would keep the reading forever.
        assertThat(tablet.deletionDao().all().map { it.syncId }).contains("w1")
    }

    private fun macro(
        profileId: Long,
        syncId: String,
        day: String?,
        kcal: Double,
        updatedAt: Long,
    ) = MacroTargetEntity(
        profileId = profileId,
        dayOfWeek = day,
        kcal = kcal,
        proteinG = null,
        carbsG = null,
        fatG = null,
        basis = "GRAMS",
        updatedAtUtcMillis = updatedAt,
        syncId = syncId,
    )
}
