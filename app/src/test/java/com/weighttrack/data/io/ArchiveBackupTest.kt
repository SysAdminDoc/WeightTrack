package com.weighttrack.data.io

import com.weighttrack.data.sync.SyncClock
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.DeletionRecorder
import com.weighttrack.data.repo.GoalRepository
import com.weighttrack.data.repo.MeasurementRepository
import com.weighttrack.data.repo.ProfileRepository
import com.weighttrack.data.repo.ProgressPhotoRepository
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.data.sync.SyncStore
import com.weighttrack.data.testSettingsRepository
import com.weighttrack.diagnostics.RuntimeLog
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
import java.time.Instant

/**
 * The archive end to end: a phone with pictures on it, a file, and a phone without them.
 *
 * [ArchiveCodecTest] proves the format. This proves the app puts the right things in it and puts
 * them back where they belong, which is the part a correct format is useless without.
 */
@RunWith(RobolectricTestRunner::class)
class ArchiveBackupTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val password = "a good long password".toCharArray()

    private lateinit var source: WeightTrackDatabase
    private lateinit var target: WeightTrackDatabase

    private fun database(): WeightTrackDatabase = Room.inMemoryDatabaseBuilder(
        context,
        WeightTrackDatabase::class.java,
    ).allowMainThreadQueries().build()

    @Before
    fun setUp() {
        source = database()
        target = database()
        photoDirectory().deleteRecursively()
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
        photoDirectory().deleteRecursively()
    }

    private fun photoDirectory() =
        File(context.filesDir, ProgressPhotoRepository.DIRECTORY_NAME)

    private class Rig(
        val service: BackupService,
        val photos: ProgressPhotoRepository,
        val profiles: ProfileRepository,
    )

    private fun rig(
        database: WeightTrackDatabase,
        settings: SettingsRepository = testSettingsRepository(),
    ): Rig {
        val deletions = DeletionRecorder(database, database.deletionDao(), database.syncDao())
        val profiles = ProfileRepository(
            database.profileDao(),
            settings,
            deletions,
            database.weightEntryDao(),
        )
        val photos = ProgressPhotoRepository(
            context,
            database.progressPhotoDao(),
            profiles,
            RuntimeLog(temporary.newFile()),
        )
        return Rig(
            BackupService(
                syncStore = SyncStore(database, database.syncDao(), database.deletionDao(), database.syncPeerDao(), SyncClock.inMemory()),
                context = context,
                weightRepository = WeightRepository(database.weightEntryDao(), profiles, deletions),
                measurementRepository = MeasurementRepository(
                    database.measurementDao(),
                    profiles,
                    deletions,
                ),
                goalRepository = GoalRepository(database.goalDao(), profiles, deletions),
                profileRepository = profiles,
                settingsRepository = settings,
                database = database,
                progressPhotoRepository = photos,
            ),
            photos,
            profiles,
        )
    }

    private suspend fun seed(rig: Rig, photoBytes: ByteArray): String {
        rig.profiles.ensureDefault()
        val weights = WeightRepository(
            source.weightEntryDao(),
            rig.profiles,
            DeletionRecorder(source, source.deletionDao(), source.syncDao()),
        )
        weights.add(grams = 82_400, timestamp = Instant.ofEpochMilli(1_800_000_000_000))
        val file = rig.photos.newCaptureFile().apply { writeBytes(photoBytes) }
        rig.photos.record(file, weightGrams = 82_400, timestamp = Instant.ofEpochMilli(1_800_000_000_000))
        return file.name
    }

    @Test
    fun `an archive carries the pictures and puts them back byte for byte`() = runTest {
        val bytes = ByteArray(150_000) { (it % 251).toByte() }
        val from = rig(source)
        val name = seed(from, bytes)
        val file = temporary.newFile("archive.wtarchive")

        val summary = from.service.exportArchive(Uri.fromFile(file), password.copyOf()).getOrThrow()
        assertThat(summary.photos).isEqualTo(1)

        // The new phone: no rows, and no pictures on disk either.
        photoDirectory().deleteRecursively()
        val to = rig(target)
        to.profiles.ensureDefault()

        to.service.importArchive(Uri.fromFile(file), password.copyOf()).getOrThrow()

        val restored = to.photos.observeAll().first().single()
        assertThat(restored.file.readBytes()).isEqualTo(bytes)
        assertThat(restored.file.name).isEqualTo(name)
        assertThat(restored.weightGrams).isEqualTo(82_400)
    }

    @Test
    fun `the wrong password restores nothing at all`() = runTest {
        val from = rig(source)
        seed(from, ByteArray(1_000) { 7 })
        val file = temporary.newFile("archive.wtarchive")
        from.service.exportArchive(Uri.fromFile(file), password.copyOf()).getOrThrow()

        photoDirectory().deleteRecursively()
        val to = rig(target)
        to.profiles.ensureDefault()

        val result = to.service.importArchive(Uri.fromFile(file), "not it at all".toCharArray())

        assertThat(result.isFailure).isTrue()
        assertThat(to.photos.observeAll().first()).isEmpty()
        assertThat(target.syncDao().weights()).isEmpty()
        assertThat(photoDirectory().listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `a changed archive restores nothing at all`() = runTest {
        val from = rig(source)
        seed(from, ByteArray(80_000) { (it % 97).toByte() })
        val file = temporary.newFile("archive.wtarchive")
        from.service.exportArchive(Uri.fromFile(file), password.copyOf()).getOrThrow()
        // Late in the file, past the header, so the slot still opens.
        val bytes = file.readBytes()
        bytes[bytes.size - 500] = (bytes[bytes.size - 500] + 1).toByte()
        file.writeBytes(bytes)

        photoDirectory().deleteRecursively()
        val to = rig(target)
        to.profiles.ensureDefault()

        val result = to.service.importArchive(Uri.fromFile(file), password.copyOf())

        assertThat(result.isFailure).isTrue()
        assertThat(target.syncDao().weights()).isEmpty()
        assertThat(photoDirectory().listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `no credential ever reaches an archive`() = runTest {
        val secret = "hunter2-webdav-password"
        val settings = testSettingsRepository()
        settings.setUsdaApiKey(secret)
        val from = rig(source, settings)
        seed(from, ByteArray(500) { 3 })
        val file = temporary.newFile("archive.wtarchive")

        from.service.exportArchive(Uri.fromFile(file), password.copyOf()).getOrThrow()

        // Read back through the codec, because the file itself is ciphertext and finding nothing
        // in it would prove only that it was encrypted.
        val backup = StringBuilder()
        ArchiveCodec.read(file.inputStream(), password.copyOf()) { name, _ ->
            if (name == ArchiveCodec.BACKUP_ENTRY) {
                object : java.io.OutputStream() {
                    override fun write(b: Int) {
                        backup.append(b.toChar())
                    }
                }
            } else {
                java.io.ByteArrayOutputStream()
            }
        }
        assertThat(backup.toString()).doesNotContain(secret)
        // The unit is a setting, not a secret, and it has to travel or a restored phone reads
        // everybody's weights in the wrong ones.
        assertThat(backup.toString()).contains("weightUnit")
    }

    @Test
    fun `a photo row whose file has gone is left out rather than promised`() = runTest {
        val from = rig(source)
        val name = seed(from, ByteArray(400) { 5 })
        // The row survives, the picture does not. An archive that named it would restore a row
        // pointing at nothing on the new phone.
        File(photoDirectory(), name).delete()
        val file = temporary.newFile("archive.wtarchive")

        val summary = from.service.exportArchive(Uri.fromFile(file), password.copyOf()).getOrThrow()

        assertThat(summary.photos).isEqualTo(0)
    }

    @Test
    fun `restoring the same archive twice leaves one row per picture`() = runTest {
        val from = rig(source)
        seed(from, ByteArray(2_000) { 9 })
        val file = temporary.newFile("archive.wtarchive")
        from.service.exportArchive(Uri.fromFile(file), password.copyOf()).getOrThrow()

        photoDirectory().deleteRecursively()
        val to = rig(target)
        to.profiles.ensureDefault()

        to.service.importArchive(Uri.fromFile(file), password.copyOf()).getOrThrow()
        to.service.importArchive(Uri.fromFile(file), password.copyOf()).getOrThrow()

        assertThat(to.photos.observeAll().first()).hasSize(1)
    }

    @Test
    fun `a json export still says the pictures are not in it`() = runTest {
        val from = rig(source)
        seed(from, ByteArray(300) { 2 })

        val text = from.service.exportedJson().getOrThrow()

        val backup = BackupCodec.decode(text)!!
        assertThat(backup.progressPhotos).isEqualTo(BackupCodec.PHOTOS_NOT_INCLUDED)
        assertThat(backup.photoRows).isNull()
    }

    @Test
    fun `restoring a photo entity that is not a photo entry writes nothing outside the folder`() =
        runTest {
            // The writer would never make this file. Somebody else's would.
            val file = temporary.newFile("hostile.wtarchive")
            val json = BackupCodec.encode(
                BackupFile(exportedAtUtcMillis = 1, photoRows = emptyList()),
            ).toByteArray()
            val escape = "escaped".toByteArray()
            ArchiveCodec.write(
                file.outputStream(),
                password.copyOf(),
                listOf(
                    ArchiveEntry(ArchiveCodec.BACKUP_ENTRY, json.size.toLong()) {
                        java.io.ByteArrayInputStream(json)
                    },
                    ArchiveEntry("../escaped.txt", escape.size.toLong()) {
                        java.io.ByteArrayInputStream(escape)
                    },
                ),
            )

            val to = rig(target)
            to.profiles.ensureDefault()
            val result = to.service.importArchive(Uri.fromFile(file), password.copyOf())

            assertThat(result.isFailure).isTrue()
            assertThat(File(context.cacheDir.parentFile, "escaped.txt").exists()).isFalse()
            assertThat(File(photoDirectory().parentFile, "escaped.txt").exists()).isFalse()
        }

    @Test
    fun `a photo row lands on the person it belonged to`() = runTest {
        val from = rig(source)
        from.profiles.ensureDefault()
        val other = from.profiles.add("Someone else")
        val bytes = ByteArray(1_500) { 11 }
        val file = from.photos.newCaptureFile().apply { writeBytes(bytes) }
        from.photos.record(file, weightGrams = null, timestamp = Instant.ofEpochMilli(1_800_000_000_000))
        // Recorded against whoever was active, which `add` switched to.
        assertThat(source.progressPhotoDao().all().single().profileId).isEqualTo(other)

        val archive = temporary.newFile("archive.wtarchive")
        from.service.exportArchive(Uri.fromFile(archive), password.copyOf()).getOrThrow()

        photoDirectory().deleteRecursively()
        val to = rig(target)
        to.profiles.ensureDefault()
        to.service.importArchive(Uri.fromFile(archive), password.copyOf()).getOrThrow()

        val restoredOwner = target.progressPhotoDao().all().single().profileId
        val ownerName = target.profileDao().byId(restoredOwner)?.name
        assertThat(ownerName).isEqualTo("Someone else")
    }

    @Test
    fun `a picture called backup json does not overwrite the export beside it`() = runTest {
        // The two names live in one archive and a photograph may legitimately be called this.
        // Unpacked into one folder they collided, and the picture truncated the export.
        val json = BackupCodec.encode(
            BackupFile(
                exportedAtUtcMillis = 1,
                photoRows = listOf(
                    BackupPhoto(
                        profileSyncId = "",
                        timestampUtcMillis = 1_800_000_000_000,
                        localDate = "2026-08-31",
                        fileName = "backup.json",
                    ),
                ),
            ),
        ).toByteArray()
        val picture = ByteArray(900) { 42 }
        val file = temporary.newFile("collision.wtarchive")
        ArchiveCodec.write(
            file.outputStream(),
            password.copyOf(),
            listOf(
                ArchiveEntry(ArchiveCodec.BACKUP_ENTRY, json.size.toLong()) {
                    java.io.ByteArrayInputStream(json)
                },
                ArchiveEntry(ArchiveCodec.PHOTO_PREFIX + "backup.json", picture.size.toLong()) {
                    java.io.ByteArrayInputStream(picture)
                },
            ),
        )

        val to = rig(target)
        to.profiles.ensureDefault()
        to.service.importArchive(Uri.fromFile(file), password.copyOf()).getOrThrow()

        assertThat(to.photos.observeAll().first().single().file.readBytes()).isEqualTo(picture)
    }

    @Test
    fun `two people with the same name keep their own photographs`() = runTest {
        val from = rig(source)
        from.profiles.ensureDefault()
        val first = from.profiles.activeId()
        from.profiles.rename(first, "Sam")
        val second = from.profiles.add("Sam")
        // One picture each, under two profiles sharing a display name.
        from.profiles.setActive(first)
        val hers = ByteArray(700) { 1 }
        from.photos.record(
            from.photos.newCaptureFile().apply { writeBytes(hers) },
            weightGrams = null,
            timestamp = Instant.ofEpochMilli(1_800_000_000_000),
        )
        from.profiles.setActive(second)
        val his = ByteArray(700) { 2 }
        from.photos.record(
            from.photos.newCaptureFile().apply { writeBytes(his) },
            weightGrams = null,
            timestamp = Instant.ofEpochMilli(1_800_000_100_000),
        )
        val archive = temporary.newFile("household.wtarchive")
        from.service.exportArchive(Uri.fromFile(archive), password.copyOf()).getOrThrow()

        photoDirectory().deleteRecursively()
        val to = rig(target)
        to.profiles.ensureDefault()
        to.service.importArchive(Uri.fromFile(archive), password.copyOf()).getOrThrow()

        // Resolved by the profile's own travelling name. Through the display name, both
        // pictures landed on whichever Sam the map happened to keep.
        val owners = target.progressPhotoDao().all().map { it.profileId }.toSet()
        assertThat(owners).hasSize(2)
    }

    @Test
    fun `a photo row naming a path outside the folder is ignored`() = runTest {
        val escape = java.io.File(context.filesDir, "escaped.jpg")
        escape.delete()
        val json = BackupCodec.encode(
            BackupFile(
                exportedAtUtcMillis = 1,
                photoRows = listOf(
                    BackupPhoto(
                        profileSyncId = "",
                        timestampUtcMillis = 1_800_000_000_000,
                        localDate = "2026-08-31",
                        // Never checked by the codec: entry names are, row names were not.
                        fileName = "../escaped.jpg",
                    ),
                ),
            ),
        ).toByteArray()
        val file = temporary.newFile("traversal.wtarchive")
        ArchiveCodec.write(
            file.outputStream(),
            password.copyOf(),
            listOf(
                ArchiveEntry(ArchiveCodec.BACKUP_ENTRY, json.size.toLong()) {
                    java.io.ByteArrayInputStream(json)
                },
            ),
        )

        val to = rig(target)
        to.profiles.ensureDefault()
        to.service.importArchive(Uri.fromFile(file), password.copyOf()).getOrThrow()

        assertThat(escape.exists()).isFalse()
        assertThat(target.progressPhotoDao().all()).isEmpty()
    }

    @Test
    fun `a restore that cannot place a picture leaves the phone exactly as it was`() = runTest {
        val from = rig(source)
        val name = seed(from, ByteArray(1_200) { 6 })
        val file = temporary.newFile("archive.wtarchive")
        from.service.exportArchive(Uri.fromFile(file), password.copyOf()).getOrThrow()

        photoDirectory().deleteRecursively()
        val to = rig(target)
        to.profiles.ensureDefault()
        // An ordinary file standing where the photo folder has to be, so nothing can be written
        // inside it and the copy throws partway through what used to be an already-committed
        // restore.
        photoDirectory().writeBytes(byteArrayOf(1))
        assertThat(name).isNotEmpty()

        val result = to.service.importArchive(Uri.fromFile(file), password.copyOf())

        assertThat(result.isFailure).isTrue()
        assertThat(target.syncDao().weights()).isEmpty()
        assertThat(target.progressPhotoDao().all()).isEmpty()
        photoDirectory().delete()
    }
}