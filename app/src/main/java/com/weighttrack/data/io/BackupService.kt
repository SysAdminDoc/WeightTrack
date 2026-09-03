package com.weighttrack.data.io

import android.content.Context
import com.weighttrack.R
import android.net.Uri
import androidx.room.withTransaction
import com.weighttrack.core.io.Csv
import com.weighttrack.core.io.OpenedFile
import com.weighttrack.core.io.OpenedFileKind
import com.weighttrack.core.io.ImportPreview
import com.weighttrack.core.io.RowProblem
import com.weighttrack.core.io.WeightCsvImporter
import com.weighttrack.core.math.SmoothingMode
import com.weighttrack.core.model.ActivityLevel
import com.weighttrack.core.model.LengthUnit
import com.weighttrack.core.model.Sex
import com.weighttrack.core.model.ThemeMode
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.core.sync.SyncSettings as SyncedSettings
import com.weighttrack.data.db.toDomain
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.GoalRepository
import com.weighttrack.data.repo.MeasurementRepository
import com.weighttrack.data.repo.WeightRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** What went into an archive. */
data class ArchiveSummary(
    val photos: Int,
)

data class ImportSummary(
    val imported: Int,
    val skipped: Int,
    val measurements: Int = 0,
    val problems: List<RowProblem> = emptyList(),
)

/**
 * What is in a backup, read without writing anything.
 *
 * Restoring is the one action in the app that can quietly change every screen at once, and until
 * now it happened the instant a file was picked. Picking the wrong file from a folder of four
 * weekly copies is an easy thing to do.
 */
data class BackupPreview(
    val formatVersion: Int,
    val exportedAtUtcMillis: Long,
    val profiles: Int,
    val weights: Int,
    val measurements: Int,
    val water: Int,
    val fasts: Int,
    val goals: Int,
    val foods: Int,
    val foodLog: Int,
)

/**
 * Reads and writes the user's data through the storage picker.
 *
 * Everything runs off the main thread and through the content resolver, so the app never needs
 * a storage permission and the person chooses exactly where the file lands.
 */
@Singleton
class BackupService @Inject constructor(
    private val syncStore: com.weighttrack.data.sync.SyncStore,
    @param:ApplicationContext private val context: Context,
    private val weightRepository: WeightRepository,
    private val measurementRepository: MeasurementRepository,
    private val goalRepository: GoalRepository,
    private val profileRepository: com.weighttrack.data.repo.ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val database: com.weighttrack.data.db.WeightTrackDatabase,
    private val progressPhotoRepository: com.weighttrack.data.repo.ProgressPhotoRepository,
) {

    suspend fun exportCsv(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val entries = weightRepository.observeEntries().first()
            writeText(uri, WeightCsvExporter.toCsv(entries))
            entries.size
        }
    }

    suspend fun exportMeasurementsCsv(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val measurements = measurementRepository.observeAll().first()
            writeText(uri, WeightCsvExporter.measurementsToCsv(measurements))
            measurements.size
        }
    }

    suspend fun exportJson(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val (text, count) = buildBackup()
            writeText(uri, text)
            count
        }
    }

    /**
     * The backup as text, for a caller that will place the file itself.
     *
     * The scheduled backup writes into a folder rather than into a document somebody picked, so
     * it needs the content without a destination. Deliberately the same call underneath: a backup
     * the app takes and one taken by hand must not be able to differ.
     */
    suspend fun exportedJson(): Result<String> = withContext(Dispatchers.IO) {
        runCatching { buildBackup().first }
    }

    /**
     * The readings as a spreadsheet, for the same caller.
     *
     * A backup restores the app and cannot be read by a person or by anything else. The rows can
     * be, which is a different job and the reason the weekly run writes both.
     */
    suspend fun exportedCsv(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            // Everybody, like the backup beside it. Scoped to whoever happens to be open, a
            // household's weekly spreadsheet carried one person's readings with nothing in the
            // file saying whose, which is the fault the backup itself was fixed for.
            val names = database.profileDao().all().associate { it.id to it.name }
            val rows = database.syncDao().weights()
            val owners = rows.associate { it.clientRecordId to it.profileId }
            WeightCsvExporter.toCsv(
                entries = rows.map { it.toDomain() },
                profileNames = names,
                profileOf = { owners[it.clientRecordId] },
            )
        }
    }

    /** The whole export and how many readings are in it. */
    private suspend fun buildBackup(photoRows: List<BackupPhoto>? = null): Pair<String, Int> {
        run {
            val entries = weightRepository.observeEntries().first()
            val measurements = measurementRepository.observeAll().first()
            val goals = goalRepository.observeAll().first()
            val settings = settingsRepository.settings.first()
            // Everything comes from the same place sync reads it, so a backup and a sync carry
            // the same thing and there is one description of what that is. Every profile, not
            // just whoever is open, and the tombstones with it.
            val now = Instant.now().toEpochMilli()
            val everything = syncStore.snapshot("backup", now, publishing = false).copy(
                settings = SyncedSettings(
                    weightUnit = settings.weightUnit.name,
                    lengthUnit = settings.lengthUnit.name,
                    themeMode = settings.themeMode.name,
                    heightMm = settings.profile.heightMm,
                    sex = settings.profile.sex.name,
                    birthYear = settings.profile.birthYear,
                    activityLevel = settings.profile.activityLevel.name,
                    trendWindowDays = settings.trendWindowDays,
                    milestoneStepGrams = settings.milestoneStepGrams,
                    updatedAtUtcMillis = settings.updatedAtUtcMillis,
                    smoothingMode = settings.smoothingMode.name,
                ),
            )
            val backup = BackupFile(
                exportedAtUtcMillis = now,
                document = everything,
                progressPhotos = if (photoRows == null) {
                    BackupCodec.PHOTOS_NOT_INCLUDED
                } else {
                    BackupCodec.PHOTOS_INCLUDED
                },
                photoRows = photoRows,
                // The lists below are the version-1 shape, kept so an older build can still
                // restore a file this one wrote. Version 2 reads `document` and ignores them.
                //
                // The food side is deliberately not repeated here. A long-kept diary is by far
                // the biggest part of a backup, and writing it twice was enough on its own to
                // push a real file past the size this app will read back in.
                entries = entries.map(BackupCodec::entryToBackup),
                measurements = measurements.map(BackupCodec::measurementToBackup),
                goals = goals.map(BackupCodec::goalToBackup),
                settings = BackupSettings(
                    weightUnit = settings.weightUnit.name,
                    lengthUnit = settings.lengthUnit.name,
                    themeMode = settings.themeMode.name,
                    heightMm = settings.profile.heightMm,
                    sex = settings.profile.sex.name,
                    birthYear = settings.profile.birthYear,
                    activityLevel = settings.profile.activityLevel.name,
                    trendWindowDays = settings.trendWindowDays,
                    milestoneStepGrams = settings.milestoneStepGrams,
                    smoothingMode = settings.smoothingMode.name,
                ),
            )
            return BackupCodec.encode(backup) to entries.size
        }
    }



    // ---- the encrypted archive ----

    /**
     * Writes the whole app, pictures included, into one password-protected file.
     *
     * JSON and CSV stay exactly as they were and say plainly that the photographs are not in
     * them. This is the file for moving to a new phone, and it is encrypted because it carries
     * pictures of somebody's body along with every weight they have ever recorded.
     *
     * No credential goes in it. The WebDAV password and the USDA key are held under a key that
     * lives in this phone's keystore and means nothing anywhere else, so exporting them would
     * either export nothing usable or export a working password, and the second is worse.
     */
    suspend fun exportArchive(uri: Uri, password: CharArray): Result<ArchiveSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rows = progressPhotoRepository.allRows()
                // Read off the row rather than looked up through a display name. Two people in
                // a household called the same thing is ordinary, and the name lookup put every
                // one of their photographs on whichever of them came last.
                val ownerNames = rows.map { it.profileId }.distinct().associateWith {
                    profileRepository.syncIdOf(it).orEmpty()
                }
                val included = rows.filter {
                    progressPhotoRepository.fileOf(it.fileName).isFile &&
                        // A picture whose own name would not be an acceptable entry cannot be
                        // put back safely on the other phone, so it is not promised here.
                        ArchiveCodec.isSafeName(ArchiveCodec.PHOTO_PREFIX + it.fileName)
                }
                val json = buildBackup(
                    photoRows = included.map { row ->
                        BackupPhoto(
                            profileSyncId = ownerNames[row.profileId].orEmpty(),
                            timestampUtcMillis = row.timestampUtcMillis,
                            localDate = row.localDate,
                            fileName = row.fileName,
                            weightGrams = row.weightGrams,
                            note = row.note,
                        )
                    },
                ).first.toByteArray(Charsets.UTF_8)

                val entries = buildList {
                    add(ArchiveEntry(ArchiveCodec.BACKUP_ENTRY, json.size.toLong()) {
                        java.io.ByteArrayInputStream(json)
                    })
                    included.forEach { row ->
                        val file = progressPhotoRepository.fileOf(row.fileName)
                        add(
                            ArchiveEntry(ArchiveCodec.PHOTO_PREFIX + row.fileName, file.length()) {
                                file.inputStream()
                            },
                        )
                    }
                }
                // Built whole in the cache first. Writing straight to the chosen file truncates
                // it on open, so a photo that disappeared halfway through left a half-written
                // archive sitting where a backup should be, looking exactly like one.
                val staged = java.io.File(context.cacheDir, "archive-export.tmp")
                try {
                    staged.delete()
                    staged.outputStream().use { ArchiveCodec.write(it, password, entries) }
                    context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                        staged.inputStream().use { it.copyTo(stream) }
                        stream.flush()
                    } ?: error(say(R.string.file_could_not_write))
                } finally {
                    staged.delete()
                }
                ArchiveSummary(photos = included.size)
            }
        }

    /**
     * Reads an archive and restores everything in it, or changes nothing at all.
     *
     * Unpacked into a private staging folder first and only then applied. A wrong password, a
     * changed byte, a name that tries to climb out of the photo folder or a file claiming more
     * than this will hold all fail before a single row or picture has moved.
     */
    suspend fun importArchive(uri: Uri, password: CharArray): Result<ImportSummary> =
        withContext(Dispatchers.IO) {
            val staging = java.io.File(context.cacheDir, "archive-restore").apply {
                deleteRecursively()
                mkdirs()
            }
            // Two folders, not one. The archive has two namespaces and a photo may legitimately
            // be called backup.json: flattened into one folder, that picture overwrites the
            // export it was supposed to sit beside.
            val stagedBackup = java.io.File(staging, "backup").apply { mkdirs() }
            val stagedPhotos = java.io.File(staging, "photos").apply { mkdirs() }
            try {
                runCatching {
                    val unpacked = mutableMapOf<String, java.io.File>()
                    try {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            ArchiveCodec.read(stream, password) { name, _ ->
                                // The codec has already checked the name against the one shape
                                // this writes to. Taken apart again here so a change to that
                                // check on its own can never let a path out.
                                check(ArchiveCodec.isSafeName(name)) { "unsafe archive name" }
                                val target = if (name == ArchiveCodec.BACKUP_ENTRY) {
                                    java.io.File(stagedBackup, name)
                                } else {
                                    java.io.File(stagedPhotos, name.removePrefix(ArchiveCodec.PHOTO_PREFIX))
                                }
                                unpacked[name] = target
                                target.outputStream()
                            }
                        } ?: error(say(R.string.file_could_not_read))
                    } catch (problem: ArchiveException) {
                        error(say(archiveMessage(problem.problem)))
                    }

                    val backupFile = unpacked[ArchiveCodec.BACKUP_ENTRY]
                        ?: error(say(R.string.archive_not_an_archive))
                    val backup = BackupCodec.decode(backupFile.readText())
                        ?: error(say(R.string.import_not_a_backup))
                    if (backup.formatVersion > BackupCodec.FORMAT_VERSION) {
                        error(say(R.string.import_newer_version))
                    }

                    // Every picture into place before a row moves, and every one of them undone
                    // if any of them fails. A phone that runs out of room halfway used to end up
                    // with the whole history restored and half the album, reported as a failure.
                    val placed = placePhotos(backup.photoRows.orEmpty(), unpacked, stagedPhotos)
                    val summary = try {
                        restore(backup)
                    } catch (failure: Throwable) {
                        placed.forEach { it.undo() }
                        throw failure
                    }
                    restorePhotoRows(placed)
                    ImportSummary(
                        imported = summary.imported,
                        skipped = summary.skipped,
                        measurements = summary.measurements,
                    )
                }
            } finally {
                staging.deleteRecursively()
            }
        }

    /** A picture put where the app keeps them, and what it replaced if anything. */
    private class PlacedPhoto(
        val row: BackupPhoto,
        val target: java.io.File,
        val replaced: java.io.File?,
    ) {
        /** Puts the photo directory back as it was. */
        fun undo() {
            target.delete()
            replaced?.renameTo(target)
        }
    }

    /**
     * Copies the unpacked pictures into the photo directory, or none of them.
     *
     * Files before rows. A row whose image is not there yet reads as a photo that has gone, and
     * the screen simply does not list it.
     */
    private fun placePhotos(
        rows: List<BackupPhoto>,
        unpacked: Map<String, java.io.File>,
        stagedPhotos: java.io.File,
    ): List<PlacedPhoto> {
        val placed = mutableListOf<PlacedPhoto>()
        try {
            rows.forEach { row ->
                // The name in the JSON is not the name the codec checked: the entry names went
                // through `isSafeName` and these did not. A row is only followed when its own
                // name would have been an acceptable entry.
                if (!ArchiveCodec.isSafeName(ArchiveCodec.PHOTO_PREFIX + row.fileName)) {
                    return@forEach
                }
                val source = unpacked[ArchiveCodec.PHOTO_PREFIX + row.fileName] ?: return@forEach
                if (!source.isFile) return@forEach
                if (source.parentFile?.canonicalFile != stagedPhotos.canonicalFile) return@forEach
                val target = progressPhotoRepository.fileOf(row.fileName)
                // Whatever was there is moved aside rather than overwritten, so an abandoned
                // restore leaves the picture that was already on the phone.
                val replaced = if (target.isFile) {
                    java.io.File(stagedPhotos, "replaced-${row.fileName}")
                        .takeIf { target.renameTo(it) }
                } else {
                    null
                }
                source.copyTo(target, overwrite = true)
                placed += PlacedPhoto(row, target, replaced)
            }
        } catch (failure: Throwable) {
            placed.forEach { it.undo() }
            throw failure
        }
        return placed
    }

    /** Puts the rows in, once every picture they point at is where they say it is. */
    private suspend fun restorePhotoRows(placed: List<PlacedPhoto>) {
        if (placed.isEmpty()) return
        val profiles = profileRepository.observeAll().first()
        // Keyed on the profile's own travelling name, read off the row rather than looked up
        // through a display name. Two people called the same thing is an ordinary household,
        // and a name is not an identity.
        val bySyncId = profiles.mapNotNull { profile ->
            profileRepository.syncIdOf(profile.id)
                ?.takeIf { it.isNotBlank() }
                ?.let { it to profile.id }
        }.toMap()
        val fallback = profileRepository.activeId()
        placed.forEach { placement ->
            val row = placement.row
            progressPhotoRepository.restoreRow(
                com.weighttrack.data.db.ProgressPhotoEntity(
                    // A blank owner means the archive did not say. It must not match a profile
                    // that has simply never synced, which also carries a blank name.
                    profileId = row.profileSyncId.takeIf { it.isNotBlank() }
                        ?.let { bySyncId[it] }
                        ?: fallback,
                    timestampUtcMillis = row.timestampUtcMillis,
                    localDate = row.localDate,
                    fileName = row.fileName,
                    weightGrams = row.weightGrams,
                    note = row.note,
                ),
            )
        }
    }
    private fun archiveMessage(problem: ArchiveProblem): Int = when (problem) {
        ArchiveProblem.NOT_AN_ARCHIVE -> R.string.archive_not_an_archive
        ArchiveProblem.UNSUPPORTED_VERSION -> R.string.archive_newer_version
        ArchiveProblem.WRONG_PASSWORD -> R.string.archive_wrong_password
        ArchiveProblem.DAMAGED -> R.string.archive_damaged
        ArchiveProblem.TOO_LARGE -> R.string.archive_too_large
        ArchiveProblem.BAD_NAME -> R.string.archive_bad_name
    }

    /**
     * A message for the person, not the log.
     *
     * These come back through a Result and end up on the settings screen, so they are the app
     * talking rather than a stack trace, and they belong in the resource file with the rest.
     */
    private fun say(id: Int, vararg arguments: Any): String = context.getString(id, *arguments)

    suspend fun importCsv(uri: Uri, fallbackUnit: WeightUnit): Result<ImportSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = readText(uri)
                val table = Csv.parse(text) ?: error(say(R.string.import_not_a_csv))
                val mapping = WeightCsvImporter.detect(table, fallbackUnit)
                    ?: error(say(R.string.import_no_columns))
                val result = WeightCsvImporter.import(table, mapping)
                weightRepository.upsertAll(result.entries)
                ImportSummary(
                    imported = result.entries.size,
                    skipped = result.skippedRows,
                    problems = result.problems,
                )
            }
        }

    /** Previews a file without writing anything, so the person can check the reading first. */
    suspend fun previewCsv(uri: Uri, fallbackUnit: WeightUnit): Result<ImportPreview> =
        withContext(Dispatchers.IO) {
            runCatching {
                val table = Csv.parse(readText(uri))
                    ?: error(say(R.string.import_not_a_csv))
                WeightCsvImporter.preview(table, fallbackUnit)
            }
        }

    /**
     * What a file holds, without touching a single row.
     *
     * The same reading and the same refusals as the restore itself, so a file this accepts is one
     * the restore will accept and a file it rejects never reaches the database.
     */
    suspend fun previewJson(uri: Uri): Result<BackupPreview> = withContext(Dispatchers.IO) {
        runCatching {
            val backup = readBackup(uri)
            val document = backup.document
            BackupPreview(
                formatVersion = backup.formatVersion,
                exportedAtUtcMillis = backup.exportedAtUtcMillis,
                profiles = document?.profiles?.size ?: 0,
                weights = document?.weights?.size ?: backup.entries.size,
                measurements = document?.measurements?.size ?: backup.measurements.size,
                water = document?.water?.size ?: 0,
                fasts = document?.fasts?.size ?: 0,
                goals = document?.goals?.size ?: backup.goals.size,
                foods = document?.foods?.size ?: backup.foods?.size ?: 0,
                foodLog = document?.foodLog?.size ?: backup.foodLog?.size ?: 0,
            )
        }
    }

    /**
     * Reads and checks a file before anything is allowed to act on it.
     *
     * Bounded on the way in: a content URI is somebody else's file and reading all of it into
     * memory first is how a truncated or absurd one takes the app down before it can be judged.
     */
    private fun readBackup(uri: Uri): BackupFile {
        val backup = BackupCodec.decode(readText(uri))
            ?: error(say(R.string.import_not_a_backup))
        // A file from a newer version may describe records this build has no idea how to place.
        // Reading what it recognises and dropping the rest would look like a successful restore.
        if (backup.formatVersion > BackupCodec.FORMAT_VERSION) {
            error(say(R.string.import_newer_version))
        }
        return backup
    }

    suspend fun importJson(uri: Uri): Result<ImportSummary> = withContext(Dispatchers.IO) {
        runCatching { restore(readBackup(uri)) }
    }

    /**
     * Puts a decoded backup back, whatever it arrived in.
     *
     * The same call for a JSON file picked by hand and for the backup inside an archive, so the
     * two cannot restore differently.
     */
    private suspend fun restore(backup: BackupFile): ImportSummary {
        run {
            backup.document?.let { return restoreDocument(it, backup.settings) }
            val entries = backup.entries.mapNotNull(BackupCodec::backupToEntry)
            val measurements = backup.measurements.mapNotNull(BackupCodec::backupToMeasurement)
            // Resolved before the transaction opens: it comes off a flow, and a flow read inside
            // a write transaction waits for the connection that transaction is holding.
            val owner = profileRepository.activeId()
            val now = Instant.now().toEpochMilli()
            val profiles = syncStore.snapshot("backup", now, publishing = false).profiles
            // One commit for the lot. Four separate ones left a file whose food section broke a
            // constraint restoring the weigh-ins, the measurements and the goal and then failing,
            // which is a half-restored database nobody asked for and no way back to the old one.
            database.withTransaction {
                weightRepository.upsertAll(entries, owner)
                measurementRepository.upsertAll(measurements, owner)
                // Only the goal that was active is restored; retired goals would otherwise pile
                // up and the newest of them would silently become current.
                backup.goals.firstOrNull { it.active }
                    ?.let(BackupCodec::backupToGoal)
                    ?.let { goal ->
                        goalRepository.setGoal(
                            startGrams = goal.startGrams,
                            targetGrams = goal.targetGrams,
                            milestoneStepGrams = goal.milestoneStepGrams,
                            startDate = goal.startDate,
                            targetDate = goal.targetDate,
                            direction = goal.direction,
                            owner = owner,
                        )
                    }
                // Restored through the same path sync uses, which already knows how to bring a
                // row in without duplicating one that is already here.
                if (backup.foods != null || backup.recipes != null || backup.foodLog != null) {
                    syncStore.apply(
                        com.weighttrack.core.sync.SyncDocument(
                            deviceId = "backup",
                            writtenAtUtcMillis = now,
                            // The profiles as they are here. A version-1 backup carries none of
                            // its own, so the diary lands on whoever is on this phone.
                            profiles = profiles,
                            foods = backup.foods.orEmpty(),
                            recipes = backup.recipes.orEmpty(),
                            recipeItems = backup.recipeItems.orEmpty(),
                            foodLog = backup.foodLog.orEmpty(),
                        ),
                        now,
                    )
                }
            }
            // Written on the way out since the first version and never read on the way back,
            // so restoring on a new phone quietly lost units, theme, height and the rest.
            backup.settings?.let { stored ->
                val current = settingsRepository.settings.first()
                settingsRepository.applySynced(
                    weightUnit = decode(stored.weightUnit, WeightUnit.entries, current.weightUnit),
                    lengthUnit = decode(stored.lengthUnit, LengthUnit.entries, current.lengthUnit),
                    themeMode = decode(stored.themeMode, ThemeMode.entries, current.themeMode),
                    heightMm = stored.heightMm,
                    sex = decode(stored.sex, Sex.entries, current.profile.sex),
                    birthYear = stored.birthYear,
                    activityLevel = decode(
                        stored.activityLevel,
                        ActivityLevel.entries,
                        current.profile.activityLevel,
                    ),
                    trendWindowDays = stored.trendWindowDays,
                    milestoneStepGrams = stored.milestoneStepGrams,
                    smoothingMode = decode(stored.smoothingMode, SmoothingMode.entries, current.smoothingMode),
                    updatedAtUtcMillis = System.currentTimeMillis(),
                )
            }
            adoptDemographics(backup.settings)
            return ImportSummary(
                imported = entries.size,
                skipped = backup.entries.size - entries.size,
                measurements = measurements.size,
            )
        }
    }

    /**
     * A version-2 restore: the whole file, through the path sync already uses.
     *
     * Profiles come back first and by their travelling names, so a reading, a fast or a day's
     * diary lands on the person it belonged to even though the row numbers on this phone are
     * different ones. Nothing here is scoped to whoever happens to be active.
     */
    /**
     * Gives a restored profile the body a backup describes, when the file kept it apart.
     *
     * Height, sex, year of birth and activity level used to belong to the phone rather than to
     * the person, so every backup taken before this release carries them once, beside the
     * settings. Nothing reads that copy any more. Without this, restoring an older backup onto a
     * new phone brought the weigh-ins, the goals and the diary back and quietly left the BMI,
     * the healthy range, the body-fat estimate, the basal rate and the daily burn behind.
     *
     * Only onto a profile that has none of its own: the newer per-profile values are the better
     * answer wherever they exist, and a file's single figure must not overwrite two people's.
     */
    private suspend fun adoptDemographics(stored: BackupSettings?) {
        if (stored == null) return
        if (stored.heightMm <= 0 && stored.birthYear <= 0) return
        val id = profileRepository.activeId()
        val existing = profileRepository.observeAll().first().firstOrNull { it.id == id }
            ?.demographics
            ?: return
        if (existing.heightMm > 0 || existing.birthYear > 0) return
        profileRepository.setDemographics(
            id,
            com.weighttrack.core.model.UserProfile(
                heightMm = stored.heightMm,
                sex = decode(stored.sex, Sex.entries, existing.sex),
                birthYear = stored.birthYear,
                activityLevel = decode(
                    stored.activityLevel,
                    ActivityLevel.entries,
                    existing.activityLevel,
                ),
            ),
        )
    }

    private suspend fun restoreDocument(
        document: com.weighttrack.core.sync.SyncDocument,
        backup: BackupSettings?,
    ): ImportSummary {
        val now = Instant.now().toEpochMilli()
        // A phone that has only ever been opened once. The app makes a profile on first start,
        // so a restore onto a new phone always meets one, and the profiles in the backup arrive
        // beside it rather than instead of it: three profiles where there should be two, with
        // the empty one still on screen and the person's history apparently missing.
        val untouched = untouchedProfile(document)
        syncStore.apply(document, now)
        if (untouched != null) {
            adoptRestoredProfile(untouched)
        }
        document.settings?.let { stored ->
            val current = settingsRepository.settings.first()
            settingsRepository.applySynced(
                weightUnit = decode(stored.weightUnit, WeightUnit.entries, current.weightUnit),
                lengthUnit = decode(stored.lengthUnit, LengthUnit.entries, current.lengthUnit),
                themeMode = decode(stored.themeMode, ThemeMode.entries, current.themeMode),
                heightMm = stored.heightMm,
                sex = decode(stored.sex, Sex.entries, current.profile.sex),
                birthYear = stored.birthYear,
                activityLevel = decode(
                    stored.activityLevel,
                    ActivityLevel.entries,
                    current.profile.activityLevel,
                ),
                trendWindowDays = stored.trendWindowDays,
                milestoneStepGrams = stored.milestoneStepGrams,
                smoothingMode = decode(stored.smoothingMode, SmoothingMode.entries, current.smoothingMode),
                updatedAtUtcMillis = System.currentTimeMillis(),
            )
        }
        // A document written before the demographics moved onto the profile carries them in its
        // settings instead, where nothing reads them any more.
        adoptDemographics(backup)
        return ImportSummary(
            imported = document.weights.size,
            skipped = 0,
            measurements = document.measurements.size,
        )
    }

    /**
     * The empty profile the app makes on first start, when that is all there is.
     *
     * Null as soon as anything has been recorded against it, or as soon as there is more than
     * one, or when the backup names it too. A phone somebody has actually used is being merged
     * into, and nothing there is the app's to tidy away.
     */
    private suspend fun untouchedProfile(
        document: com.weighttrack.core.sync.SyncDocument,
    ): Long? {
        if (document.profiles.isEmpty()) return null
        val local = syncStore.snapshot("backup", Instant.now().toEpochMilli(), publishing = false)
        val only = local.profiles.singleOrNull() ?: return null
        if (document.profiles.any { it.syncId == only.syncId }) return null
        val empty = local.weights.isEmpty() && local.measurements.isEmpty() &&
            local.water.isEmpty() && local.fasts.isEmpty() && local.goals.isEmpty() &&
            local.macroTargets.isEmpty() && local.foodLog.isEmpty() &&
            local.medicationDoses.isEmpty() && local.sideEffects.isEmpty()
        if (!empty) return null
        val id = profileRepository.observeAll().first().singleOrNull()?.id ?: return null
        // Photographs are not in the sync document, so the emptiness above cannot see them. A
        // phone whose only content is a progress photo is one somebody has used.
        if (profileRepository.photoFileNamesOf(id).isNotEmpty()) return null
        return id
    }

    /**
     * Moves onto the restored history and drops the empty profile that was standing in for it.
     *
     * In that order: the last profile cannot be deleted, and nobody should be looking at a blank
     * screen for the moment in between.
     */
    private suspend fun adoptRestoredProfile(untouchedId: Long) {
        val restored = profileRepository.observeAll().first()
            .filterNot { it.id == untouchedId }
            .minByOrNull { it.position }
            ?: return
        profileRepository.setActive(restored.id)
        // The variant that hands back the files, even though this only runs for a profile with
        // none: the one that discards them leaves orphaned images on the phone for ever, and the
        // day somebody widens the check above is the day that starts happening quietly.
        val photos = profileRepository.deleteReturningPhotos(untouchedId)?.photoFileNames.orEmpty()
        check(photos.isEmpty()) { "a profile with photographs was taken for an untouched one" }
    }

    /**
     * Reads a name back to a value, keeping what is here when the file says something unknown.
     *
     * Falling back to a default would quietly reset somebody's units because a backup was
     * written by a version that spelled them differently.
     */
    private fun <T : Enum<T>> decode(name: String, values: List<T>, fallback: T): T =
        values.firstOrNull { it.name == name } ?: fallback

    private fun writeText(uri: Uri, text: String) {
        // "wt" truncates an existing file. Without it, overwriting a longer backup leaves the
        // old tail behind and produces a corrupt file.
        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.flush()
        } ?: error(say(R.string.file_could_not_write))
    }

    /**
     * What a file handed to the app from outside turns out to be.
     *
     * Both the declared type and the name are asked for, because neither is reliable on its own:
     * a file manager will send a backup as `application/octet-stream`, and the name is all that
     * is left when it does. Nothing is read here; deciding costs a metadata query and no more.
     */
    suspend fun openedFileKind(uri: Uri): OpenedFileKind? = withContext(Dispatchers.IO) {
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val name = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
        OpenedFile.kindOf(type, name)
    }

    /**
     * Reads a chosen file, with a ceiling.
     *
     * A content URI names somebody else's file and the provider behind it is free to say
     * anything about its size, so the only honest limit is the one applied while reading. Ten
     * megabytes is far past a plausible backup: an export of ten years of daily weigh-ins with a
     * full food diary comes to a small fraction of it.
     */
    private fun readText(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(BUFFER_BYTES)
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (out.size() + read > MAX_BACKUP_BYTES) error(say(R.string.import_too_large))
                out.write(buffer, 0, read)
            }
            out.toString(Charsets.UTF_8.name())
        } ?: error(say(R.string.file_could_not_read))

    companion object {
        /**
         * The ceiling on a file the restore will read.
         *
         * Set far above any backup this app can produce rather than near it. The export is not
         * bounded, and a limit a real person's own file could reach would turn this from a guard
         * against a hostile file into a refusal to restore their history.
         */
        const val MAX_BACKUP_BYTES = 64L * 1024 * 1024

        private const val BUFFER_BYTES = 64 * 1024
    }
}
