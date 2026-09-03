package com.weighttrack.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.sync.SyncMerge
import com.weighttrack.data.db.ProfileEntity
import com.weighttrack.data.db.WeightEntryEntity
import com.weighttrack.data.db.WeightTrackDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Where a row's stamp comes from, against a real database.
 *
 * Nothing in the app writes a stamp when it edits a row. The rule is that a row is stamped on the
 * way out, and that a row whose recorded time no longer matches its stamp has been edited here
 * since. These are about that rule holding through Room, because if it does not, an edit made on
 * this phone travels wearing another phone's name.
 */
@RunWith(RobolectricTestRunner::class)
class SyncStampingTest {

    private lateinit var database: WeightTrackDatabase

    private val now = 1_800_000_000_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    private fun store(clock: SyncClock = SyncClock.inMemory()) = SyncStore(
        database,
        database.syncDao(),
        database.deletionDao(),
        database.syncPeerDao(),
        database.medicationDoseDao(),
        database.sideEffectDao(),
        clock,
    )

    private suspend fun seed(updatedAt: Long): Long {
        val profileId = database.syncDao().insertProfile(
            ProfileEntity(
                name = "Me",
                position = 0,
                createdAtUtcMillis = now - 100_000,
                syncId = "p1",
                updatedAtUtcMillis = now - 100_000,
            ),
        )
        database.syncDao().insertWeights(
            listOf(
                WeightEntryEntity(
                    profileId = profileId,
                    timestampUtcMillis = now - 50_000,
                    zoneOffsetSeconds = 0,
                    localDate = "2026-08-29",
                    grams = 80_000,
                    bodyFatPercent = null,
                    note = null,
                    tags = "",
                    source = "MANUAL",
                    clientRecordId = "w1",
                    healthConnectId = null,
                    updatedAtUtcMillis = updatedAt,
                ),
            ),
        )
        return profileId
    }

    @Test
    fun `a row written with no stamp goes out in this device's name`() = runTest {
        seed(updatedAt = now - 10_000)

        val document = store().snapshot("aaa", now)

        assertThat(document.weights.single().stampDeviceId).isEqualTo("aaa")
    }

    @Test
    fun `a healthy clock leaves the recorded time exactly as it was`() = runTest {
        seed(updatedAt = now - 10_000)

        val document = store().snapshot("aaa", now)

        assertThat(document.weights.single().updatedAtUtcMillis).isEqualTo(now - 10_000)
    }

    @Test
    fun `an edit made after a clock went backwards is stamped ahead of what it corrects`() = runTest {
        // Driven through the shape the app actually produces rather than a hand-built row: the
        // reading is published once, so it is stamped, and only then does the clock go wrong.
        // The other phone's edit landed at "now"; this phone's clock then jumped an hour back,
        // so the correction made here carries an earlier time than the version it is correcting
        // and under a plain newest-wins it would lose forever.
        val clock = SyncClock.inMemory()
        seed(updatedAt = now - 7_200_000)
        val store = store(clock)
        store.snapshot("aaa", now)
        clock.observe(now, now)

        val row = database.syncDao().weights().single()
        database.syncDao().updateWeights(
            listOf(row.copy(grams = 79_000, updatedAtUtcMillis = now - 3_600_000)),
        )
        val document = store.snapshot("aaa", now - 3_600_000)

        assertThat(document.weights.single().updatedAtUtcMillis).isGreaterThan(now)
    }

    @Test
    fun `a row is stamped once and not again on every sync`() = runTest {
        seed(updatedAt = now - 10_000)
        val store = store()

        val first = store.snapshot("aaa", now)
        val second = store.snapshot("aaa", now)

        assertThat(second.weights.single()).isEqualTo(first.weights.single())
    }

    @Test
    fun `a row that arrived from another device keeps that device's name`() = runTest {
        seed(updatedAt = now - 10_000)
        val store = store()
        val mine = store.snapshot("aaa", now)
        val theirs = mine.copy(
            deviceId = "bbb",
            weights = mine.weights.map {
                it.copy(grams = 81_000, updatedAtUtcMillis = now, stampDeviceId = "bbb")
            },
        )

        store.apply(SyncMerge.merge(listOf(mine, theirs), "aaa", now), now)
        val republished = store.snapshot("aaa", now)

        assertThat(republished.weights.single().grams).isEqualTo(81_000)
        assertThat(republished.weights.single().stampDeviceId).isEqualTo("bbb")
    }

    @Test
    fun `editing a row that arrived from elsewhere makes it this device's own again`() = runTest {
        seed(updatedAt = now - 10_000)
        val store = store()
        val mine = store.snapshot("aaa", now)
        val theirs = mine.copy(
            deviceId = "bbb",
            weights = mine.weights.map {
                it.copy(grams = 81_000, updatedAtUtcMillis = now, stampDeviceId = "bbb")
            },
        )
        store.apply(SyncMerge.merge(listOf(mine, theirs), "aaa", now), now)

        // Somebody corrects it here. Nothing in the repository layer knows about stamps: it
        // writes a new time and that is all.
        val row = database.syncDao().weights().single()
        database.syncDao().updateWeights(listOf(row.copy(grams = 79_500, updatedAtUtcMillis = now + 1)))

        val republished = store.snapshot("aaa", now)

        assertThat(republished.weights.single().grams).isEqualTo(79_500)
        assertThat(republished.weights.single().stampDeviceId).isEqualTo("aaa")
    }

    @Test
    fun `restoring a backup does not throw away this phone's deletions`() = runTest {
        // A restore carries whatever deletions the archive was written with. Treated as the last
        // word they would wipe everything deleted here since, and those rows would come home
        // from the other phone on the next sync.
        seed(updatedAt = now - 10_000)
        database.deletionDao().record(
            com.weighttrack.data.db.DeletionEntity(
                kind = com.weighttrack.core.sync.SyncKind.WATER.name,
                syncId = "x1",
                deletedAtUtcMillis = now - 1000,
                profileSyncId = "p1",
            ),
        )
        val store = store()
        val archive = store.snapshot("backup", now).copy(deletions = emptyList())

        store.apply(archive, now)

        assertThat(database.deletionDao().all().map { it.syncId }).containsExactly("x1")
    }

    /**
     * The state every row is in the first time the app runs after the upgrade that added stamps:
     * a profile touched this morning, and a reading from months ago.
     */
    private suspend fun seedAsAfterTheUpgrade() {
        val profileId = database.syncDao().insertProfile(
            ProfileEntity(
                name = "Me",
                position = 0,
                createdAtUtcMillis = now - 40_000_000,
                syncId = "p1",
                updatedAtUtcMillis = now - 1_000,
            ),
        )
        database.syncDao().insertWeights(
            listOf(
                WeightEntryEntity(
                    profileId = profileId,
                    timestampUtcMillis = now - 10_000_000,
                    zoneOffsetSeconds = 0,
                    localDate = "2026-08-01",
                    grams = 80_000,
                    bodyFatPercent = null,
                    note = null,
                    tags = "",
                    source = "MANUAL",
                    clientRecordId = "w1",
                    healthConnectId = null,
                    updatedAtUtcMillis = now - 10_000_000,
                ),
            ),
        )
    }

    @Test
    fun `an upgrade does not drag an old reading's time up to today`() = runTest {
        // Every row is unstamped on the first run after the upgrade, and they are stamped in
        // whatever order the tables are walked. Insisting each one beat the clock meant the
        // profile touched this morning pulled every reading behind it forward to now.
        seedAsAfterTheUpgrade()

        val document = store().snapshot("aaa", now)

        assertThat(document.weights.single().updatedAtUtcMillis).isEqualTo(now - 10_000_000)
    }

    @Test
    fun `a deletion still beats a reading the upgrade has just stamped`() = runTest {
        // What the bug above actually cost: a reading deleted on the other phone last week came
        // back, because this phone had just re-dated it to today.
        seedAsAfterTheUpgrade()
        val store = store()
        val mine = store.snapshot("aaa", now)
        val theirs = mine.copy(
            deviceId = "bbb",
            weights = emptyList(),
            deletions = listOf(
                com.weighttrack.core.sync.SyncDeletion(
                    kind = com.weighttrack.core.sync.SyncKind.WEIGHT,
                    syncId = "w1",
                    deletedAtUtcMillis = now - 5_000_000,
                    profileSyncId = "p1",
                    stampDeviceId = "bbb",
                ),
            ),
        )

        assertThat(SyncMerge.merge(listOf(mine, theirs), "aaa", now).weights).isEmpty()
    }

    @Test
    fun `taking a backup does not put its own name on this phone's rows`() = runTest {
        // A backup is a photograph of this phone rather than this phone speaking, so it stamps
        // nothing. Stamping there named every unstamped row after a device called "backup" that
        // has never existed, and that name then travelled and broke ties against real phones.
        seedAsAfterTheUpgrade()
        val store = store()

        store.snapshot("backup", now, publishing = false)
        val published = store.snapshot("aaa", now)

        assertThat(published.weights.single().stampDeviceId).isEqualTo("aaa")
    }

    @Test
    fun `a correction made after the clock was put right still wins`() = runTest {
        // The phone thought it was ten days later than it was. Believing that time would put the
        // row past the furthest the clock will ever follow, so nothing stamped afterwards could
        // beat it and the correction would lose for good.
        val profileId = database.syncDao().insertProfile(
            ProfileEntity(
                name = "Me",
                position = 0,
                createdAtUtcMillis = now - 100_000,
                syncId = "p1",
                updatedAtUtcMillis = now - 100_000,
            ),
        )
        val tenDays = 10L * 24 * 60 * 60 * 1000
        database.syncDao().insertWeights(
            listOf(
                WeightEntryEntity(
                    profileId = profileId,
                    timestampUtcMillis = now,
                    zoneOffsetSeconds = 0,
                    localDate = "2026-09-01",
                    grams = 80_000,
                    bodyFatPercent = null,
                    note = null,
                    tags = "",
                    source = "MANUAL",
                    clientRecordId = "w1",
                    healthConnectId = null,
                    updatedAtUtcMillis = now + tenDays,
                ),
            ),
        )
        val store = store()
        val first = store.snapshot("aaa", now).weights.single()

        val row = database.syncDao().weights().single()
        database.syncDao().updateWeights(
            listOf(row.copy(grams = 79_000, updatedAtUtcMillis = now + 2_000)),
        )
        val second = store.snapshot("aaa", now).weights.single()

        assertThat(second.updatedAtUtcMillis).isGreaterThan(first.updatedAtUtcMillis)
    }

    @Test
    fun `a device this one has synced with is remembered`() = runTest {
        seed(updatedAt = now - 10_000)
        val store = store()
        val mine = store.snapshot("aaa", now)
        val theirs = mine.copy(deviceId = "bbb")

        store.apply(SyncMerge.merge(listOf(mine, theirs), "aaa", now), now)

        assertThat(store.snapshot("aaa", now).peers.map { it.deviceId })
            .containsExactly("aaa", "bbb")
    }
}
