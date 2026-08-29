package com.weighttrack.data.repo

import android.content.Context
import android.net.Uri
import com.weighttrack.data.db.ProgressPhotoDao
import com.weighttrack.data.db.ProgressPhotoEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
class ProgressPhotoRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: ProgressPhotoDao,
) {
    private val directory: File
        get() = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }

    fun observeAll(): Flow<List<ProgressPhoto>> =
        dao.observeAll()
            .map { rows -> rows.mapNotNull { it.toDomain() } }
            .flowOn(Dispatchers.IO)

    fun observeCount(): Flow<Int> = dao.observeCount()

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
