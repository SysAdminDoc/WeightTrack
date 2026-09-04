package com.weighttrack.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.sync.SyncKind
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.testSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The travelling name every person needs, including the one the app makes for itself.
 *
 * A fresh install's first profile is written straight into the table by the database's create
 * step, which cannot give each row a different value and so left the name blank. Nothing else
 * ever filled it in: `ensureDefault` returns early because a row already exists, and the
 * migration that names rows only runs on an upgrade. A profile with no name is invisible to
 * sync, so deleting that person recorded no tombstone, the other phone had no reason to drop
 * them, and they came back as an empty profile nobody could remove.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileNamingTest {

    private lateinit var database: WeightTrackDatabase
    private lateinit var profiles: ProfileRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()
        profiles = ProfileRepository(
            database.profileDao(),
            testSettingsRepository(),
            DeletionRecorder(database, database.deletionDao(), database.syncDao()),
            database.weightEntryDao(),
        )
    }

    @After
    fun tearDown() = database.close()

    /** Exactly what the database's own create step writes on a fresh install. */
    private fun insertUnnamedProfile(id: Long, name: String) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT OR IGNORE INTO profiles (id, name, position, createdAtUtcMillis) " +
                "VALUES ($id, '$name', 0, 0)",
        )
    }

    @Test
    fun `a profile created without a name is given one`() = runTest {
        insertUnnamedProfile(1, "Me")
        assertThat(database.profileDao().all().single().syncId).isEmpty()

        val named = profiles.nameTheUnnamed()

        assertThat(named).isEqualTo(1)
        assertThat(database.profileDao().all().single().syncId).isNotEmpty()
    }

    @Test
    fun `two of them do not end up sharing a name`() = runTest {
        // A shared name would be worse than none: the merge would treat two people as one.
        insertUnnamedProfile(1, "Me")
        insertUnnamedProfile(2, "Them")

        profiles.nameTheUnnamed()

        val names = database.profileDao().all().map { it.syncId }
        assertThat(names.toSet()).hasSize(2)
        assertThat(names.none { it.isEmpty() }).isTrue()
    }

    @Test
    fun `a profile that already has a name keeps it`() = runTest {
        profiles.ensureDefault()
        val before = database.profileDao().all().single().syncId

        assertThat(profiles.nameTheUnnamed()).isEqualTo(0)
        assertThat(database.profileDao().all().single().syncId).isEqualTo(before)
    }

    @Test
    fun `deleting a named profile is remembered, so the other phone drops them too`() = runTest {
        insertUnnamedProfile(1, "Me")
        val second = profiles.add("Them")
        profiles.nameTheUnnamed()

        profiles.deleteReturningPhotos(1)

        val remembered = database.deletionDao().all().filter { it.kind == SyncKind.PROFILE.name }
        assertThat(remembered).hasSize(1)
        assertThat(remembered.single().syncId).isNotEmpty()
        assertThat(database.profileDao().all().map { it.id }).containsExactly(second)
    }
}
