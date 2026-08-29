package com.weighttrack.data.repo

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.weighttrack.data.db.ProgressPhotoDao
import com.weighttrack.data.db.ProgressPhotoEntity
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
) {
    private val directory: File
        get() = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }

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
    ): ProgressPhoto? = withContext(Dispatchers.IO) {
        val target = File(directory, "photo-${UUID.randomUUID()}.jpg")
        val copied = runCatching {
            context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching false
            true
        }.getOrDefault(false)

        if (!copied || target.length() == 0L) {
            target.delete()
            return@withContext null
        }
        record(target, weightGrams, timestamp, zone)
    }

    /** Records a file already written into the photo directory, as the camera does. */
    suspend fun record(
        file: File,
        weightGrams: Int?,
        timestamp: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ProgressPhoto? = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() == 0L) {
            file.delete()
            return@withContext null
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
            file.delete()
            return@withContext null
        }
        entity.copy(id = id).toDomain()
    }

    /** Deletes the row and the image together, so no orphan file is left behind. */
    suspend fun delete(photo: ProgressPhoto) = withContext(Dispatchers.IO) {
        dao.byId(photo.id)?.let { row ->
            dao.delete(row)
            File(directory, row.fileName).delete()
        }
        Unit
    }

    /** Unlinks images whose rows have already gone, which is what deleting a profile leaves. */
    suspend fun deleteFiles(fileNames: List<String>) = withContext(Dispatchers.IO) {
        fileNames.forEach { File(directory, it).delete() }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dao.all().forEach { File(directory, it.fileName).delete() }
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
