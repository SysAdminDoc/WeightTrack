package com.weighttrack.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.sync.SyncMerge
import com.weighttrack.data.db.FastEntity
import com.weighttrack.data.db.GoalEntity
import com.weighttrack.data.db.MacroTargetEntity
import com.weighttrack.data.db.ProfileEntity
import com.weighttrack.data.db.WeightEntryEntity
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.repo.DeletionRecorder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The ways sync was found to lose or resurrect somebody's data.
 *
 * Every one of these went red before the fix it covers. They are kept together because they are
 * one story: a record only travels if it has a name that means the same thing on both devices, an
 * edit only travels if something says it happened, and a deletion only travels if something
 * remembers it.
 */
@RunWith(RobolectricTestRunner::class)
class SyncRegressionTest {

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

    private suspend fun profile(
        db: WeightTrackDatabase,
        syncId: String = "p1",
        name: String = "Me",
        position: Int = 0,
    ): Long = db.syncDao().insertProfile(
        ProfileEntity(
            name = name,
            position = position,
            createdAtUtcMillis = now - 100_000,
            syncId = syncId,
            updatedAtUtcMillis = now - 10_000,
        ),
    )

    private fun weight(profileId: Long, recordId: String, grams: Int, updatedAt: Long = now - 10_000) =
        WeightEntryEntity(
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
            healthConnectId = null,
            updatedAtUtcMillis = updatedAt,
        )

    private suspend fun sync(from: SyncStore, fromId: String, to: SyncStore, toId: String): SyncChanges {
        val documents = listOf(from.snapshot(fromId, now), to.snapshot(toId, now))
        return to.apply(SyncMerge.merge(documents, toId, now), now)
    }

    // ---- one person's rows are not another's ----

    @Test
    fun `two people with the same record name keep their own readings`() {
        // The same backup restored for two people, or the same file imported twice, gives both
        // of them rows called the same thing: the identifier is only unique within a profile.
        // Keyed on the name alone, one person's correction lands on the other person's morning.
        runTest {
            val me = profile(phone, "p1", "Me", position = 0)
            val them = profile(phone, "p2", "Them", position = 1)
            phone.syncDao().insertWeights(
                listOf(weight(me, "shared", 79_000), weight(them, "shared", 62_000)),
            )
            profile(tablet, "p1", "Me", position = 0)
            profile(tablet, "p2", "Them", position = 1)

            sync(phoneStore, "aaa", tabletStore, "bbb")

            val byProfile = tablet.syncDao().weights().associate { it.profileId to it.grams }
            assertThat(byProfile.values).containsExactly(79_000, 62_000)
        }
    }

    @Test
    fun `deleting one person's reading leaves the other person's alone`() = runTest {
        val me = profile(phone, "p1", "Me", position = 0)
        val them = profile(phone, "p2", "Them", position = 1)
        phone.syncDao().insertWeights(
            listOf(weight(me, "shared", 79_000), weight(them, "shared", 62_000)),
        )
        val recorder = DeletionRecorder(phone.deletionDao(), phone.syncDao())
        // Deleted for one of them only.
        phone.syncDao().deleteWeights(me, listOf("shared"))
        recorder.record(com.weighttrack.core.sync.SyncKind.WEIGHT, "shared", now)

        assertThat(phone.syncDao().weights().map { it.grams }).containsExactly(62_000)
    }

    // ---- deletions that have to travel ----

    @Test
    fun `deleting a profile takes its history with it and it stays gone`() = runTest {
        val me = profile(phone, "p1", "Me", position = 0)
        val them = profile(phone, "p2", "Them", position = 1)
        phone.syncDao().insertWeights(
            listOf(weight(me, "w1", 79_000), weight(them, "w2", 62_000)),
        )
        sync(phoneStore, "aaa", tabletStore, "bbb")
        assertThat(tablet.syncDao().profiles()).hasSize(2)

        // Deleted the way the repository does it: the profile and everything it owned.
        val recorder = DeletionRecorder(phone.deletionDao(), phone.syncDao())
        val owned = recorder.namesOwnedBy(them)
        phone.syncDao().deleteWeights(them, listOf("w2"))
        phone.syncDao().deleteProfiles(listOf("p2"))
        recorder.record(com.weighttrack.core.sync.SyncKind.PROFILE, "p2", now + 1_000)
        owned.forEach { (kind, names) -> recorder.record(kind, names, now + 1_000) }

        sync(phoneStore, "aaa", tabletStore, "bbb")

        assertThat(tablet.syncDao().profiles().map { it.syncId }).containsExactly("p1")
        // The person's readings go too. One tombstone for the profile is not enough: the tablet
        // still holds their weigh-ins and would hand the whole history straight back.
        assertThat(tablet.syncDao().weights().map { it.clientRecordId }).containsExactly("w1")
    }

    @Test
    fun `a profile that only one device deleted does not come back`() = runTest {
        profile(phone, "p1", "Me", position = 0)
        val them = profile(phone, "p2", "Them", position = 1)
        sync(phoneStore, "aaa", tabletStore, "bbb")

        val recorder = DeletionRecorder(phone.deletionDao(), phone.syncDao())
        phone.syncDao().deleteProfiles(listOf("p2"))
        recorder.record(com.weighttrack.core.sync.SyncKind.PROFILE, "p2", now + 1_000)
        sync(phoneStore, "aaa", tabletStore, "bbb")

        // And the tablet does not push it back on the return trip.
        sync(tabletStore, "bbb", phoneStore, "aaa")

        assertThat(phone.syncDao().profiles().map { it.syncId }).containsExactly("p1")
        assertThat(them).isGreaterThan(0L)
    }

    // ---- edits that have to travel ----

    @Test
    fun `renaming a profile reaches the other device`() = runTest {
        profile(phone, "p1", "Me")
        sync(phoneStore, "aaa", tabletStore, "bbb")

        val existing = phone.syncDao().profiles().single()
        phone.syncDao().updateProfile(
            existing.copy(name = "Alex", updatedAtUtcMillis = now + 1_000),
        )
        sync(phoneStore, "aaa", tabletStore, "bbb")

        assertThat(tablet.syncDao().profiles().single().name).isEqualTo("Alex")
    }

    @Test
    fun `a reminder set on one device reaches the other`() = runTest {
        profile(phone, "p1", "Me")
        sync(phoneStore, "aaa", tabletStore, "bbb")

        val existing = phone.syncDao().profiles().single()
        phone.syncDao().updateProfile(
            existing.copy(
                reminderEnabled = true,
                reminderHour = 6,
                updatedAtUtcMillis = now + 1_000,
            ),
        )
        sync(phoneStore, "aaa", tabletStore, "bbb")

        val there = tablet.syncDao().profiles().single()
        assertThat(there.reminderEnabled).isTrue()
        assertThat(there.reminderHour).isEqualTo(6)
    }

    @Test
    fun `retiring a goal reaches the other device`() = runTest {
        val me = profile(phone, "p1")
        phone.syncDao().insertGoals(listOf(goal(me, "g1", active = true, updatedAt = now - 5_000)))
        sync(phoneStore, "aaa", tabletStore, "bbb")
        assertThat(tablet.syncDao().goals().single().active).isTrue()

        // Retired the way the DAO does it now, with a time on it.
        phone.goalDao().deactivateAll(me, now + 1_000)
        sync(phoneStore, "aaa", tabletStore, "bbb")

        assertThat(tablet.syncDao().goals().single().active).isFalse()
    }

    // ---- ties ----

    @Test
    fun `an edit made in the same millisecond settles the same way on both devices`() = runTest {
        val onPhone = profile(phone, "p1")
        val onTablet = profile(tablet, "p1")
        phone.syncDao().insertWeights(listOf(weight(onPhone, "w1", 80_000, updatedAt = now)))
        tablet.syncDao().insertWeights(listOf(weight(onTablet, "w1", 79_000, updatedAt = now)))

        sync(tabletStore, "bbb", phoneStore, "aaa")
        sync(phoneStore, "aaa", tabletStore, "bbb")

        // The merge breaks the tie on the device identifier. The store used to disagree with it
        // and keep its own, so the two argued forever.
        assertThat(phone.syncDao().weights().single().grams)
            .isEqualTo(tablet.syncDao().weights().single().grams)
    }

    // ---- the unique index on macro targets ----

    @Test
    fun `two devices setting the same weekday do not break a third`() = runTest {
        val onPhone = profile(phone, "p1")
        val onTablet = profile(tablet, "p1")
        phone.syncDao().insertMacroTargets(
            listOf(macro(onPhone, "t-phone", "MONDAY", 2_000.0, now)),
        )
        tablet.syncDao().insertMacroTargets(
            listOf(macro(onTablet, "t-tablet", "MONDAY", 2_400.0, now + 1_000)),
        )

        // A third device with no Monday row at all, which is where this used to throw: the table
        // allows one row per profile and day, and both were inserted.
        val third = database()
        try {
            val thirdStore = SyncStore(third, third.syncDao(), third.deletionDao())
            third.syncDao().insertProfile(
                ProfileEntity(
                    name = "Me",
                    position = 0,
                    createdAtUtcMillis = now - 100_000,
                    syncId = "p1",
                    updatedAtUtcMillis = now - 10_000,
                ),
            )
            val documents = listOf(
                phoneStore.snapshot("aaa", now),
                tabletStore.snapshot("bbb", now),
                thirdStore.snapshot("ccc", now),
            )

            thirdStore.apply(SyncMerge.merge(documents, "ccc", now), now)

            val targets = third.syncDao().macroTargets()
            assertThat(targets).hasSize(1)
            assertThat(targets.single().kcal).isWithin(1e-9).of(2_400.0)
        } finally {
            third.close()
        }
    }

    // ---- two devices each deleting a different profile ----

    @Test
    fun `deleting one profile on each device still settles on the same answer`() = runTest {
        profile(phone, "p1", "Me", position = 0)
        profile(phone, "p2", "Them", position = 1)
        profile(tablet, "p1", "Me", position = 0)
        profile(tablet, "p2", "Them", position = 1)

        val onPhone = DeletionRecorder(phone.deletionDao(), phone.syncDao())
        phone.syncDao().deleteProfiles(listOf("p1"))
        onPhone.record(com.weighttrack.core.sync.SyncKind.PROFILE, "p1", now + 1_000)

        val onTablet = DeletionRecorder(tablet.deletionDao(), tablet.syncDao())
        tablet.syncDao().deleteProfiles(listOf("p2"))
        onTablet.record(com.weighttrack.core.sync.SyncKind.PROFILE, "p2", now + 1_000)

        // Both refuse to delete their last profile, which used to leave them holding different
        // ones forever. The survivor is brought back to life instead, later than the tombstone
        // that buried it, so the next pass agrees.
        repeat(3) {
            sync(phoneStore, "aaa", tabletStore, "bbb")
            sync(tabletStore, "bbb", phoneStore, "aaa")
        }

        assertThat(phone.syncDao().profiles().map { it.syncId }.sorted())
            .isEqualTo(tablet.syncDao().profiles().map { it.syncId }.sorted())
        assertThat(phone.syncDao().profiles()).isNotEmpty()
    }

    @Test
    fun `a fast cancelled by mistake stays cancelled`() = runTest {
        val me = profile(phone, "p1")
        phone.syncDao().insertFasts(
            listOf(
                FastEntity(
                    profileId = me,
                    startUtcMillis = now - 60_000,
                    endUtcMillis = null,
                    targetMinutes = 960,
                    note = null,
                    updatedAtUtcMillis = now - 10_000,
                    syncId = "f1",
                ),
            ),
        )
        sync(phoneStore, "aaa", tabletStore, "bbb")

        val recorder = DeletionRecorder(phone.deletionDao(), phone.syncDao())
        phone.syncDao().deleteFasts(listOf("f1"))
        recorder.record(com.weighttrack.core.sync.SyncKind.FAST, "f1", now + 1_000)
        sync(phoneStore, "aaa", tabletStore, "bbb")

        // Otherwise the app tells somebody they are still fasting.
        assertThat(tablet.syncDao().fasts()).isEmpty()
    }

    private fun goal(profileId: Long, syncId: String, active: Boolean, updatedAt: Long) = GoalEntity(
        profileId = profileId,
        direction = "LOSE",
        startGrams = 85_000,
        targetGrams = 78_000,
        startDate = "2026-08-01",
        targetDate = null,
        milestoneStepGrams = 2_000,
        active = active,
        createdAtUtcMillis = now - 100_000,
        syncId = syncId,
        updatedAtUtcMillis = updatedAt,
    )

    private fun macro(profileId: Long, syncId: String, day: String?, kcal: Double, updatedAt: Long) =
        MacroTargetEntity(
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
