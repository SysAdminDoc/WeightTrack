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

    @Test
    fun `an exported row carries the day it was recorded on, whatever the region says`() {
        // Where a week starts decides how days are gathered up and nothing else, so an export
        // must not move when somebody changes their region. Every stored date is asserted
        // individually rather than the two files being compared, because two identical wrong
        // files would satisfy a comparison.
        val entries = (0..13).map { entry(day = it + 1, grams = 80_000 - it * 100) }
        val original = java.util.Locale.getDefault()
        try {
            listOf(java.util.Locale.US, java.util.Locale.GERMANY).forEach { locale ->
                java.util.Locale.setDefault(locale)
                val text = WeightCsvExporter.toCsv(entries, zone)
                val dates = Csv.parse(text)!!.rows.map { it.first() }
                assertThat(dates).isEqualTo(entries.map { it.localDate.toString() })
            }
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    @Test
    fun `an export says whose reading each row is when it is asked to`() {
        // A household's weekly spreadsheet used to carry whichever person happened to be open,
        // with nothing in the file saying who.
        val hers = entry(day = 1, grams = 62_000)
        val his = entry(day = 2, grams = 84_000)
        val owners = mapOf(hers.clientRecordId to 1L, his.clientRecordId to 2L)

        val table = Csv.parse(
            WeightCsvExporter.toCsv(
                entries = listOf(hers, his),
                zone = zone,
                profileNames = mapOf(1L to "Sam", 2L to "Alex"),
                profileOf = { owners[it.clientRecordId] },
            ),
        )!!

        val column = table.header.indexOf("profile")
        assertThat(column).isAtLeast(0)
        assertThat(table.rows.map { it[column] }).containsExactly("Sam", "Alex").inOrder()
    }

    @Test
    fun `an export of one person leaves the profile column empty rather than guessing`() {
        val table = Csv.parse(WeightCsvExporter.toCsv(listOf(entry(day = 1, grams = 80_000)), zone))!!

        val column = table.header.indexOf("profile")
        assertThat(table.rows.single()[column]).isEmpty()
    }
}