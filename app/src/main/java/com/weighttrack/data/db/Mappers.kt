package com.weighttrack.data.db

import com.weighttrack.core.model.BodyMeasurement
import com.weighttrack.core.model.EntrySource
import com.weighttrack.core.model.EntryTag
import com.weighttrack.core.model.Goal
import com.weighttrack.core.model.GoalDirection
import com.weighttrack.core.model.MeasurementType
import com.weighttrack.core.model.WeightEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Entity to domain conversion.
 *
 * Every decode is forgiving: an unknown enum name or an unparseable date falls back rather
 * than throwing. A row written by a newer version of the app, or hand-edited in a database
 * browser, must never crash the list it appears in.
 */

fun WeightEntryEntity.toDomain(): WeightEntry = WeightEntry(
    id = id,
    timestamp = Instant.ofEpochMilli(timestampUtcMillis),
    zoneOffset = runCatching { ZoneOffset.ofTotalSeconds(zoneOffsetSeconds) }.getOrDefault(ZoneOffset.UTC),
    localDate = parseDateOrDerive(localDate, timestampUtcMillis, zoneOffsetSeconds),
    grams = grams,
    bodyFatPercent = bodyFatPercent,
    note = note,
    tags = decodeTags(tags),
    source = decodeEnum(source, EntrySource.entries, EntrySource.MANUAL),
    clientRecordId = clientRecordId,
    healthConnectId = healthConnectId,
)

fun WeightEntry.toEntity(
    profileId: Long,
    updatedAtUtcMillis: Long = System.currentTimeMillis(),
): WeightEntryEntity =
    WeightEntryEntity(
        id = id,
        profileId = profileId,
        timestampUtcMillis = timestamp.toEpochMilli(),
        zoneOffsetSeconds = zoneOffset.totalSeconds,
        localDate = localDate.toString(),
        grams = grams,
        bodyFatPercent = bodyFatPercent,
        note = note?.takeIf { it.isNotBlank() },
        tags = encodeTags(tags),
        source = source.name,
        clientRecordId = clientRecordId,
        healthConnectId = healthConnectId,
        updatedAtUtcMillis = updatedAtUtcMillis,
    )

fun MeasurementEntity.toDomain(): BodyMeasurement? {
    val decodedType = MeasurementType.entries.firstOrNull { it.name == type } ?: return null
    return BodyMeasurement(
        id = id,
        timestamp = Instant.ofEpochMilli(timestampUtcMillis),
        localDate = parseDateOrDerive(localDate, timestampUtcMillis, 0),
        type = decodedType,
        valueMm = valueMm,
        note = note,
    )
}

/**
 * [syncId] has to be handed in on any path that is editing a row that already exists.
 *
 * The default mints a new one, which is right for something being created and quietly wrong for
 * an edit: the row would keep its place in this database and become a different record to every
 * other device, so the edit would arrive as a second measurement rather than a correction.
 */
fun BodyMeasurement.toEntity(
    profileId: Long,
    updatedAtUtcMillis: Long = System.currentTimeMillis(),
    syncId: String = newSyncId(),
): MeasurementEntity =
    MeasurementEntity(
        id = id,
        syncId = syncId,
        profileId = profileId,
        timestampUtcMillis = timestamp.toEpochMilli(),
        localDate = localDate.toString(),
        type = type.name,
        valueMm = valueMm,
        note = note?.takeIf { it.isNotBlank() },
        updatedAtUtcMillis = updatedAtUtcMillis,
    )

fun GoalEntity.toDomain(): Goal = Goal(
    id = id,
    direction = decodeEnum(direction, GoalDirection.entries, GoalDirection.LOSE),
    startGrams = startGrams,
    targetGrams = targetGrams,
    startDate = runCatching { LocalDate.parse(startDate) }.getOrElse { LocalDate.now() },
    targetDate = targetDate?.let { raw -> runCatching { LocalDate.parse(raw) }.getOrNull() },
    milestoneStepGrams = milestoneStepGrams,
    active = active,
)

/** [syncId] has to be handed in when editing an existing goal. See the note above. */
fun Goal.toEntity(
    profileId: Long,
    createdAtUtcMillis: Long = System.currentTimeMillis(),
    syncId: String = newSyncId(),
    updatedAtUtcMillis: Long = System.currentTimeMillis(),
): GoalEntity = GoalEntity(
    id = id,
    syncId = syncId,
    updatedAtUtcMillis = updatedAtUtcMillis,
    profileId = profileId,
    direction = direction.name,
    startGrams = startGrams,
    targetGrams = targetGrams,
    startDate = startDate.toString(),
    targetDate = targetDate?.toString(),
    milestoneStepGrams = milestoneStepGrams,
    active = active,
    createdAtUtcMillis = createdAtUtcMillis,
)

internal fun encodeTags(tags: Set<EntryTag>): String =
    tags.joinToString(separator = ",") { it.name }

internal fun decodeTags(raw: String): Set<EntryTag> {
    if (raw.isBlank()) return emptySet()
    return raw.split(',')
        .mapNotNull { name -> EntryTag.entries.firstOrNull { it.name == name.trim() } }
        .toSet()
}

private fun <T : Enum<T>> decodeEnum(raw: String, values: List<T>, fallback: T): T =
    values.firstOrNull { it.name == raw } ?: fallback

/**
 * Falls back to deriving the local date from the timestamp when the stored text is missing or
 * malformed, so a bad row degrades to the right day rather than disappearing.
 */
private fun parseDateOrDerive(raw: String, timestampUtcMillis: Long, offsetSeconds: Int): LocalDate =
    runCatching { LocalDate.parse(raw) }.getOrElse {
        val offset = runCatching { ZoneOffset.ofTotalSeconds(offsetSeconds) }.getOrDefault(ZoneOffset.UTC)
        Instant.ofEpochMilli(timestampUtcMillis).atOffset(offset).toLocalDate()
    }
