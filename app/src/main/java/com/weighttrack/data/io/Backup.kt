package com.weighttrack.data.io

import com.weighttrack.core.io.Csv
import com.weighttrack.core.math.UnitConverter
import com.weighttrack.core.model.BodyMeasurement
import com.weighttrack.core.model.EntrySource
import com.weighttrack.core.model.EntryTag
import com.weighttrack.core.model.Goal
import com.weighttrack.core.model.GoalDirection
import com.weighttrack.core.model.MeasurementType
import com.weighttrack.core.model.WeightEntry
import com.weighttrack.core.model.WeightUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * CSV export.
 *
 * Both kilograms and pounds are written on every row. A file that only carries the unit in a
 * header is one careless spreadsheet edit away from being unreadable, and a person exporting
 * their history should be able to hand it to any other app without explaining anything.
 */
object WeightCsvExporter {

    val HEADER = listOf(
        "date", "time", "weight_kg", "weight_lb", "body_fat_percent", "tags", "note", "source",
        // What a body-composition scale sent, in the units the app stores rather than in
        // whatever the scale used, and what those figures are worth. An empty cell means not
        // measured, which is not zero.
        "muscle_mass_kg", "fat_free_mass_kg", "soft_lean_mass_kg", "body_water_kg",
        "muscle_percent", "impedance_ohms", "basal_metabolism_kcal", "scale_bmi",
        "scale_height_cm",
        "composition_device", "composition_protocol", "composition_quality",
        // Whose reading it is. Last, so anything reading the columns by position is unaffected,
        // and empty when the file covers one person and there is nobody to distinguish them from.
        "profile",
    )

    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /**
     * The readings as a spreadsheet.
     *
     * [profileNames] names whose each reading is, keyed by the identifier a caller uses for the
     * row. A household's weekly export used to carry whichever person happened to be open, with
     * nothing in the file saying who, which is the same fault the backup had before it started
     * carrying everybody.
     */
    fun toCsv(
        entries: List<WeightEntry>,
        zone: ZoneId = ZoneId.systemDefault(),
        profileNames: Map<Long, String> = emptyMap(),
        profileOf: (WeightEntry) -> Long? = { null },
    ): String =
        buildString {
            appendLine(Csv.row(HEADER))
            entries.sortedBy { it.timestamp }.forEach { entry ->
                val time = entry.timestamp.atZone(zone).toLocalTime()
                appendLine(
                    Csv.row(
                        listOf(
                            dateFormat.format(entry.localDate),
                            timeFormat.format(time),
                            decimal(UnitConverter.gramsToKg(entry.grams), 3),
                            decimal(UnitConverter.gramsToLb(entry.grams), 3),
                            entry.bodyFatPercent?.let { decimal(it, 1) }.orEmpty(),
                            entry.tags.joinToString(" ") { it.name },
                            entry.note.orEmpty(),
                            entry.source.name,
                            grams(entry.composition?.muscleMassGrams),
                            grams(entry.composition?.fatFreeMassGrams),
                            grams(entry.composition?.softLeanMassGrams),
                            grams(entry.composition?.bodyWaterMassGrams),
                            entry.composition?.musclePercent?.let { decimal(it, 1) }.orEmpty(),
                            entry.composition?.impedanceOhms?.let { decimal(it, 1) }.orEmpty(),
                            entry.composition?.basalMetabolismKcal?.let { decimal(it, 0) }
                                .orEmpty(),
                            entry.composition?.scaleBmi?.let { decimal(it, 1) }.orEmpty(),
                            entry.composition?.heightMm
                                ?.let { decimal(UnitConverter.mmToCm(it), 1) }
                                .orEmpty(),
                            entry.composition?.device.orEmpty(),
                            entry.composition?.protocol.orEmpty(),
                            entry.composition?.quality?.name.orEmpty(),
                            profileOf(entry)?.let { profileNames[it] }.orEmpty(),
                        ),
                    ),
                )
            }
        }

    fun measurementsToCsv(
        measurements: List<BodyMeasurement>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        appendLine(Csv.row(listOf("date", "time", "type", "value_cm", "value_in", "note")))
        measurements.sortedBy { it.timestamp }.forEach { measurement ->
            val time = measurement.timestamp.atZone(zone).toLocalTime()
            appendLine(
                Csv.row(
                    listOf(
                        dateFormat.format(measurement.localDate),
                        timeFormat.format(time),
                        measurement.type.name,
                        decimal(UnitConverter.mmToCm(measurement.valueMm), 2),
                        decimal(UnitConverter.mmToInches(measurement.valueMm), 2),
                        measurement.note.orEmpty(),
                    ),
                ),
            )
        }
    }

    /** Grams as kilograms, or an empty cell for something nobody measured. */
    private fun grams(value: Int?): String =
        value?.let { decimal(UnitConverter.gramsToKg(it), 3) }.orEmpty()

    private fun decimal(value: Double, places: Int): String =
        String.format(Locale.ROOT, "%.${places}f", value)
}

@Serializable
data class BackupEntry(
    val timestampUtcMillis: Long,
    val zoneOffsetSeconds: Int,
    val localDate: String,
    val grams: Int,
    val bodyFatPercent: Double? = null,
    val note: String? = null,
    val tags: List<String> = emptyList(),
    val source: String = EntrySource.MANUAL.name,
    val clientRecordId: String,
    val healthConnectId: String? = null,
)

@Serializable
data class BackupMeasurement(
    val timestampUtcMillis: Long,
    val localDate: String,
    val type: String,
    val valueMm: Int,
    val note: String? = null,
)

@Serializable
data class BackupGoal(
    val direction: String,
    val startGrams: Int,
    val targetGrams: Int,
    val startDate: String,
    val targetDate: String? = null,
    val milestoneStepGrams: Int,
    val active: Boolean,
)

/**
 * A progress photo's row, for the archive that carries the picture with it.
 *
 * The owner travels by the profile's own name rather than by a row number, because row numbers
 * on the new phone are different ones. The file name is the identity: it is unique in the table
 * and it is what the archive entry is called, so restoring the same archive twice replaces the
 * row rather than making a second one beside the same picture.
 */
@Serializable
data class BackupPhoto(
    val profileSyncId: String,
    val timestampUtcMillis: Long,
    val localDate: String,
    val fileName: String,
    val weightGrams: Int? = null,
    val note: String? = null,
)

@Serializable
data class BackupSettings(
    val weightUnit: String,
    val lengthUnit: String,
    val themeMode: String,
    val heightMm: Int,
    val sex: String,
    val birthYear: Int,
    val activityLevel: String,
    val trendWindowDays: Int,
    val milestoneStepGrams: Int,
)

/**
 * The whole app in one file: readings, measurements, goals and settings.
 *
 * Cloud backup is switched off by design, so this file is the migration path to a new phone.
 * It has to be complete, and it has to stay readable by a future version, which is what the
 * version field and the lenient parser are for.
 */
@Serializable
data class BackupFile(
    val app: String = "WeightTrack",
    val formatVersion: Int = BackupCodec.FORMAT_VERSION,
    val exportedAtUtcMillis: Long,
    /**
     * Everything, for every profile, in the shape sync already describes it in.
     *
     * The lists below it hold one profile's readings and were the whole of a backup until
     * version 2. That was never a backup: a household of two got whichever person happened to be
     * active, and water, fasts, macro targets, profile names, reminder times and every tombstone
     * were not written at all. Restoring such a file onto a phone with different profile rows
     * also dropped the diary, because the food log points at a profile and the names did not
     * line up.
     *
     * Null in a file written before this existed, which is what makes the older lists worth
     * keeping. They are still written so that an older build of the app can restore a file this
     * one wrote, rather than reading it happily and putting nothing back.
     */
    val document: com.weighttrack.core.sync.SyncDocument? = null,
    /**
     * Said out loud, because a file called a backup that quietly leaves something out is worse
     * than one that admits it. Progress photos are files rather than rows and need the encrypted
     * archive export.
     */
    val progressPhotos: String = BackupCodec.PHOTOS_NOT_INCLUDED,
    /**
     * The photo rows, written only into an encrypted archive.
     *
     * Absent from a JSON export, because the pictures those rows point at are not in it and a
     * row with no file behind it is a permanent blank in the grid.
     */
    val photoRows: List<BackupPhoto>? = null,
    val entries: List<BackupEntry> = emptyList(),
    val measurements: List<BackupMeasurement> = emptyList(),
    val goals: List<BackupGoal> = emptyList(),
    val settings: BackupSettings? = null,
    /**
     * The food side: your own foods, your recipes and your diary.
     *
     * Described with the sync types rather than a second set of its own. It is the same data, and
     * two descriptions of it would drift apart the first time one of them changed.
     *
     * Absent from files written before this existed, and absent is not empty: a file with no food
     * section leaves the food alone rather than clearing it.
     */
    val foods: List<com.weighttrack.core.sync.SyncFood>? = null,
    val recipes: List<com.weighttrack.core.sync.SyncRecipe>? = null,
    val recipeItems: List<com.weighttrack.core.sync.SyncRecipeItem>? = null,
    val foodLog: List<com.weighttrack.core.sync.SyncFoodLogEntry>? = null,
)

object BackupCodec {

    /** 1 was one profile's readings. 2 carries the whole database. */
    const val FORMAT_VERSION = 2

    const val PHOTOS_NOT_INCLUDED =
        "Progress photo files are not in this file. Use the encrypted archive export for those."

    const val PHOTOS_INCLUDED = "Progress photo files are in this archive."

    val json = Json {
        prettyPrint = true
        // A file written by a newer version must still restore what this version understands.
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    fun decode(text: String): BackupFile? =
        runCatching { json.decodeFromString(BackupFile.serializer(), text) }.getOrNull()

    fun entryToBackup(entry: WeightEntry): BackupEntry = BackupEntry(
        timestampUtcMillis = entry.timestamp.toEpochMilli(),
        zoneOffsetSeconds = entry.zoneOffset.totalSeconds,
        localDate = entry.localDate.toString(),
        grams = entry.grams,
        bodyFatPercent = entry.bodyFatPercent,
        note = entry.note,
        tags = entry.tags.map { it.name },
        source = entry.source.name,
        clientRecordId = entry.clientRecordId,
        healthConnectId = entry.healthConnectId,
    )

    fun backupToEntry(backup: BackupEntry): WeightEntry? {
        val date = runCatching { LocalDate.parse(backup.localDate) }.getOrNull() ?: return null
        if (backup.grams <= 0) return null
        return WeightEntry(
            timestamp = Instant.ofEpochMilli(backup.timestampUtcMillis),
            zoneOffset = runCatching { ZoneOffset.ofTotalSeconds(backup.zoneOffsetSeconds) }
                .getOrDefault(ZoneOffset.UTC),
            localDate = date,
            grams = backup.grams,
            bodyFatPercent = backup.bodyFatPercent,
            note = backup.note,
            tags = backup.tags.mapNotNull { name ->
                EntryTag.entries.firstOrNull { it.name == name }
            }.toSet(),
            source = EntrySource.entries.firstOrNull { it.name == backup.source } ?: EntrySource.IMPORT,
            clientRecordId = backup.clientRecordId,
            healthConnectId = backup.healthConnectId,
        )
    }

    fun measurementToBackup(measurement: BodyMeasurement): BackupMeasurement = BackupMeasurement(
        timestampUtcMillis = measurement.timestamp.toEpochMilli(),
        localDate = measurement.localDate.toString(),
        type = measurement.type.name,
        valueMm = measurement.valueMm,
        note = measurement.note,
    )

    fun backupToMeasurement(backup: BackupMeasurement): BodyMeasurement? {
        val type = MeasurementType.entries.firstOrNull { it.name == backup.type } ?: return null
        val date = runCatching { LocalDate.parse(backup.localDate) }.getOrNull() ?: return null
        if (backup.valueMm <= 0) return null
        return BodyMeasurement(
            timestamp = Instant.ofEpochMilli(backup.timestampUtcMillis),
            localDate = date,
            type = type,
            valueMm = backup.valueMm,
            note = backup.note,
        )
    }

    fun goalToBackup(goal: Goal): BackupGoal = BackupGoal(
        direction = goal.direction.name,
        startGrams = goal.startGrams,
        targetGrams = goal.targetGrams,
        startDate = goal.startDate.toString(),
        targetDate = goal.targetDate?.toString(),
        milestoneStepGrams = goal.milestoneStepGrams,
        active = goal.active,
    )

    fun backupToGoal(backup: BackupGoal): Goal? {
        val start = runCatching { LocalDate.parse(backup.startDate) }.getOrNull() ?: return null
        return Goal(
            direction = GoalDirection.entries.firstOrNull { it.name == backup.direction }
                ?: GoalDirection.LOSE,
            startGrams = backup.startGrams,
            targetGrams = backup.targetGrams,
            startDate = start,
            targetDate = backup.targetDate?.let { raw ->
                runCatching { LocalDate.parse(raw) }.getOrNull()
            },
            milestoneStepGrams = backup.milestoneStepGrams,
            active = backup.active,
        )
    }

    /** A filename with the date in it, so a folder of backups sorts and reads sensibly. */
    fun suggestedFileName(extension: String, today: LocalDate = LocalDate.now()): String =
        "weighttrack-$today.$extension"

    fun unitName(unit: WeightUnit): String = unit.name
}
