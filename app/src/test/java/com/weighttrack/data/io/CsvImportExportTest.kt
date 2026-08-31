package com.weighttrack.data.io

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.math.UnitConverter
import com.weighttrack.core.model.EntrySource
import com.weighttrack.core.model.EntryTag
import com.weighttrack.core.model.WeightEntry
import com.weighttrack.core.model.WeightUnit
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class CsvParserTest {

    @Test
    fun `a plain comma file parses`() {
        val table = Csv.parse("date,weight\n2026-01-01,80.5\n2026-01-02,80.2")!!
        assertThat(table.header).containsExactly("date", "weight").inOrder()
        assertThat(table.rows).hasSize(2)
        assertThat(table.rows[1][1]).isEqualTo("80.2")
    }

    @Test
    fun `semicolons and tabs are detected`() {
        assertThat(Csv.parse("date;weight\n2026-01-01;80,5")!!.delimiter).isEqualTo(';')
        assertThat(Csv.parse("date\tweight\n2026-01-01\t80.5")!!.delimiter).isEqualTo('\t')
    }

    @Test
    fun `a quoted note containing a comma does not shift the columns`() {
        val table = Csv.parse("date,note,weight\n2026-01-01,\"ate out, then travelled\",80.5")!!
        assertThat(table.rows.single()[1]).isEqualTo("ate out, then travelled")
        assertThat(table.rows.single()[2]).isEqualTo("80.5")
    }

    @Test
    fun `a quoted note containing a newline stays one row`() {
        val table = Csv.parse("date,note,weight\n2026-01-01,\"line one\nline two\",80.5")!!
        assertThat(table.rows).hasSize(1)
        assertThat(table.rows.single()[1]).isEqualTo("line one\nline two")
    }

    @Test
    fun `doubled quotes decode to a single quote`() {
        val table = Csv.parse("date,note\n2026-01-01,\"said \"\"hello\"\"\"")!!
        assertThat(table.rows.single()[1]).isEqualTo("said \"hello\"")
    }

    @Test
    fun `windows line endings and a byte order mark are handled`() {
        val table = Csv.parse("﻿date,weight\r\n2026-01-01,80.5\r\n")!!
        assertThat(table.header.first()).isEqualTo("date")
        assertThat(table.rows).hasSize(1)
    }

    @Test
    fun `blank rows are dropped`() {
        val table = Csv.parse("date,weight\n2026-01-01,80.5\n\n\n")!!
        assertThat(table.rows).hasSize(1)
    }

    @Test
    fun `empty input yields nothing`() {
        assertThat(Csv.parse("")).isNull()
        assertThat(Csv.parse("   ")).isNull()
    }

    @Test
    fun `escaping round trips through the parser`() {
        val values = listOf("plain", "has, comma", "has \"quote\"", "has\nnewline")
        val text = "a,b,c,d\n" + Csv.row(values)
        assertThat(Csv.parse(text)!!.rows.single()).containsExactlyElementsIn(values).inOrder()
    }
}

class WeightCsvImporterTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val now: Instant = Instant.parse("2026-08-31T12:00:00Z")

    private fun importAll(
        text: String,
        fallback: WeightUnit = WeightUnit.KG,
        at: Instant = now,
    ): ImportResult {
        val table = Csv.parse(text)!!
        val mapping = WeightCsvImporter.detect(table, fallback)!!
        return WeightCsvImporter.import(table, mapping, zone, at)
    }

    @Test
    fun `a simple export imports`() {
        val result = importAll("Date,Weight (kg)\n2026-01-01,80.5\n2026-01-02,80.2")
        assertThat(result.entries).hasSize(2)
        assertThat(result.entries.first().grams).isEqualTo(80_500)
        assertThat(result.entries.first().localDate).isEqualTo(LocalDate.of(2026, 1, 1))
        assertThat(result.entries.first().source).isEqualTo(EntrySource.IMPORT)
    }

    @Test
    fun `the unit is read from the weight column header`() {
        val pounds = importAll("Date,Weight (lb)\n2026-01-01,180.0")
        assertThat(pounds.entries.single().grams).isEqualTo(UnitConverter.lbToGrams(180.0))

        val kilos = importAll("Date,weight_kg\n2026-01-01,80.0")
        assertThat(kilos.entries.single().grams).isEqualTo(80_000)
    }

    @Test
    fun `the fallback unit is used when the header says nothing`() {
        val result = importAll("Date,Weight\n2026-01-01,180.0", fallback = WeightUnit.LB)
        assertThat(result.entries.single().grams).isEqualTo(UnitConverter.lbToGrams(180.0))
    }

    @Test
    fun `a separate time column is used`() {
        val result = importAll("Date,Time,Weight (kg)\n2026-01-01,07:30,80.5")
        val entry = result.entries.single()
        assertThat(entry.timestamp.atZone(zone).hour).isEqualTo(7)
        assertThat(entry.timestamp.atZone(zone).minute).isEqualTo(30)
    }

    @Test
    fun `a combined date and time column is used`() {
        val result = importAll("Date,Weight (kg)\n2026-01-01 07:30:00,80.5")
        assertThat(result.entries.single().timestamp.atZone(zone).hour).isEqualTo(7)
    }

    @Test
    fun `european decimal commas are read correctly`() {
        val table = Csv.parse("Datum;Gewicht (kg)\n2026-01-01;80,5")!!
        val mapping = WeightCsvImporter.detect(table, WeightUnit.KG)!!
        assertThat(WeightCsvImporter.import(table, mapping, zone).entries.single().grams)
            .isEqualTo(80_500)
    }

    @Test
    fun `thousands separators do not inflate a reading`() {
        assertThat(WeightCsvImporter.parseNumber("1,234.5")).isWithin(1e-9).of(1234.5)
        assertThat(WeightCsvImporter.parseNumber("1.234,5")).isWithin(1e-9).of(1234.5)
        assertThat(WeightCsvImporter.parseNumber("80.5 kg")).isWithin(1e-9).of(80.5)
    }

    @Test
    fun `a day above twelve settles day-first dates for the whole file`() {
        // 02/01 alone is ambiguous. 13/01 in the same file proves the format is day first,
        // so the earlier row must be read as 2 January, not 1 February.
        val result = importAll("Date,Weight (kg)\n02/01/2026,80.5\n13/01/2026,80.2")
        assertThat(result.entries.map { it.localDate })
            .containsExactly(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 13))
            .inOrder()
    }

    @Test
    fun `a month above twelve settles month-first dates`() {
        val result = importAll("Date,Weight (kg)\n01/02/2026,80.5\n01/13/2026,80.2")
        assertThat(result.entries.map { it.localDate })
            .containsExactly(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 13))
            .inOrder()
    }

    @Test
    fun `unix timestamps are recognised`() {
        val seconds = LocalDate.of(2026, 3, 4).atStartOfDay(zone).toEpochSecond()
        val result = importAll("Date,Weight (kg)\n$seconds,80.5")
        assertThat(result.entries.single().localDate).isEqualTo(LocalDate.of(2026, 3, 4))
    }

    @Test
    fun `body fat and notes are carried across`() {
        val result = importAll(
            "Date,Weight (kg),Body Fat,Comment\n2026-01-01,80.5,22.4,\"felt fine\"",
        )
        val entry = result.entries.single()
        assertThat(entry.bodyFatPercent).isWithin(1e-9).of(22.4)
        assertThat(entry.note).isEqualTo("felt fine")
    }

    @Test
    fun `an impossible body fat figure is dropped rather than stored`() {
        val result = importAll("Date,Weight (kg),Body Fat\n2026-01-01,80.5,0")
        assertThat(result.entries.single().bodyFatPercent).isNull()
    }

    @Test
    fun `unreadable rows are counted and reported, not silently dropped`() {
        val result = importAll(
            "Date,Weight (kg)\n2026-01-01,80.5\nnot-a-date,80.2\n2026-01-03,not-a-number",
        )
        assertThat(result.entries).hasSize(1)
        assertThat(result.skippedRows).isEqualTo(2)
        assertThat(result.problems).hasSize(2)
    }

    @Test
    fun `implausible weights are counted and reported`() {
        val result = importAll(
            "Date,Weight (kg)\n2026-01-01,80\n2026-01-02,0.005\n" +
                "2026-01-03,-1\n2026-01-04,401",
        )

        assertThat(result.entries.map { it.grams }).containsExactly(80_000)
        assertThat(result.skippedRows).isEqualTo(3)
        assertThat(result.problems.map { it.field })
            .containsExactly(
                RowProblem.Field.WEIGHT,
                RowProblem.Field.WEIGHT,
                RowProblem.Field.WEIGHT,
            )
    }

    @Test
    fun `dates before 1970 and over one day ahead are counted and reported`() {
        val result = importAll(
            "Date,Weight (kg)\n1969-12-31,80\n2026-09-01 12:00:00,81\n" +
                "2026-09-01 12:00:01,82\n2094-09-16,83",
        )

        assertThat(result.entries.map { it.grams }).containsExactly(81_000)
        assertThat(result.skippedRows).isEqualTo(3)
        assertThat(result.problems.map { it.field }.toSet())
            .containsExactly(RowProblem.Field.DATE)
    }

    @Test
    fun `a file with no recognisable columns is refused`() {
        val table = Csv.parse("colour,size\nred,large")!!
        assertThat(WeightCsvImporter.detect(table, WeightUnit.KG)).isNull()
    }

    @Test
    fun `exact header matches win over partial ones`() {
        val table = Csv.parse("Date Created,Date,Weight (kg)\n2026-01-01,2026-02-02,80.5")!!
        val mapping = WeightCsvImporter.detect(table, WeightUnit.KG)!!
        assertThat(mapping.dateIndex).isEqualTo(1)
        assertThat(WeightCsvImporter.import(table, mapping, zone).entries.single().localDate)
            .isEqualTo(LocalDate.of(2026, 2, 2))
    }

    @Test
    fun `importing the same file twice does not double the log`() {
        // Identity comes from the reading itself, so a re-import of the same export updates
        // the same rows instead of creating a second copy of a year of history.
        val text = "Date,Weight (kg)\n2026-01-01,80.5\n2026-01-02,80.2"
        val first = importAll(text)
        val second = importAll(text)
        assertThat(first.entries.map { it.clientRecordId })
            .containsExactlyElementsIn(second.entries.map { it.clientRecordId })
    }

    @Test
    fun `different readings get different identities`() {
        val result = importAll("Date,Weight (kg)\n2026-01-01,80.5\n2026-01-02,80.2")
        assertThat(result.entries.map { it.clientRecordId }.toSet()).hasSize(2)
    }

    @Test
    fun `a preview describes what it found without importing`() {
        val table = Csv.parse(
            "Date,Weight (lb)\n2026-01-01,180.0\n2094-09-16,180.0\n2026-01-02,0.01",
        )!!
        val preview = WeightCsvImporter.preview(table, WeightUnit.KG, zone, now)
        assertThat(preview.mapping).isNotNull()
        assertThat(preview.sampleRowCount).isEqualTo(3)
        assertThat(preview.detectedFrom).contains("pounds")
        assertThat(preview.importableRowCount).isEqualTo(1)
        assertThat(preview.skippedRows).isEqualTo(2)
        assertThat(preview.problems.map { it.field })
            .containsExactly(RowProblem.Field.DATE, RowProblem.Field.WEIGHT)
            .inOrder()
    }
}

class ExportRoundTripTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun entry(day: Int, grams: Int, note: String? = null): WeightEntry {
        val date = LocalDate.of(2026, 1, 1).plusDays(day.toLong())
        val instant = date.atTime(7, 30).toInstant(ZoneOffset.UTC)
        return WeightEntry(
            timestamp = instant,
            zoneOffset = ZoneOffset.UTC,
            localDate = date,
            grams = grams,
            bodyFatPercent = 22.5,
            note = note,
            tags = setOf(EntryTag.FASTED),
            clientRecordId = "id-$day",
        )
    }

    @Test
    fun `an export reads back with the same weights`() {
        val entries = listOf(entry(0, 80_500), entry(1, 80_200))
        val csv = WeightCsvExporter.toCsv(entries, zone)
        val table = Csv.parse(csv)!!
        val mapping = WeightCsvImporter.detect(table, WeightUnit.KG)!!
        val reimported = WeightCsvImporter.import(table, mapping, zone)

        assertThat(reimported.entries.map { it.grams }).containsExactly(80_500, 80_200).inOrder()
        assertThat(reimported.skippedRows).isEqualTo(0)
    }

    @Test
    fun `an exported note with a comma survives the round trip`() {
        val csv = WeightCsvExporter.toCsv(listOf(entry(0, 80_500, "ate out, then travelled")), zone)
        val table = Csv.parse(csv)!!
        val mapping = WeightCsvImporter.detect(table, WeightUnit.KG)!!
        val reimported = WeightCsvImporter.import(table, mapping, zone)
        assertThat(reimported.entries.single().note).isEqualTo("ate out, then travelled")
        assertThat(reimported.entries.single().grams).isEqualTo(80_500)
    }

    @Test
    fun `the export carries both units so the file is unambiguous`() {
        val csv = WeightCsvExporter.toCsv(listOf(entry(0, 80_000)), zone)
        val table = Csv.parse(csv)!!
        assertThat(table.header).containsAtLeast("weight_kg", "weight_lb")
        val kg = table.rows.single()[table.columnIndex("weight_kg")].toDouble()
        val lb = table.rows.single()[table.columnIndex("weight_lb")].toDouble()
        assertThat(kg).isWithin(0.001).of(80.0)
        assertThat(lb).isWithin(0.01).of(176.37)
    }

    @Test
    fun `a json backup round trips every field`() {
        val entries = listOf(entry(0, 80_500, "note here"))
        val backup = BackupFile(
            exportedAtUtcMillis = Instant.now().toEpochMilli(),
            entries = entries.map(BackupCodec::entryToBackup),
        )
        val restored = BackupCodec.decode(BackupCodec.encode(backup))!!
        val entry = BackupCodec.backupToEntry(restored.entries.single())!!

        assertThat(entry.grams).isEqualTo(80_500)
        assertThat(entry.note).isEqualTo("note here")
        assertThat(entry.tags).containsExactly(EntryTag.FASTED)
        assertThat(entry.clientRecordId).isEqualTo("id-0")
        assertThat(entry.bodyFatPercent).isWithin(1e-9).of(22.5)
    }

    @Test
    fun `the settings in a backup are the ones the importer reads`() {
        // They were written on the way out from the first version and never read on the way back,
        // so restoring on a new phone quietly lost units, theme, height and the rest. This holds
        // the file format and the importer to the same field names.
        val importer = java.io.File(
            "src/main/java/com/weighttrack/data/io/BackupService.kt",
        ).readText()
        val restoring = importer.substringAfter("backup.settings?.let").substringBefore("ImportSummary(")

        listOf(
            "weightUnit", "lengthUnit", "themeMode", "heightMm",
            "sex", "birthYear", "activityLevel", "trendWindowDays", "milestoneStepGrams",
        ).forEach { field ->
            assertThat(restoring).contains(field)
        }
    }

    @Test
    fun `a backup written by a newer version still restores`() {
        val text = """
            {
              "app": "WeightTrack",
              "formatVersion": 99,
              "exportedAtUtcMillis": 1,
              "somethingNew": {"nested": true},
              "entries": [
                {
                  "timestampUtcMillis": 1767255000000,
                  "zoneOffsetSeconds": 0,
                  "localDate": "2026-01-01",
                  "grams": 80500,
                  "clientRecordId": "abc",
                  "unknownField": 5
                }
              ]
            }
        """.trimIndent()
        val restored = BackupCodec.decode(text)
        assertThat(restored).isNotNull()
        assertThat(BackupCodec.backupToEntry(restored!!.entries.single())!!.grams).isEqualTo(80_500)
    }

    @Test
    fun `a corrupt backup is rejected rather than half applied`() {
        assertThat(BackupCodec.decode("not json at all")).isNull()
        assertThat(BackupCodec.decode("")).isNull()
    }

    @Test
    fun `a row with an impossible weight is dropped on restore`() {
        val bad = BackupEntry(
            timestampUtcMillis = 1,
            zoneOffsetSeconds = 0,
            localDate = "2026-01-01",
            grams = 0,
            clientRecordId = "x",
        )
        assertThat(BackupCodec.backupToEntry(bad)).isNull()
    }

    @Test
    fun `a row with an unreadable date is dropped on restore`() {
        val bad = BackupEntry(
            timestampUtcMillis = 1,
            zoneOffsetSeconds = 0,
            localDate = "not-a-date",
            grams = 80_000,
            clientRecordId = "x",
        )
        assertThat(BackupCodec.backupToEntry(bad)).isNull()
    }
}
