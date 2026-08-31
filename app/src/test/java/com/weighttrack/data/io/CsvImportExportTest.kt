package com.weighttrack.data.io

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.io.Csv
import com.weighttrack.core.io.WeightCsvImporter
import com.weighttrack.core.math.UnitConverter
import com.weighttrack.core.model.EntryTag
import com.weighttrack.core.model.WeightEntry
import com.weighttrack.core.model.WeightUnit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

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
