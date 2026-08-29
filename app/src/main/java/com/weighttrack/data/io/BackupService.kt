package com.weighttrack.data.io

import android.content.Context
import com.weighttrack.R
import android.net.Uri
import com.weighttrack.core.model.ActivityLevel
import com.weighttrack.core.model.LengthUnit
import com.weighttrack.core.model.Sex
import com.weighttrack.core.model.ThemeMode
import com.weighttrack.core.model.WeightUnit
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

data class ImportSummary(
    val imported: Int,
    val skipped: Int,
    val measurements: Int = 0,
    val problems: List<RowProblem> = emptyList(),
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
    private val settingsRepository: SettingsRepository,
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

    /** The whole export and how many readings are in it. */
    private suspend fun buildBackup(): Pair<String, Int> {
        run {
            val entries = weightRepository.observeEntries().first()
            val measurements = measurementRepository.observeAll().first()
            val goals = goalRepository.observeAll().first()
            val settings = settingsRepository.settings.first()
            // The food side comes from the same place sync reads it, so a backup and a sync
            // carry the same thing and there is one description of what that is.
            val everything = syncStore.snapshot("backup", Instant.now().toEpochMilli())
            val backup = BackupFile(
                exportedAtUtcMillis = Instant.now().toEpochMilli(),
                foods = everything.foods,
                recipes = everything.recipes,
                recipeItems = everything.recipeItems,
                foodLog = everything.foodLog,
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
                ),
            )
            return BackupCodec.encode(backup) to entries.size
        }
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

    suspend fun importJson(uri: Uri): Result<ImportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val backup = BackupCodec.decode(readText(uri))
                ?: error(say(R.string.import_not_a_backup))
            val entries = backup.entries.mapNotNull(BackupCodec::backupToEntry)
            val measurements = backup.measurements.mapNotNull(BackupCodec::backupToMeasurement)
            weightRepository.upsertAll(entries)
            measurementRepository.upsertAll(measurements)
            // Only the goal that was active is restored; retired goals would otherwise pile up
            // and the newest of them would silently become current.
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
                    )
                }
            // Restored through the same path sync uses, which already knows how to bring a
            // row in without duplicating one that is already here.
            if (backup.foods != null || backup.recipes != null || backup.foodLog != null) {
                val now = Instant.now().toEpochMilli()
                syncStore.apply(
                    com.weighttrack.core.sync.SyncDocument(
                        deviceId = "backup",
                        writtenAtUtcMillis = now,
                        // The profiles as they are here. A backup carries no profiles of its own,
                        // so the diary lands on whoever is on this phone.
                        profiles = syncStore.snapshot("backup", now).profiles,
                        foods = backup.foods.orEmpty(),
                        recipes = backup.recipes.orEmpty(),
                        recipeItems = backup.recipeItems.orEmpty(),
                        foodLog = backup.foodLog.orEmpty(),
                    ),
                    now,
                )
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
                    updatedAtUtcMillis = System.currentTimeMillis(),
                )
            }
            ImportSummary(
                imported = entries.size,
                skipped = backup.entries.size - entries.size,
                measurements = measurements.size,
            )
        }
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

    private fun readText(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        } ?: error(say(R.string.file_could_not_read))
}
