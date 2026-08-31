package com.weighttrack.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.core.sync.SyncDocument
import com.weighttrack.core.sync.SyncProfile
import com.weighttrack.core.sync.SyncWeight
import com.weighttrack.data.InMemoryPreferences
import com.weighttrack.data.db.ProfileEntity
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.testSecretStore
import com.weighttrack.diagnostics.RuntimeLog
import com.weighttrack.ui.AppStrings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import com.weighttrack.core.sync.SyncSettings as SyncedSettings

/**
 * What a sync leaves behind when it cannot finish.
 *
 * A merge touches eleven tables and a preferences file. Applied one write at a time, a failure
 * halfway through left a database no single writer could have produced, and this device then
 * republished that half to everybody else as though it were the answer. The rows go in as one
 * commit; the settings, which live outside the database and cannot join it, are covered by a
 * note that survives the process.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineCommitTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var database: WeightTrackDatabase
    private lateinit var preferences: SyncPreferences
    private lateinit var settings: SettingsRepository
    private lateinit var target: RecordingTarget

    private val now = 1_800_000_000_000L

    /** Accepts every write and keeps none of them. */
    private class ForgetfulPreferences : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
        private val state = kotlinx.coroutines.flow.MutableStateFlow(
            androidx.datastore.preferences.core.emptyPreferences(),
        )
        override val data = state
        override suspend fun updateData(
            transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences,
        ) = transform(state.value)
    }

    /** Refuses to write weigh-ins. Everything else answers as the real one does. */
    private class RefusingSyncDao(
        private val real: com.weighttrack.data.db.SyncDao,
    ) : com.weighttrack.data.db.SyncDao by real {
        override suspend fun insertWeights(rows: List<com.weighttrack.data.db.WeightEntryEntity>) =
            error("the table would not take them")
    }

    /** A folder in memory, which also remembers whether this device ever published. */
    private class RecordingTarget(private val files: MutableMap<String, String>) : SyncTarget {
        override val describe = "a folder in memory"
        var published: String? = null

        override suspend fun list(): SyncOutcome<List<String>> = SyncOutcome.Ok(files.keys.toList())

        override suspend fun read(name: String): SyncOutcome<String?> = SyncOutcome.Ok(files[name])

        override suspend fun write(name: String, content: String): SyncOutcome<Unit> {
            published = content
            files[name] = content
            return SyncOutcome.Ok(Unit)
        }
    }

    private fun engineWith(
        peer: SyncDocument,
        settingsRepository: SettingsRepository = settings,
        dao: com.weighttrack.data.db.SyncDao = database.syncDao(),
    ): SyncEngine {
        target = RecordingTarget(
            mutableMapOf(SyncDocument.fileName("peer") to SyncDocument.encode(peer)),
        )
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return SyncEngine(
            context = context,
            strings = AppStrings(context),
            preferences = preferences,
            store = SyncStore(database, dao, database.deletionDao()),
            settingsRepository = settingsRepository,
            runtimeLog = RuntimeLog(File(temporary.newFolder(), "log.txt")),
            targets = object : SyncTargets(context, AppStrings(context), RuntimeLog(File(temporary.newFolder(), "log.txt"))) {
                override fun forSettings(settings: SyncSettings) = target
            },
        )
    }

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()
        preferences = SyncPreferences(InMemoryPreferences(), testSecretStore())
        preferences.useFolder("content://somewhere")
        settings = SettingsRepository(InMemoryPreferences(), testSecretStore())
        database.syncDao().insertProfile(
            ProfileEntity(
                name = "Me",
                position = 0,
                createdAtUtcMillis = now - 100_000,
                syncId = "p-me",
                updatedAtUtcMillis = now - 10_000,
            ),
        )
    }

    @After
    fun tearDown() = database.close()

    private fun weight(name: String, grams: Int) = SyncWeight(
        syncId = name,
        profileSyncId = "p-me",
        timestampUtcMillis = now - 50_000,
        zoneOffsetSeconds = 0,
        localDate = "2026-08-29",
        grams = grams,
        source = "MANUAL",
        updatedAtUtcMillis = now - 1_000,
    )

    private fun peerDocument(
        weights: List<SyncWeight>,
        settings: SyncedSettings? = null,
        extraProfile: Boolean = false,
    ) = SyncDocument(
        deviceId = "peer",
        writtenAtUtcMillis = now - 1_000,
        profiles = listOfNotNull(
            SyncProfile(
                syncId = "p-me",
                name = "Me",
                position = 0,
                createdAtUtcMillis = now - 100_000,
                updatedAtUtcMillis = now - 10_000,
            ),
            SyncProfile(
                syncId = "p-them",
                name = "Them",
                position = 1,
                createdAtUtcMillis = now - 90_000,
                updatedAtUtcMillis = now - 10_000,
            ).takeIf { extraProfile },
        ),
        weights = weights,
        settings = settings,
    )

    private val theirs = SyncedSettings(
        weightUnit = "LB",
        lengthUnit = "IN",
        themeMode = "AMOLED",
        heightMm = 1_803,
        sex = "FEMALE",
        birthYear = 1988,
        activityLevel = "ACTIVE",
        trendWindowDays = 21,
        milestoneStepGrams = 2_500,
        updatedAtUtcMillis = now,
    )

    @Test
    fun `a merge that cannot be written leaves nothing behind and publishes nothing`() = runTest {
        // The peer brings a second person and a weigh-in. The weigh-ins are refused, and by then
        // the profile has already been written.
        val engine = engineWith(
            peerDocument(listOf(weight("w-1", 80_000)), extraProfile = true),
            dao = RefusingSyncDao(database.syncDao()),
        )

        val result = engine.syncNow(now)

        assertThat(result).isInstanceOf(SyncResult.Unreachable::class.java)
        assertThat(database.syncDao().weights()).isEmpty()
        assertThat(database.syncDao().profiles().map { it.syncId }).containsExactly("p-me")
        // Republishing a half-applied merge would hand that half to every other device as
        // though this one had accepted the whole of it.
        assertThat(target.published).isNull()
    }

    @Test
    fun `a peer sending something absurd is skipped and everything else still syncs`() = runTest {
        // A note the length of a book, which is cheap to build and refused for the same reason
        // a quarter of a million readings would be: nobody wrote it.
        val absurd = peerDocument(
            listOf(
                weight("w-1", 80_000)
                    .copy(note = "x".repeat(com.weighttrack.core.sync.SyncBudget.MAX_STRING + 1)),
            ),
        )
        val engine = engineWith(absurd)

        val result = engine.syncNow(now)

        // Skipped rather than fatal, and skipped rather than applied. One device sending
        // something nobody could have written must not stop the phone syncing with the rest.
        assertThat(result).isInstanceOf(SyncResult.Done::class.java)
        assertThat(database.syncDao().weights()).isEmpty()
        assertThat(target.published).isNotNull()
    }

    @Test
    fun `a peer sending an ordinary document is not skipped`() = runTest {
        val engine = engineWith(peerDocument(listOf(weight("w-1", 80_000))))

        engine.syncNow(now)

        assertThat(database.syncDao().weights()).hasSize(1)
    }

    @Test
    fun `a merge that lands is published and leaves no note behind`() = runTest {
        val engine = engineWith(peerDocument(listOf(weight("w-1", 80_000)), settings = theirs))

        val result = engine.syncNow(now)

        assertThat(result).isInstanceOf(SyncResult.Done::class.java)
        assertThat(database.syncDao().weights()).hasSize(1)
        assertThat(target.published).isNotNull()
        assertThat(settings.settings.first().weightUnit).isEqualTo(WeightUnit.LB)
        assertThat(preferences.pendingSettings()).isNull()
    }

    @Test
    fun `settings that could not be written leave a note`() = runTest {
        // A preferences file whose writes are lost, standing in for the process dying between
        // the database commit and the settings landing on disk.
        val refusing = SettingsRepository(ForgetfulPreferences(), testSecretStore())
        val engine = engineWith(
            peerDocument(listOf(weight("w-1", 80_000)), settings = theirs),
            settingsRepository = refusing,
        )

        engine.syncNow(now)

        // The rows are in, so the document must never be applied again for the settings' sake.
        // The note is what carries the unfinished half to the next run instead.
        assertThat(database.syncDao().weights()).hasSize(1)
        assertThat(preferences.pendingSettings()).isNotNull()
    }

    @Test
    fun `a note left by an earlier run is finished on the next sync`() = runTest {
        preferences.setPendingSettings(
            SyncDocument.json.encodeToString(SyncedSettings.serializer(), theirs),
        )
        val engine = engineWith(peerDocument(emptyList()))

        engine.syncNow(now)

        assertThat(settings.settings.first().weightUnit).isEqualTo(WeightUnit.LB)
        assertThat(preferences.pendingSettings()).isNull()
    }

    @Test
    fun `a note nothing can read is thrown away rather than retried for ever`() = runTest {
        preferences.setPendingSettings("not settings at all")
        val engine = engineWith(peerDocument(emptyList()))

        engine.syncNow(now)

        assertThat(preferences.pendingSettings()).isNull()
    }
}
