package com.weighttrack.data.repo

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.weighttrack.data.db.ProgressPhotoDao
import com.weighttrack.data.db.ProgressPhotoEntity
import com.weighttrack.diagnostics.LogArea
import com.weighttrack.diagnostics.LogEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What happened to a photo somebody tried to keep.
 *
 * Capture, copy, decode and database failures all used to collapse into null. From the screen
 * that is a picture that simply did not appear, with nothing to read and nothing to do about it:
 * a gallery grant that lapsed, a file that is not an image, a full phone and a refused write all
 * looked exactly the same and none of them looked like anything.
 */
sealed interface PhotoOutcome {
    data class Saved(val photo: ProgressPhoto) : PhotoOutcome

    data class Failed(val problem: Problem) : PhotoOutcome

    /** Why it did not work, in the terms somebody can act on. */
    enum class Problem {
        /** The image could not be opened. A gallery grant that has already lapsed does this. */
        UNREADABLE,

        /** It opened, and what came out is not a picture. */
        NOT_AN_IMAGE,

        /** There is no room on the phone for it. */
        NO_ROOM,

        /** It was copied and the database would not have it. */
        NOT_SAVED,

        /** The camera said it took one and the file is not there, or is empty. */
        GONE,
    }
}

data class ProgressPhoto(
    val id: Long,
    val timestamp: Instant,
    val localDate: LocalDate,
    val file: File,
    val weightGrams: Int?,
    val note: String?,
)

/**
 * Progress photos, kept in app-private storage.
 *
 * The picked image is copied in rather than referenced. A content URI from the gallery is a
 * temporary grant: keeping only the URI means the photo silently stops loading later, and it
 * would also leave the picture outside the app where the app lock cannot cover it.
 */
@Singleton
/** Scoped to the active profile, which it asks for rather than being told. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressPhotoRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: ProgressPhotoDao,
    private val profiles: ProfileRepository,
    private val runtimeLog: com.weighttrack.diagnostics.RuntimeLog,
) {
    private val directory: File
        get() = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }

    /**
     * Where a deleted image waits while its undo is on screen.
     *
     * A picture cannot be put back from a row alone, so the file is moved aside rather than
     * unlinked. It is a sibling of the photo directory, not a child of it, because everything
     * that walks the photos does so by listing that folder and would count these as live.
     */
    private val recoveryDirectory: File
        get() = File(context.filesDir, RECOVERY_DIRECTORY_NAME).apply { mkdirs() }

    fun observeAll(): Flow<List<ProgressPhoto>> =
        profiles.activeProfileId.flatMapLatest { dao.observeAll(it) }
            .map { rows -> rows.mapNotNull { it.toDomain() } }
            .flowOn(Dispatchers.IO)

    fun observeCount(): Flow<Int> =
        profiles.activeProfileId.flatMapLatest { dao.observeCount(it) }

    /**
     * When a picked image was actually taken, or null when nothing says.
     *
     * A gallery import filed under today is wrong for the one thing progress photos are for:
     * a picture from March belongs in March, next to the weight from March. EXIF first, because
     * it travels with the file, then the media store, which knows about pictures that arrived
     * without EXIF.
     */
    suspend fun takenAt(
        source: Uri,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): Instant? = withContext(Dispatchers.IO) {
        val fromExif = plausibleCaptureTime(
            runCatching {
                context.contentResolver.openInputStream(source)?.use { exifTakenAt(it, zone) }
            }.getOrNull(),
            now,
        )
        // A camera clock set to 1970 fails the plausibility check but the gallery may still know
        // when the file arrived, so an unusable EXIF date falls through rather than ending here.
        fromExif ?: plausibleCaptureTime(mediaStoreTakenAt(source), now)
    }

    private fun exifTakenAt(stream: InputStream, zone: ZoneId): Instant? {
        val exif = runCatching { ExifInterface(stream) }.getOrNull() ?: return null
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        return parseExifDateTime(raw, zone)
    }

    private fun mediaStoreTakenAt(source: Uri): Instant? = runCatching {
        // Not every provider carries this column, and asking one that does not throws.
        context.contentResolver.query(
            source,
            arrayOf(MediaStore.Images.Media.DATE_TAKEN),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return@use null
            Instant.ofEpochMilli(cursor.getLong(0))
        }
    }.getOrNull()

    /** Where a camera capture should write, created empty and ready. */
    fun newCaptureFile(): File = File(directory, "photo-${UUID.randomUUID()}.jpg")

    /**
     * Copies an image into private storage and records it.
     *
     * Returns null when the source cannot be read, which happens if the grant has already
     * lapsed by the time this runs.
     */
    suspend fun importFrom(
        source: Uri,
        weightGrams: Int?,
        timestamp: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): PhotoOutcome = withContext(Dispatchers.IO) {
        val target = File(directory, "photo-${UUID.randomUUID()}.jpg")
        val copy = runCatching {
            context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("nothing to read")
        }
        copy.exceptionOrNull()?.let { failure ->
            // Nothing half-copied is left where a later pass could mistake it for a photo.
            target.delete()
            return@withContext failed(
                if (outOfRoom(failure)) {
                    PhotoOutcome.Problem.NO_ROOM
                } else {
                    PhotoOutcome.Problem.UNREADABLE
                },
            )
        }
        if (target.length() == 0L) {
            target.delete()
            return@withContext failed(PhotoOutcome.Problem.UNREADABLE)
        }
        // Opened, copied, and not a picture. A file picker will hand over anything at all, and a
        // row pointing at something that cannot be drawn is a permanent blank in the grid.
        if (!isAnImage(target)) {
            target.delete()
            return@withContext failed(PhotoOutcome.Problem.NOT_AN_IMAGE)
        }
        record(target, weightGrams, timestamp, zone)
    }

    /** Whether what was copied is something that can actually be drawn. */
    private fun isAnImage(file: File): Boolean {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { android.graphics.BitmapFactory.decodeFile(file.path, bounds) }
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    /** Whether a failure is the phone being full rather than the file being wrong. */
    private fun outOfRoom(failure: Throwable): Boolean {
        val words = (failure.message.orEmpty() + " " + failure.javaClass.name).lowercase()
        return words.contains("enospc") || words.contains("no space") ||
            words.contains("insufficient")
    }

    private fun failed(problem: PhotoOutcome.Problem): PhotoOutcome {
        // Recorded, because every one of these used to be a photo that simply did not appear.
        runtimeLog.write(LogArea.DATA, LogEvent.PHOTO_FAILED, code = problem.ordinal)
        return PhotoOutcome.Failed(problem)
    }

    /** Records a file already written into the photo directory, as the camera does. */
    suspend fun record(
        file: File,
        weightGrams: Int?,
        timestamp: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): PhotoOutcome = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() == 0L) {
            file.delete()
            return@withContext failed(PhotoOutcome.Problem.GONE)
        }
        val entity = ProgressPhotoEntity(
            profileId = profiles.activeId(),
            timestampUtcMillis = timestamp.toEpochMilli(),
            localDate = timestamp.atZone(zone).toLocalDate().toString(),
            fileName = file.name,
            weightGrams = weightGrams,
            note = null,
        )
        val id = runCatching { dao.insert(entity) }.getOrDefault(-1L)
        if (id <= 0) {
            // No row, so no file either: an image nothing points at is an orphan nobody will
            // ever find or clear.
            file.delete()
            return@withContext failed(PhotoOutcome.Problem.NOT_SAVED)
        }
        val saved = entity.copy(id = id).toDomain()
        if (saved == null) {
            // Committed and then unreadable: the file went between the check above and here.
            // Reporting a failure while leaving the row behind would make a retry produce two
            // pictures, one of which points at nothing.
            runCatching { dao.byId(id)?.let { dao.delete(it) } }
            file.delete()
            return@withContext failed(PhotoOutcome.Problem.NOT_SAVED)
        }
        PhotoOutcome.Saved(saved)
    }

    /**
     * Deletes the row and moves the image aside, so no orphan file is left behind and the undo
     * still has something to put back.
     *
     * The row goes first. A file moved with the row still pointing at it renders as a blank tile,
     * and this way round the worst case is a picture nothing refers to, which the sweep collects.
     */
    suspend fun delete(photo: ProgressPhoto): UndoableDelete? = withContext(Dispatchers.IO) {
        val row = dao.byId(photo.id) ?: return@withContext null
        dao.delete(row)
        val held = holdForUndo(listOf(row.fileName))
        UndoableDelete(release = { releaseHeld(held) }) {
            withContext(Dispatchers.IO) {
                // The file first. A row whose image is not back yet reads as a photo that has
                // gone, and the screen simply does not list it.
                returnFromUndo(held)
                dao.insert(row)
            }
        }
    }

    /**
     * Moves images out of the photo directory and hands back where they went.
     *
     * A rename within the same filesystem, so a phone with no room left can still delete. A file
     * that would not move is deleted instead: leaving it would show a picture the person has
     * been told is gone.
     */
    suspend fun holdForUndo(fileNames: List<String>): List<File> = withContext(Dispatchers.IO) {
        sweepAbandonedRecovery()
        fileNames.mapNotNull { name ->
            val source = File(directory, name)
            if (!source.isFile) return@mapNotNull null
            val target = File(recoveryDirectory, name)
            target.delete()
            if (!source.renameTo(target)) {
                source.delete()
                return@mapNotNull null
            }
            // Stamped now, because a rename carries the file's own age across and the sweep
            // would collect a picture taken last month before its snackbar had faded.
            target.setLastModified(System.currentTimeMillis())
            target
        }
    }

    /** Unlinks held images once nobody can ask for them back. */
    suspend fun releaseHeld(held: List<File>) = withContext(Dispatchers.IO) {
        held.forEach { it.delete() }
    }

    /** Puts held images back where the rows expect to find them. */
    suspend fun returnFromUndo(held: List<File>) = withContext(Dispatchers.IO) {
        held.forEach { file ->
            if (file.isFile) file.renameTo(File(directory, file.name))
        }
    }

    /**
     * Unlinks images nobody can ask for back any more.
     *
     * An undo lives in memory only, so a process killed while the snackbar was up leaves a file
     * here with nothing left that knows about it. Run at startup and before each delete: an hour
     * is far longer than any snackbar and far shorter than a file worth keeping.
     */
    suspend fun purgeAbandonedRecovery() = withContext(Dispatchers.IO) { sweepAbandonedRecovery() }

    private fun sweepAbandonedRecovery() {
        val cutoff = System.currentTimeMillis() - RECOVERY_LIFETIME_MILLIS
        recoveryDirectory.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dao.all().forEach { File(directory, it.fileName).delete() }
        // Erasing everything has to mean everything. A picture waiting on an undo is still on the
        // phone, and leaving it would be the one image that survived "delete all my data".
        recoveryDirectory.listFiles()?.forEach { it.delete() }
        dao.deleteAll()
    }

    private fun ProgressPhotoEntity.toDomain(): ProgressPhoto? {
        val date = runCatching { LocalDate.parse(localDate) }.getOrNull() ?: return null
        val file = File(directory, fileName)
        // A row whose image has gone would render as a blank tile, so it is simply not listed.
        if (!file.isFile) return null
        return ProgressPhoto(
            id = id,
            timestamp = Instant.ofEpochMilli(timestampUtcMillis),
            localDate = date,
            file = file,
            weightGrams = weightGrams,
            note = note,
        )
    }

    companion object {
        const val DIRECTORY_NAME = "progress-photos"
        const val RECOVERY_DIRECTORY_NAME = "progress-photos-undo"

        /** How long a moved-aside image is kept. Far longer than a snackbar, far shorter than a day. */
        const val RECOVERY_LIFETIME_MILLIS = 60L * 60L * 1000L
    }
}

private val EXIF_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

/**
 * Reads an EXIF date.
 *
 * EXIF records the camera's wall clock with no zone at all, so it is read in the device's zone
 * rather than as an instant. Treating it as UTC moves a photo taken after dark onto the previous
 * day for anyone west of Greenwich, which is the whole point of dating it properly.
 *
 * Cameras that never had a clock set write "0000:00:00 00:00:00", which fails to parse and is
 * reported as no date rather than as the year zero.
 */
internal fun parseExifDateTime(raw: String?, zone: ZoneId): Instant? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val local = runCatching { LocalDateTime.parse(text, EXIF_DATE_TIME) }.getOrNull() ?: return null
    return runCatching { local.atZone(zone).toInstant() }.getOrNull()
}

/**
 * Keeps a capture time only when it could be one.
 *
 * A camera whose clock was never set reports 1970, and one set to the wrong year reports the
 * future. Either would sort a photo somewhere it cannot be looked at, so both are dropped and
 * the caller falls back to the time of the import.
 */
internal fun plausibleCaptureTime(candidate: Instant?, now: Instant): Instant? =
    candidate?.takeIf { it.isAfter(Instant.EPOCH) && !it.isAfter(now) }
