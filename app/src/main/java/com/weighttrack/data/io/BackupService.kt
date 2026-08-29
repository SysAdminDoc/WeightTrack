package com.weighttrack.data.io

import android.content.Context
import android.net.Uri
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
    val problems: List<String> = emptyList(),
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
            writeText(uri, BackupCodec.encode(backup))
            entries.size
        }
    }

    suspend fun importCsv(uri: Uri, fallbackUnit: WeightUnit): Result<ImportSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = readText(uri)
                val table = Csv.parse(text) ?: error("That file does not look like a CSV export.")
                val mapping = WeightCsvImporter.detect(table, fallbackUnit)
                    ?: error("Could not find a date column and a weight column in that file.")
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
                    ?: error("That file does not look like a CSV export.")
                WeightCsvImporter.preview(table, fallbackUnit)
            }
        }

    suspend fun importJson(uri: Uri): Result<ImportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val backup = BackupCodec.decode(readText(uri))
                ?: error("That file is not a WeightTrack backup.")
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
            ImportSummary(
                imported = entries.size,
                skipped = backup.entries.size - entries.size,
                measurements = measurements.size,
            )
        }
    }

    private fun writeText(uri: Uri, text: String) {
        // "wt" truncates an existing file. Without it, overwriting a longer backup leaves the
        // old tail behind and produces a corrupt file.
        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.flush()
        } ?: error("Could not write to that file.")
    }

    private fun readText(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        } ?: error("Could not read that file.")
}
