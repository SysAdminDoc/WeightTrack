package com.weighttrack.data.io

import com.weighttrack.core.math.UnitConverter
import com.weighttrack.core.model.EntrySource
import com.weighttrack.core.model.WeightPlausibility
import com.weighttrack.core.model.WeightEntry
import com.weighttrack.core.model.WeightUnit
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Which column holds what, and what unit the weights are in. */
data class ImportMapping(
    val dateIndex: Int,
    val timeIndex: Int? = null,
    val weightIndex: Int,
    val unit: WeightUnit,
    val bodyFatIndex: Int? = null,
    val noteIndex: Int? = null,
    val dayFirstDates: Boolean = true,
)

data class ImportPreview(
    val mapping: ImportMapping?,
    val detectedFrom: String?,
    val header: List<String>,
    val sampleRowCount: Int,
    val importableRowCount: Int,
    val skippedRows: Int,
    val problems: List<RowProblem>,
)

data class ImportResult(
    val entries: List<WeightEntry>,
    val skippedRows: Int,
    val problems: List<RowProblem>,
)

/**
 * A row the importer could not use.
 *
 * Kept as what went wrong rather than a sentence about it, so the screen can say it in the
 * reader's language. The importer has no Context and no business holding English.
 */
data class RowProblem(val row: Int, val field: Field, val value: String) {
    enum class Field { WEIGHT, DATE }
}

/**
 * Reads a weight log exported by another app.
 *
 * Nobody starts from zero. People arrive carrying years of history in a Libra or Happy Scale
 * export, and an importer that only accepts one exact layout means they either lose that
 * history or do not switch at all. Columns are matched by meaning rather than by position.
 */
object WeightCsvImporter {

    private val DATE_HEADERS = listOf("date", "datum", "day", "timestamp", "date/time", "datetime", "recorded")
    private val TIME_HEADERS = listOf("time", "zeit", "hour")
    private val WEIGHT_HEADERS = listOf("weight", "gewicht", "mass", "peso", "poids", "value")
    private val BODY_FAT_HEADERS = listOf("body fat", "bodyfat", "fat", "fat %", "fat percent", "bf")
    private val NOTE_HEADERS = listOf("note", "notes", "comment", "comments", "remark", "description")

    private val KG_HINTS = listOf("kg", "kilogram", "kilo")
    private val LB_HINTS = listOf("lb", "lbs", "pound")

    private val DATE_TIME_PATTERNS = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm:ss",
        "dd/MM/yyyy HH:mm:ss",
        "dd/MM/yyyy HH:mm",
        "MM/dd/yyyy HH:mm:ss",
        "MM/dd/yyyy HH:mm",
        "dd.MM.yyyy HH:mm:ss",
        "dd.MM.yyyy HH:mm",
    )
    private val DATE_PATTERNS = listOf(
        "yyyy-MM-dd",
        "yyyy/MM/dd",
        "dd/MM/yyyy",
        "MM/dd/yyyy",
        "dd.MM.yyyy",
        "dd-MM-yyyy",
        "d MMM yyyy",
        "MMM d, yyyy",
    )

    fun preview(
        table: CsvTable,
        fallbackUnit: WeightUnit,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): ImportPreview {
        val mapping = detect(table, fallbackUnit)
        val result = mapping?.let { this.import(table, it, zone, now) }
        return ImportPreview(
            mapping = mapping,
            detectedFrom = mapping?.let { describe(table, it) },
            header = table.header,
            sampleRowCount = table.rows.size,
            importableRowCount = result?.entries?.size ?: 0,
            skippedRows = result?.skippedRows ?: table.rows.size,
            problems = result?.problems.orEmpty(),
        )
    }

    fun detect(table: CsvTable, fallbackUnit: WeightUnit): ImportMapping? {
        val dateIndex = findColumn(table.header, DATE_HEADERS) ?: return null
        val weightIndex = findColumn(table.header, WEIGHT_HEADERS)
            // openScale and some others label the column with the unit alone.
            ?: table.header.indexOfFirst { header ->
                val lower = header.lowercase(Locale.ROOT)
                KG_HINTS.any { lower == it } || LB_HINTS.any { lower == it }
            }.takeIf { it >= 0 }
            ?: return null

        val timeIndex = findColumn(table.header, TIME_HEADERS)?.takeIf { it != dateIndex }
        return ImportMapping(
            dateIndex = dateIndex,
            timeIndex = timeIndex,
            weightIndex = weightIndex,
            unit = detectUnit(table, weightIndex, fallbackUnit),
            bodyFatIndex = findColumn(table.header, BODY_FAT_HEADERS)?.takeIf { it != weightIndex },
            noteIndex = findColumn(table.header, NOTE_HEADERS),
            dayFirstDates = detectDayFirst(table, dateIndex),
        )
    }

    /**
     * Reads the unit from the weight column's own header, since that is where every export
     * puts it: "Weight (kg)", "weight_lb", or just "kg".
     */
    private fun detectUnit(table: CsvTable, weightIndex: Int, fallback: WeightUnit): WeightUnit {
        val header = table.header.getOrNull(weightIndex)?.lowercase(Locale.ROOT).orEmpty()
        val tokens = header.split(' ', '(', ')', '_', '[', ']', '/', '-').filter { it.isNotBlank() }
        return when {
            tokens.any { token -> LB_HINTS.any { token == it } } -> WeightUnit.LB
            tokens.any { token -> KG_HINTS.any { token == it } } -> WeightUnit.KG
            else -> fallback
        }
    }

    /**
     * Decides between day-first and month-first dates by looking for a value that can only be
     * a day. Guessing wrong silently shuffles a year of history into the wrong months, so when
     * nothing in the file settles it, day-first is used and the caller can override.
     */
    private fun detectDayFirst(table: CsvTable, dateIndex: Int): Boolean {
        var sawDayOverTwelve = false
        var sawMonthPositionOverTwelve = false
        table.rows.forEach { row ->
            val raw = table.value(row, dateIndex) ?: return@forEach
            val parts = raw.substringBefore(' ').split('/', '.', '-')
            if (parts.size < 3) return@forEach
            val first = parts[0].toIntOrNull() ?: return@forEach
            val second = parts[1].toIntOrNull() ?: return@forEach
            // An ISO date leads with the year, so it tells us nothing about the other two.
            if (parts[0].length == 4) return@forEach
            if (first > 12) sawDayOverTwelve = true
            if (second > 12) sawMonthPositionOverTwelve = true
        }
        return when {
            sawDayOverTwelve -> true
            sawMonthPositionOverTwelve -> false
            else -> true
        }
    }

    private fun describe(table: CsvTable, mapping: ImportMapping): String = buildString {
        append(table.header.getOrNull(mapping.dateIndex).orEmpty())
        append(" and ")
        append(table.header.getOrNull(mapping.weightIndex).orEmpty())
        append(", read as ")
        append(if (mapping.unit == WeightUnit.KG) "kilograms" else "pounds")
    }

    private fun findColumn(header: List<String>, candidates: List<String>): Int? {
        val lowered = header.map { it.lowercase(Locale.ROOT).trim() }
        // Exact matches first so "date" never loses to "date created".
        candidates.forEach { candidate ->
            val exact = lowered.indexOf(candidate)
            if (exact >= 0) return exact
        }
        candidates.forEach { candidate ->
            val partial = lowered.indexOfFirst { it.contains(candidate) }
            if (partial >= 0) return partial
        }
        return null
    }

    fun import(
        table: CsvTable,
        mapping: ImportMapping,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): ImportResult {
        val entries = ArrayList<WeightEntry>()
        val problems = ArrayList<RowProblem>()
        var skipped = 0

        table.rows.forEachIndexed { index, row ->
            val rawDate = table.value(row, mapping.dateIndex)
            val rawWeight = table.value(row, mapping.weightIndex)
            if (rawDate == null || rawWeight == null) {
                skipped++
                return@forEachIndexed
            }
            val weightValue = parseNumber(rawWeight)
            if (weightValue == null || weightValue <= 0) {
                skipped++
                if (problems.size < 5) {
                    problems += RowProblem(index + 2, RowProblem.Field.WEIGHT, rawWeight)
                }
                return@forEachIndexed
            }
            val rawTime = mapping.timeIndex?.let { table.value(row, it) }
            val dateTime = parseDateTime(rawDate, rawTime, mapping.dayFirstDates, zone)
            if (dateTime == null) {
                skipped++
                if (problems.size < 5) {
                    problems += RowProblem(index + 2, RowProblem.Field.DATE, rawDate)
                }
                return@forEachIndexed
            }

            val grams = UnitConverter.displayToGrams(weightValue, mapping.unit)
            val instant = dateTime.atZone(zone).toInstant()
            val plausibility = WeightPlausibility.problem(grams, instant, now)
            if (plausibility != null) {
                skipped++
                if (problems.size < 5) {
                    problems += when (plausibility) {
                        WeightPlausibility.Problem.WEIGHT ->
                            RowProblem(index + 2, RowProblem.Field.WEIGHT, rawWeight)
                        WeightPlausibility.Problem.TIMESTAMP ->
                            RowProblem(index + 2, RowProblem.Field.DATE, rawDate)
                    }
                }
                return@forEachIndexed
            }
            val bodyFat = mapping.bodyFatIndex
                ?.let { table.value(row, it) }
                ?.let { parseNumber(it) }
                ?.takeIf { it in 1.0..75.0 }

            entries += WeightEntry(
                timestamp = instant,
                zoneOffset = zone.rules.getOffset(instant),
                localDate = dateTime.toLocalDate(),
                grams = grams,
                bodyFatPercent = bodyFat,
                note = mapping.noteIndex?.let { table.value(row, it) },
                source = EntrySource.IMPORT,
                // An import re-run must land on the same rows rather than duplicating them, so
                // the identity is derived from the reading itself instead of a fresh id.
                clientRecordId = importRecordId(instant, grams),
            )
        }
        return ImportResult(entries, skipped, problems)
    }

    private fun importRecordId(instant: Instant, grams: Int): String =
        UUID.nameUUIDFromBytes("import:${instant.toEpochMilli()}:$grams".toByteArray()).toString()

    internal fun parseNumber(raw: String): Double? {
        val cleaned = raw.trim()
            .replace("\"", "")
            .replace(Regex("[^0-9,.\\-]"), "")
        if (cleaned.isEmpty()) return null
        // A comma is a decimal separator across most of Europe, and a thousands separator in
        // the files that also use a full stop. Whichever appears last is the decimal point.
        val lastComma = cleaned.lastIndexOf(',')
        val lastDot = cleaned.lastIndexOf('.')
        val normalised = when {
            lastComma >= 0 && lastDot >= 0 ->
                if (lastComma > lastDot) {
                    cleaned.replace(".", "").replace(',', '.')
                } else {
                    cleaned.replace(",", "")
                }
            lastComma >= 0 -> cleaned.replace(',', '.')
            else -> cleaned
        }
        return normalised.toDoubleOrNull()
    }

    internal fun parseDateTime(
        rawDate: String,
        rawTime: String?,
        dayFirst: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): LocalDateTime? {
        val date = rawDate.trim()

        // Some exports write a unix timestamp rather than a formatted date. It is resolved in
        // the same zone the rest of the import uses, or a file of epoch seconds lands on the
        // wrong day for anyone not sitting on UTC.
        date.toLongOrNull()?.let { number ->
            val instant = runCatching {
                when {
                    number > 100_000_000_000L -> Instant.ofEpochMilli(number)
                    number > 100_000_000L -> Instant.ofEpochSecond(number)
                    else -> null
                }
            }.getOrNull()
            if (instant != null) return LocalDateTime.ofInstant(instant, zone)
        }

        val combined = if (rawTime.isNullOrBlank()) date else "$date ${rawTime.trim()}"
        orderedDateTimePatterns(dayFirst).forEach { pattern ->
            runCatching {
                return LocalDateTime.parse(combined, formatter(pattern))
            }
        }
        runCatching { return LocalDateTime.parse(combined.replace(' ', 'T')) }

        val parsedDate = orderedDatePatterns(dayFirst).firstNotNullOfOrNull { pattern ->
            runCatching { LocalDate.parse(date, formatter(pattern)) }.getOrNull()
        } ?: return null

        val time = rawTime?.let { parseTime(it) } ?: LocalTime.NOON
        return parsedDate.atTime(time)
    }

    private fun parseTime(raw: String): LocalTime? {
        val trimmed = raw.trim()
        listOf("HH:mm:ss", "HH:mm", "h:mm a", "h:mm:ss a").forEach { pattern ->
            runCatching { return LocalTime.parse(trimmed, formatter(pattern)) }
        }
        return null
    }

    /** Ambiguous patterns are tried in the order the file's own dates suggest. */
    private fun orderedDateTimePatterns(dayFirst: Boolean): List<String> =
        if (dayFirst) DATE_TIME_PATTERNS else DATE_TIME_PATTERNS.sortedBy { it.startsWith("dd/") }

    private fun orderedDatePatterns(dayFirst: Boolean): List<String> =
        if (dayFirst) DATE_PATTERNS else DATE_PATTERNS.sortedBy { it.startsWith("dd/") }

    private fun formatter(pattern: String): DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)
}
