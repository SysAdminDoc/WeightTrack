package com.weighttrack.data.io

import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.BodyMeasurement
import com.weighttrack.core.model.MeasurementType
import com.weighttrack.core.sync.SyncDocument
import com.weighttrack.core.sync.SyncMeasurement
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Whether a carried measurement is still carried after it has been somewhere and come back.
 *
 * A value carried forward is a fact about the last time somebody got the tape out. If a backup
 * or a sync forgets that, the restore turns it into a measurement taken that day, and every
 * reason for keeping the distinction goes with it. Sync was worse than the backup: the receiving
 * phone recorded it as measured and the sending phone never rewrote its own row, so the two
 * disagreed for good.
 */
class CarriedSurvivesRoundTripTest {

    private val carried = BodyMeasurement(
        timestamp = Instant.parse("2026-08-30T08:00:00Z"),
        localDate = LocalDate.of(2026, 8, 30),
        type = MeasurementType.CHEST,
        valueMm = 1_020,
        carried = true,
    )
    private val measured = carried.copy(type = MeasurementType.WAIST, carried = false)

    @Test
    fun `the backup keeps it`() {
        listOf(carried, measured).forEach { original ->
            val there = BackupCodec.measurementToBackup(original)
            val back = BackupCodec.backupToMeasurement(there)!!

            assertThat(back.carried).isEqualTo(original.carried)
            assertThat(back.valueMm).isEqualTo(original.valueMm)
        }
    }

    @Test
    fun `a backup written before sets existed still reads, as measured`() {
        // No `carried` key at all. It has to default to measured rather than refuse the file:
        // those values were measured, which is exactly what the absent field means.
        val old = BackupCodec.json.decodeFromString(
            BackupMeasurement.serializer(),
            """{"timestampUtcMillis":1,"localDate":"2026-08-30","type":"CHEST","valueMm":1020}""",
        )

        assertThat(old.carried).isFalse()
        assertThat(BackupCodec.backupToMeasurement(old)!!.carried).isFalse()
    }

    @Test
    fun `the sync document keeps it`() {
        val sent = SyncMeasurement(
            syncId = "m-1",
            profileSyncId = "p-1",
            timestampUtcMillis = 1,
            localDate = "2026-08-30",
            type = "CHEST",
            valueMm = 1_020,
            carried = true,
            updatedAtUtcMillis = 2,
        )

        val document = SyncDocument(deviceId = "a", writtenAtUtcMillis = 1, measurements = listOf(sent))
        val received = SyncDocument.decode(SyncDocument.encode(document))!!

        assertThat(received.measurements.single().carried).isTrue()
    }

    @Test
    fun `a document from a device that has not been updated still reads`() {
        val withoutTheField = """
            {"app":"WeightTrack","formatVersion":1,"deviceId":"old","writtenAtUtcMillis":1,
             "measurements":[{"syncId":"m-1","profileSyncId":"p-1","timestampUtcMillis":1,
             "localDate":"2026-08-30","type":"CHEST","valueMm":1020,"updatedAtUtcMillis":2}]}
        """.trimIndent()

        val received = SyncDocument.decode(withoutTheField)!!

        assertThat(received.measurements.single().carried).isFalse()
    }
}
